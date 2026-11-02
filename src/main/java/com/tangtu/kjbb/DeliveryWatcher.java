package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 到货放款：每 5 秒检查一次待收货订单，买家的提货点里出现了 Create 包裹
 * 就把托管里的钱放给卖家（纯 mod 实现，不依赖 KubeJS）。
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.GAME)
public final class DeliveryWatcher {

    private static int tickCounter = 0;
    private static int purgeCounter = 0;

    /** 买家订单超时退款：30 分钟（PENDING 没人供货） */
    public static final long ORDER_TIMEOUT_MS = 30L * 60_000L;
    /** 已供货但迟迟不到货：2 小时后提醒（不自动退款，避免货钱两失） */
    public static final long SUPPLIED_WARN_MS = 2L * 60L * 60_000L;

    private DeliveryWatcher() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // 离线邮寄队列：区块加载后补投
        try {
            com.tangtu.kjbb.MailQueue.tick(event.getServer());
            // 机器人下的单：2 个游戏日无人供货就撤单退款
            try {
                long dayMs = 24_000L * 50L;          // 1 游戏日 = 24000 tick × 50ms
                com.tangtu.kjbb.OrderBook.cancelStaleBotOrders(
                        com.tangtu.kjbb.ServerClock.now(), 2L * dayMs);
            } catch (Throwable ignored) {
                // ignore
            }
            com.tangtu.kjbb.TradeOrder.tick();
        } catch (Throwable ignored) {
            // ignore
        }

        // 游戏时间流速（/billtime speed N）
        try {
            int tspeed = com.tangtu.kjbb.TimeSpeed.get();
            var srv = event.getServer();
            if (tspeed > 1 && srv != null && srv.overworld() != null) {
                var ow = srv.overworld();
                ow.setDayTime(ow.getDayTime() + (tspeed - 1));
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // 每 tick 都过一趟：脏标记攒够 saveIntervalTicks 就批量落盘，其余时间零 I/O
        long perfTick = com.tangtu.kjbb.Perf.begin();
        try {
            com.tangtu.kjbb.DirtySave.tick();
        } finally {
            com.tangtu.kjbb.Perf.end("tick.dirtySave", perfTick);
        }
        if (++tickCounter < 100) {
            return;
        }
        tickCounter = 0;
        MinecraftServer server = event.getServer();
        com.tangtu.kjbb.BotLedger.flushIfDirty();
        com.tangtu.kjbb.OrderBook.releaseStaleAccepted(10L * 60_000L);
        com.tangtu.kjbb.TicketCollector.tick(server);
        com.tangtu.kjbb.BasePriceLongRun.tick(ServerClock.now());
        com.tangtu.kjbb.StorageFee.tick(server);
        checkOrderTimeouts(server);
        // K 线只在一分钟周期结束时推送（见 KlineAggregator.onTrade 的周期滚动）
        long perfMarket = com.tangtu.kjbb.Perf.begin();
        try {
            com.tangtu.kjbb.MarketBots.tick(server);
        } finally {
            com.tangtu.kjbb.Perf.end("market.bots", perfMarket);
        }
        com.tangtu.kjbb.RetailWatcher.tick(server);
        com.tangtu.kjbb.LaborBots.tick(server);
        com.tangtu.kjbb.KlineAggregator.tick();
        com.tangtu.kjbb.LogHousekeeping.tick();
        com.tangtu.kjbb.MarketPrice.anchorTick();
        if (++purgeCounter >= 12) {
            purgeCounter = 0;
            purgeFinished();
        }
        SealWatcher.check(server);
        recycleShippedBills(server);
        for (OrderWatch.Row row : OrderWatch.all()) {
            try {
                check(server, row);
            } catch (Throwable t) {
                System.out.println("[TangtuEconomical] 到货检查异常: " + t);
            }
        }
    }

    /** 已完成/已取消的订单、挂单，保留 10 分钟后清掉 */
    private static void purgeFinished() {
        long cutoff = ServerClock.now() - 10L * 60_000L;
        int expired = OrderBook.cancelExpired(ServerClock.now(), 24L * 3600_000L);
        if (expired > 0) {
            System.out.println("[TangtuEconomical] \u53d6\u6d88\u8fc7\u671f\u4e70\u5355 " + expired
                    + " \u6761\uff0c\u5df2\u9000\u6b3e");
        }
        int o = OrderBook.purge(cutoff);
        int f = OfferBook.purge(cutoff);
        int tk = TicketMarket.purge(cutoff);
        if (o > 0 || f > 0) {
            System.out.println("[TangtuEconomical] 定时清理：订单 " + o + " 条，挂单 " + f
                    + " 条，票据 " + tk + " 条");
        }
    }

    /** 提单：包裹到达指定地址后，把提单物品回收掉 */
    private static void recycleShippedBills(MinecraftServer server) {
        for (String[] pair : com.tangtu.kjbb.item.BillRegistry.shippedList()) {
            long serial;
            try {
                serial = Long.parseLong(pair[0]);
            } catch (Throwable t) {
                continue;
            }
            String addr = pair[1];
            BlockPos port = com.tangtu.kjbb.AddressBook.get(addr);
            if (port == null) {
                var entries = com.tangtu.kjbb.PortRegistry.all();
                for (var e : entries) {
                    if (e.address() != null && e.address().equalsIgnoreCase(addr)) {
                        port = e.pos();
                        break;
                    }
                }
            }
            if (port == null) {
                continue;
            }
            ServerLevel level = server.overworld();
            if (level == null || !level.isLoaded(port)) {
                continue;
            }
            if (!hasPackage(level, port)) {
                continue;
            }
            // 到货：回收所有玩家背包里的这张票
            int removed = 0;
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                var inv = sp.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    var st = inv.getItem(i);
                    if (st == null || st.isEmpty()) {
                        continue;
                    }
                    if (st.getItem() != com.tangtu.kjbb.item.KjbbItems.BILL.get()) {
                        continue;
                    }
                    var d = com.tangtu.kjbb.item.KjbbItems.getBill(st);
                    if (d == null || d.serial() != serial) {
                        continue;
                    }
                    st.shrink(1);
                    removed++;
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "§a[提单] 包裹已到达「" + addr + "」，这张提单已完成使命并被回收"), false);
                }
                sp.containerMenu.broadcastChanges();
            }
            com.tangtu.kjbb.item.BillRegistry.markDelivered(serial);
            System.out.println("[TangtuEconomical] 提单到货回收 No." + serial + " 地址=" + addr
                    + " 回收数量=" + removed);
        }
    }

    /** 订单超时：PENDING 超 30 分钟 → 全额退款给买家并取消 */
    private static void checkOrderTimeouts(MinecraftServer server) {
        long now = ServerClock.now();
        for (OrderBook.Row r : OrderBook.byStatus(OrderBook.PENDING)) {
            if (now - r.created < ORDER_TIMEOUT_MS) {
                continue;
            }
            BankBridge.addMoney(r.buyer, r.total);
            OrderBook.setStatus(r.id, OrderBook.CANCELLED, null);
            System.out.println("[TangtuEconomical] 订单超时退款 #" + r.id + " 买家=" + r.buyer
                    + " 金额=" + r.total);
            ServerPlayer buyer = server.getPlayerList().getPlayerByName(r.buyer);
            if (buyer != null) {
                buyer.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "§e[退款] 订单 #" + r.id + " 超时无人供货，" + r.total + " 唐兔币已退回余额"), false);
            }
        }
        for (OrderBook.Row r : OrderBook.byStatus(OrderBook.SUPPLIED)) {
            // 只在超过阈值后的 1 分钟窗口里提醒一次，避免每 tick 刷屏
                long age = now - r.created;
                if (age < SUPPLIED_WARN_MS || age > SUPPLIED_WARN_MS + 60_000L) {
                continue;
            }
            ServerPlayer buyer = server.getPlayerList().getPlayerByName(r.buyer);
            if (buyer != null) {
                buyer.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "§c[提醒] 订单 #" + r.id + " 已发货但还没到货，检查一下物流/提货点"), false);
            }
        }
    }

    private static void check(MinecraftServer server, OrderWatch.Row row) {
        ServerPlayer buyer = server.getPlayerList().getPlayerByName(row.buyer());
        ServerLevel level = buyer != null ? buyer.serverLevel() : server.overworld();
        if (level == null) {
            return;
        }
        BlockPos port = AddressBook.get(row.address());
        if (port == null) {
            // 兜底：订单里地址错了，就用买家自己的绑定地址
            port = AddressBook.get(row.buyer());
        }
        if (port == null) {
            // 没绑定地址 → 用共享登记表
            var entries = PortRegistry.of(row.buyer());
            for (var e : entries) {
                port = e.pos();
                break;
            }
        }
        if (port == null || !level.isLoaded(port)) {
            return;
        }
        if (hasPackage(level, port)) {
            var order = com.tangtu.kjbb.OrderBook.get(row.escrowId());
            long paid = 0L;
            String seller = "";
            if (order != null) {
                paid = order.total;
                seller = order.seller == null ? "" : order.seller;
                if (!seller.isEmpty() && paid > 0) {
                    com.tangtu.kjbb.Pay.credit(seller, paid);
                }
                com.tangtu.kjbb.OrderBook.setStatus(order.id, com.tangtu.kjbb.OrderBook.DONE, null);
            }
            OrderWatch.remove(row.escrowId());

            // 提单核销：包裹到了 → 买家手里的提单消失 + 提示
            try {
                long serial = com.tangtu.kjbb.OrderBillLink.serialOfOrder(row.escrowId());
                if (serial >= 0 && buyer != null) {
                    var inv = buyer.getInventory();
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        var st = inv.getItem(i);
                        if (st == null || st.isEmpty()) {
                            continue;
                        }
                        var bd = com.tangtu.kjbb.item.KjbbItems.getBill(st);
                        if (bd != null && bd.serial() == serial) {
                            st.shrink(1);
                            buyer.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7e[\u63d0\u5355] \u8ba2\u5355 #" + row.escrowId()
                                            + " \u7684\u5305\u88f9\u5df2\u5230\u8fbe\uff0c\u63d0\u5355\u5df2\u81ea\u52a8\u6838\u9500"), false);
                            break;
                        }
                    }
                    com.tangtu.kjbb.OrderBillLink.removeSerial(serial);
                }
            } catch (Throwable ignored) {
                // ignore
            }
            System.out.println("[TangtuEconomical] 到货放款 #" + row.escrowId() + " 买家=" + row.buyer()
                    + " 物品=" + row.itemId() + " x" + row.count() + " 卖家=" + seller
                    + " 支付=" + paid);
            if (buyer != null) {
                buyer.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "§a[到货] 订单 #" + row.escrowId() + " 的包裹已送到提货点「"
                                + row.address() + "」" + (paid > 0 ? "，货款 " + paid + " 唐兔币已转给卖家" : "")), false);
            }
            if (paid > 0 && !seller.isEmpty()) {
                var sellerPlayer = server.getPlayerList().getPlayerByName(seller);
                if (sellerPlayer != null) {
                    sellerPlayer.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "§a[收款] 订单 #" + row.escrowId() + " 已到货，" + paid + " 唐兔币已到账"), false);
                }
            }
        }
    }

    private static boolean hasPackage(ServerLevel level, BlockPos port) {
        // 目标格 + 周围 2 格范围（含地面上的包裹实体）
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    var p2 = port.offset(dx, dy, dz);
                    if (checkAt(level, p2)) {
                        return true;
                    }
                }
            }
        }
        // 地面上的 Create 包裹（玩家手动丢的也算）
        try {
            var box = new net.minecraft.world.phys.AABB(
                    port.getX() - 2.5, port.getY() - 1.5, port.getZ() - 2.5,
                    port.getX() + 3.5, port.getY() + 3.5, port.getZ() + 3.5);
            for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
                var st = e.getItem();
                if (st == null || st.isEmpty()) {
                    continue;
                }
                var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
                if (rl != null && rl.getPath().contains("package")) {
                    System.out.println("[TangtuEconomical] 在提货点附近发现地面包裹 " + rl + " @" + e.blockPosition());
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return false;
    }

    private static boolean checkAt(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) {
            return false;
        }
        var be = level.getBlockEntity(p);
        if (be == null) {
            return false;
        }
        IItemHandler h = TickerStock.handlerAt(level, p);
        if (h == null) {
            return false;
        }
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack st = h.getStackInSlot(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (rl != null && rl.getPath().contains("package")) {
                return true;
            }
        }
        return false;
    }
}
