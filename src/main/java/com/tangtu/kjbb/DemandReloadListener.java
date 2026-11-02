package com.tangtu.kjbb;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 需求包：data/&lt;命名空间&gt;/kjbb/demand/*.json
 *
 * 格式：
 * {
 *   "cityItems":   ["minecraft:iron_ingot", "create:precision_mechanism"],
 *   "rareItems":   ["minecraft:diamond", "minecraft:nether_star"],
 *   "cityPerHead": 16,
 *   "cityMax":     4096,
 *   "rareChance":  5,
 *   "rareMax":     64,
 *   "cityPremium": [1.1, 1.4],
 *   "rarePremium": [1.5, 2.5]
 * }
 *
 * 多个文件会合并（后读到的覆盖标量，列表取并集）。
 */
public class DemandReloadListener extends SimplePreparableReloadListener<DemandReloadListener.Demand> {

    private static final Gson GSON = new Gson();
    private static final String DIR = "kjbb/demand";

    /** 需求配置（默认值 = 硬编码兜底） */
    public static final class Demand {
        public List<String> cityItems = new ArrayList<>(List.of(
                "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:gold_ingot",
                "create:zinc_ingot", "create:brass_ingot", "create:andesite_alloy",
                "create:precision_mechanism", "create:electron_tube", "create:cogwheel",
                "minecraft:redstone", "minecraft:coal"));
        public List<String> rareItems = new ArrayList<>(List.of(
                "minecraft:diamond", "minecraft:emerald", "minecraft:netherite_scrap",
                "minecraft:netherite_ingot", "minecraft:ancient_debris", "minecraft:echo_shard",
                "minecraft:amethyst_shard", "minecraft:blaze_rod", "minecraft:ender_pearl",
                "minecraft:enchanted_golden_apple", "minecraft:nether_star",
                "create:precision_mechanism", "create:sturdy_sheet"));
        public int cityPerHead = 16;
        public int cityMax = 4096;
        public int rareChance = 5;
        public int rareMax = 64;
        public double cityPremiumMin = 1.1;
        public double cityPremiumMax = 1.4;
        public double rarePremiumMin = 1.5;
        public double rarePremiumMax = 2.5;

        /** 三层市民的食物偏好权重：谷物/蔬菜/蛋白/糖/水果 */
        public double[][] layerWeights = {
                {3.0, 2.0, 1.0, 0.5, 1.0},
                {1.0, 2.0, 3.0, 1.0, 2.0},
                {0.5, 1.0, 2.0, 3.0, 3.0}
        };

        /** 经济部托底的基础农产品 */
        public List<String> farmSupport = new ArrayList<>(List.of(
                "minecraft:wheat", "minecraft:carrot", "minecraft:potato",
                "minecraft:beetroot", "minecraft:sugar_cane", "minecraft:pumpkin",
                "minecraft:melon_slice", "minecraft:apple"));
    }

    private static volatile Demand CURRENT = new Demand();

    public static Demand current() {
        return CURRENT;
    }

    @Override
    protected Demand prepare(ResourceManager mgr, ProfilerFiller profiler) {
        Demand d = new Demand();
        d.cityItems.clear();
        d.rareItems.clear();
        try {
            var found = mgr.listResources(DIR, rl -> rl.getPath().endsWith(".json"));
            for (var e : found.entrySet()) {
                try (var in = e.getValue().open()) {
                    JsonElement el = GSON.fromJson(
                            new InputStreamReader(in, StandardCharsets.UTF_8), JsonElement.class);
                    if (el != null && el.isJsonObject()) {
                        merge(el.getAsJsonObject(), d);
                    }
                } catch (Throwable t) {
                    System.out.println("[TangtuEconomical] 需求包读取失败 " + e.getKey() + ": " + t);
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 扫描需求包失败: " + t);
        }
        if (d.cityItems.isEmpty()) {
            d.cityItems.addAll(new Demand().cityItems);
        }
        if (d.rareItems.isEmpty()) {
            d.rareItems.addAll(new Demand().rareItems);
        }
        System.out.println("[TangtuEconomical] 需求包加载：城建 " + d.cityItems.size()
                + " 种，珍宝 " + d.rareItems.size() + " 种，托底 " + d.farmSupport.size() + " 种");
        return d;
    }

    private void merge(JsonObject o, Demand d) {
        readList(o, "cityItems", d.cityItems);
        readList(o, "rareItems", d.rareItems);
        if (o.has("cityPerHead")) {
            d.cityPerHead = o.get("cityPerHead").getAsInt();
        }
        if (o.has("cityMax")) {
            d.cityMax = o.get("cityMax").getAsInt();
        }
        if (o.has("rareChance")) {
            d.rareChance = o.get("rareChance").getAsInt();
        }
        if (o.has("rareMax")) {
            d.rareMax = o.get("rareMax").getAsInt();
        }
        readPair(o, "cityPremium", v -> d.cityPremiumMin = v, v -> d.cityPremiumMax = v);
        readPair(o, "rarePremium", v -> d.rarePremiumMin = v, v -> d.rarePremiumMax = v);

        // 三层偏好：{"poor":[...], "mid":[...], "rich":[...]}
        if (o.has("layerWeights") && o.get("layerWeights").isJsonObject()) {
            var lw = o.getAsJsonObject("layerWeights");
            String[] keys = {"poor", "mid", "rich"};
            for (int i = 0; i < 3; i++) {
                if (lw.has(keys[i]) && lw.get(keys[i]).isJsonArray()) {
                    var arr = lw.getAsJsonArray(keys[i]);
                    if (arr.size() >= 5) {
                        double[] w = new double[5];
                        for (int k = 0; k < 5; k++) {
                            w[k] = arr.get(k).getAsDouble();
                        }
                        d.layerWeights[i] = w;
                    }
                }
            }
        }
        readList(o, "farmSupportItems", d.farmSupport);
    }

    private void readList(JsonObject o, String key, List<String> target) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return;
        }
        JsonArray arr = o.getAsJsonArray(key);
        for (var el : arr) {
            try {
                String s = el.getAsString();
                if (s != null && !s.isBlank() && !target.contains(s)) {
                    target.add(s);
                }
            } catch (Throwable ignored) {
                // skip
            }
        }
    }

    private void readPair(JsonObject o, String key,
                          java.util.function.DoubleConsumer lo,
                          java.util.function.DoubleConsumer hi) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return;
        }
        JsonArray arr = o.getAsJsonArray(key);
        if (arr.size() >= 2) {
            try {
                lo.accept(arr.get(0).getAsDouble());
                hi.accept(arr.get(1).getAsDouble());
            } catch (Throwable ignored) {
                // skip
            }
        }
    }

    @Override
    protected void apply(Demand data, ResourceManager mgr, ProfilerFiller profiler) {
        CURRENT = data;
    }
}
