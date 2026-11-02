package com.tangtu.kjbb;

import java.util.ArrayList;
import java.util.List;

/**
 * 经济参考台
 *
 * 把「玩家真实产量 + 市场价格 + 三家库存」合起来，给 NPC 决策提供依据：
 *
 *   · surplus()  —— 玩家生产过剩、价格跌破基准的品类（常平仓可以托底收购）
 *   · shortage() —— 玩家几乎不产、价格高于基准、国库与国民库存都少的品类（国民可以补产）
 *
 * 全部基于 PlayerProduction（模组自记的合成/熔炼统计），不依赖外部文件。
 */
public final class EconomyAdvisor {

    private EconomyAdvisor() {
    }

    /** 当前游戏日（用于「每天只估一次」） */
    private static long today() {
        try {
            return com.tangtu.kjbb.ServerCalendar.currentDay();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 国民「感知到」的玩家产量：
     *   · 每天给每个物品一个固定的随机偏差（0.55 ~ 1.45 倍）
     *   · 产量低于 50 件时直接当作 0（零散小作坊，国民看不出来）
     *   · 同一天内结果稳定（不会每 tick 乱跳）
     */
    public static long perceived(String item) {
        if (item == null || item.isEmpty()) {
            return 0L;
        }
        long real = PlayerProduction.produced(item);
        if (real < 50L) {
            return 0L;
        }
        long day = today();
        long seed = (day * 1_000_003L) ^ item.hashCode();
        java.util.Random rnd = new java.util.Random(seed);
        double factor = 0.55 + rnd.nextDouble() * 0.90;      // 0.55 ~ 1.45
        long v = Math.round(real * factor);
        // 再做一次「粗粒度」：按百位取整，显得更模糊
        if (v >= 1000L) {
            v = (v / 100L) * 100L;
        } else if (v >= 100L) {
            v = (v / 10L) * 10L;
        }
        return v;
    }

    private static long baseOf(String item) {
        long b = ShopPrices.price(item);
        if (b <= 0L) {
            b = MarketPrice.price(item);
        }
        return b;
    }

    private static long marketOf(String item) {
        long m = MarketPrice.price(item);
        if (m <= 0L) {
            m = ShopPrices.price(item);
        }
        return m;
    }

    /**
     * 生产过剩：产量榜里有量、且市价低于基准价 90%
     *
     * @param limit 最多返回几种
     */
    public static List<String> surplus(int limit) {
        List<String> out = new ArrayList<>();
        for (var e : PlayerProduction.topProduced(48)) {
            String item = e.getKey();
            long made = perceived(item);        // 国民只知道个大概
            if (made < 200L) {
                continue;                      // 感知到产量太少，谈不上过剩
            }
            long base = baseOf(item);
            long market = marketOf(item);
            if (base <= 0L || market <= 0L) {
                continue;
            }
            if (market * 100L < base * 90L) {   // 市价 < 基准 × 0.9
                out.add(item);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 缺货：市价高于基准 110%，且国库 + 国民库存都很少
     */
    public static List<String> shortage(int limit) {
        List<String> out = new ArrayList<>();
        String reg = MarketBots.regulatorName();
        String citizen = MarketBots.citizenName();
        for (String item : ShopPrices.items()) {
            long base = ShopPrices.price(item);
            if (base <= 0L) {
                continue;
            }
            long market = marketOf(item);
            if (market <= 0L || market * 100L < base * 110L) {
                continue;                      // 没涨价，不算缺
            }
            long stock = 0L;
            try {
                if (reg != null) {
                    stock += BotLedger.stock(reg, item);
                }
                if (citizen != null) {
                    stock += BotLedger.stock(citizen, item);
                }
            } catch (Throwable ignored) {
                // ignore
            }
            if (stock > 96L) {
                continue;                      // 库存还够
            }
            if (perceived(item) > 4000L) {
                continue;                      // 它估摸着玩家在生产，就不急
            }
            out.add(item);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** 一行摘要，写日志用 */
    public static String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("过剩(估)=");
        List<String> s = surplus(4);
        sb.append(s.isEmpty() ? "无" : String.join(",", s));
        sb.append("  缺货(估)=");
        List<String> t = shortage(4);
        sb.append(t.isEmpty() ? "无" : String.join(",", t));
        return sb.toString();
    }
}
