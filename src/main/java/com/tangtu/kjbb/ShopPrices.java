package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 商城价格表（服务端单一数据源）。
 *
 * 存于 config/kjbb/shop_prices.properties，形如：
 *   minecraft:iron_ingot=18
 *
 * KubeJS 通过 {@link ShopBridge} 读，mixin 扣款时也读这里 —— 保证只有一个价格源。
 */
public final class ShopPrices {

    /** 最终生效价（由四层合并而成） */
    private static final Map<String, Long> PRICES = new LinkedHashMap<>();
    /** 内置兜底价 */
    private static final Map<String, Long> DEFAULTS = new LinkedHashMap<>();
    /** config 文件层 */
    private static final Map<String, Long> CONFIG = new LinkedHashMap<>();
    /** 数据包层 */
    private static final Map<String, Long> PACK = new LinkedHashMap<>();
    /** 运行时层（KubeJS 调价） */
    private static final Map<String, Long> RUNTIME = new LinkedHashMap<>();
    private static Path filePath;
    private static boolean loaded = false;

    static {
        // 初次运行时的默认价（等于收购起始价 × 1.5 左右）
        DEFAULTS.put("minecraft:iron_ingot", 18L);
        DEFAULTS.put("minecraft:copper_ingot", 15L);
        DEFAULTS.put("create:zinc_ingot", 21L);
        DEFAULTS.put("minecraft:gold_ingot", 60L);
        DEFAULTS.put("minecraft:netherite_scrap", 780L);
        DEFAULTS.put("minecraft:coal", 12L);
        DEFAULTS.put("minecraft:diamond", 240L);
        DEFAULTS.put("minecraft:emerald", 150L);
        DEFAULTS.put("minecraft:oak_log", 15L);
        DEFAULTS.put("minecraft:wheat", 3L);
        DEFAULTS.put("minecraft:carrot", 3L);
        DEFAULTS.put("minecraft:potato", 3L);
        DEFAULTS.put("minecraft:beetroot", 5L);
        DEFAULTS.put("minecraft:sugar_cane", 2L);
        DEFAULTS.put("minecraft:pumpkin", 8L);
        DEFAULTS.put("create:andesite_alloy", 9L);
        DEFAULTS.put("create:cogwheel", 12L);
        DEFAULTS.put("create:electron_tube", 105L);
        DEFAULTS.put("create:copper_casing", 128L);
        DEFAULTS.put("create:brass_casing", 165L);
        DEFAULTS.put("create:railway_casing", 210L);
        DEFAULTS.put("create:mechanical_arm", 270L);
        DEFAULTS.put("create:crushing_wheel", 300L);
        DEFAULTS.put("create:precision_mechanism", 450L);
        // ---- 动态定价参数（小数用千分之一）----
        DEFAULTS.put("target_stock", 1000L);
        DEFAULTS.put("stock_factor_min", 500L);
        DEFAULTS.put("stock_factor_max", 2000L);
        DEFAULTS.put("stock_elasticity", 300L);
    }

    private ShopPrices() {
    }

    private static Path file() {
        if (filePath == null) {
            try {
                Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
                Files.createDirectories(dir);
                filePath = dir.resolve("shop_prices.properties");
            } catch (Throwable t) {
                filePath = Path.of("kjbb_shop_prices.properties");
            }
        }
        return filePath;
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        CONFIG.clear();
        Path p = file();
        if (!Files.exists(p)) {
            rebuild();
            save();
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(p)) {
            props.load(in);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读价格表失败: " + t);
            return;
        }
        for (String key : props.stringPropertyNames()) {
            try {
                long v = Long.parseLong(props.getProperty(key).trim());
                if (v > 0) {
                    CONFIG.put(key, v);
                }
            } catch (Throwable ignored) {
                // 跳过坏行
            }
        }
        rebuild();
    }

    /** 按优先级重建最终价：内置 < 数据包 < config < 运行时 */
    private static synchronized void rebuild() {
        PRICES.clear();
        PRICES.putAll(DEFAULTS);
        PRICES.putAll(PACK);
        PRICES.putAll(CONFIG);
        PRICES.putAll(RUNTIME);
    }

    /** 数据包层（由 PriceReloadListener 调用） */
    public static synchronized void setPackPrices(Map<String, Long> map) {
        PACK.clear();
        if (map != null) {
            PACK.putAll(map);
        }
        rebuild();
        System.out.println("[TangtuEconomical] 数据包价格层: " + PACK.size() + " 条");
    }

    /** 这个价格来自哪一层 */
    public static synchronized String sourceOf(String itemId) {
        load();
        if (RUNTIME.containsKey(itemId)) {
            return "运行时(KJS)";
        }
        if (CONFIG.containsKey(itemId)) {
            return "config 文件";
        }
        if (PACK.containsKey(itemId)) {
            return "数据包";
        }
        if (DEFAULTS.containsKey(itemId)) {
            return "内置默认";
        }
        return "无";
    }

    public static synchronized void save() {
        Path p = file();
        Properties props = new Properties();
        Map<String, Long> all = new LinkedHashMap<>(PRICES);
        for (Map.Entry<String, Long> e : all.entrySet()) {
            props.setProperty(e.getKey(), String.valueOf(e.getValue()));
        }
        try (OutputStream out = Files.newOutputStream(p)) {
            props.store(out, "KJBB shop prices (itemId=price)");
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写价格表失败: " + t);
        }
    }

    // ---- 动态定价参数（与物品价存在同一个 properties 里）----

    public static long targetStock() {
        return longParam("target_stock", 1000L);
    }

    public static double stockFactorMin() {
        return doubleParam("stock_factor_min", 0.5);
    }

    public static double stockFactorMax() {
        return doubleParam("stock_factor_max", 2.0);
    }

    public static double stockElasticity() {
        return doubleParam("stock_elasticity", 0.3);
    }

    private static long longParam(String key, long def) {
        load();
        Long v = PRICES.get(key);
        return v == null ? def : v;
    }

    /** 小数用千分之一编码：存 300 表示 0.300 */
    private static double doubleParam(String key, double def) {
        load();
        Long v = PRICES.get(key);
        if (v == null) {
            return def;
        }
        return v / 1000.0;
    }

    /** 单价；0 = 未定价（不允许通过发报机购买） */
    public static long price(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return 0L;
        }
        load();
        Long v = PRICES.get(itemId);
        return v == null ? 0L : v;
    }

    public static boolean hasPrice(String itemId) {
        return price(itemId) > 0L;
    }

    public static synchronized boolean setPrice(String itemId, long price) {
        if (itemId == null || itemId.isEmpty() || price <= 0L) {
            return false;
        }
        load();
        RUNTIME.put(itemId, price);
        CONFIG.put(itemId, price);
        rebuild();
        save();
        return true;
    }

    public static synchronized boolean removePrice(String itemId) {
        if (itemId == null) {
            return false;
        }
        load();
        RUNTIME.remove(itemId);
        CONFIG.remove(itemId);
        rebuild();
        save();
        return true;
    }

    /** "itemId=price;itemId=price" */
    /** 所有有定价的物品 id */
    public static synchronized java.util.List<String> items() {
        load();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : PRICES.keySet()) {
            if (!isItemKey(id)) {
                continue;
            }
            if (PRICES.get(id) != null && PRICES.get(id) > 0) {
                out.add(id);
            }
        }
        return out;
    }

    /** 只认「命名空间:路径」形式，并排除内部动态定价参数 */
    public static boolean isItemKey(String id) {
        if (id == null || !id.contains(":")) {
            return false;
        }
        return !id.equals("target_stock")
                && !id.equals("stock_factor_min")
                && !id.equals("stock_factor_max")
                && !id.equals("stock_elasticity");
    }

    public static synchronized String dump() {
        load();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : PRICES.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 价格来源统计 */
    public static synchronized String describeSources() {
        load();
        int d = 0;
        int k = 0;
        int p = 0;
        for (String id : PRICES.keySet()) {
            String s = sourceOf(id);
            if (s.equals("内置默认")) {
                d++;
            } else if (s.equals("数据包")) {
                p++;
            } else {
                k++;
            }
        }
        return "§e价格表：共 " + PRICES.size() + " 条§7（内置 " + d + " / 数据包 " + p
                + " / config+运行时 " + k + "）\n§7文件：" + pathString();
    }

    /** 某种物品的价格 + 来源 */
    public static synchronized String detail(String itemId) {
        long v = price(itemId);
        return (v <= 0 ? "§c未定价" : "§a" + v + " 唐兔币")
                + "§7（来源：" + sourceOf(itemId) + "）";
    }

    public static synchronized int size() {
        load();
        return PRICES.size();
    }

    public static String pathString() {
        return file().toAbsolutePath().toString();
    }

    public static synchronized void reloadFromDisk() {
        loaded = false;
        load();
    }
}
