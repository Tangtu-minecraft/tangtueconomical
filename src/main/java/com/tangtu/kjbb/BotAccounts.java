package com.tangtu.kjbb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 机器人名册：所有「不是玩家」的账户都登记在这里。
 *
 * 目的：以后新增任何机器人，只要 register() 一次，
 * Ledger.isBot / EconReport 就都认得它，不会再被当成玩家。
 *
 * 文件：config/kjbb/bot_accounts.txt（一行一个名字）
 */
public final class BotAccounts {

    private static final Set<String> NAMES = new LinkedHashSet<>();
    private static boolean LOADED = false;

    private BotAccounts() {
    }

    private static File file() {
        try {
            var dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("kjbb");
            java.nio.file.Files.createDirectories(dir);
            return dir.resolve("bot_accounts.txt").toFile();
        } catch (Throwable t) {
            return new File("config/kjbb/bot_accounts.txt");
        }
    }

    public static synchronized void load() {
        if (LOADED) {
            return;
        }
        LOADED = true;
        // ① 配置里的机器人
        try {
            for (var b : BotConfig.get().bots) {
                if (b != null && b.name != null && !b.name.isEmpty()) {
                    NAMES.add(b.name);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // ② 系统账户（不在配置里，但确定是机器人）
        try {
            NAMES.add(com.tangtu.kjbb.VoyageBill.issuerName());
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            NAMES.add(com.tangtu.kjbb.RomaMarket.NAME);
        } catch (Throwable ignored) {
            // ignore
        }
        NAMES.add("唐兔国航运公司");
        NAMES.add("罗马国");
        // ③ 文件里已登记的
        File f = file();
        if (f.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        NAMES.add(line);
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        // ④ 账本里已经存在的
        try {
            NAMES.addAll(com.tangtu.kjbb.BotLedger.allNames());
        } catch (Throwable ignored) {
            // ignore
        }
        save();
    }

    public static synchronized void save() {
        try (PrintWriter pw = new PrintWriter(file(), "UTF-8")) {
            pw.println("# 机器人账户名单（一行一个）");
            for (String n : NAMES) {
                pw.println(n);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 新机器人上线时调用一次 */
    public static synchronized void register(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        load();
        if (NAMES.add(name)) {
            save();
            DebugLog.log("机器人名册新增：" + name);
        }
    }

    public static synchronized boolean isBot(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        load();
        return NAMES.contains(name);
    }

    public static synchronized Set<String> all() {
        load();
        return new LinkedHashSet<>(NAMES);
    }
}