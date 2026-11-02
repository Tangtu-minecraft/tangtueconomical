package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 发报机（仓储发报机）周边容器的货物读写工具 */
public final class TickerStock {

    /** 扫描半径（格） */
    public static final int RADIUS = 16;

    private TickerStock() {
    }

    /** 判断某个方块是不是 Create 的仓储发报机 */
    public static boolean isTicker(net.minecraft.world.level.Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        String n = be.getClass().getName().toLowerCase();
        return n.contains("stockticker") || n.contains("stock_ticker");
    }

    /** 反射取方块实体的坐标（部分 Create 类的父类不可直接引用） */
    public static BlockPos posOfBlockEntity(Object be) {
        try {
            for (var m : be.getClass().getMethods()) {
                if ("getBlockPos".equals(m.getName()) && m.getParameterCount() == 0) {
                    Object v = m.invoke(be);
                    if (v instanceof BlockPos bp) {
                        return bp;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return BlockPos.ZERO;
    }

    /** 发报机周围所有物品容器 */
    public static List<IItemHandler> containers(ServerLevel level, BlockPos center) {
        List<IItemHandler> out = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    var be = level.getBlockEntity(p);
                    if (be == null) {
                        continue;
                    }
                    IItemHandler h = Capabilities.ItemHandler.BLOCK.getCapability(
                            level, p, be.getBlockState(), be, null);
                    if (h != null) {
                        out.add(h);
                    }
                }
            }
        }
        return out;
    }

    /** 仓库现有货物汇总：itemId -> 数量 */
    public static Map<String, Integer> stock(ServerLevel level, BlockPos center) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (IItemHandler h : containers(level, center)) {
            for (int i = 0; i < h.getSlots(); i++) {
                ItemStack st = h.getStackInSlot(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                ResourceLocation rl = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (rl == null) {
                    continue;
                }
                map.merge(rl.toString(), st.getCount(), Integer::sum);
            }
        }
        return map;
    }

    /**
     * 读【发报机链接的仓库】里某物品的数量（用 Create 的网络库存汇总）。
     * 读不到就退回扫描容器。
     */
    public static int networkCount(ServerLevel level, BlockPos tickerPos, ItemStack stack) {
        try {
            Object be = level.getBlockEntity(tickerPos);
            if (be == null) {
                return stock(level, tickerPos).getOrDefault(itemIdOf(stack), 0);
            }
            Object summary = null;
            for (var m : be.getClass().getMethods()) {
                if (!"getAccurateSummary".equals(m.getName()) || m.getParameterCount() != 0) {
                    continue;
                }
                summary = m.invoke(be);
                break;
            }
            if (summary == null) {
                return stock(level, tickerPos).getOrDefault(itemIdOf(stack), 0);
            }
            for (var m : summary.getClass().getMethods()) {
                if (!"getCountOf".equals(m.getName()) || m.getParameterCount() != 1) {
                    continue;
                }
                Object v = m.invoke(summary, stack);
                if (v instanceof Number n) {
                    return n.intValue();
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读网络库存失败: " + t);
        }
        return 0;
    }

    private static String itemIdOf(ItemStack st) {
        var rl = BuiltInRegistries.ITEM.getKey(st.getItem());
        return rl == null ? "" : rl.toString();
    }

    /** 取方块实体里的 LogisticallyLinkedBehaviour（反射，用于比对网络频率） */
    private static Object findLink(Object be) {
        Class<?> c = be.getClass();
        while (c != null && c != Object.class) {
            for (var f : c.getDeclaredFields()) {
                if (!f.getType().getName().toLowerCase().contains("logisticallylinked")) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(be);
                    if (v != null) {
                        return v;
                    }
                } catch (Throwable ignored) {
                    // 继续
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 取 link 的网络频率 */
    private static Object freqOf(Object link) {
        if (link == null) {
            return null;
        }
        try {
            var f = link.getClass().getField("freqId");
            return f.get(link);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 把货物塞进【仓库】——即发报机网络中打包机的目标库存，
     * 这样发报机的库存汇总里能看到这些货。
     *
     * @return 没塞进去的数量
     */
    public static int insertIntoWarehouse(ServerLevel level, BlockPos center, ItemStack stack) {
        int left = stack.getCount();
        int r = RADIUS;
        // 发报机所在网络的频率（只往这个网络的打包机里塞）
        Object tickerFreq = freqOf(findLink(level.getBlockEntity(center)));
        for (int dx = -r; dx <= r && left > 0; dx++) {
            for (int dy = -r; dy <= r && left > 0; dy++) {
                for (int dz = -r; dz <= r && left > 0; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    BlockEntity be = level.getBlockEntity(p);
                    if (be == null) {
                        continue;
                    }
                    String cls = be.getClass().getName().toLowerCase();
                    if (!cls.contains("packager")) {
                        continue;
                    }
                    // 必须是同一个物流网络
                    if (tickerFreq != null) {
                        Object pkFreq = freqOf(findLink(be));
                        if (pkFreq != null && !tickerFreq.equals(pkFreq)) {
                            continue;
                        }
                    }
                    try {
                        var f = be.getClass().getField("targetInventory");
                        Object manipulation = f.get(be);
                        if (manipulation == null) {
                            continue;
                        }
                        var insert = manipulation.getClass()
                                .getMethod("insert", ItemStack.class);
                        ItemStack toPut = stack.copyWithCount(left);
                        Object restObj = insert.invoke(manipulation, toPut);
                        int rest = 0;
                        if (restObj instanceof ItemStack restStack) {
                            rest = restStack.getCount();
                        }
                        left = rest;
                    } catch (Throwable ex) {
                        System.out.println("[TangtuEconomical] 塞入打包机库存失败: " + ex);
                    }
                }
            }
        }
        return left;
    }

    /** 取某个位置的物品容器（没有就返回 null） */
    public static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        return Capabilities.ItemHandler.BLOCK.getCapability(level, pos, be.getBlockState(), be, null);
    }

    /** 取发报机所在网络里所有打包机的【目标库存】处理器 */
    public static java.util.List<IItemHandler> warehouseHandlers(ServerLevel level, BlockPos center) {
        java.util.List<IItemHandler> out = new java.util.ArrayList<>();
        Object tickerFreq = freqOf(findLink(level.getBlockEntity(center)));
        int r = RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    BlockEntity be = level.getBlockEntity(p);
                    if (be == null) {
                        continue;
                    }
                    String cls = be.getClass().getName().toLowerCase();
                    if (!cls.contains("packager")) {
                        continue;
                    }
                    if (tickerFreq != null) {
                        Object pkFreq = freqOf(findLink(be));
                        if (pkFreq != null && !tickerFreq.equals(pkFreq)) {
                            continue;
                        }
                    }
                    try {
                        Object manipulation = be.getClass().getField("targetInventory").get(be);
                        if (manipulation == null) {
                            continue;
                        }
                        Class<?> c = manipulation.getClass();
                        java.lang.reflect.Field cap = null;
                        while (c != null && cap == null) {
                            try {
                                cap = c.getDeclaredField("targetCapability");
                            } catch (Throwable ignored) {
                                c = c.getSuperclass();
                            }
                        }
                        if (cap == null) {
                            continue;
                        }
                        cap.setAccessible(true);
                        Object handler = cap.get(manipulation);
                        if (handler instanceof IItemHandler ih) {
                            out.add(ih);
                        }
                    } catch (Throwable ex) {
                        // 忽略
                    }
                }
            }
        }
        return out;
    }

    /** 从【发报机链接的仓库】扣货，返回实际扣到的数量 */
    public static int extractFromWarehouse(ServerLevel level, BlockPos center, Item item, int want) {
        int left = want;
        for (IItemHandler h : warehouseHandlers(level, center)) {
            for (int i = 0; i < h.getSlots() && left > 0; i++) {
                ItemStack st = h.getStackInSlot(i);
                if (st == null || st.isEmpty() || st.getItem() != item) {
                    continue;
                }
                ItemStack got = h.extractItem(i, left, false);
                if (got != null && !got.isEmpty()) {
                    left -= got.getCount();
                }
            }
        }
        return want - left;
    }

    /** 从仓库扣货，返回实际扣到的数量 */
    /**
     * 从某人的仓库取货：在线按其位置找最近的发报机，
     * 离线按其绑定的提货点位置找。
     */
    public static int takeFromAnyWarehouse(String owner, Item item, int want) {
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null || want <= 0) {
                return 0;
            }
            net.minecraft.server.level.ServerLevel level = null;
            net.minecraft.core.BlockPos anchor = null;
            var sp = srv.getPlayerList().getPlayerByName(owner);
            if (sp != null) {
                level = sp.serverLevel();
                anchor = sp.blockPosition();
            } else {
                net.minecraft.core.BlockPos port = com.tangtu.kjbb.AddressBook.get(owner);
                if (port != null) {
                    level = srv.overworld();
                    anchor = port;
                }
            }
            if (level == null || anchor == null) {
                return 0;
            }
            net.minecraft.core.BlockPos ticker =
                    com.tangtu.kjbb.TickerShip.nearestTicker(level, anchor, 64);
            if (ticker == null) {
                return 0;
            }
            return extractFromWarehouse(level, ticker, item, want);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int extract(ServerLevel level, BlockPos center, Item item, int want) {
        int left = want;
        for (IItemHandler h : containers(level, center)) {
            for (int i = 0; i < h.getSlots() && left > 0; i++) {
                ItemStack st = h.getStackInSlot(i);
                if (st == null || st.isEmpty() || st.getItem() != item) {
                    continue;
                }
                ItemStack got = h.extractItem(i, left, false);
                if (got != null && !got.isEmpty()) {
                    left -= got.getCount();
                }
            }
        }
        return want - left;
    }

    /** 往仓库塞货，返回没塞进去的数量 */
    public static int insert(ServerLevel level, BlockPos center, ItemStack stack) {
        int left = stack.getCount();
        for (IItemHandler h : containers(level, center)) {
            for (int i = 0; i < h.getSlots() && left > 0; i++) {
                ItemStack toPut = stack.copyWithCount(left);
                ItemStack remain = h.insertItem(i, toPut, false);
                int put = left - (remain == null ? 0 : remain.getCount());
                left -= put;
            }
        }
        return left;
    }
}