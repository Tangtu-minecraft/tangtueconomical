package com.tangtu.kjbb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基准价自动修正
 *
 * 逻辑：
 *   每 20 分钟检查一次所有「有基准价」的物品，
 *   把当前市场价（MarketPrice）与基准价比较：
 *
 *     ratio ≥ 1.30  → 记一次「偏高」
 *     ratio ≤ 0.70  → 记一次「偏低」
 *     其它          → 清零
 *
 *   同一个方向连续 3 次（约 60 分钟）→ 把基准价朝市场价挪 10%
 *
 * 这样价格长期脱离实际时，机器人会自己把基准价调过去，
 * 而不是一直按老价格买卖。
 */
public final class BasePriceDrift {

    private BasePriceDrift() {
    }

    /** 检查间隔 */
    public static long INTERVAL_MS = 20L * 60_000L;
    /** 需要连续多少次同向才修正 */
    public static int NEED_STREAK = 3;
    /** 每次修正的比例 */
    public static double STEP = 0.10;

    private static final Map<String, Integer> STREAK = new LinkedHashMap<>();
    private static final Map<String, Boolean> DIR = new LinkedHashMap<>();
    private static long nextAt = 0L;

    /** 由 MarketBots 的定时器调用 */
    public static synchronized void tick(long nowMs) {
        if (nowMs < nextAt) {
            return;
        }
        nextAt = nowMs + INTERVAL_MS;
        try {
            int changed = 0;
            for (String id : ShopPrices.items()) {
                int base = (int) ShopPrices.price(id);
                if (base <= 0) {
                    continue;
                }
                long market = MarketPrice.price(id);
                if (market <= 0) {
                    continue;
                }
                double ratio = (double) market / (double) base;
                Boolean up = null;
                if (ratio >= 1.30) {
                    up = Boolean.TRUE;
                } else if (ratio <= 0.70) {
                    up = Boolean.FALSE;
                }
                if (up == null) {
                    STREAK.remove(id);
                    DIR.remove(id);
                    continue;
                }
                if (DIR.containsKey(id) && up.equals(DIR.get(id))) {
                    STREAK.merge(id, 1, Integer::sum);
                } else {
                    DIR.put(id, up);
                    STREAK.put(id, 1);
                }
                if (STREAK.getOrDefault(id, 0) < NEED_STREAK) {
                    continue;
                }
                // 连续同向够次数 → 修正基准价
                int target = up ? (int) Math.round(base * (1.0 + STEP))
                        : (int) Math.round(base * (1.0 - STEP));
                target = Math.max(1, target);
                if (target != base) {
                    ShopPrices.setPrice(id, target);
                    changed++;
                    System.out.println("[TangtuEconomical] 基准价修正 " + id + " " + base + " → " + target
                            + "（市场价 " + market + "，比 " + String.format("%.2f", ratio)
                            + (up ? " 偏高" : " 偏低") + "）");
                }
                STREAK.remove(id);
                DIR.remove(id);
            }
            if (changed > 0) {
                System.out.println("[TangtuEconomical] 本轮共修正 " + changed + " 个基准价");
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 基准价修正异常: " + t);
        }
    }
}
