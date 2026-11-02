package com.tangtu.kjbb;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 市场机器人：让交易所里有人气。
 *
 * · 定时（约 25 秒）让每个机器人做一件事：
 *      55% 挂卖单（卖给玩家）
 *      45% 下买单（收购玩家的货）
 * · 挂单 10 分钟没成交自动撤掉
 * · 总挂单 / 总买单有上限，避免刷屏
 * · 玩家接「机器人买单」时不需要发货，直接结账
 */
public final class MarketBots {

    private static boolean WALLET_CLEANED = false;

    public static String[] NAMES = {"经济部", "唐兔国国民", "大A膏手", "唐兔国航运公司"};

    private static final Random RND = new Random();

    /** 同一机器人对同一物品的冷却（毫秒），防止来回倒手刷流水 */
    private static final long TRADE_COOLDOWN = 60_000L;
    private static final java.util.Map<String, Long> LAST_TRADE = new java.util.HashMap<>();

    /** 按活跃度收缩后的上限（每轮由 tick 更新） */
    private static int EFF_OFFERS = 12;
    private static int EFF_ORDERS = 8;

    /** 这些物品不该被机器人当成商品：mod 自己的票据、纸凭据 */
    private static boolean tradable(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        if (itemId.startsWith("kjbankbridge:")) {
            return false;
        }
        return !itemId.equals("minecraft:paper");
    }

    private static boolean cooledDown(String bot, String itemId) {
        String k = bot + "|" + itemId;
        Long last = LAST_TRADE.get(k);
        long now = ServerClock.now();
        if (last != null && now - last < TRADE_COOLDOWN) {
            return false;
        }
        LAST_TRADE.put(k, now);
        if (LAST_TRADE.size() > 2000) {
            LAST_TRADE.entrySet().removeIf(e -> now - e.getValue() > TRADE_COOLDOWN * 4);
        }
        return true;
    }
    private static int tickCounter = 0;

    private MarketBots() {
    }

    /** 名字集合（isBot 用，O(1)） */
    private static final java.util.Set<String> BOT_SET = new java.util.HashSet<>();

    public static boolean isBot(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (BOT_SET.contains(name)) {
            return true;
        }
        // 名册里的系统账户（航运公司、罗马国等）也算机器人
        try {
            return com.tangtu.kjbb.BotAccounts.isBot(name);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 该角色是否参与日常做市（航运公司不参与） */
    /**
     * 投机客清仓：只做金融之后，把压手里的实物按基准价九折挂出去。
     * 每轮最多 8 种、每种 256 件，卖完为止。
     */
    public static int traderLiquidate() {
        try {
            var cfg = BotConfig.get();
            if (!cfg.traderFinancialOnly) {
                return 0;
            }
            String trader = null;
            for (var b : cfg.bots) {
                if (b != null && BotConfig.ROLE_NOISE.equals(b.role)) { trader = b.name; break; }
            }
            if (trader == null || citizenFrozen()) {
                return 0;
            }
            var stock = BotLedger.stockMap(trader);
            if (stock.isEmpty()) {
                return 0;
            }
            java.util.Set<String> onSale = new java.util.HashSet<>();
            for (OfferBook.Row r : OfferBook.of(trader)) { onSale.add(r.itemId); }
            int done = 0;
            for (var e : new java.util.ArrayList<>(stock.entrySet())) {
                if (done >= 8) { break; }
                String item = e.getKey();
                int have = e.getValue();
                if (have <= 0 || onSale.contains(item)) { continue; }
                long base = 0L;
                try { base = ShopPrices.price(item); } catch (Throwable t) { base = 0L; }
                if (base <= 0L) { continue; }
                int count = Math.min(have, Math.min(256, stockCapOf(item)));
                if (count < 8) { continue; }
                // 粮食不碰：直接转给国库，不在市场卖
                if (isFoodItem(item)) {
                    String reg = regulatorName();
                    if (reg != null && !reg.equals(trader)) {
                        BotLedger.addStock(trader, item, -count);
                        BotLedger.addStock(reg, item, count);
                        BotTrades.log(trader, reg, item, count, 0, "\u7cae\u98df\u4ea4\u56fd\u5e93");
                        done++;
                    }
                    continue;
                }
                long price = Math.max(1L, Math.round(base * 0.9));
                BotLedger.addStock(trader, item, -count);
                OfferBook.add(trader, item, count, price);
                BotTrades.log(trader, "-", item, count, price, "\u6e05\u4ed3");
                done++;
            }
            return done;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isMarketRole(String role) {
        if (role == null) {
            return true;
        }
        // 投机客只做金融活动（股票/票据/航次），不参与实物做市
        if (BotConfig.ROLE_NOISE.equals(role)) {
            try {
                if (BotConfig.get().traderFinancialOnly) {
                    return false;
                }
            } catch (Throwable ignored) {
                // 配置读不到就按老行为
            }
        }
        return BotConfig.ROLE_REGULATOR.equals(role)
                || BotConfig.ROLE_STABILIZER.equals(role)
                || BotConfig.ROLE_NOISE.equals(role)
                || BotConfig.ROLE_NEUTRAL.equals(role);
    }

    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (String n : NAMES) {
            out.add(n);
        }
        return out;
    }

    /** 每 100 tick（5 秒）调用一次；内部再降频到约 25 秒 */
    /** 经营周期计数 */
    private static int OPERATE_TICK = 0;
    private static int RETAIL_TICK = 0;
    private static int CLOCK_TICK = 0;
    private static int RETAIL_BUILD_TICK = 0;
    private static int CITIZEN_SUPPLY_TICK = 0;

    /** 当前服务器（供收粮算在线人数） */
    private static MinecraftServer CURRENT_SERVER;

    /** 机器人基础收入 + 消耗原材料 */
    private static void operateCycle() {
        var cfg = BotConfig.get();
        int n = 0;
        for (var entry : BotLedger.PRINCIPAL.entrySet()) {
            String bot = entry.getKey();
            if (bot == null || bot.isEmpty()) {
                continue;
            }
            if (!BotConfig.get().botOperateBots.contains(bot)) {
                continue;   // 只有名单内的机器人参与经营
            }
            // ① 基础收入（国民不领：它靠卖自己种的粮/挖的矿赚钱）
            boolean isCitizen = bot.equals(citizenName());
            long income = isCitizen ? Math.max(0L, cfg.citizenPassiveIncome)
                    : cfg.botIncomePerCycle;
            if (income > 0L) {
                BotLedger.addMoney(bot, income);
            }
            // ② 消耗原材料（优先铁/铜/煤/锌/作物）
            int need = cfg.botConsumePerCycle;
            long used = 0L;
            for (String item : new String[]{"minecraft:iron_ingot", "minecraft:copper_ingot",
                    "create:zinc_ingot", "minecraft:coal", "minecraft:wheat",
                    "minecraft:carrot", "minecraft:potato"}) {
                if (need <= 0) {
                    break;
                }
                int have = BotLedger.stock(bot, item);
                if (have <= 0) {
                    continue;
                }
                int take = Math.min(have, need);
                BotLedger.addStock(bot, item, -take);
                need -= take;
                used += take;
            }
            BotTrades.log("-", bot, "\u7ecf\u8425\u6d88\u8017", (int) used,
                    income, "\u7ecf\u8425");
            n++;
        }
        if (n > 0) {
            BotLedger.save();
            System.out.println("[TangtuEconomical] \u673a\u5668\u4eba\u7ecf\u8425\u5468\u671f\uff1a"
                    + n + " \u5bb6\uff0c\u6bcf\u5bb6 +" + cfg.botIncomePerCycle
                    + " \u5510\u5154\u5e01\uff0c\u6d88\u8017 " + cfg.botConsumePerCycle + " \u4ef6");
        }
    }


    // ==================================================================
    //  唐兔国国民：收粮（小麦/土豆/胡萝卜），价格随收购量浮动
    // ==================================================================

    /** 基础收购价 */
    private static final java.util.Map<String, Long> CITIZEN_BASE = new java.util.LinkedHashMap<>();
    /** 当前收购价 */
    private static final java.util.Map<String, Long> CITIZEN_PRICE = new java.util.LinkedHashMap<>();
    /** 各物品的基准价（回弹目标） */
    private static final java.util.Map<String, Long> CITIZEN_BASE_PRICE = new java.util.LinkedHashMap<>();
    /** 上周期库存（算收购量） */
    private static final java.util.Map<String, Integer> CITIZEN_PREV = new java.util.LinkedHashMap<>();

    static {
        CITIZEN_BASE.put("minecraft:wheat", 6L);
        CITIZEN_BASE.put("minecraft:carrot", 7L);
        CITIZEN_BASE.put("minecraft:bread", 12L);
        CITIZEN_BASE.put("minecraft:iron_ingot", 18L);
        CITIZEN_BASE.put("minecraft:copper_ingot", 15L);
        CITIZEN_BASE.put("minecraft:coal", 12L);
        CITIZEN_PRICE.putAll(CITIZEN_BASE);
    }


    /** 上次吃饭的游戏日 */
    private static long eatenDay = -1L;

    /**
     * 群体增长模型（逻辑斯蒂曲线，参考人口增长）：
     *
     *   人口 N = 资金池 / 人均财富
     *   每日口粮需求 = N（每人每天 1 小麦）
     *   满足度 s = 实际吃到 / 需求
     *   日增长率 = 基础增长率 × (1 − N/承载上限) × s
     *   资金池 += 资金池 × 日增长率
     *
     *   → 粮食充足时先快后慢（逼近上限），缺粮时停止增长
     */
    /** 破产危机状态 */
    private static long LAST_CRISIS_WARN = 0L;
    private static long LAST_CRISIS_SELL = 0L;

    /** 独立人口（持久化，不再由资金池直接换算） */
    private static double CITIZEN_POP = -1.0;
    private static boolean POP_LOADED = false;

    private static java.nio.file.Path popFile() {
        return java.nio.file.Path.of("config", "kjbb", "citizen_pop.txt");
    }

    private static synchronized void loadPop(String citizen) {
        if (POP_LOADED) {
            return;
        }
        POP_LOADED = true;
        try {
            var p = popFile();
            if (java.nio.file.Files.exists(p)) {
                String s = java.nio.file.Files.readString(p,
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                double v = Double.parseDouble(s);
                if (v >= 1.0) {
                    CITIZEN_POP = v;
                    return;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // 首次：按资金池折算
        try {
            long pool = BotLedger.money(citizen);
            CITIZEN_POP = Math.max(1.0, pool / (double) Math.max(1,
                    BotConfig.get().citizenPerCapita));
        } catch (Throwable ignored) {
            CITIZEN_POP = 1.0;
        }
        savePop();
    }

    private static synchronized void savePop() {
        try {
            var p = popFile();
            if (p.getParent() != null) {
                java.nio.file.Files.createDirectories(p.getParent());
            }
            java.nio.file.Files.writeString(p,
                    String.format(java.util.Locale.ROOT, "%.3f", CITIZEN_POP),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static String ticketItemName(String id) {
        try {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            if (it != null) {
                return new net.minecraft.world.item.ItemStack(it).getHoverName().getString();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return id;
    }

    /** 调试用：国民经济内部状态（OP 命令打印） */
    public static java.util.List<String> debugStatus() {
        var out = new java.util.ArrayList<String>();
        var cfg = BotConfig.get();
        String citizen = citizenName();
        loadPop(citizen == null ? "" : citizen);
        loadLayers();
        boolean online;
        int players = 0;
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            players = srv == null ? 0 : srv.getPlayerList().getPlayers().size();
        } catch (Throwable ignored) {
            // ignore
        }
        online = players > 0;
        boolean paused = cfg.citizenPauseWhenEmpty && !online;

        out.add("\u00a78\u2014\u2014 \u56fd\u6c11\u7ecf\u6d4e\u8c03\u8bd5 \u2014\u2014");
        out.add("\u00a77\u5728\u7ebf\u73a9\u5bb6\uff1a\u00a7f" + players
                + "\u00a77\uff08\u542b\u5047\u4eba\uff09\u00a77  \u7ecf\u6d4e\uff1a"
                + (paused ? "\u00a7c\u6682\u505c" : "\u00a7a\u8fd0\u884c\u4e2d"));
        out.add("\u00a77\u4eba\u53e3\uff1a\u00a7f"
                + String.format(java.util.Locale.ROOT, "%.3f", CITIZEN_POP)
                + "\u00a77  \u5bb9\u91cf " + cfg.citizenCarryCapacity
                + "\u00a77  \u9636\u5c42\uff1a\u6e29\u9971 "
                + Math.round(LAYER_POOR * 100) + "% / \u5c0f\u5eb7 "
                + Math.round(LAYER_MID * 100) + "% / \u5bcc\u8db3 "
                + Math.round((1 - LAYER_POOR - LAYER_MID) * 100) + "%");
        if (citizen != null) {
            out.add("\u00a77\u8d44\u91d1\uff1a\u00a7f" + BotLedger.money(citizen)
                    + "\u00a77  \u5e93\u5b58\u54c1\u7c7b\uff1a\u00a7f"
                    + BotLedger.stockMap(citizen).size()
                    + "\u00a77  \u8b66\u6212\u7ebf\uff1a\u00a7f"
                    + (long) (cfg.minMoney * Math.max(1.0, cfg.citizenCrisisWarnRatio)));
            out.add("\u00a77\u7279\u6b8a\u5546\u54c1\uff1a\u6982\u7387 \u00a7f"
                + cfg.citizenSpecialChance + "%\u00a77  \u4ef7\u683c\uff1a\u00a7f"
                + cfg.citizenSpecialPrice + " / " + cfg.citizenSpecialApplePrice);
        out.add("\u00a77\u635f\u8017\uff08\u6bcf\u65e5\uff09\uff1a\u98df\u7269 "
                    + cfg.citizenSpoilageFoodPermille / 10.0 + "% / \u9ad8\u7ea7 "
                    + cfg.citizenSpoilagePremiumPermille / 10.0 + "% / \u5efa\u6750 "
                    + cfg.citizenSpoilageMaterialPermille / 10.0 + "%");
            StringBuilder sb = new StringBuilder("\u00a77\u4e3b\u8981\u5b58\u8d27\uff1a\u00a7f");
            int n = 0;
            for (var e : BotLedger.stockMap(citizen).entrySet()) {
                if (n >= 6) {
                    break;
                }
                if (e.getValue() <= 0) {
                    continue;
                }
                if (n > 0) {
                    sb.append("\u00a77\u3001\u00a7f");
                }
                sb.append(ticketItemName(e.getKey())).append(" \u00d7").append(e.getValue());
                n++;
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** 当前人口（独立状态；报表与各类需求都用它） */
        /**
     * 常平仓力度：国库现金越盈余，力度越大（反之越小）。
     *   现金 300 万 → 1.0 倍（基准）
     *   现金 1500 万 → 5.0 倍（上限 3.0）
     *   现金  30 万 → 0.1 倍（下限 0.2）
     */
    public static double regulatorSurplusMul() {
        try {
            String reg = regulatorName();
            if (reg == null) {
                return 1.0;
            }
            long cash = BotLedger.money(reg);
            double m = cash / 3_000_000.0;
            return Math.max(0.2, Math.min(3.0, m));
        } catch (Throwable t) {
            return 1.0;
        }
    }

    /** 人口倍率：人口越多，可同时持有的挂单/订单越多（上限 4 倍） */
    public static double popMul() {
        try {
            long pop = citizenPopulation();
            return Math.max(1.0, Math.min(4.0, 1.0 + pop / 1000.0));
        } catch (Throwable t) {
            return 1.0;
        }
    }

public static long citizenPopulation() {
        String c = citizenName();
        loadPop(c == null ? "" : c);
        return Math.max(1L, Math.round(CITIZEN_POP));
    }

    /** 阶层占比（温饱 / 小康），富足 = 剩余；持久化到 config/kjbb/citizen_layers.txt */
    private static double LAYER_POOR = 0.60;
    private static double LAYER_MID = 0.30;
    private static boolean LAYERS_LOADED = false;

    /** 各阶层的基础生育率系数（温饱高、富足低） */
    private static final double[] LAYER_FERTILITY = {1.00, 0.60, 0.30};

    private static java.nio.file.Path layersFile() {
        return java.nio.file.Path.of("config", "kjbb", "citizen_layers.txt");
    }

    private static synchronized void loadLayers() {
        if (LAYERS_LOADED) {
            return;
        }
        LAYERS_LOADED = true;
        try {
            var p = layersFile();
            if (!java.nio.file.Files.exists(p)) {
                return;
            }
            String line = java.nio.file.Files.readString(p,
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            String[] f = line.split("\\|");
            if (f.length >= 2) {
                double poor = Double.parseDouble(f[0].trim());
                double mid = Double.parseDouble(f[1].trim());
                if (poor > 0 && mid >= 0 && poor + mid <= 1.0) {
                    LAYER_POOR = poor;
                    LAYER_MID = mid;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static synchronized void saveLayers() {
        try {
            var p = layersFile();
            if (p.getParent() != null) {
                java.nio.file.Files.createDirectories(p.getParent());
            }
            java.nio.file.Files.writeString(p,
                    String.format(java.util.Locale.ROOT, "%.4f|%.4f", LAYER_POOR, LAYER_MID),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 国民轻量收购计数 */
    private static int CITIZEN_LIGHT_TICK = 0;

    /** 票据回收检查计数 */
    private static int RESALE_TICK = 0;

    /** 满意度的平滑值（0..1）：避免「今天吃饱就立刻爆发式增长」 */
    private static double SMOOTH_SATISFACTION = 0.0;
    /** 上一次结算当天的满意度（0..1） */
    private static double LAST_SATISFACTION = 0.0;

    public static double lastSatisfaction() {
        return LAST_SATISFACTION;
    }

    public static double smoothSatisfaction() {
        return SMOOTH_SATISFACTION;
    }

    /**
     * 仓库固定损耗：模拟腐坏 / 鼠咬 / 过期
     *
     *   普通食物  20‰/日
     *   高级食物  30‰/日
     *   建材       3‰/日
     *   珍宝       0（不损耗）
     */
    /** 经济暂停日志只打一次 */
    private static boolean PAUSED_LOGGED = false;

    /** 服务器上是否有玩家（真人或假人都算；假人也是 ServerPlayer） */
    private static boolean hasAnyPlayerOnline() {
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null) {
                return false;
            }
            return !srv.getPlayerList().getPlayers().isEmpty();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void applySpoilage(String citizen, long days) {
        if (days <= 0L) {
            return;
        }
        var cfg = BotConfig.get();
        var stock = BotLedger.stockMap(citizen);
        if (stock.isEmpty()) {
            return;
        }
        long lostFood = 0L;
        long lostMat = 0L;
        int kinds = 0;
        for (var e : new java.util.ArrayList<>(stock.entrySet())) {
            String item = e.getKey();
            int have = e.getValue();
            if (have <= 0) {
                continue;
            }
            String group = com.tangtu.kjbb.ElasticityReloadListener.stockGroupOf(item);
            int permille;
            if ("food_premium".equals(group)) {
                permille = cfg.citizenSpoilagePremiumPermille;
            } else if ("food_cheap".equals(group)) {
                permille = cfg.citizenSpoilageFoodPermille;
            } else if ("treasure".equals(group)) {
                permille = 0;
            } else {
                permille = cfg.citizenSpoilageMaterialPermille;
            }
            if (permille <= 0) {
                continue;
            }
            long loss = (long) have * permille / 1000L * days;
            // 至少 1 件（只要库存 > 0 且损耗率 > 0），避免小数永远丢不掉
            if (loss <= 0L && permille >= 10) {
                loss = 1L;
            }
            loss = Math.min(loss, have);
            if (loss <= 0L) {
                continue;
            }
            BotLedger.addStock(citizen, item, (int) -loss);
            CITIZEN_PREV.merge(item, (int) -loss, Integer::sum);
            if ("food_premium".equals(group) || "food_cheap".equals(group)) {
                lostFood += loss;
            } else {
                lostMat += loss;
            }
            kinds++;
        }
        if (kinds > 0) {
            BotTrades.log("-", citizen, "\u4ed3\u50a8\u635f\u8017", kinds,
                    lostFood + lostMat, "\u635f\u8017");
            System.out.println("[TangtuEconomical] \u56fd\u6c11\u4ed3\u5e93\u635f\u8017\uff1a\u98df\u7269 "
                    + lostFood + " \u4ef6\uff0c\u5efa\u6750 " + lostMat
                    + " \u4ef6\uff08" + kinds + " \u79cd\u53d7\u5f71\u54cd\uff09");
        }
    }

    /** 从库存吃某一种食物，返回吃到的饱食点数 */
    private static long eatFrom(String citizen, String food, long wantPoints) {
        if (wantPoints <= 0L) {
            return 0L;
        }
        int per = Math.max(1, FoodValue.of(food));
        int have = BotLedger.stock(citizen, food);
        if (have <= 0) {
            return 0L;
        }
        long needCount = (wantPoints + per - 1) / per;
        int take = (int) Math.min(have, needCount);
        if (take <= 0) {
            return 0L;
        }
        BotLedger.addStock(citizen, food, -take);
        // 吃饭不是市场行为：同步下调基准，避免把「吃掉」误判成「没买到」
        CITIZEN_PREV.merge(food, -take, Integer::sum);
        return (long) take * per;
    }

    /** 国民库存里所有食物的饱食度总和 */
    private static long totalFoodPoints(String citizen) {
        long sum = 0L;
        try {
            for (var e : BotLedger.stockMap(citizen).entrySet()) {
                if (!com.tangtu.kjbb.FoodTable.isFood(e.getKey())) {
                    continue;
                }
                sum += (long) com.tangtu.kjbb.FoodTable.value(e.getKey()) * e.getValue();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return sum;
    }

    /** 国库里最适合当口粮的谷物 */
    private static String pickGrain(String regulator) {
        String[] grains = {"minecraft:wheat", "minecraft:carrot", "minecraft:potato",
                "minecraft:beetroot", "minecraft:bread"};
        for (String g : grains) {
            if (BotLedger.stock(regulator, g) > 0) {
                return g;
            }
        }
        return "minecraft:wheat";
    }

    /**
     * 国民自己生产：种粮 + 挖矿
     *
     *   每人每天产出 citizenProductionPerDay 件，按 60% 粮食 / 40% 金属分配
     *   产出直接进国民库存，之后由它挂到市场换成唐兔币
     */
    /** 该物品还能再放多少（受单项库存上限约束） */
    private static long roomFor(String citizen, String itemId, long want) {
        if (want <= 0L) {
            return 0L;
        }
        try {
            int cap = stockCapOf(itemId);
            int now = BotLedger.stock(citizen, itemId);
            return Math.max(0L, Math.min(want, cap - now));
        } catch (Throwable ignored) {
            return want;
        }
    }

    /**
     * 国民向「招标中的航次」交货换份额（相当于它来认购航运公司这趟船）。
     *
     * 规则：
     *   · 只交航次 need 里还缺的货
     *   · 粮食保留 citizenFoodCrisisDays 天的口粮，不动保命粮
     *   · 每种最多交库存的 30%（VoyageBill.contribute 内部也会再夹一次）
     */
    public static int citizenSupplyToVoyage() {
        try {
            String citizen = citizenName();
            if (citizen == null || citizenFrozen()) {
                return 0;
            }
            com.tangtu.kjbb.VoyageBill.Bill b = com.tangtu.kjbb.VoyageBill.openBill();
            if (b == null || b.need == null || b.need.isEmpty()) {
                return 0;
            }
            var cfg = BotConfig.get();
            boolean foodTight = foodSellingBlocked();
            int sent = 0;
            for (var e : new java.util.LinkedHashMap<>(b.need).entrySet()) {
                String item = e.getKey();
                int miss = b.missing(item);
                if (miss <= 0) {
                    continue;
                }
                int have = BotLedger.stock(citizen, item);
                if (have < 32) {
                    continue;
                }
                boolean isFood = com.tangtu.kjbb.RomaGoods.isFood(item)
                        || com.tangtu.kjbb.FoodTable.isFood(item);
                if (isFood && foodTight) {
                    continue;                      // 保命粮不动
                }
                int give = Math.min(miss, (int) Math.max(8, have * 3L / 10L));
                if (give <= 0) {
                    continue;
                }
                String r = com.tangtu.kjbb.VoyageBill.contribute(citizen, item, give);
                if (r != null && r.startsWith("§a")) {
                    sent++;
                    DebugLog.log("国民认购航次 #" + b.id + "：" + item + " x" + give + " → " + r);
                }
            }
            return sent;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 玩家挂了很久还没成交的买单 → 国民该去生产的东西。
     *
     * 判据：OrderBook 里 PENDING、买家是玩家（不是机器人）、挂了超过
     * citizenStaleOrderMinutes 分钟、且该物品在国民可产池里。
     *
     * @return 物品 → 缺口件数（按订单累计）
     */
    public static java.util.Map<String, Integer> stalePlayerDemand() {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        try {
            var cfg = BotConfig.get();
            long mins = Math.max(1, cfg.citizenStaleOrderMinutes);
            long cutoff = ServerClock.now() - mins * 60_000L;
            java.util.Set<String> can = new java.util.HashSet<>(produciblePool());
            for (com.tangtu.kjbb.OrderBook.Row r : com.tangtu.kjbb.OrderBook.byStatus(
                    com.tangtu.kjbb.OrderBook.PENDING)) {
                if (r == null || r.itemId == null || r.count <= 0) {
                    continue;
                }
                if (r.created <= 0L || r.created > cutoff) {
                    continue;                  // 挂得还不够久
                }
                if (isBot(r.buyer)) {
                    continue;                  // 只看玩家的单
                }
                if (!can.contains(r.itemId)) {
                    continue;                  // 产不出来就不硬产
                }
                out.merge(r.itemId, r.count, Integer::sum);
            }
        } catch (Throwable ignored) {
            // 市场没起来就当没有滞留需求
        }
        return out;
    }

    /** 把 part 件产能均摊到 items 上，返回实际产出 */
    private static long distributeProduction(String citizen, java.util.List<String> items, long part) {
        long done = 0L;
        if (part <= 0L || items == null || items.isEmpty()) {
            return 0L;
        }
        // 只挑库存最少的 N 种来产，避免几十种各产几件
        int maxKinds = 12;
        try {
            maxKinds = Math.max(1, BotConfig.get().citizenProducePickKinds);
        } catch (Throwable ignored) {
            maxKinds = 12;
        }
        if (items.size() > maxKinds) {
            java.util.List<String> picked = new java.util.ArrayList<>(items);
            picked.sort((a, b) -> Integer.compare(
                    BotLedger.stock(citizen, a), BotLedger.stock(citizen, b)));
            items = picked.subList(0, maxKinds);
        }
        int n = items.size();
        for (int i = 0; i < n && part > 0; i++) {
            long share = part / (n - i);
            if (share <= 0L) {
                continue;
            }
            String item = items.get(i);
            long give = roomFor(citizen, item, share);
            if (give > 0L) {
                BotLedger.addStock(citizen, item, (int) give);
                done += give;
            }
            part -= share;
        }
        return done;
    }

    /** 内置基础产出（永远可产） */
    private static final String[] BASE_FOODS = {
            "minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot"};
    private static final String[] BASE_METALS = {
            "minecraft:iron_ingot", "minecraft:copper_ingot", "create:zinc_ingot", "minecraft:coal"};

    /** 金属类可产池的关键词白名单（只认原矿/锭/基础矿物） */
    private static final String[] METAL_WORDS = {
            "ingot", "nugget", "coal", "redstone", "lapis", "quartz", "flint",
            "_ore", "raw_", "crushed_raw"};
    /** 建材类可产池的关键词白名单 */
    private static final String[] BUILD_WORDS = {
            "_planks", "_log", "stone", "bricks", "glass", "terracotta", "sand",
            "gravel", "clay", "cobblestone", "andesite", "diorite", "granite"};

    private static boolean producibleByKeyword(String item, String[] words) {
        String n = item.toLowerCase(java.util.Locale.ROOT);
        String name = n.contains(":") ? n.substring(n.indexOf(':') + 1) : n;
        for (String w : words) {
            if (name.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 是否可以由国民生产（含票据/生成蛋/超难食物过滤） */
    private static boolean citizenCanProduce(String item, int foodMaxTier) {
        // 硬门槛①：不可堆叠的物品（工具/护甲/背包本体/机器）一律不产
        try {
        var it0 = net.minecraft.core.registries.BuiltInRegistries.ITEM
        .get(net.minecraft.resources.ResourceLocation.tryParse(item));
        if (it0 != null && new net.minecraft.world.item.ItemStack(it0).getMaxStackSize() <= 1) {
        return false;
        }
        } catch (Throwable ignored) {
        // 拿不到就按关键词判
        }
        // 硬门槛②：功能性道具/升级/装备关键词一票否决
        String nm0 = item.contains(":") ? item.substring(item.indexOf(':') + 1) : item;
        String[] banned = {"upgrade", "backpack", "_sword", "_pickaxe", "_axe", "_shovel", "_hoe",
        "helmet", "chestplate", "leggings", "boots", "shield", "_bow", "_arrow",
        "machine", "controller", "module", "tool", "wrench", "goggles",
        "seed", "sapling", "spawn_egg", "bucket", "potion"};
        for (String w : banned) {
        if (nm0.contains(w)) {
        return false;
        }
        }
        if (item == null || item.isEmpty() || isTicketItem(item)) {
            return false;
        }
        String n = item.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("spawn_egg") || n.contains("_bucket") || n.contains("creative")) {
            return false;
        }
        try {
            if (ShopPrices.price(item) <= 0L) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        String cat = null;
        try {
            cat = com.tangtu.kjbb.RomaGoods.catOf(item);
        } catch (Throwable ignored) {
            // 目录未加载
        }
        if ("FOOD".equals(cat)) {
            // 只产「原料/作物」，不产熟食成品（排除 cooked_/block_item/香肠/烤肉等）
            String nm = n.contains(":") ? n.substring(n.indexOf(':') + 1) : n;
            String[] cooked = {"cooked_", "_cooked", "block_item", "sausage", "ribs",
                    "_fish_mount", "fish_bones", "fishing_line", "fish_mount",
                    "roast", "scraps", "jerky", "stew", "soup", "pie", "cake", "burger",
                    "sandwich", "dumpling", "noodle", "bowl", "skewer", "salad", "platter",
                    "casserole", "hotpot", "brain", "eyeball", "liver", "heart", "tongue"};
            for (String w : cooked) {
                if (nm.contains(w)) {
                    return false;
                }
            }
            int tier;
            try {
                tier = com.tangtu.kjbb.FoodDifficulty.tier(item);
            } catch (Throwable t) {
                tier = 0;
            }
            return tier <= Math.max(0, foodMaxTier);
        }
        if ("METAL".equals(cat)) {
            return producibleByKeyword(item, METAL_WORDS);
        }
        if ("BUILD".equals(cat)) {
            return producibleByKeyword(item, BUILD_WORDS);
        }
        return false;
    }

    /**
     * 可产池：内置 8 种 + 共享目录里的「基础粮 / 基础金属 / 基础建材」。
     *
     * 扩池的目的：航次需要的货、市场缺的货，只要在池里它就能自己产，
     * 不再局限于固定的 4 种粮食。
     */
    public static java.util.List<String> produciblePool() {
        var cfg = BotConfig.get();
        java.util.LinkedHashSet<String> pool = new java.util.LinkedHashSet<>();
        for (String f : BASE_FOODS) {
            pool.add(f);
        }
        for (String m : BASE_METALS) {
            pool.add(m);
        }
        if (!cfg.citizenProducePoolEnabled) {
            return new java.util.ArrayList<>(pool);
        }
        int cap = Math.max(4, Math.min(200, cfg.citizenProducePoolMaxKinds));
        int maxTier = Math.max(0, Math.min(3, cfg.citizenProduceFoodMaxTier));
        String[] cats = {"FOOD", "METAL", "BUILD"};
        for (String cat : cats) {
            int added = 0;
            java.util.List<String> list;
            try {
                list = com.tangtu.kjbb.RomaGoods.of(cat);
            } catch (Throwable t) {
                continue;
            }
            for (String item : list) {
                if (added >= cap) {
                    break;
                }
                if (pool.contains(item)) {
                    continue;
                }
                if (citizenCanProduce(item, maxTier)) {
                    pool.add(item);
                    added++;
                }
            }
        }
        return new java.util.ArrayList<>(pool);
    }

    /** 航次需求里有、而国民本来就产得出的品种（粮/金属那 8 种） */
    private static java.util.Map<String, Integer> producibleVoyageNeeds() {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        try {
            com.tangtu.kjbb.VoyageBill.Bill b = com.tangtu.kjbb.VoyageBill.openBill();
            if (b == null || b.need == null || b.need.isEmpty()) {
                return out;
            }
            java.util.Set<String> can = new java.util.HashSet<>(produciblePool());
            for (var e : b.need.entrySet()) {
                if (can.contains(e.getKey()) && b.missing(e.getKey()) > 0) {
                    out.put(e.getKey(), b.missing(e.getKey()));
                }
            }
        } catch (Throwable ignored) {
            // 没有航次就当没有需求
        }
        return out;
    }

    /** 测试入口：跑一次生产（无头环境用） */
    public static void productionForTest(String citizen, long days) {
        citizenProduce(citizen, days);
    }

    private static void citizenProduce(String citizen, long days) {
        var cfg = BotConfig.get();
        if (!cfg.citizenProductionEnabled || days <= 0L) {
            return;
        }
        long pop = Math.max(1L, Math.round(citizenPopulation()));
        long total = pop * Math.max(0, cfg.citizenProductionPerDay) * days;
        if (total <= 0L) {
            return;
        }
        // 粮食低于危机线时，产能全部留给口粮，不再为航次/滞留单补产
        boolean foodTight = citizenFoodDays() < Math.max(1,
                BotConfig.get().citizenFoodCrisisDays);

        // ① 先用一部分产能补航次缺口（最多 50%）
        var need = foodTight ? new java.util.LinkedHashMap<String, Integer>()
                : producibleVoyageNeeds();
        long voyagePart = 0L;
        if (!need.isEmpty()) {
            long cap = total / 2L;
            for (var e : need.entrySet()) {
                if (voyagePart >= cap) {
                    break;
                }
                long want = Math.min(e.getValue(), cap - voyagePart);
                if (want <= 0L) {
                    continue;
                }
                long give = roomFor(citizen, e.getKey(), want);
                if (give > 0L) {
                    BotLedger.addStock(citizen, e.getKey(), (int) give);
                    voyagePart += give;
                    DebugLog.log("国民补产航次需求 " + e.getKey() + " x" + give);
                }
            }
        }
        total = Math.max(0L, total - voyagePart);
        if (total <= 0L) {
            if (voyagePart > 0L) {
                BotTrades.log("-", citizen, "\u56fd\u6c11\u751f\u4ea7", (int) voyagePart, 0, "\u751f\u4ea7");
            }
            return;
        }
        // ② 玩家长期买不到的东西：留一部分产能去产
        var stale = foodTight ? new java.util.LinkedHashMap<String, Integer>()
                : stalePlayerDemand();
        long stalePart = 0L;
        if (!stale.isEmpty() && total > 0L) {
            long capStale = total * Math.max(0, Math.min(1000,
                    cfg.citizenStaleDemandSharePermille)) / 1000L;
            for (var e : stale.entrySet()) {
                if (stalePart >= capStale) {
                    break;
                }
                long want = Math.min(e.getValue(), capStale - stalePart);
                if (want <= 0L) {
                    continue;
                }
                long give = roomFor(citizen, e.getKey(), want);
                if (give > 0L) {
                    BotLedger.addStock(citizen, e.getKey(), (int) give);
                    stalePart += give;
                    DebugLog.log("国民补产滞留订单货 " + e.getKey() + " x" + give);
                }
            }
        }
        total = Math.max(0L, total - stalePart);
        if (total <= 0L) {
            if (voyagePart + stalePart > 0L) {
                BotTrades.log("-", citizen, "\u56fd\u6c11\u751f\u4ea7",
                        (int) (voyagePart + stalePart), 0, "\u751f\u4ea7");
            }
            return;
        }

        // ③ 剩余产能：在「可产池」里按品类权重分配（粮 50% / 金属 35% / 建材 15%）
        var pool = produciblePool();
        java.util.List<String> pFoods = new java.util.ArrayList<>();
        java.util.List<String> pMetals = new java.util.ArrayList<>();
        java.util.List<String> pBuilds = new java.util.ArrayList<>();
        for (String item : pool) {
            String cat = null;
            try {
                cat = com.tangtu.kjbb.RomaGoods.catOf(item);
            } catch (Throwable ignored) {
                // 目录未加载
            }
            if ("METAL".equals(cat)) {
                pMetals.add(item);
            } else if ("BUILD".equals(cat)) {
                pBuilds.add(item);
            } else if ("FOOD".equals(cat)) {
                pFoods.add(item);
            } else if (java.util.Arrays.asList(BASE_METALS).contains(item)) {
                pMetals.add(item);
            } else {
                pFoods.add(item);
            }
        }
        if (pFoods.isEmpty()) {
            pFoods.addAll(java.util.Arrays.asList(BASE_FOODS));
        }
        if (pMetals.isEmpty()) {
            pMetals.addAll(java.util.Arrays.asList(BASE_METALS));
        }
        long foodPart = total * 50L / 100L;
        long metalPart = total * 35L / 100L;
        long buildPart = Math.max(0L, total - foodPart - metalPart);

        // 配额溢出转移：某一类仓库满了，用不掉的产能依次转给下一类，
        // 最后再回灌给粮食（粮食最重要，永远优先吃饱）。
        long produced = 0L;
        long usedFood = distributeProduction(citizen, pFoods, foodPart);
        long carry = Math.max(0L, foodPart - usedFood);
        long usedMetal = distributeProduction(citizen, pMetals, metalPart + carry);
        carry = Math.max(0L, metalPart + carry - usedMetal);
        long usedBuild = distributeProduction(citizen, pBuilds, buildPart + carry);
        carry = Math.max(0L, buildPart + carry - usedBuild);
        if (carry > 0L) {
            usedFood += distributeProduction(citizen, pFoods, carry);
        }
        produced = usedFood + usedMetal + usedBuild;
        if (carry > 0L) {
            DebugLog.log("\u56fd\u6c11\u4ed3\u5e93\u9971\u548c\uff0c" + carry
                    + " \u4ef6\u4ea7\u80fd\u65e0\u5904\u53ef\u7528");
        }
        if (foodTight) {
            DebugLog.log("\u56fd\u6c11\u7cae\u98df\u7d27\u5f20\uff0c\u672c\u8f6e\u4ea7\u80fd\u5168\u90e8\u7559\u7ed9\u53e3\u7cae");
        }
        // 按经济参考台，把一部分产能转向「缺货品类」
        try {
            long gapPart = total * 25L / 100L;
            if (gapPart > 0L) {
                for (String item : EconomyAdvisor.shortage(2)) {
                    if (gapPart <= 0L) {
                        break;
                    }
                    long give = roomFor(citizen, item, gapPart);
                    if (give > 0L) {
                        BotLedger.addStock(citizen, item, (int) give);
                        produced += give;
                        gapPart -= give;
                        DebugLog.log("国民补产缺货品 " + item + " x" + give);
                    } else {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 参考台异常不影响正常生产
        }

        if (produced > 0L) {
            BotTrades.log("-", citizen, "\u56fd\u6c11\u751f\u4ea7", (int) produced, 0, "\u751f\u4ea7");
            DebugLog.log("\u56fd\u6c11\u751f\u4ea7 " + produced + " \u4ef6\uff0c\u65e0\u5904\u53ef\u7528 " + carry);
            System.out.println("[TangtuEconomical] \u56fd\u6c11\u751f\u4ea7\uff1a" + produced
                    + " \u4ef6\uff08" + pop + " \u4eba \u00d7 " + cfg.citizenProductionPerDay
                    + " / \u65e5 \u00d7 " + days + " \u65e5\uff09"
                    + (carry > 0L ? "\uff0c" + carry + " \u4ef6\u4ed3\u5e93\u5df2\u6ee1" : ""));
        }
    }

    private static void citizenEat(long day) {
        var cfg = BotConfig.get();
        String citizen = null;
        for (String n : cfg.botOperateBots) {
            if (n != null && !n.isEmpty()) {
                citizen = n;
                break;
            }
        }
        if (citizen == null) {
            return;
        }
        // 服务器上没人（包括假人）→ 国民不吃饭、不损耗，经济暂停
        try {
            if (BotConfig.get().citizenPauseWhenEmpty && !hasAnyPlayerOnline()) {
                if (!PAUSED_LOGGED) {
                    PAUSED_LOGGED = true;
                    System.out.println("[TangtuEconomical] \u670d\u52a1\u5668\u65e0\u4eba\uff0c"
                            + "\u56fd\u6c11\u7ecf\u6d4e\u6682\u505c\uff08\u4e0d\u5403\u996d\u3001\u4e0d\u635f\u8017\uff09");
                }
                return;
            }
            if (PAUSED_LOGGED) {
                PAUSED_LOGGED = false;
                System.out.println("[TangtuEconomical] \u670d\u52a1\u5668\u6709\u4eba\u4e86\uff0c"
                        + "\u56fd\u6c11\u7ecf\u6d4e\u6062\u590d");
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (eatenDay < 0L) {
            eatenDay = day;
            return;
        }
        long elapsed = Math.max(0L, day - eatenDay);
        if (elapsed <= 0L) {
            return;
        }
        // 单次最多结算 citizenMaxCatchupDays 日，防止 /time 跳变导致一次性巨额结算
        int cap = 3;
        try {
            cap = Math.max(1, BotConfig.get().citizenMaxCatchupDays);
        } catch (Throwable ignored) {
            // 用默认 3
        }
        long days = Math.min(cap, elapsed);
        eatenDay = day;
        if (elapsed > days) {
            System.out.println("[TangtuEconomical] \u65f6\u95f4\u8df3\u53d8 " + elapsed
                    + " \u65e5\uff0c\u4ec5\u7ed3\u7b97 " + days + " \u65e5\uff08\u9632\u6b62\u4e00\u6b21\u6027\u5de8\u989d\u635f\u8017\uff09");
        }

        // 先生产：自给自足 + 拿产出换钱
        try {
            citizenProduce(citizen, days);
        } catch (Throwable ignored) {
            // ignore
        }
        // 罗马国也要生产：把粮食和金属转化成机械/油脂/葡萄酒（含保底产能）
        try {
            com.tangtu.kjbb.RomaMarket.productionTick(day, days);
        } catch (Throwable ignored) {
            // ignore
        }
        long pool = BotLedger.money(citizen);
        long population = citizenPopulation();
        // 每人每天需要的饱食度（默认 4 点 ≈ 2 个小麦 / 1 个胡萝卜）
        long needPoints = population * Math.max(1, cfg.citizenFoodPerDay) * days;
        long needWheat = needPoints;   // 后面按饱食度换算

        // 吃：先吃「廉价食物」管饱，再吃「高级食物」提满意度
        long got = 0L;
        long gotCheap = 0L;
        long gotPremium = 0L;
        long gotExotic = 0L;
        var stockNow = BotLedger.stockMap(citizen);
        // 廉价优先（吃饱）
        for (var e : stockNow.entrySet()) {
            if (got >= needPoints) {
                break;
            }
            String food = e.getKey();
            if (!com.tangtu.kjbb.FoodTable.isFood(food)
                    || com.tangtu.kjbb.FoodTiers.isPremium(food)) {
                continue;
            }
            long g = eatFrom(citizen, food, needPoints - got);
            got += g;
            gotCheap += g;
        }
        // 高级食物：吃到 130% 需求为止，多出的 30% 算「吃得讲究」
        long premiumCap = needPoints * 130L / 100L;
        for (var e : stockNow.entrySet()) {
            if (got >= premiumCap) {
                break;
            }
            String food = e.getKey();
            if (!com.tangtu.kjbb.FoodTable.isFood(food)
                    || !com.tangtu.kjbb.FoodTiers.isPremium(food)) {
                continue;
            }
            long g = eatFrom(citizen, food, premiumCap - got);
            got += g;
            gotPremium += g;
            try {
                if (com.tangtu.kjbb.FoodTiers.isExotic(food)) {
                    gotExotic += g;
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        // ---- 基础口粮：国库按折扣价补足，保证不饿死 ----
        long rationGot = 0L;
        try {
            if (cfg.citizenRationEnabled && got < needPoints) {
                long deficit = needPoints - got;
                // 存粮够就不用领（避免国民囤粮转卖）
                long reserve = population * Math.max(1, cfg.citizenFoodPerDay)
                        * Math.max(1, cfg.citizenRationReserveDays);
                long totalFoodInStock = totalFoodPoints(citizen);
                if (totalFoodInStock < reserve) {
                    String regulator = regulatorName();
                    if (regulator != null && !regulator.equals(citizen)) {
                        long unit = Math.max(1L, Math.round(cfg.citizenPricePerFood
                                * Math.max(0.1, cfg.citizenRationDiscount)));
                        // 低卖：再按「基准价 × regulatorGrainSellFactor」压一次，取更便宜的那个
                        if (cfg.regulatorGrainLowSellEnabled) {
                            String g0 = pickGrain(regulator);
                            long baseG = 0L;
                            try {
                                baseG = com.tangtu.kjbb.ShopPrices.price(g0);
                            } catch (Throwable ignored) {
                                baseG = 0L;
                            }
                            if (baseG > 0L) {
                                long low = Math.max(1L, Math.round(baseG
                                        * Math.max(0.1, cfg.regulatorGrainSellFactor)));
                                unit = Math.min(unit, low);
                            }
                        }
                        // 国库有粮就扣库存，没粮就按「补种」凭空产（有上限）
                        long maxRation = deficit;
                        String grain = pickGrain(regulator);
                        long haveGrain = grain == null ? 0L : BotLedger.stock(regulator, grain);
                        long fromStock = Math.min(maxRation, haveGrain);
                        // ② 国库不够的部分：先向玩家买（买挂了低价粮食的玩家单）
                        long stillNeed = maxRation - fromStock;
                        if (stillNeed > 0L) {
                            try {
                                stillNeed -= buyFoodFromPlayers(citizen, stillNeed);
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                        long fromGrow = Math.min(Math.max(0L, stillNeed),
                                population * Math.max(1, cfg.citizenFoodPerDay) * days);
                        long give = fromStock + fromGrow;
                        if (give > 0L) {
                            if (grain != null && fromStock > 0L) {
                                BotLedger.addStock(regulator, grain, (int) -fromStock);
                            }
                            long price = unit * give;
                            long pay = Math.min(price, BotLedger.money(citizen));
                            if (pay > 0L) {
                                BotLedger.addMoney(citizen, -pay);
                                BotLedger.addMoney(regulator, pay);
                                BotTrades.log(citizen, regulator, "\u57fa\u7840\u53e3\u7cae",
                                        (int) give, unit, "\u53e3\u7cae");
                            } else {
                                BotTrades.log(regulator, citizen, "\u57fa\u7840\u53e3\u7cae",
                                        (int) give, 0, "\u6551\u6d4e");
                            }
                            rationGot = give;
                            got += give;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 口粮失败不影响主流程
        }

        double satisfaction = needPoints <= 0 ? 1.0
                : Math.min(1.0, got / (double) needPoints);
        // 只靠口粮吃饱 → 满意度封顶，避免「不靠玩家也能繁衍」
        if (rationGot > 0L && gotPremium <= 0L && gotCheap + rationGot <= needPoints) {
            satisfaction = Math.min(satisfaction,
                    Math.max(0.0, cfg.citizenRationSatisfactionCap));
        }
        // 高级食物占比越高，满意度额外加成（最多 +12%）
        double premiumShare = got <= 0L ? 0.0 : (double) gotPremium / (double) got;
        satisfaction = Math.min(1.0, satisfaction * (1.0 + 0.12 * premiumShare));
        // 异界食物（下界/末地/暮色）额外加成：最多再 +100%
        double exoticShare = got <= 0L ? 0.0 : (double) gotExotic / (double) got;
        satisfaction = Math.min(1.0, satisfaction * (1.0 + 1.00 * exoticShare));

        // 食物充足 ≠ 立刻多生：用「平均满意度」平滑，
        // 一天吃得饱不足以立刻推动人口，要持续吃得饱才行
        SMOOTH_SATISFACTION = SMOOTH_SATISFACTION <= 0.0
                ? satisfaction
                : SMOOTH_SATISFACTION * 0.8 + satisfaction * 0.2;
        LAST_SATISFACTION = satisfaction;
        // 仓库固定损耗
        try {
            applySpoilage(citizen, days);
        } catch (Throwable ignored) {
            // ignore
        }
        // 固定工资：citizenSalary 平摊到 citizenCycleGameDays 天，每天发一次
        try {
            int period = Math.max(1, cfg.citizenCycleGameDays);
            long perDay = Math.max(0L, cfg.citizenSalary) / period;
            long wage = perDay * Math.max(1L, days);
            if (wage > 0L) {
                BotLedger.addMoney(citizen, wage);
                BotTrades.log("-", citizen, "\u5de5\u8d44", 1, wage, "\u5de5\u8d44");
                System.out.println("[TangtuEconomical] \u56fd\u6c11\u5de5\u8d44\uff1a+" + wage
                        + "\uff08" + days + " \u65e5\uff0c\u6bcf\u65e5 " + perDay + "\uff09");
            }
        } catch (Throwable ignored) {
            // ignore
        }
        double growthBasis = Math.min(satisfaction, SMOOTH_SATISFACTION);
        // 生育门禁：吃口粮只能活命，要「玩家供粮」把满意度抬起来才会增人口
        if (SMOOTH_SATISFACTION < cfg.citizenBirthMinSatisfaction) {
            growthBasis = 0.0;
        }
        // 阶层生育率加权：温饱 1.0 / 小康 0.6 / 富足 0.3
        loadLayers();
        double richShare = Math.max(0.0, 1.0 - LAYER_POOR - LAYER_MID);
        double fertility = LAYER_POOR * LAYER_FERTILITY[0]
                + LAYER_MID * LAYER_FERTILITY[1]
                + richShare * LAYER_FERTILITY[2];
        fertility = fertility / 0.60;              // 初始结构下 = 1.0，保持原速度
        // 吃得好 → 富足层变多、温饱层变少（每天慢慢漂移）
        double targetPoor = 0.60 - 0.30 * SMOOTH_SATISFACTION;
        double targetMid = 0.30 + 0.05 * SMOOTH_SATISFACTION;
        LAYER_POOR = LAYER_POOR * 0.90 + targetPoor * 0.10;
        LAYER_MID = LAYER_MID * 0.90 + targetMid * 0.10;
        LAYER_POOR = Math.max(0.25, Math.min(0.75, LAYER_POOR));
        LAYER_MID = Math.max(0.10, Math.min(0.45, LAYER_MID));
        if (LAYER_POOR + LAYER_MID > 0.95) {
            LAYER_POOR = 0.95 - LAYER_MID;
        }
        saveLayers();

        // 逻辑斯蒂增长
        double capacity = Math.max(1.0, cfg.citizenCarryCapacity);
        double load = Math.min(1.0, population / capacity);
        double daily = cfg.citizenDailyGrowthPermille / 1000.0;   // 日增长率
        double rate = daily * (1.0 - load) * growthBasis * days * fertility;
        // 人口独立演化：出生 - 饿死
        double births = CITIZEN_POP * rate;
        double starve = satisfaction >= 0.999 ? 0.0
                : CITIZEN_POP * 0.002 * (1.0 - satisfaction) * days;
        double before = CITIZEN_POP;
        CITIZEN_POP = Math.max(1.0, CITIZEN_POP + births - starve);
        savePop();
        // 新生儿自带人均财富 → 资金池同步增加（钱随人走，不再是凭空注资）
        long delta = cfg.citizenBirthMintEnabled
                ? Math.round((CITIZEN_POP - before) * Math.max(1, cfg.citizenPerCapita))
                : 0L;
        if (delta != 0L) {
            BotLedger.addMoney(citizen, delta);
            BotTrades.log("-", citizen, delta > 0 ? "\u4eba\u53e3\u589e\u957f" : "\u4eba\u53e3\u6d41\u5931",
                    1, Math.abs(delta), "\u589e\u957f");
        }

        BotLedger.save();
        DebugLog.log("\u56fd\u6c11\u996e\u98df \u5ec9\u4ef7 " + gotCheap + " / \u9ad8\u7ea7 "
                + gotPremium + "\uff0c\u6ee1\u610f\u5ea6 " + String.format(java.util.Locale.ROOT, "%.2f", satisfaction)
                + "\uff0c\u4eba\u53e3 " + String.format(java.util.Locale.ROOT, "%.2f", CITIZEN_POP));
        System.out.println("[TangtuEconomical] \u56fd\u6c11\u996e\u98df\uff1a\u5ec9\u4ef7 "
                + gotCheap + " \u70b9 / \u9ad8\u7ea7 " + gotPremium + " \u70b9");
        System.out.println("[TangtuEconomical] \u56fd\u6c11\u9636\u5c42\uff1a\u6e29\u9971 "
                + Math.round(LAYER_POOR * 100) + "% / \u5c0f\u5eb7 "
                + Math.round(LAYER_MID * 100) + "% / \u5bcc\u8db3 "
                + Math.round(richShare * 100) + "%\uff0c\u751f\u80b2\u7cfb\u6570 "
                + String.format(java.util.Locale.ROOT, "%.2f", fertility));
        System.out.println("[TangtuEconomical] \u56fd\u6c11\u4eba\u53e3\u53d8\u5316\uff1a"
                + String.format(java.util.Locale.ROOT, "%.2f", before) + " \u2192 "
                + String.format(java.util.Locale.ROOT, "%.2f", CITIZEN_POP)
                + "\uff08\u51fa\u751f +" + String.format(java.util.Locale.ROOT, "%.2f", births)
                + "\uff0c\u997f\u6b7b -" + String.format(java.util.Locale.ROOT, "%.2f", starve) + "\uff09");
        System.out.println("[TangtuEconomical] \u56fd\u6c11\uff1a\u4eba\u53e3 " + population
                + "\uff0c\u9700\u8981 " + needPoints + " \u70b9\uff0c\u5403\u5230 " + got
                + " \u70b9\uff08" + Math.round(satisfaction * 100) + "%\uff09\uff0c\u8d44\u91d1\u6c60 "
                + BotLedger.money(citizen) + "\uff08+" + delta + "\uff09");
    }



    /**
     * 消费等级（按人口划分）
     *
     *   1 级 温饱：人口 < 20   只买便宜管饱的，价格 ×0.7，3 种 × 128
     *   2 级 小康：人口 20~79  正常消费，      价格 ×1.0，6 种 × 256
     *   3 级 富足：人口 ≥ 80   追求高价值食物，价格 ×1.5，10 种 × 512
     */
    public static int citizenTier() {
        String citizen = null;
        for (String n : BotConfig.get().botOperateBots) {
            if (n != null && !n.isEmpty()) {
                citizen = n;
                break;
            }
        }
        if (citizen == null) {
            return 1;
        }
        long pool = BotLedger.money(citizen);
        long population = citizenPopulation();
        if (population >= 80L) {
            return 3;
        }
        if (population >= 20L) {
            return 2;
        }
        return 1;
    }

    /** 等级名称 */
    public static String citizenTierName() {
        return switch (citizenTier()) {
            case 3 -> "\u5bcc\u8db3";
            case 2 -> "\u5c0f\u5eb7";
            default -> "\u6e29\u9971";
        };
    }

    /** 三层市民的食物偏好权重（可被数据包覆盖） */
    private static double[] layerW(int tier) {
        return DemandReloadListener.current().layerWeights[Math.min(2, Math.max(0, tier - 1))];
    }


    /** 基础农产品（经济部托底），可被数据包覆盖 */
    private static java.util.List<String> farmItems() {
        return DemandReloadListener.current().farmSupport;
    }

    /** 经济部：农产品价格低于参考价 → 挂买单托底 */
    private static void regulatorSupport() {
        var cfg = BotConfig.get();
        if (!cfg.regulatorPriceControlEnabled) {
            return;                 // 不做价格调控
        }
        String reg = null;
        for (var b : cfg.bots) {
            if ("stabilizer".equals(b.role) || "regulator".equals(b.role)) {
                reg = b.name;
                break;
            }
        }
        if (reg == null) {
            return;
        }
        if (BotLedger.money(reg) < 50_000L) {
            return;
        }
        for (String item : farmItems()) {
            long ref = ShopPrices.price(item);
            if (ref <= 0L) {
                continue;
            }
            long cur = MarketPrice.price(item);
            if (cur > 0L && cur >= ref * 0.95) {
                continue;   // 价格已经接近参考价，不用托
            }
            int count = 256;
            long unit = Math.max(2L, Math.round(ref * 0.98));
            long total = unit * count;
            if (BotLedger.money(reg) < total) {
                continue;
            }
            OrderBook.add(reg, item, count, unit, reg);
            BotLedger.addMoney(reg, -total);
            BotTrades.log("\u7ecf\u6d4e\u90e8", "-", item, count, unit, "\u6258\u5e95");
        }
        BotLedger.save();
    }

    /** 上次扫描食物的时间（缓存，避免每 tick 扫注册表） */
    private static long FOOD_SCAN_AT = 0L;
    private static java.util.Map<String, Integer> FOOD_CACHE = new java.util.LinkedHashMap<>();

    private static java.util.Map<String, Integer> foods() {
        java.util.Map<String, int[]> table = FoodTable.table();
        if (!table.isEmpty()) {
            // 兼容：返回等权价值（真正排序时按层偏好重算）
            java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
            for (String k : table.keySet()) {
                m.put(k, FoodTable.value(k));
            }
            return m;
        }
        long now = ServerClock.now();
        if (now - FOOD_SCAN_AT > 600_000L || FOOD_CACHE.isEmpty()) {
            FOOD_CACHE = FoodValue.allFoods();
            FOOD_SCAN_AT = now;
        }
        return FOOD_CACHE;
    }


    /**
     * 价格连续回弹：每分钟向基准价靠拢 1.5%
     *
     * 玩家一次倾销可以把价格砸到基准价的 20%，
     * 但之后即使没人供货，价格也会自己慢慢涨回来。
     */
    private static void priceRecoveryTick() {
        var cfg = BotConfig.get();
        double rate = cfg.citizenPriceRecoverRate / 1000.0;   // 1.5% = 15
        for (var e : new java.util.ArrayList<>(CITIZEN_PRICE.entrySet())) {
            String item = e.getKey();
            Long baseV = CITIZEN_BASE_PRICE.get(item);
            if (baseV == null || baseV <= 0L) {
                continue;
            }
            double cur = e.getValue();
            double baseD = baseV;
            if (Math.abs(cur - baseD) < 0.5) {
                continue;
            }
            double next = cur + (baseD - cur) * rate;
            long lo = Math.round(baseD * 0.2);
            long hi = Math.round(baseD * 1.5);
            long v = Math.max(lo, Math.min(hi, Math.round(next)));
            if (v != e.getValue()) {
                CITIZEN_PRICE.put(item, v);
            }
        }
    }

    /** 国民对某物品的当前估价（成本基准，零售定价用） */
    public static long citizenPriceOf(String item) {
        Long p = CITIZEN_PRICE.get(item);
        if (p != null && p > 0L) {
            return p;
        }
        return ShopPrices.price(item);
    }

    /** 经济部（调节机器人）名字 */
    public static String regulatorName() {
        for (var b : BotConfig.get().bots) {
            if ("stabilizer".equals(b.role) || "regulator".equals(b.role)) {
                return b.name;
            }
        }
        return "\u7ecf\u6d4e\u90e8";
    }

    /** 三层市民同时存在的入口：按人口比例分别采购 */
    private static void citizenBuyFoods(String citizen, long budget) {
        int[] layers = citizenLayers();
        long total = Math.max(1L, (long) layers[0] + layers[1] + layers[2]);
        for (int i = 0; i < 3; i++) {
            if (layers[i] <= 0) {
                continue;
            }
            long part = budget * layers[i] / total;
            if (part <= 0) {
                continue;
            }
            try {
                citizenBuyFoodsTier(citizen, part, i + 1);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 市民分层：温饱 60% / 小康 30% / 富足 10% */
    public static int[] citizenLayers() {
        String citizen = citizenName();
        if (citizen == null) {
            return new int[]{0, 0, 0};
        }
        long population = citizenPopulation();
        loadLayers();
        int poor = (int) Math.max(1L, Math.round(population * LAYER_POOR));
        int mid = (int) Math.max(0L, Math.round(population * LAYER_MID));
        if (poor + mid >= population) {
            mid = Math.max(0, (int) population - poor - 1);
        }
        int rich = (int) Math.max(0L, population - poor - mid);
        return new int[]{poor, mid, rich};
    }

    /**
     * 国民经济主体是谁
     *
     * 优先级：
     *   ① bots.json 里显式配置的 citizenName
     *   ② role = neutral 的机器人（国民经济主体）
     *   ③ 名字里含「国民」的机器人
     *   ④ botOperateBots 里第一个「确实存在于 bots 列表」的名字
     *
     * 以前直接取 botOperateBots[0]，一旦名单里写的是过时名字
     * （例如已删除的「罗马国·乙」），国民经济就会指向一个空壳账户。
     */
    /**
     * 是否是「票据类」物品（提单 / 仓单 / 本票 / 支票 / 空白单据 / 劳动合同）
     *
     * 这类东西代表的是「权利」，不是普通商品：
     *   · 国民不该把它们挂到市场上零售
     *   · 否则等于把「取货权」当货卖，容易出错
     */
    public static boolean isTicketItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String id = itemId.trim().toLowerCase();
        if (!id.contains("kjbankbridge") && !id.startsWith("kjbb:")) {
            return false;
        }
        return id.contains("bill_of_lading")
                || id.contains("store_receipt")
                || id.contains("promissory_note")
                || id.contains(":check")
                || id.contains("blank_form")
                || id.contains("labor_contract")
                || id.contains("address_tag");
    }

    public static String citizenName() {
        var cfg = BotConfig.get();
        // ① 显式配置
        try {
            String want = cfg.citizenName;
            if (want != null && !want.isEmpty() && isBot(want)) {
                return want;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // ② role = neutral
        try {
            for (var b : cfg.bots) {
                if (b != null && "neutral".equalsIgnoreCase(b.role) && isBot(b.name)) {
                    return b.name;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // ③ 名字含「国民」
        try {
            for (String n : BotConfig.names()) {
                if (n != null && n.contains("\u56fd\u6c11")) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // ④ 名单里第一个有效的
        try {
            for (String n : cfg.botOperateBots) {
                if (n != null && !n.isEmpty() && isBot(n)) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    public static String citizenLayerText() {
        int[] l = citizenLayers();
        return "\u6e29\u9971 " + l[0] + " \u00b7 \u5c0f\u5eb7 " + l[1]
                + " \u00b7 \u5bcc\u8db3 " + l[2];
    }




    /** 单物品库存上限：目标库存 × 倍率，最低 256 */
    /**
     * 单次下单数量上限：受物品堆叠上限约束
     *
     *   可堆叠 64 → 不限（沿用 baseCap）
     *   可堆叠 16 → 最多 256
     *   不可堆叠（1）→ 最多 16（不囤 256 个占格子）
     */
    /**
     * 解析机器人标识：支持 名字 / 序号(0,1,2) / 角色(regulator,neutral,noise)
     *
     * @return 机器人名字；无法解析返回 null
     */
    /** 投机客（role = noise）的名字 */
    public static String traderName() {
        try {
            for (var b : BotConfig.get().bots) {
                if (b != null && "noise".equals(b.role)) {
                    return b.name;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    public static String resolveBot(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim();
        if (k.isEmpty()) {
            return null;
        }
        // ① 名字直接匹配
        if (isBot(k)) {
            return k;
        }
        // ② 序号
        try {
            int idx = Integer.parseInt(k);
            var names = BotConfig.names();
            if (idx >= 0 && idx < names.length) {
                return names[idx];
            }
        } catch (Throwable ignored) {
            // 不是数字
        }
        // ③ 角色
        try {
            String role = BotConfig.role(k);
            for (String n : BotConfig.names()) {
                if (("regulator".equalsIgnoreCase(k) || "neutral".equalsIgnoreCase(k)
                        || "noise".equalsIgnoreCase(k))
                        && k.equalsIgnoreCase(BotConfig.role(n))) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    public static int orderCapFor(String itemId, int baseCap) {
        int maxStack = 64;
        try {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            if (it != null) {
                maxStack = Math.max(1, new net.minecraft.world.item.ItemStack(it).getMaxStackSize());
            }
        } catch (Throwable ignored) {
            // 默认 64
        }
        long cap = (long) maxStack * 16L;
        return (int) Math.max(1L, Math.min(baseCap, cap));
    }

    /** 廉价食物的性价比：每 1 唐兔币能买到多少营养点 */
    private static double cheapEfficiency(String itemId, double[] w, int perFood) {
        try {
            int score = FoodTable.score(itemId, w);
            if (score <= 0) {
                return 0.0;
            }
            long price = CITIZEN_PRICE.getOrDefault(itemId,
                    Math.max(2L, Math.round(score * perFood / 1000.0)));
            if (price <= 0L) {
                price = 2L;
            }
            if (com.tangtu.kjbb.FoodPreference.likesCarrot(itemId)) {
                score = (int) Math.round(score * com.tangtu.kjbb.FoodPreference.CARROT_BONUS);
            }
            return score / (double) price;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    public static int stockCapOf(String itemId) {
        try {
            long target = com.tangtu.kjbb.ElasticityReloadListener.scaledTargetStockOf(itemId);
            double ratio = Math.max(0.5, BotConfig.get().citizenStockCapRatio);
            long cap = Math.round(target * ratio);
            return (int) Math.max(256L, Math.min(2_000_000L, cap));
        } catch (Throwable ignored) {
            return 256;
        }
    }

    /** 高级食物的出价倍率（愿意为高级食物多付 50%） */
    private static double premiumPriceFactor(String itemId) {
        return com.tangtu.kjbb.FoodTiers.isPremium(itemId) ? 1.5 : 1.0;
    }

    /** 物品的显示名（可翻译组件，客户端会显示中文） */
    private static net.minecraft.network.chat.Component displayName(String itemId) {
        try {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (rl != null) {
                var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
                if (it != null) {
                    return new net.minecraft.world.item.ItemStack(it).getHoverName();
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        int c = itemId.indexOf(':');
        return net.minecraft.network.chat.Component.literal(c >= 0 ? itemId.substring(c + 1) : itemId);
    }

    /** 大单播报：总价超过阈值才全服公告，避免刷屏 */
    private static void announceBigOrder(String item, int count, long unit, long total) {
        var cfg = BotConfig.get();
        if (total < cfg.bigOrderAnnounceMin) {
            return;
        }
        MinecraftServer server = CURRENT_SERVER;
        if (server == null) {
            return;
        }
        String id = item;
        int c = id.indexOf(':');
        if (c >= 0) {
            id = id.substring(c + 1);
        }
        var msg = net.minecraft.network.chat.Component.literal("\u00a76[\u5510\u5154\u56fd\u56fd\u6c11] \u00a7e\u5927\u5b97\u91c7\u8d2d\uff1a")
                .append(displayName(item))
                .append(net.minecraft.network.chat.Component.literal(" \u00d7" + count
                        + "\uff0c\u51fa\u4ef7 \u00a7b" + unit
                        + "\u00a77/\u4ef6\uff08\u603b\u4ef7 " + total
                        + "\uff09\u00a78\u3000\u00a77/billorder \u63a5\u5355\uff0c24 \u5c0f\u65f6\u5185\u6709\u6548"));
        try {
            server.getPlayerList().broadcastSystemMessage(msg, false);
        } catch (Throwable ignored) {
            // ignore
        }
    }



    /**
     * 国民收购仓单：把「锁在箱子里的存货」按折价吃下来
     *
     * 逻辑：仓单挂牌价 ≤ 货物市价 × discount% → 买入
     *      钱付给卖家，货物记入国民账本库存（虚拟入库）
     */
    private static void citizenBuyReceipts(String citizen) {
        var cfg = BotConfig.get();
        var rows = ReceiptMarket.onSale();
        if (rows.isEmpty()) {
            return;
        }
        int bought = 0;
        for (var r : rows) {
            if (bought >= cfg.citizenReceiptMaxPerCycle) {
                break;
            }
            if (r.seller != null && r.seller.equals(citizen)) {
                continue;
            }
            long value = 0L;
            int totalCount = 0;
            for (String part : r.items.split(";")) {
                int eq = part.lastIndexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String id = part.substring(0, eq);
                int cnt;
                try {
                    cnt = Integer.parseInt(part.substring(eq + 1));
                } catch (Throwable e) {
                    continue;
                }
                long unit = ShopPrices.price(id);
                if (unit <= 0L) {
                    unit = MarketPrice.price(id);
                }
                if (unit <= 0L) {
                    unit = Math.max(2L, FoodTable.value(id) / 100L);
                }
                value += unit * cnt;
                totalCount += cnt;
            }
            if (value <= 0L || totalCount <= 0) {
                continue;
            }
            // 库存已满：票面里任何一项超过上限就不买
            int cap = Math.max(1, cfg.citizenReceiptStockCap);
            boolean full = false;
            for (String part : r.items.split(";")) {
                int eq = part.lastIndexOf('=');
                if (eq <= 0) {
                    continue;
                }
                try {
                    String iid = part.substring(0, eq);
                    int c2 = Integer.parseInt(part.substring(eq + 1));
                    if (BotLedger.stock(citizen, iid) + c2 > cap) {
                        full = true;
                        break;
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            if (full) {
                continue;
            }
            // 总库存上限
            try {
                int totalCap2 = Math.max(1, totalStockCap());
                int totalNow2 = BotLedger.totalItems(citizen);
                if (totalNow2 + totalCount > totalCap2) {
                    System.out.println("[TangtuEconomical] 国民总库存已满 "
                            + totalNow2 + "+" + totalCount + " > " + totalCap2 + "，不收仓单 #" + r.id);
                    continue;
                }
            } catch (Throwable ignored) {
                // ignore
            }
            // 折价门槛
            if (r.price > value * cfg.citizenReceiptDiscount / 100L) {
                continue;
            }
            // 库存越满越不愿意买
            try {
                int cap2 = Math.max(1, itemStockCap());
                long have2 = 0L;
                for (String part2 : r.items.split(";")) {
                    int eq2 = part2.lastIndexOf('=');
                    if (eq2 <= 0) {
                        continue;
                    }
                    try {
                        have2 = Math.max(have2,
                                BotLedger.stock(citizen, part2.substring(0, eq2))
                                        + Integer.parseInt(part2.substring(eq2 + 1)));
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
                double fill2 = Math.min(1.0, (double) have2 / (double) cap2);
                double willing = Math.max(0.10, 1.0 - fill2);
                if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() > willing) {
                    continue;
                }
            } catch (Throwable ignored) {
                // ignore
            }
            if (BotLedger.money(citizen) < r.price + 1000L) {
                continue;
            }
            BotLedger.addMoney(citizen, -r.price);
            Pay.credit(r.seller, r.price);
            // 货物进国民账本
            for (String part : r.items.split(";")) {
                int eq = part.lastIndexOf('=');
                if (eq <= 0) {
                    continue;
                }
                try {
                    BotLedger.addStock(citizen, part.substring(0, eq),
                            Integer.parseInt(part.substring(eq + 1)));
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            ReceiptMarket.setStatus(r.id, ReceiptMarket.SOLD);
            // 实物还在锁定的箱子里：登记坐标，等玩家靠近时清空并解锁
            try {
                String[] xyz = r.pos.split(",");
                ReceiptClearWatcher.add(new net.minecraft.core.BlockPos(
                        Integer.parseInt(xyz[0].trim()),
                        Integer.parseInt(xyz[1].trim()),
                        Integer.parseInt(xyz[2].trim())));
            } catch (Throwable ignored) {
                // ignore
            }
            BotTrades.log(citizen, r.seller, "\u4ed3\u5355", totalCount, r.price, "\u4ed3\u5355\u6536\u8d2d");
            System.out.println("[TangtuEconomical] \u4ed3\u5355\u6536\u8d2d\uff1a#"
                    + r.id + " \u4ef7 " + r.price + "\uff08\u4f30\u503c " + value + "\uff09");
            announceBigOrder("\u4ed3\u5355", totalCount, r.price / Math.max(1, totalCount), r.price);
            bought++;
        }
        if (bought > 0) {
            BotLedger.save();
        }
    }

    /** 稀有物品清单（小概率才会有需求） */
    private static final String[] RARE_ITEMS = {
            "minecraft:diamond", "minecraft:emerald", "minecraft:netherite_scrap",
            "minecraft:netherite_ingot", "minecraft:ancient_debris", "minecraft:echo_shard",
            "minecraft:amethyst_shard", "minecraft:blaze_rod", "minecraft:ender_pearl",
            "minecraft:enchanted_golden_apple", "minecraft:nether_star",
            "create:precision_mechanism", "create:sturdy_sheet"
    };

    /**
     * 稀有物品需求：小概率触发，单价大幅溢价
     *
     *   概率：citizenRareChance（默认 5%）
     *   数量：人口 / 2（最少 4，最多 citizenRareMax 默认 64）
     *   价格：参考价 × (1.5 ~ 2.5)（急求，重重加价）
     */
    private static void citizenRareRequest(String citizen) {
        var cfg = BotConfig.get();
        var demand = DemandReloadListener.current();
        int rareChance = demand.rareChance > 0 ? demand.rareChance : cfg.citizenRareChance;
        if (java.util.concurrent.ThreadLocalRandom.current().nextInt(100) >= rareChance) {
            return;
        }
        long pool = BotLedger.money(citizen);
        long population = citizenPopulation();

        var rareList = demand.rareItems;
        if (rareList.isEmpty()) {
            return;
        }
        String item = rareList.get(java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(rareList.size()));
        long ref = ShopPrices.price(item);
        if (ref <= 0L) {
            ref = MarketPrice.price(item);
        }
        if (ref <= 0L) {
            ref = 200L;
        }
        double lo = demand.rarePremiumMin;
        double hi = Math.max(lo + 0.01, demand.rarePremiumMax);
        double premium = lo + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * (hi - lo);
        long unit = Math.max(2L, Math.round(ref * premium));
        // 珍宝弹性：资金越紧，稀有物品的出价压得越低
        {
            double el = com.tangtu.kjbb.ElasticityReloadListener.treasureOf(item);
            double tight = budgetTightness(citizen, Math.max(1L, unit));
            // 资金太紧 + 弹性太高 → 这轮干脆不下珍宝单
            if (tight >= com.tangtu.kjbb.ElasticityReloadListener.SKIP_TIGHT
                    && el >= com.tangtu.kjbb.ElasticityReloadListener.SKIP_ELASTICITY) {
                System.out.println("[TangtuEconomical] \u73cd\u5b9d\u9700\u6c42\u6682\u505c\uff1a"
                        + item + "\uff08\u7d27\u5f20\u5ea6 "
                        + String.format(java.util.Locale.ROOT, "%.2f", tight)
                        + "\uff0c\u5f39\u6027 " + el + "\uff09");
                return;
            }
            unit = Math.max(2L, Math.round(unit * Math.max(0.35, 1.0 - tight * el)));
        }
        int rareCap = demand.rareMax > 0 ? demand.rareMax : cfg.citizenRareMax;
        int count = (int) Math.max(4L, Math.min(rareCap, population / 2L));
        // 不可堆叠的物品不要一次要太多（按堆叠上限 ×16 封顶，最多也就是 16 个）
        count = orderCapFor(item, count);
        long total = unit * count;
        if (BotLedger.money(citizen) < total + 1000L) {
            return;
        }
        OrderBook.add(citizen, item, count, unit, citizen);
        BotLedger.addMoney(citizen, -total);
        BotTrades.log("\u5510\u5154\u56fd\u56fd\u6c11", "-", item, count, unit, "\u73cd\u5b9d\u9700\u6c42");
        System.out.println("[TangtuEconomical] \u73cd\u5b9d\u9700\u6c42\uff1a" + item
                + " \u00d7" + count + " @" + unit + "\uff08\u603b " + total + "\uff09");
        announceRare(item, count, unit, total);
    }

    /** 稀有需求公告（不论金额，稀有本身值得宣传） */
    private static void announceRare(String item, int count, long unit, long total) {
        MinecraftServer server = CURRENT_SERVER;
        if (server == null) {
            return;
        }
        String id = item;
        int c = id.indexOf(':');
        if (c >= 0) {
            id = id.substring(c + 1);
        }
        var msg = net.minecraft.network.chat.Component.literal("\u00a75[\u5510\u5154\u56fd\u56fd\u6c11] \u00a7d\u73cd\u5b9d\u9700\u6c42\uff1a\u00a7e")
                .append(displayName(item))
                .append(net.minecraft.network.chat.Component.literal(" \u00d7" + count
                        + "\uff0c\u51fa\u4ef7 \u00a7b" + unit
                        + "\u00a77/\u4ef6\uff08\u603b\u4ef7 " + total + "\uff09\u00a78\u3000/billorder \u63a5\u5355"));
        try {
            server.getPlayerList().broadcastSystemMessage(msg, false);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 城市建设可能突然要的工业品 */
    private static final String[] CITY_ITEMS = {
            "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:gold_ingot",
            "create:zinc_ingot", "create:brass_ingot", "create:andesite_alloy",
            "create:precision_mechanism", "create:electron_tube", "create:cogwheel",
            "minecraft:redstone", "minecraft:coal"
    };

    /**
     * 城市建设需求：人口越多越可能突然下一批工业品大单（溢价收购）
     *
     * 三条防坑规则（v3.20）：
     *   ① 单价低于 cityDemandMinPrice 的物品不进池（避免要 3 块的建材）
     *   ② 单笔总额不超过池子的 cityDemandPoolRatio%（避免一单抽干）
     *   ③ 抽 3 个候选、选参考价最高的（贵的更容易被要）
     */
    private static void citizenCityDemand(String citizen) {
        var cfg = BotConfig.get();
        long pool = BotLedger.money(citizen);
        long population = citizenPopulation();

        int chance = (int) Math.min(100L, Math.max(5L, population * 2L));
        if (java.util.concurrent.ThreadLocalRandom.current().nextInt(100) >= chance) {
            return;
        }

        var demand = DemandReloadListener.current();
        var cityList = demand.cityItems;
        if (cityList.isEmpty()) {
            return;
        }
        long poolCap = pool * Math.max(5, cfg.citizenCityDemandPoolRatio) / 100L;
        int kinds = 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
        long spent = 0L;

        for (int k = 0; k < kinds; k++) {
            if (poolCap - spent <= 0) {
                break;
            }
            // 纯随机抽取（便宜建材也允许，城建本来就要大量建材）
            String item = cityList.get(java.util.concurrent.ThreadLocalRandom.current()
                    .nextInt(cityList.size()));
            long ref = ShopPrices.price(item);
            if (ref <= 0L) {
                ref = MarketPrice.price(item);
            }
            if (item == null || ref <= 0L) {
                continue;
            }
            double lo = demand.cityPremiumMin;
            double hi = Math.max(lo + 0.01, demand.cityPremiumMax);
            double premium = lo + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * (hi - lo);
            long unit = Math.max(2L, Math.round(ref * premium));
            // 建材弹性：资金越紧，出价压得越低（数据包 kjbb/elasticity 的 materials 段）
            double matElastic = com.tangtu.kjbb.ElasticityReloadListener.materialOf(item);
            double matTight = budgetTightness(citizen, Math.max(1L, poolCap));
            unit = Math.max(2L, Math.round(unit * Math.max(0.40, 1.0 - matTight * matElastic)));

            int perHead = demand.cityPerHead > 0 ? demand.cityPerHead : cfg.citizenCityDemandPerHead;
            int cap = demand.cityMax > 0 ? demand.cityMax : cfg.citizenCityDemandMax;
            int count = (int) Math.min(cap, Math.max(16L, population * perHead));
            // 不可堆叠的建材同样限 16 个（按堆叠上限 ×16）
            count = orderCapFor(item, count);

            // 单笔总额不超过池子配额
            long allow = poolCap - spent;
            if (unit * count > allow) {
                count = (int) (allow / Math.max(1L, unit));
            }
            if (count < cfg.citizenMinOrderCount) {
                continue;
            }
            long total = unit * count;
            if (BotLedger.money(citizen) < total + 1000L) {
                continue;
            }
            OrderBook.add(citizen, item, count, unit, citizen);
            BotLedger.addMoney(citizen, -total);
            BotTrades.log("\u5510\u5154\u56fd\u56fd\u6c11", "-", item, count, unit, "\u57ce\u5efa\u9700\u6c42");
            System.out.println("[TangtuEconomical] \u57ce\u5efa\u9700\u6c42\uff1a" + item
                    + " \u00d7" + count + " @" + unit + "\uff08\u603b " + total + "\uff09");
            announceBigOrder(item, count, unit, total);
            spent += total;
            poolCap -= total;
        }
        BotLedger.save();
    }


    /** 大城市批发大单：人口越多越大，价格打折 */
    private static void citizenBigOrder(String citizen) {
        var cfg = BotConfig.get();
        long pool = BotLedger.money(citizen);
        long population = citizenPopulation();
        if (population < cfg.citizenBigOrderMinPeople) {
            return;   // 小城不下大单
        }
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 100.0
                >= cfg.citizenBigOrderChance) {
            return;
        }
        var all = foods();
        if (all.isEmpty()) {
            return;
        }
        // 挑最高价值的食物做大单
        String best = null;
        int bestValue = 0;
        for (var e : all.entrySet()) {
            if (e.getValue() > bestValue) {
                bestValue = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null) {
            return;
        }
        int count = (int) Math.min(cfg.citizenBigOrderMax,
                Math.max(64L, population * cfg.citizenBigOrderPerHead));
        // 不可堆叠的食物不要一次买几千个
        count = com.tangtu.kjbb.MarketBots.orderCapFor(best, count);
        // 批量价：正常价 × 0.85
        long unit = Math.max(2L, (long) Math.round(
                bestValue * cfg.citizenPricePerFood / 100.0 * 0.85));
        // 弹性：大单也按「食物/建材」的弹性随资金紧张度打折
        {
            boolean premium = com.tangtu.kjbb.FoodTiers.isPremium(best);
            double el = com.tangtu.kjbb.FoodTable.isFood(best)
                    ? com.tangtu.kjbb.ElasticityReloadListener.of(best, 2, premium)
                    : com.tangtu.kjbb.ElasticityReloadListener.materialOf(best);
            double tight = budgetTightness(citizen, Math.max(1L, unit * count));
            unit = Math.max(2L, Math.round(unit * Math.max(0.40, 1.0 - tight * el)));
        }
        long total = unit * count;
        if (BotLedger.money(citizen) < total) {
            return;
        }
        OrderBook.add(citizen, best, count, unit, citizen);
        BotLedger.addMoney(citizen, -total);
        BotTrades.log("\u5510\u5154\u56fd\u56fd\u6c11", "-", best, count, unit, "\u5927\u57ce\u6279\u53d1");
        System.out.println("[TangtuEconomical] \u56fd\u6c11\u5927\u57ce\u6279\u53d1\uff1a" + best
                + " \u00d7" + count + " @" + unit);
        announceBigOrder(best, count, unit, total);
    }

    /**
     * 单物品库存上限，随人口放大。
     *   人口 500  → 基准 ×2
     *   人口 1000 → 基准 ×3
     *   人口 5000 → 基准 ×11
     */
    public static int itemStockCap() {
        try {
            int base = Math.max(1, BotConfig.get().citizenReceiptStockCap);
            long pop = citizenPopulation();
            long mul = 1L + Math.max(0L, pop / 500L);
            long v = base * mul;
            return (int) Math.min(Integer.MAX_VALUE, Math.max(base, v));
        } catch (Throwable t) {
            return 1024;
        }
    }

    /** 总库存上限，随人口放大（基准 ×(1 + 人口/500)） */
    public static int totalStockCap() {
        try {
            int base = Math.max(1, BotConfig.get().citizenTotalStockCap);
            long pop = citizenPopulation();
            long mul = 1L + Math.max(0L, pop / 500L);
            long v = base * mul;
            return (int) Math.min(Integer.MAX_VALUE, Math.max(base, v));
        } catch (Throwable t) {
            return 50000;
        }
    }

    /** 供货失败冷却：物品 → 到期时间（毫秒） */
    private static final java.util.Map<String, Long> SUPPLY_FAIL = new java.util.concurrent.ConcurrentHashMap<>();

    /** 某物的订单无人供货 → 冷却一段时间，换别的物品买 */
    public static void markSupplyFail(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        long until = ServerClock.now() + 2L * 24_000L * 50L;   // 冷却 2 个游戏日
        SUPPLY_FAIL.put(itemId, until);
        DebugLog.log("供货冷却：" + itemId + " 2 日内不再下单");
    }

    /** 是否处于供货失败冷却期 */
    public static boolean isSupplyFailed(String itemId) {
        if (itemId == null) {
            return false;
        }
        Long until = SUPPLY_FAIL.get(itemId);
        if (until == null) {
            return false;
        }
        if (ServerClock.now() > until) {
            SUPPLY_FAIL.remove(itemId);
            return false;
        }
        return true;
    }

    /**
     * 向玩家买粮：扫市场里「卖家是玩家」的粮食挂单，按市价上限成交。
     * @return 实际买到的营养点
     */
    private static long buyFoodFromPlayers(String citizen, long needPoints) {
        long got = 0L;
        try {
            long cash = BotLedger.money(citizen);
            if (cash <= 0L) {
                return 0L;
            }
            var offers = new java.util.ArrayList<>(com.tangtu.kjbb.OfferBook.open());
            // 便宜的优先
            offers.sort(java.util.Comparator.comparingLong(r -> r.price));
            for (var r : offers) {
                if (got >= needPoints) {
                    break;
                }
                if (r == null || r.count <= 0L) {
                    continue;
                }
                try {
                    if (isBot(r.seller)) {
                        continue;              // 只买玩家的
                    }
                    if (!FoodTable.isFood(r.itemId)) {
                        continue;              // 只买粮食
                    }
                } catch (Throwable ignored) {
                    continue;
                }
                long base = 0L;
                try {
                    base = com.tangtu.kjbb.ShopPrices.price(r.itemId);
                } catch (Throwable ignored) {
                    base = 0L;
                }
                if (base <= 0L || r.price > base * 1.2) {
                    continue;                  // 太贵不买（最多高 20%）
                }
                int take = (int) Math.min(r.count, needPoints - got);
                if (take <= 0) {
                    continue;
                }
                long cost = r.price * take;
                if (cost > BotLedger.money(citizen)) {
                    continue;
                }
                // 从玩家取货
                int removed = com.tangtu.kjbb.OfferBook.takeFromSellerPublic(r.seller, r.itemId, take);
                if (removed <= 0) {
                    continue;
                }
                BotLedger.addMoney(citizen, -cost);
                com.tangtu.kjbb.Pay.credit(r.seller, cost);
                BotLedger.addStock(citizen, r.itemId, removed);
                r.count -= removed;
                if (r.count <= 0) {
                    com.tangtu.kjbb.OfferBook.setStatus(r.id, com.tangtu.kjbb.OfferBook.FILLED);
                }
                got += removed;
                DebugLog.log("国民向玩家买粮 " + r.itemId + " x" + removed + " @" + r.price
                        + " 卖家=" + r.seller);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return got;
    }

    /** 某一层市民的采购（带偏好权重、低值过滤、最少件数） */
    /**
     * 预算紧张度 0..1
     *
     *   1.0 = 钱几乎花光（价格该往下压）
     *   0.0 = 资金充裕
     */
    private static double budgetTightness(String citizen, long budget) {
        try {
            long pool = BotLedger.money(citizen);
            long need = Math.max(1L, budget);
            double ratio = pool / (double) need;
            if (ratio >= 4.0) {
                return 0.0;
            }
            if (ratio <= 1.0) {
                return 1.0;
            }
            return (4.0 - ratio) / 3.0;
        } catch (Throwable ignored) {
            return 0.5;
        }
    }

    private static void citizenBuyFoodsTier(String citizen, long budget, int tier) {
        var cfg = BotConfig.get();
        double priceMul = tier == 3 ? 1.5 : (tier == 2 ? 1.0 : 0.7);
        int perFood = Math.max(1, (int) Math.round(cfg.citizenPricePerFood * priceMul));
        int maxEach = tier == 3 ? cfg.citizenBuyMaxEach * 2
                : (tier == 2 ? cfg.citizenBuyMaxEach : Math.max(8, cfg.citizenBuyMaxEach / 2));

        // 订单条数随人口增长
        long population = citizenPopulation();
        int baseKinds = tier == 1 ? 3 : (tier == 2 ? cfg.citizenBuyKinds : cfg.citizenBuyKinds + 4);
        // 采购种类随人口放大：人口 500 起每多 500 人 +10 种，上限 200
        int kindsCap = (int) Math.min(200L, 40L + Math.max(0L, population / 500L) * 10L);
        int kinds = Math.min(kindsCap, baseKinds + (int) (population / 20));

        double[] w = layerW(tier);
        var foods = foods();
        var list = new java.util.ArrayList<>(foods.keySet());
        // 廉价食物之间可互相替代：按「营养点 / 价格」性价比排序
        list.sort((a, b) -> {
            boolean cheapA = com.tangtu.kjbb.FoodTiers.isCheap(a);
            boolean cheapB = com.tangtu.kjbb.FoodTiers.isCheap(b);
            if (cheapA && cheapB) {
                double ea = cheapEfficiency(a, w, perFood);
                double eb = cheapEfficiency(b, w, perFood);
                int c = Double.compare(eb, ea);
                if (c != 0) {
                    return c;
                }
            }
            int sa = FoodTable.score(a, w);
            int sb = FoodTable.score(b, w);
            boolean ca = com.tangtu.kjbb.FoodPreference.likesCarrot(a);
            boolean cb = com.tangtu.kjbb.FoodPreference.likesCarrot(b);
            if (ca) {
                sa = (int) Math.round(sa * com.tangtu.kjbb.FoodPreference.CARROT_BONUS);
            }
            if (cb) {
                sb = (int) Math.round(sb * com.tangtu.kjbb.FoodPreference.CARROT_BONUS);
            }
            // 制作难度：越难做的越靠后（分数 × 折扣），挤不进本轮的 kinds 名额
            double ra = com.tangtu.kjbb.FoodDifficulty.rankFactor(a);
            double rb = com.tangtu.kjbb.FoodDifficulty.rankFactor(b);
            int c2 = Double.compare(sb * rb, sa * ra);
            if (c2 != 0) {
                return c2;
            }
            return Integer.compare(sb, sa);
        });
        // 廉价食物的最高性价比（用于「太贵就换替代品」判定）
        double bestCheapEff = 0.0;
        for (String f : list) {
            if (com.tangtu.kjbb.FoodTiers.isCheap(f)) {
                bestCheapEff = Math.max(bestCheapEff, cheapEfficiency(f, w, perFood));
            }
        }

        // 胡萝卜偏好：含胡萝卜的食物更愿意买、也愿意多付
        var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (srv != null) {
            com.tangtu.kjbb.FoodPreference.load(srv);
        }

        // 预算紧张度：预算越少，价格越往下压（高级食物压得更狠）
        double tight = budgetTightness(citizen, budget);

        int picked = 0;
        for (String item : list) {
            if (budget <= 0 || picked >= kinds) {
                break;
            }
            int value = FoodTable.score(item, w);
            // 异界食物：稀缺 → 只有 35% 的轮次才考虑买
            boolean exoticItem = false;
            try {
                exoticItem = com.tangtu.kjbb.FoodTiers.isExotic(item);
            } catch (Throwable ignored) {
                // ignore
            }
            if (exoticItem
                    && java.util.concurrent.ThreadLocalRandom.current().nextDouble() > 0.10) {
                continue;
            }
            boolean carrotLike = com.tangtu.kjbb.FoodPreference.likesCarrot(item);
            if (carrotLike) {
                value = (int) Math.round(value * com.tangtu.kjbb.FoodPreference.CARROT_BONUS);
            }
            if (value < cfg.citizenMinFoodScore) {
                continue;   // 低价值食物（碎肉屑等）不收
            }
            if (BotLedger.stock(citizen, item) > stockCapOf(item)) {
                continue;   // 库存达到该物品上限 → 不再收货
            }
            if (isSupplyFailed(item)) {
                continue;   // 上次下单没人供货 → 换别的
            }
            // 廉价食物：性价比低于最优的 75% → 这次不买，去买更划算的替代品
            if (com.tangtu.kjbb.FoodTiers.isCheap(item) && bestCheapEff > 0.0) {
                double eff = cheapEfficiency(item, w, perFood);
                if (eff < bestCheapEff * 0.75) {
                    System.out.println("[TangtuEconomical] \u66ff\u4ee3\uff1a" + item
                            + " \u6027\u4ef7\u6bd4\u504f\u4f4e\uff0c\u6539\u4e70\u66ff\u4ee3\u54c1");
                    continue;
                }
            }
            long basePrice = Math.max(2L, (long) Math.round(value * perFood / 1000.0));
            // 异界食物：出价三倍
            if (exoticItem) {
                basePrice = Math.max(2L, Math.round(basePrice * 3.0));
            }
            // 库存修正：国民库存多 → 降价；库存少 → 涨价
            basePrice = Math.max(2L,
                    Math.round(basePrice * MarketPrice.stockMul(item)));
            // 弹性：高级食物对预算更敏感（便宜食物刚性 0.10，高级 0.45）
            boolean premium = com.tangtu.kjbb.FoodTiers.isPremium(item);
            // 弹性：数据包可调（kjbb/elasticity/*.json），按阶层 + 物品覆盖
            double elasticity = com.tangtu.kjbb.ElasticityReloadListener.of(item, tier, premium);
            double adj = Math.max(0.35, 1.0 - tight * elasticity);
            basePrice = Math.max(2L, Math.round(basePrice * adj));
            CITIZEN_BASE_PRICE.putIfAbsent(item, basePrice);
            long cur = CITIZEN_PRICE.getOrDefault(item, basePrice);
            int prev = CITIZEN_PREV.getOrDefault(item, 0);
            int now = BotLedger.stock(citizen, item);
            int bought = Math.max(0, now - prev);
            CITIZEN_PREV.put(item, now);

            double factor = bought > 0
                    ? Math.max(0.5, 1.0 - Math.min(0.4, bought / (double) maxEach * 0.4))
                    : Math.min(1.5, cur / (double) Math.max(1L, basePrice) + 0.08);
            long next = Math.round(basePrice * factor);
            // 允许跌到基础价的 20%（放开下限）
            next = Math.max(Math.round(basePrice * 0.2), Math.min(Math.round(basePrice * 1.5), next));
            CITIZEN_PRICE.put(item, next);

            int itemCap = orderCapFor(item, maxEach);
            int count = (int) Math.min(itemCap, budget / Math.max(1L, next));
            if (count < cfg.citizenMinOrderCount) {
                continue;   // 不下碎单
            }
            long total = next * count;
            if (total <= 0 || total > budget) {
                continue;
            }
            OrderBook.add(citizen, item, count, next, citizen);
            BotLedger.addMoney(citizen, -total);
            BotTrades.log("\u5510\u5154\u56fd\u56fd\u6c11", "-", item, count, next,
                    tier == 1 ? "\u6e29\u9971\u91c7\u8d2d"
                            : (tier == 2 ? "\u5c0f\u5eb7\u91c7\u8d2d" : "\u5bcc\u8db3\u91c7\u8d2d"));
            budget -= total;
            picked++;
        }
    }


    /** 国民每 30 天：发工资 3000，然后拿它买粮（偶尔买金属/煤） */
    private static void citizenCycle() {
        var cfg = BotConfig.get();
        String citizen = null;
        for (String n : cfg.botOperateBots) {
            if (n != null && !n.isEmpty()) {
                citizen = n;
                break;
            }
        }
        if (citizen == null) {
            return;
        }

        // 工资已由「粮食驱动的人口增长」取代，这里不再固定注资

        long budget = Math.max(0L, BotLedger.money(citizen) * 80L / 100L);

        // ② 买粮：小麦 / 胡萝卜 / 面包
        budget = buyUpTo(citizen, "minecraft:wheat", 6L, budget, 40);
        budget = buyUpTo(citizen, "minecraft:carrot", 7L, budget, 40);
        budget = buyUpTo(citizen, "minecraft:bread", 12L, budget, 30);

        // ③ 偶尔买金属/煤（50% 概率，随机一种）
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.5) {
            String[][] pool = {
                    {"minecraft:iron_ingot", "18"},
                    {"minecraft:copper_ingot", "15"},
                    {"minecraft:coal", "12"},
            };
            String[] pick = pool[java.util.concurrent.ThreadLocalRandom.current()
                    .nextInt(pool.length)];
            budget = buyUpTo(citizen, pick[0], Long.parseLong(pick[1]), budget, 24);
        }

        // 按饱食度自动收购所有食物
        try {
            // 采购预算：默认「只有本轮赚钱才买」，且最多花 citizenBuyCashPermille；
            // 粮食掉到危机线以下则无视盈亏强制补货。
            long poolNow = BotLedger.money(citizen);
            long lastCash = loadCycleCash();
            double foodDays = citizenFoodDays();
            BuyDecision decision = decideFoodPurchase(poolNow, lastCash, foodDays);
            saveCycleCash(poolNow);
            if (decision.buy) {
                DebugLog.log("国民采购：现金 " + poolNow + " 粮食 "
                        + String.format(java.util.Locale.ROOT, "%.1f", foodDays)
                        + " 天 → 预算 " + decision.budget + "（" + decision.reason + "）");
                citizenBuyFoods(citizen, decision.budget);
            } else {
                DebugLog.log("国民暂停采购：现金 " + poolNow + " ≤ 上轮 " + lastCash
                        + "，粮食 " + String.format(java.util.Locale.ROOT, "%.1f", foodDays)
                        + " 天高于危机线（" + decision.reason + "）");
            }
            citizenBigOrder(citizen);
            citizenCityDemand(citizen);
            citizenRareRequest(citizen);
            citizenBuyReceipts(citizen);
            CitizenRetail.buildOffers();
        } catch (Throwable ignored) {
            // ignore
        }

        BotLedger.save();
    }

    /**
     * 按当前浮动价买入，直到花掉一部分预算。
     *
     * @param maxCount 最多买多少件
     * @return 剩余预算
     */
    private static long buyUpTo(String citizen, String item, long basePrice, long budget,
                                int maxCount) {
        if (budget <= 0) {
            return 0L;
        }
        long cur = CITIZEN_PRICE.getOrDefault(item, basePrice);
        CITIZEN_BASE_PRICE.putIfAbsent(item, basePrice);
        int prev = CITIZEN_PREV.getOrDefault(item, 0);
        int now = BotLedger.stock(citizen, item);
        int bought = Math.max(0, now - prev);
        CITIZEN_PREV.put(item, now);

        // 上周期买得多 → 压价；没买到 → 回涨
        double factor = bought > 0 ? Math.pow(0.97, Math.min(40, bought)) : 1.05;
        long next = Math.round(cur * factor);
        long lo = Math.round(basePrice * 0.2);
        long hi = Math.round(basePrice * 3 / 2);
        next = Math.max(lo, Math.min(hi, next));
        CITIZEN_PRICE.put(item, next);

        if (now > 512) {
            return budget;   // 囤满了
        }
        long unit = next;
        int count = (int) Math.max(1L, Math.min(maxCount, budget / Math.max(1L, unit)));
        long total = unit * count;
        if (total <= 0 || total > budget) {
            return budget;
        }
        OrderBook.add(citizen, item, count, unit, citizen);
        BotLedger.addMoney(citizen, -total);
        BotTrades.log("\u5510\u5154\u56fd\u56fd\u6c11", "-", item, count, unit, "\u91c7\u8d2d");
        return budget - total;
    }

    /** 当前国民收购价（调试/展示用） */
    public static String citizenPrices() {
        StringBuilder sb = new StringBuilder();
        for (var e : CITIZEN_PRICE.entrySet()) {
            if (sb.length() > 0) {
                sb.append("\uff1b");
            }
            sb.append(e.getKey()).append(" ").append(e.getValue());
        }
        return sb.toString();
    }


    // ---------------- 国民收粮：按游戏天数（30 天）触发 ----------------

    private static final java.nio.file.Path CITIZEN_DAY_FILE =
            net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("kjbb/citizen_day.txt");

    private static long LAST_CITIZEN_DAY = -1L;

    // ---------------- 采购盈亏闸门 ----------------

    /** 上一轮结算时的现金（用于判断「本轮是否盈利」），落盘 config/kjbb/citizen_cash.txt */
    private static long LAST_CYCLE_CASH = -1L;

    private static java.nio.file.Path cycleCashFile() {
        return java.nio.file.Path.of("config", "kjbb", "citizen_cash.txt");
    }

    private static long loadCycleCash() {
        try {
            var p = cycleCashFile();
            if (!java.nio.file.Files.exists(p)) {
                return -1L;
            }
            String t = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8).trim();
            return t.isEmpty() ? -1L : Long.parseLong(t);
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static void saveCycleCash(long v) {
        try {
            java.nio.file.Files.createDirectories(cycleCashFile().getParent());
            com.tangtu.kjbb.SafeFile.write(cycleCashFile(), String.valueOf(v));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 采购决策结果（纯数据，方便无头测试） */
    public static final class BuyDecision {
        public final boolean buy;
        public final long budget;
        public final boolean profit;
        public final double foodDays;
        public final String reason;

        public BuyDecision(boolean buy, long budget, boolean profit, double foodDays, String reason) {
            this.buy = buy;
            this.budget = budget;
            this.profit = profit;
            this.foodDays = foodDays;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return (buy ? "BUY " : "SKIP ") + "budget=" + budget
                    + " profit=" + profit
                    + " foodDays=" + String.format(java.util.Locale.ROOT, "%.1f", foodDays)
                    + " (" + reason + ")";
        }
    }

    /**
     * 采购决策（纯函数，不读写文件）：只有盈利或粮食告急时才买，且最多花 permille。
     *
     * @param pool     当前现金
     * @param lastCash 上轮现金（<0 表示首次，视为盈利）
     * @param foodDays 当前粮食储备天数
     */
    public static BuyDecision decideFoodPurchase(long pool, long lastCash, double foodDays) {
        var cfg = BotConfig.get();
        boolean profit = lastCash < 0L || pool > lastCash;
        boolean crisis = foodDays < Math.max(1, cfg.citizenFoodCrisisDays);
        boolean want = !cfg.citizenBuyOnlyWhenProfit || profit || crisis;
        if (!want) {
            return new BuyDecision(false, 0L, profit, foodDays,
                    "未盈利且粮食高于危机线 " + cfg.citizenFoodCrisisDays + " 天");
        }
        int pm = Math.max(0, Math.min(1000, cfg.citizenBuyCashPermille));
        long budget = Math.max(0L, pool * pm / 1000L);

        // 储备目标：只补到 citizenFoodReserveDays 天，不超额囤粮
        long perDay = Math.max(1L, Math.round(citizenPopulation()
                * Math.max(1, cfg.citizenFoodPerDay)));
        long target = Math.max(1, cfg.citizenFoodReserveDays) * perDay;
        long have = Math.max(0L, Math.round(foodDays * perDay));
        long gap = Math.max(0L, target - have);
        if (gap <= 0L) {
            return new BuyDecision(false, 0L, profit, foodDays,
                    "储备已达标（" + String.format(java.util.Locale.ROOT, "%.1f", foodDays)
                            + " 天 ≥ 目标 " + cfg.citizenFoodReserveDays + " 天）");
        }
        long gapCost = gap * Math.max(1L, cfg.citizenPricePerFood);
        if (gapCost > 0L) {
            budget = Math.min(budget, gapCost);
        }

        String reason = crisis ? "粮食危机(" + String.format(java.util.Locale.ROOT, "%.1f", foodDays) + "天)"
                : (profit ? "本轮盈利" : "强制采购");
        return new BuyDecision(budget > 0L, budget, profit, foodDays, reason);
    }

    /**
     * 国民的股票投资：用「闲置现金」买交易行里便宜的航次份额。
     *
     * 逻辑：
     *   闲置 = 现金 − citizenInvestReserveCash(6万)
     *   只买「挂单价 ≤ 公允价(每份货值) × 1.15」的票，避免接盘
     *   单笔不超过闲置的 30%，每轮最多 citizenInvestMaxPerRound 笔
     *
     * @return 买到的笔数
     */
    public static int citizenInvestInStocks() {
        try {
            var cfg = BotConfig.get();
            if (!cfg.citizenInvestEnabled) {
                return 0;
            }
            String citizen = citizenName();
            if (citizen == null || citizenFrozen()) {
                return 0;
            }
            long cash = BotLedger.money(citizen);
            long spare = cash - Math.max(0L, cfg.citizenInvestReserveCash);
            if (spare <= 0L) {
                return 0;
            }
            long capPer = spare * Math.max(0, Math.min(1000, cfg.citizenInvestMaxSharePermille)) / 1000L;
            int maxRounds = Math.max(1, Math.min(20, cfg.citizenInvestMaxPerRound));
            int bought = 0;
            for (com.tangtu.kjbb.StockMarket.Row r : com.tangtu.kjbb.StockMarket.open()) {
                if (bought >= maxRounds || spare <= 0L) {
                    break;
                }
                if (r == null) {
                    continue;
                }
                long cost = r.price * (long) Math.max(1, r.shares);
                if (cost <= 0L || cost > spare || cost > capPer) {
                    continue;
                }
                com.tangtu.kjbb.VoyageBill.Bill bill = com.tangtu.kjbb.VoyageBill.get(r.billId);
                if (bill == null || com.tangtu.kjbb.VoyageBill.CLOSED.equals(bill.status)) {
                    continue;
                }
                long fair = bill.shareValue();
                if (fair <= 0L) {
                    continue;
                }
                long maxPay = fair * Math.max(100, cfg.citizenInvestMaxPremiumPermille) / 1000L;
                if (r.price > maxPay) {
                    DebugLog.log("国民放弃买票 #" + r.id + "：单价 " + r.price
                            + " 高于公允价上限 " + maxPay + "（公允 " + fair + "）");
                    continue;
                }
                String res = com.tangtu.kjbb.StockMarket.botBuy(r, citizen);
                if ("ok".equals(res)) {
                    bought++;
                    spare -= cost;
                    DebugLog.log("国民买票 #" + r.id + "：船#" + r.billId + " " + r.shares
                            + " 份 @" + r.price + " = " + cost + "（公允价 " + fair + "）");
                }
            }
            return bought;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ---------------- 保命粮封存（唯一判定点，零售/国库/航次共用） ----------------

    private static boolean FOOD_RESERVING = false;
    private static boolean FOOD_RESERVING_LOADED = false;

    private static java.nio.file.Path foodReserveFile() {
        return java.nio.file.Path.of("config", "kjbb", "citizen_food_reserve.txt");
    }

    private static void loadFoodReserving() {
        if (FOOD_RESERVING_LOADED) {
            return;
        }
        FOOD_RESERVING_LOADED = true;
        try {
            var p = foodReserveFile();
            if (java.nio.file.Files.exists(p)) {
                FOOD_RESERVING = "1".equals(java.nio.file.Files
                        .readString(p, java.nio.charset.StandardCharsets.UTF_8).trim());
            }
        } catch (Throwable ignored) {
            // 默认不封存
        }
    }

    private static void saveFoodReserving() {
        try {
            com.tangtu.kjbb.SafeFile.write(foodReserveFile(), FOOD_RESERVING ? "1" : "0");
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * 粮食是否处于「封存」状态（不再对外卖粮）。
     *
     * 迟滞：跌破 citizenFoodCrisisDays(10) → 封存；回到 citizenFoodResumeDays(15) → 解封。
     * 零售上架、国库供货、航次认购三处都调这一个方法，避免各写一套阈值。
     */
    public static synchronized boolean foodSellingBlocked() {
        try {
            var cfg = BotConfig.get();
            if (!cfg.citizenReserveFoodWhenCrisis) {
                return false;
            }
            loadFoodReserving();
            double days = citizenFoodDays();
            if (days < Math.max(1, cfg.citizenFoodCrisisDays)) {
                if (!FOOD_RESERVING) {
                    FOOD_RESERVING = true;
                    saveFoodReserving();
                    DebugLog.log("国民封存粮食：储备 "
                            + String.format(java.util.Locale.ROOT, "%.1f", days)
                            + " 天 < 危机线 " + cfg.citizenFoodCrisisDays + " 天");
                }
            } else if (days >= Math.max(cfg.citizenFoodCrisisDays + 1, cfg.citizenFoodResumeDays)) {
                if (FOOD_RESERVING) {
                    FOOD_RESERVING = false;
                    saveFoodReserving();
                    DebugLog.log("国民恢复卖粮：储备 "
                            + String.format(java.util.Locale.ROOT, "%.1f", days)
                            + " 天 ≥ 解禁线 " + cfg.citizenFoodResumeDays + " 天");
                }
            }
            return FOOD_RESERVING;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 统一食物判定：共享目录（goods.json）优先，其次营养表/原版组件 */
    public static boolean isFoodItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        try {
            if (com.tangtu.kjbb.RomaGoods.isFood(itemId)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 目录未加载
        }
        try {
            return com.tangtu.kjbb.FoodTable.isFood(itemId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前粮食储备天数 = 粮库存 / (人口 × 每日口粮) */
    public static double citizenFoodDays() {
        String citizen = citizenName();
        if (citizen == null) {
            return 0.0;
        }
        String[] foods = {"minecraft:wheat", "minecraft:carrot", "minecraft:potato",
                "minecraft:beetroot", "minecraft:bread"};
        long have = 0L;
        for (String f : foods) {
            try {
                have += BotLedger.stock(citizen, f);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        double perDay = Math.max(1.0, citizenPopulation() * Math.max(1, BotConfig.get().citizenFoodPerDay));
        return have / perDay;
    }


    private static long loadLastDay() {
        try {
            if (java.nio.file.Files.exists(CITIZEN_DAY_FILE)) {
                return Long.parseLong(java.nio.file.Files
                        .readString(CITIZEN_DAY_FILE, java.nio.charset.StandardCharsets.UTF_8).trim());
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return -1L;
    }

    private static void saveLastDay(long day) {
        try {
            java.nio.file.Files.createDirectories(CITIZEN_DAY_FILE.getParent());
            java.nio.file.Files.writeString(CITIZEN_DAY_FILE, Long.toString(day),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 每游戏日检查一次：满 30 天就收一次粮 */
    private static void citizenDayCheck(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        long day = level.getDayTime() / 24000L;
        if (LAST_CITIZEN_DAY < 0L) {
            LAST_CITIZEN_DAY = loadLastDay();
        }
        if (LAST_CITIZEN_DAY < 0L) {
            LAST_CITIZEN_DAY = day;   // 首次：以当前天为基准
            saveLastDay(day);
            return;
        }
        // 每天吃饭
        try {
            citizenEat(day);
        } catch (Throwable ignored) {
            // ignore
        }

        int period = BotConfig.get().citizenCycleGameDays;
        if (day - LAST_CITIZEN_DAY >= period) {
            LAST_CITIZEN_DAY = day;
            saveLastDay(day);
            try {
                citizenCycle();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 本轮市场计算起点（纳秒）：超预算把重活推到下一轮 */
    private static long TICK_START = 0L;
    private static int DEFERRED = 0;

    private static boolean overBudget() {
        if (TICK_START == 0L) {
            return false;
        }
        int budget = 8;
        try {
            budget = Math.max(1, BotConfig.get().taskBudgetMs);
        } catch (Throwable ignored) {
            // 用默认
        }
        return (System.nanoTime() - TICK_START) / 1_000_000L > budget;
    }

    public static int deferredCount() {
        return DEFERRED;
    }

    public static void tick(MinecraftServer server) {
        TICK_START = System.nanoTime();
        NAMES = BotConfig.names();
        BOT_SET.clear();
        for (String n : NAMES) {
            BOT_SET.add(n);
        }
        CURRENT_SERVER = server;
        int every = Math.max(1, BotConfig.get().intervalTicks / 100);
        // 注意：本方法由 DeliveryWatcher 每 100 tick（5 秒）调用一次
        //       所以这里的计数单位是「5 秒」→ 360 次 ≈ 30 分钟
        // 国民轻量收购：每 30 分钟一轮（30 游戏日的完整周期太久）
        if (++CITIZEN_LIGHT_TICK >= 360) {
            CITIZEN_LIGHT_TICK = 0;
            try {
                citizenLightCycle();
            } catch (Throwable ignored) {
                // ignore
            }
        }

        // 一次性：清理「名字与机器人重名」的钱包账户（余额并入账本）
        if (!WALLET_CLEANED) {
            WALLET_CLEANED = true;
            try {
                java.util.List<String> names = new java.util.ArrayList<>();
                for (BotConfig.Bot b : BotConfig.get().bots) {
                    if (b != null) {
                        names.add(b.name);
                    }
                }
                names.add("唐兔国航运公司");
                String r = BotLedger.absorbWalletDuplicates(names);
                if (r != null && !r.isEmpty()) {
                    DebugLog.log("钱包重名清理：" + r);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }

        // 玩家生产统计：定时落盘
        try {
            com.tangtu.kjbb.PlayerProduction.tickFlush();
        } catch (Throwable ignored) {
            // ignore
        }

        // 大A膏手：股票交易板做市（买卖二手船票）
        try {
            com.tangtu.kjbb.StockTrader.tick(server);
        } catch (Throwable ignored) {
            // ignore
        }

        // 远洋提单：到期掷风险 + 自动发行下一船（每 5 秒检查）
        try {
            com.tangtu.kjbb.VoyageBill.tick(server);
        // 机器人持有的份额自动折现，避免航次卡在 DUE
        try {
            for (com.tangtu.kjbb.VoyageBill.Bill vb : com.tangtu.kjbb.VoyageBill.all()) {
                if (!com.tangtu.kjbb.VoyageBill.DUE.equals(vb.status) || vb.holders.isEmpty()) {
                    continue;
                }
                for (String holder : new java.util.ArrayList<>(vb.holders.keySet())) {
                    boolean isBot;
                    try {
                        isBot = com.tangtu.kjbb.BankBridge.isBotAccount(holder)
                                || com.tangtu.kjbb.BotConfig.bot(holder) != null;
                    } catch (Throwable t) {
                        isBot = false;
                    }
                    if (!isBot) {
                        continue;
                    }
                    String r = com.tangtu.kjbb.VoyageBill.settleHolder(holder, vb.id, "cash");
                    if (r != null) {
                        DebugLog.log("机器人份额自动结算 #" + vb.id + " " + holder + "：" + r);
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        } catch (Throwable ignored) {
            // ignore
        }

        // 国民向国库收购单供货（每分钟一轮，卖货换钱）
        try {
            traderLiquidate();
        } catch (Throwable ignored) {
            // ignore
        }

        if (++CITIZEN_SUPPLY_TICK >= 12) {
            CITIZEN_SUPPLY_TICK = 0;
            try {
                com.tangtu.kjbb.RegulatorOps.citizenSupplyToRegulator();
            } catch (Throwable ignored) {
                // ignore
            }
        }

        // 国库常平仓：低价收粮托底（每分钟一轮）
        if (overBudget()) {
            DEFERRED++;
        } else {
            long tg = com.tangtu.kjbb.Perf.begin();
            try {
                com.tangtu.kjbb.RegulatorGranary.tick();
            } catch (Throwable ignored) {
                // ignore
            } finally {
                com.tangtu.kjbb.Perf.end("market.granary", tg);
            }
        }

        // 国民内部交易：定时生成一批成交，驱动物价（取代对敲）
        if (overBudget()) {
            DEFERRED++;
        } else {
            long ti = com.tangtu.kjbb.Perf.begin();
            try {
                com.tangtu.kjbb.CitizenInternalTrade.tick();
            } catch (Throwable ignored) {
                // ignore
            } finally {
                com.tangtu.kjbb.Perf.end("market.internalTrade", ti);
            }
        }

        // 国民零售刷新：按 citizenRetailRefreshMinutes 间隔（最少 1 分钟）
        try {
            int everyMin = Math.max(1, BotConfig.get().citizenRetailRefreshMinutes);
            int needCalls = Math.max(1, everyMin * 60 / 5);
            if (++RETAIL_BUILD_TICK >= needCalls) {
                boolean deferRetail = overBudget();
                if (deferRetail) {
                    DEFERRED++;
                }
                RETAIL_BUILD_TICK = deferRetail ? needCalls : 0;
                long tr = com.tangtu.kjbb.Perf.begin();
                int n2 = 0;
                if (!deferRetail) {
                    n2 = com.tangtu.kjbb.CitizenRetail.buildOffers();
                }
                com.tangtu.kjbb.Perf.end("market.retailBuild", tr);
                if (n2 > 0) {
                    System.out.println("[TangtuEconomical] \u56fd\u6c11\u96f6\u552e\u4e0a\u67b6 " + n2
                            + " \u79cd\uff08\u5237\u65b0\u95f4\u9694 " + everyMin + " \u5206\u949f\uff09");
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // 服务器日期同步（每 12 次 = 60 秒推一次，换日时立即推）
        if (++CLOCK_TICK >= 12) {
            CLOCK_TICK = 0;
            try {
                com.tangtu.kjbb.net.MarketNetwork.broadcastClock();
            } catch (Throwable ignored) {
                // ignore
            }
        }

        // 经济部：国民危机时收购其甩卖单（内部 5 秒一次，成本很低）
        try {
            com.tangtu.kjbb.RegulatorOps.crisisBuyout();
        } catch (Throwable ignored) {
            // ignore
        }

        // 大A膏手：投机交易（内部 2 分钟节流）
        try {
            com.tangtu.kjbb.HotShotTrader.tick();
        } catch (Throwable ignored) {
            // ignore
        }

        // 破产危机：每轮都检查（内部限流）
        try {
            citizenCrisisCheck();
        } catch (Throwable ignored) {
            // ignore
        }

        // 票据回收：每 6 次调用 = 30 秒扫一次在售票据
        if (++RESALE_TICK >= 6) {
            RESALE_TICK = 0;
            try {
                citizenResaleBuy();
            } catch (Throwable ignored) {
                // ignore
            }
        }
        try {
            // 本方法每 100 tick（5 秒）调用一次 → 12 次 ≈ 1 分钟
            if (++RETAIL_TICK >= 12) {   // 每 60 秒（12 次 × 5 秒）
                RETAIL_TICK = 0;
                CitizenRetail.tick();
                priceRecoveryTick();
                com.tangtu.kjbb.BasePriceDrift.tick(ServerClock.now());
                // K 线：把正在形成的蜡烛每分钟落一次盘（不改变收线周期）
                try {
                    for (String it : KlineAggregator.trackedItems()) {
                        KlineAggregator.pushLive(it);
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            citizenDayCheck(server);
        } catch (Throwable ignored) {
            // ignore
        }
        // 经营周期（默认 5 分钟一次）
        if (++OPERATE_TICK >= Math.max(1, BotConfig.get().botCycleTicks / 100)) {
            OPERATE_TICK = 0;
            try {
                operateCycle();
                try {
                    regulatorSupport();
                } catch (Throwable ignored) {
                    // ignore
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        if (++tickCounter % every != 0) {
            return;
        }
        tickCounter = 0;
        try {
            // 玩家越活跃，机器人越收敛（1.0 = 全力做市，0.15 = 几乎退场）
            double scale = com.tangtu.kjbb.TradeActivity.botScale();
            cleanupExpired();
            // 国民滞销单：超过玩家优先期仍无人买 → 经济部接管
            try {
                com.tangtu.kjbb.RegulatorOps.takeoverCitizenOffers();
            } catch (Throwable ignored) {
                // ignore
            }

            // 机器人对敲开关（默认关闭：只与玩家交易）
            if (BotConfig.get().botCrossTradeEnabled) {
                crossTrade();
                sillyCross();
            }
            buyPlayerOffers(scale);
            List<String> items = ShopPrices.items();
            java.util.List<String> only = BotConfig.onlyItems();
            if (!only.isEmpty()) {
                items = new ArrayList<>();
                for (String id : only) {
                    if (ShopPrices.price(id) > 0) {
                        items.add(id);
                    }
                }
            }
            if (items.isEmpty()) {
                return;
            }
            int botOffers = countOffers();
            int botOrders = countOrders();
            BotLedger.load();
            EFF_OFFERS = Math.max(1, (int) Math.round(BotConfig.get().maxOffers * scale));
            try {
                maintainDepth(scale);
            } catch (Throwable ignored) {
                // ignore
            }
            EFF_ORDERS = Math.max(1, (int) Math.round(BotConfig.get().maxOrders * scale * popMul()));
            for (String bot : NAMES) {
                // 航运公司这类「非交易角色」不参与日常做市（留给远洋航次逻辑）
                if (!isMarketRole(BotConfig.role(bot))) {
                    continue;
                }
                // 活跃度越低，摸鱼概率越高
                if (RND.nextDouble() < 1.0 - (1.0 - BotConfig.idleChance(bot)) * scale) {
                    continue;
                }
                // 先看这个机器人有没有「长期收购」任务
                BotConfig.Bot self = BotConfig.bot(bot);
                if (self != null && !self.longBuy.isEmpty()
                && !("regulator".equals(self.role)
                     && !BotConfig.get().regulatorPriceControlEnabled)
                        && RND.nextDouble() < self.longBuyChance * scale
                        && botOrders < EFF_ORDERS) {
                    String li = self.longBuy.get(RND.nextInt(self.longBuy.size()));
                    if (!tradable(li)) {
                        continue;
                    }
                    if (longBuy(bot, self, li, botOrders)) {
                        botOrders++;
                    }
                    continue;
                }
                String itemId = items.get(RND.nextInt(items.size()));
                int guard = 0;
                while (!tradable(itemId) && guard++ < 12) {
                    itemId = items.get(RND.nextInt(items.size()));
                }
                if (!tradable(itemId)) {
                    continue;
                }
                long base = ShopPrices.price(itemId);
                if (base <= 0) {
                    continue;
                }
                String role = BotConfig.role(bot);
                if (BotConfig.ROLE_REGULATOR.equals(role)) {
                    if (regulate(bot, itemId, botOffers, botOrders)) {
                        botOffers++;
                    }
                } else if (BotConfig.ROLE_STABILIZER.equals(role)) {
                    if (stabilize(bot, itemId, base, botOffers, botOrders)) {
                        botOffers++;
                    }
                } else if (BotConfig.ROLE_NOISE.equals(role)) {
                    noise(bot, itemId, base, botOffers, botOrders);
                    botOffers++;
                } else {
                    boolean sell = RND.nextDouble() < 0.55;
                    BotConfig cfg = BotConfig.get();
                    if (sell && botOffers < EFF_OFFERS) {
                        long price = clampPrice(base, cfg.sellMin, cfg.sellMax);
                        int count = rand(cfg.sellCountMin, cfg.sellCountMax);
                        if (BotLedger.stock(bot, itemId) < count
                                || BotLedger.stock(bot, itemId) <= cfg.minStock) {
                            continue;
                        }
                        BotLedger.addStock(bot, itemId, -count);
                        OfferBook.add(bot, itemId, count, price);
                        botOffers++;
                    } else if (!sell && botOrders < EFF_ORDERS) {
                        long price = clampPrice(base, cfg.buyMin, cfg.buyMax);
                        int count = rand(cfg.buyCountMin, cfg.buyCountMax);
                        long total = price * count;
                        if (BotLedger.money(bot) - total < cfg.minMoney) {
                            continue;
                        }
                        BotLedger.addMoney(bot, -total);
                        OrderBook.add(bot, itemId, count, price, bot);
                        botOrders++;
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 机器人异常: " + t);
        }
    }

    private static int countOffers() {
        int n = 0;
        for (OfferBook.Row r : OfferBook.open()) {
            if (isBot(r.seller)) {
                n++;
            }
        }
        return n;
    }

    private static int countOrders() {
        int n = 0;
        for (OrderBook.Row r : OrderBook.byStatus(OrderBook.PENDING)) {
            if (isBot(r.buyer)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 维持市场深度：
     *   每个机器人都保证挂到目标数量的卖单和买单，
     *   避免「市场里只有两三条挂单」的体验。
     */
    private static void maintainDepth(double scale) {
        var cfg = BotConfig.get();
        int targetOffers = Math.max(2, (int) Math.round(cfg.maxOffers * scale));
        int targetOrders = Math.max(2, (int) Math.round(cfg.maxOrders * scale * popMul()));
        var items = ShopPrices.items();
        if (items.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String bot : NAMES) {
            if (!cfg.botOperateBots.contains(bot)) {
                continue;
            }
            // 统计该机器人现有挂单
            int myOffers = 0;
            int myOrders = 0;
            for (OfferBook.Row r : OfferBook.open()) {
                if (bot.equals(r.seller)) {
                    myOffers++;
                }
            }
            for (OrderBook.Row r : OrderBook.byStatus(OrderBook.PENDING)) {
                if (bot.equals(r.buyer)) {
                    myOrders++;
                }
            }
            // 补卖单
            int guard = 0;
            while (myOffers < targetOffers && guard++ < 16) {
                String item = items.get(RND.nextInt(items.size()));
                if (!tradable(item)) {
                    continue;
                }
                if (isTicketItem(item)) {
                    continue;      // 票据不挂卖单
                }
                long base = ShopPrices.price(item);
                if (base <= 0L) {
                    continue;
                }
                int have = BotLedger.stock(bot, item);
                int count = Math.min(have, 8 + RND.nextInt(25));
                if (count < 8) {
                    continue;
                }
                long price = Math.max(1L, Math.round(base * (1.02 + RND.nextDouble() * 0.18)));
                BotLedger.addStock(bot, item, -count);
                OfferBook.add(bot, item, count, price);
                myOffers++;
                changed = true;
            }
            // 补买单
            boolean isRegulator = "regulator".equalsIgnoreCase(BotConfig.role(bot));
            guard = 0;
            while (myOrders < targetOrders && guard++ < 16) {
                String item = items.get(RND.nextInt(items.size()));
                if (!tradable(item)) {
                    continue;
                }
                // 经济部只收民生与基础原料，不囤工业制成品
                if (isRegulator && !BotConfig.get().regulatorAllows(item)) {
                    continue;
                }
                long base = ShopPrices.price(item);
                if (base <= 0L) {
                    continue;
                }
                int count = Math.min(8 + RND.nextInt(25), orderCapFor(item, 9999));
                long price = Math.max(1L, Math.round(base * (0.82 + RND.nextDouble() * 0.14)));
                long total = price * count;
                if (BotLedger.money(bot) - total < cfg.minMoney) {
                    continue;
                }
                BotLedger.addMoney(bot, -total);
                OrderBook.add(bot, item, count, price, bot);
                myOrders++;
                changed = true;
            }
        }
        if (changed) {
            BotLedger.save();
            System.out.println("[TangtuEconomical] \u5e02\u573a\u6df1\u5ea6\u8865\u9f50\uff1a\u5356\u5355 "
                    + countOffers() + " / \u4e70\u5355 " + countOrders());
        }
    }

    /**
     * 机器人之间互相吃单：
     *   ① 谁挂得便宜，另一个机器人就买走（钱货在两家账本内转移）
     *   ② 谁出价高，另一个机器人就把库存卖给它
     * 成交价写入 K 线（MarketPulse + ShopKline），所以图上有量。
     */
    private static void crossTrade() {
        try {
            int done = 0;
            List<OfferBook.Row> offers = new ArrayList<>(OfferBook.open());
            for (OfferBook.Row r : offers) {
                if (done >= 8) {
                    break;
                }
                if (!isBot(r.seller) || r.count <= 0) {
                    continue;
                }
                if (!tradable(r.itemId)) {
                    continue;
                }
                long base = refPrice(r.itemId);
                if (base <= 0) {
                    continue;
                }
                for (String buyer : BotConfig.names()) {
                    if (buyer.equals(r.seller)) {
                        continue;
                    }
                    // 投机客不赚国民的钱：既不吃国民的货，也不卖给国民
                    try {
                        String tn0 = traderName();
                        String cn0 = citizenName();
                        if (tn0 != null && cn0 != null) {
                            if (tn0.equals(buyer) && cn0.equals(r.seller)) {
                                continue;
                            }
                            if (tn0.equals(r.seller) && cn0.equals(buyer)) {
                                continue;
                            }
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    if (!isBot(r.seller)) {
                        continue;
                    }
                    if (!cooledDown(buyer, r.itemId)) {
                        continue;
                    }
                    // 市价 103% 以内都吃（做市商互相提供流动性）
                    double stkF2 = 1.0;
                    try {
                        stkF2 = com.tangtu.kjbb.ShopPricing.factor(r.itemId,
                                BotLedger.stock(buyer, r.itemId));
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    if (r.price > base * 1.03 * stkF2) {
                        continue;
                    }
                    // 投机客「看盘」：只买低于当前市价的货（避免接盘高价库存）
                    if ("noise".equalsIgnoreCase(BotConfig.role(buyer))) {
                        long market = MarketPrice.price(r.itemId);
                        long ceiling = market > 0L
                                ? Math.max(1L, Math.round(market * 0.95))
                                : Math.max(1L, Math.round(base * 0.85));
                        if (r.price > ceiling) {
                            continue;
                        }
                    }
                    int take = r.count;
                    long total = r.price * take;
                    if (BotLedger.money(buyer) - total < BotConfig.get().minMoney) {
                        continue;
                    }
                    // 方案 B：货还在卖家手上，机器人买之前必须先取货
                    if (!com.tangtu.kjbb.Ledger.isBot(r.seller)) {
                        boolean got = false;
                        try {
                            var srvS = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                            var spS = srvS == null ? null
                                    : srvS.getPlayerList().getPlayerByName(r.seller);
                            var itemS = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .get(net.minecraft.resources.ResourceLocation.tryParse(r.itemId));
                            if (spS != null && itemS != null) {
                                int needS = take;
                                var invS = spS.getInventory();
                                for (int k = 0; k < invS.getContainerSize() && needS > 0; k++) {
                                    var stS = invS.getItem(k);
                                    if (stS == null || stS.isEmpty() || stS.getItem() != itemS) {
                                        continue;
                                    }
                                    int nS = Math.min(needS, stS.getCount());
                                    stS.shrink(nS);
                                    needS -= nS;
                                }
                                got = needS <= 0;
                            }
                        } catch (Throwable ignored) {
                            // 取货失败就不成交
                        }
                        if (!got) {
                            continue;   // 卖家不在线或货不够 → 这单跳过
                        }
                    }
                    BotLedger.addMoney(buyer, -total);
                    Ledger.credit(r.seller, total);
                    // 抽税（破产甩卖/拍卖除外）
                    try {
                        if (!com.tangtu.kjbb.RegulatorOps.isBankruptcyDeal(r.itemId, r.price)) {
                            com.tangtu.kjbb.RegulatorOps.taxBot(r.seller, total);
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    BotLedger.addStock(buyer, r.itemId, take);
                    // 投机客盈亏记账
                    try {
                        String tn = traderName();
                        if (tn != null && tn.equals(buyer)) {
                            com.tangtu.kjbb.TraderPnl.buy(r.itemId, take, r.price);
                        } else if (tn != null && tn.equals(r.seller)) {
                            com.tangtu.kjbb.TraderPnl.sell(r.itemId, take, r.price);
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    r.count = 0;
                    OfferBook.setStatus(r.id, OfferBook.FILLED);
                    MarketPulse.record(r.itemId, r.price);
                    MarketPrice.onTrade(r.itemId, r.price);
                    KlineAggregator.onTrade(r.itemId, (int) Math.max(1L, r.price), take);
                    BotTrades.log(buyer, r.seller, r.itemId, take, r.price, "对敲");
                    com.tangtu.kjbb.TradeActivity.recordBotTrade();
                    System.out.println("[TangtuEconomical] 机器人对敲 " + buyer + " ← " + r.seller
                            + " " + r.itemId + " ×" + take + " @" + r.price);
                    done++;
                    break;
                }
            }
            // ② 反向：把库存卖给出价高的机器人
            List<OrderBook.Row> orders = new ArrayList<>(OrderBook.byStatus(OrderBook.PENDING));
            for (OrderBook.Row o : orders) {
                if (done >= 16) {
                    break;
                }
                if (!isBot(o.buyer)) {
                    continue;
                }
                if (!tradable(o.itemId)) {
                    continue;
                }
                long base = refPrice(o.itemId);
                if (base <= 0 || o.unit < base * 0.97) {
                    continue;   // 市价 97% 以上就卖给对方
                }
                for (String seller : BotConfig.names()) {
                    if (seller.equals(o.buyer) || !isBot(seller)) {
                        continue;
                    }
                    if (!cooledDown(seller, o.itemId)) {
                        continue;
                    }
                    int have = BotLedger.stock(seller, o.itemId);
                    if (have < o.count) {
                        continue;
                    }
                    BotLedger.addStock(seller, o.itemId, -o.count);
                    Ledger.addStock(o.buyer, o.itemId, o.count);
                    Ledger.credit(seller, o.unit * o.count);   // 买家货款挂单时已预扣
                // 抽税（破产甩卖/拍卖除外）
                try {
                    if (!com.tangtu.kjbb.RegulatorOps.isBankruptcyDeal(o.itemId, o.unit)) {
                        com.tangtu.kjbb.RegulatorOps.taxBot(seller, o.unit * o.count);
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
                    OrderBook.setStatus(o.id, OrderBook.DONE, seller);
                    MarketPulse.record(o.itemId, o.unit);
                    MarketPrice.onTrade(o.itemId, o.unit);
                    KlineAggregator.onTrade(o.itemId, (int) Math.max(1L, o.unit), o.count);
                    BotTrades.log(o.buyer, seller, o.itemId, o.count, o.unit, "吃买单");
                    com.tangtu.kjbb.TradeActivity.recordBotTrade();
                    System.out.println("[TangtuEconomical] 机器人对敲(买单) " + seller + " → "
                            + o.buyer + " " + o.itemId + " ×" + o.count + " @" + o.unit);
                    done++;
                    break;
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 机器人对敲异常: " + t);
        }
    }

    /**
     * 机器人接玩家的买单：玩家下单后，机器人有货就直接卖给玩家。
     * 这样玩家在「市场」页签点买入，不用干等别的玩家。
     */
    private static void fillPlayerOrders(net.minecraft.server.MinecraftServer server) {
        try {
            int done = 0;
            for (OrderBook.Row o : new ArrayList<>(OrderBook.byStatus(OrderBook.PENDING))) {
                if (done >= 2) {
                    break;
                }
                if (isBot(o.buyer)) {
                    continue;                      // 机器人之间的单走 crossTrade
                }
                if (!tradable(o.itemId)) {
                    continue;
                }
                long marketPrice = MarketPrice.price(o.itemId);
                if (marketPrice <= 0) {
                    marketPrice = ShopPrices.price(o.itemId);
                }
                if (marketPrice <= 0) {
                    continue;
                }
                // 玩家出价不低于市场价 95% 才接
                if (o.unit < marketPrice * 0.95) {
                    continue;
                }
                for (String bot : BotConfig.names()) {
                    if (BotLedger.stock(bot, o.itemId) < o.count) {
                        continue;
                    }
                    if (!cooledDown(bot, o.itemId)) {
                        continue;
                    }
                    if (!deliverToPlayer(server, o)) {
                        continue;
                    }
                    BotLedger.addStock(bot, o.itemId, -o.count);
                    OrderBook.setStatus(o.id, OrderBook.DONE, bot);
                    MarketPulse.record(o.itemId, o.unit);
                    MarketPrice.onTrade(o.itemId, o.unit);
                    KlineAggregator.onTrade(o.itemId, (int) Math.max(1L, o.unit), o.count);
                    BotTrades.log(o.buyer, bot, o.itemId, o.count, o.unit, "接玩家单");
                    done++;
                    break;
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 机器人接单异常: " + t);
        }
    }

    /** 把货交给玩家：优先寄到提货点，没有就直接塞背包 */
    private static boolean deliverToPlayer(net.minecraft.server.MinecraftServer server,
                                           OrderBook.Row o) {
        net.minecraft.server.level.ServerPlayer target = null;
        for (var sp : server.getPlayerList().getPlayers()) {
            if (sp.getScoreboardName().equalsIgnoreCase(o.buyer)) {
                target = sp;
                break;
            }
        }
        if (target == null) {
            return false;                          // 玩家不在线 → 不接
        }
        net.minecraft.world.item.Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.tryParse(o.itemId));
        if (it == null) {
            return false;
        }
        int left = o.count;
        while (left > 0) {
            int n = Math.min(left, Math.max(1, it.getDefaultMaxStackSize()));
            var st = new net.minecraft.world.item.ItemStack(it, n);
            if (!target.getInventory().add(st)) {
                target.drop(st, false);
            }
            left -= n;
        }
        target.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§a[成交] 你的买单 #" + o.id + " 已被市场商家接单，货物已放入背包"), false);
        return true;
    }

    /**
     * 收购玩家挂单：挂单价 ≤ 市场价 ×(1 - playerOfferMargin) 才收。
     * 玩家挂得比市场价贵 → 机器人不收，只能等别的玩家买。
     */
    private static void buyPlayerOffers(double scale) {
        try {
            BotConfig cfg = BotConfig.get();
            if (RND.nextDouble() > cfg.playerOfferChance * scale) {
                return;
            }
            int done = 0;
            for (OfferBook.Row r : new ArrayList<>(OfferBook.open())) {
                if (done >= 2) {
                    break;
                }
                if (isBot(r.seller) || r.count <= 0 || !tradable(r.itemId)) {
                    continue;
                }
                long ref = refPrice(r.itemId);
                if (ref <= 0) {
                    continue;
                }
                long maxAccept = Math.round(ref * (1.0 - cfg.playerOfferMargin));
                if (r.price > maxAccept) {
                    continue;
                }
                // 投机客优先收玩家的货
                var order = new ArrayList<String>();
                String tn = traderName();
                if (tn != null) {
                    order.add(tn);
                }
                for (String b2 : BotConfig.names()) {
                    if (!b2.equals(tn)) {
                        order.add(b2);
                    }
                }
                for (String bot : order) {
                    if (!cooledDown(bot, r.itemId)) {
                        continue;
                    }
                    int take = r.count;
                    if (tn != null && tn.equals(bot)) {
                        take = Math.min(take, Math.max(1, cfg.playerOfferMaxCount));
                    }
                    if (cfg.playerOfferMaxCount > 0) {
                        take = Math.min(take, cfg.playerOfferMaxCount);
                    }
                    long total = r.price * take;
                    if (BotLedger.money(bot) - total < cfg.minMoney) {
                        continue;
                    }
                    // 总库存上限
                    try {
                        int totalCapB = Math.max(1, totalStockCap());
                        if (BotLedger.totalItems(bot) + take > totalCapB) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    // 库存上限：该物品已达上限就不再收
                    int capB = Math.max(1, itemStockCap());
                    if (BotLedger.stock(bot, r.itemId) + take > capB) {
                        continue;
                    }
                    // 库存越多，出价越低（factor 0.5~2.0）
                    double stkF = 1.0;
                    try {
                        stkF = com.tangtu.kjbb.ShopPricing.factor(r.itemId,
                                BotLedger.stock(bot, r.itemId));
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    long ceilingB = Math.round(ref * stkF * (1.0 - cfg.playerOfferMargin));
                    if (r.price > ceilingB) {
                        continue;
                    }
                    BotLedger.addMoney(bot, -total);
                    BotLedger.addStock(bot, r.itemId, take);
                    com.tangtu.kjbb.Pay.credit(r.seller, total);
                    r.count -= take;
                    if (r.count <= 0) {
                        OfferBook.setStatus(r.id, OfferBook.FILLED);
                    }
                    MarketPulse.record(r.itemId, r.price);
                    MarketPrice.onTrade(r.itemId, r.price);
                    KlineAggregator.onTrade(r.itemId, (int) Math.max(1L, r.price), take);
                    com.tangtu.kjbb.TradeActivity.recordPlayerTrade();
                    BotTrades.log(bot, r.seller, r.itemId, take, r.price, "收玩家挂单");
                    done++;
                    break;
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 收玩家挂单异常: " + t);
        }
    }

    /** 偶尔不讲价，谁便宜买谁的（纯粹为了市场有成交） */
    private static void sillyCross() {
        try {
            if (RND.nextDouble() > 0.15) {
                return;
            }
            List<OfferBook.Row> offers = new ArrayList<>(OfferBook.open());
            if (offers.isEmpty()) {
                return;
            }
            OfferBook.Row best = null;
            for (OfferBook.Row r : offers) {
                if (!isBot(r.seller) || r.count <= 0) {
                    continue;
                }
                if (!tradable(r.itemId)) {
                    continue;
                }
                long b0 = refPrice(r.itemId);
                if (b0 > 0 && (r.price < b0 * 0.70 || r.price > b0 * 1.30)) {
                    continue;   // 只在 ±30% 内随意撮合
                }
                if (best == null || r.price < best.price) {
                    best = r;
                }
            }
            if (best == null) {
                return;
            }
            for (String buyer : BotConfig.names()) {
                if (buyer.equals(best.seller)) {
                    continue;
                }
                if (!cooledDown(buyer, best.itemId)) {
                    continue;
                }
                long total = best.price * best.count;
                if (BotLedger.money(buyer) - total < BotConfig.get().minMoney) {
                    continue;
                }
                BotLedger.addMoney(buyer, -total);
                Ledger.credit(best.seller, total);
                BotLedger.addStock(buyer, best.itemId, best.count);
                OfferBook.setStatus(best.id, OfferBook.FILLED);
                MarketPulse.record(best.itemId, best.price);
                MarketPrice.onTrade(best.itemId, best.price);
                KlineAggregator.onTrade(best.itemId, (int) Math.max(1L, best.price), best.count);
                BotTrades.log(buyer, best.seller, best.itemId, best.count, best.price, "随意");
                com.tangtu.kjbb.TradeActivity.recordBotTrade();
                System.out.println("[TangtuEconomical] 机器人对敲(随意) " + buyer + " ← "
                        + best.seller + " " + best.itemId + " ×" + best.count + " @" + best.price);
                return;
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 随意撮合异常: " + t);
        }
    }

    /** 机器人挂单 10 分钟没卖掉 → 撤掉并把货/钱退回去 */
    private static void cleanupExpired() {
        long cutoff = ServerClock.now() - BotConfig.get().offerTtlMs;
        BotLedger.load();
        for (OfferBook.Row r : OfferBook.open()) {
            if (isBot(r.seller) && r.created < cutoff && !r.seller.equals(citizenName())) {
                OfferBook.setStatus(r.id, OfferBook.CANCELLED);
                if (r.count > 0) {
                    BotLedger.addStock(r.seller, r.itemId, r.count);
                }
            }
        }
        for (OrderBook.Row r : OrderBook.byStatus(OrderBook.PENDING)) {
            // 国民的买单给 24 小时（由 OrderBook.cancelExpired 统一处理退款），
            // 不能被 10 分钟的通用 TTL 撤掉
            if (isBot(r.buyer) && r.created < cutoff && !r.buyer.equals(citizenName())) {
                OrderBook.setStatus(r.id, OrderBook.CANCELLED, null);
                BotLedger.addMoney(r.buyer, r.unit * r.count);
            }
        }
    }

    /**
     * 长期收购：货越多，出价越低（最低到 floor）。
     * 这样玩家可以一直卖，但卖得越多单价越差。
     */
    private static boolean longBuy(String bot, BotConfig.Bot self, String itemId, int botOrders) {
        BotConfig cfg = BotConfig.get();
        if (botOrders >= Math.round(cfg.maxOrders * popMul())) {
            return false;
        }
        long base = refPrice(itemId);
        if (base <= 0) {
            return false;
        }
        int stock = BotLedger.stock(bot, itemId);
        double f = self.longBuyFactor(stock);
        long price = Math.max(1L, Math.round(base * f));
        int count = Math.max(1, self.longBuyCount);
        long total = price * count;
        if (BotLedger.money(bot) - total < cfg.minMoney) {
            return false;
        }
        BotLedger.addMoney(bot, -total);
        OrderBook.add(bot, itemId, count, price, bot);
        return true;
    }

    /** 长期收购的当前出价（给命令展示用） */
    public static String longBuyStatus(String bot) {
        BotConfig.Bot self = BotConfig.bot(bot);
        if (self == null || self.longBuy.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : self.longBuy) {
            long base = ShopPrices.price(id);
            int stock = BotLedger.stock(bot, id);
            double f = self.longBuyFactor(stock);
            long price = Math.max(1L, Math.round(base * f));
            sb.append("§7  ").append(com.tangtu.kjbb.item.StoreReceiptItem.niceName(id))
                    .append(" §f").append(price).append(" §8(库存 ").append(stock)
                    .append("，系数 ").append(String.format("%.2f", f)).append(")§r\n");
        }
        return sb.toString();
    }

    /**
     * 调节型：逆势操作，把价格拉回基准附近。
     * 返回 true 表示挂了卖单（调用方据此计数）。
     */
    private static boolean stabilize(String bot, String itemId, long base,
                                     int botOffers, int botOrders) {
        BotConfig cfg = BotConfig.get();
        if (botOffers >= cfg.maxOffers && botOrders >= cfg.maxOrders) {
            return false;
        }
        long ref = MarketPulse.reference(itemId, base);
        double ratio = base <= 0 ? 1.0 : (double) ref / base;
        // 偏贵 → 出货压价；偏便宜 → 收货抬价
        if (ratio > 1.02 && botOffers < EFF_OFFERS) {
            long price = Math.max(1L, Math.round(base * 1.00));       // 贴基准卖
            int count = rand(cfg.sellCountMin, cfg.sellCountMax) * 2;
            if (BotLedger.stock(bot, itemId) >= count
                    && BotLedger.stock(bot, itemId) > cfg.minStock) {
                BotLedger.addStock(bot, itemId, -count);
                OfferBook.add(bot, itemId, count, price);
                return true;
            }
            return false;
        }
        if (ratio < 0.98 && botOrders < EFF_ORDERS) {
            long price = Math.max(1L, Math.round(base * 1.00));       // 贴基准收
            int count = rand(cfg.buyCountMin, cfg.buyCountMax) * 2;
            long total = price * count;
            if (BotLedger.money(bot) - total >= cfg.minMoney) {
                BotLedger.addMoney(bot, -total);
                OrderBook.add(bot, itemId, count, price, bot);
            }
            return false;
        }
        // 正常区间 → 双边小额报价，缩小价差
        if (botOrders < EFF_ORDERS) {
            long bp = Math.max(1L, Math.round(base * 0.995));
            int bc = rand(cfg.buyCountMin, cfg.buyCountMax);
            if (BotLedger.money(bot) - bp * bc >= cfg.minMoney) {
                BotLedger.addMoney(bot, -bp * bc);
                OrderBook.add(bot, itemId, bc, bp, bot);
            }
        }
        if (botOffers < EFF_OFFERS) {
            long sp = Math.max(1L, Math.round(base * 1.005));
            int sc = rand(cfg.sellCountMin, cfg.sellCountMax);
            if (BotLedger.stock(bot, itemId) > cfg.minStock
                    && BotLedger.stock(bot, itemId) >= sc) {
                BotLedger.addStock(bot, itemId, -sc);
                OfferBook.add(bot, itemId, sc, sp);
                return true;
            }
        }
        return false;
    }

    /** 搅局型：报价离谱、忽大忽小，偶尔大额砸盘/拉盘 */
    private static void noise(String bot, String itemId, long base,
                              int botOffers, int botOrders) {
        BotConfig cfg = BotConfig.get();
        boolean sell = RND.nextDouble() < 0.5;
        boolean big = RND.nextDouble() < 0.12;
        if (sell && botOffers < EFF_OFFERS) {
            double f = 0.60 + RND.nextDouble() * 1.20;          // 0.60 ~ 1.80 倍
            long price = Math.max(1L, Math.round(base * f));
            int count = big ? rand(64, 160) : rand(4, 48);
            if (BotLedger.stock(bot, itemId) >= count) {
                BotLedger.addStock(bot, itemId, -count);
                OfferBook.add(bot, itemId, count, price);
            }
        } else if (!sell && botOrders < EFF_ORDERS) {
            double f = 0.45 + RND.nextDouble() * 1.15;          // 0.45 ~ 1.60 倍
            long price = Math.max(1L, Math.round(base * f));
            int count = big ? rand(64, 160) : rand(4, 48);
            long total = price * count;
            if (BotLedger.money(bot) - total >= cfg.minMoney) {
                BotLedger.addMoney(bot, -total);
                OrderBook.add(bot, itemId, count, price, bot);
            }
        }
    }

    /** 各机器人的干预次数（给 /billbot 显示） */
    private static final java.util.Map<String, Integer> INTERVENTIONS = new java.util.HashMap<>();

    public static int interventions(String bot) {
        return INTERVENTIONS.getOrDefault(bot, 0);
    }

    /**
     * 调节者：把市价摁在「基础价 × (1 ± targetBand)」区间内。
     *
     *   市价高于上轨 → 挂大额卖单（压低）
     *   市价低于下轨 → 挂大额买单（托起）
     *   区间内       → 不出手，把生意让给玩家
     */
    private static boolean regulate(String bot, String itemId, int botOffers, int botOrders) {
        BotConfig cfg = BotConfig.get();
        BotConfig.Bot self = BotConfig.bot(bot);
        if (self == null) {
            return false;
        }
        if (RND.nextDouble() > self.interveneChance) {
            return false;
        }
        long base = ShopPrices.price(itemId);          // 调节目标是「基础价」
        if (base <= 0) {
            return false;
        }
        long market = MarketPrice.price(itemId);
        if (market <= 0) {
            market = base;
        }
        double ratio = (double) market / base;
        double band = Math.max(0.01, self.targetBand);
        int size = Math.max(1, self.interveneCount);

        if (ratio > 1.0 + band && botOffers < EFF_OFFERS) {
            // 太贵 → 压低：略低于市价挂大额卖单
            long price = Math.max(1L, Math.round(market * 0.97));
            int have = BotLedger.stock(bot, itemId);
            if (have < size) {
                size = have;
            }
            if (size <= 0) {
                return false;
            }
            BotLedger.addStock(bot, itemId, -size);
            OfferBook.add(bot, itemId, size, price);
            INTERVENTIONS.merge(bot, 1, Integer::sum);
            BotTrades.log(bot, "调控", itemId, size, price, "压价");
            return true;
        }
        if (ratio < 1.0 - band && botOrders < EFF_ORDERS) {
            // 太便宜 → 托起：略高于市价挂大额买单
            long price = Math.max(1L, Math.round(market * 1.03));
            long total = price * size;
            if (BotLedger.money(bot) - total < cfg.minMoney) {
                return false;
            }
            BotLedger.addMoney(bot, -total);
            OrderBook.add(bot, itemId, size, price, bot);
            INTERVENTIONS.merge(bot, 1, Integer::sum);
            BotTrades.log("调控", bot, itemId, size, price, "托价");
            return false;
        }
        return false;
    }

    /** 机器人报价的基准价：优先用市场价（成交驱动），没有才用定价表 */
    private static long refPrice(String itemId) {
        long m = MarketPrice.priceWithStock(itemId);
        if (m > 0) {
            return m;
        }
        return ShopPrices.price(itemId);
    }

    /** [lo, hi] 区间随机整数 */
    private static int rand(int lo, int hi) {
        if (hi <= lo) {
            return Math.max(1, lo);
        }
        return lo + RND.nextInt(hi - lo + 1);
    }

    /** 把价格压在市价的 [lo, hi] 区间内，并不低于 1 */
    private static long clampPrice(long base, double lo, double hi) {
        double f = lo + RND.nextDouble() * (hi - lo);
        long p = Math.max(1L, Math.round(base * f));
        long min = Math.max(1L, Math.round(base * lo));
        long max = Math.max(min, Math.round(base * hi));
        if (p < min) {
            p = min;
        }
        if (p > max) {
            p = max;
        }
        return p;
    }

    /**
     * 唐兔国国民对某个物品的购买意愿
     *
     * @return 0 = 低，1 = 中，2 = 高
     */
    public static int willingness(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return 0;
        }
        // 视角：玩家挂买单 → 唐兔国民是【卖家】
        // 所以这里算的是「国民有多愿意把货卖给你」
        int score = 0;

        // ① 库存越多越愿意卖
        try {
            int stock = BotLedger.stock("\u5510\u5154\u56fd\u56fd\u6c11", itemId);
            int cap = stockCapOf(itemId);
            if (stock >= cap * 3 / 4) {
                score += 2;
            } else if (stock >= cap / 4) {
                score += 1;
            } else if (stock <= 0) {
                score -= 2;                 // 没货，卖不了
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // ② 自己也要用的东西，不太愿意卖
        try {
            var d = com.tangtu.kjbb.DemandReloadListener.current();
            if (d != null) {
                if (d.cityItems != null && d.cityItems.contains(itemId)) {
                    score -= 2;             // 城建要用
                }
                if (d.rareItems != null && d.rareItems.contains(itemId)) {
                    score -= 2;             // 珍宝收藏
                }
                if (d.farmSupport != null && d.farmSupport.contains(itemId)) {
                    score += 1;             // 托底收的农产，本来就打算卖
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // ③ 农产品：国民是产粮方，手上货多，愿意卖
        try {
            String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
            if (path.contains("wheat") || path.contains("carrot") || path.contains("potato")
                    || path.contains("beetroot") || path.contains("rice")
                    || path.contains("cabbage") || path.contains("tomato")
                    || path.contains("onion")) {
                score += 1;
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // ④ 含胡萝卜的料理：国民爱吃，舍不得卖
        try {
            if (com.tangtu.kjbb.FoodPreference.likesCarrot(itemId)) {
                score -= 1;
            }
        } catch (Throwable ignored) {
            // ignore
        }

        if (score >= 3) {
            return 2;
        }
        if (score >= 1) {
            return 1;
        }
        return 0;
    }

    /**
     * 玩家挂买单后，机器人（卖家）在参考价下的接单概率
     *
     * 每个机器人：按自己的库存算出愿意出售的价格（ask），
     * 用「玩家出价 / 它的 ask」做 logistic，再按库存能否满足数量打折。
     * 多个机器人取「至少一个愿意接」的并集。
     *
     * @return 0..100
     */
    /**
     * @param count 典型单量（客户端目前传 16；界面上按玩家实际数量再做修正）
     */
    public static int orderFillProbability(String itemId, int count) {
        if (itemId == null || itemId.isEmpty()) {
            return 0;
        }
        long ref = com.tangtu.kjbb.MarketPrice.uiPrice(itemId, 0);
        if (ref <= 0) {
            ref = Math.max(1L, com.tangtu.kjbb.ShopPrices.price(itemId));
        }
        if (ref <= 0) {
            return 0;
        }
        double noFill = 1.0;
        int bots = 0;
        // 国民的出售意愿（高/中/低）直接缩放国民这一家的接单概率
        int will = willingness(itemId);
        double willFactor = will == 2 ? 1.00 : (will == 1 ? 0.60 : 0.25);
        for (String name : NAMES) {
            bots++;
            int stock = 0;
            try {
                stock = BotLedger.stock(name, itemId);
            } catch (Throwable ignored) {
                // ignore
            }
            long ask = ref;
            try {
                long p2 = com.tangtu.kjbb.MarketPrice.uiPrice(itemId, stock);
                if (p2 > 0) {
                    ask = p2;
                }
            } catch (Throwable ignored) {
                // ignore
            }
            double ratio = (double) ref / (double) Math.max(1L, ask);
            double x = 3.0 * (ratio - 1.0);
            double pSell = 1.0 / (1.0 + Math.exp(-x));          // 出价=ask 时 50%
            if (stock <= 0) {
                pSell *= 0.05;                                   // 没货
            } else if (stock < count) {
                pSell *= 0.35;                                   // 货不够一整单
            }
            if (name != null && name.startsWith("\u5510\u5154\u56fd")) {
                pSell *= willFactor;      // 国民出售意愿：高 1.0 / 中 0.6 / 低 0.25
            }
            pSell = Math.max(0.0, Math.min(0.95, pSell));
            noFill *= (1.0 - pSell);
        }
        if (bots == 0) {
            return 0;
        }
        int prob = (int) Math.round((1.0 - noFill) * 100.0);
        return Math.max(0, Math.min(95, prob));
    }

    /** 城镇规模名称（按人口） */
    public static String townSize(long population) {
        if (population >= 300) {
            return "\u5927\u57ce";
        }
        if (population >= 150) {
            return "\u57ce\u5e02";
        }
        if (population >= 50) {
            return "\u5c0f\u9547";
        }
        return "\u6751\u5e84";
    }

    /** 饱食程度的程度词（避免把百分比暴露给普通玩家） */
    public static String satietyWord(double v) {
        if (v >= 0.90) {
            return "\u5403\u5f97\u5f88\u597d";
        }
        if (v >= 0.70) {
            return "\u5403\u5f97\u9971";
        }
        if (v >= 0.50) {
            return "\u4e00\u822c";
        }
        if (v >= 0.30) {
            return "\u6709\u70b9\u6371\u997f";
        }
        return "\u6371\u997f";
    }

    /** 普通玩家可见的国民概况（不含资金与流水） */
    public static java.util.List<String> publicStatus() {
        var cfg = BotConfig.get();
        String citizen = citizenName();
        long pool = 0L;
        try {
            pool = BotLedger.money(citizen);
        } catch (Throwable ignored) {
            // ignore
        }
        long population = citizenPopulation();
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("\u00a76\u3010\u5510\u5154\u56fd\u56fd\u6c11\u3011");
        out.add("\u00a77\u4eba\u53e3\uff1a\u00a7f" + population + " \u4eba");
        out.add("\u00a77\u57ce\u9547\u89c4\u6a21\uff1a\u00a7f" + townSize(population));
        double satiety = Math.min(lastSatisfaction(), smoothSatisfaction());
        out.add("\u00a77\u9971\u98df\u60c5\u51b5\uff1a\u00a7f" + satietyWord(satiety));
        // 消费最多的物品改用「图标」展示（由 GUI 的图标区渲染）
        out.add("");
        out.add("\u00a77\u6700\u8fd1\u6d88\u8d39\u6700\u591a\uff08\u56fe\u6807\uff09\uff1a");
        return out;
    }

    /**
     * 国民买票据市场里便宜的挂牌票据（提单 / 仓单）
     *
     * 判定：挂牌价 ≤ 票据内含货物估值 × 0.85 → 有概率买下
     * 买下后：钱付给卖家，票据里的货物进入国民库存
     */
    static void citizenResaleBuy() {
        var cfg = com.tangtu.kjbb.BotConfig.get();
        String citizen = citizenName();
        long pool = BotLedger.money(citizen);
        if (pool < cfg.minMoney) {
            return;
        }
        if (citizenFrozen()) {
            return;   // 破产冷冻期不收票据
        }
        var list = com.tangtu.kjbb.TicketMarket.onSale();
        System.out.println("[TangtuEconomical] \u7968\u636e\u56de\u6536\u68c0\u67e5\uff1a\u56fd\u6c11\u8d44\u91d1 "
                + pool + " / \u95e8\u69db " + cfg.minMoney + "\uff0c\u5728\u552e " + list.size() + " \u5f20");
        if (list.isEmpty()) {
            return;
        }
        System.out.println("[TangtuEconomical] \u7968\u636e\u56de\u6536\u626b\u63cf\uff1a\u5728\u552e "
                + list.size() + " \u5f20\uff0c\u56fd\u6c11\u8d44\u91d1 " + pool);
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (var row : list) {
            if (row.price <= 0L || row.price > pool / 4) {
                continue;                 // 太贵不吃
            }
            // 估算票据内容价值
            long worth = 0L;
            String itemId = null;
            int count = 0;
            try {
                // 按票据「内含货物」估值（不是票据物品本身）
                if ("RECEIPT".equals(row.kind)) {
                    String[] f = row.data.split(":", 3);
                    if (f.length >= 3) {
                        for (String part : f[2].split(";")) {
                            int eq = part.lastIndexOf('=');
                            if (eq <= 0) {
                                continue;
                            }
                            String iid = part.substring(0, eq);
                            int cnt;
                            try {
                                cnt = Integer.parseInt(part.substring(eq + 1).trim());
                            } catch (Throwable tt) {
                                continue;
                            }
                            long v = ticketUnitWorth(iid) * cnt;
                            worth += v;
                            if (itemId == null) {
                                itemId = iid;
                                count = cnt;
                            }
                        }
                    }
                } else {
                    String[] f = row.data.split(":");
                    long serial = Long.parseLong(f[0].trim());
                    var bd = com.tangtu.kjbb.item.BillRegistry.dataOf(serial);
                    if (bd != null) {
                        itemId = bd.itemId();
                        count = bd.count();
                        worth = ticketUnitWorth(itemId) * count;
                    }
                }
            } catch (Throwable ignored) {
                continue;
            }
            // 有真实容器 → 用它里面的东西估值（含潜影盒、包裹、背包等嵌套物品）
            net.minecraft.core.BlockPos goodsPos = null;
            try {
                var rawPos = com.tangtu.kjbb.TicketMarket.posOf(row.kind, row.data);
                var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (srv != null && rawPos != null) {
                    goodsPos = com.tangtu.kjbb.TicketMarket.findGoodsContainer(
                            srv.overworld(), rawPos, itemId);
                }
            } catch (Throwable ignored) {
                // ignore
            }
            if (goodsPos != null) {
                try {
                    var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                    var h = com.tangtu.kjbb.TickerStock.handlerAt(srv.overworld(), goodsPos);
                    long real = com.tangtu.kjbb.ItemValuation.handlerWorth(h);
                    if (real > 0L) {
                        worth = real;
                    }
                    // 收货目标：容器里最值钱的那种
                    String bestId = null;
                    int bestCount = 0;
                    long bestVal = -1L;
                    for (int i = 0; i < h.getSlots(); i++) {
                        var st = h.getStackInSlot(i);
                        if (st == null || st.isEmpty()) {
                            continue;
                        }
                        long v = com.tangtu.kjbb.ItemValuation.worth(st);
                        if (v > bestVal) {
                            bestVal = v;
                            bestId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(st.getItem()).toString();
                            bestCount = st.getCount();
                        }
                    }
                    if (bestId != null) {
                        itemId = bestId;
                        count = bestCount;
                    }
                } catch (Throwable ignored) {
                    // 用票据记录里的估值
                }
            }
            if (itemId == null || count <= 0 || worth <= 0) {
                continue;
            }
            // 弹性：资金越紧，收购线越往下压（85% → 最低 55%）
            double ticketEl = 0.35;
            try {
                ticketEl = com.tangtu.kjbb.FoodTable.isFood(itemId)
                        ? com.tangtu.kjbb.ElasticityReloadListener.of(itemId, 2,
                                com.tangtu.kjbb.FoodTiers.isPremium(itemId))
                        : com.tangtu.kjbb.ElasticityReloadListener.materialOf(itemId);
            } catch (Throwable ignored) {
                // 用默认
            }
            // 总库存上限：国民货太多就不再收任何票据
            try {
                int totalCap = Math.max(1, totalStockCap());
                int totalNow = BotLedger.totalItems(citizen);
                if (totalNow + count > totalCap) {
                    System.out.println("[TangtuEconomical] 国民总库存已满 "
                            + totalNow + "+" + count + " > " + totalCap + "，不收票据 #" + row.id);
                    continue;
                }
            } catch (Throwable ignored) {
                // ignore
            }
            // 库存上限：票据内含货物已经满了就不收（仓单和提单都适用）
            try {
                int capTicket = Math.max(1, itemStockCap());
                if (itemId != null && BotLedger.stock(citizen, itemId) + count > capTicket) {
                    System.out.println("[TangtuEconomical] 国民库存满，不收票据 #" + row.id
                            + " " + itemId + " " + BotLedger.stock(citizen, itemId)
                            + "+" + count + " > " + capTicket);
                    continue;
                }
            } catch (Throwable ignored) {
                // ignore
            }
            double tightNow = budgetTightness(citizen, Math.max(1L, row.price));
            double acceptLine = Math.max(0.55, 0.85 - tightNow * ticketEl * 0.30);
            if (row.price > worth * acceptLine) {
                System.out.println("[TangtuEconomical] \u56fd\u6c11\u5acc\u8d35\u6ca1\u4e70 #"
                        + row.id + " \u4ef7 " + row.price + " > \u4f30\u503c " + worth
                        + "\u00d7" + String.format(java.util.Locale.ROOT, "%.2f", acceptLine));
                continue;                 // 不便宜就不买
            }
            // 三档意愿：高(≤50%) 100% / 中(≤70%) 70% / 低(≤85%) 25%
            double ratio = (double) row.price / (double) worth;
            double chance;
            if (ratio <= 0.50) {
                chance = 100.0;
            } else if (ratio <= 0.70) {
                chance = 70.0;
            } else {
                chance = 25.0;
            }
            // 库存越满，买的意愿越低（满仓时降到 10%）
            try {
                int capP = Math.max(1, itemStockCap());
                long haveP = BotLedger.stock(citizen, itemId);
                double fill = Math.min(1.0, (double) (haveP + count) / (double) capP);
                double stockWilling = Math.max(0.10, 1.0 - fill);
                chance *= stockWilling;
            } catch (Throwable ignored) {
                // ignore
            }
            if (rnd.nextDouble() * 100.0 > chance) {
                System.out.println("[TangtuEconomical] \u56fd\u6c11\u72b9\u8c6b\u4e86\u4e00\u4e0b #"
                        + row.id + " \u6298\u4ef7 " + Math.round(ratio * 100) + "% "
                        + "\u6982\u7387 " + (int) chance + "%");
                continue;
            }
            if (BotLedger.money(citizen) < row.price) {
                continue;
            }
            BotLedger.addMoney(citizen, -row.price);
            com.tangtu.kjbb.Pay.credit(row.seller, row.price);
            com.tangtu.kjbb.TicketMarket.setStatus(row.id,
                    com.tangtu.kjbb.TicketMarket.SOLD);
            // 有对应容器 → 等玩家靠近再收货；没有容器（纯提单）→ 直接入库存
            net.minecraft.core.BlockPos container = goodsPos;
            if (container != null) {
                com.tangtu.kjbb.TicketCollector.add(container, itemId, count, row.id,
                        row.seller);
            } else {
                BotLedger.addStock(citizen, itemId, count);
            }
            // 通知卖家：国民买走了
            try {
                var srv0 = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (srv0 != null) {
                    for (var seller : srv0.getPlayerList().getPlayers()) {
                        if (row.seller != null
                                && row.seller.equalsIgnoreCase(seller.getScoreboardName())) {
                            seller.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a77[\u7968\u636e\u5e02\u573a] \u00a7a\u4f60\u7684\u7968\u636e #"
                                            + row.id + " \u5df2\u88ab\u5510\u5154\u56fd\u56fd\u6c11\u4e70\u8d70"
                                            + "\uff0c\u6210\u4ea4 " + row.price + " \u5510\u5154\u5e01"
                                            + "\uff1b\u56fd\u6c11\u4f1a\u4e0a\u95e8\u63d0\u8d27\uff0c"
                                            + "\u63d0\u8d27\u540e\u8fd9\u5f20\u7968\u4f5c\u5e9f"), false);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
            BotTrades.log(citizen, row.seller, itemId, count,
                    Math.max(1L, row.price / Math.max(1, count)),
                    "\u7968\u636e\u56de\u6536");
            System.out.println("[TangtuEconomical] \u56fd\u6c11\u4e70\u4e0b\u7968\u636e #" + row.id
                    + " " + itemId + " x" + count + " @" + row.price);
            break;                        // 每轮最多买一张
        }
    }

    /**
     * 国民轻量收购：每 30 分钟一轮
     *
     * 完整周期（citizenCycle）是 30 游戏日 ≈ 10 小时，太稀疏；
     * 这里用资金池的 10% 做小额补货，让玩家种的粮食能及时被买走。
     */
    private static void citizenLightCycle() {
        if (citizenFrozen()) {
            return;   // 破产冷冻期不下新单
        }
        var cfg = BotConfig.get();
        String citizen = citizenName();
        long pool = BotLedger.money(citizen);
        if (pool < cfg.minMoney) {
            return;
        }
        long budget = pool / 10L;                 // 只用 10% 资金
        int before = OrderBook.byStatus(OrderBook.PENDING).size();
        try {
            citizenBuyFoods(citizen, budget);
            citizenCityDemand(citizen);
            // 航运公司招标中 → 用手里的货认购份额
            int sub = citizenSupplyToVoyage();
            if (sub > 0) {
                System.out.println("[TangtuEconomical] \u56fd\u6c11\u8ba4\u8d2d\u822a\u6b21\uff1a" + sub + " \u79cd\u8d27");
            }
            // 闲置现金 → 买便宜的航次份额（股票）
            int inv = citizenInvestInStocks();
            if (inv > 0) {
                System.out.println("[TangtuEconomical] \u56fd\u6c11\u6295\u8d44\uff1a\u4e70\u5165 " + inv + " \u7b14\u822a\u6b21\u4efd\u989d");
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u56fd\u6c11\u8f7b\u91cf\u6536\u8d2d\u5f02\u5e38: " + t);
        }
        // 每 30 分钟重建一次零售（与 30 分钟期限对齐）
        try {
            int listed = com.tangtu.kjbb.CitizenRetail.buildOffers();
            if (listed > 0) {
                System.out.println("[TangtuEconomical] \u56fd\u6c11\u96f6\u552e\u5237\u65b0\uff1a"
                        + listed + " \u79cd");
            }
        } catch (Throwable ignored) {
            // ignore
        }

        int after = OrderBook.byStatus(OrderBook.PENDING).size();
        System.out.println("[TangtuEconomical] \u56fd\u6c11\u8f7b\u91cf\u6536\u8d2d\uff1a\u9884\u7b97 "
                + budget + "\uff0c\u65b0\u589e\u8ba2\u5355 " + Math.max(0, after - before)
                + " \u5f20\uff08\u5f53\u524d\u5728\u552e PENDING " + after + "\uff09");
    }

    /**
     * 破产危机：资金低于警戒线 → 发警告 + 开始低价抛售库存
     *
     * 警戒线 = minMoney × citizenCrisisWarnRatio（默认 1.6 倍）
     * 抛售折扣 = citizenCrisisDiscount（默认 30% off）
     */
    /** 破产计时起点 */
    private static long BANKRUPT_SINCE = 0L;
    /** 冷冻期截止（游戏日，-1 = 未冷冻） */
    private static long FREEZE_UNTIL_DAY = -1L;
    private static boolean FREEZE_LOADED = false;

    private static java.nio.file.Path freezeFile() {
        return java.nio.file.Path.of("config", "kjbb", "citizen_bankrupt.txt");
    }

    private static synchronized void loadFreeze() {
        if (FREEZE_LOADED) {
            return;
        }
        FREEZE_LOADED = true;
        try {
            var p = freezeFile();
            if (!java.nio.file.Files.exists(p)) {
                return;
            }
            String s = java.nio.file.Files.readString(p,
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!s.isEmpty()) {
                FREEZE_UNTIL_DAY = Long.parseLong(s);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static synchronized void saveFreeze() {
        try {
            var p = freezeFile();
            if (p.getParent() != null) {
                java.nio.file.Files.createDirectories(p.getParent());
            }
            java.nio.file.Files.writeString(p, Long.toString(FREEZE_UNTIL_DAY),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 是否处于破产冷冻期 */
    public static boolean citizenFrozen() {
        loadFreeze();
        if (FREEZE_UNTIL_DAY < 0L) {
            return false;
        }
        long day = 0L;
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv != null && srv.overworld() != null) {
                day = srv.overworld().getDayTime() / 24000L;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (day >= FREEZE_UNTIL_DAY) {
            FREEZE_UNTIL_DAY = -1L;
            saveFreeze();
            return false;
        }
        return true;
    }

    /** 破产清算：拍卖库存 → 人口下降 → 票据下架 → 冷冻期 */
    private static void citizenBankruptcy(String citizen, BotConfig cfg) {
        long pool = BotLedger.money(citizen);
        double discount = Math.max(0.1, Math.min(0.95, cfg.citizenBankruptSellDiscount));

        var stock = BotLedger.stockMap(citizen);
        int listed = 0;
        for (var e : new java.util.ArrayList<>(stock.entrySet())) {
            String item = e.getKey();
            int have = e.getValue();
            if (have <= 0) {
                continue;
            }
            long unit = ticketUnitWorth(item);
            if (unit <= 0L) {
                unit = 1L;
            }
            long price = Math.max(1L, Math.round(unit * discount));
            int count = orderCapFor(item, have);
            com.tangtu.kjbb.OfferBook.add(citizen, item, count, price);
            BotLedger.addStock(citizen, item, -count);
            BotTrades.log(citizen, "-", item, count, price, "\u7834\u4ea7\u62cd\u5356");
            listed++;
        }

        double before = CITIZEN_POP;
        CITIZEN_POP = Math.max(1.0, CITIZEN_POP
                * Math.max(0.1, Math.min(1.0, cfg.citizenBankruptPopRatio)));
        LAYER_POOR = 0.80;
        LAYER_MID = 0.15;
        savePop();
        saveLayers();

        try {
            for (var row : com.tangtu.kjbb.TicketMarket.onSale()) {
                com.tangtu.kjbb.TicketMarket.setStatus(row.id,
                        com.tangtu.kjbb.TicketMarket.CANCELLED);
            }
        } catch (Throwable ignored) {
            // ignore
        }

        long day = 0L;
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv != null && srv.overworld() != null) {
                day = srv.overworld().getDayTime() / 24000L;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        FREEZE_UNTIL_DAY = day + Math.max(1, cfg.citizenBankruptFreezeDays);
        saveFreeze();
        BotLedger.save();

        String msg = "\u00a7c[\u5510\u5154\u56fd] \u00a74\u56fd\u5e93\u8017\u5c3d\uff0c\u5ba3\u5e03\u7834\u4ea7\uff01"
                + "\u00a7e\u5e93\u5b58\u5df2 50% \u629b\u552e\u62cd\u5356\uff0c\u4eba\u53e3 "
                + String.format(java.util.Locale.ROOT, "%.1f", before) + " \u2192 "
                + String.format(java.util.Locale.ROOT, "%.1f", CITIZEN_POP)
                + "\uff0c\u51b7\u51bb " + cfg.citizenBankruptFreezeDays + " \u65e5";
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv != null) {
                srv.getPlayerList().broadcastSystemMessage(
                        net.minecraft.network.chat.Component.literal(msg), false);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        System.out.println("[TangtuEconomical] \u56fd\u6c11\u7834\u4ea7\uff1a\u8d44\u91d1 " + pool
                + "\uff0c\u62cd\u5356 " + listed + " \u79cd\uff0c\u4eba\u53e3 "
                + before + " \u2192 " + CITIZEN_POP);
    }

    private static void citizenCrisisCheck() {
        var cfg = BotConfig.get();
        String citizen = citizenName();
        if (citizen == null) {
            return;
        }
        long pool = BotLedger.money(citizen);
        long warnAt = (long) (cfg.minMoney * Math.max(1.0, cfg.citizenCrisisWarnRatio));

        // ===== 破产判定（先于抛售）=====
        try {
            long bankruptAt = (long) (cfg.minMoney
                    * Math.max(0.05, Math.min(5.0, cfg.citizenBankruptRatio)));
            long nowB = ServerClock.now();
            if (pool < bankruptAt) {
                if (BANKRUPT_SINCE <= 0L) {
                    BANKRUPT_SINCE = nowB;
                    System.out.println("[TangtuEconomical] \u56fd\u6c11\u8d44\u91d1 " + pool
                            + " \u4f4e\u4e8e\u7834\u4ea7\u7ebf " + bankruptAt
                            + "\uff0c\u5f00\u59cb\u8ba1\u65f6");
                } else {
                    long needMs = (long) (Math.max(0.001, cfg.citizenBankruptHours)
                            * 60.0 * 60_000.0);
                    if (nowB - BANKRUPT_SINCE >= needMs) {
                        try {
                            citizenBankruptcy(citizen, cfg);
                        } catch (Throwable ex) {
                            System.out.println("[TangtuEconomical] \u7834\u4ea7\u6e05\u7b97\u5f02\u5e38: " + ex);
                            ex.printStackTrace();
                        }
                        BANKRUPT_SINCE = 0L;
                        return;
                    }
                }
            } else {
                BANKRUPT_SINCE = 0L;
            }
        } catch (Throwable ignored) {
            // ignore
        }

        if (pool > warnAt) {
            return;
        }
        // 警告（每小时最多一次）
        long now = ServerClock.now();
        if (now - LAST_CRISIS_WARN > 60L * 60_000L) {
            LAST_CRISIS_WARN = now;
            String msg = "\u00a7c[\u5510\u5154\u56fd] \u00a7e\u56fd\u5e93\u544a\u6025\uff01\u56fd\u6c11"
                    + "\u6b63\u5728\u4f4e\u4ef7\u629b\u552e\u5e93\u5b58\uff08\u8d44\u91d1 "
                    + pool + " / \u8b66\u6212\u7ebf " + warnAt
                    + "\uff09\uff0c\u53bb\u5e02\u573a\u62a2\u8d2d\u5427";
            try {
                var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (srv != null) {
                    srv.getPlayerList().broadcastSystemMessage(
                            net.minecraft.network.chat.Component.literal(msg), false);
                }
            } catch (Throwable ignored) {
                // ignore
            }
            System.out.println("[TangtuEconomical] \u56fd\u6c11\u8d22\u653f\u544a\u6025\uff1a\u8d44\u91d1 "
                    + pool + " < \u8b66\u6212\u7ebf " + warnAt);
        }
        // 抛售（每 2 分钟最多一轮）
        if (now - LAST_CRISIS_SELL < 120_000L) {
            return;
        }
        LAST_CRISIS_SELL = now;
        double discount = 1.0 - Math.max(0.0, Math.min(0.7, cfg.citizenCrisisDiscount));
        var stock = BotLedger.stockMap(citizen);
        if (stock.isEmpty()) {
            return;
        }
        int soldKinds = 0;
        long gained = 0L;
        for (var e : new java.util.ArrayList<>(stock.entrySet())) {
            if (soldKinds >= 4) {
                break;
            }
            String item = e.getKey();
            int have = e.getValue();
            if (have < 16) {
                continue;
            }
            String group = com.tangtu.kjbb.ElasticityReloadListener.stockGroupOf(item);
            if ("treasure".equals(group)) {
                continue;                    // 珍宝不贱卖
            }
            long unit = ticketUnitWorth(item);
            if (unit <= 0L) {
                unit = 1L;
            }
            long price = Math.max(1L, Math.round(unit * discount));
            int count = Math.min(have / 2, 512);
            if (count < 8) {
                continue;
            }
            com.tangtu.kjbb.OfferBook.add(citizen, item, count, price);
            BotLedger.addStock(citizen, item, -count);
            BotTrades.log(citizen, "-", item, count, price, "\u7834\u4ea7\u629b\u552e");
            // 注意：挂单时不入账，等玩家买走时由 Pay.credit 打钱
            gained += price * count;
            soldKinds++;
            System.out.println("[TangtuEconomical] \u629b\u552e\uff1a" + item + " \u00d7" + count
                    + " @" + price + "\uff08" + Math.round((1 - discount) * 100) + "% off\uff09");
        }
        if (soldKinds > 0) {
            BotLedger.save();
            System.out.println("[TangtuEconomical] \u56fd\u6c11\u629b\u552e\uff1a" + soldKinds
                    + " \u79cd\uff0c\u56de\u6b3e " + gained);
        }
    }

    /** 票据内物品的单价：市场价 → 基准价 → 0 */
    private static long ticketUnitWorth(String itemId) {
        long unit = com.tangtu.kjbb.MarketPrice.price(itemId);
        if (unit <= 0L) {
            unit = com.tangtu.kjbb.ShopPrices.price(itemId);
        }
        return Math.max(0L, unit);
    }
}
