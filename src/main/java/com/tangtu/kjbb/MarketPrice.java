package com.tangtu.kjbb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自由定价：市场价由成交推动，基础价只做「软约束」。
 *
 *   ① 初始价 = 基础价
 *   ② 每笔成交 → 指数平滑更新（新价权重 0.35）
 *   ③ 每分钟朝基础价回拉 3%（软锚定，防止跑到天边）
 *   ④ 只做宽幅护栏：基础价 × 0.25 ~ × 4.0
 *   ⑤ 库存因子只做「轻微」影响（±15%），不再是价格主导
 *
 * 结果：价格由买卖力量决定，基础价只是引力中心。
 */
public final class MarketPrice {

    private static final Map<String, Double> PRICE = new LinkedHashMap<>();

    // 锚定与护栏全部改由 bots.json 控制：
    //   priceAnchorPerMin = 0 → 完全不拉回基础价（价格纯由成交决定）
    //   priceFloor        → 只保留「不能为 0」的底线

    private MarketPrice() {
    }

    /** 一笔成交 */
    public static synchronized void onTrade(String itemId, long price) {
        if (itemId == null || price <= 0) {
            return;
        }
        Double old = PRICE.get(itemId);
        double v = old == null ? price : old * 0.65 + price * 0.35;
        PRICE.put(itemId, v);
        // 长期趋势层：记录成交，供基准价缓慢跟随
        try {
            com.tangtu.kjbb.BasePriceLongRun.record(itemId, price, ServerClock.now());
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 每分钟调用：朝基础价回拉 */
    public static synchronized void anchorTick() {
        double a = BotConfig.get().priceAnchorPerMin;
        if (a <= 0.0) {
            return;                      // 不做任何回拉
        }
        for (String id : new java.util.ArrayList<>(PRICE.keySet())) {
            long baseP = ShopPrices.price(id);
            if (baseP <= 0) {
                continue;
            }
            double v = PRICE.getOrDefault(id, (double) baseP);
            v = v * (1 - a) + baseP * a;
            PRICE.put(id, v);
        }
    }

    /** 当前市场价（没有成交就用基础价） */
    public static synchronized long price(String itemId) {
        long baseP = ShopPrices.price(itemId);
        if (baseP <= 0) {
            return 0L;
        }
        Double v = PRICE.get(itemId);
        if (v == null || v <= 0) {
            return Math.max(BotConfig.get().priceFloor, baseP);
        }
        // 不再有任何相对基础价的上下限，只保证不为 0
        long floor = Math.max(1L, BotConfig.get().priceFloor);
        return Math.max(floor, Math.round(v));
    }

    /** 界面单价：市场价 × 库存的轻微修正（0.85 ~ 1.15） */
    public static long uiPrice(String itemId, long stock) {
        long p = price(itemId);
        if (p <= 0) {
            return 0L;
        }
        double influence = BotConfig.get().stockInfluence;
        if (influence <= 0.0) {
            return p;                                  // 库存不影响价格
        }
        double f = ShopPricing.factor(itemId, stock);          // 0.5 ~ 2.0
        double soft = 1.0 + (f - 1.0) * influence;
        if (soft < 0.05) {
            soft = 0.05;
        }
        long floor = Math.max(1L, BotConfig.get().priceFloor);
        return Math.max(floor, Math.round(p * soft));
    }

    /** 相对基础价的倍数（给界面显示） */
    public static String describe(String itemId) {
        long baseP = ShopPrices.price(itemId);
        long now = price(itemId);
        if (baseP <= 0) {
            return "未定价";
        }
        return String.format("%d 唐兔币（基础价 %d，×%.2f）", now, baseP,
                baseP == 0 ? 1.0 : (double) now / baseP);
    }

    /**
     * 带库存修正的价格（实际成交用）
     *
     *   库存不足 → 价格上浮，库存积压 → 价格下压
     *   幅度由 BotConfig.stockInfluence 控制（0 = 关闭）
     */
    public static long priceWithStock(String itemId) {
        long p = price(itemId);
        if (p <= 0L) {
            return 0L;
        }
        double influence = BotConfig.get().stockInfluence;
        if (influence <= 0.0) {
            return p;
        }
        int stock = 0;
        try {
            String citizen = com.tangtu.kjbb.MarketBots.citizenName();
            if (citizen != null) {
                stock = BotLedger.stock(citizen, itemId);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        double f = ShopPricing.factor(itemId, stock);
        double soft = 1.0 + (f - 1.0) * influence;
        soft = Math.max(0.05, soft);
        long floor = Math.max(1L, BotConfig.get().priceFloor);
        return Math.max(floor, Math.round(p * soft));
    }

    /** 库存修正系数（给界面显示：1.0 = 无影响） */
    public static double stockMul(String itemId) {
        double influence = BotConfig.get().stockInfluence;
        if (influence <= 0.0) {
            return 1.0;
        }
        int stock = 0;
        try {
            String citizen = com.tangtu.kjbb.MarketBots.citizenName();
            if (citizen != null) {
                stock = BotLedger.stock(citizen, itemId);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        double f = ShopPricing.factor(itemId, stock);
        return Math.max(0.05, 1.0 + (f - 1.0) * influence);
    }
}
