package com.tangtu.kjbb;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 参数包：data/&lt;命名空间&gt;/kjbb/tuning/*.json
 *
 * 把 bots.json 里所有可调数值搬进数据包，改完 /reload 立即生效。
 *
 * 格式（键名 = BotConfig 的字段名）：
 * {
 *   "citizenPerCapita": 5000,
 *   "citizenCarryCapacity": 200,
 *   "citizenSalary": 3000,
 *   "citizenPricePerFood": 34,
 *   "laborPrice": 1200
 * }
 *
 * 多个文件合并，后读的覆盖先读的。
 */
public class TuningReloadListener extends SimplePreparableReloadListener<Map<String, JsonElement>> {

    private static final Gson GSON = new Gson();
    private static final String DIR = "kjbb/tuning";

    @Override
    protected Map<String, JsonElement> prepare(ResourceManager mgr, ProfilerFiller profiler) {
        Map<String, JsonElement> out = new LinkedHashMap<>();
        try {
            var found = mgr.listResources(DIR, rl -> rl.getPath().endsWith(".json"));
            for (var e : found.entrySet()) {
                try (var in = e.getValue().open()) {
                    JsonElement el = GSON.fromJson(
                            new InputStreamReader(in, StandardCharsets.UTF_8), JsonElement.class);
                    if (el != null && el.isJsonObject()) {
                        for (var en : el.getAsJsonObject().entrySet()) {
                            String k = en.getKey();
                            if (k.startsWith("_")) {
                                continue;
                            }
                            out.put(k, en.getValue());
                        }
                    }
                } catch (Throwable t) {
                    System.out.println("[TangtuEconomical] 参数包读取失败 " + e.getKey() + ": " + t);
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 扫描参数包失败: " + t);
        }
        return out;
    }

    @Override
    protected void apply(Map<String, JsonElement> data, ResourceManager mgr, ProfilerFiller profiler) {
        if (data == null || data.isEmpty()) {
            System.out.println("[TangtuEconomical] 参数包为空（使用 bots.json / 默认值）");
            return;
        }
        BotConfig.applyOverrides(data);
        // 写文件日志：确认数据包到底生效了没有
        try {
            var c = BotConfig.get();
            DebugLog.log("=== 参数包已应用：共 " + data.size() + " 个键 ===");
            DebugLog.log("botTakeoverCitizenOffers=" + c.botTakeoverCitizenOffers
                    + "  citizenSalary=" + c.citizenSalary
                    + "  retailKinds=" + c.citizenRetailKinds
                    + "  retailRefreshMin=" + c.citizenRetailRefreshMinutes
                    + "  whitelist=" + c.regulatorBuyWhitelist.size());
            DebugLog.log("crossTrade=" + c.botCrossTradeEnabled
                    + "  granary=" + c.regulatorGranaryEnabled
                    + "  internalTradeMin=" + c.citizenInternalTradeIntervalMinutes
                    + "  productionPerDay=" + c.citizenProductionPerDay);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
