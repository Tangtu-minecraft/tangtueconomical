package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;

/**
 * 仓单仓储管理费
 *
 * 规则：
 *   · 只要容器上有封条（= 有仓单存续），每个游戏日收一次管理费
 *   · 费用 = 容器内货物估值 × 费率（默认 5‰，最低 1 唐兔币）
 *   · 欠费超过宽限日数 → 封条清除、容器解锁（货留在箱子里，谁都能拿）
 *
 * 目的：
 *   让「长期锁箱占位」有成本，与现实中的仓储费一致。
 */
public final class StorageFee {

    private StorageFee() {
    }

    /** 每游戏日的费率（千分比） */
    public static int FEE_PERMILLE = 5;
    /** 欠费宽限天数（超过就解封） */
    public static int GRACE_DAYS = 3;

    private static long lastDay = -1L;
    /** 欠费天数累计：owner + 坐标 → 天数 */
    private static final java.util.Map<String, Integer> ARREARS = new java.util.LinkedHashMap<>();

    private static java.nio.file.Path stateFile() {
        return java.nio.file.Path.of("config", "kjbb", "storage_fee.txt");
    }

    private static void load() {
        try {
            var p = stateFile();
            if (!java.nio.file.Files.exists(p)) {
                return;
            }
            for (String line : java.nio.file.Files.readAllLines(p,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|");
                if (f.length >= 2) {
                    ARREARS.put(f[0], Integer.parseInt(f[1].trim()));
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void save() {
        try {
            var p = stateFile();
            if (p.getParent() != null) {
                java.nio.file.Files.createDirectories(p.getParent());
            }
            StringBuilder sb = new StringBuilder("# key|days\n");
            for (var e : ARREARS.entrySet()) {
                sb.append(e.getKey()).append('|').append(e.getValue()).append('\n');
            }
            java.nio.file.Files.writeString(p, sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 计时（每 5 秒调一次，内部按游戏日结算） */
    public static synchronized void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            var cfg = BotConfig.get();
            FEE_PERMILLE = Math.max(0, cfg.storageFeePermille);
            GRACE_DAYS = Math.max(1, cfg.storageFeeGraceDays);
        } catch (Throwable ignored) {
            // ignore
        }
        if (FEE_PERMILLE <= 0) {
            return;
        }
        long day = server.overworld() == null ? 0L : server.overworld().getDayTime() / 24000L;
        if (lastDay < 0L) {
            lastDay = day;
            load();
            return;
        }
        long days = Math.min(30L, day - lastDay);
        if (days <= 0L) {
            return;
        }
        lastDay = day;
        charge(server, days);
        save();
    }

    private static void charge(MinecraftServer server, long days) {
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        int charged = 0;
        long total = 0L;
        for (var row : StoreSeals.all()) {
            BlockPos pos = new BlockPos(row.x(), row.y(), row.z());
            if (!level.isLoaded(pos)) {
                continue;
            }
            var lock = StoreLocks.get(pos);
            if (lock == null || lock.owner() == null || lock.owner().isEmpty()
                    || "__MARKET__".equals(lock.owner())) {
                continue;   // 市场托管中不收（等国民收货）
            }
            var h = TickerStock.handlerAt(level, pos);
            long worth = ItemValuation.handlerWorth(h);
            if (worth <= 0L) {
                continue;
            }
            long fee = Math.max(1L, worth * FEE_PERMILLE / 1000L * days);
            String key = lock.owner() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
            long have = 0L;
            try {
                have = BankBridge.money(lock.owner());
            } catch (Throwable ignored) {
                // ignore
            }
            if (have >= fee) {
                try {
                    BankBridge.takeMoney(lock.owner(), fee);
                    total += fee;
                    charged++;
                    ARREARS.remove(key);
                    BotTrades.log(lock.owner(), "-", "\u4ed3\u50a8\u7ba1\u7406\u8d39",
                            1, fee, "\u4ed3\u50a8\u8d39");
                } catch (Throwable ignored) {
                    // ignore
                }
            } else {
                int arrears = ARREARS.getOrDefault(key, 0) + (int) days;
                ARREARS.put(key, arrears);
                System.out.println("[TangtuEconomical] \u4ed3\u50a8\u8d39\u6b20\u8d39 " + key
                        + " \u5df2\u6b20 " + arrears + " \u65e5");
                if (arrears >= GRACE_DAYS) {
                    // 解封：货留在箱子里
                    try {
                        StoreSeals.clear(pos);
                        StoreLocks.unlock(pos);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    ARREARS.remove(key);
                    notify(server, lock.owner(),
                            "\u00a7c[\u4ed3\u50a8] \u4f60\u7684\u4ed3\u5355\u56e0\u6b20\u7f34\u7ba1\u7406\u8d39\u88ab\u89e3\u9664\uff0c"
                                    + "\u5bb9\u5668 " + pos.getX() + ", " + pos.getY() + ", "
                                    + pos.getZ() + " \u5df2\u89e3\u9501\uff08\u8d27\u7269\u4ecd\u5728\u91cc\u9762\uff09");
                    System.out.println("[TangtuEconomical] \u4ed3\u50a8\u8d39\u6b20\u8d39\u89e3\u5c01 " + key);
                }
            }
        }
        if (charged > 0) {
            System.out.println("[TangtuEconomical] \u4ed3\u50a8\u7ba1\u7406\u8d39\uff1a" + charged
                    + " \u4e2a\u5bb9\u5668\uff0c\u5408\u8ba1 " + total + " \u5510\u5154\u5e01");
        }
    }

    private static void notify(MinecraftServer server, String owner, String msg) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (owner != null && owner.equalsIgnoreCase(sp.getScoreboardName())) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), false);
            }
        }
    }
}
