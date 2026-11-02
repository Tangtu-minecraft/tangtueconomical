package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓单封条：config/kjbb/store_seals.txt
 *
 * 格式：x|y|z|item=count;item=count
 *
 * 开具仓单时把容器内容做成「封条」。之后任何一次打开/挂单/成交都会比对；
 * 一旦内容变了，封条失效，仓单作废 —— 防止仓主开后门换货。
 */
public final class StoreSeals {

    public record Row(int x, int y, int z, String seal) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static boolean loaded = false;

    private StoreSeals() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("store_seals.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_store_seals.txt");
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
                if (f.length < 4) {
                    continue;
                }
                try {
                    ROWS.add(new Row(Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()),
                            Integer.parseInt(f[2].trim()), f[3].trim()));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读封条失败: " + t);
        }
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder("# x|y|z|seal\n");
        for (Row r : ROWS) {
            sb.append(r.x()).append('|').append(r.y()).append('|').append(r.z()).append('|')
                    .append(r.seal()).append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写封条失败: " + t);
        }
    }

    public static synchronized java.util.List<Row> all() {
        load();
        return new ArrayList<>(ROWS);
    }

    public static synchronized String get(BlockPos pos) {
        load();
        for (Row r : ROWS) {
            if (r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ()) {
                return r.seal();
            }
        }
        return null;
    }

    public static synchronized void set(BlockPos pos, String seal) {
        load();
        ROWS.removeIf(r -> r.x() == pos.getX() && r.y() == pos.getY() && r.z() == pos.getZ());
        if (seal != null && !seal.isEmpty()) {
            ROWS.add(new Row(pos.getX(), pos.getY(), pos.getZ(), seal));
        }
        save();
    }

    public static synchronized void clear(BlockPos pos) {
        set(pos, null);
    }

    /** 把物品映射做成封条字符串 */
    public static String sealOf(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        for (var e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 读容器当前内容 */
    public static Map<String, Integer> snapshot(ServerLevel level, BlockPos pos) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            var be = level.getBlockEntity(pos);
            if (be == null) {
                return counts;
            }
            IItemHandler h = Capabilities.ItemHandler.BLOCK
                    .getCapability(level, pos, be.getBlockState(), be, null);
            if (h == null) {
                return counts;
            }
            for (int i = 0; i < h.getSlots(); i++) {
                ItemStack st = h.getStackInSlot(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                var rl = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (rl == null) {
                    continue;
                }
                counts.merge(rl.toString(), st.getCount(), Integer::sum);
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读取容器内容失败: " + t);
        }
        return counts;
    }

    /** 当前内容是否仍与封条一致 */
    public static boolean matches(ServerLevel level, BlockPos pos) {
        String seal = get(pos);
        if (seal == null) {
            return true;
        }
        return seal.equals(sealOf(snapshot(level, pos)));
    }
}