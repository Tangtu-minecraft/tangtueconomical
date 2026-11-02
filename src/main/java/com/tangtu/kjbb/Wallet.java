package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自建钱包 + 物品账户（替代 banksystem mod）。
 *
 * config/kjbb/wallet.txt
 *   playerName|money|itemId=count;itemId=count
 *
 * 名字一律用计分板名（我们全链路都用它）。
 */
public final class Wallet {

    private static final Map<String, Long> MONEY = new LinkedHashMap<>();
    private static final Map<String, Map<String, Long>> ITEMS = new LinkedHashMap<>();
    private static boolean loaded = false;

    private Wallet() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("wallet.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_wallet.txt");
        }
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path p = file();
        if (!Files.exists(p)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line == null || line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length < 2) {
                    continue;
                }
                String name = f[0].trim();
                try {
                    MONEY.put(name, Long.parseLong(f[1].trim()));
                } catch (Throwable ignored) {
                    // 跳过
                }
                Map<String, Long> m = new LinkedHashMap<>();
                if (f.length > 2 && !f[2].isEmpty()) {
                    for (String part : f[2].split(";")) {
                        int eq = part.lastIndexOf('=');
                        if (eq <= 0) {
                            continue;
                        }
                        try {
                            m.put(part.substring(0, eq).trim(),
                                    Long.parseLong(part.substring(eq + 1).trim()));
                        } catch (Throwable ignored) {
                            // 跳过
                        }
                    }
                }
                ITEMS.put(name, m);
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读钱包失败: " + t);
        }
    }

    public static synchronized void flushNow() {
        StringBuilder sb = new StringBuilder("# player|money|itemId=count;...\n");
        for (var e : MONEY.entrySet()) {
            sb.append(e.getKey()).append('|').append(e.getValue()).append('|');
            Map<String, Long> m = ITEMS.get(e.getKey());
            int n = 0;
            if (m != null) {
                for (var it : m.entrySet()) {
                    if (it.getValue() <= 0) {
                        continue;
                    }
                    if (n++ > 0) {
                        sb.append(';');
                    }
                    sb.append(it.getKey()).append('=').append(it.getValue());
                }
            }
            sb.append('\n');
        }
        try {
            SafeFile.write(file(), sb.toString());
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写钱包失败: " + t);
        }
    }

    /** 标脏：真正写盘由 DirtySave 每 saveIntervalTicks 批量执行 */
    public static synchronized void save() {
        DirtySave.mark("wallet");
    }

    static {
        DirtySave.register("wallet", Wallet::flushNow);
    }


    /** 只读旧钱包 map（不经过统一账户），迁移用 */
    public static synchronized long legacyMoney(String name) {
        load();
        return MONEY.getOrDefault(name, 0L);
    }

    /** 旧钱包里是否有这个人（不管金额） */
    public static synchronized boolean legacyHas(String name) {
        load();
        return MONEY.containsKey(name);
    }

    public static synchronized long money(String name) {
        try {
            return com.tangtu.kjbb.Accounts.money(name);
        } catch (Throwable ignored) {
            load();
            return MONEY.getOrDefault(name, 0L);
        }
    }

    public static synchronized long addMoney(String name, long amount) {
        if (!com.tangtu.kjbb.Accounts.isLegacyWrite() && amount > 0) {
            com.tangtu.kjbb.Accounts.credit(name, amount);
            return com.tangtu.kjbb.Accounts.money(name);
        }
        load();
        if (amount <= 0) {
            return money(name);
        }
        long v = MONEY.getOrDefault(name, 0L) + amount;
        MONEY.put(name, v);
        save();
        return v;
    }

    public static synchronized long takeMoney(String name, long amount) {
        if (!com.tangtu.kjbb.Accounts.isLegacyWrite() && amount > 0) {
            long got = com.tangtu.kjbb.Accounts.take(name, amount);
            return got >= amount ? com.tangtu.kjbb.Accounts.money(name) : -1L;
        }
        load();
        if (amount <= 0) {
            return -1L;
        }
        long cur = MONEY.getOrDefault(name, 0L);
        if (cur < amount) {
            return -1L;
        }
        long v = cur - amount;
        MONEY.put(name, v);
        save();
        return v;
    }

    public static synchronized long items(String name, String itemId) {
        load();
        Map<String, Long> m = ITEMS.get(name);
        return m == null ? 0L : m.getOrDefault(itemId, 0L);
    }

    public static synchronized long addItems(String name, String itemId, long count) {
        load();
        if (count <= 0) {
            return items(name, itemId);
        }
        Map<String, Long> m = ITEMS.computeIfAbsent(name, k -> new LinkedHashMap<>());
        long v = m.getOrDefault(itemId, 0L) + count;
        m.put(itemId, v);
        save();
        return v;
    }

    public static synchronized long takeItems(String name, String itemId, long count) {
        load();
        if (count <= 0) {
            return -1L;
        }
        Map<String, Long> m = ITEMS.get(name);
        if (m == null) {
            return -1L;
        }
        long cur = m.getOrDefault(itemId, 0L);
        if (cur < count) {
            return -1L;
        }
        long v = cur - count;
        if (v <= 0) {
            m.remove(itemId);
        } else {
            m.put(itemId, v);
        }
        save();
        return v;
    }

    public static synchronized String accounts(String name) {
        load();
        Map<String, Long> m = ITEMS.get(name);
        if (m == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var e : m.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static synchronized long circulation() {
        load();
        long sum = 0;
        for (long v : MONEY.values()) {
            sum += v;
        }
        return sum;
    }

    public static synchronized java.util.List<String> names() {
        load();
        return new java.util.ArrayList<>(MONEY.keySet());
    }

    /** 删除一个账户（余额与物品一起移除），返回是否删除成功 */
    public static synchronized boolean remove(String name) {
        load();
        if (name == null || name.isEmpty()) {
            return false;
        }
        boolean had = MONEY.remove(name) != null;
        ITEMS.remove(name);
        if (had) {
            save();
        }
        return had;
    }
}
