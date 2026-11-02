package com.tangtu.kjbb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件日志：把关键经济运行写进 config/kjbb/economy_debug.txt
 *
 * 为什么不用 System.out：
 *   不同启动器/日志配置下，stdout 可能不进 latest.log，
 *   排查时拿不到任何线索。写文件最可靠。
 */
public final class DebugLog {

    private DebugLog() {
    }

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT);
    private static final long MAX_BYTES = 2L * 1024 * 1024;   // 2MB 上限
    private static long written = 0L;

    private static Path file() {
        return Path.of("config", "kjbb", "economy_debug.txt");
    }

    public static synchronized void log(String msg) {
        if (msg == null) {
            return;
        }
        try {
            Path p = file();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            // 超过上限：保留后半段（简单截断）
            if (written > MAX_BYTES && Files.exists(p)) {
                var lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                int keepFrom = Math.max(0, lines.size() / 2);
                Files.write(p, lines.subList(keepFrom, lines.size()), StandardCharsets.UTF_8);
                written = 0L;
            }
            String line = FMT.format(new Date()) + "  " + msg + System.lineSeparator();
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            Files.write(p, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            written += bytes.length;
        } catch (IOException ignored) {
            // ignore
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
