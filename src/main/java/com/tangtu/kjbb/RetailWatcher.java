package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.loading.FMLPaths;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零售观察器（只读，事件驱动）。
 *
 * 售货机 / 售卖点都是「玩家右键才会成交」的方块，所以不需要定时扫描：
 *
 *   玩家右键 → 立刻记录「成交前」快照
 *   60 tick（3 秒）后复查一次
 *   货物减少 + 硬币增加 → 判定成交 → 反推单价 → 写 K 线
 *
 * 全程反射，不依赖 numismatics 的编译期类。
 */
public final class RetailWatcher {

    private static final class Snapshot {
        Map<String, Integer> items = new LinkedHashMap<>();
        long coins = 0L;
    }

    private static final class Pending {
        ServerLevel level;
        BlockPos pos;
        Snapshot before;
        long dueAt;
        long deadline;
        long price;
        java.util.UUID player;
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static final List<Pending> KEEP = new ArrayList<>();

    /** 玩家「我们换出去的硬币额度」：成交后按花费回收，净效果 = 直接花唐兔币 */
    private static final Map<java.util.UUID, Long> CASH_CREDIT = new java.util.HashMap<>();

    /** 记录一次钱包换硬币（由 RetailInteraction 调用） */
    public static synchronized void addCashCredit(java.util.UUID player, long amount) {
        if (amount <= 0) {
            return;
        }
        CASH_CREDIT.merge(player, amount, Long::sum);
    }

    private RetailWatcher() {
    }

    /** 反射读标价（getTotalPrice），读不到返回 0 */
    private static long readPrice(BlockEntity be) {
        try {
            for (Method m : be.getClass().getMethods()) {
                if (m.getName().equals("getTotalPrice") && m.getParameterCount() == 0) {
                    Object v = m.invoke(be);
                    if (v instanceof Integer i) {
                        return i.longValue();
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0L;
    }

    /** 玩家右键了可能是零售方块的方块 */
    public static void onInteract(BlockPos pos, BlockEntity be, java.util.UUID player) {
        try {
            if (be == null || !(be.getLevel() instanceof ServerLevel level)) {
                return;
            }
            String cls = be.getClass().getName().toLowerCase();
            if (!cls.contains("vendor") && !cls.contains("salepoint")
                    && !cls.contains("tablecloth")) {
                return;
            }
            Pending p = new Pending();
            p.level = level;
            p.pos = pos.immutable();
            p.before = read(be);
            p.player = player;
            p.dueAt = ServerClock.now() + 1500L;
            p.deadline = ServerClock.now() + 60_000L;
            p.price = readPrice(be);
            PENDING.add(p);
            writeDebug("已登记观察 " + pos.toShortString() + "  " + be.getClass().getSimpleName()
                    + "  物品=" + (p.before == null ? "?" : p.before.items.size())
                    + " 硬币=" + (p.before == null ? "?" : p.before.coins) + "\n");
        } catch (Throwable t) {
            // 忽略
        }
    }

    /** 每 100 tick（5 秒）处理到期的观察 */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = ServerClock.now();
        List<Pending> done = new ArrayList<>();
        for (Pending p : PENDING) {
            if (now < p.dueAt) {
                continue;
            }
            done.add(p);
            try {
                topUpIfNeeded(p);
                if (!p.level.isLoaded(p.pos)) {
                    continue;
                }
                BlockEntity be = p.level.getBlockEntity(p.pos);
                if (be == null) {
                    continue;
                }
                Snapshot after = read(be);
                compare(p, after);
            } catch (Throwable t) {
                // 忽略
            }
        }
        PENDING.removeAll(done);
        PENDING.addAll(KEEP);
        KEEP.clear();
        if (PENDING.size() > 64) {
            PENDING.subList(0, PENDING.size() - 64).clear();
        }
    }

    private static void compare(Pending p, Snapshot after) {
        if (p.before == null || after == null) {
            return;
        }
        long coinsDelta = after.coins - p.before.coins;
        String soldItem = null;
        int soldCount = 0;
        for (var e : p.before.items.entrySet()) {
            int was = e.getValue();
            int is = after.items.getOrDefault(e.getKey(), 0);
            if (was > is) {
                int d = was - is;
                if (d > soldCount) {
                    soldCount = d;
                    soldItem = e.getKey();
                }
            }
        }
        if (soldItem == null || soldCount <= 0) {
            writeDebug("观察 " + p.pos.toShortString() + " 暂无成交（硬币变化 "
                    + coinsDelta + "），继续等 60 秒\n");
            if (ServerClock.now() >= p.deadline) {
                refundCredit(p);
            } else {
                p.dueAt = ServerClock.now() + 3000L;   // 3 秒后再看
                KEEP.add(p);
            }
            return;
        }
        long unit = coinsDelta > 0 ? Math.max(1L, coinsDelta / soldCount) : 0L;
        if (unit <= 0) {
            unit = ShopPrices.price(soldItem);
        }
        if (unit <= 0) {
            writeDebug("观察 " + p.pos.toShortString() + " 卖出 " + soldItem + "×" + soldCount
                    + " 但算不出价格\n");
            return;
        }
        ShopBridge.recordRetailSale(soldItem, unit, soldCount);
        logTrade(p.pos, soldItem, soldCount, unit, coinsDelta);
        // 结算：把本次换出的硬币剩余部分全部折算回钱包
        //   换出 1024 -> 花了 168 -> 剩 856 全部回钱包 -> 钱包净变化 = -168
        try {
            if (p.player != null) {
                Long credit = CASH_CREDIT.get(p.player);
                if (credit != null && credit > 0) {
                    var sp = p.level.getServer() == null ? null
                            : p.level.getServer().getPlayerList().getPlayer(p.player);
                    if (sp != null) {
                        long back = Coins.withdraw(sp, credit);
                        if (back > 0) {
                            long change = Math.max(0L, back - coinsDelta);
                            CASH_CREDIT.put(p.player, Math.max(0L, credit - back));
                            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    "§a[商店] 消费 §e" + coinsDelta
                                            + " 唐兔币§a，找零 §e" + change
                                            + " §a已退回钱包"), false);
                        } else {
                            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    "§a[商店] 消费 §e" + coinsDelta + " 唐兔币"), false);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        writeDebug("✅ 零售成交 " + p.pos.toShortString() + " " + soldItem + " ×" + soldCount
                + " @" + unit + "（硬币 +" + coinsDelta + "）\n");
    }

    /**
     * 观察期内持续补币：只要还没成交，就保证玩家身上的硬币不少于标价。
     * 这样玩家在界面里磨蹭多久都不会遇到「资金不足」。
     */
    private static void topUpIfNeeded(Pending p) {
        try {
            if (p.player == null || p.price <= 0) {
                return;
            }
            var sp = p.level.getServer() == null ? null
                    : p.level.getServer().getPlayerList().getPlayer(p.player);
            if (sp == null) {
                return;
            }
            long have = Coins.inventoryValue(sp);
            if (have >= p.price) {
                return;
            }
            long wallet = Wallet.money(sp.getScoreboardName());
            long need = Math.min(p.price - have, wallet);
            if (need <= 0) {
                return;
            }
            long got = Coins.withdrawWalletToCoins(sp, need);
            if (got > 0) {
                addCashCredit(p.player, got);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 没有成交：把预支的硬币全部折回钱包 */
    private static void refundCredit(Pending p) {
        try {
            if (p.player == null) {
                return;
            }
            Long credit = CASH_CREDIT.get(p.player);
            if (credit == null || credit <= 0) {
                return;
            }
            var sp = p.level.getServer() == null ? null
                    : p.level.getServer().getPlayerList().getPlayer(p.player);
            if (sp == null) {
                return;
            }
            long back = Coins.withdraw(sp, credit);
            if (back > 0) {
                CASH_CREDIT.put(p.player, Math.max(0L, credit - back));
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a77[商店] 未购买，已退回 \u00a7e" + back
                                + " 唐兔币\u00a77（剩余硬币已换回钱包）"),
                        false);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 反射读「货物 + 硬币总量」：递归找所有 Container 字段 */
    private static Snapshot read(BlockEntity be) {
        try {
            Snapshot s = new Snapshot();
            java.util.Set<Object> seen = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            collectContainers(be, s, seen, 0, "", new StringBuilder());
            Method m = null;
            for (Method mm : be.getClass().getMethods()) {
                if (mm.getName().equals("getCoinCount") && mm.getParameterCount() == 1) {
                    m = mm;
                    break;
                }
            }
            if (m != null) {
                Class<?> coinType = m.getParameterTypes()[0];
                if (coinType.isEnum()) {
                    for (Object coin : coinType.getEnumConstants()) {
                        try {
                            Object v = m.invoke(be, coin);
                            int n = v instanceof Integer i ? i : 0;
                            if (n > 0) {
                                s.coins += Coins.faceValue(
                                        "numismatics:" + ((Enum<?>) coin).name().toLowerCase()) * n;
                            }
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                }
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 递归扫描字段，把所有 Container 里的物品都统计进来 */
    private static void collectContainers(Object obj, Snapshot s, java.util.Set<Object> seen,
                                          int depth, String path, StringBuilder dbg) {
        if (obj == null || depth > 2 || seen.contains(obj)) {
            return;
        }
        seen.add(obj);
        if (obj instanceof net.minecraft.world.Container c) {
            int kinds = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                var rl = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (rl == null) {
                    continue;
                }
                s.items.merge(rl.toString(), st.getCount(), Integer::sum);
                kinds++;
            }
            dbg.append("  容器 ").append(path).append(" → ").append(c.getContainerSize())
                    .append(" 格 / ").append(kinds).append(" 非空\n");
            return;
        }
        Class<?> cls = obj.getClass();
        if (cls.getName().startsWith("java.") || cls.getName().startsWith("net.minecraft.core")
                || cls.getName().startsWith("net.minecraft.nbt")) {
            return;
        }
        Class<?> c0 = cls;
        while (c0 != null && c0 != Object.class) {
            for (Field f : c0.getDeclaredFields()) {
                if (f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v != null) {
                        collectContainers(v, s, seen, depth + 1, path + "." + f.getName(), dbg);
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            c0 = c0.getSuperclass();
        }
    }

    private static Object field(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static void writeDebug(String content) {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            Path f = dir.resolve("_debug_retail.txt");
            String line = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date())
                    + " " + content;
            Files.writeString(f, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            // 太长就截断一半
            try {
                var all = Files.readAllLines(f, StandardCharsets.UTF_8);
                if (all.size() > 200) {
                    Files.writeString(f, String.join("\n",
                            all.subList(all.size() - 100, all.size())) + "\n",
                            StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static Path logFile() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("retail_trades.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_retail_trades.txt");
        }
    }

    private static void logTrade(BlockPos pos, String itemId, int count, long unit, long total) {
        try {
            String line = ServerClock.now() + "|" + pos.getX() + "," + pos.getY() + ","
                    + pos.getZ() + "|" + itemId + "|" + count + "|" + unit + "|" + total + "\n";
            Files.writeString(logFile(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
