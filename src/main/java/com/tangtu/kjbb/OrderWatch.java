package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 待收货订单监视表：config/kjbb/watching.txt
 *
 * 格式：escrowId|buyer|itemId|count|address|createdMs
 *
 * K 线界面下单成功后写一条；DeliveryWatcher 定期检查买家的提货点，
 * 看到 Create 包裹就把托管的钱放给卖家。
 */
public final class OrderWatch {

    public record Row(long escrowId, String buyer, String itemId, int count,
                      String address, long createdMs) {
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static boolean loaded = false;

    private OrderWatch() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("watching.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_watching.txt");
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
                if (f.length < 6) {
                    continue;
                }
                try {
                    ROWS.add(new Row(Long.parseLong(f[0].trim()), f[1].trim(), f[2].trim(),
                            Integer.parseInt(f[3].trim()), f[4].trim(),
                            Long.parseLong(f[5].trim())));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读待收货表失败: " + t);
        }
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder("# escrowId|buyer|itemId|count|address|createdMs\n");
        for (Row r : ROWS) {
            sb.append(r.escrowId()).append('|').append(r.buyer()).append('|')
                    .append(r.itemId()).append('|').append(r.count()).append('|')
                    .append(r.address()).append('|').append(r.createdMs()).append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写待收货表失败: " + t);
        }
    }

    public static synchronized void add(long escrowId, String buyer, String itemId,
                                        int count, String address) {
        load();
        ROWS.add(new Row(escrowId, buyer, itemId, count, address, ServerClock.now()));
        save();
    }

    public static synchronized void remove(long escrowId) {
        load();
        if (ROWS.removeIf(r -> r.escrowId() == escrowId)) {
            save();
        }
    }

    public static synchronized List<Row> all() {
        load();
        return new ArrayList<>(ROWS);
    }
}