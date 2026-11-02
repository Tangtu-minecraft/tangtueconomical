package com.tangtu.kjbb;

import java.io.Reader;
import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 交易黑名单（数据包）
 *
 * 文件：data/&lt;命名空间&gt;/kjbb/trade/*.json
 *
 * <pre>
 * {
 *   "mods":     ["waystones", "sophisticatedbackpacks"],
 *   "keywords": ["upgrade", "bedrock", "command_block"],
 *   "items":    ["minecraft:bedrock"]
 * }
 * </pre>
 *
 * 三档判定，任意命中即禁止上架：
 *   ① mods：整个模组封杀（新增变种自动拦）
 *   ② keywords：物品名包含即拦
 *   ③ items：精确物品 id
 */
public class TradeBlacklist extends SimplePreparableReloadListener<JsonObject> {

    private static final Gson GSON = new Gson();
    private static final String DIR = "kjbb/trade";

    private static final Set<String> MODS = new LinkedHashSet<>();
    private static final Set<String> KEYWORDS = new LinkedHashSet<>();
    private static final Set<String> ITEMS = new LinkedHashSet<>();

    /** 数据包缺失时的兜底（保证不会因为没写文件就全放开） */
    private static final String[] DEFAULT_MODS = { "waystones", "sophisticatedbackpacks" };
    private static final String[] DEFAULT_KEYWORDS = {
        "upgrade", "backpack", "spawn_egg", "command_block",
        "structure_block", "barrier", "bedrock", "debug_stick",
        "waystone", "portstone", "sharestone", "warp_stone", "attuned_shard"
    };

    @Override
    protected JsonObject prepare(ResourceManager mgr, ProfilerFiller profiler) {
        JsonObject merged = new JsonObject();
        try {
            var found = mgr.listResources(DIR, rl -> rl.getPath().endsWith(".json"));
            for (var e : found.entrySet()) {
                try (Reader rd = e.getValue().openAsReader()) {
                    JsonObject obj = GSON.fromJson(rd, JsonObject.class);
                    if (obj == null) {
                        continue;
                    }
                    for (var en : obj.entrySet()) {
                        merged.add(en.getKey(), en.getValue());
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 交易黑名单扫描失败: " + t);
        }
        return merged;
    }

    @Override
    protected void apply(JsonObject obj, ResourceManager mgr, ProfilerFiller profiler) {
        MODS.clear();
        KEYWORDS.clear();
        ITEMS.clear();
        if (obj != null) {
            readSet(obj, "mods", MODS);
            readSet(obj, "keywords", KEYWORDS);
            readSet(obj, "items", ITEMS);
        }
        if (MODS.isEmpty() && KEYWORDS.isEmpty() && ITEMS.isEmpty()) {
            for (String m : DEFAULT_MODS) {
                MODS.add(m);
            }
            for (String k : DEFAULT_KEYWORDS) {
                KEYWORDS.add(k);
            }
        }
        DebugLog.log("交易黑名单已加载：模组 " + MODS.size() + " / 关键词 "
                + KEYWORDS.size() + " / 物品 " + ITEMS.size());
    }

    private static void readSet(JsonObject obj, String key, Set<String> out) {
        try {
            if (!obj.has(key) || !obj.get(key).isJsonArray()) {
                return;
            }
            for (JsonElement el : obj.getAsJsonArray(key)) {
                try {
                    String v = el.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                    if (!v.isEmpty()) {
                        out.add(v);
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 这个物品是否禁止上架 */
    public static boolean banned(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return true;
        }
        String full = itemId.toLowerCase(java.util.Locale.ROOT);
        if (ITEMS.contains(full)) {
            return true;
        }
        String mod = full.contains(":") ? full.substring(0, full.indexOf(':')) : "";
        if (!mod.isEmpty() && MODS.contains(mod)) {
            return true;
        }
        String name = full.contains(":") ? full.substring(full.indexOf(':') + 1) : full;
        for (String k : KEYWORDS) {
            if (name.contains(k)) {
                return true;
            }
        }
        // 数据包还没加载时，用内置兜底
        if (MODS.isEmpty() && KEYWORDS.isEmpty() && ITEMS.isEmpty()) {
            for (String m : DEFAULT_MODS) {
                if (mod.equals(m)) {
                    return true;
                }
            }
            for (String k : DEFAULT_KEYWORDS) {
                if (name.contains(k)) {
                    return true;
                }
            }
        }
        return false;
    }
}