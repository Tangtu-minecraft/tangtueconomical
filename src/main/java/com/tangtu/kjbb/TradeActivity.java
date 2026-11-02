package com.tangtu.kjbb;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 交易活跃度：统计「玩家参与的成交」，用来动态收缩机器人的存在感。
 *
 * 规则：
 *   玩家越活跃 → 机器人越收敛（少挂单、少撮合、单量更小）
 *   玩家冷清   → 机器人恢复做市
 *
 * 窗口：最近 60 分钟
 */
public final class TradeActivity {

    private static final long WINDOW_MS = 60L * 60_000L;

    /** 玩家参与的成交时间戳 */
    private static final Deque<Long> PLAYER_TRADES = new ArrayDeque<>();
    /** 机器人之间的成交时间戳 */
    private static final Deque<Long> BOT_TRADES = new ArrayDeque<>();

    private TradeActivity() {
    }

    private static void push(Deque<Long> q, long now) {
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
            q.pollFirst();
        }
    }

    private static int count(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
            q.pollFirst();
        }
        return q.size();
    }

    /** 玩家买 / 卖成交 */
    public static synchronized void recordPlayerTrade() {
        push(PLAYER_TRADES, ServerClock.now());
    }

    /** 机器人之间的成交 */
    public static synchronized void recordBotTrade() {
        push(BOT_TRADES, ServerClock.now());
    }

    public static synchronized int playerTradesPerHour() {
        return count(PLAYER_TRADES, ServerClock.now());
    }

    public static synchronized int botTradesPerHour() {
        return count(BOT_TRADES, ServerClock.now());
    }

    /**
     * 机器人活跃度系数 0.15 ~ 1.0
     *
     *   玩家成交 0 笔/小时        → 1.00（机器人全力做市）
     *   玩家成交 = fullAt 笔/小时 → floor（机器人几乎退场）
     *   超过 fullAt               → 一直是 floor
     */
    public static double botScale() {
        BotConfig c = BotConfig.get();
        int p = playerTradesPerHour();
        double fullAt = Math.max(1.0, c.activityFullAt);
        double floor = Math.max(0.0, Math.min(1.0, c.activityFloor));
        double s = 1.0 - (p / fullAt);
        if (s < floor) {
            s = floor;
        }
        // 玩家在线时也保留至少一半做市能力（否则玩家感觉「卖不出去」）
        if (s < 0.5) {
            s = 0.5;
        }
        if (s > 1.0) {
            s = 1.0;
        }
        return s;
    }

    public static synchronized void clear() {
        PLAYER_TRADES.clear();
        BOT_TRADES.clear();
    }

    public static synchronized String describe() {
        return String.format("玩家成交 %d 笔/小时　机器人成交 %d 笔/小时　机器人活跃度 %.0f%%",
                playerTradesPerHour(), botTradesPerHour(), botScale() * 100);
    }
}
