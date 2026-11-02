package com.tangtu.kjbb;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import net.minecraft.server.MinecraftServer;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 食物分级：廉价食物 / 高级食物
 *
 * 判定顺序（任一命中即算高级）：
 *   ① 单价 ≥ premiumPrice（默认 60，取市场价或基准价）
 *   ② 营养分 ≥ premiumScore（默认 1200，FoodTable.score 是 ×1000 的）
 *   ③ 名字里带高级标签（蛋糕 / 金苹果 / 熟肉 / 炖菜 / 派 / 曲奇…）
 *
 * 其余能吃的一律算廉价食物。
 */
public final class FoodTiers {

    private FoodTiers() {
    }

    public static final String CHEAP = "CHEAP";
    public static final String PREMIUM = "PREMIUM";

    /** 异界食材关键词：下界 / 末地 / 暮色森林 */
    private static final String[] EXOTIC_WORDS = {
        // 下界
        "nether", "blaze", "ghast", "magma", "soul", "wither", "hoglin",
        "crimson", "warped", "quartz", "glowstone", "netherite", "strider",
        "zoglin", "piglin", "basalt", "blackstone", "gold_nugget",
        // 末地
        "end_", "_end", "ender", "chorus", "purpur", "shulker", "dragon",
        "endstone", "end_stone", "void",
        // 暮色森林
        "twilight", "fiery", "steeleaf", "ironwood", "knightmetal",
        "carminite", "raven_feather", "torchberry", "hydra", "naga",
        "lich", "minotaur", "yeti", "maze", "troll", "giant"
    };

    /**
     * 是否「异界食物」：主料来自下界 / 末地 / 暮色森林。
     * 这类食物比普通高级食物更稀有，算更高一档。
     */
    public static boolean isExotic(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String id = itemId.toLowerCase(java.util.Locale.ROOT);
        // 模组层面
        if (id.startsWith("twilightforest:") || id.startsWith("twilightdelight:")
                || id.startsWith("ends_delight:") || id.startsWith("end_delight:")
                || id.startsWith("nethersdelight:") || id.startsWith("mynethersdelight:")
                || id.startsWith("netherdepthsupgrade:")) {
            return true;
        }
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        for (String w : EXOTIC_WORDS) {
            if (name.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 高级食物的单价门槛 */
    public static long premiumPrice = 60L;
    /** 高级食物的营养分门槛（FoodTable.score 是 ×1000） */
    public static long premiumScore = 1200L;

    /** 森罗物语（kaleidoscope_cookery）里必须用炒锅/汤锅才能做的食物 */
    private static final Set<String> PANS = new HashSet<>();
    private static boolean panScanDone = false;

    /** 需要炒锅 / 汤锅的配方类型关键字 */
    private static final String[] PAN_TYPES = {
            "kaleidoscope_cookery:pot",
            "kaleidoscope_cookery:stockpot",
            "kaleidoscope_cookery:flex_pot",
            "kaleidoscope_cookery:flex_stockpot",
            "kaleidoscope_cookery:teapot",
            "kaleidoscope_cookery:steamer",
    };

    /**
     * 扫描所有配方，把「炒锅 / 汤锅类配方」的产物记下来
     * 每次数据包重载后都可以重复调用（幂等）
     */
    public static synchronized void loadOtherFoods(MinecraftServer server) {
        if (panScanDone || server == null) {
            return;
        }
        panScanDone = true;
        PANS.clear();
        try {
            var rm = server.getRecipeManager();
            int scanned = 0;
            for (var holder : rm.getRecipes()) {
                scanned++;
                try {
                    var recipe = holder.value();
                    String type = recipe.getType().toString();
                    boolean pan = false;
                    for (String k : PAN_TYPES) {
                        if (type.contains(k)) {
                            pan = true;
                            break;
                        }
                    }
                    if (!pan) {
                        continue;
                    }
                    ItemStack out = recipe.getResultItem(server.registryAccess());
                    if (out == null || out.isEmpty()) {
                        continue;
                    }
                    String id = BuiltInRegistries.ITEM.getKey(out.getItem()).toString();
                    if (FoodTable.isFood(id)) {
                        PANS.add(id);
                    }
                } catch (Throwable ignored) {
                    // 单条配方失败不影响整体
                }
            }
            System.out.println("[TangtuEconomical] \u9ad8\u7ea7\u98df\u7269\uff1a\u7092\u9505/\u6c64\u9505\u4ea7\u7269 "
                    + PANS.size() + " \u79cd\uff08\u626b\u63cf\u914d\u65b9 " + scanned + " \u6761\uff09");
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u7092\u9505/\u6c64\u9505\u98df\u7269\u626b\u63cf\u5931\u8d25: " + t);
        }
    }

    /** 是否属于炒锅 / 汤锅类配方产物 */
    public static boolean isPanFood(String itemId) {
        return itemId != null && PANS.contains(itemId);
    }

    /** 名字/路径里出现这些词 → 高级 */
    private static final String[] PREMIUM_WORDS = {
            "cake", "cookie", "pie", "stew", "soup", "golden_apple", "golden_carrot",
            "cooked_", "honey", "chocolate", "pudding", "pancake", "burger", "pizza",
            "sushi", "curry", "roast", "grilled", "delight", "feast", "deluxe",
    };

    private static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 分类 */
    public static String tier(String itemId) {
        if (itemId == null) {
            return CHEAP;
        }
        if (!FoodTable.isFood(itemId)) {
            return CHEAP;
        }
        if (PANS.contains(itemId)) {
            return PREMIUM;
        }
        String path = itemId.toLowerCase(Locale.ROOT);
        for (String w : PREMIUM_WORDS) {
            if (path.contains(w)) {
                return PREMIUM;
            }
        }
        long unit = unitPrice(itemId);
        if (unit >= premiumPrice) {
            return PREMIUM;
        }
        try {
            if (FoodTable.score(itemId, new double[]{1, 1, 1, 1, 1}) >= premiumScore) {
                return PREMIUM;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return CHEAP;
    }

    public static boolean isPremium(String itemId) {
        return PREMIUM.equals(tier(itemId));
    }

    public static boolean isCheap(String itemId) {
        return CHEAP.equals(tier(itemId));
    }

    /** 单价：市场价 → 基准价 → 0 */
    public static long unitPrice(String itemId) {
        try {
            long p = MarketPrice.price(itemId);
            if (p > 0L) {
                return p;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            return Math.max(0L, ShopPrices.price(itemId));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 显示名（日志用） */
    public static String nameOf(String itemId) {
        try {
            var it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
            if (it != null) {
                return new ItemStack(it).getHoverName().getString();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return itemId;
    }
}
