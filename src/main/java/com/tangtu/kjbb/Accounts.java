package com.tangtu.kjbb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一账户层（第一阶段：统一读写 + 双写兼容）。
 *
 * 以前分裂成 wallet.txt（玩家）和 bot_ledger.txt（机器人），
 * 导致「查错账本」「机器人被当玩家」等一串 bug。
 *
 * 现在 accounts.txt 是唯一真相：
 *   name|bot|money|item=count;item=count;...
 *
 * 第一阶段为了兼容，写入时仍然同步写一份旧的 wallet.txt / bot_ledger.txt；
 * 等所有读取点切换过来后再停掉旧文件。
 */
public final class Accounts {

    public static final class Account {
        public String name;
        public boolean bot;
        public long money;
        public Map<String, Integer> stock = new LinkedHashMap<>();
    }

    private static final Map<String, Account> MAP = new LinkedHashMap<>();
    private static boolean LOADED = false;
    /** 是否同时写旧账本（阶段三起默认关闭：accounts.txt 是唯一真相） */
    public static volatile boolean dualWriteLegacy = false;
    /** 正在写旧账本时置 true，避免旧账本回调造成递归 */
    public static volatile boolean legacyWrite = false;

    public static boolean isLegacyWrite() {
        return legacyWrite;
    }

    private Accounts() {
    }

    private static File file() {
        try {
            var dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("kjbb");
            java.nio.file.Files.createDirectories(dir);
            return dir.resolve("accounts.txt").toFile();
        } catch (Throwable t) {
            return new File("config/kjbb/accounts.txt");
        }
    }

    public static synchronized void load() {
        if (LOADED) {
            return;
        }
        LOADED = true;
        File f = file();
        if (f.exists()) {
            parse(f);
            // 与旧账本对齐：补上「旧账本有、统一账户没有」的钱与货
            int fixed = alignLegacy();
            if (fixed > 0) {
                save();
                DebugLog.log("统一账户：从旧账本补全 " + fixed + " 项");
            }
            DebugLog.log("统一账户：已载入 " + MAP.size() + " 个账户");
            return;
        }
        // 首次运行：从旧的两套账迁移
        migrateLegacy();
        save();
        DebugLog.log("统一账户：首次迁移 " + MAP.size() + " 个账户");
    }

    private static void parse(File f) {
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\|", 4);
                if (p.length < 3) {
                    continue;
                }
                Account a = new Account();
                a.name = p[0];
                a.bot = "1".equals(p[1].trim());
                try {
                    a.money = Long.parseLong(p[2].trim());
                } catch (Throwable t) {
                    a.money = 0L;
                }
                if (p.length >= 4 && !p[3].isBlank()) {
                    for (String kv : p[3].split(";")) {
                        int i = kv.indexOf('=');
                        if (i <= 0) {
                            continue;
                        }
                        try {
                            a.stock.put(kv.substring(0, i), Integer.parseInt(kv.substring(i + 1)));
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                }
                MAP.put(a.name, a);
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读统一账户失败: " + t);
        }
    }

    private static void migrateLegacy() {
        // ① 玩家钱包
        try {
            java.nio.file.Path wp = net.neoforged.fml.loading.FMLPaths.CONFIGDIR
                    .get().resolve("kjbb").resolve("wallet.txt");
            if (java.nio.file.Files.exists(wp)) {
                for (String line : java.nio.file.Files.readAllLines(wp)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] p = line.split("\\|");
                    if (p.length < 2) {
                        continue;
                    }
                    Account a = getOrCreate(p[0], false);
                    try {
                        a.money = Long.parseLong(p[1].trim());
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        } finally {
            legacyWrite = false;
        }
        // ② 机器人账本
        try {
            java.nio.file.Path bp = net.neoforged.fml.loading.FMLPaths.CONFIGDIR
                    .get().resolve("kjbb").resolve("bot_ledger.txt");
            if (java.nio.file.Files.exists(bp)) {
                for (String line : java.nio.file.Files.readAllLines(bp)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] p = line.split("\\|", 3);
                    if (p.length < 2) {
                        continue;
                    }
                    Account a = getOrCreate(p[0], true);
                    a.bot = true;
                    try {
                        a.money = Long.parseLong(p[1].trim());
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    if (p.length >= 3 && !p[2].isBlank()) {
                        for (String kv : p[2].split(";")) {
                            int i = kv.indexOf('=');
                            if (i <= 0) {
                                continue;
                            }
                            try {
                                a.stock.put(kv.substring(0, i), Integer.parseInt(kv.substring(i + 1)));
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        } finally {
            legacyWrite = false;
        }
        // ③ 名册里的机器人（可能是 0 余额）
        try {
            for (String n : BotAccounts.all()) {
                Account a = getOrCreate(n, true);
                a.bot = true;
            }
        } catch (Throwable ignored) {
            // ignore
        } finally {
            legacyWrite = false;
        }
    }

    /**
     * 与旧账本对齐：把 legacy 里有、统一账户里缺的钱和库存补进来。
     * 返回修正的项数。
     */
    private static int alignLegacy() {
        int fixed = 0;
        try {
            java.nio.file.Path bp = net.neoforged.fml.loading.FMLPaths.CONFIGDIR
                    .get().resolve("kjbb").resolve("bot_ledger.txt");
            if (java.nio.file.Files.exists(bp)) {
                for (String line : java.nio.file.Files.readAllLines(bp)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] q = line.split("\\|", 3);
                    if (q.length < 2) {
                        continue;
                    }
                    Account a = getOrCreate(q[0], true);
                    a.bot = true;
                    long lm = 0L;
                    try {
                        lm = Long.parseLong(q[1].trim());
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    // 只在统一账户没有钱时才从旧账本补（不取最大值，避免把膨胀值带进来）
                    if (a.money <= 0L && lm > 0L) {
                        a.money = lm;
                        fixed++;
                    }
                    if (q.length >= 3 && !q[2].isBlank()) {
                        for (String kv : q[2].split(";")) {
                            int i = kv.indexOf('=');
                            if (i <= 0) {
                                continue;
                            }
                            try {
                                String id = kv.substring(0, i);
                                int n = Integer.parseInt(kv.substring(i + 1));
                                if (a.stock.getOrDefault(id, 0) <= 0 && n > 0) {
                                    a.stock.put(id, n);
                                    fixed++;
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            java.nio.file.Path wp = net.neoforged.fml.loading.FMLPaths.CONFIGDIR
                    .get().resolve("kjbb").resolve("wallet.txt");
            if (java.nio.file.Files.exists(wp)) {
                for (String line : java.nio.file.Files.readAllLines(wp)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] q = line.split("\\|");
                    if (q.length < 2) {
                        continue;
                    }
                    Account a = getOrCreate(q[0], false);
                    long lm = 0L;
                    try {
                        lm = Long.parseLong(q[1].trim());
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    if (a.money <= 0L && lm > 0L) {
                        a.money = lm;
                        fixed++;
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return fixed;
    }

    private static Account getOrCreate(String name, boolean bot) {
        Account a = MAP.get(name);
        if (a == null) {
            a = new Account();
            a.name = name;
            a.bot = bot;
            MAP.put(name, a);
        }
        return a;
    }

    public static synchronized void save() {
        try (PrintWriter pw = new PrintWriter(file(), "UTF-8")) {
            pw.println("# name|bot|money|item=count;...");
            for (Account a : MAP.values()) {
                StringBuilder sb = new StringBuilder();
                sb.append(a.name).append('|')
                        .append(a.bot ? "1" : "0").append('|')
                        .append(a.money).append('|');
                boolean first = true;
                for (var e : a.stock.entrySet()) {
                    if (e.getValue() == null || e.getValue() == 0) {
                        continue;
                    }
                    if (!first) {
                        sb.append(';');
                    }
                    sb.append(e.getKey()).append('=').append(e.getValue());
                    first = false;
                }
                pw.println(sb);
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写统一账户失败: " + t);
        }
    }

    /** 账户是不是机器人 */
    public static synchronized boolean isBot(String name) {
        load();
        Account a = MAP.get(name);
        if (a != null) {
            return a.bot;
        }
        try {
            return BotAccounts.isBot(name);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static synchronized long money(String name) {
        load();
        Account a = MAP.get(name);
        return a == null ? 0L : a.money;
    }

    /** 给钱：统一账本 + 双写旧文件（兼容期） */
    public static synchronized void credit(String name, long amount) {
        if (name == null || name.isEmpty() || amount == 0L) {
            return;
        }
        load();
        Account a = getOrCreate(name, BotAccounts.isBot(name));
        a.money += amount;
        save();
        // 双写：让还没切换的旧读取点仍然看得到
        try {
            if (!dualWriteLegacy) {
                return;
            }
            legacyWrite = true;
            if (a.bot) {
                BotLedger.addMoney(name, amount);
            } else {
                Wallet.addMoney(name, amount);
            }
        } catch (Throwable ignored) {
            // ignore
        } finally {
            legacyWrite = false;
        }
    }

    private static void endLegacy() {
        legacyWrite = false;
    }

    /** 扣钱：返回实际扣到的金额 */
    public static synchronized long take(String name, long amount) {
        if (name == null || name.isEmpty() || amount <= 0L) {
            return 0L;
        }
        load();
        Account a = getOrCreate(name, BotAccounts.isBot(name));
        long take = Math.min(a.money, amount);
        if (take <= 0L) {
            return 0L;
        }
        a.money -= take;
        save();
        try {
            if (!dualWriteLegacy) {
                return take;
            }
            legacyWrite = true;
            if (a.bot) {
                BotLedger.addMoney(name, -take);
            } else {
                Wallet.takeMoney(name, take);
            }
        } catch (Throwable ignored) {
            // ignore
        } finally {
            legacyWrite = false;
        }
        return take;
    }

    /** 库存 */
    public static synchronized int stock(String name, String itemId) {
        load();
        Account a = MAP.get(name);
        if (a == null) {
            return 0;
        }
        Integer v = a.stock.get(itemId);
        return v == null ? 0 : v;
    }

    public static synchronized void addStock(String name, String itemId, int count) {
        if (name == null || itemId == null || count == 0) {
            return;
        }
        load();
        Account a = getOrCreate(name, true);
        int now = a.stock.getOrDefault(itemId, 0) + count;
        if (now <= 0) {
            a.stock.remove(itemId);
        } else {
            a.stock.put(itemId, now);
        }
        save();
        try {
            if (!dualWriteLegacy) {
                return;
            }
            legacyWrite = true;
            BotLedger.addStock(name, itemId, count);
        } catch (Throwable ignored) {
            // ignore
        } finally {
            legacyWrite = false;
        }
    }

    /** 所有账户名（审计用） */
    public static synchronized java.util.Set<String> names() {
        load();
        return new java.util.LinkedHashSet<>(MAP.keySet());
    }

    /** 账户快照（/billaccounts 用） */
    public static synchronized String dump() {
        load();
        StringBuilder sb = new StringBuilder("§6===== 统一账户（" + MAP.size() + "）=====\n");
        long totalBot = 0L, totalPlayer = 0L;
        for (Account a : MAP.values()) {
            if (a.bot) {
                totalBot += a.money;
            } else {
                totalPlayer += a.money;
            }
            sb.append("§7").append(a.bot ? "§b[机] " : "§f[人] ")
                    .append(a.name).append(" §f").append(a.money);
            if (!a.stock.isEmpty()) {
                sb.append(" §8(").append(a.stock.size()).append(" 种货)");
            }
            sb.append('\n');
        }
        sb.append("§7合计：人 ").append(totalPlayer).append(" / 机 ").append(totalBot);
        return sb.toString();
    }
}