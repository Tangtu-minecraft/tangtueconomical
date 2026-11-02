package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 机器人账本：让机器人处于「封闭系统」，不能凭空造钱造货。
 *
 * config/kjbb/bot_ledger.txt
 *   bot|money|itemId=count;itemId=count
 *
 * 规则：
 *   · 挂卖单 → 预扣库存；撤单/过期 → 退回
 *   · 下买单 → 预扣货款；撤单/过期 → 退款
 *   · 玩家买它的货 → 钱进它的钱包、货扣掉
 *   · 玩家接它的买单 → 货进它的库存、钱付给玩家（用的是预扣的那笔）
 *
 * 所以整个市场是守恒的：钱和货只在玩家与机器人之间转移。
 */
public final class BotLedger {

    /** 三位机器人各自的本金（唐兔币） */
    public static final java.util.Map<String, Long> PRINCIPAL = new java.util.LinkedHashMap<>();
    /** 三位机器人各自的初始库存（每种物品） */
    public static final java.util.Map<String, Integer> STOCK0 = new java.util.LinkedHashMap<>();

    static {
        PRINCIPAL.put(MarketBots.NAMES[0], 500_000L);   // 经济部：大商号
        PRINCIPAL.put(MarketBots.NAMES[1], 200_000L);   // 唐兔国国民：中等
        PRINCIPAL.put(MarketBots.NAMES[2], 80_000L);    // 大A膏手：小本买卖
        if (MarketBots.NAMES.length > 3) {
            PRINCIPAL.put(MarketBots.NAMES[3], 800_000L);   // 唐兔国航运公司：远洋资本
        }
        STOCK0.put(MarketBots.NAMES[0], 1024);
        STOCK0.put(MarketBots.NAMES[1], 512);
        STOCK0.put(MarketBots.NAMES[2], 256);
        if (MarketBots.NAMES.length > 3) {
            STOCK0.put(MarketBots.NAMES[3], 1024);
        }
    }

    /** 所有机器人名字（清理物品用） */
    public static java.util.Set<String> allNames() {
        load();
        return new java.util.LinkedHashSet<>(STOCK.keySet());
    }

    public static long principal(String bot) {
        return BotConfig.principal(bot);
    }

    public static int stock0(String bot) {
        return BotConfig.stock0(bot);
    }

    /** 钱包下限：低于这个不再下买单 */
    public static final long MIN_MONEY = 20_000L;
    /** 库存下限：低于这个不再挂卖 */
    public static final int MIN_STOCK = 32;

    private static final Map<String, Long> MONEY = new LinkedHashMap<>();
    private static final Map<String, Map<String, Integer>> STOCK = new LinkedHashMap<>();
    private static boolean loaded = false;

    private BotLedger() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("bot_ledger.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_bot_ledger.txt");
        }
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (String n : BotConfig.names()) {
            MONEY.put(n, principal(n));
            STOCK.put(n, new LinkedHashMap<>());
        }
        Path p = file();
        if (!Files.exists(p)) {
            seedAll();
            save();
            return;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line == null || line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length < 2) {
                    continue;
                }
                String bot = f[0].trim();
                try {
                    MONEY.put(bot, Long.parseLong(f[1].trim()));
                } catch (Throwable ignored) {
                    // 跳过
                }
                Map<String, Integer> m = new LinkedHashMap<>();
                if (f.length > 2 && !f[2].isEmpty()) {
                    for (String part : f[2].split(";")) {
                        int eq = part.lastIndexOf('=');
                        if (eq <= 0) {
                            continue;
                        }
                        try {
                            m.put(part.substring(0, eq).trim(),
                                    Integer.parseInt(part.substring(eq + 1).trim()));
                        } catch (Throwable ignored) {
                            // 跳过
                        }
                    }
                }
                STOCK.put(bot, m);
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读机器人账本失败: " + t);
        }
        boolean changed = false;
        for (String n : BotConfig.names()) {
            Map<String, Integer> m = STOCK.get(n);
            if (m == null || m.isEmpty()) {
                Map<String, Integer> nm = STOCK.computeIfAbsent(n, k -> new LinkedHashMap<>());
                for (String id : ShopPrices.items()) {
                    if (!seedable(id)) {
                        continue;
                    }
                    nm.put(id, stock0(n));
                }
                changed = true;
            }
        }
        if (changed) {
            save();
        }
        // 清理不在 bots.json 名单里的僵尸账本条目（改名/删机器人后的残留）
        pruneUnknownBots();
    }

    /** 脏标记：批量操作时只标记，由 DeliveryWatcher 每 5 秒统一落盘 */
    private static volatile boolean DIRTY = false;

    public static synchronized void markDirty() {
        DIRTY = true;
    }

    /** 有脏数据才写盘 */
    public static synchronized void flushIfDirty() {
        if (DIRTY) {
            save();
        }
    }

    public static synchronized void flushNow() {
        DIRTY = false;
        StringBuilder sb = new StringBuilder("# bot|money|itemId=count;...\n");
        for (var e : MONEY.entrySet()) {
            sb.append(e.getKey()).append('|').append(e.getValue()).append('|');
            Map<String, Integer> m = STOCK.get(e.getKey());
            int n = 0;
            if (m != null) {
                for (var it : m.entrySet()) {
                    if (it.getValue() <= 0) {
                        continue;
                    }
                    if (n++ > 0) {
                        sb.append(';');
                    }
                    sb.append(it.getKey()).append('=').append(it.getValue());
                }
            }
            sb.append('\n');
        }
        try {
            SafeFile.write(file(), sb.toString());
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写机器人账本失败: " + t);
        }
    }

    /** 标脏：真正写盘由 DirtySave 每 saveIntervalTicks 批量执行 */
    public static synchronized void save() {
        // 阶段三：accounts.txt 为唯一真相，旧账本不再落盘
        if (!com.tangtu.kjbb.Accounts.dualWriteLegacy) {
            return;
        }
        DirtySave.mark("bots");
    }

    static {
        DirtySave.register("bots", BotLedger::flushNow);
    }


    public static synchronized long money(String bot) {
        load();
        return MONEY.getOrDefault(bot, 0L);
    }

    public static synchronized void addMoney(String bot, long delta) {
        if (!com.tangtu.kjbb.Accounts.isLegacyWrite()) {
            if (delta > 0) {
                com.tangtu.kjbb.Accounts.credit(bot, delta);
            } else if (delta < 0) {
                com.tangtu.kjbb.Accounts.take(bot, -delta);
            }
            return;
        }
        load();
        long v = Math.max(0L, MONEY.getOrDefault(bot, 0L) + delta);
        MONEY.put(bot, v);
        save();
    }

    /** 该机器人所有库存的件数总和 */
    public static synchronized int totalItems(String bot) {
        load();
        int sum = 0;
        try {
            Map<String, Integer> m = STOCK.get(bot);
            if (m != null) {
                for (Integer v : m.values()) {
                    if (v != null && v > 0) {
                        sum += v;
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return sum;
    }

    public static synchronized int stock(String bot, String itemId) {
        try {
            return com.tangtu.kjbb.Accounts.stock(bot, itemId);
        } catch (Throwable ignored) {
            // 回退
        }
        load();
        Map<String, Integer> m = STOCK.get(bot);
        return m == null ? 0 : m.getOrDefault(itemId, 0);
    }

    public static synchronized void addStock(String bot, String itemId, int delta) {
        if (!com.tangtu.kjbb.Accounts.isLegacyWrite()) {
            com.tangtu.kjbb.Accounts.addStock(bot, itemId, delta);
            return;
        }
        load();
        Map<String, Integer> m = STOCK.computeIfAbsent(bot, k -> new LinkedHashMap<>());
        int v = m.getOrDefault(itemId, 0) + delta;
        if (v <= 0) {
            m.remove(itemId);
        } else {
            m.put(itemId, v);
        }
        save();
    }

    /** 把三位机器人恢复到各自的本金与初始库存 */
    /** 只有允许交易的物品才会被铺进机器人库存 */
    private static boolean seedable(String itemId) {
        try {
            return !com.tangtu.kjbb.Tradability.banned(itemId);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 给库存为空的机器人铺上初始货 */
    private static void seedAll() {
        java.util.List<String> items = ShopPrices.items();
        for (String n : BotConfig.names()) {
            Map<String, Integer> m = STOCK.computeIfAbsent(n, k -> new LinkedHashMap<>());
            if (!m.isEmpty()) {
                continue;
            }
            for (String id : items) {
                if (!seedable(id)) {
                    continue;
                }
                m.put(id, stock0(n));
            }
        }
    }

    /**
     * 清理「僵尸机器人」：账本里存在、但已不在 bots.json 名单中的条目
     *
     *   · 资金并入「经济部」（找不到则并入第一家）
     *   · 库存按物品累加进去
     *   · 条目本身删除
     *
     * 这样改名/删机器人之后，残留的旧账不会变成「退出流通的死钱」。
     */
    public static synchronized void pruneUnknownBots() {
        try {
            java.util.Set<String> known = new java.util.HashSet<>();
            for (String n : BotConfig.names()) {
                known.add(n);
            }
            if (known.isEmpty()) {
                return;
            }
            // 合并目标：经济部 > 第一家
            String target = null;
            for (String n : known) {
                if (n != null && n.contains("\u7ecf\u6d4e\u90e8")) {
                    target = n;
                    break;
                }
            }
            if (target == null) {
                target = known.iterator().next();
            }
            java.util.List<String> ghosts = new java.util.ArrayList<>();
            for (String n : MONEY.keySet()) {
                if (!known.contains(n)) {
                    ghosts.add(n);
                }
            }
            if (ghosts.isEmpty()) {
                return;
            }
            for (String g : ghosts) {
                long money = MONEY.getOrDefault(g, 0L);
                Map<String, Integer> stock = STOCK.get(g);
                MONEY.put(target, MONEY.getOrDefault(target, 0L) + money);
                if (stock != null && !stock.isEmpty()) {
                    Map<String, Integer> tgt = STOCK.computeIfAbsent(target,
                            k -> new LinkedHashMap<>());
                    for (var e : stock.entrySet()) {
                        tgt.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
                MONEY.remove(g);
                STOCK.remove(g);
                System.out.println("[TangtuEconomical] \u5e10\u672c\u6e05\u7406\uff1a\u5c06\u50f5\u5c38\u673a\u5668\u4eba\u300c"
                        + g + "\u300d\u7684 " + money
                        + " \u5510\u5154\u5e01\u5e76\u5165\u300c" + target + "\u300d");
            }
            save();
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u5e10\u672c\u6e05\u7406\u5931\u8d25: " + t);
        }
    }

    public static synchronized void resetAll() {
        loaded = true;
        MONEY.clear();
        STOCK.clear();
        for (String n : BotConfig.names()) {
            MONEY.put(n, principal(n));
            Map<String, Integer> m = new LinkedHashMap<>();
            // 按当前定价表铺货
            for (String itemId : ShopPrices.items()) {
                if (!seedable(itemId)) {
                    continue;
                }
                m.put(itemId, stock0(n));
            }
            STOCK.put(n, m);
        }
        save();
    }

    /** 单个机器人摘要（命令展示用） */
    public static synchronized String summary(String bot) {
        load();
        Map<String, Integer> m = STOCK.get(bot);
        int kinds = m == null ? 0 : m.size();
        long total = 0;
        if (m != null) {
            for (int v : m.values()) {
                total += v;
            }
        }
        return bot + "  本金 " + principal(bot) + "  现有 " + money(bot)
                + " 唐兔币  库存 " + kinds + " 种 / " + total + " 件";
    }

    public static synchronized String dump() {
        load();
        StringBuilder sb = new StringBuilder();
        for (String n : BotConfig.names()) {
            sb.append(summary(n)).append('\n');
        }
        return sb.toString();
    }

    /** 读取某机器人的全部库存（副本） */
    public static synchronized java.util.Map<String, Integer> stockMap(String bot) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        try {
            var d = STOCK.get(bot);
            if (d != null) {
                out.putAll(d);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return out;
    }

    /**
     * 清理「名字与机器人重名」的钱包账户：
     * 余额先转入机器人账本，再删除该钱包条目（总额不变）
     *
     * @param candidates 需要清理的名字（机器人名）
     */
    public static synchronized String absorbWalletDuplicates(java.util.Collection<String> candidates) {
        load();
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : candidates) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            // 必须读旧钱包 map：Wallet.money() 现在走统一账户，会把余额重复加一遍
            if (!Wallet.legacyHas(name)) {
                continue;
            }
            long bal = Wallet.legacyMoney(name);
            if (bal > 0L) {
                MONEY.merge(name, bal, Long::sum);
                sb.append("· ").append(name).append(" 钱包余额 ").append(bal).append(" → 并入账本；");
                save();
            }
            if (Wallet.remove(name)) {
                sb.append("· 已删除钱包条目 ").append(name).append("；");
            }
        }
        return sb.toString();
    }
}
