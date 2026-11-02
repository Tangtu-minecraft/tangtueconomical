package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志文件维护：超过上限自动截断，只保留后一半。
 *
 * 涉及：bot_trades / retail_trades / issuance / labor_bots
 */
public final class LogHousekeeping {

    private static final int MAX_LINES = 5000;

    private LogHousekeeping() {
    }

    /** 每 100 tick 调一次；内部按次数节流（约 5 分钟执行一轮） */
    private static int counter = 0;

    public static void tick() {
        if (++counter < 60) {
            return;
        }
        counter = 0;
        Path dir;
        try {
            dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            if (!Files.isDirectory(dir)) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        for (String name : new String[]{"bot_trades.txt", "retail_trades.txt",
                "issuance.txt", "_debug_retail.txt"}) {
            trim(dir.resolve(name));
        }
    }

    private static void trim(Path f) {
        try {
            if (!Files.exists(f)) {
                return;
            }
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.size() <= MAX_LINES) {
                return;
            }
            List<String> keep = new ArrayList<>();
            keep.add("# 已截断，保留最近 " + (MAX_LINES / 2) + " 行");
            keep.addAll(lines.subList(lines.size() - MAX_LINES / 2, lines.size()));
            Files.write(f, keep, StandardCharsets.UTF_8);
            System.out.println("[TangtuEconomical] 日志截断: " + f.getFileName()
                    + " " + lines.size() + " -> " + keep.size());
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
