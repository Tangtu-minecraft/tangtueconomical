package com.tangtu.kjbb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K 线聚合：把散落的成交价合成「真正的蜡烛」。
 *
 * 问题背景：之前每笔成交直接 push(price,price,price,price) —— 开高低收全一样，
 * 画出来没有影线。现在改成按周期（默认 5 分钟）聚合：
 *
 *   一个周期内记录 开 / 高 / 低 / 收 / 成交量
 *   周期结束时把这一根完整的蜡烛推进 stockmarket 的 PriceHistory
 *
 * 这样 K 线上才会出现：实体（开收差）+ 上下影线（最高最低）+ 成交量。
 */
public final class KlineAggregator {

    /**
     * 一根蜡烛的宽度（毫秒）。
     *
     * 必须与 stockmarket 的 ServerMarket.shiftPriceHistoryInterval 对齐，
     * 那边的默认值是 60000（1 分钟）。两边不一致时，图上会出现
     * 「几格重复/空档」的怪现象。
     */
    public static long periodMs() {
        return BotConfig.get().klinePeriodMs;
    }

    public static final class Candle {
        public long start;
        public int open;
        public int high;
        public int low;
        public int close;
        public long volume;

        public Candle(long start, int price, int count) {
            this.start = start;
            this.open = price;
            this.high = price;
            this.low = price;
            this.close = price;
            this.volume = count;
        }

        public void feed(int price, int count) {
            if (price > high) {
                high = price;
            }
            if (price < low) {
                low = price;
            }
            close = price;
            volume += count;
        }
    }

    private static final Map<String, Candle> CURRENT = new LinkedHashMap<>();
    /** 每物品的成交笔数（按笔数合成 K 线用） */
    private static final Map<String, Integer> TRADE_SEQ = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, List<Candle>> DONE = new LinkedHashMap<>();
    private static final int KEEP = 64;
    /** 每个物品上次推送的蜡烛状态，避免重复推同一根（否则图上会出现一堆一模一样的蜡烛） */
    private static final Map<String, String> LAST_PUSH = new LinkedHashMap<>();

    private static String sig(Candle c) {
        return c.open + "," + c.high + "," + c.low + "," + c.close;
    }

    private KlineAggregator() {
    }

    /** 收到一笔成交 */
    /** 价格熔断：成交价超出「基准价 ÷ 3 ~ × 3」就夹回区间 */
    private static int clampPrice(String itemId, int price) {
        try {
            long base = com.tangtu.kjbb.ShopPrices.price(itemId);
            if (base <= 0L) {
                return price;
            }
            long lo = Math.max(1L, base / 3L);
            long hi = base * 3L;
            if (price < lo) {
                DebugLog.log("K线熔断： " + itemId + " 成交价 " + price
                        + " 低于基准 " + base + " 的 1/3，按 " + lo + " 记");
                return (int) Math.min(Integer.MAX_VALUE, lo);
            }
            if (price > hi) {
                DebugLog.log("K线熔断： " + itemId + " 成交价 " + price
                        + " 高于基准 " + base + " 的 3 倍，按 " + hi + " 记");
                return (int) Math.min(Integer.MAX_VALUE, hi);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return price;
    }

    public static synchronized void onTrade(String itemId, int price, int count) {
        if (itemId == null || price <= 0) {
            return;
        }
        price = clampPrice(itemId, price);
        long now = ServerClock.now();
        long period = periodMs();
        // 按笔数合成：每 N 笔成交一根
        int perBar = 10;
        try {
            perBar = BotConfig.get().klineTradesPerBar;
        } catch (Throwable ignored) {
            perBar = 10;
        }
        if (perBar > 1) {
            int n = TRADE_SEQ.merge(itemId, 1, Integer::sum);
            Candle c0 = CURRENT.get(itemId);
            if (c0 == null) {
                CURRENT.put(itemId, new Candle(now, price, Math.max(1, count)));
            } else {
                c0.feed(price, Math.max(1, count));
                if (n % perBar == 0) {
                    flush(itemId, c0);
                    CURRENT.remove(itemId);
                }
            }
            return;
        }
        Candle cur = CURRENT.get(itemId);
        if (cur == null || now - cur.start >= period) {
            if (cur != null) {
                flush(itemId, cur);
            }
            cur = new Candle(now - (now % period), price, Math.max(1, count));
            CURRENT.put(itemId, cur);
        } else {
            cur.feed(price, Math.max(1, count));
        }
    }

    /** 周期结束：把这根蜡烛推给 stockmarket 并留档 */
    private static void flush(String itemId, Candle c) {
        try {
            KlineStore.append(itemId, c.start, c.open, c.high, c.low, c.close, c.volume);
            LAST_PUSH.put(itemId, sig(c));
        } catch (Throwable ignored) {
            // 忽略
        }
        List<Candle> list = DONE.computeIfAbsent(itemId, k -> new ArrayList<>());
        list.add(c);
        while (list.size() > KEEP) {
            list.remove(0);
        }
    }

    /** 把当前未结束的蜡烛也推一次（让最新价立刻出现在图上） */
    /** 当前正在形成的所有物品（供定时落盘用） */
    public static synchronized java.util.Set<String> trackedItems() {
        return new java.util.LinkedHashSet<>(CURRENT.keySet());
    }

    /** 供外部定时调用：只推「已经变化且尚未推送」的当前蜡烛（不会刷屏） */
    public static synchronized void pushLive(String itemId) {
        Candle cur = CURRENT.get(itemId);
        if (cur == null) {
            return;
        }
        try {
            String s = sig(cur);
            if (s.equals(LAST_PUSH.get(itemId))) {
                return;   // 这一根没变化，不用重复推
            }
            KlineStore.append(itemId, cur.start, cur.open, cur.high, cur.low, cur.close,
                    cur.volume);
            LAST_PUSH.put(itemId, s);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 到点结算：
     *   ① 有未完成蜡烛且已过周期 → 结算写盘
     *   ② 有历史但这一周期没成交 → 补一根平价蜡烛（开=高=低=收=上根收，量 0）
     *      让时间轴连续，不会出现「15 分直接跳到 18 分」
     */
    public static synchronized void tick() {
        long now = ServerClock.now();
        long period = periodMs();

        // ① 结算过期的进行中蜡烛
        for (String id : new ArrayList<>(CURRENT.keySet())) {
            Candle cur = CURRENT.get(id);
            if (cur == null) {
                continue;
            }
            long end = cur.start + period;
            if (now >= end) {
                flush(id, cur);
                CURRENT.remove(id);
            }
        }

        // ② 给冷门物品补平价蜡烛（默认关闭：没成交就跳过，不留空柱）
        boolean fillEmpty = false;
        try {
            fillEmpty = BotConfig.get().klineFillEmptyBars;
        } catch (Throwable ignored) {
            fillEmpty = false;
        }
        if (!fillEmpty) {
            return;
        }
        int filled = 0;
        for (String id : KlineStore.items()) {
            if (filled >= 2) {
                break;
            }
            if (CURRENT.containsKey(id)) {
                continue;
            }
            long last = KlineStore.lastStart(id);
            if (last <= 0L) {
                continue;
            }
            long next = last + period;
            if (now < next) {
                continue;
            }
            int close = KlineStore.lastClose(id);
            if (close <= 0) {
                continue;
            }
            // 上一根若是异常价（超基准 2 倍 / 低于一半），空柱改用基准价
            try {
                long base = com.tangtu.kjbb.ShopPrices.price(id);
                if (base > 0L && (close > base * 2L || close * 2L < base)) {
                    DebugLog.log("K线空柱校正：" + id + " 沿用异常价 " + close
                            + "，改用基准 " + base);
                    close = (int) Math.min(Integer.MAX_VALUE, base);
                }
            } catch (Throwable ignored) {
                // ignore
            }
            KlineStore.append(id, next, close, close, close, close, 0);
            filled++;
        }
    }

    /** 已完成的蜡烛（倒序 = 最新在前） */
    public static synchronized List<Candle> recent(String itemId, int n) {
        List<Candle> out = new ArrayList<>();
        List<Candle> list = DONE.get(itemId);
        if (list != null) {
            for (int i = list.size() - 1; i >= 0 && out.size() < n; i--) {
                out.add(list.get(i));
            }
        }
        Candle cur = CURRENT.get(itemId);
        if (cur != null && out.size() < n) {
            out.add(0, cur);
        }
        return out;
    }

    /** 还有哪些物品在聚合 */
    public static synchronized List<String> items() {
        List<String> out = new ArrayList<>(DONE.keySet());
        for (String k : CURRENT.keySet()) {
            if (!out.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }
}
