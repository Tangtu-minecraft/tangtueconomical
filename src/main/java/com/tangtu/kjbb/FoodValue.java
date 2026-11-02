package com.tangtu.kjbb;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 食物饱食度标准（全 mod 通用）
 *
 * Minecraft 原版食物自带 FoodProperties（nutrition 饥饿值恢复 + saturation 饱和度）。
 * 我们用：
 *
 *     饱食度 = nutrition + saturation × 2
 *
 * 例：小麦 1+0 = 1；胡萝卜 3+1.2 = 5.4→5；面包 5+3 = 11；牛排 8+12.8 = 33
 *
 * 没有 FoodProperties 的物品（如某些 mod 原料）走兜底表。
 */
public final class FoodValue {

    private FoodValue() {
    }

    /** 兜底表：没有 FoodProperties 但确实能当粮食的物品 */
    private static final Map<String, Integer> FALLBACK = new LinkedHashMap<>();

    static {
        FALLBACK.put("minecraft:wheat", 1);
        FALLBACK.put("minecraft:wheat_seeds", 0);
        FALLBACK.put("minecraft:potato", 1);
        FALLBACK.put("minecraft:carrot", 5);
        FALLBACK.put("minecraft:bread", 11);
        FALLBACK.put("minecraft:baked_potato", 9);
    }

    /** 单个物品的饱食度 */
    public static int of(String itemId) {
        try {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
            if (item != null) {
                return of(new ItemStack(item));
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return FALLBACK.getOrDefault(itemId, 0);
    }

    /** 单个物品堆的饱食度 */
    public static int of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        try {
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null) {
                double v = food.nutrition() + food.saturation() * 2.0;
                return Math.max(1, (int) Math.round(v));
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            var rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (rl != null) {
                return FALLBACK.getOrDefault(rl.toString(), 0);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0;
    }

    /** 是食物吗 */
    public static boolean isFood(String itemId) {
        return of(itemId) > 0;
    }

    /** 自动扫描：所有带 FoodProperties 的物品 → 饱食度 */
    public static Map<String, Integer> allFoods() {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        try {
            for (var item : BuiltInRegistries.ITEM) {
                try {
                    var stack = new ItemStack(item);
                    int v = of(stack);
                    if (v > 0) {
                        var rl = BuiltInRegistries.ITEM.getKey(item);
                        if (rl != null) {
                            out.put(rl.toString(), v);
                        }
                    }
                } catch (Throwable ignored) {
                    // 单个物品失败不影响整体
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return out;
    }

    /** 说明文本 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (var e : FALLBACK.entrySet()) {
            if (sb.length() > 0) {
                sb.append("\uff1b");
            }
            sb.append(e.getKey().replace("minecraft:", "")).append(" ")
                    .append(of(e.getKey()));
        }
        return sb.toString();
    }
}
