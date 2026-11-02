package com.tangtu.kjbb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量性能计时器 + 任务预算。
 *
 * 用法：
 * <pre>
 *   long t = Perf.begin();
 *   ...做事...
 *   Perf.end("market.tick", t);
 * </pre>
 *
 * 每个 section 记录：调用次数、总耗时、平均、最大、超预算次数。
 * 超预算（单次 > perfBudgetMs）会写一条 economy_debug，方便事后定位尖峰。
 */
public final class Perf {

    /** 单个统计段 */
    public static final class Stat {
        public long calls;
        public long totalUs;
        public long maxUs;
        public long overBudget;
        public String lastNote = "";

        public double avgMs() {
            return calls == 0 ? 0.0 : totalUs / 1000.0 / calls;
        }

        public double maxMs() {
            return maxUs / 1000.0;
        }

        public double totalMs() {
            return totalUs / 1000.0;
        }
    }

    private static final Map<String, Stat> STATS = new LinkedHashMap<>();
    private static long budgetUs = 5000L;
    private static boolean enabled = true;

    private Perf() {
    }

    private static void refreshConfig() {
        try {
            enabled = BotConfig.get().perfEnabled;
            budgetUs = Math.max(100L, BotConfig.get().perfBudgetMs) * 1000L;
        } catch (Throwable ignored) {
            // 用默认
        }
    }

    /** 开始计时（返回纳秒时间戳） */
    public static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    /** 结束计时并记账 */
    public static void end(String section, long startNanos) {
        if (startNanos == 0L || section == null) {
            return;
        }
        refreshConfig();
        if (!enabled) {
            return;
        }
        long us = (System.nanoTime() - startNanos) / 1000L;
        Stat st;
        synchronized (STATS) {
            st = STATS.computeIfAbsent(section, k -> new Stat());
            st.calls++;
            st.totalUs += us;
            if (us > st.maxUs) {
                st.maxUs = us;
            }
            if (us > budgetUs) {
                st.overBudget++;
            }
        }
        if (us > budgetUs) {
            DebugLog.log("[PERF] " + section + " 超预算：" + String.format("%.1f", us / 1000.0)
                    + "ms（预算 " + budgetUs / 1000.0 + "ms）");
        }
    }

    /** 计时一个会返回值的任务 */
    public static <T> T time(String section, java.util.function.Supplier<T> task) {
        long t = begin();
        try {
            return task.get();
        } finally {
            end(section, t);
        }
    }

    /** 计时一个无返回值任务 */
    public static void time(String section, Runnable task) {
        long t = begin();
        try {
            task.run();
        } finally {
            end(section, t);
        }
    }

    /** 报表：按总耗时降序 */
    public static String report() {
        StringBuilder sb = new StringBuilder("§6===== 性能计时 =====\n");
        java.util.List<Map.Entry<String, Stat>> list;
        synchronized (STATS) {
            list = new java.util.ArrayList<>(STATS.entrySet());
        }
        if (list.isEmpty()) {
            return sb.append("§7（还没有采样）").toString();
        }
        list.sort((a, b) -> Long.compare(b.getValue().totalUs, a.getValue().totalUs));
        sb.append("§7预算 §f").append(budgetUs / 1000.0).append("ms§7/次   段数 §f")
                .append(list.size()).append('\n');
        for (var e : list) {
            Stat s = e.getValue();
            sb.append("§e").append(e.getKey()).append("§7  调用 §f").append(s.calls)
                    .append("§7  均 §f").append(String.format("%.2f", s.avgMs()))
                    .append("ms§7  峰 §f").append(String.format("%.2f", s.maxMs()))
                    .append("ms§7  超预算 §f").append(s.overBudget)
                    .append("§7  合计 §f").append(String.format("%.1f", s.totalMs())).append("ms\n");
        }
        return sb.toString();
    }

    /** 一行摘要（定期写日志用） */
    public static String summary() {
        Map.Entry<String, Stat> worst = null;
        synchronized (STATS) {
            for (var e : STATS.entrySet()) {
                if (worst == null || e.getValue().totalUs > worst.getValue().totalUs) {
                    worst = e;
                }
            }
        }
        if (worst == null) {
            return "性能：无采样";
        }
        Stat s = worst.getValue();
        return String.format("性能：最重 %s 均 %.2fms 峰 %.2fms 调用 %d 次",
                worst.getKey(), s.avgMs(), s.maxMs(), s.calls);
    }

    /** 清零（/billperf reset） */
    public static void reset() {
        synchronized (STATS) {
            STATS.clear();
        }
    }
}
