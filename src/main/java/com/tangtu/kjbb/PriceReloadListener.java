package com.tangtu.kjbb;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据包价格层：data/&lt;命名空间&gt;/kjbb/prices/*.json
 *
 * 格式（两种都支持）：
 *   { "prices": { "minecraft:iron_ingot": 18 } }
 *   或直接 { "minecraft:iron_ingot": 18, "create:zinc_ingot": 21 }
 */
public class PriceReloadListener extends SimplePreparableReloadListener<Map<String, Long>> {

    private static final Gson GSON = new Gson();
    private static final String DIR = "kjbb/prices";

    @Override
    protected Map<String, Long> prepare(ResourceManager mgr, ProfilerFiller profiler) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            var found = mgr.listResources(DIR, rl -> rl.getPath().endsWith(".json"));
            for (var e : found.entrySet()) {
                Resource res = e.getValue();
                try (var in = res.open()) {
                    JsonElement el = GSON.fromJson(
                            new InputStreamReader(in, StandardCharsets.UTF_8), JsonElement.class);
                    collect(el, out);
                } catch (Throwable t) {
                    System.out.println("[TangtuEconomical] 价格包读取失败 " + e.getKey() + ": " + t);
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 扫描价格包失败: " + t);
        }
        return out;
    }

    private void collect(JsonElement el, Map<String, Long> out) {
        if (el == null || !el.isJsonObject()) {
            return;
        }
        JsonObject o = el.getAsJsonObject();
        JsonObject target = o;
        if (o.has("prices") && o.get("prices").isJsonObject()) {
            target = o.getAsJsonObject("prices");
        }
        for (var en : target.entrySet()) {
            String key = en.getKey();
            if (key.startsWith("_")) {
                continue;
            }
            try {
                long v = en.getValue().getAsLong();
                if (v > 0) {
                    out.put(key, v);
                }
            } catch (Throwable ignored) {
                // 跳过
            }
        }
    }

    @Override
    protected void apply(Map<String, Long> data, ResourceManager mgr, ProfilerFiller profiler) {
        ShopPrices.setPackPrices(data);
    }
}
