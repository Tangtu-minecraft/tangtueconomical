package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 唐兔国国民统计报表（短行版，适配纸质界面宽度）
 */
public final class CitizenReport {

    private CitizenReport() {
    }

    public static String name() {
        String n = MarketBots.citizenName();
        return n == null ? "\u5510\u5154\u56fd\u56fd\u6c11" : n;
    }

    private static String num(long v) {
        boolean neg = v < 0;
        String s = Long.toString(Math.abs(v));
        StringBuilder sb = new StringBuilder();
        int c = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            sb.append(s.charAt(i));
            if (++c % 3 == 0 && i > 0) {
                sb.append(',');
            }
        }
        return (neg ? "-" : "") + sb.reverse();
    }

    private static String shortId(String id) {
        int c = id.indexOf(':');
        return c >= 0 ? id.substring(c + 1) : id;
    }

    public static List<String> build() {
        List<String> out = new ArrayList<>();
        String bot = name();
        var cfg = BotConfig.get();
        long pool = BotLedger.money(bot);
        int[] layers = MarketBots.citizenLayers();
        long population = com.tangtu.kjbb.MarketBots.citizenPopulation();

        out.add("\u6c60 " + num(pool) + " \u5510\u5154\u5e01");
        out.add("\u4eba " + population + "\uff08\u6e29 " + layers[0] + " \u5c0f " + layers[1]
                + " \u5bcc " + layers[2] + "\uff09");

        Map<String, Integer> stock = BotLedger.stockMap(bot);
        int totalItems = 0;
        long foodPoints = 0;
        for (var e : stock.entrySet()) {
            totalItems += e.getValue();
            if (FoodTable.table().containsKey(e.getKey()) || FoodValue.of(e.getKey()) > 0) {
                foodPoints += (long) e.getValue() * Math.max(1, FoodValue.of(e.getKey()));
            }
        }
        long dailyNeed = Math.max(1L, population * cfg.citizenFoodPerDay);
        out.add("\u5b58 " + stock.size() + " \u79cd\uff0c" + num(totalItems) + " \u4ef6");
        out.add("\u652f\u6491 " + (foodPoints / dailyNeed) + " \u5929\uff08\u4f59 "
                + num(foodPoints) + " \u70b9\uff09");
        out.add("\u9700 " + num(dailyNeed) + " \u70b9/\u5929");

        int pending = 0;
        long pendingValue = 0;
        try {
            for (var r : OrderBook.byStatus(OrderBook.PENDING)) {
                if (bot.equals(r.buyer)) {
                    pending++;
                    pendingValue += r.total;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        out.add("\u5355 " + pending + " \u6761\uff0c\u5360 " + num(pendingValue));

        // 流水
        long income = 0, expense = 0;
        Map<String, long[]> byKind = new LinkedHashMap<>();
        Path f = FMLPaths.CONFIGDIR.get().resolve("kjbb/bot_trades.txt");
        try {
            if (Files.exists(f)) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                int from = Math.max(0, lines.size() - 8000);
                for (int i = from; i < lines.size(); i++) {
                    String[] p = lines.get(i).split("\\|");
                    if (p.length < 8) {
                        continue;
                    }
                    String buyer = p[1], seller = p[2], kind = p[7];
                    long total;
                    try {
                        total = Long.parseLong(p[6]);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (bot.equals(seller)) {
                        income += total;
                        byKind.computeIfAbsent(kind, k -> new long[2])[0] += total;
                    }
                    if (bot.equals(buyer)) {
                        expense += total;
                        byKind.computeIfAbsent(kind, k -> new long[2])[1] += total;
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        out.add("\u6536 +" + num(income) + "\u3000\u652f -" + num(expense));
        out.add("\u51c0 " + num(income - expense));
        out.add("--------------------");

        for (var e : byKind.entrySet()) {
            long[] v = e.getValue();
            out.add(e.getKey() + " +" + num(v[0]) + " / -" + num(v[1]));
            if (out.size() > 22) {
                break;
            }
        }
        return out;
    }

    /** 库存前 N 项，编码成 "id|count,id|count" 给客户端画图标 */
    public static String topItems(int n) {
        var stock = BotLedger.stockMap(name());
        if (stock.isEmpty()) {
            return "";
        }
        var list = new ArrayList<>(stock.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            var e = list.get(i);
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('|').append(e.getValue());
        }
        return sb.toString();
    }

    public static void writeFile(List<String> lines) {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve("kjbb/citizen_report.txt");
            Files.createDirectories(p.getParent());
            StringBuilder sb = new StringBuilder();
            for (String s : lines) {
                sb.append(s.replaceAll("\u00a7.", "")).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 国民最近消费最多的几种物品（按流水统计买入数量） */
    public static java.util.List<String> topConsumed(int n) {
        java.util.Map<String, Long> sum = new java.util.LinkedHashMap<>();
        try {
            var p = java.nio.file.Path.of("config", "kjbb", "bot_trades.txt");
            if (!java.nio.file.Files.exists(p)) {
                return java.util.List.of();
            }
            var lines = java.nio.file.Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8);
            String citizen = name();
            int from = Math.max(0, lines.size() - 4000);
            for (int i = from; i < lines.size(); i++) {
                String line = lines.get(i);
                String[] f = line.split("\\|");
                if (f.length < 5) {
                    continue;
                }
                // 时间|from|to|item|count|unit|total|reason
                if (f.length < 4 || !citizen.equals(f[2])) {
                    continue;
                }
                String item = f[3];
                if (item == null || !item.contains(":")) {
                    continue;                 // 经营消耗 / 人口增长 之类不算
                }
                long c;
                try {
                    c = Long.parseLong(f[4].trim());
                } catch (Throwable tt) {
                    continue;
                }
                if (c <= 0) {
                    continue;
                }
                sum.merge(item, c, Long::sum);
            }
        } catch (Throwable ignored) {
            return java.util.List.of();
        }
        java.util.List<java.util.Map.Entry<String, Long>> list =
                new java.util.ArrayList<>(sum.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < list.size() && i < n; i++) {
            var e = list.get(i);
            out.add(shortId(e.getKey()) + " \u00d7" + num(e.getValue()));
        }
        return out;
    }

    /** 国民消费过的物品（去重，按购买量排序） */
    public static java.util.List<String> consumedIds() {
        java.util.Map<String, Long> sum = new java.util.LinkedHashMap<>();
        try {
            var p = java.nio.file.Path.of("config", "kjbb", "bot_trades.txt");
            if (!java.nio.file.Files.exists(p)) {
                return java.util.List.of();
            }
            var lines = java.nio.file.Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8);
            String citizen = name();
            int from = Math.max(0, lines.size() - 4000);
            for (int i = from; i < lines.size(); i++) {
                String[] f = lines.get(i).split("\\|");
                if (f.length < 5 || !citizen.equals(f[2])) {
                    continue;
                }
                String item = f[3];
                if (item == null || !item.contains(":")) {
                    continue;
                }
                long c;
                try {
                    c = Long.parseLong(f[4].trim());
                } catch (Throwable tt) {
                    continue;
                }
                if (c > 0) {
                    sum.merge(item, c, Long::sum);
                }
            }
        } catch (Throwable ignored) {
            return java.util.List.of();
        }
        var list = new java.util.ArrayList<>(sum.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        var out = new java.util.ArrayList<String>();
        for (var e : list) {
            out.add(e.getKey());
        }
        return out;
    }

    /**
     * 公共界面用的图标串：前 top 个是消费最多的，后面再随机补 extra 个（不重复）
     * 格式："id|count,id|count"
     */
    public static String publicIconData(int top, int extra) {
        var ids = consumedIds();
        if (ids.isEmpty()) {
            return "";
        }
        var stock = BotLedger.stockMap(name());
        var picked = new java.util.ArrayList<String>();
        for (int i = 0; i < Math.min(top, ids.size()); i++) {
            picked.add(ids.get(i));
        }
        // 池子里剩下的随机补几个
        var rest = new java.util.ArrayList<>(ids);
        rest.removeAll(picked);
        java.util.Collections.shuffle(rest, new java.util.Random());
        for (int i = 0; i < extra && i < rest.size(); i++) {
            picked.add(rest.get(i));
        }
        StringBuilder sb = new StringBuilder();
        for (String id : picked) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id).append('|').append(Math.max(1, stock.getOrDefault(id, 1)));
        }
        return sb.toString();
    }
}
