package com.tangtu.kjbb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 基准价的长期趋势层（第三层时间尺度）
 *
 *   短期：MarketPrice 成交 EMA
 *   中期：BasePriceDrift 每 20 分钟 ±10% 修正
 *   长期：本类 —— 基准价向「近期成交均价」缓慢靠拢
 *
 * 为什么要这一层：
 *   价格回流（priceAnchorPerMin）会把「量产带来的永久降价」当成噪音抹掉。
 *   长期层让基准价跟随真实成交趋势，技术革新才能真正反映到价格上。
 */
public final class BasePriceLongRun {

    private BasePriceLongRun() {
    }

    /** 每次调整的间隔（毫秒） */
    public static long INTERVAL_MS = 6L * 60L * 60_000L;
    /** 统计窗口（毫秒） */
    public static long WINDOW_MS = 24L * 60L * 60_000L;
    /** 每次向均价靠拢的比例 */
    public static double TRACK_RATIO = 0.10;
    /** 窗口内至少多少笔成交才算数 */
    public static int MIN_TRADES = 20;
    /** 单个物品最多保存多少笔（防止内存膨胀） */
    private static final int MAX_POINTS = 4000;

    private static final Map<String, Deque<long[]>> POINTS = new HashMap<>();
    private static long nextAt = 0L;

    /** 记录一笔成交（由 MarketPrice.onTrade 调用） */
    public static synchronized void record(String itemId, long price, long nowMs) {
        if (itemId == null || price <= 0L) {
            return;
        }
        Deque<long[]> q = POINTS.computeIfAbsent(itemId, k -> new ArrayDeque<>());
        q.addLast(new long[]{nowMs, price});
        while (q.size() > MAX_POINTS) {
            q.pollFirst();
        }
        // 顺便清掉窗口外的
        while (!q.isEmpty() && nowMs - q.peekFirst()[0] > WINDOW_MS) {
            q.pollFirst();
        }
    }

    /** 定期调用（每 5 秒一次即可，内部自己控制间隔） */
    public static synchronized void tick(long nowMs) {
        var cfg = BotConfig.get();
        if (!cfg.baseLongRunEnabled) {
            return;
        }
        // 参数热更新
        INTERVAL_MS = Math.max(60_000L, Math.round(cfg.baseLongRunIntervalHours * 60.0 * 60_000.0));
        WINDOW_MS = Math.max(60_000L, Math.round(cfg.baseLongRunWindowHours * 60.0 * 60_000.0));
        TRACK_RATIO = Math.max(0.0, Math.min(0.5, cfg.baseLongRunTrackRatio));
        MIN_TRADES = Math.max(1, cfg.baseLongRunMinTrades);
        if (nowMs < nextAt) {
            return;
        }
        nextAt = nowMs + INTERVAL_MS;
        int changed = 0;
        var ids = new ArrayList<>(POINTS.keySet());
        for (String id : ids) {
            Deque<long[]> q = POINTS.get(id);
            if (q == null || q.size() < MIN_TRADES) {
                continue;
            }
            long sumPrice = 0L;
            long sumQty = 0L;
            long cutoff = nowMs - WINDOW_MS;
            for (long[] p : q) {
                if (p[0] < cutoff) {
                    continue;
                }
                sumPrice += p[1];
                sumQty++;
            }
            if (sumQty < MIN_TRADES) {
                continue;
            }
            long avg = sumPrice / sumQty;
            long base = ShopPrices.price(id);
            if (base <= 0L || avg <= 0L) {
                continue;
            }
            long target = Math.round(base * (1.0 - TRACK_RATIO) + avg * TRACK_RATIO);
            target = Math.max(1L, target);
            if (target == base) {
                continue;
            }
            // 单次调整幅度限制在 ±25%，避免一次极端行情把基准带飞
            long lo = Math.round(base * 0.75);
            long hi = Math.round(base * 1.25);
            target = Math.max(lo, Math.min(hi, target));
            if (target == base) {
                continue;
            }
            ShopPrices.setPrice(id, target);
            changed++;
            System.out.println("[TangtuEconomical] 基准价长期跟随 " + id + " " + base
                    + " → " + target + "（24h 成交均价 " + avg + "，样本 " + sumQty + "）");
        }
        if (changed > 0) {
            System.out.println("[TangtuEconomical] 基准价长期跟随：本次调整 " + changed + " 种物品");
        }
    }

    /** 某物品的近期成交均价（给界面/命令查看） */
    public static synchronized long averageOf(String itemId) {
        if (itemId == null) {
            return 0L;
        }
        Deque<long[]> q = POINTS.get(itemId);
        if (q == null || q.isEmpty()) {
            return 0L;
        }
        long now = ServerClock.now();
        long cut = now - WINDOW_MS;
        long sum = 0L;
        long n = 0L;
        for (long[] p : q) {
            if (p[0] >= cut) {
                sum += p[1];
                n++;
            }
        }
        return n == 0L ? 0L : sum / n;
    }
}
