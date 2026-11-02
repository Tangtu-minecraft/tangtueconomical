package com.tangtu.kjbb;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 国库常平仓（第一阶段：只做收购托底）
 *
 * 规则：
 *   · 只收「粮食类」（便宜食物）
 *   · 市价低于「基准价 × buyBand」时才收
 *   · 国库该物品库存低于「目标储备」时才收
 *   · 每天有收购预算上限，超出当天停手
 *
 * 这样玩家种粮有稳定销路，同时粮价被托在基准价附近。
 */
public final class RegulatorGranary {

    private RegulatorGranary() {
    }

    private static long GRANARY_DAY = -1L;
    private static long LAST_DIAG = 0L;
    /** 今天已经下过常平仓单的物品 */
    private static final java.util.Set<String> PLACED_TODAY = new java.util.HashSet<>();
    private static long SPENT_TODAY = 0L;
    private static boolean LOADED = false;

    private static Path file() {
        return Path.of("config", "kjbb", "granary.txt");
    }

    private static synchronized void load() {
        if (LOADED) {
            return;
        }
        LOADED = true;
        try {
            Path p = file();
            if (!Files.exists(p)) {
                return;
            }
            String s = Files.readString(p, StandardCharsets.UTF_8).trim();
            String[] f = s.split("\\|");
            if (f.length >= 2) {
                GRANARY_DAY = Long.parseLong(f[0].trim());
                SPENT_TODAY = Long.parseLong(f[1].trim());
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static synchronized void save() {
        try {
            Path p = file();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, GRANARY_DAY + "|" + SPENT_TODAY,
                    StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 每天重置预算 */
    private static void rollDay(long day) {
        if (GRANARY_DAY == day) {
            return;
        }
        GRANARY_DAY = day;
        SPENT_TODAY = 0L;
        PLACED_TODAY.clear();
        save();
    }

    /** 每 5 秒调用一次；内部按分钟限流 */
    private static int TICK = 0;

    public static void tick() {
        var cfg = BotConfig.get();
        if (!cfg.regulatorGranaryEnabled) {
            return;
        }
        // 高买粮食：给玩家一个「高于市价」的卖粮出口（与价格调控无关，独立运行）
        try {
            grainBuyAct(cfg);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u9ad8\u4e70\u7cae\u98df\u51fa\u9519: " + t);
        }
        // 按量放货：库存太多了就卖给玩家（不看价格，只看存量）
        try {
            stockSellAct(cfg);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u6309\u91cf\u653e\u8d27\u51fa\u9519: " + t);
        }
        if (!cfg.regulatorPriceControlEnabled) {
            return;                 // 不做价格调控：既不托市收购也不放货压价
        }
        if (++TICK < 12) {          // 12 × 5 秒 = 1 分钟
            return;
        }
        TICK = 0;
        try {
            act(cfg);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u5e38\u5e73\u4ed3\u51fa\u9519: " + t);
        }
        try {
            sellAct(cfg);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u56fd\u5e93\u653e\u8d27\u51fa\u9519: " + t);
        }
    }

    // ---------------- 国库高买粮食 ----------------

    private static final java.util.Set<String> GRAIN_BUY_TODAY = new java.util.HashSet<>();
    private static long GRAIN_BUY_DAY = -1L;
    private static long WARN_DAY = -1L;
    private static long GRAIN_BUY_SPENT = 0L;

    /** 兜底收购项（目录没加载时用） */
    private static final String[] GRAIN_ITEMS = {
            "minecraft:wheat", "minecraft:carrot", "minecraft:potato",
            "minecraft:beetroot", "minecraft:bread"};

    /**
     * 收购项：优先取共享贸易品目录（goods.json）里 FOOD 类的「农作物」。
     *
     * 判据：品类 FOOD + 有定价 + 制作难度 ≤ regulatorGrainMaxTier（默认 0 = 生鲜作物）。
     * 目录没加载/没有条目时退回内置 5 种。
     */
    /** 作物白名单：只有这些词开头的物品才算"粮食作物" */
    private static final String[] CROP_WORDS = {
        "wheat", "carrot", "potato", "beetroot", "sugar_cane", "pumpkin",
        "melon", "corn", "corncob", "rice", "cabbage", "onion", "tomato",
        "lettuce", "chili", "pepper", "cucumber", "soybean", "peanut",
        "barley", "oat", "rye", "millet", "sorghum", "turnip", "radish",
        "spinach", "kelp", "cocoa", "grape", "strawberry", "blueberry",
        "cranberry", "raspberry", "banana", "avocado", "lemon", "lime",
        "orange", "peach", "pear", "cherry", "apple", "mushroom",
        "cactus", "bamboo", "seed", "beans", "pea", "garlic", "ginger",
        "vanilla", "cinnamon", "salt", "flour", "dough", "yeast"
    };

    /** 是否是"作物"类原料（用于高买） */
    public static boolean isCrop(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String n = name.toLowerCase(java.util.Locale.ROOT);
        for (String w : CROP_WORDS) {
            if (n.contains(w)) {
                return true;
            }
        }
        return false;
    }

    public static java.util.List<String> grainCandidates() {
        var cfg = BotConfig.get();
        java.util.List<String> out = new java.util.ArrayList<>();
        if (cfg.regulatorGrainUseCatalog) {
            int maxTier = Math.max(0, Math.min(3, cfg.regulatorGrainMaxTier));
            try {
                for (String id : com.tangtu.kjbb.RomaGoods.of("FOOD")) {
                    if (id == null || id.isEmpty()) {
                        continue;
                    }
                    long price;
                    try {
                        price = com.tangtu.kjbb.ShopPrices.price(id);
                    } catch (Throwable t) {
                        price = 0L;
                    }
                    if (price <= 0L) {
                        continue;               // 没定价的不收
                    }
                    int tier;
                    try {
                        tier = com.tangtu.kjbb.FoodDifficulty.tier(id);
                    } catch (Throwable t) {
                        tier = 0;
                    }
                    if (tier > maxTier) {
                        continue;               // 复杂料理/宴席不收
                    }
                    // 只收「作物」：名字必须像原料，且不含成品/加工词
                    String nm = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                    if (!isCrop(nm)) {
                        continue;
                    }
                    String[] junk = {"fish_bones", "fishing_line", "fish_mount", "cooked_",
                            "block_item", "sausage", "ribs", "roast", "skewer", "bowl",
                            "soup", "stew", "pie", "cake", "burger", "sandwich",
                            "chips", "popcorn", "dog", "popsicle", "juice", "wine",
                            "ale", "beer", "whiskey", "vodka", "cocktail", "jam",
                            "jelly", "candy", "cookie", "donut", "pancake", "toast"};
                    boolean bad = false;
                    for (String w : junk) {
                        if (nm.contains(w)) { bad = true; break; }
                    }
                    if (bad) {
                        continue;
                    }
                    out.add(id);
                }
            } catch (Throwable ignored) {
                // 目录未加载
            }
            java.util.Collections.sort(out);
        }
        if (out.isEmpty()) {
            out.addAll(java.util.Arrays.asList(GRAIN_ITEMS));
        }
        return out;
    }

    /**
     * 国库用「高于市价」的价格向玩家收粮。
     *
     * 与常平仓的区别：常平仓是「跌了才收」（托价，已关），
     * 这里是**常驻**的高价收购口，让玩家随时能把粮食换成钱。
     * 价格 = 基准价 × regulatorGrainBuyFactor(1.15)，每天预算 regulatorGrainBuyBudget。
     */
    private static void grainBuyAct(BotConfig cfg) {
        String reg = MarketBots.regulatorName();
        if (reg == null) {
            return;
        }
        long day = com.tangtu.kjbb.ServerCalendar.currentDay();
        if (GRAIN_BUY_DAY != day) {
            GRAIN_BUY_DAY = day;
            GRAIN_BUY_TODAY.clear();
            GRAIN_BUY_SPENT = 0L;
        }
        long budget = Math.max(0L, cfg.regulatorGrainBuyBudget);
        try {
            double mul2 = com.tangtu.kjbb.MarketBots.regulatorSurplusMul();
            budget = (long) Math.max(0L, Math.min(50_000_000L, Math.round(budget * mul2)));
        } catch (Throwable ignored) {
            // ignore
        }
        if (budget <= 0L || GRAIN_BUY_SPENT >= budget) {
            return;
        }
        long treasuryCash = BotLedger.money(reg);
        if (treasuryCash < budget / 4L) {
            return;                       // 国库自己都没钱了，别硬收
        }
        // 现金警戒线：低于阈值停止高买
        if (treasuryCash < cfg.regulatorCashWarnHighBuy) {
            if (WARN_DAY != day) {
                WARN_DAY = day;
                DebugLog.log("\u56fd\u5e93\u73b0\u91d1 " + treasuryCash + " < \u8b66\u6212\u7ebf "
                        + cfg.regulatorCashWarnHighBuy + "\uff0c\u6682\u505c\u9ad8\u4e70\u7cae\u98df");
            }
            return;
        }
        int kinds = Math.max(0, Math.min(12, cfg.regulatorGrainBuyKinds));
        int placed = 0;
        java.util.List<String> candidates = grainCandidates();
        for (String item : candidates) {
            if (placed >= kinds) {
                break;
            }
            if (GRAIN_BUY_TODAY.contains(item)) {
                continue;
            }
            long base = 0L;
            try {
                base = com.tangtu.kjbb.ShopPrices.price(item);
            } catch (Throwable ignored) {
                base = 0L;
            }
            if (base <= 0L) {
                continue;
            }
            // 至少比基准价高 1 币，避免低价物品被取整抹掉溢价
            long price = Math.max(base + 1L,
                    Math.round(base * Math.max(1.0, cfg.regulatorGrainBuyFactor)));
            int count = Math.max(1, cfg.regulatorGrainBuyCount);
            long total = price * count;
            long left = budget - GRAIN_BUY_SPENT;
            // 预算按剩余种类均分，避免先到先得把预算吃光
            {
                int kindsLeft = Math.max(1, kinds - placed);
                long share = Math.max(1L, (budget - GRAIN_BUY_SPENT) / kindsLeft);
                if (left > share * 3L) { left = share * 3L; }
            }
            if (total > left) {
                count = (int) Math.max(0L, left / Math.max(1L, price));
                if (count < 16) { continue; }   // 太少不值得挂
                total = price * count;
            }
            if (BotLedger.money(reg) < total) {
                break;
            }
            com.tangtu.kjbb.OrderBook.add(reg, item, count, price, reg);
            BotLedger.addMoney(reg, -total);
            GRAIN_BUY_SPENT += total;
            GRAIN_BUY_TODAY.add(item);
            placed++;
            com.tangtu.kjbb.BotTrades.log(reg, "-", item, count, price, "\u9ad8\u4e70\u7cae\u98df");
            com.tangtu.kjbb.DebugLog.log("\u56fd\u5e93\u9ad8\u4e70 " + item + " x" + count
                    + " @" + price + "\uff08\u57fa\u51c6 " + base + "\uff0c\u6ea2\u4ef7 "
                    + Math.round((price / (double) base - 1.0) * 100) + "%\uff09");
        }
    }

    // ---------------- 国库按量放货 ----------------

    private static final java.util.Set<String> STOCK_SOLD_TODAY = new java.util.HashSet<>();
    private static long STOCK_SELL_DAY = -1L;

    /**
     * 库存过多时向玩家售卖（不看价格，只看存量）。
     *
     * 和「价格调控」无关：涨跌都不管，只要单种库存超过阈值，
     * 就把超出部分挂到市场上卖，避免几十万资金沉在仓库里。
     */
    private static void stockSellAct(BotConfig cfg) {
        if (!cfg.regulatorStockSellEnabled) {
            return;
        }
        String reg = MarketBots.regulatorName();
        if (reg == null) {
            return;
        }
        load();
        long day = com.tangtu.kjbb.ServerCalendar.currentDay();
        if (STOCK_SELL_DAY != day) {
            STOCK_SELL_DAY = day;
            STOCK_SOLD_TODAY.clear();
        }
        int threshold = Math.max(1, cfg.regulatorStockSellThreshold);
        int maxKinds = Math.max(0, Math.min(24, cfg.regulatorStockSellDailyKinds));
        int maxPer = Math.max(1, cfg.regulatorStockSellMaxPerItem);
        try {
            double mul = com.tangtu.kjbb.MarketBots.regulatorSurplusMul();
            maxPer = (int) Math.max(1L, Math.min(100_000L, Math.round(maxPer * mul)));
        } catch (Throwable ignored) {
            // ignore
        }
        if (maxKinds <= 0) {
            return;
        }
        var stock = com.tangtu.kjbb.BotLedger.stockMap(reg);
        if (stock.isEmpty()) {
            return;
        }
        java.util.Set<String> onSale = new java.util.HashSet<>();
        for (com.tangtu.kjbb.OfferBook.Row r : com.tangtu.kjbb.OfferBook.of(reg)) {
            onSale.add(r.itemId);
        }
        // 先卖超出最多的（超量大的优先）
        java.util.List<java.util.Map.Entry<String, Integer>> list =
                new java.util.ArrayList<>(stock.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int placed = 0;
        for (var e : list) {
            if (placed >= maxKinds) {
                break;
            }
            String item = e.getKey();
            int have = e.getValue();
            if (have <= threshold || onSale.contains(item) || STOCK_SOLD_TODAY.contains(item)) {
                continue;
            }
            int excess = have - threshold;
            int count = Math.min(excess, maxPer);
            if (count < 16) {
                continue;
            }
            long base;
            try {
                base = com.tangtu.kjbb.ShopPrices.price(item);
            } catch (Throwable t) {
                base = 0L;
            }
            if (base <= 0L) {
                continue;
            }
            long price = Math.max(1L,
                    Math.round(base * Math.max(0.5, cfg.regulatorStockSellPriceFactor)));
            com.tangtu.kjbb.OfferBook.add(reg, item, count, price);
            com.tangtu.kjbb.BotLedger.addStock(reg, item, -count);
            com.tangtu.kjbb.BotTrades.log(reg, "-", item, count, price, "\u6309\u91cf\u653e\u8d27");
            com.tangtu.kjbb.DebugLog.log("\u56fd\u5e93\u6309\u91cf\u653e\u8d27 " + item + " x" + count
                    + " @" + price + "\uff08\u5e93\u5b58 " + have + " > \u9608\u503c "
                    + threshold + "\uff09");
            STOCK_SOLD_TODAY.add(item);
            placed++;
        }
    }

    /** 今天已经放过的物品 */
    private static final java.util.Set<String> SOLD_TODAY = new java.util.HashSet<>();
    private static long SELL_DAY = -1L;

    /**
     * 国库放货：市价明显高于基准价时，把库存挂出来卖。
     *
     * 和常平仓收购是一对：
     *   跌了收（act）、涨了放（sellAct）—— 这才是一个完整的平准机制。
     * 放出来的货走 OfferBook，玩家/机器人买到后货款进国库。
     */
    private static void sellAct(BotConfig cfg) {
        if (!cfg.regulatorSellEnabled) {
            return;
        }
        String reg = MarketBots.regulatorName();
        if (reg == null) {
            return;
        }
        load();
        long day = com.tangtu.kjbb.ServerCalendar.currentDay();
        if (SELL_DAY != day) {
            SELL_DAY = day;
            SOLD_TODAY.clear();
        }
        int kinds = Math.max(0, cfg.regulatorSellDailyKinds);
        if (kinds <= 0) {
            return;
        }
        var stock = com.tangtu.kjbb.BotLedger.stockMap(reg);
        if (stock.isEmpty()) {
            return;
        }
        // 已有在售单的物品不重复挂
        java.util.Set<String> onSale = new java.util.HashSet<>();
        for (com.tangtu.kjbb.OfferBook.Row r : com.tangtu.kjbb.OfferBook.of(reg)) {
            onSale.add(r.itemId);
        }
        int placed = 0;
        for (var e : new java.util.ArrayList<>(stock.entrySet())) {
            if (placed >= kinds) {
                break;
            }
            String item = e.getKey();
            int have = e.getValue();
            if (have <= 0 || onSale.contains(item) || SOLD_TODAY.contains(item)) {
                continue;
            }
            long ref = 0L;
            try {
                ref = com.tangtu.kjbb.ShopPrices.price(item);
            } catch (Throwable ignored) {
                ref = 0L;
            }
            if (ref <= 0L) {
                continue;
            }
            long cur = 0L;
            try {
                cur = com.tangtu.kjbb.MarketPrice.price(item);
            } catch (Throwable ignored) {
                cur = 0L;
            }
            if (cur <= 0L) {
                continue;
            }
            double band = Math.max(1.0, cfg.regulatorSellBand);
            if (cur < ref * band) {
                continue;                        // 价格没涨上来，不砸盘
            }
            int count = (int) Math.min(
                    Math.min(have / 4L, Math.max(1, cfg.regulatorSellMaxPerItem)),
                    com.tangtu.kjbb.MarketBots.stockCapOf(item));
            if (count < 8) {
                continue;
            }
            long price = Math.max(2L, Math.round(ref * Math.max(0.5, cfg.regulatorSellPriceFactor)));
            com.tangtu.kjbb.OfferBook.add(reg, item, count, price);
            com.tangtu.kjbb.BotLedger.addStock(reg, item, -count);
            com.tangtu.kjbb.BotTrades.log(reg, "-", item, count, price, "\u56fd\u5e93\u653e\u8d27");
            com.tangtu.kjbb.DebugLog.log("\u56fd\u5e93\u653e\u8d27 " + item + " x" + count
                    + " @" + price + "\uff08\u5e02\u4ef7 " + cur + " / \u57fa\u51c6 " + ref + "\uff09");
            SOLD_TODAY.add(item);
            placed++;
        }
    }

    private static void act(BotConfig cfg) {
        String reg = MarketBots.regulatorName();
        if (reg == null) {
            return;
        }
        String citizen = MarketBots.citizenName();
        if (citizen == null) {
            return;
        }
        load();
        long day = com.tangtu.kjbb.ServerCalendar.currentDay();
        rollDay(day);

        long budget = Math.max(0L, cfg.regulatorGranaryDailyBudget);
        long left = budget - SPENT_TODAY;
        if (left <= 0L) {
            return;
        }
        long treasury = BotLedger.money(reg);
        if (treasury < cfg.minMoney) {
            return;
        }
        left = Math.min(left, treasury - cfg.minMoney);
        if (left <= 0L) {
            return;
        }

        long pop = Math.max(1L, Math.round(MarketBots.citizenPopulation()));
        // 诊断（每 5 分钟一次）：把判断依据写进文件，便于排查为什么没收购
        long nowMs = ServerClock.now();
        if (nowMs - LAST_DIAG > 300_000L) {
            LAST_DIAG = nowMs;
            try {
                long wBase = ShopPrices.price("minecraft:wheat");
                long wMarket = MarketPrice.price("minecraft:wheat");
                int wHave = BotLedger.stock(reg, "minecraft:wheat");
                DebugLog.log("\u5e38\u5e73\u4ed3\u68c0\u67e5 enabled=" + cfg.regulatorGranaryEnabled
                        + " band=" + cfg.regulatorGranaryBuyBand
                        + " targetDays=" + cfg.regulatorGranaryTargetDays
                        + " budget=" + cfg.regulatorGranaryDailyBudget
                        + " treasury=" + treasury
                        + " pop=" + pop
                        + " wheat(base=" + wBase + ", market=" + wMarket + ", stock=" + wHave + ")");
            } catch (Throwable ignored) {
                // ignore
            }
        }
        int targetDays = Math.max(1, cfg.regulatorGranaryTargetDays);
        // 目标储备（按小麦当量折算成件数）
        long targetPerItem = pop * Math.max(1, cfg.citizenFoodPerDay)
                * targetDays / 2L;      // 2 饱食度 ≈ 1 件粮

        String[] grains = {"minecraft:wheat", "minecraft:carrot",
                "minecraft:potato", "minecraft:beetroot", "minecraft:bread"};
        int placed = 0;
        for (String item : grains) {
            if (left <= 0L) {
                break;
            }
            long base = ShopPrices.price(item);
            if (base <= 0L) {
                continue;
            }
            long have = BotLedger.stock(reg, item);
            if (have >= targetPerItem) {
                continue;                  // 储备已够
            }
            long market = MarketPrice.price(item);
            if (market <= 0L) {
                market = base;
            }
            // 只在市价偏低时收
            if (market > base * Math.max(0.5, cfg.regulatorGranaryBuyBand)) {
                continue;
            }
            // 同一天、同一物品只挂一张常平仓单（不影响 maintainDepth 的单）
            if (PLACED_TODAY.contains(item)) {
                continue;
            }
            long price = Math.max(1L, Math.round(base * Math.max(0.5,
                    cfg.regulatorGranaryBuyPriceFactor)));
            long count = Math.min(256L, Math.max(8L, left / Math.max(1L, price)));
            long total = price * count;
            if (total > left) {
                count = Math.max(1L, left / Math.max(1L, price));
                total = price * count;
            }
            if (total <= 0L) {
                continue;
            }
            BotLedger.addMoney(reg, -total);
            OrderBook.add(reg, item, (int) count, price, reg);
            BotTrades.log(reg, "-", item, (int) count, price, "\u5e38\u5e73\u4ed3\u6536\u8d2d");
            SPENT_TODAY += total;
            PLACED_TODAY.add(item);
            left -= total;
            placed++;
            DebugLog.log("\u5e38\u5e73\u4ed3\u6536\u8d2d " + item + " x" + count + " @" + price
                    + " \u4eca\u65e5\u5df2\u82b1 " + SPENT_TODAY + "/" + budget);
            System.out.println("[TangtuEconomical] \u5e38\u5e73\u4ed3\\u6536\u8d2d " + item
                    + " \\u00d7" + count + " @" + price + "\\uff08\\u4eca\\u65e5\\u5df2\\u82b1 "
                    + SPENT_TODAY + "/" + budget + "\\uff09");
        }
        // ===== 第二轮：玩家生产过剩的品类托底收购（用经济参考台）=====
        try {
            long cap = Math.min(left, Math.max(0L, cfg.regulatorGranaryDailyBudget / 4));
            for (String item : EconomyAdvisor.surplus(3)) {
                if (cap <= 0L || PLACED_TODAY.contains(item)) {
                    break;
                }
                long base = ShopPrices.price(item);
                if (base <= 0L) {
                    continue;
                }
                long price = Math.max(1L, Math.round(base * 0.85));   // 低于基准收，托底
                long count = Math.min(128L, Math.max(8L, cap / Math.max(1L, price)));
                long total = price * count;
                if (total > cap) {
                    count = Math.max(1L, cap / Math.max(1L, price));
                    total = price * count;
                }
                if (total <= 0L) {
                    continue;
                }
                BotLedger.addMoney(reg, -total);
                OrderBook.add(reg, item, (int) count, price, reg);
                BotTrades.log(reg, "-", item, (int) count, price, "常平仓托底");
                SPENT_TODAY += total;
                PLACED_TODAY.add(item);
                cap -= total;
                placed++;
                DebugLog.log("常平仓托底 " + item + " x" + count + " @" + price
                        + "（玩家过剩品类，今日已花 " + SPENT_TODAY + "/" + budget + "）");
            }
        } catch (Throwable ignored) {
            // 参考台异常不影响粮食收购
        }

        if (placed > 0) {
            save();
            BotLedger.save();
        }
    }


}
