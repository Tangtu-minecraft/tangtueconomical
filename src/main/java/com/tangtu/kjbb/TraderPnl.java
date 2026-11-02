package com.tangtu.kjbb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 投机客盈亏账
 *
 * 用「加权平均成本」记持仓：
 *   买入 → 数量与成本累加
 *   卖出 → 用当前均价算已实现盈亏，并扣减持仓
 *
 * 存盘：config/kjbb/trader_pnl.txt
 *   第一行：realized|wins|losses|buyVolume|sellVolume
 *   之后： itemId|qty|costTotal
 */
public final class TraderPnl {

    private TraderPnl() {
    }

    /** 已实现盈亏 */
    private static long REALIZED = 0L;
    private static int WINS = 0;
    private static int LOSSES = 0;
    /** 累计买入额 / 卖出额（流水口径） */
    private static long BUY_VOLUME = 0L;
    private static long SELL_VOLUME = 0L;

    /** itemId → [qty, costTotal] */
    private static final Map<String, long[]> HOLD = new LinkedHashMap<>();

    private static boolean LOADED = false;

    private static Path file() {
        return Path.of("config", "kjbb", "trader_pnl.txt");
    }

    private static synchronized void load() {
        if (LOADED) {
            return;
        }
        LOADED = true;
        try {
            Path p = file();
            if (!Files.exists(p)) {
                return;
            }
            var lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            boolean head = true;
            for (String line : lines) {
                if (line == null || line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|");
                if (head) {
                    head = false;
                    if (f.length >= 5) {
                        REALIZED = Long.parseLong(f[0].trim());
                        WINS = Integer.parseInt(f[1].trim());
                        LOSSES = Integer.parseInt(f[2].trim());
                        BUY_VOLUME = Long.parseLong(f[3].trim());
                        SELL_VOLUME = Long.parseLong(f[4].trim());
                        continue;
                    }
                }
                if (f.length >= 3) {
                    HOLD.put(f[0].trim(), new long[]{
                            Long.parseLong(f[1].trim()), Long.parseLong(f[2].trim())});
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static synchronized void flushNow() {
        try {
            Path p = file();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# realized|wins|losses|buyVolume|sellVolume\n");
            sb.append(REALIZED).append('|').append(WINS).append('|').append(LOSSES)
                    .append('|').append(BUY_VOLUME).append('|').append(SELL_VOLUME).append('\n');
            for (var e : HOLD.entrySet()) {
                sb.append(e.getKey()).append('|').append(e.getValue()[0]).append('|')
                        .append(e.getValue()[1]).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 标脏：真正写盘由 DirtySave 每 saveIntervalTicks 批量执行 */
    private static synchronized void save() {
        DirtySave.mark("traderpnl");
    }

    static {
        DirtySave.register("traderpnl", TraderPnl::flushNow);
    }


    /** 记一笔买入 */
    public static synchronized void buy(String itemId, int qty, long unit) {
        if (itemId == null || qty <= 0 || unit <= 0L) {
            return;
        }
        load();
        long[] cur = HOLD.computeIfAbsent(itemId, k -> new long[]{0L, 0L});
        cur[0] += qty;
        cur[1] += unit * qty;
        BUY_VOLUME += unit * qty;
        save();
    }

    /** 记一笔卖出（加权平均成本结算） */
    public static synchronized void sell(String itemId, int qty, long unit) {
        if (itemId == null || qty <= 0 || unit <= 0L) {
            return;
        }
        load();
        long[] cur = HOLD.get(itemId);
        long revenue = unit * qty;
        SELL_VOLUME += revenue;
        if (cur == null || cur[0] <= 0L) {
            // 没成本记录（历史持仓）→ 只记收入
            REALIZED += revenue;
            WINS++;
            save();
            return;
        }
        int matched = (int) Math.min(qty, cur[0]);
        long avg = cur[1] / Math.max(1L, cur[0]);
        long cost = avg * matched;
        long profit = revenue - cost;
        REALIZED += profit;
        if (profit >= 0L) {
            WINS++;
        } else {
            LOSSES++;
        }
        cur[0] -= matched;
        cur[1] = Math.max(0L, cur[1] - cost);
        if (cur[0] <= 0L) {
            HOLD.remove(itemId);
        }
        save();
    }

    /** 生成报表 */
    public static synchronized String report() {
        load();
        long qty = 0L;
        long cost = 0L;
        for (long[] v : HOLD.values()) {
            qty += v[0];
            cost += v[1];
        }
        int trades = WINS + LOSSES;
        double winRate = trades == 0 ? 0.0 : WINS * 100.0 / trades;
        StringBuilder sb = new StringBuilder();
        sb.append("\u00a76\u3010\u6295\u673a\u76c8\u4e8f\u8d26\u3011\n");
        sb.append("\u00a77\u5df2\u5b9e\u73b0\u76c8\u4e8f\uff1a\u00a7f").append(signed(REALIZED)).append('\n');
        sb.append("\u00a77\u76c8\u5229\u7b14\u6570\uff1a\u00a7a").append(WINS)
                .append("\u00a77\uff0c\u4e8f\u635f\u7b14\u6570\uff1a\u00a7c").append(LOSSES)
                .append("\u00a77\uff0c\u80dc\u7387\uff1a\u00a7f")
                .append(String.format(Locale.ROOT, "%.0f%%", winRate)).append('\n');
        sb.append("\u00a77\u7d2f\u8ba1\u4e70\u5165\uff1a\u00a7f").append(BUY_VOLUME)
                .append("\u00a77\uff0c\u7d2f\u8ba1\u5356\u51fa\uff1a\u00a7f").append(SELL_VOLUME).append('\n');
        sb.append("\u00a77\u5f53\u524d\u6301\u4ed3\uff1a\u00a7f").append(qty)
                .append(" \u4ef6\uff0c\u6210\u672c ").append(cost).append('\n');
        // 前 6 个持仓
        int shown = 0;
        for (var e : HOLD.entrySet()) {
            if (shown++ >= 6) {
                sb.append("\u00a78  \u2026\n");
                break;
            }
            long[] v = e.getValue();
            sb.append("\u00a78  \u00b7 \u00a77").append(e.getKey()).append(" \u00a7f")
                    .append(v[0]).append(" \u4ef6 @")
                    .append(v[1] / Math.max(1L, v[0])).append('\n');
        }
        return sb.toString();
    }

    private static String signed(long v) {
        if (v > 0L) {
            return "\u00a7a+" + v;
        }
        if (v < 0L) {
            return "\u00a7c" + v;
        }
        return "0";
    }

    /** 清空（测试用） */
    public static synchronized void reset() {
        load();
        REALIZED = 0L;
        WINS = 0;
        LOSSES = 0;
        BUY_VOLUME = 0L;
        SELL_VOLUME = 0L;
        HOLD.clear();
        save();
    }
}
