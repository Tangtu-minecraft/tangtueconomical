package com.tangtu.kjbb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 投递记录：谁把什么发给了谁、走的是物流还是排队。
 *
 * 文件：config/kjbb/mail_log.txt（只保留最近 200 条）
 * 格式：时间|卖家|买家|物品|数量|方式
 */
public final class MailLog {

    private static final int MAX = 200;

    private MailLog() {
    }

    private static File file() {
        try {
            var dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("kjbb");
            java.nio.file.Files.createDirectories(dir);
            return dir.resolve("mail_log.txt").toFile();
        } catch (Throwable t) {
            return new File("config/kjbb/mail_log.txt");
        }
    }

    public static synchronized void add(String seller, String buyer, String itemId,
            int count, String mode) {
        List<String> lines = read();
        String ts = String.format("%tF %tR", System.currentTimeMillis(),
                System.currentTimeMillis());
        lines.add(ts + "|" + seller + "|" + buyer + "|" + itemId + "|" + count + "|" + mode);
        while (lines.size() > MAX) {
            lines.remove(0);
        }
        write(lines);
        try {
            DebugLog.log("投递：" + seller + " → " + buyer + " " + itemId + " ×" + count
                    + "（" + mode + "）");
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 最近的记录（按时间正序） */
    public static synchronized List<String> recent(int n) {
        List<String> all = read();
        int from = Math.max(0, all.size() - Math.max(1, n));
        return new ArrayList<>(all.subList(from, all.size()));
    }

    private static List<String> read() {
        List<String> out = new ArrayList<>();
        File f = file();
        if (!f.exists()) {
            return out;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    out.add(line);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return out;
    }

    private static void write(List<String> lines) {
        try (PrintWriter pw = new PrintWriter(file(), "UTF-8")) {
            pw.println("# time|seller|buyer|item|count|mode");
            for (String l : lines) {
                pw.println(l);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }
}