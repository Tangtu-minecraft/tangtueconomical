package com.tangtu.kjbb.mixin;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderRequestPacket;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.tangtu.kjbb.BankBridge;
import com.tangtu.kjbb.ShopEscrow;
import com.tangtu.kjbb.ShopKline;
import com.tangtu.kjbb.ShopLedger;
import com.tangtu.kjbb.ShopPricing;
import com.tangtu.kjbb.ShopPrices;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家对玩家交易所（货到付款）。
 *
 * 下单时：
 *   ① 扣买家总额
 *   ② 按托管账本 FIFO 算出「每个卖家应得多少」
 *   ③ 写一条 PENDING 托管记录（**先不付钱**）
 *
 * 之后 KubeJS 轮询 /shopaddr 登记的收货箱：
 *   箱子里出现对应物品 → ShopBridge.release(id) → 钱给卖家
 *   超时没出现         → ShopBridge.expire(...)  → 钱退买家
 */
@Mixin(value = PackageOrderRequestPacket.class, remap = false)
public abstract class PackageOrderRequestPacketMixin {

    @Shadow
    @Final
    private PackageOrderWithCrafts order;

    @Inject(
            method = "applySettings(Lnet/minecraft/server/level/ServerPlayer;"
                    + "Lcom/simibubi/create/content/logistics/stockTicker/StockTickerBlockEntity;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void kjbb$chargeForOrder(ServerPlayer player, StockTickerBlockEntity ticker, CallbackInfo ci) {
        try {
            if (order == null || order.isEmpty()) {
                return;
            }
            List<BigItemStack> stacks = order.stacks();
            if (stacks == null || stacks.isEmpty()) {
                return;
            }

            // ★ 出票模式：手持空白单据下单（或用空白单据右键过发报机）
            boolean billMode = com.tangtu.kjbb.BillMode.consume(player) || holdsBlankForm(player);
            System.out.println("[TangtuEconomical] 下单检查 玩家=" + player.getScoreboardName()
                    + " 手持=" + player.getMainHandItem()
                    + " 出票模式=" + billMode);
            if (!billMode) {
                // 空手下单 = 原版行为，不进入我们的买卖流程
                System.out.println("[TangtuEconomical] 非出票模式，放行原版下单："
                        + player.getScoreboardName());
                return;
            }
            // 出票模式：
            // ① 先关掉玩家当前的容器界面（发报机界面），否则改背包会和菜单槽位同步打架
            try {
                player.closeContainer();
            } catch (Throwable ignored) {
                // 忽略
            }
            // ② 延迟一 tick 再动背包/容器
            final List<BigItemStack> snapshot = stacks;
            try {
                player.server.execute(() -> {
                    try {
                        kjbb$issueBills(player, ticker, snapshot);
                    } catch (Throwable ex) {
                        System.out.println("[TangtuEconomical] 出票延迟执行异常: " + ex);
                    }
                });
            } catch (Throwable ex) {
                kjbb$issueBills(player, ticker, snapshot);
            }
            ci.cancel();
            return;
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 下单拦截异常（放行订单）: " + t);
        }
    }

    /**
     * 【卖货 / 存仓出票】—— 只允许从【发报机链接的仓库】提交，不动玩家背包。
     *
     * 玩家在发报机界面选好货 → 点「下单」：
     *   ① 检查仓库（Create 网络汇总）里够不够
     *   ② 从仓库扣货（打包机的目标库存）
     *   ③ 生成仓单（凭证），绑定提货点
     *
     * 之后凭票兑付时：把货放回仓库 → 广播包裹请求 → 由物流送到提货点。
     */
    /**
     * 【提单】—— 必须由发报机真的打包发货，才出具提单。
     *
     *   ① 校验仓库（发报机链接的网络）里够不够
     *   ② 向发报机广播包裹请求 → Create 打包 + 发货到提货点
     *   ③ 成功才出提单：记录 发报机坐标 / 收货地址 / 物品 / 数量 / 签发人
     *   ④ 提单只是【证明文件】：兑付时只查状态，不再重复发货
     */
    private static void kjbb$issueBills(ServerPlayer player, StockTickerBlockEntity ticker,
                                        List<BigItemStack> stacks) {
        try {
            String seller = nameOf(player);
            if (seller.isEmpty()) {
                message(player, "§c[提单] 读不到你的身份");
                return;
            }
            if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                message(player, "§c[提单] 只能在服务端执行");
                return;
            }
            net.minecraft.core.BlockPos tickerPos = posOf(ticker);

            // 提货点（收货地址）
            String playerName = com.tangtu.kjbb.AddressBook.addressOf(player);
            com.tangtu.kjbb.PortRegistry.Entry reg =
                    com.tangtu.kjbb.PortRegistry.pick(tickerPos, playerName);
            com.tangtu.kjbb.AddressBook.PortInfo port;
            String addr;
            if (reg != null) {
                port = new com.tangtu.kjbb.AddressBook.PortInfo(level, reg.pos(), reg.address());
                addr = reg.address();
            } else {
                port = com.tangtu.kjbb.AddressBook.findPort(level, tickerPos, playerName);
                addr = (port != null && port.address() != null && !port.address().isEmpty())
                        ? port.address() : playerName;
            }

            long now = player.level().getGameTime();
            long expiresAt = now + com.tangtu.kjbb.item.BillData.LIFETIME_TICKS;
            int ok = 0;
            int failed = 0;
            StringBuilder detail = new StringBuilder();

            for (BigItemStack big : stacks) {
                if (big == null || big.stack == null) {
                    continue;
                }
                String itemId = itemIdOf(big.stack);
                if (itemId.isEmpty()) {
                    continue;
                }
                int want = Math.max(1, big.count);
                net.minecraft.world.item.Item it =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                net.minecraft.resources.ResourceLocation.tryParse(itemId));
                if (it == null) {
                    continue;
                }
                // ① 仓库够不够
                int inWarehouse = com.tangtu.kjbb.TickerStock.networkCount(level, tickerPos,
                        new net.minecraft.world.item.ItemStack(it));
                if (inWarehouse < want) {
                    message(player, "§c[提单] 仓库里 " + itemId + " 只有 " + inWarehouse
                            + " 个，出不了货");
                    failed++;
                    continue;
                }
                // ② 让发报机真的打包发货
                boolean sent = com.tangtu.kjbb.TickerShip.request(level, tickerPos, itemId, want,
                        addr);
                if (!sent) {
                    message(player, "§c[提单] 发报机没能打包发货（检查打包机/链接站），未出票");
                    failed++;
                    continue;
                }
                // ③ 成功才出提单
                long serial = com.tangtu.kjbb.item.BillRegistry.register(
                        itemId, want, seller, expiresAt,
                        tickerPos.getX(), tickerPos.getY(), tickerPos.getZ(), addr);
                com.tangtu.kjbb.item.BillRegistry.setStatus(serial,
                        com.tangtu.kjbb.item.BillData.STATUS_SHIPPED);
                net.minecraft.world.item.ItemStack bill =
                        new net.minecraft.world.item.ItemStack(
                                com.tangtu.kjbb.item.KjbbItems.BILL.get());
                com.tangtu.kjbb.item.KjbbItems.setBill(bill,
                        new com.tangtu.kjbb.item.BillData(itemId, want, seller, serial,
                                com.tangtu.kjbb.item.BillData.STATUS_SHIPPED,
                                expiresAt, tickerPos.getX(), tickerPos.getY(), tickerPos.getZ(),
                                addr));
                com.tangtu.kjbb.item.BillItem.setBearer(bill,
                        com.tangtu.kjbb.BillMode.bearerPref(player));
                if (!player.getInventory().add(bill)) {
                    player.drop(bill, false);
                }
                if (detail.length() > 0) {
                    detail.append("，");
                }
                detail.append(itemId).append(" ×").append(want);
                ok++;
                System.out.println("[TangtuEconomical] 提单 No." + serial + " " + itemId + " x" + want
                        + " 发报机=" + tickerPos + " 收货地址=" + addr);
            }

            if (ok == 0) {
                message(player, "§c[提单] 没有出成任何提单");
                return;
            }
            message(player, "§a[提单] 发报机已打包发货 " + ok + " 项，提单已出具"
                    + (failed > 0 ? "（" + failed + " 项失败）" : ""));
            message(player, "§7" + detail);
            message(player, "§7发货发报机：§f" + tickerPos.getX() + ", " + tickerPos.getY() + ", "
                    + tickerPos.getZ() + "　收货地址：§f" + addr);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 出提单异常: " + t);
        }
    }

    /** 背包塞不进去时退回 */
    private static void giveBack(ServerPlayer player, net.minecraft.world.item.Item it, int count) {
        int left = count;
        while (left > 0) {
            int n = Math.min(left, Math.max(1, it.getDefaultMaxStackSize()));
            net.minecraft.world.item.ItemStack st = new net.minecraft.world.item.ItemStack(it, n);
            if (!player.getInventory().add(st)) {
                player.drop(st, false);
            }
            left -= n;
        }
    }

    /** 手上（主手或副手）是否拿着空白单据 */
    private static boolean holdsBlankForm(ServerPlayer player) {
        try {
            var item = com.tangtu.kjbb.item.KjbbItems.BLANK_FORM.get();
            return player.getMainHandItem().is(item) || player.getOffhandItem().is(item);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反射取方块坐标（直接调用会因父类不可访问而编译失败） */
    private static net.minecraft.core.BlockPos posOf(Object be) {
        try {
            for (Method m : be.getClass().getMethods()) {
                if ("getBlockPos".equals(m.getName()) && m.getParameterCount() == 0) {
                    Object v = m.invoke(be);
                    if (v instanceof net.minecraft.core.BlockPos bp) {
                        return bp;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return net.minecraft.core.BlockPos.ZERO;
    }

    // =====================================================================
    //  反射工具
    // =====================================================================

    /** 读发报机所在网络的某物品库存 */
    private static long stockOf(Object ticker, Object itemStack) {
        try {
            Object summary = null;
            for (Method m : ticker.getClass().getMethods()) {
                if ("getAccurateSummary".equals(m.getName()) && m.getParameterCount() == 0) {
                    summary = m.invoke(ticker);
                    break;
                }
            }
            if (summary == null) {
                return 0L;
            }
            for (Method m : summary.getClass().getMethods()) {
                if ("getCountOf".equals(m.getName()) && m.getParameterCount() == 1) {
                    Object v = m.invoke(summary, itemStack);
                    return v instanceof Number ? ((Number) v).longValue() : 0L;
                }
            }
        } catch (Throwable ignored) {
            return 0L;
        }
        return 0L;
    }

    private static String itemIdOf(Object itemStack) {
        try {
            Method getItem = itemStack.getClass().getMethod("getItem");
            Object item = getItem.invoke(itemStack);
            if (item == null) {
                return "";
            }
            Class<?> regCls = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object registry = regCls.getField("ITEM").get(null);
            if (registry == null) {
                return "";
            }
            Method getKey = registry.getClass().getMethod("getKey", Object.class);
            Object rl = getKey.invoke(registry, item);
            return rl == null ? "" : String.valueOf(rl);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 玩家名（账本/托管里用名字，方便脚本对照） */
    private static String nameOf(Object player) {
        try {
            Method m = player.getClass().getMethod("getScoreboardName");
            Object v = m.invoke(player);
            if (v != null && !String.valueOf(v).isEmpty()) {
                return String.valueOf(v);
            }
        } catch (Throwable ignored) {
            // 换一种
        }
        try {
            Method m = player.getClass().getMethod("getStringUUID");
            Object v = m.invoke(player);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void message(Object player, String text) {
        try {
            Class<?> compCls = Class.forName("net.minecraft.network.chat.Component");
            Object comp = compCls.getMethod("literal", String.class).invoke(null, text);
            if (comp == null) {
                return;
            }
            for (Method m : player.getClass().getMethods()) {
                if (!"sendSystemMessage".equals(m.getName())) {
                    continue;
                }
                if (m.getParameterCount() != 1) {
                    continue;
                }
                m.invoke(player, comp);
                return;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
