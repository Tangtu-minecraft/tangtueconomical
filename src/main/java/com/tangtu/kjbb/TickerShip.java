package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 让【机械动力的物流系统】发货：向发报机广播包裹请求，
 * 由 Create 的打包机打包、按地址投递到玩家自己建的蛙港/邮箱。
 *
 * 我们只负责「出请求」，不自己造包裹塞目标箱子。
 */
public final class TickerShip {

    private TickerShip() {
    }

    /** 找离 center 最近的发报机 */
    public static BlockPos nearestTicker(ServerLevel level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    if (!TickerStock.isTicker(level, p)) {
                        continue;
                    }
                    double d = center.distSqr(p);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        if (best != null) {
            System.out.println("[TangtuEconomical] 找到发报机 " + best + " 距离²≈"
                    + (long) bestD + " 玩家 @" + center);
        }
        return best;
    }

    /**
     * 广播一条包裹请求（Create 负责打包与配送）。
     *
     * @return true = 请求已被接受
     */
    public static boolean request(ServerLevel level, BlockPos tickerPos,
                                  String itemId, int count, String address) {
        if (tickerPos == null) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (item == null) {
            return false;
        }
        Object be = level.getBlockEntity(tickerPos);
        if (be == null) {
            return false;
        }
        try {
            java.util.List<com.simibubi.create.content.logistics.BigItemStack> stacks =
                    new java.util.ArrayList<>();
            stacks.add(new com.simibubi.create.content.logistics.BigItemStack(
                    new ItemStack(item), Math.max(1, count)));
            var order = com.simibubi.create.content.logistics.stockTicker
                    .PackageOrderWithCrafts.simple(stacks);
            Object requestType = Class.forName(
                            "com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour$RequestType")
                    .getField("PLAYER").get(null);
            for (var m : be.getClass().getMethods()) {
                if (!"broadcastPackageRequest".equals(m.getName()) || m.getParameterCount() != 4) {
                    continue;
                }
                Object r = m.invoke(be, requestType, order, null, address);
                boolean ok = r instanceof Boolean b && b;
                System.out.println("[TangtuEconomical] 广播发货请求 " + itemId + " x" + count
                        + " → " + address + " = " + ok);
                return ok;
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 广播发货请求失败: " + t);
        }
        return false;
    }
}