package com.tangtu.kjbb;

/**
 * 库存驱动的动态定价。
 *
 *   售价 = 基础价 × 库存因子
 *   库存因子 = clamp((目标库存 / 当前库存)^指数, 下限, 上限)
 *
 * 直观含义：
 *   库存不足 → 因子 > 1 → 涨价（稀缺）
 *   库存充足 → 因子 < 1 → 降价（积压）
 *
 * 参数（config/kjbb/shop_prices.properties）：
 *   target_stock = 1000     目标库存（因子为 1 的库存量）
 *   stock_factor_min = 0.5  价格最多打到 5 折
 *   stock_factor_max = 2.0  价格最多翻 2 倍
 *   stock_elasticity = 0.3  指数：越大越敏感
 */
public final class ShopPricing {

    private ShopPricing() {
    }

    /** 库存因子 */
    public static double factor(long stock) {
        return factor(null, stock);
    }

    /** 金属类关键词（锭 / 粒 / 矿 / 板 / 片 / 原矿） */
    private static final String[] METAL_WORDS = {
        "ingot", "nugget", "ore", "raw_", "_sheet", "sheet_", "plate",
        "brass", "copper", "zinc", "iron", "gold", "steel", "tin", "lead",
        "silver", "nickel", "bronze", "aluminum", "titanium", "platinum",
        "netherite", "electrum", "andesite_alloy", "crushed_raw"
    };

    /** 是否金属类物品 */
    public static boolean isMetal(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String id = itemId.toLowerCase(java.util.Locale.ROOT);
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        for (String w : METAL_WORDS) {
            if (name.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 库存因子（按物品取目标库存；itemId 为 null 时用全局目标） */
    public static double factor(String itemId, long stock) {
        long target = itemId == null
                ? ShopPrices.targetStock()
                : com.tangtu.kjbb.ElasticityReloadListener.scaledTargetStockOf(itemId);
        if (target <= 0L) {
            target = 1000L;
        }
        long s = Math.max(1L, stock);
        double elasticity = BotConfig.get().stockElasticity;
        if (elasticity <= 0.0) {
            elasticity = ShopPrices.stockElasticity();
        }
        if (elasticity <= 0.0) {
            elasticity = 0.3;
        }
        // 库存敏感度全面加强：等效库存 ×3、弹性 ×1.6（所有物品）
        s = (long) Math.max(1L, Math.round(s * 3.0));
        elasticity = elasticity * 1.6;
        // 金属再强一点（×1.5、弹性 ×1.25）
        if (isMetal(itemId)) {
            s = (long) Math.max(1L, Math.round(s * 1.5));
            elasticity = elasticity * 1.25;
        }
        // 珍宝类（钻石、下界之星等）库存影响最大：等效库存 ×2、弹性 ×1.35
        try {
            if ("treasure".equals(com.tangtu.kjbb.ElasticityReloadListener.stockGroupOf(itemId))) {
                s = (long) Math.max(1L, Math.round(s * 2.0));
                elasticity = elasticity * 1.35;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        double raw = Math.pow((double) target / (double) s, elasticity);
        double min = BotConfig.get().stockFactorMin;
        if (min <= 0.0) {
            min = ShopPrices.stockFactorMin();
        }
        double max = BotConfig.get().stockFactorMax;
        if (max <= 0.0) {
            max = ShopPrices.stockFactorMax();
        }
        if (min <= 0.0) {
            min = 0.3;
        }
        if (max < min) {
            max = min;
        }
        if (raw < min) {
            raw = min;
        }
        if (raw > max) {
            raw = max;
        }
        return raw;
    }

    /** 动态单价：基础价 × 库存因子；基础价<=0 返回 0（不出售） */
    public static long price(String itemId, long stock) {
        long base = ShopPrices.price(itemId);
        if (base <= 0L) {
            return 0L;
        }
        long p = Math.round(base * factor(stock));
        return Math.max(1L, p);
    }

    /** 给玩家看的因子说明，例如 "×1.24（库存 400）" */
    public static String describe(long stock) {
        return String.format("×%.2f（库存 %d）", factor(stock), stock);
    }

    public static String version() {
        return "1.7.0";
    }
}
