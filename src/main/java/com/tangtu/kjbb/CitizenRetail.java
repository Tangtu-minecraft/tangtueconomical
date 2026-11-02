package com.tangtu.kjbb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 国民零售：把收进来的货加价挂出去卖，回收货币
 *
 * 规则：
 *   · 只卖库存里已有的东西（不凭空造货）
 *   · 上架量 = 库存 × retailRatio%（默认 30%），单笔上限 retailMax(512)
 *   · 售价 = 参考价 × retailMarkup%（默认 160%），且不超过市价 × 1.4
 *   · 每周期最多上架 retailKinds(6) 种
 *   · 24 小时未卖出 → 下架并退回库存
 */
public final class CitizenRetail {

    /** 国民零售单有效期：24 小时 */
    private static final long TTL_MS = 30L * 60_000L;   // 30 分钟

    private CitizenRetail() {
    }

    private static String citizen() {
        String n = MarketBots.citizenName();
        return n == null ? "\u5510\u5154\u56fd\u56fd\u6c11" : n;
    }

    /** 上架一批货 */
    /**
     * 零售优先级：数字越小越先上架。
     *
     * 参考共享贸易品目录（data/kjbankbridge/kjbb/roma/goods.json）的品类：
     * 粮食/油脂/酒 → 生活必需；金属/建材 → 生产必需；机械/羊毛/石料 → 一般；
     * 奢侈品最后。目录查不到（数据包没加载）就用中性值。
     */
    private static int retailRank(String itemId) {
        String c;
        try {
            c = com.tangtu.kjbb.RomaGoods.catOf(itemId);
        } catch (Throwable t) {
            return 50;
        }
        if (c == null) {
            return 50;
        }
        // 食物再按「制作难度」细排：难做的往后站（每档 +4）
        if ("FOOD".equals(c)) {
            int d;
            try {
                d = com.tangtu.kjbb.FoodDifficulty.tier(itemId);
            } catch (Throwable t) {
                d = 0;
            }
            return 10 + Math.max(0, Math.min(3, d)) * 4;
        }
        switch (c) {
            case "FOOD":
            case "OIL":
            case "WINE":
                return 10;
            case "METAL":
            case "BUILD":
                return 20;
            case "MECH":
            case "TEXTILE":
            case "STONE":
                return 30;
            case "LUXURY":
                return 60;
            default:
                return 50;
        }
    }

    /**
     * 这个物品现在能不能挂零售。
     *
     * 粮食在「储备天数低于危机线」时不卖——产多少吃多少，但绝不把保命粮卖掉。
     * 非食物永远可卖。
     */
    public static boolean maySell(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        if (!com.tangtu.kjbb.MarketBots.isFoodItem(itemId)) {
            return true;                      // 非食物永远可卖
        }
        // 保命粮：统一由 MarketBots 判定（含迟滞与落盘）
        return !com.tangtu.kjbb.MarketBots.foodSellingBlocked();
    }

    public static int buildOffers() {
        if (com.tangtu.kjbb.MarketBots.citizenFrozen()) {
            return 0;   // 破产冷冻期不零售
        }
        var cfg = BotConfig.get();
        String bot = citizen();
        var stock = BotLedger.stockMap(bot);
        if (stock.isEmpty()) {
            return 0;
        }

        // 按「生活必需品优先 + 库存量」排序：
        // 共享贸易品目录（goods.json）里分好的品类，决定国民先摆什么
        List<Map.Entry<String, Integer>> list = new ArrayList<>(stock.entrySet());
        list.sort((a, b) -> {
            int ra = retailRank(a.getKey());
            int rb = retailRank(b.getKey());
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            return Integer.compare(b.getValue(), a.getValue());
        });

        int listed = 0;
        for (var e : list) {
            if (listed >= cfg.citizenRetailKinds) {
                break;
            }
            String item = e.getKey();
            // 票据类（提单/仓单/本票/支票等）不零售：那是权利凭证，不是商品
            if (com.tangtu.kjbb.MarketBots.isTicketItem(item)) {
                continue;
            }
            // 已经在售的品类不重复挂（否则刷新一次就多一批重复单）
            if (hasOpenOffer(bot, item)) {
                continue;
            }
            // 保命粮：粮食储备低于危机线时不挂售
            if (!maySell(item)) {
                continue;
            }
            // 食物按制作难度掷一次：难做的（蛋糕、宴席菜）只有 8%~17% 的轮次会上架
            try {
                if (com.tangtu.kjbb.FoodTable.isFood(item)
                        && !com.tangtu.kjbb.FoodDifficulty.roll(item)) {
                    continue;
                }
            } catch (Throwable ignored) {
                // 判定异常不拦
            }
            int have = e.getValue();
            int count = (int) Math.min(cfg.citizenRetailMax,
                    (long) have * cfg.citizenRetailRatio / 100L);
            count = com.tangtu.kjbb.MarketBots.orderCapFor(item, count);
            if (count < 8) {
                continue;   // 太少不值得挂
            }
            long cost = MarketBots.citizenPriceOf(item);
            if (cost <= 0L) {
                cost = ShopPrices.price(item);
            }
            if (cost <= 0L) {
                cost = Math.max(2L, FoodTable.value(item) / 100L);
            }
            if (cost <= 0L) {
                continue;
            }
            long price = Math.max(2L, Math.round(cost * cfg.citizenRetailMarkup / 100.0));
            // 库存越多，零售价越低（和收购价用同一套库存因子）
            try {
                double stkF = com.tangtu.kjbb.ShopPricing.factor(item,
                        com.tangtu.kjbb.BotLedger.stock(MarketBots.citizenName(), item));
                price = Math.max(2L, Math.round(price * stkF));
            } catch (Throwable ignored) {
                // ignore
            }
            long market = MarketPrice.price(item);
            if (market > 0L) {
                price = Math.min(price, Math.max(2L, Math.round(market * 1.4)));
            }
            BotLedger.addStock(bot, item, -count);
            OfferBook.add(bot, item, count, price);
            BotTrades.log(bot, "-", item, count, price, "\u4e0a\u67b6");
            listed++;
        }
        // 特殊商品：传送石碑 / 传送石（国民手工产出，有概率上架，固定基准价）
        try {
            listed += specialOffers(cfg, bot);
        } catch (Throwable ignored) {
            // ignore
        }
        if (listed > 0) {
            BotLedger.save();
            System.out.println("[TangtuEconomical] \u56fd\u6c11\u96f6\u552e\u4e0a\u67b6 " + listed + " \u79cd");
        }
        return listed;
    }

    /** 该机器人是否已有这个物品的在售卖单 */
    private static boolean hasOpenOffer(String bot, String itemId) {
        try {
            for (OfferBook.Row r : OfferBook.open()) {
                if (r != null && bot.equals(r.seller) && itemId.equals(r.itemId)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return false;
    }

    /** 超时未卖出的零售单：下架退货 */
    public static void tick() {
        String bot = citizen();
        long now = ServerClock.now();
        int openAll = 0;
        int mine = 0;
        int expired = 0;
        for (OfferBook.Row r : OfferBook.open()) {
            if (r == null) {
                continue;
            }
            openAll++;
            if (!bot.equals(r.seller)) {
                continue;
            }
            mine++;
            if (now - r.created <= TTL_MS) {
                continue;
            }
            expired++;
            OfferBook.setStatus(r.id, OfferBook.CANCELLED);
            // 特殊商品是凭空产出的，过期不退货（否则国民库存凭空多出物品）
            boolean special = "waystones:waystone".equals(r.itemId)
                    || "waystones:warp_stone".equals(r.itemId)
                    || "minecraft:enchanted_golden_apple".equals(r.itemId);
            if (r.count > 0 && !special) {
                BotLedger.addStock(bot, r.itemId, r.count);
            }
            System.out.println("[TangtuEconomical] \u96f6\u552e\u8fc7\u671f\u4e0b\u67b6\uff1a"
                    + r.itemId + " \u00d7" + r.count + " @ " + r.price
                    + "\uff08\u8d85\u8fc7 30 \u5206\u949f\u672a\u552e\u51fa\uff09");
        }
        if (expired > 0) {
            System.out.println("[TangtuEconomical] \u96f6\u552e tick\uff1a\u8fc7\u671f\u4e0b\u67b6 "
                    + expired + " \u5355\uff08\u5728\u552e " + openAll + "\uff0c\u56fd\u6c11 "
                    + mine + "\uff0cTTL " + (TTL_MS / 60000L) + " \u5206\u949f\uff09");
        }
    }

    /** 当前在售的国民零售单（供命令/报表用） */
    public static Map<String, String> current() {
        String bot = citizen();
        Map<String, String> out = new LinkedHashMap<>();
        for (OfferBook.Row r : OfferBook.open()) {
            if (r != null && bot.equals(r.seller)) {
                out.put(r.itemId, r.count + " @ " + r.price);
            }
        }
        return out;
    }

    /** 特殊商品：传送石碑 / 传送石，有概率上架，固定价 */
    private static int specialOffers(BotConfig cfg, String bot) {
        int chance = Math.max(0, cfg.citizenSpecialChance);
        if (chance <= 0 || cfg.citizenSpecialPrice <= 0L) {
            return 0;
        }
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        int roll = rnd.nextInt(100);
        if (roll >= chance) {
            System.out.println("[TangtuEconomical] \u7279\u6b8a\u5546\u54c1\u672c\u8f6e\u672a\u4e0a\u67b6"
                    + "\uff08\u6982\u7387 " + chance + "%\uff0c\u63b7\u51fa " + roll + "\uff09");
            return 0;
        }
        // 商品池：传送石碑 / 传送石 / 附魔金苹果
        String[] pool = {"waystones:waystone", "waystones:warp_stone",
                "minecraft:enchanted_golden_apple"};
        String item = pool[rnd.nextInt(pool.length)];
        long basePrice = switch (item) {
            case "minecraft:enchanted_golden_apple" -> cfg.citizenSpecialApplePrice;
            default -> cfg.citizenSpecialPrice;
        };
        // 已经有一件在售就不重复挂
        if (OfferBook.available(item) > 0) {
            return 0;
        }
        int count = 1;
        long price = Math.max(1L, basePrice);
        OfferBook.add(bot, item, count, price);
        BotTrades.log(bot, "-", item, count, price, "\u7279\u6b8a\u5546\u54c1");
        System.out.println("[TangtuEconomical] \u56fd\u6c11\u4e0a\u67b6\u7279\u6b8a\u5546\u54c1\uff1a"
                + item + " \u00d71 @" + price);
        return 1;
    }
}
