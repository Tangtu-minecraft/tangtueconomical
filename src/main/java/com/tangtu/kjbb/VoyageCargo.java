package com.tangtu.kjbb;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 远洋载货单候选池（数据包可调）
 *
 * 文件：data/&lt;命名空间&gt;/kjbb/voyage/cargo.json
 *
 * 格式：
 * {
 *   "items": [
 *     {"item": "minecraft:iron_ingot", "weight": 8},
 *     {"item": "create:cogwheel",      "weight": 5},
 *     {"item": "minecraft:emerald",    "weight": 3}
 *   ]
 * }
 *
 * 权重越大，越容易被唐兔国航运公司抽中作为本航次的货物。
 */
public class VoyageCargo extends SimplePreparableReloadListener<Map<String, Integer>> {

    private static final Gson GSON = new Gson();
    private static final String DIR = "kjbb/voyage";
    private static Map<String, Integer> POOL = new LinkedHashMap<>();

    @Override
    protected Map<String, Integer> prepare(ResourceManager mgr, ProfilerFiller profiler) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            var found = mgr.listResources(DIR, rl -> rl.getPath().endsWith(".json"));
            for (var e : found.entrySet()) {
                try (Reader rd = e.getValue().openAsReader()) {
                    JsonObject obj = GSON.fromJson(rd, JsonObject.class);
                    if (obj == null || !obj.has("items")) {
                        continue;
                    }
                    JsonElement arr = obj.get("items");
                    if (!arr.isJsonArray()) {
                        continue;
                    }
                    JsonArray a = arr.getAsJsonArray();
                    for (JsonElement el : a) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        JsonObject o = el.getAsJsonObject();
                        String item = o.has("item") ? o.get("item").getAsString().trim() : "";
                        int w = o.has("weight") ? o.get("weight").getAsInt() : 1;
                        if (item.isEmpty() || w <= 0) {
                            continue;
                        }
                        out.put(item, w);
                    }
                } catch (Throwable ignored) {
                    // 单个文件损坏不影响其他
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 载货池扫描失败: " + t);
        }
        return out;
    }

    @Override
    protected void apply(Map<String, Integer> data, ResourceManager mgr, ProfilerFiller profiler) {
        if (data == null || data.isEmpty()) {
            return;
        }
        POOL = data;
        DebugLog.log("远洋载货池已加载：" + data.size() + " 种候选货");
    }

    /** 候选池（可能为空 → 调用方用自己的兜底列表） */
    public static Map<String, Integer> pool() {
        return POOL;
    }

    public static boolean isEmpty() {
        return POOL.isEmpty();
    }

    /** 按权重随机抽一种（抽不到返回 null） */
    public static String pickWeighted(ThreadLocalRandom rnd) {
        if (POOL.isEmpty()) {
            return null;
        }
        int total = 0;
        for (int w : POOL.values()) {
            total += w;
        }
        if (total <= 0) {
            return null;
        }
        int r = rnd.nextInt(total);
        for (var e : POOL.entrySet()) {
            r -= e.getValue();
            if (r < 0) {
                return e.getKey();
            }
        }
        return POOL.keySet().iterator().next();
    }

    /** 调试用：当前候选清单 */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        for (var e : POOL.entrySet()) {
            out.add(e.getKey() + "(" + e.getValue() + ")");
        }
        return out;
    }
}
