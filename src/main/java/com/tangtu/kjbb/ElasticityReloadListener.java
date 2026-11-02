package com.tangtu.kjbb;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * 物品价格弹性（数据包可调）
 *
 * 文件位置：data/&lt;命名空间&gt;/kjbb/elasticity/*.json
 *
 * 格式：
 * {
 *   "default": {
 *     "poor_cheap": 0.05,  "poor_premium": 0.55,
 *     "mid_cheap": 0.10,   "mid_premium": 0.45,
 *     "rich_cheap": 0.02,  "rich_premium": 0.20
 *   },
 *   "items": {
 *     "minecraft:wheat":     {"poor_cheap": 0.02, "mid_cheap": 0.02, "rich_cheap": 0.01},
 *     "kaleidoscope_cookery:XXX": {"poor_premium": 0.8},
 *     "minecraft:golden_apple":   {"all": 0.9}
 *   }
 * }
 *
 * 含义：预算紧张度 tight(0..1) 时，出价系数 = 1 − tight × 弹性，
 *       弹性越大 → 越容易降价（高级食物通常弹性大）。
 */
public final class ElasticityReloadListener
        extends SimplePreparableReloadListener<Map<String, JsonObject>> {

    /** 当前生效的默认值 */
    public static final class Defaults {
        public double poorCheap = 0.05;
        public double poorPremium = 0.55;
        public double midCheap = 0.10;
        public double midPremium = 0.45;
        public double richCheap = 0.02;
        public double richPremium = 0.20;
    }

    /** 建材默认弹性（城建采购用） */
    public static volatile double MATERIAL_DEFAULT = 0.35;
    /** 珍宝默认弹性（稀有物品需求单用；越稀有越容易被推迟） */
    public static volatile double TREASURE_DEFAULT = 0.60;
    /** 资金紧张到该值时，高弹性珍宝直接不下单 */
    public static volatile double SKIP_TIGHT = 0.75;
    /** 弹性达到该值的珍宝才会被「跳过」 */
    public static volatile double SKIP_ELASTICITY = 0.80;

    /** 珍宝逐物品覆盖 */
    private static volatile Map<String, Double> TREASURES = new HashMap<>();

    /** 逐物品目标库存（决定库存因子的中性点） */
    public static volatile double STOCK_DEFAULT = 1000.0;
    private static volatile Map<String, Double> STOCKS = new HashMap<>();
    /** 大库：品类 → 目标库存 */
    private static volatile Map<String, Double> STOCK_GROUPS = new HashMap<>();

    /** 建材逐物品覆盖 */
    private static volatile Map<String, Double> MATERIALS = new HashMap<>();

    private static volatile Defaults DEFAULTS = new Defaults();
    /** 物品 → 覆盖值（键为 poor_cheap / all …） */
    private static volatile Map<String, Map<String, Double>> ITEMS = new HashMap<>();

    public static Defaults defaults() {
        return DEFAULTS;
    }

    /**
     * 查询某物品在某阶层的弹性
     *
     * @param tier 1=温饱 2=小康 3=富足
     */
    public static double of(String itemId, int tier, boolean premium) {
        Defaults d = DEFAULTS;
        double base;
        if (tier <= 1) {
            base = premium ? d.poorPremium : d.poorCheap;
        } else if (tier == 2) {
            base = premium ? d.midPremium : d.midCheap;
        } else {
            base = premium ? d.richPremium : d.richCheap;
        }
        if (itemId == null) {
            return base;
        }
        Map<String, Double> ov = ITEMS.get(itemId);
        if (ov == null || ov.isEmpty()) {
            return base;
        }
        String tierKey = tier <= 1 ? "poor" : (tier == 2 ? "mid" : "rich");
        String exact = tierKey + (premium ? "_premium" : "_cheap");
        if (ov.containsKey(exact)) {
            return clamp(ov.get(exact));
        }
        if (ov.containsKey(tierKey)) {
            return clamp(ov.get(tierKey));
        }
        if (ov.containsKey("all")) {
            return clamp(ov.get("all"));
        }
        return base;
    }

    /** 建材弹性（城建采购时用） */
    public static double materialOf(String itemId) {
        if (itemId != null) {
            Double v = MATERIALS.get(itemId);
            if (v != null) {
                return clamp(v);
            }
        }
        return clamp(MATERIAL_DEFAULT);
    }

    /** 珍宝弹性（稀有需求单用） */
    public static double treasureOf(String itemId) {
        if (itemId != null) {
            Double v = TREASURES.get(itemId);
            if (v != null) {
                return clamp(v);
            }
        }
        return clamp(TREASURE_DEFAULT);
    }

    /**
     * 物品属于哪个大库
     *
     *   treasure      珍宝
     *   food_premium  高级食物
     *   food_cheap    廉价食物
     *   mat_advanced  高级建材（加工复杂）
     *   mat_processed 加工建材
     *   mat_basic     基础建材
     */
    public static String stockGroupOf(String itemId) {
        if (itemId == null) {
            return "default";
        }
        if (TREASURES.containsKey(itemId)) {
            return "treasure";
        }
        try {
            if (com.tangtu.kjbb.FoodTable.isFood(itemId)) {
                return com.tangtu.kjbb.FoodTiers.isPremium(itemId)
                        ? "food_premium" : "food_cheap";
            }
        } catch (Throwable ignored) {
            // ignore
        }
        double matEl = materialOf(itemId);
        if (matEl >= 0.60) {
            return "mat_advanced";
        }
        if (matEl >= 0.40) {
            return "mat_processed";
        }
        return "mat_basic";
    }

    /** 人口缩放：目标库存与人口成正比 */
    private static double populationScale() {
        try {
            long pop = com.tangtu.kjbb.MarketBots.citizenPopulation();
            int base0 = Math.max(1, com.tangtu.kjbb.BotConfig.get().stockTargetBasePopulation);
            double s = pop / (double) base0;
            return Math.max(0.1, Math.min(10.0, s));
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    /** 目标库存：逐物品 → 大库 → 默认（再 × 人口系数） */
    public static long scaledTargetStockOf(String itemId) {
        long raw = targetStockOf(itemId);
        return Math.max(1L, Math.round(raw * populationScale()));
    }

    /** 目标库存：逐物品 → 大库 → 默认 */
    public static long targetStockOf(String itemId) {
        if (itemId != null) {
            Double v = STOCKS.get(itemId);
            if (v != null && v > 0) {
                return Math.max(1L, Math.round(v));
            }
            Double g = STOCK_GROUPS.get(stockGroupOf(itemId));
            if (g != null && g > 0) {
                return Math.max(1L, Math.round(g));
            }
        }
        return Math.max(1L, Math.round(STOCK_DEFAULT));
    }

    private static double clamp(double v) {
        // 弹性 0~0.95，避免把价格压到负数
        return Math.max(0.0, Math.min(0.95, v));
    }

    @Override
    protected Map<String, JsonObject> prepare(ResourceManager mgr, ProfilerFiller profiler) {
        Map<String, JsonObject> out = new HashMap<>();
        Gson gson = new Gson();
        try {
            var found = mgr.listResources("kjbb/elasticity",
                    rl -> rl.getPath().endsWith(".json"));
            for (var e : found.entrySet()) {
                try (Reader rd = e.getValue().openAsReader()) {
                    JsonObject obj = gson.fromJson(rd, JsonObject.class);
                    if (obj != null) {
                        out.put(e.getKey().toString(), obj);
                    }
                } catch (Throwable ignored) {
                    // 单个文件损坏不影响其他
                }
            }
        } catch (Throwable ignored) {
            // 没有这个目录就用默认值
        }
        return out;
    }

    @Override
    protected void apply(Map<String, JsonObject> data, ResourceManager mgr,
                         ProfilerFiller profiler) {
        Defaults d = new Defaults();
        Map<String, Map<String, Double>> items = new HashMap<>();
        for (var obj : data.values()) {
            try {
                JsonObject def = obj.getAsJsonObject("default");
                if (def != null) {
                    if (def.has("poor_cheap")) {
                        d.poorCheap = def.get("poor_cheap").getAsDouble();
                    }
                    if (def.has("poor_premium")) {
                        d.poorPremium = def.get("poor_premium").getAsDouble();
                    }
                    if (def.has("mid_cheap")) {
                        d.midCheap = def.get("mid_cheap").getAsDouble();
                    }
                    if (def.has("mid_premium")) {
                        d.midPremium = def.get("mid_premium").getAsDouble();
                    }
                    if (def.has("rich_cheap")) {
                        d.richCheap = def.get("rich_cheap").getAsDouble();
                    }
                    if (def.has("rich_premium")) {
                        d.richPremium = def.get("rich_premium").getAsDouble();
                    }
                }
                JsonObject mats = obj.getAsJsonObject("materials");
                if (mats != null) {
                    if (mats.has("default")) {
                        try {
                            MATERIAL_DEFAULT = mats.get("default").getAsDouble();
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                    JsonObject mit = mats.getAsJsonObject("items");
                    if (mit != null) {
                        for (var en : mit.entrySet()) {
                            try {
                                MATERIALS.put(en.getKey(), en.getValue().getAsDouble());
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    }
                }
                JsonObject treas = obj.getAsJsonObject("treasures");
                if (treas != null) {
                    if (treas.has("default")) {
                        try {
                            TREASURE_DEFAULT = treas.get("default").getAsDouble();
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                    if (treas.has("skip_when_tight")) {
                        try {
                            SKIP_TIGHT = treas.get("skip_when_tight").getAsDouble();
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                    if (treas.has("skip_elasticity")) {
                        try {
                            SKIP_ELASTICITY = treas.get("skip_elasticity").getAsDouble();
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                    JsonObject tit = treas.getAsJsonObject("items");
                    if (tit != null) {
                        for (var en : tit.entrySet()) {
                            try {
                                TREASURES.put(en.getKey(), en.getValue().getAsDouble());
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    }
                }
                JsonObject groups = obj.getAsJsonObject("stockGroups");
                if (groups != null) {
                    for (var en : groups.entrySet()) {
                        try {
                            STOCK_GROUPS.put(en.getKey(), en.getValue().getAsDouble());
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                }
                JsonObject stock = obj.getAsJsonObject("stock");
                if (stock != null) {
                    if (stock.has("default")) {
                        try {
                            STOCK_DEFAULT = stock.get("default").getAsDouble();
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                    JsonObject sit = stock.getAsJsonObject("items");
                    if (sit != null) {
                        for (var en : sit.entrySet()) {
                            try {
                                STOCKS.put(en.getKey(), en.getValue().getAsDouble());
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    }
                }
                JsonObject its = obj.getAsJsonObject("items");
                if (its != null) {
                    for (var en : its.entrySet()) {
                        if (!en.getValue().isJsonObject()) {
                            continue;
                        }
                        Map<String, Double> m = new HashMap<>();
                        for (var kv : en.getValue().getAsJsonObject().entrySet()) {
                            try {
                                m.put(kv.getKey(), kv.getValue().getAsDouble());
                            } catch (Throwable ignored) {
                                // 跳过坏值
                            }
                        }
                        if (!m.isEmpty()) {
                            items.put(en.getKey(), m);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 跳过坏文件
            }
        }
        DEFAULTS = d;
        ITEMS = items;
        System.out.println("[TangtuEconomical] 价格弹性包加载：食物 " + items.size()
                + " 种，建材 " + MATERIALS.size() + " 种，珍宝 "
                + TREASURES.size() + " 种，目标库存 " + STOCKS.size()
                + " + " + STOCK_GROUPS.size() + " 大库");
    }

    /** 供命令/调试查看 */
    public static String describe() {
        Defaults d = DEFAULTS;
        return String.format(java.util.Locale.ROOT,
                "poor cheap=%.2f premium=%.2f / mid cheap=%.2f premium=%.2f / "
                        + "rich cheap=%.2f premium=%.2f / items=%d",
                d.poorCheap, d.poorPremium, d.midCheap, d.midPremium,
                d.richCheap, d.richPremium, ITEMS.size());
    }

    /** 未使用的占位，保持 import 有效 */
    @SuppressWarnings("unused")
    private static ResourceLocation unused() {
        return null;
    }
}
