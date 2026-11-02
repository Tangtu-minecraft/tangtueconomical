package com.tangtu.kjbb;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家生产统计（模组自己记，不依赖原版统计）
 *
 *   · 合成（ItemCraftedEvent）  → crafted
 *   · 熔炼（ItemSmeltedEvent）  → smelted
 *   · 挖方块（BlockEvent.BreakEvent）→ mined
 *
 * 文件：config/kjbb/player_production.txt
 *   itemId|crafted|smelted|mined
 *
 * 用途：远洋载货单按「玩家真正在生产的品类」来拟定
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.GAME)
public final class PlayerProduction {

    private static final Map<String, long[]> DATA = new LinkedHashMap<>();
    private static boolean LOADED = false;
    private static long LAST_SAVE = 0L;
    private static boolean DIRTY = false;

    private PlayerProduction() {
    }

    private static Path file() {
        return Path.of("config", "kjbb", "player_production.txt");
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
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length < 4) {
                    continue;
                }
                try {
                    DATA.put(f[0].trim(), new long[]{
                            Long.parseLong(f[1].trim()),
                            Long.parseLong(f[2].trim()),
                            Long.parseLong(f[3].trim())});
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public static synchronized void save() {
        try {
            Path p = file();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# itemId|crafted|smelted|mined\n");
            for (var e : DATA.entrySet()) {
                long[] v = e.getValue();
                sb.append(e.getKey()).append('|').append(v[0]).append('|').append(v[1])
                        .append('|').append(v[2]).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
            DIRTY = false;
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void bump(String itemId, int idx, long add) {
        if (itemId == null || itemId.isEmpty() || add <= 0L) {
            return;
        }
        synchronized (PlayerProduction.class) {
            load();
            long[] v = DATA.computeIfAbsent(itemId, k -> new long[3]);
            v[idx] += add;
            DIRTY = true;
            long now = ServerClock.now();
            if (now - LAST_SAVE > 60_000L) {
                LAST_SAVE = now;
                save();
            }
        }
    }

    /** 每分钟由 tick 调用，补一次落盘 */
    public static void tickFlush() {
        if (DIRTY) {
            save();
        }
    }

    // ---------------- 事件 ----------------

    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        try {
            ItemStack st = event.getCrafting();
            if (st == null || st.isEmpty()) {
                return;
            }
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            bump(id, 0, st.getCount());
        } catch (Throwable ignored) {
            // ignore
        }
    }

    @SubscribeEvent
    public static void onSmelted(PlayerEvent.ItemSmeltedEvent event) {
        try {
            ItemStack st = event.getSmelting();
            if (st == null || st.isEmpty()) {
                return;
            }
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            bump(id, 1, st.getCount());
        } catch (Throwable ignored) {
            // ignore
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        try {
            if (!(event.getLevel() instanceof ServerLevel)) {
                return;
            }
            if (!(event.getPlayer() instanceof ServerPlayer)) {
                return;
            }
            var state = event.getState();
            if (state == null) {
                return;
            }
            ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (rl == null) {
                return;
            }
            bump(rl.toString(), 2, 1L);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ---------------- 查询 ----------------

    /** 生产热度榜：只有「合成 + 熔炼」算生产，挖方块单独算 */
    public static synchronized List<Map.Entry<String, Long>> topProduced(int limit) {
        load();
        List<Map.Entry<String, Long>> list = new ArrayList<>();
        for (var e : DATA.entrySet()) {
            long produced = e.getValue()[0] + e.getValue()[1];
            if (produced <= 0L) {
                continue;
            }
            list.add(Map.entry(e.getKey(), produced));
        }
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    /** 挖矿榜（方块 ID） */
    public static synchronized List<Map.Entry<String, Long>> topMined(int limit) {
        load();
        List<Map.Entry<String, Long>> list = new ArrayList<>();
        for (var e : DATA.entrySet()) {
            long mined = e.getValue()[2];
            if (mined <= 0L) {
                continue;
            }
            list.add(Map.entry(e.getKey(), mined));
        }
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public static synchronized long produced(String itemId) {
        load();
        long[] v = DATA.get(itemId);
        return v == null ? 0L : (v[0] + v[1]);
    }

    /** 文案：给命令展示 */
    public static String describe(int top) {
        StringBuilder sb = new StringBuilder("§6===== 玩家生产榜（模组统计）=====\n");
        List<Map.Entry<String, Long>> p = topProduced(top);
        if (p.isEmpty()) {
            sb.append("§7（还没有数据：装了这个版本后玩家再合成就会开始记）\n");
        } else {
            int i = 0;
            for (var e : p) {
                sb.append("§7  ").append(++i).append(". ").append(e.getKey())
                        .append("  §f").append(e.getValue()).append('\n');
            }
        }
        sb.append("§6===== 玩家挖矿榜 =====\n");
        for (var e : topMined(Math.min(top, 8))) {
            sb.append("§7  ").append(e.getKey()).append("  §f").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }
}
