package com.tangtu.kjbb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 市场脉搏：记录每个物品的「最近成交价」与指数平均值。
 *
 * 机器人用它判断「现在偏贵还是偏便宜」：
 *   · 调节型机器人 → 偏贵就出货打压，偏便宜就收货运价
 *   · 搅局型机器人 → 无视它，纯随机报价
 */
public final class MarketPulse {

    private static final Map<String, Long> LAST = new LinkedHashMap<>();
    private static final Map<String, Double> EMA = new LinkedHashMap<>();

    private MarketPulse() {
    }

    /** 记一次成交 */
    public static void record(String itemId, long price) {
        if (itemId == null || price <= 0) {
            return;
        }
        LAST.put(itemId, price);
        Double old = EMA.get(itemId);
        // 平滑系数 0.35
        EMA.put(itemId, old == null ? (double) price : old * 0.65 + price * 0.35);
    }

    /** 最近一次成交价（0 = 还没有成交记录） */
    public static long last(String itemId) {
        Long v = LAST.get(itemId);
        return v == null ? 0L : v;
    }

    /** 指数平均（0 = 还没有成交记录） */
    public static double ema(String itemId) {
        Double v = EMA.get(itemId);
        return v == null ? 0.0 : v;
    }

    /** 参考价：有成交记录就用 EMA 与基准价混合，没有就用基准价 */
    public static long reference(String itemId, long base) {
        Double e = EMA.get(itemId);
        if (e == null || e <= 0) {
            return base;
        }
        long v = Math.round(e * 0.7 + base * 0.3);
        return v <= 0 ? base : v;
    }

    public static void clear() {
        LAST.clear();
        EMA.clear();
    }
}
