package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 食物营养表（config/kjbb/food_values.txt）
 *
 *     itemId|grains|vegetables|proteins|sugars|fruits   （×1000）
 *
 * 862 条约 37 KB 文件，内存里约 80 KB，只加载一次。
 */
public final class FoodTable {

    /** 维度下标 */
    public static final int GRAINS = 0, VEG = 1, PROTEIN = 2, SUGAR = 3, FRUIT = 4;

    private static Map<String, int[]> TABLE = null;

    private FoodTable() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("food_values.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_food_values.txt");
        }
    }

    public static synchronized Map<String, int[]> table() {
        if (TABLE != null) {
            return TABLE;
        }
        Map<String, int[]> m = new LinkedHashMap<>();
        try {
            Path f = file();
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#")) {
                        continue;
                    }
                    String[] p = s.split("\\|");
                    if (p.length < 6) {
                        continue;
                    }
                    try {
                        int[] v = new int[5];
                        for (int i = 0; i < 5; i++) {
                            v[i] = Integer.parseInt(p[i + 1].trim());
                        }
                        m.put(p[0].trim(), v);
                    } catch (Throwable ignored) {
                        // 跳过坏行
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        TABLE = m.isEmpty() ? Collections.emptyMap() : m;
        System.out.println("[TangtuEconomical] 营养表加载 " + TABLE.size() + " 条");
        return TABLE;
    }

    /** 按某层的偏好权重算分（权重 5 个） */
    public static int score(String itemId, double[] w) {
        int[] v = table().get(itemId);
        if (v == null) {
            return FoodValue.of(itemId) * 10;
        }
        double sum = 0;
        for (int i = 0; i < 5; i++) {
            sum += v[i] * w[i];
        }
        return (int) Math.round(sum);
    }

    /** 通用价值（等权重） */
    public static int value(String itemId) {
        return score(itemId, new double[]{1.0, 1.0, 1.0, 1.0, 1.0}) / 5;
    }

    public static synchronized void reload() {
        TABLE = null;
        table();
    }

    /** 这个物品是不是（有营养数据的）食物 */
        /** 这些是「半成品 / 状态变体 / 装饰品」，不算可交易食物 */
    private static final String[] JUNK_WORDS = {
        "stage_", "_stage", "bites/", "_bite", "bite_stages/",
        "remaining_", "variant_", "state_", "_states/", "skewer_states/",
        "raw_dough", "straw_hat", "_gui/", "fixed_skewer_gui/",
        "seasoning_special", "failure_skewer", "mysterious_state",
        "incomplete_", "_incomplete", "unfinished_"
    };

    /** 是否是要排除的垃圾条目 */
    public static boolean isJunk(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return true;
        }
        String id = itemId.toLowerCase(java.util.Locale.ROOT);
        for (String w : JUNK_WORDS) {
            if (id.contains(w)) {
                return true;
            }
        }
        return false;
    }

public static boolean isFood(String itemId) {
        if (isJunk(itemId)) {
            return false;
        }
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        // 先查共享贸易品目录（data/kjbankbridge/kjbb/roma/goods.json 的 FOOD 类）：
        // 模组食物很多没有原版 FOOD 组件，也不在营养表里，靠这一层才认得出来。
        try {
            if (com.tangtu.kjbb.RomaGoods.isFood(itemId)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 数据包未加载时继续走原逻辑
        }
        int[] v = table().get(itemId);
        if (v != null) {
            return true;
        }
        try {
            var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.tryParse(itemId));
            if (it == null) {
                return false;
            }
            return new net.minecraft.world.item.ItemStack(it)
                    .get(net.minecraft.core.component.DataComponents.FOOD) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
