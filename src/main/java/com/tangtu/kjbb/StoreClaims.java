package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 仓单占用表：config/kjbb/store_claims.txt
 *
 * 格式：x|y|z|itemId|count
 *
 * 仓单不搬货（货留在箱子/保险库里），所以用这张表记录
 * 「某个容器里的某物品已经被开了多少张仓单」，防止重复开单。
 */
public final class StoreClaims {

    public record Row(int x, int y, int z, String itemId, int count) {
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static boolean loaded = false;

    private StoreClaims() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("store_claims.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_store_claims.txt");
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
                if (f.length < 5) {
                    continue;
                }
                try {
                    ROWS.add(new Row(Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()),
                            Integer.parseInt(f[2].trim()), f[3].trim(),
                            Integer.parseInt(f[4].trim())));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读仓单占用表失败: " + t);
        }
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder("# x|y|z|itemId|count\n");
        for (Row r : ROWS) {
            sb.append(r.x()).append('|').append(r.y()).append('|').append(r.z()).append('|')
                    .append(r.itemId()).append('|').append(r.count()).append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写仓单占用表失败: " + t);
        }
    }

    /** 该容器该物品已开单数量 */
    public static synchronized int claimed(BlockPos pos, String itemId) {
        load();
        int sum = 0;
        for (Row r : ROWS) {
            if (r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ()
                    && r.itemId().equalsIgnoreCase(itemId)) {
                sum += r.count();
            }
        }
        return sum;
    }

    public static synchronized void add(BlockPos pos, String itemId, int count) {
        load();
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ()
                    && r.itemId().equalsIgnoreCase(itemId)) {
                ROWS.set(i, new Row(r.x(), r.y(), r.z(), r.itemId(), r.count() + count));
                save();
                return;
            }
        }
        ROWS.add(new Row(pos.getX(), pos.getY(), pos.getZ(), itemId, count));
        save();
    }

    public static synchronized void release(BlockPos pos, String itemId, int count) {
        load();
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (r.x() != pos.getX() || r.y() != pos.getY() || r.z() != pos.getZ()
                    || !r.itemId().equalsIgnoreCase(itemId)) {
                continue;
            }
            int left = r.count() - count;
            if (left <= 0) {
                ROWS.remove(i);
            } else {
                ROWS.set(i, new Row(r.x(), r.y(), r.z(), r.itemId(), left));
            }
            save();
            return;
        }
    }

    /** 该容器的未兑付明细（itemId → 数量） */
    public static synchronized java.util.Map<String, Integer> of(BlockPos pos) {
        load();
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (Row r : ROWS) {
            if (r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ()) {
                m.merge(r.itemId(), r.count(), Integer::sum);
            }
        }
        return m;
    }

    /** 该容器所有物品的未兑付总数（0 = 没有任何未兑付仓单） */
    public static synchronized int claimedTotal(BlockPos pos) {
        load();
        int sum = 0;
        for (Row r : ROWS) {
            if (r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ()) {
                sum += r.count();
            }
        }
        return sum;
    }

    /** 清掉该容器的全部占用（解封 / 作废时用） */
    public static synchronized void clear(BlockPos pos) {
        load();
        boolean any = ROWS.removeIf(r -> r.x() == pos.getX() && r.y() == pos.getY()
                && r.z() == pos.getZ());
        if (any) {
            save();
        }
    }

    /** 容器里可开单的数量 = 现有数量 - 已开单数量 */
    public static int available(BlockPos pos, String itemId, int inContainer) {
        return Math.max(0, inContainer - claimed(pos, itemId));
    }
}