package com.tangtu.kjbb;

import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystoneTeleportContext;
import net.blay09.mods.waystones.api.event.WaystoneActivatedEvent;
import net.blay09.mods.waystones.api.event.WaystoneTeleportEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 传送石碑的收费钩子
 *
 *   ① 激活石碑（买石碑）：一次性收费
 *   ② 石碑之间传送：按距离收费，钱不够就取消传送
 *
 * 全部通过 Waystones 的 API 事件实现，不侵入对方代码。
 */
public final class WaystoneFee {

    private WaystoneFee() {
    }

    /** 激活石碑的价格 */
    public static long ACTIVATE_PRICE = 500L;
    /** 传送基础价 */
    public static long TELEPORT_BASE = 0L;
    /** 每格距离的价钱 */
    public static long TELEPORT_PER_BLOCK = 1L;
    /** 跨维度一口价 */
    public static long TELEPORT_DIMENSION_FEE = 6400L;

    private static void refresh() {
        try {
            var c = BotConfig.get();
            ACTIVATE_PRICE = Math.max(0L, c.waystoneActivatePrice);
            TELEPORT_BASE = Math.max(0L, c.waystoneTeleportBase);
            TELEPORT_PER_BLOCK = Math.max(0L, c.waystoneTeleportPerBlock);
            TELEPORT_DIMENSION_FEE = Math.max(0L, c.waystoneTeleportDimension);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** ① 激活石碑 = 买石碑 */
    @SubscribeEvent
    public static void onActivated(WaystoneActivatedEvent event) {
        refresh();
        if (ACTIVATE_PRICE <= 0L) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer sp)) {
            return;
        }
        long have = 0L;
        try {
            have = BankBridge.money(sp.getScoreboardName());
        } catch (Throwable ignored) {
            // ignore
        }
        if (have < ACTIVATE_PRICE) {
            sp.displayClientMessage(Component.literal(
                    "\u00a7c[传送] \u6fc0\u6d3b\u77f3\u7891\u9700\u8981 " + ACTIVATE_PRICE
                            + " \u5510\u5154\u5e01\uff08\u4f60\u53ea\u6709 " + have + "\uff09"), false);
            return;
        }
        try {
            com.tangtu.kjbb.Pay.pay(sp, ACTIVATE_PRICE);
            com.tangtu.kjbb.RegulatorOps.creditTreasury(ACTIVATE_PRICE,
                    "\u77f3\u7891\u6fc0\u6d3b");
            BotTrades.log(sp.getScoreboardName(), "-", "\u4f20\u9001\u77f3\u7891\u6fc0\u6d3b",
                    1, ACTIVATE_PRICE, "\u77f3\u7891");
            sp.displayClientMessage(Component.literal(
                    "\u00a7a[传送] \u5df2\u82b1 " + ACTIVATE_PRICE + " \u5510\u5154\u5e01\u6fc0\u6d3b\u77f3\u7891"), false);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** ② 石碑之间传送 */
    @SubscribeEvent
    public static void onTeleportPre(WaystoneTeleportEvent.Pre event) {
        refresh();
        WaystoneTeleportContext ctx = event.getContext();
        if (ctx == null) {
            return;
        }
        Entity e = ctx.getEntity();
        if (!(e instanceof ServerPlayer sp)) {
            return;
        }
        Waystone to = ctx.getTargetWaystone();
        if (to == null) {
            return;
        }
        long fee = TELEPORT_BASE;
        boolean crossDim = false;
        BlockPos fromPos = null;
        ResourceKey<Level> fromDim = null;
        var fromOpt = ctx.getFromWaystone();
        if (fromOpt != null && fromOpt.isPresent()) {
            Waystone from = fromOpt.get();
            fromPos = from.getPos();
            fromDim = from.getDimension();
        } else {
            fromPos = sp.blockPosition();
            fromDim = sp.level().dimension();
        }
        if (fromDim != null && !fromDim.equals(to.getDimension())) {
            // 跨维度：一口价
            crossDim = true;
            fee = TELEPORT_DIMENSION_FEE;
        } else if (fromPos != null) {
            // 同维度：每格 1 币（最低 1 币）
            double dist = Math.sqrt(fromPos.distSqr(to.getPos()));
            fee = Math.max(1L, Math.round(dist * TELEPORT_PER_BLOCK));
        }
        if (!crossDim && fee <= 0L) {
            fee = Math.max(1L, TELEPORT_BASE);
        }
        if (fee <= 0L) {
            return;
        }
        long have = 0L;
        try {
            have = BankBridge.money(sp.getScoreboardName());
        } catch (Throwable ignored) {
            // ignore
        }
        if (have < fee) {
            event.setCanceled(true);
            sp.displayClientMessage(Component.literal(
                    "\u00a7c[传送] \u8fd9\u6b21\u4f20\u9001\u9700\u8981 " + fee
                            + " \u5510\u5154\u5e01\uff08\u4f60\u53ea\u6709 " + have + "\uff09\uff0c\u4f20\u9001\u5df2\u53d6\u6d88"), false);
            return;
        }
        try {
            com.tangtu.kjbb.Pay.pay(sp, fee);
            com.tangtu.kjbb.RegulatorOps.creditTreasury(fee, "\u77f3\u7891\u4f7f\u7528");
            BotTrades.log(sp.getScoreboardName(), "-", "\u4f20\u9001\u77f3\u7891\u4f7f\u7528",
                    1, fee, "\u77f3\u7891");
            sp.displayClientMessage(Component.literal(
                    "\u00a77[传送] \u6d88\u8017 " + fee + " \u5510\u5154\u5e01"), false);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
