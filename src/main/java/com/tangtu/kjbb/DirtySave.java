package com.tangtu.kjbb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脏标记 + 批量落盘。
 *
 * 以前每次改一个钱包余额、加一条订单、更新一根 K 线，都会立刻 Files.writeString 整个文件——
 * 一次 5 秒的市场刷新里可能发生上百次全量写盘，同步 I/O 直接把 TPS 拖下去。
 *
 * 现在：改数据只调 {@code DirtySave.mark("wallet")}，真正写盘由
 *   ① 每 saveIntervalTicks（默认 100 tick = 5 秒）批量刷一次
 *   ② 服务器停止时 flushAll()
 *   ③ 管理员执行 /billflush 强制刷
 * 三者触发，写盘次数从「每次修改」降到「每 5 秒」，且写入走 SafeFile 的原子替换。
 *
 * 用之前要先 {@link #register(String, Runnable)} 注册一个真正执行写盘的回调。
 */
public final class DirtySave {

    /** key -> 真正的落盘动作 */
    private static final Map<String, Runnable> FLUSHERS = new LinkedHashMap<>();
    /** 当前脏的 key */
    private static final Map<String, Long> DIRTY = new LinkedHashMap<>();

    private static long tickCounter = 0L;
    private static long lastFlushTick = 0L;
    private static long flushCount = 0L;
    private static long markCount = 0L;

    private DirtySave() {
    }

    /** 注册落盘回调（各存储类在自己的静态初始化里调用一次即可） */
    public static synchronized void register(String key, Runnable flusher) {
        if (key == null || flusher == null) {
            return;
        }
        FLUSHERS.put(key, flusher);
    }

    /** 标脏：只记一个 key，不做任何 I/O */
    public static synchronized void mark(String key) {
        if (key == null) {
            return;
        }
        DIRTY.put(key, tickCounter);
        markCount++;
    }

    /** 这个 key 是否还没落盘 */
    public static synchronized boolean isDirty(String key) {
        return DIRTY.containsKey(key);
    }

    /** 立刻落盘指定 key */
    public static synchronized void flush(String key) {
        if (key == null) {
            return;
        }
        Runnable f = FLUSHERS.get(key);
        if (f == null) {
            DIRTY.remove(key);
            return;
        }
        try {
            f.run();
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] flush " + key + " 失败: " + t);
            return;                 // 失败保留脏标记，下个周期再试
        }
        DIRTY.remove(key);
        flushCount++;
    }

    /** 立刻落盘所有脏数据 */
    public static void flushAll() {
        java.util.List<String> keys;
        synchronized (DirtySave.class) {
            keys = new java.util.ArrayList<>(DIRTY.keySet());
        }
        for (String k : keys) {
            flush(k);
        }
    }

    /** 无条件落盘所有已注册的存储（关服用，避免还有未标脏但改了内存没标记的边界情况） */
    public static void flushEverything() {
        java.util.List<String> keys;
        synchronized (DirtySave.class) {
            keys = new java.util.ArrayList<>(FLUSHERS.keySet());
        }
        for (String k : keys) {
            flush(k);
        }
    }

    /**
     * 每 tick 调一次；到间隔就批量落盘。
     *
     * @return 本次是否真的刷了盘
     */
    public static boolean tick() {
        int interval;
        try {
            interval = Math.max(1, BotConfig.get().saveIntervalTicks);
        } catch (Throwable t) {
            interval = 100;
        }
        boolean did = false;
        synchronized (DirtySave.class) {
            tickCounter++;
            if (tickCounter - lastFlushTick < interval) {
                return false;
            }
            lastFlushTick = tickCounter;
            if (DIRTY.isEmpty()) {
                return false;
            }
            did = true;
        }
        flushAll();
        return did;
    }

    /** 统计（给 /billperf 和日志用） */
    public static synchronized String stats() {
        return "脏标记 " + DIRTY.size() + " 项 / 已注册 " + FLUSHERS.size()
                + " 项；累计标脏 " + markCount + " 次，实际落盘 " + flushCount + " 次"
                + "（合并率 " + (flushCount > 0 ? String.valueOf(markCount / Math.max(1, flushCount)) : "-") + ":1）";
    }

    public static synchronized int dirtyCount() {
        return DIRTY.size();
    }

    public static synchronized int registeredCount() {
        return FLUSHERS.size();
    }
}
