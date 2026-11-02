package com.tangtu.kjbb;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 罗马国贸易品目录（数据包）
 *
 * 文件：data/&lt;命名空间&gt;/kjbb/roma/goods.json
 *
 * <pre>
 * {
 *   "categories": { "food": ["minecraft:wheat", ...], "mech": ["create:cogwheel", ...] },
 *   "relativePricePermille": { "food": 1550, "mech": 720, ... }
 * }
 * </pre>
 *
 * 作用：把「一个物品属于哪个品类」从关键词猜测升级成**显式建档**。
 * 罗马国的收购价、量价参考量都按品类走，所以这张表直接决定经济走向。
 *
 * 查不到的物品回退到 RomaMarket 的关键词兜底（老存档/新模组不会因此崩掉）。
 */
public class RomaGoods extends SimplePreparableReloadListener<Map<String, Object>> {

    private static final Gson GSON = new Gson();
    private static final String DIR = "kjbb/roma";

    /** itemId -> category（FOOD / MECH / METAL / BUILD / LUXURY / OTHER） */
    private static final Map<String, String> CAT = new LinkedHashMap<>();
    /** category -> 相对价格（千分比） */
    private static final Map<String, Integer> PRICE = new LinkedHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> prepare(ResourceManager mgr, ProfilerFiller profiler) {
        Map<String, String> cat = new LinkedHashMap<>();
        Map<String, Integer> price = new LinkedHashMap<>();
        try {
            var found = mgr.listResources(DIR, rl -> rl.getPath().endsWith(".json"));
            for (var e : found.entrySet()) {
                try (Reader rd = e.getValue().openAsReader()) {
                    JsonObject obj = GSON.fromJson(rd, JsonObject.class);
                    if (obj == null) {
                        continue;
                    }
                    if (obj.has("categories") && obj.get("categories").isJsonObject()) {
                        JsonObject cats = obj.getAsJsonObject("categories");
                        for (var en : cats.entrySet()) {
                            String cname = en.getKey().trim().toUpperCase(java.util.Locale.ROOT);
                            if (!en.getValue().isJsonArray()) {
                                continue;
                            }
                            JsonArray arr = en.getValue().getAsJsonArray();
                            for (JsonElement it : arr) {
                                if (it.isJsonPrimitive()) {
                                    String id = it.getAsString().trim();
                                    if (!id.isEmpty()) {
                                        cat.put(id, cname);
                                    }
                                }
                            }
                        }
                    }
                    if (obj.has("relativePricePermille")
                            && obj.get("relativePricePermille").isJsonObject()) {
                        for (var en : obj.getAsJsonObject("relativePricePermille").entrySet()) {
                            try {
                                price.put(en.getKey().trim().toUpperCase(java.util.Locale.ROOT),
                                        en.getValue().getAsInt());
                            } catch (Throwable ignored) {
                                // 单个坏值跳过
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 单个文件损坏不影响其他
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 罗马贸易品目录扫描失败: " + t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cat", cat);
        out.put("price", price);
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void apply(Map<String, Object> data, ResourceManager mgr, ProfilerFiller profiler) {
        if (data == null) {
            return;
        }
        Object c = data.get("cat");
        Object p = data.get("price");
        if (c instanceof Map<?, ?> m && !m.isEmpty()) {
            CAT.clear();
            for (var e : m.entrySet()) {
                CAT.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        if (p instanceof Map<?, ?> m2 && !m2.isEmpty()) {
            PRICE.clear();
            for (var e : m2.entrySet()) {
                try {
                    PRICE.put(String.valueOf(e.getKey()), (Integer) e.getValue());
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        DebugLog.log("罗马贸易品目录已加载：" + CAT.size() + " 个物品，"
                + PRICE.size() + " 个品类价格");
    }

    /** 查品类；不在目录里返回 null（调用方回退关键词判断） */
    public static String catOf(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        return CAT.get(itemId);
    }

    /** 品类相对价格（千分比）；没配置返回 fallback */
    public static int pricePermille(String category, int fallback) {
        if (category == null) {
            return fallback;
        }
        Integer v = PRICE.get(category.toUpperCase(java.util.Locale.ROOT));
        return v == null ? fallback : v;
    }

    /** 通用品类判断：数据包里写了就算（不命中返回 false，调用方自己兜底） */
    public static boolean is(String category, String itemId) {
        String c = catOf(itemId);
        return c != null && category != null && c.equalsIgnoreCase(category);
    }

    public static boolean isFood(String itemId) {
        return is("FOOD", itemId);
    }

    public static boolean isMetal(String itemId) {
        return is("METAL", itemId);
    }

    public static boolean isMech(String itemId) {
        return is("MECH", itemId);
    }

    public static boolean isLuxury(String itemId) {
        return is("LUXURY", itemId);
    }

    public static boolean isBuild(String itemId) {
        return is("BUILD", itemId);
    }

    public static boolean isTextile(String itemId) {
        return is("TEXTILE", itemId);
    }

    public static boolean isStone(String itemId) {
        return is("STONE", itemId);
    }

    public static boolean isOil(String itemId) {
        return is("OIL", itemId);
    }

    public static boolean isWine(String itemId) {
        return is("WINE", itemId);
    }

    /** 某个品类下所有物品（给调试/指令用） */
    public static java.util.List<String> of(String category) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (category == null) {
            return out;
        }
        for (var e : CAT.entrySet()) {
            if (e.getValue().equalsIgnoreCase(category)) {
                out.add(e.getKey());
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    public static int size() {
        return CAT.size();
    }

    /** 目录里所有物品（给调试指令用） */
    public static Map<String, String> all() {
        return CAT;
    }
}
