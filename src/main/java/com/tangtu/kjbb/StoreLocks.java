package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 仓单锁：被开过仓单的容器会锁住，只有仓单持有人能打开。
 *
 * config/kjbb/store_locks.txt
 *   x|y|z|owner|locked(0/1)
 */
public final class StoreLocks {

    public record Row(int x, int y, int z, String owner, boolean locked, boolean bearer) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static boolean loaded = false;

    private StoreLocks() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("store_locks.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_store_locks.txt");
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
                            "1".equals(f[4].trim()),
                            f.length > 5 && "1".equals(f[5].trim())));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读仓单锁失败: " + t);
        }
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder("# x|y|z|owner|locked|bearer\n");
        for (Row r : ROWS) {
            sb.append(r.x()).append('|').append(r.y()).append('|').append(r.z()).append('|')
                    .append(r.owner()).append('|').append(r.locked() ? "1" : "0").append('|')
                    .append(r.bearer() ? "1" : "0").append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写仓单锁失败: " + t);
        }
    }

    public static synchronized Row get(BlockPos pos) {
        load();
        for (Row r : ROWS) {
            if (r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ()) {
                return r;
            }
        }
        return null;
    }

    public static synchronized void lock(BlockPos pos, String owner) {
        lock(pos, owner, false);
    }

    public static synchronized void lock(BlockPos pos, String owner, boolean bearer) {
        load();
        ROWS.removeIf(r -> r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ());
        ROWS.add(new Row(pos.getX(), pos.getY(), pos.getZ(), owner, true, bearer));
        save();
        System.out.println("[TangtuEconomical] 锁定容器 " + pos + " owner=" + owner
                + " bearer=" + bearer);
    }

    /** 某人名下所有锁定的容器 */
    public static synchronized java.util.List<BlockPos> lockedBy(String player) {
        load();
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (Row r : ROWS) {
            if (r.locked() && r.owner() != null && r.owner().equalsIgnoreCase(player)) {
                out.add(new BlockPos(r.x(), r.y(), r.z()));
            }
        }
        return out;
    }

    public static synchronized void unlock(BlockPos pos) {
        load();
        if (ROWS.removeIf(r -> r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ())) {
            save();
        }
    }

    public static synchronized void transfer(BlockPos pos, String newOwner) {
        load();
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ()) {
                ROWS.set(i, new Row(r.x(), r.y(), r.z(), newOwner, true, r.bearer()));
                save();
                return;
            }
        }
    }

    /** 该玩家能不能打开这个容器（holdsReceipt = 背包里有对应仓单） */
    public static boolean canOpen(BlockPos pos, String player, boolean holdsReceipt) {
        Row r = get(pos);
        if (r == null || !r.locked()) {
            return true;
        }
        if (holdsReceipt) {
            return true;                              // 持有该容器的任一有效仓单 → 可开
        }
        if (r.bearer()) {
            return false;                             // 不记名但手上没票 → 不开
        }
        return r.owner() != null && r.owner().equalsIgnoreCase(player);   // 记名：票上的人可开
    }

    public static boolean canOpen(BlockPos pos, String player) {
        return canOpen(pos, player, false);
    }

    /** 玩家背包里是否有一张对应这个容器的仓单 */
    public static boolean playerHoldsReceipt(net.minecraft.server.level.ServerPlayer sp,
                                             BlockPos pos) {
        String want = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var st = inv.getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            if (st.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
                continue;
            }
            if (com.tangtu.kjbb.item.StoreReceiptItem.isVoid(st)) {
                continue;
            }
            String p = com.tangtu.kjbb.item.StoreReceiptItem.coordText(st);
            String dim = com.tangtu.kjbb.item.StoreReceiptItem.dimText(st);
            String cur = sp.serverLevel().dimension().location().toString();
            boolean dimOk = dim == null || dim.isEmpty()
                    ? "minecraft:overworld".equals(cur)      // 老仓单：只认主世界
                    : dim.equals(cur);
            if (dimOk && want.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** 玩家背包里对应这个容器的仓单（返回数量 0 表示没有） */
    public static int countReceipts(net.minecraft.server.level.ServerPlayer sp, BlockPos pos) {
        String want = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        int n = 0;
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var st = inv.getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            if (st.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
                continue;
            }
            if (want.equals(com.tangtu.kjbb.item.StoreReceiptItem.posText(st))) {
                n++;
            }
        }
        return n;
    }

    public static synchronized String dump(String player) {
        load();
        StringBuilder sb = new StringBuilder();
        for (Row r : ROWS) {
            if (player != null && !r.owner().equalsIgnoreCase(player)) {
                continue;
            }
            sb.append(r.x()).append(", ").append(r.y()).append(", ").append(r.z())
                    .append("  → ").append(r.owner()).append('\n');
        }
        return sb.length() == 0 ? "（没有锁定的容器）" : sb.toString();
    }
}
