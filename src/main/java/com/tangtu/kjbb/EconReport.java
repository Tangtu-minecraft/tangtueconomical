package com.tangtu.kjbb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 服务器经济体检报告
 *
 * 面向「不玩这个服的观察者」：用标准宏观指标描述这台服务器的经济健康度，
 * 不需要知道任何游戏机制。
 *
 * 输出分五节：
 *   一、货币与分配（货币总量、人均、基尼系数、前 10% 占比）
 *   二、价格与通胀（基准价指数、通胀率）
 *   三、交易活跃度（24h 成交笔数/金额、税收、税费比）
 *   四、公共部门（国民人口、资金、库存估值、是否危机/破产）
 *   五、市场深度与风险（挂单数、买卖价差、风险提示）
 */
public final class EconReport {

    private EconReport() {
    }

    /** 基准价基线（首次运行时记录，用作通胀计算的基期） */
    private static Path baselineFile() {
        return Path.of("config", "kjbb", "econ_baseline.txt");
    }

    public static List<String> build() {
        List<String> out = new ArrayList<>();
        out.add("\u00a78\u2500\u2500 \u5510\u5154\u56fd\u7ecf\u6d4e\u4f53\u68c0 \u2500\u2500");
        out.add("\u00a77\u751f\u6210\u65f6\u95f4\u00a7f "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()));
        out.add("\u00a77\u5510\u5154\u56fd\u65e5\u671f\uff1a\u00a7f"
                + ServerCalendar.prettyNow()
                + "\u00a78\uff08\u5f53\u524d\u7b2c " + ServerCalendar.currentDay() + " \u65e5\uff09");

        // ---------- 一、货币与分配 ----------
        Map<String, Long> wealth = new LinkedHashMap<>();
        long totalPlayer = 0L;
        try {
            long[] wallet = readWallets(wealth);
            totalPlayer = wallet[0];
        } catch (Throwable ignored) {
            // ignore
        }
        long totalBots = 0L;
        Map<String, Long> botMoney = new LinkedHashMap<>();
        try {
            for (String[] row : readBotLedger()) {
                long m = Long.parseLong(row[1]);
                botMoney.put(row[0], m);
                totalBots += m;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // 兜底：名册里的机器人（含航运公司/罗马国等系统账户）也要列出来
        try {
            for (String bn : com.tangtu.kjbb.BotAccounts.all()) {
                if (bn == null || bn.isEmpty() || botMoney.containsKey(bn)) {
                    continue;
                }
                long bm = com.tangtu.kjbb.BotLedger.money(bn);
                botMoney.put(bn, bm);
                totalBots += bm;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // 兜底：配置里的机器人即使账本里没有，也要列出来（显示 0），
        // 避免「某个机器人凭空消失」
        try {
            for (var b : BotConfig.get().bots) {
                if (b == null || b.name == null || b.name.isEmpty()) {
                    continue;
                }
                if (!botMoney.containsKey(b.name)) {
                    long m2 = BotLedger.money(b.name);
                    botMoney.put(b.name, m2);
                    totalBots += m2;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        long total = totalPlayer + totalBots;
        int players = wealth.size();
        out.add("");
        out.add("\u00a76\u3010\u4e00\u3011\u8d27\u5e01\u4e0e\u5206\u914d");
        out.add("\u00a77\u8d27\u5e01\u603b\u91cf\uff1a\u00a7f" + fmt(total) + " \u5510\u5154\u5e01");
        out.add("\u00a77  \u73a9\u5bb6\u6301\u6709\uff1a\u00a7f" + fmt(totalPlayer)
                + "\u00a77\uff08" + players + " \u4eba\u5747 " + fmt(players == 0 ? 0 : totalPlayer / players)
                + "\uff09");
        out.add("\u00a77  \u673a\u5668\u4eba\uff1a\u00a7f" + fmt(totalBots));
        for (var e : botMoney.entrySet()) {
            out.add("\u00a78    \u00b7 \u00a77" + e.getKey() + " \u00a7f" + fmt(e.getValue()));
        }
        if (players > 0 && players < 5) {
            out.add("\u00a78\uff08\u73a9\u5bb6\u4ec5 " + players
                    + " \u4eba\uff0c\u5206\u914d\u7c7b\u6307\u6807\u4ec5\u4f9b\u53c2\u8003\uff09");
        }
        if (players >= 2) {
            double gini = gini(new ArrayList<>(wealth.values()));
            long top10 = topShare(new ArrayList<>(wealth.values()));
            out.add("\u00a77\u8d22\u5bcc\u96c6\u4e2d\u5ea6\uff08\u57fa\u5c3c\u7cfb\u6570\uff09\uff1a\u00a7f"
                    + String.format(Locale.ROOT, "%.2f", gini)
                    + "\u00a77\uff08 0=\u6781\u5747\u8861 / 1=\u6781\u96c6\u4e2d\uff09");
            out.add("\u00a77\u524d 10% \u73a9\u5bb6\u5360\u8d22\u5bcc\uff1a\u00a7f"
                    + top10 + "%");
        }

        // ---------- 二、价格与通胀 ----------
        out.add("");
        out.add("\u00a76\u3010\u4e8c\u3011\u4ef7\u683c\u4e0e\u901a\u80c0");
        try {
            double idx = priceIndex();
            long based = baselineCount();
            out.add("\u00a77\u57fa\u51c6\u4ef7\u6307\u6570\uff08\u57fa\u671f=100\uff09\uff1a\u00a7f"
                    + String.format(Locale.ROOT, "%.1f", idx));
            double infl = idx - 100.0;
            String trend = infl > 5 ? "\u00a7c\u901a\u80c0" : (infl < -5 ? "\u00a79\u901a\u7f29" : "\u00a7a\u7a33\u5b9a");
            out.add("\u00a77\u76f8\u5bf9\u57fa\u671f\uff1a" + trend + "\u00a77 "
                    + String.format(Locale.ROOT, "%+.1f%%", infl)
                    + "\u00a78\uff08\u57fa\u671f\u5f55\u5165 " + based + " \u79cd\u7269\u54c1\uff09");
        } catch (Throwable ignored) {
            out.add("\u00a78\uff08\u4ef7\u683c\u6570\u636e\u4e0d\u8db3\uff09");
        }

        // ---------- 三、交易活跃度 ----------
        out.add("");
        out.add("\u00a76\u3010\u4e09\u3011\u4ea4\u6613\u6d3b\u8dc3\u5ea6\uff0824 \u5c0f\u65f6\uff09");
        try {
            double[] t = trades24h();
            out.add("\u00a77\u6210\u4ea4\u7b14\u6570\uff1a\u00a7f" + (long) t[0]
                    + "\u00a77  \u6210\u4ea4\u989d\uff1a\u00a7f" + fmt((long) t[1]));
            out.add("\u00a77\u6536\u5230\u7a0e\u6b3e\uff1a\u00a7f" + fmt((long) t[2])
                    + "\u00a77  \u7a0e\u8d1f\u7387\uff1a\u00a7f"
                    + String.format(Locale.ROOT, "%.2f%%",
                    t[1] <= 0 ? 0.0 : t[2] * 100.0 / t[1]));
            double vel = total <= 0 ? 0.0 : (t[1] * 100.0 / total);
            out.add("\u00a77\u8d27\u5e01\u6d41\u901a\u901f\u5ea6\uff1a\u00a7f"
                    + String.format(Locale.ROOT, "%.1f%%", vel)
                    + "\u00a78\uff08\u6bcf\u65e5\u6210\u4ea4\u989d \u00f7 \u8d27\u5e01\u603b\u91cf\uff09");
        } catch (Throwable ignored) {
            out.add("\u00a78\uff08\u6d41\u6c34\u6570\u636e\u4e0d\u8db3\uff09");
        }

        // ---------- 四、公共部门 ----------
        out.add("");
        out.add("\u00a76\u3010\u56db\u3011\u516c\u5171\u90e8\u95e8\uff08\u5510\u5154\u56fd\uff09");
        try {
            String citizen = MarketBots.citizenName();
            long pop = MarketBots.citizenPopulation();
            long pool = BotLedger.money(citizen);
            long stockValue = stockValue(citizen);
            var cfg = BotConfig.get();
            long warnAt = (long) (cfg.minMoney * Math.max(1.0, cfg.citizenCrisisWarnRatio));
            long bankAt = (long) (cfg.minMoney * Math.max(0.05, cfg.citizenBankruptRatio));
            out.add("\u00a77\u4eba\u53e3\uff1a\u00a7f" + pop + "\u00a77  \u56fd\u5e93\uff1a\u00a7f" + fmt(pool)
                    + "\u00a77  \u5e93\u5b58\u4f30\u503c\uff1a\u00a7f" + fmt(stockValue));
            String state = pool >= warnAt ? "\u00a7a\u5065\u5eb7"
                    : (pool >= bankAt ? "\u00a7e\u8b66\u6212" : "\u00a7c\u6fd2\u4e34\u7834\u4ea7");
            out.add("\u00a77\u8d22\u653f\u72b6\u6001\uff1a" + state + "\u00a77\uff08\u8b66\u6212\u7ebf "
                    + fmt(warnAt) + " / \u7834\u4ea7\u7ebf " + fmt(bankAt) + "\uff09");
            out.add("\u00a77\u51b7\u51bb\u671f\uff1a\u00a7f"
                    + (MarketBots.citizenFrozen() ? "\u00a7c\u662f" : "\u00a7a\u5426"));
        } catch (Throwable ignored) {
            out.add("\u00a78\uff08\u56fd\u6c11\u6570\u636e\u4e0d\u8db3\uff09");
        }

        // ---------- 五、市场深度与风险 ----------
        out.add("");
        out.add("\u00a76\u3010\u4e94\u3011\u5e02\u573a\u6df1\u5ea6\u4e0e\u98ce\u9669");
        try {
            var offers = OfferBook.open();
            var orders = OrderBook.byStatus(OrderBook.PENDING);
            long offerValue = 0L;
            for (var o : offers) {
                offerValue += o.price * o.count;
            }
            long orderValue = 0L;
            for (var o : orders) {
                orderValue += o.unit * o.count;
            }
            out.add("\u00a77\u5728\u552e\u5356\u5355\uff1a\u00a7f" + offers.size()
                    + "\u00a77\uff08\u4f30\u503c " + fmt(offerValue) + "\uff09"
                    + "   \u00a77\u5f85\u6210\u4ea4\u4e70\u5355\uff1a\u00a7f" + orders.size()
                    + "\u00a77\uff08" + fmt(orderValue) + "\uff09");
            // 风险提示
            List<String> risks = new ArrayList<>();
            if (total > 0 && totalBots * 100L / Math.max(1L, total) > 60) {
                risks.add("\u673a\u5668\u4eba\u5360\u8d27\u5e01\u603b\u91cf\u8fc7\u9ad8");
            }
            if (offers.isEmpty()) {
                risks.add("\u5356\u5355\u4e3a\u7a7a\uff08\u4f9b\u7ed9\u4e0d\u8db3\uff09");
            }
            if (orders.isEmpty()) {
                risks.add("\u4e70\u5355\u4e3a\u7a7a\uff08\u9700\u6c42\u4e0d\u8db3\uff09");
            }
            double idx = priceIndex();
            if (idx > 130) {
                risks.add("\u4ef7\u683c\u6307\u6570\u504f\u9ad8\uff0c\u53ef\u80fd\u901a\u80c0");
            }
            if (idx < 70) {
                risks.add("\u4ef7\u683c\u6307\u6570\u504f\u4f4e\uff0c\u53ef\u80fd\u901a\u7f29");
            }
            if (risks.isEmpty()) {
                out.add("\u00a7a\u98ce\u9669\u63d0\u793a\uff1a\u672a\u53d1\u73b0\u660e\u663e\u5f02\u5e38");
            } else {
                out.add("\u00a7e\u98ce\u9669\u63d0\u793a\uff1a");
                for (String r : risks) {
                    out.add("\u00a7c  \u00b7 " + r);
                }
            }
        } catch (Throwable ignored) {
            out.add("\u00a78\uff08\u5e02\u573a\u6570\u636e\u4e0d\u8db3\uff09");
        }

        out.add("");
        out.add("\u00a78\u6307\u6807\u8bf4\u660e\uff1a");
        out.add("\u00a78  \u57fa\u5c3c\u7cfb\u6570 0=\u4eba\u4eba\u5e73\u7b49\uff0c1=\u4e00\u4eba\u72ec\u5360\uff1b"
                + "\u5e38\u89c1\u56fd\u5bb6\u5728 0.3~0.5\uff0c\u8d85\u8fc7 0.6 \u5c5e\u4e8e\u9ad8\u5ea6\u4e0d\u5e73\u7b49\u3002");
        out.add("\u00a78  \u57fa\u51c6\u4ef7\u6307\u6570\uff1a100=\u57fa\u671f\u6c34\u5e73\uff0c\u9ad8\u4e8e 105 \u4e3a\u901a\u80c0\uff0c\u4f4e\u4e8e 95 \u4e3a\u901a\u7f29\u3002");
        out.add("\u00a78  \u7a0e\u8d1f\u7387\uff1a\u7a0e\u6536 \u00f7 \u6210\u4ea4\u989d\uff0c\u8bbe\u5b9a\u4e3a 1%\u3002");
        out.add("\u00a78  \u6d41\u901a\u901f\u5ea6\uff1a\u6bcf\u65e5\u6210\u4ea4\u989d \u00f7 \u8d27\u5e01\u603b\u91cf\uff0c\u8d8a\u9ad8\u7ecf\u6d4e\u8d8a\u6d3b\u8dc3\u3002");
        return out;
    }

    /**
     * 统计局面板用的结构化数据：每行 {类型, 左, 右}
     *   m = 页眉信息（日期）、h = 小标题、r = 数值行
     * 与 {@link #build()} 同源，只是把「给人看的段落」压成「一行一个指标」。
     */
    public static List<String[]> stats() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"m", "\u5510\u5154\u56fd " + ServerCalendar.prettyNow(),
                new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date())});

        Map<String, Long> wealth = new LinkedHashMap<>();
        long totalPlayer = 0L;
        try {
            totalPlayer = readWallets(wealth)[0];
        } catch (Throwable ignored) {
            // ignore
        }
        long totalBots = 0L;
        try {
            for (String[] row : readBotLedger()) {
                totalBots += Long.parseLong(row[1]);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        long total = totalPlayer + totalBots;

        out.add(new String[]{"h", "\u8d27\u5e01\u4e0e\u5206\u914d", ""});
        out.add(new String[]{"r", "\u8d27\u5e01\u603b\u91cf", fmt(total)});
        out.add(new String[]{"r", "\u73a9\u5bb6\u6301\u6709",
                fmt(totalPlayer) + "\uff08" + pct(totalPlayer, total) + "%\uff09"});
        out.add(new String[]{"r", "\u673a\u5668\u4eba\u6301\u6709", fmt(totalBots)});
        if (wealth.size() >= 2) {
            List<Long> vals = new ArrayList<>(wealth.values());
            out.add(new String[]{"r", "\u57fa\u5c3c\u7cfb\u6570",
                    String.format(Locale.ROOT, "%.2f", gini(vals))
                            + "\u3000\u524d10% " + topShare(vals) + "%"});
        } else {
            out.add(new String[]{"r", "\u57fa\u5c3c\u7cfb\u6570", "\u73a9\u5bb6\u4e0d\u8db3 2 \u4eba"});
        }

        out.add(new String[]{"h", "\u4ef7\u683c", ""});
        try {
            double idx = priceIndex();
            double infl = idx - 100.0;
            out.add(new String[]{"r", "\u57fa\u51c6\u4ef7\u6307\u6570", String.format(Locale.ROOT, "%.1f", idx)});
            out.add(new String[]{"r", "\u76f8\u5bf9\u57fa\u671f",
                    String.format(Locale.ROOT, "%+.1f%%", infl) + "\u3000"
                            + (infl > 5 ? "\u901a\u80c0" : infl < -5 ? "\u901a\u7f29" : "\u7a33\u5b9a")});
        } catch (Throwable ignored) {
            out.add(new String[]{"r", "\u57fa\u51c6\u4ef7\u6307\u6570", "\u6570\u636e\u4e0d\u8db3"});
        }

        out.add(new String[]{"h", "\u4ea4\u6613\uff0824 \u5c0f\u65f6\uff09", ""});
        try {
            double[] t = trades24hSplit(wealth.keySet());
            out.add(new String[]{"r", "\u6210\u4ea4\u7b14\u6570", (long) t[0] + " \u7b14"});
            out.add(new String[]{"r", "\u6210\u4ea4\u989d", fmt((long) t[1])});
            out.add(new String[]{"r", "\u7a0e\u8d1f\u7387",
                    String.format(Locale.ROOT, "%.2f%%", t[1] <= 0 ? 0.0 : t[2] * 100.0 / t[1])});
            out.add(new String[]{"r", "\u6d41\u901a\u901f\u5ea6",
                    String.format(Locale.ROOT, "%.1f%%/\u65e5", total <= 0 ? 0.0 : t[1] * 100.0 / total)});
            out.add(new String[]{"r", "\u73a9\u5bb6\u5bf9\u624b\u65b9", (long) t[3] + " \u7b14\uff08"
                    + (t[0] <= 0 ? "\u2014" : (long) (t[3] * 100 / t[0]) + "%") + "\uff09"});
        } catch (Throwable ignored) {
            out.add(new String[]{"r", "\u4ea4\u6613\u6570\u636e", "\u4e0d\u8db3"});
        }

        out.add(new String[]{"h", "\u56fd\u5e93\u4e0e\u5e02\u573a", ""});
        try {
            String citizen = MarketBots.citizenName();
            out.add(new String[]{"r", "\u4eba\u53e3", String.valueOf(MarketBots.citizenPopulation())});
            out.add(new String[]{"r", "\u56fd\u5e93\u73b0\u91d1", fmt(BotLedger.money(citizen))});
            out.add(new String[]{"r", "\u5e93\u5b58\u4f30\u503c", fmt(stockValue(citizen))});
        } catch (Throwable ignored) {
            out.add(new String[]{"r", "\u56fd\u5e93", "\u6570\u636e\u4e0d\u8db3"});
        }
        try {
            int offers = OfferBook.open().size();
            int orders = OrderBook.byStatus(OrderBook.PENDING).size();
            out.add(new String[]{"r", "\u5728\u552e / \u4e70\u5355", offers + " \u5355 / " + orders + " \u5355"});
        } catch (Throwable ignored) {
            out.add(new String[]{"r", "\u5728\u552e / \u4e70\u5355", "\u2014"});
        }
        return out;
    }

    private static String pct(long part, long whole) {
        if (whole <= 0L) {
            return "0";
        }
        return String.valueOf(part * 100L / whole);
    }

    // ------------------------------------------------------------------ 数据源

    private static long[] readWallets(Map<String, Long> out) throws IOException {
        Path p = Path.of("config", "kjbb", "wallet.txt");
        long total = 0L;
        if (!Files.exists(p)) {
            return new long[]{0L};
        }
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\\|");
            if (f.length < 2) {
                continue;
            }
            try {
                long m = Long.parseLong(f[1].trim());
                out.put(f[0].trim(), m);
                total += m;
            } catch (Throwable ignored) {
                // skip
            }
        }
        return new long[]{total};
    }

    private static List<String[]> readBotLedger() throws IOException {
        List<String[]> out = new ArrayList<>();
        Path p = Path.of("config", "kjbb", "bot_ledger.txt");
        if (!Files.exists(p)) {
            return out;
        }
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\\|", 3);
            if (f.length < 2) {
                continue;
            }
            out.add(new String[]{f[0].trim(), f[1].trim()});
        }
        return out;
    }

    /** 非交易类流水（不计入成交额） */
    private static final java.util.Set<String> NON_TRADE = java.util.Set.of(
            "\u4e0a\u67b6", "\u5de5\u8d44", "\u589e\u957f", "\u635f\u8017",
            "\u7ecf\u8425\u6d88\u8017", "\u8865\u52a9", "\u7a0e\u6536",
            "\u4eba\u53e3\u6d41\u5931");

    /** 24 小时内：笔数、成交额、税收（统计局面板还要看玩家对手方占比，见 Split 版） */
    private static double[] trades24h() throws IOException {
        double[] r = trades24hSplit(java.util.Set.of());
        return new double[]{r[0], r[1], r[2]};
    }

    /**
     * 24 小时内：笔数、成交额、税收、对手方是玩家的笔数
     * @param players 玩家名单（wallet.txt 里的名字），用来区分「玩家对玩家」与「机器人在自娱自乐」
     */
    private static double[] trades24hSplit(java.util.Set<String> players) throws IOException {
        Path p = Path.of("config", "kjbb", "bot_trades.txt");
        if (!Files.exists(p)) {
            return new double[]{0, 0, 0};
        }
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        long cutoff = ServerClock.now() - 24L * 3600_000L;
        double count = 0;
        double volume = 0;
        double tax = 0;
        double playerTrades = 0;
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT);
        f.setLenient(true);
        SimpleDateFormat f2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        f2.setLenient(true);
        int from = Math.max(0, lines.size() - 5000);
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] p2 = line.split("\\|", -1);
            // 时间|from|to|item|count|unit|total|reason  （至少 7 段，税收行 8 段）
            if (p2.length < 7) {
                continue;
            }
            long ts = 0L;
            try {
                ts = f.parse(p2[0].trim()).getTime();
            } catch (Throwable ignored) {
                try {
                    ts = f2.parse(p2[0].trim()).getTime();
                } catch (Throwable ignored2) {
                    continue;
                }
            }
            if (ts <= 0L) {
                continue;
            }
            // 补年份：MM-dd 解析默认 1970 年，这里换成当前年
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(ts);
            java.util.Calendar nowCal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.YEAR, nowCal.get(java.util.Calendar.YEAR));
            ts = cal.getTimeInMillis();
            if (ts < cutoff) {
                continue;
            }
            // 成交额：优先用 total（第 7 段），失败用 unit×count
            long amount = 0L;
            try {
                amount = Long.parseLong(p2[6].trim());
            } catch (Throwable ignored) {
                try {
                    amount = Long.parseLong(p2[4].trim()) * Long.parseLong(p2[5].trim());
                } catch (Throwable ignored2) {
                    amount = 0L;
                }
            }
            String reason = p2.length >= 8 ? p2[7].trim() : p2[p2.length - 1].trim();
            if ("\u7a0e\u6536".equals(reason)) {
                tax += Math.abs(amount);
                continue;
            }
            if (NON_TRADE.contains(reason)) {
                continue;            // 上架/工资/损耗等不算成交
            }
            count++;
            volume += Math.abs(amount);
            if (!players.isEmpty()
                    && (players.contains(p2[1].trim()) || players.contains(p2[2].trim()))) {
                playerTrades++;
            }
        }
        return new double[]{count, volume, tax, playerTrades};
    }

    /** 公共部门库存估值 */
    private static long stockValue(String citizen) {
        long v = 0L;
        try {
            for (var e : BotLedger.stockMap(citizen).entrySet()) {
                long unit = ShopPrices.price(e.getKey());
                if (unit <= 0L) {
                    unit = 1L;
                }
                v += unit * e.getValue();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return v;
    }

    // ------------------------------------------------------------------ 价格指数

    private static Map<String, Long> baseline() {
        Map<String, Long> m = new LinkedHashMap<>();
        try {
            Path p = baselineFile();
            if (!Files.exists(p)) {
                return m;
            }
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|");
                if (f.length < 2) {
                    continue;
                }
                m.put(f[0], Long.parseLong(f[1].trim()));
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return m;
    }

    /** 首次运行记录基线，之后用于算指数 */
    public static synchronized void ensureBaseline() {
        try {
            Path p = baselineFile();
            if (Files.exists(p)) {
                return;
            }
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            StringBuilder sb = new StringBuilder("# item|price\n");
            for (String id : ShopPrices.items()) {
                long price = ShopPrices.price(id);
                if (price > 0L) {
                    sb.append(id).append('|').append(price).append('\n');
                }
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[TangtuEconomical] \u5df2\u8bb0\u5f55\u7ecf\u6d4e\u57fa\u51c6\u4ef7\u5feb\u7167");
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static long baselineCount() {
        return baseline().size();
    }

    /** 加权价格指数：基准价 vs 基线（基期=100） */
    private static double priceIndex() {
        Map<String, Long> base = baseline();
        if (base.isEmpty()) {
            ensureBaseline();
            return 100.0;
        }
        double sumRatio = 0.0;
        int n = 0;
        for (var e : base.entrySet()) {
            long now = ShopPrices.price(e.getKey());
            long was = e.getValue();
            if (now <= 0L || was <= 0L) {
                continue;
            }
            sumRatio += now / (double) was;
            n++;
        }
        return n == 0 ? 100.0 : sumRatio * 100.0 / n;
    }

    // ------------------------------------------------------------------ 统计

    private static double gini(List<Long> values) {
        if (values.size() < 2) {
            return 0.0;
        }
        values.sort(Long::compareTo);
        int n = values.size();
        long sum = 0L;
        for (long v : values) {
            sum += v;
        }
        if (sum <= 0L) {
            return 0.0;
        }
        double cum = 0.0;
        for (int i = 0; i < n; i++) {
            cum += (i + 1.0) * values.get(i);
        }
        double g = (2.0 * cum) / (n * (double) sum) - (n + 1.0) / n;
        return Math.max(0.0, Math.min(1.0, g));
    }

    private static long topShare(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        values.sort((a, b) -> Long.compare(b, a));
        long sum = 0L;
        for (long v : values) {
            sum += v;
        }
        if (sum <= 0L) {
            return 0;
        }
        int k = Math.max(1, values.size() / 10);
        long top = 0L;
        for (int i = 0; i < k; i++) {
            top += values.get(i);
        }
        return top * 100L / sum;
    }

    private static String fmt(long v) {
        if (Math.abs(v) >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2fM", v / 1_000_000.0);
        }
        if (Math.abs(v) >= 10_000L) {
            return String.format(Locale.ROOT, "%.1fK", v / 1000.0);
        }
        return String.valueOf(v);
    }

    /** 存成文件，方便外部阅读 */
    public static void save(List<String> lines) {
        try {
            Path p = Path.of("config", "kjbb", "econ_report.txt");
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            StringBuilder sb = new StringBuilder();
            for (String s : lines) {
                sb.append(s.replaceAll("\u00a7.", "")).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
