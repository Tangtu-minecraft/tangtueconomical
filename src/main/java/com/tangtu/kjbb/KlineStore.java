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
 * 自建 K 线存储（不再依赖股市 mod）。
 *
 * config/kjbb/kline.txt
 *   itemId|开始时间(ms)|开|高|低|收|成交量
 *
 * 每个物品最多保留 MAX 根，超过就丢最旧的。
 */
public final class KlineStore {

    /** 每根蜡烛的宽度（毫秒），由 bots.json 的 klinePeriodMs 决定 */
    public static long periodMs() {
        return BotConfig.get().klinePeriodMs;
    }

    /** 最近一根的时间（0 = 没有） */
    public static synchronized long lastStart(String itemId) {
        load();
        java.util.List<Candle> list = DATA.get(itemId);
        return list == null || list.isEmpty() ? 0L : list.get(list.size() - 1).start();
    }

    /** 最近一根的收盘价（0 = 没有） */
    public static synchronized int lastClose(String itemId) {
        load();
        java.util.List<Candle> list = DATA.get(itemId);
        return list == null || list.isEmpty() ? 0 : list.get(list.size() - 1).close();
    }

    /** 所有有历史的物品 */
    public static synchronized java.util.List<String> items() {
        load();
        return new java.util.ArrayList<>(DATA.keySet());
    }
    /** 每个物品最多保留多少根 */
    public static final int MAX = 240;

    public record Candle(String itemId, long start, int open, int high, int low, int close,
                         long volume) {
    }

    private static final Map<String, List<Candle>> DATA = new LinkedHashMap<>();
    private static boolean loaded = false;

    private KlineStore() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("kline.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_kline.txt");
        }
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path p = file();
        if (!Files.exists(p)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line == null || line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length < 7) {
                    continue;
                }
                try {
                    Candle c = new Candle(f[0].trim(), Long.parseLong(f[1].trim()),
                            Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim()),
                            Integer.parseInt(f[4].trim()), Integer.parseInt(f[5].trim()),
                            Long.parseLong(f[6].trim()));
                    DATA.computeIfAbsent(c.itemId(), k -> new ArrayList<>()).add(c);
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读 K 线失败: " + t);
        }
    }

    private static synchronized void flushNow() {
        StringBuilder sb = new StringBuilder("# itemId|start|open|high|low|close|volume\n");
        for (var e : DATA.entrySet()) {
            for (Candle c : e.getValue()) {
                sb.append(c.itemId()).append('|').append(c.start()).append('|')
                        .append(c.open()).append('|').append(c.high()).append('|')
                        .append(c.low()).append('|').append(c.close()).append('|')
                        .append(c.volume()).append('\n');
            }
        }
        try {
            SafeFile.write(file(), sb.toString());
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写 K 线失败: " + t);
        }
    }

    /** 标脏：真正写盘由 DirtySave 每 saveIntervalTicks 批量执行 */
    private static synchronized void save() {
        DirtySave.mark("kline");
    }

    static {
        DirtySave.register("kline", KlineStore::flushNow);
    }


    /** 追加一根蜡烛（同一开始时间会替换） */
    public static synchronized void append(String itemId, long start, int open, int high,
                                           int low, int close, long volume) {
        load();
        List<Candle> list = DATA.computeIfAbsent(itemId, k -> new ArrayList<>());
        Candle c = new Candle(itemId, start, open, high, low, close, volume);
        if (!list.isEmpty() && list.get(list.size() - 1).start() == start) {
            list.set(list.size() - 1, c);
        } else {
            list.add(c);
        }
        while (list.size() > MAX) {
            list.remove(0);
        }
        save();
    }

    /** 最近 n 根（时间正序） */
    public static synchronized List<Candle> recent(String itemId, int n) {
        load();
        List<Candle> out = new ArrayList<>();
        List<Candle> list = DATA.get(itemId);
        if (list == null) {
            return out;
        }
        int from = Math.max(0, list.size() - n);
        for (int i = from; i < list.size(); i++) {
            out.add(list.get(i));
        }
        return out;
    }

    public static synchronized int size(String itemId) {
        load();
        List<Candle> list = DATA.get(itemId);
        return list == null ? 0 : list.size();
    }

    public static synchronized void clear() {
        loaded = true;
        DATA.clear();
        save();
    }
}
