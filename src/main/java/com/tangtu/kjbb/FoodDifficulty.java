package com.tangtu.kjbb;

import java.util.Locale;

/**
 * 食物制作难度分级（0~3），用于「难做的食物出现概率低」。
 *
 * <pre>
 *   0 = 生鲜/基础   （小麦、土豆、胡萝卜、生肉、水果）
 *   1 = 简单加工     （面包、烤土豆、熟肉、米饭）
 *   2 = 复杂料理     （炒饭、派、汤、炖菜、寿司、汉堡）
 *   3 = 宴席/稀有     （大餐、蛋糕、龙肉料理、金苹果类）
 * </pre>
 *
 * 判定依据两条：
 *   ① 价格分位（相对所有食物价格中位数）
 *   ② 物品 id 里的关键词（生鲜降级、加工升级、宴席再升级）
 *
 * 概率权重：tier0 45% / tier1 30% / tier2 17% / tier3 8%
 * 排序权重：1.0 / 0.75 / 0.50 / 0.30（越难做越靠后）
 */
public final class FoodDifficulty {

    /** 各档的出现权重（千分比） */
    private static final int[] WEIGHT = {450, 300, 170, 80};
    /** 各档的排序折扣 */
    private static final double[] RANK = {1.0, 0.75, 0.50, 0.30};
    /** 各档的中文名 */
    private static final String[] LABEL = {"生鲜", "简加工", "复杂料理", "宴席/稀有"};

    private static double medianPrice = -1.0;

    private FoodDifficulty() {
    }

    /** 食物价格中位数（用于分档），懒计算并缓存 */
    private static synchronized double medianPrice() {
        if (medianPrice >= 0.0) {
            return medianPrice;
        }
        java.util.List<Long> prices = new java.util.ArrayList<>();
        try {
            for (String id : ShopPrices.items()) {
                if (!FoodTable.isFood(id)) {
                    continue;
                }
                long p = ShopPrices.price(id);
                if (p > 0) {
                    prices.add(p);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        if (prices.isEmpty()) {
            medianPrice = 12.0;
            return medianPrice;
        }
        java.util.Collections.sort(prices);
        medianPrice = prices.get(prices.size() / 2);
        return medianPrice;
    }

    /** 重新计算中位数（reload 配置/价格表后调用） */
    public static synchronized void invalidate() {
        medianPrice = -1.0;
    }

    /** 制作难度 0~3：先看关键词（最能体现制作工序），价格只作兜底 */
    public static int tier(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return 0;
        }
        String id = itemId.toLowerCase(Locale.ROOT);
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;

        // ① 宴席 / 稀有：最高档，不看价格
        if (containsAny(name, "feast", "banquet", "deluxe", "premium", "royal", "golden",
                "dragon", "ender", "nether_star", "enchanted", "crown", "buffet")) {
            return 3;
        }
        // ② 复杂料理：至少 2 档
        if (containsAny(name, "soup", "stew", "pie", "cake", "curry", "bowl", "skewer",
                "burger", "sandwich", "dumpling", "noodle", "sushi", "salad", "casserole",
                "hotpot", "fried", "roast_dinner", "platter", "stuffed", "roll")) {
            return 2;
        }
        // ③ 简单加工：至少 1 档
        if (containsAny(name, "cooked_", "_cooked", "baked", "toast", "boiled", "roasted",
                "smoked", "grilled", "rice", "bread", "cheese", "dough", "jerky", "sausage")) {
            return 1;
        }
        // ④ 生鲜 / 原料：0 档
        if (containsAny(name, "raw_", "_raw", "seeds", "seed", "crop", "berries", "berry",
                "fruit", "leaves", "grain", "sapling", "kelp", "egg", "milk", "meat",
                "fish", "wheat", "potato", "carrot", "beetroot", "cabbage", "tomato",
                "onion", "corn", "chili", "lettuce", "cucumber", "avocado")) {
            return 0;
        }
        // ⑤ 兜底：按价格分档；没收录价格的算 1 档（不奖励也不惩罚）
        long price = 0L;
        try {
            price = ShopPrices.price(itemId);
        } catch (Throwable ignored) {
            // 用 0
        }
        if (price <= 0L) {
            return 1;
        }
        double med = medianPrice();
        double ratio = med <= 0 ? 1.0 : price / med;
        if (ratio <= 0.55) {
            return 0;
        }
        if (ratio <= 1.6) {
            return 1;
        }
        return ratio <= 3.2 ? 2 : 3;
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 出现权重（千分比）：越难做越小 */
    public static int weightPermille(String itemId) {
        return WEIGHT[tier(itemId)];
    }

    /** 排序折扣：越难做越靠后 */
    public static double rankFactor(String itemId) {
        return RANK[tier(itemId)];
    }

    /** 按权重掷一次：返回 true = 这轮允许出现 */
    public static boolean roll(String itemId) {
        int w = weightPermille(itemId);
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(1000) < w;
    }

    public static String label(String itemId) {
        return LABEL[tier(itemId)];
    }
}
