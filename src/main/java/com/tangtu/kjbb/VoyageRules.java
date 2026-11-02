package com.tangtu.kjbb;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 远洋规则（数据包）
 *
 * 文件：data/&lt;命名空间&gt;/kjbb/voyage/rules.json
 *
 * <pre>
 * {
 *   "coins": { "create_currency_shops:copper_coin": 1, "create_currency_shops:gold_coin": 100 },
 *   "returnCoin": { "item": "create_currency_shops:copper_coin", "budgetPermille": 200, "maxCount": 512 },
 *   "share": { "issuerMaxPermille": 400, "npcJoinAfterPermille": 300, "maxConcurrent": 2 },
 *   "tariff": { "exportPermille": 80, "importPermille": 40 }
 * }
 * </pre>
 *
 * 没写的字段保留 BotConfig 里的默认值，所以老存档不会因为缺文件而崩。
 */
public class VoyageRules extends SimplePreparableReloadListener<JsonObject> {

    private static final Gson GSON = new Gson();
    private static final String DIR = "kjbb/voyage";

    /** 旧币面值表（币种 → 唐兔币面值） */
    private static final Map<String, Long> COIN = new LinkedHashMap<>();
    /** 返航旧币配置 */
    private static String returnCoinItem = "create_currency_shops:copper_coin";
    private static int returnCoinBudgetPermille = 200;
    private static int returnCoinMaxCount = 512;

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
            System.out.println("[TangtuEconomical] 远洋规则扫描失败: " + t);
        }
        return merged;
    }

    @Override
    protected void apply(JsonObject obj, ResourceManager mgr, ProfilerFiller profiler) {
        if (obj == null) {
            return;
        }
        try {
            if (obj.has("coins") && obj.get("coins").isJsonObject()) {
                COIN.clear();
                for (var en : obj.getAsJsonObject("coins").entrySet()) {
                    try {
                        COIN.put(en.getKey(), en.getValue().getAsLong());
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
            if (obj.has("returnCoin") && obj.get("returnCoin").isJsonObject()) {
                JsonObject rc = obj.getAsJsonObject("returnCoin");
                if (rc.has("item")) {
                    returnCoinItem = rc.get("item").getAsString();
                }
                if (rc.has("budgetPermille")) {
                    returnCoinBudgetPermille = rc.get("budgetPermille").getAsInt();
                }
                if (rc.has("maxCount")) {
                    returnCoinMaxCount = rc.get("maxCount").getAsInt();
                }
            }
            if (obj.has("share") && obj.get("share").isJsonObject()) {
                JsonObject sh = obj.getAsJsonObject("share");
                var cfg = BotConfig.get();
                if (sh.has("issuerMaxPermille")) {
                    cfg.voyageIssuerMaxSharePermille = sh.get("issuerMaxPermille").getAsInt();
                }
                if (sh.has("npcJoinAfterPermille")) {
                    cfg.voyageNpcJoinAfterPermille = sh.get("npcJoinAfterPermille").getAsInt();
                }
                if (sh.has("maxConcurrent")) {
                    cfg.voyageMaxConcurrent = Math.max(1, sh.get("maxConcurrent").getAsInt());
                }
            }
            if (obj.has("tariff") && obj.get("tariff").isJsonObject()) {
                JsonObject tf = obj.getAsJsonObject("tariff");
                var cfg = BotConfig.get();
                if (tf.has("exportPermille")) {
                    cfg.voyageTariffPermille = tf.get("exportPermille").getAsInt();
                }
                if (tf.has("importPermille")) {
                    cfg.voyageImportTariffPermille = tf.get("importPermille").getAsInt();
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 远洋规则应用失败: " + t);
        }
        DebugLog.log("远洋规则已加载：旧币 " + COIN.size() + " 种，返航币 "
                + returnCoinItem + "，并发上限 " + BotConfig.get().voyageMaxConcurrent);
    }

    /** 旧币面值；不是旧币返回 0 */
    public static long coinFace(String itemId) {
        if (itemId == null) {
            return 0L;
        }
        Long v = COIN.get(itemId);
        return v == null ? 0L : v;
    }

    /** 所有登记过的旧币 id（面额从大到小由调用方排） */
    public static java.util.List<String> coinIds() {
        return new java.util.ArrayList<>(COIN.keySet());
    }

    /** 是不是登记的旧币 */
    public static boolean isCoin(String itemId) {
        return coinFace(itemId) > 0L;
    }

    public static String returnCoinItem() {
        return returnCoinItem;
    }

    public static int returnCoinBudgetPermille() {
        return returnCoinBudgetPermille;
    }

    public static int returnCoinMaxCount() {
        return returnCoinMaxCount;
    }
}