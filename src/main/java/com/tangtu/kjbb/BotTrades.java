package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 机器人成交流水：config/kjbb/bot_trades.txt
 * 格式：时间|买方|卖方|物品|数量|单价|总额|类型
 */
public final class BotTrades {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss");

    private BotTrades() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("bot_trades.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_bot_trades.txt");
        }
    }

    public static synchronized void log(String buyer, String seller, String itemId,
                                        int count, long price, String kind) {
        String line = FMT.format(new Date()) + "|" + buyer + "|" + seller + "|" + itemId
                + "|" + count + "|" + price + "|" + (price * count) + "|" + kind + "\n";
        try {
            Files.writeString(file(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写成交流水失败: " + t);
        }
    }

    /** 最近 n 条（倒序） */
    public static synchronized java.util.List<String> tail(int n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Path p = file();
            if (!Files.exists(p)) {
                return out;
            }
            var all = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) {
                if (!all.get(i).isBlank()) {
                    out.add(all.get(i));
                }
            }
        } catch (Throwable t) {
            // 忽略
        }
        return out;
    }
}
