package com.tangtu.kjbb;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** 玩家右键方块时，若可能是零售方块就登记一次观察 */
@EventBusSubscriber(modid = "kjbankbridge")
public class RetailInteraction {

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) {
            return;
        }
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        var be = level.getBlockEntity(event.getPos());
        if (be == null) {
            return;
        }
        String cls = be.getClass().getName().toLowerCase();
        if (cls.contains("vendor") || cls.contains("salepoint") || cls.contains("tablecloth")) {
            var cfg = BotConfig.get();
            if (cfg.autoConvertCoins) {
                long old = com.tangtu.kjbb.Coins.convertOldToGear(sp);
                if (old > 0) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "§7已把旧币换成齿轮币（面值 " + old + "）"), false);
                }
            }
            if (cfg.autoCashOut > 0) {
                long wallet = com.tangtu.kjbb.Wallet.money(sp.getScoreboardName());
                long price = readPrice(be);
                // 预支 = min(钱包, 上限)：只要钱包有钱，任何价格的消费都覆盖得住
                //   （剩下的部分成交后会自动换回钱包，未购买则全额退回）
                long want = Math.min(cfg.autoCashOut, wallet);
                long got = want > 0
                        ? com.tangtu.kjbb.Coins.withdrawWalletToCoins(sp, want) : 0L;
                if (got > 0) {
                    com.tangtu.kjbb.RetailWatcher.addCashCredit(sp.getUUID(), got);
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7e[商店] 预支 \u00a7e" + got
                                    + " 唐兔币\u00a7e（价格 "
                                    + (price > 0 ? price : "?")
                                    + "，未购买自动退回）"), false);
                } else if (price > 0) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a77[商店] 余额不足：钱包为空且没有硬币"
                                    + "（价格 " + price + " 唐兔币）"),
                            false);
                }
            }
        }
        RetailWatcher.onInteract(event.getPos(), be, sp.getUUID());
    }

    /**
     * 反射读零售方块的标价（getTotalPrice）。
     * 读不到就返回 0 -> 不预支，避免把钱包掏空。
     */
    private static long readPrice(net.minecraft.world.level.block.entity.BlockEntity be) {
        for (java.lang.reflect.Method m : be.getClass().getMethods()) {
            if (m.getName().equals("getTotalPrice") && m.getParameterCount() == 0) {
                try {
                    Object v = m.invoke(be);
                    if (v instanceof Integer i) {
                        return i.longValue();
                    }
                } catch (Throwable ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }
}
