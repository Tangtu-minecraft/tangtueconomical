package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 发行记录：config/kjbb/issuance.txt */
public final class IssuanceLog {

    private IssuanceLog() {
    }

    public static synchronized void log(String kind, String target, long amount, String by) {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date())
                    + "|" + by + "|" + kind + "|" + target + "|" + amount + "\n";
            Files.writeString(dir.resolve("issuance.txt"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
