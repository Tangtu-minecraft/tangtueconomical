package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 提货点登记表：config/kjbb/ports.txt
 *
 * 格式：owner|地址|x|y|z|类型|默认(0/1)
 *
 * 提货点【只由 NPC / 管理员绑定】：
 *   /billport bind <玩家> <地址>
 *   /billport default <玩家> <地址>
 *
 * 一个玩家可以有多个提货点，其中一个为默认。
 */
public final class PortRegistry {

    /** 一条登记 */
    public record Entry(String owner, String address, int x, int y, int z,
                        String type, boolean isDefault) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean loaded = false;

    private PortRegistry() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("ports.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_ports.txt");
        }
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        ENTRIES.clear();
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
                try {
                    if (f.length >= 7) {
                        // 新格式：owner|地址|x|y|z|类型|默认
                        ENTRIES.add(new Entry(f[0].trim(), f[1].trim(),
                                Integer.parseInt(f[2].trim()),
                                Integer.parseInt(f[3].trim()),
                                Integer.parseInt(f[4].trim()),
                                f[5].trim(),
                                "1".equals(f[6].trim())));
                    } else if (f.length >= 5) {
                        // 旧格式：地址|x|y|z|类型 → 视为「自己给自己登记，默认」
                        String addr = f[0].trim();
                        ENTRIES.add(new Entry(addr, addr,
                                Integer.parseInt(f[1].trim()),
                                Integer.parseInt(f[2].trim()),
                                Integer.parseInt(f[3].trim()),
                                f[4].trim(), true));
                        System.out.println("[TangtuEconomical] 兼容旧格式提货点: " + addr);
                    }
                } catch (Throwable ignored) {
                    // 跳过坏行
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读提货点登记失败: " + t);
        }
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder("# owner|地址|x|y|z|类型|默认\n");
        for (Entry e : ENTRIES) {
            sb.append(e.owner()).append('|').append(e.address()).append('|')
                    .append(e.x()).append('|').append(e.y()).append('|').append(e.z())
                    .append('|').append(e.type()).append('|')
                    .append(e.isDefault() ? "1" : "0").append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写提货点登记失败: " + t);
        }
    }

    /** 绑定（同 owner + 同地址覆盖） */
    public static synchronized void bind(String owner, String address, BlockPos pos, String type) {
        load();
        boolean first = ENTRIES.stream().noneMatch(e -> e.owner().equalsIgnoreCase(owner));
        ENTRIES.removeIf(e -> e.owner().equalsIgnoreCase(owner)
                && e.address().equalsIgnoreCase(address));
        ENTRIES.add(new Entry(owner, address, pos.getX(), pos.getY(), pos.getZ(), type, first));
        save();
    }

    /** 设默认（同一玩家的其它点取消默认） */
    public static synchronized boolean setDefault(String owner, String address) {
        load();
        boolean found = false;
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry e = ENTRIES.get(i);
            if (!e.owner().equalsIgnoreCase(owner)) {
                continue;
            }
            boolean target = e.address().equalsIgnoreCase(address);
            if (target) {
                found = true;
            }
            ENTRIES.set(i, new Entry(e.owner(), e.address(), e.x(), e.y(), e.z(), e.type(), target));
        }
        if (found) {
            save();
        }
        return found;
    }

    public static synchronized boolean unbind(String owner, String address) {
        load();
        boolean removed = ENTRIES.removeIf(e -> e.owner().equalsIgnoreCase(owner)
                && e.address().equalsIgnoreCase(address));
        if (removed) {
            save();
        }
        return removed;
    }

    public static synchronized List<Entry> of(String owner) {
        load();
        List<Entry> out = new ArrayList<>();
        for (Entry e : ENTRIES) {
            if (e.owner().equalsIgnoreCase(owner)) {
                out.add(e);
            }
        }
        return out;
    }

    public static synchronized List<Entry> all() {
        load();
        return new ArrayList<>(ENTRIES);
    }

    /**
     * 给某个玩家挑提货点：
     *   ① 他自己的默认点
     *   ② 他自己的、离发报机最近的点
     *   ③ 全服离发报机最近的点（兜底，保证能送到）
     */
    public static synchronized Entry pick(BlockPos center, String playerName) {
        load();
        if (ENTRIES.isEmpty()) {
            return null;
        }
        Entry def = null;
        Entry ownNearest = null;
        double ownD = Double.MAX_VALUE;
        for (Entry e : ENTRIES) {
            if (playerName == null || !e.owner().equalsIgnoreCase(playerName)) {
                continue;
            }
            if (e.isDefault()) {
                def = e;
            }
            double d = center.distSqr(e.pos());
            if (d < ownD) {
                ownD = d;
                ownNearest = e;
            }
        }
        if (def != null) {
            return def;
        }
        if (ownNearest != null) {
            return ownNearest;
        }
        Entry best = null;
        double bestD = Double.MAX_VALUE;
        for (Entry e : ENTRIES) {
            double d = center.distSqr(e.pos());
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** 把货物交给这个提货点（直接塞进它的库存；Create 物流的发送由 TickerShip 负责） */
    public static boolean deliver(ServerLevel level, Entry entry,
                                  java.util.List<net.minecraft.world.item.ItemStack> goods) {
        if (entry == null) {
            return false;
        }
        var port = new AddressBook.PortInfo(level, entry.pos(), entry.address());
        return AddressBook.deliverTo(port, goods);
    }

    public static synchronized String dump() {
        load();
        if (ENTRIES.isEmpty()) {
            return "（还没有绑定任何提货点）";
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : ENTRIES) {
            sb.append(e.owner()).append("  →  ").append(e.address())
                    .append(e.isDefault() ? "  [默认]" : "")
                    .append("  [").append(e.type()).append("]  ")
                    .append(e.x()).append(", ").append(e.y()).append(", ").append(e.z())
                    .append('\n');
        }
        return sb.toString();
    }

    /** 判断方块实体是不是 白色邮箱 / 蛙港 */
    public static String typeOf(Object be) {
        if (be == null) {
            return "";
        }
        String cls = be.getClass().getName().toLowerCase();
        if (cls.contains("frogport")) {
            return "frogport";
        }
        if (cls.contains("postbox")) {
            return "postbox";
        }
        return "";
    }
}
