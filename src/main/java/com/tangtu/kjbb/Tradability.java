package com.tangtu.kjbb;

import java.util.HashSet;
import java.util.Set;

/**
 * 可交易性判定：什么物品允许挂单卖出。
 *
 * 两条硬规则：
 *   ① 背包升级等「功能道具」一律不准卖
 *   ② 没有合成配方的物品一律不准卖（拿不到产出的途径）
 */
public final class Tradability {

    /** 永不开放交易的物品关键词 */
    private static final String[] BANNED = {
        "upgrade", "backpack", "spawn_egg", "command_block",
        "structure_block", "barrier", "bedrock", "debug_stick"
    };

    /** 名字黑名单（不需要服务器） */
    public static boolean banned(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return true;
        }
        // 数据包黑名单优先
        try {
            if (com.tangtu.kjbb.TradeBlacklist.banned(itemId)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 回退内置名单
        }
        String nm = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        for (String w : BANNED) {
            if (nm.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 由配方表构建的「可合成产物」集合 */
    private static final Set<String> CRAFTABLE = new HashSet<>();
    private static boolean BUILT = false;
    private static long BUILT_AT = 0L;

    private Tradability() {
    }

    /** 重建可合成集合（配方重载后调用） */
    public static synchronized void rebuild(net.minecraft.server.MinecraftServer server) {
        CRAFTABLE.clear();
        BUILT = false;
        if (server == null) {
            return;
        }
        try {
            var rm = server.getRecipeManager();
            for (var holder : rm.getRecipes()) {
                try {
                    var out = holder.value().getResultItem(
                            server.registryAccess());
                    if (out == null || out.isEmpty()) {
                        continue;
                    }
                    var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(out.getItem());
                    if (rl != null) {
                        CRAFTABLE.add(rl.toString());
                    }
                } catch (Throwable ignored) {
                    // 单个配方出错跳过
                }
            }
            BUILT = true;
            BUILT_AT = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .toFile().lastModified();
            DebugLog.log("可交易性：已收录 " + CRAFTABLE.size() + " 个可合成产物");
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 构建配方表失败: " + t);
        }
    }

    /** 这个物品能不能挂单卖 */
    public static boolean sellable(net.minecraft.server.MinecraftServer server, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        String nm = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        for (String w : BANNED) {
            if (nm.contains(w)) {
                return false;
            }
        }
        if (!BUILT) {
            rebuild(server);
            if (!BUILT) {
                return true;   // 配方表拿不到，先放行（避免全部瘫痪）
            }
        }
        return CRAFTABLE.contains(itemId);
    }

    /** 不准卖时的提示 */
    public static String rejectReason(String itemId) {
        String nm = itemId == null ? "" : itemId;
        for (String w : BANNED) {
            if (nm.toLowerCase(java.util.Locale.ROOT).contains(w)) {
                return "§c这个物品不允许交易（功能道具）";
            }
        }
        return "§c这个物品没有合成配方，不能挂单";
    }
}