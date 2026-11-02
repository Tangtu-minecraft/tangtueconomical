package com.tangtu.kjbb;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.HashSet;
import java.util.Set;

/**
 * 唐兔国国民的饮食偏好：喜欢「含胡萝卜的食物」
 *
 * 判定方式（数据驱动，不靠手写名单）：
 *   扫描所有合成 / 熔炼 / 营火 / 烟熏 / 高炉配方，
 *   只要配方原料里出现胡萝卜（或胡萝卜相关物品），
 *   且产物是食物 → 记进 set。
 *
 * 另外，物品 ID 里带 carrot 的食物也直接算进去。
 */
public final class FoodPreference {

    private FoodPreference() {
    }

    private static final Set<String> CARROT_FOODS = new HashSet<>();
    private static boolean loaded = false;

    /** 偏好加成：国民愿意为此多花多少（1.25 = 多 25%） */
    public static final double CARROT_BONUS = 1.25;

    public static synchronized void load(MinecraftServer server) {
        if (loaded || server == null) {
            return;
        }
        loaded = true;
        // 顺便扫一遍炒锅 / 汤锅类高级食物
        try {
            com.tangtu.kjbb.FoodTiers.loadOtherFoods(server);
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            var rm = server.getRecipeManager();
            int scanned = 0;
            for (var holder : rm.getRecipes()) {
                scanned++;
                try {
                    var recipe = holder.value();
                    var type = recipe.getType();
                    boolean cookLike = type == net.minecraft.world.item.crafting.RecipeType.SMELTING
                            || type == net.minecraft.world.item.crafting.RecipeType.CAMPFIRE_COOKING
                            || type == net.minecraft.world.item.crafting.RecipeType.SMOKING
                            || type == net.minecraft.world.item.crafting.RecipeType.BLASTING
                            || type == net.minecraft.world.item.crafting.RecipeType.CRAFTING;
                    if (!cookLike) {
                        continue;
                    }
                    if (!hasCarrot(recipe)) {
                        continue;
                    }
                    ItemStack out = recipe.getResultItem(server.registryAccess());
                    if (out == null || out.isEmpty()) {
                        continue;
                    }
                    if (out.get(net.minecraft.core.component.DataComponents.FOOD) == null) {
                        continue;
                    }
                    String id = BuiltInRegistries.ITEM.getKey(out.getItem()).toString();
                    CARROT_FOODS.add(id);
                } catch (Throwable ignored) {
                    // 单条配方出错不影响整体
                }
            }
            System.out.println("[TangtuEconomical] 胡萝卜偏好食物 " + CARROT_FOODS.size()
                    + " 种（扫描配方 " + scanned + " 条）");
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 胡萝卜偏好扫描失败: " + t);
        }
    }

    private static boolean hasCarrot(Object recipe) {
        try {
            Object ing = null;
            try {
                var m = recipe.getClass().getMethod("getIngredients");
                ing = m.invoke(recipe);
            } catch (Throwable ignored) {
                return false;
            }
            if (!(ing instanceof java.util.List<?> list)) {
                return false;
            }
            for (Object slot : list) {
                if (slot instanceof net.minecraft.world.item.crafting.Ingredient ingr) {
                    for (ItemStack st : ingr.getItems()) {
                        if (st == null || st.isEmpty()) {
                            continue;
                        }
                        var rl = BuiltInRegistries.ITEM.getKey(st.getItem());
                        if (rl != null && rl.getPath().toLowerCase().contains("carrot")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }


    /** 这个食物是不是「含胡萝卜」的（带 ID 兜底判断） */
    public static boolean likesCarrot(String itemId) {
        if (itemId == null) {
            return false;
        }
        if (CARROT_FOODS.contains(itemId)) {
            return true;
        }
        return itemId.toLowerCase().contains("carrot") && FoodTable.isFood(itemId);
    }

    public static int size() {
        return CARROT_FOODS.size();
    }
}
