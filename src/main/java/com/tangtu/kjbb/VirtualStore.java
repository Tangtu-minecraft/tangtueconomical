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
 * 交易市场虚拟仓：config/kjbb/virtual_store.txt
 *
 * 格式：owner|itemId|count
 *
 * 被仓单锁定的容器被破坏时，里面的货不会掉地上，
 * 而是原样流进这里，挂在仓单持有人名下。
 */
public final class VirtualStore {

    public record Row(String owner, String itemId, int count) {
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static boolean loaded = false;

    private VirtualStore() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("virtual_store.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_virtual_store.txt");
        }
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        ROWS.clear();
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
                if (f.length < 3) {
                    continue;
                }
                try {
                    ROWS.add(new Row(f[0].trim(), f[1].trim(), Integer.parseInt(f[2].trim())));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读虚拟仓失败: " + t);
        }
    }

    private static synchronized void flushNow() {
        StringBuilder sb = new StringBuilder("# owner|itemId|count\n");
        for (Row r : ROWS) {
            sb.append(r.owner()).append('|').append(r.itemId()).append('|').append(r.count())
                    .append('\n');
        }
        try {
            SafeFile.write(file(), sb.toString());
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写虚拟仓失败: " + t);
        }
    }

    /** 标脏：真正写盘由 DirtySave 每 saveIntervalTicks 批量执行 */
    private static synchronized void save() {
        DirtySave.mark("virtualstore");
    }

    static {
        DirtySave.register("virtualstore", VirtualStore::flushNow);
    }


    public static synchronized void add(String owner, String itemId, int count) {
        load();
        if (count <= 0) {
            return;
        }
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (r.owner().equalsIgnoreCase(owner) && r.itemId().equals(itemId)) {
                ROWS.set(i, new Row(r.owner(), r.itemId(), r.count() + count));
                save();
                return;
            }
        }
        ROWS.add(new Row(owner, itemId, count));
        save();
    }

    public static synchronized int take(String owner, String itemId, int count) {
        load();
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (!r.owner().equalsIgnoreCase(owner) || !r.itemId().equals(itemId)) {
                continue;
            }
            int t = Math.min(count, r.count());
            int left = r.count() - t;
            if (left <= 0) {
                ROWS.remove(i);
            } else {
                ROWS.set(i, new Row(r.owner(), r.itemId(), left));
            }
            save();
            return t;
        }
        return 0;
    }

    public static synchronized Map<String, Integer> of(String owner) {
        load();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Row r : ROWS) {
            if (r.owner().equalsIgnoreCase(owner)) {
                out.merge(r.itemId(), r.count(), Integer::sum);
            }
        }
        return out;
    }

    public static synchronized String dump(String owner) {
        load();
        Map<String, Integer> map = of(owner);
        if (map.isEmpty()) {
            return "（虚拟仓是空的）";
        }
        StringBuilder sb = new StringBuilder();
        for (var e : map.entrySet()) {
            sb.append(com.tangtu.kjbb.item.StoreReceiptItem.niceName(e.getKey()))
                    .append(" ×").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }
}