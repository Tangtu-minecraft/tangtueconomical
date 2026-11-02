package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 封条巡检：每 5 秒比对一次「封条 vs 容器现状」。
 *
 * 这样不用等下次打开容器，仓主一拿走东西，仓单立刻作废。
 */
public final class SealWatcher {

    private SealWatcher() {
    }

    /** 连续几次巡检发现「没有任何未兑付仓单」→ 自动恢复开放 */
    private static final java.util.Map<String, Integer> NO_CLAIM = new java.util.HashMap<>();

    /** 轮询游标：每 5 秒只查一个封条，避免 O(N) 全扫 */
    private static int checkCounter = 0;

    public static void check(MinecraftServer server) {
        for (var row : StoreSeals.all()) {
            try {
                checkOne(server, row);
            } catch (Throwable t) {
                System.out.println("[TangtuEconomical] 封条巡检异常: " + t);
            }
        }
    }

    /** 容器里不该出现仓单：抽出来并作废 */
    private static void ejectReceipts(ServerLevel level, BlockPos pos, StoreSeals.Row row) {
        try {
            var be = level.getBlockEntity(pos);
            if (be == null) {
                return;
            }
            var h = net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK
                    .getCapability(level, pos, be.getBlockState(), be, null);
            if (h == null) {
                return;
            }
            for (int i = 0; i < h.getSlots(); i++) {
                var st = h.getStackInSlot(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                if (st.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
                    continue;
                }
                if (!com.tangtu.kjbb.item.StoreReceiptItem.recordsPos(st, pos)) {
                    continue;
                }
                var got = h.extractItem(i, st.getCount(), false);
                if (got != null && !got.isEmpty()) {
                    com.tangtu.kjbb.item.StoreReceiptItem.setVoid(got);
                    System.out.println("[TangtuEconomical] 容器内发现仓单，已抽出作废 @ " + pos);
                    // 只通知仓单持有人，不向全服广播
                    String owner = com.tangtu.kjbb.item.StoreReceiptItem.ownerText(got);
                    String note = "§e[仓单] 你的仓单在容器 "
                            + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                            + " 里被发现（仓单不能放回容器），已抽出并作废";
                    for (var sp : level.getServer().getPlayerList().getPlayers()) {
                        if (owner != null && !owner.isEmpty()
                                && owner.equalsIgnoreCase(sp.getScoreboardName())) {
                            sp.displayClientMessage(
                                    net.minecraft.network.chat.Component.literal(note), false);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 清理容器内仓单失败: " + t);
        }
    }

    /** 比对两份封条，输出前 3 处差异 */
    private static String describeDiff(String expected, String actual) {
        try {
            java.util.Map<String, Integer> a = parseSeal(expected);
            java.util.Map<String, Integer> b = parseSeal(actual);
            StringBuilder sb = new StringBuilder();
            int n = 0;
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            keys.addAll(a.keySet());
            keys.addAll(b.keySet());
            for (String k : keys) {
                if (n >= 3) {
                    break;
                }
                int x = a.getOrDefault(k, 0);
                int y = b.getOrDefault(k, 0);
                if (x == y) {
                    continue;
                }
                if (n > 0) {
                    sb.append(" §7，");
                }
                String name = com.tangtu.kjbb.item.StoreReceiptItem.niceName(k);
                if (x == 0) {
                    sb.append("§a新增 §f").append(name).append("§7 ×").append(y);
                } else if (y == 0) {
                    sb.append("§c少了 §f").append(name).append("§7 ×").append(x);
                } else {
                    sb.append("§e").append(name).append("§7 ").append(x).append(" → ").append(y);
                }
                n++;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static java.util.Map<String, Integer> parseSeal(String seal) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (seal == null || seal.isEmpty()) {
            return out;
        }
        for (String part : seal.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                out.put(part.substring(0, eq).trim(),
                        Integer.parseInt(part.substring(eq + 1).trim()));
            } catch (Throwable ignored) {
                // 跳过
            }
        }
        return out;
    }

    /** 两份「物品→数量」完全一致 */
    private static boolean strictMatch(java.util.Map<String, Integer> a,
                                       java.util.Map<String, Integer> b) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            int x = a.getOrDefault(k, 0);
            int y = b.getOrDefault(k, 0);
            if (x != y) {
                return false;
            }
        }
        return true;
    }

    private static void checkOne(MinecraftServer server, StoreSeals.Row row) {
        ServerLevel level = server.overworld();
        BlockPos pos = row.pos();
        if (level == null) {
            return;
        }
        boolean ok;
        String actual = "";
        // 严格对应：所有未兑付票面之和 必须等于 容器现存内容
        java.util.Map<String, Integer> claimed = StoreClaims.of(pos);
        if (!claimed.isEmpty()) {
            java.util.Map<String, Integer> inside = com.tangtu.kjbb.net.StoreFormNetwork
                    .readCounts(level, pos);
            if (!strictMatch(claimed, inside)) {
                StoreSeals.clear(pos);
                StoreLocks.unlock(pos);
                LockedStoreBreakHandler.voidReceipts(server, pos);
                StoreClaims.clear(pos);
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "§c[仓单] 容器 " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                    + " 与票面不符，全部仓单作废"), false);
                }
                return;
            }
        }
        try {
            if (!level.isLoaded(pos)) {
                return;
            }
            var be = level.getBlockEntity(pos);
            actual = StoreSeals.sealOf(StoreSeals.snapshot(level, pos));
            ok = be != null && row.seal().equals(actual);
        } catch (Throwable t) {
            return;
        }
        if (ok) {
            ejectReceipts(level, pos, row);
            // 没有任何未兑付仓单 → 连续 2 次（10 秒）后自动解封
            String k = pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
            if (StoreClaims.claimedTotal(pos) <= 0) {
                int n = NO_CLAIM.merge(k, 1, Integer::sum);
                if (n >= 2 && n % 2 == 0) {
                    StoreSeals.clear(pos);
                    StoreLocks.unlock(pos);
                    StoreClaims.clear(pos);
                    NO_CLAIM.remove(k);
                    for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                        sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                "§a[仓单] 容器 " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                        + " 已无未兑付仓单，恢复开放"), false);
                    }
                }
            } else {
                NO_CLAIM.remove(k);
            }
            return;
        }
        StoreSeals.clear(pos);
        StoreLocks.unlock(pos);
        LockedStoreBreakHandler.voidReceipts(server, pos);
        System.out.println("[TangtuEconomical] 封条巡检失效 " + pos + "（内容已变）");
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§c[仓单] 容器 " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                            + " 内容被改动，相关仓单已作废"), false);
            String diff = describeDiff(row.seal(), actual);
            if (!diff.isEmpty()) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "§7变化：" + diff), false);
            }
        }
    }
}
