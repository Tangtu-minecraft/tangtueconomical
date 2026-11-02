package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易托管（货到付款）。
 *
 * 买家下单 → 扣款 → 记一条 PENDING（卖家分配明细）
 * 买家收货箱里出现该物品 → KubeJS 调 release() → 钱转给卖家
 * 超时未到货 → KubeJS 调 expire() → 钱退回买家
 *
 * 存储：config/kjbb/shop_escrow.txt
 *   id|buyer|itemId|count|total|state|created|seller:money,seller:money
 */
public final class ShopEscrow {

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static long nextId = 1L;
    private static boolean loaded = false;
    private static Path filePath;

    private ShopEscrow() {
    }

    private static final class Entry {
        long id;
        String buyer;
        String itemId;
        long count;
        long total;
        String state = "PENDING";
        long created;
        Map<String, Long> payouts = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();   // 卖家 → 件数（退款要还账本）
    }

    private static Path file() {
        if (filePath == null) {
            try {
                Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
                Files.createDirectories(dir);
                filePath = dir.resolve("shop_escrow.txt");
            } catch (Throwable t) {
                filePath = Path.of("kjbb_shop_escrow.txt");
            }
        }
        return filePath;
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        ENTRIES.clear();
        nextId = 1L;
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
                if (f.length < 7) {
                    continue;
                }
                try {
                    Entry e = new Entry();
                    e.id = Long.parseLong(f[0].trim());
                    e.buyer = f[1].trim();
                    e.itemId = f[2].trim();
                    e.count = Long.parseLong(f[3].trim());
                    e.total = Long.parseLong(f[4].trim());
                    e.state = f[5].trim();
                    e.created = Long.parseLong(f[6].trim());
                    if (f.length > 8 && !f[8].trim().isEmpty()) {
                        for (String pair : f[8].split(",")) {
                            int idx2 = pair.lastIndexOf(':');
                            if (idx2 <= 0) {
                                continue;
                            }
                            long c = Long.parseLong(pair.substring(idx2 + 1).trim());
                            if (c > 0L) {
                                e.counts.put(pair.substring(0, idx2).trim(), c);
                            }
                        }
                    }
                    if (f.length > 7 && !f[7].trim().isEmpty()) {
                        for (String pair : f[7].split(",")) {
                            int idx = pair.lastIndexOf(':');
                            if (idx <= 0) {
                                continue;
                            }
                            String seller = pair.substring(0, idx).trim();
                            long money = Long.parseLong(pair.substring(idx + 1).trim());
                            if (money > 0L) {
                                e.payouts.put(seller, money);
                            }
                        }
                    }
                    ENTRIES.add(e);
                    if (e.id >= nextId) {
                        nextId = e.id + 1L;
                    }
                } catch (Throwable ignored) {
                    // 跳过坏行
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读托管失败: " + t);
        }
    }

    public static synchronized void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("# id|buyer|itemId|count|total|state|created|payouts\n");
        for (Entry e : ENTRIES) {
            StringBuilder pay = new StringBuilder();
            for (Map.Entry<String, Long> en : e.payouts.entrySet()) {
                if (pay.length() > 0) {
                    pay.append(',');
                }
                pay.append(en.getKey()).append(':').append(en.getValue());
            }
            StringBuilder cnt = new StringBuilder();
            for (Map.Entry<String, Long> en : e.counts.entrySet()) {
                if (cnt.length() > 0) {
                    cnt.append(',');
                }
                cnt.append(en.getKey()).append(':').append(en.getValue());
            }
            sb.append(e.id).append('|').append(e.buyer).append('|').append(e.itemId)
                    .append('|').append(e.count).append('|').append(e.total)
                    .append('|').append(e.state).append('|').append(e.created)
                    .append('|').append(pay).append('|').append(cnt).append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写托管失败: " + t);
        }
    }

    /** 新建托管，返回 id（<=0 表示失败） */
    public static synchronized long create(String buyer, String itemId, long count,
                                           long total, Map<String, Long> payouts,
                                           Map<String, Long> counts) {
        if (buyer == null || buyer.isEmpty() || itemId == null || itemId.isEmpty()
                || count <= 0L || total <= 0L) {
            return -1L;
        }
        load();
        Entry e = new Entry();
        e.id = nextId++;
        e.buyer = buyer;
        e.itemId = itemId;
        e.count = count;
        e.total = total;
        e.state = "PENDING";
        e.created = ServerClock.now();
        if (payouts != null) {
            e.payouts.putAll(payouts);
        }
        if (counts != null) {
            e.counts.putAll(counts);
        }
        ENTRIES.add(e);
        save();
        return e.id;
    }

    /** 给 KubeJS 的快照：id|buyer|itemId|count|total|ageMs|state ; 多条用 \n 分隔 */
    public static synchronized String dumpPending() {
        load();
        StringBuilder sb = new StringBuilder();
        long now = ServerClock.now();
        for (Entry e : ENTRIES) {
            if (!"PENDING".equals(e.state)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.id).append('|').append(e.buyer).append('|').append(e.itemId)
                    .append('|').append(e.count).append('|').append(e.total)
                    .append('|').append(now - e.created).append('|').append(e.state);
        }
        return sb.toString();
    }

    /** 到货：把钱给卖家 */
    public static synchronized boolean release(long id) {
        load();
        for (Entry e : ENTRIES) {
            if (e.id != id || !"PENDING".equals(e.state)) {
                continue;
            }
            long paid = 0L;
            for (Map.Entry<String, Long> en : e.payouts.entrySet()) {
                long got = BankBridge.addMoney(en.getKey(), en.getValue());
                if (got >= 0L) {
                    paid += en.getValue();
                } else {
                    System.out.println("[TangtuEconomical] 放款失败 " + en.getKey() + " " + en.getValue());
                }
            }
            e.state = "RELEASED";
            save();
            System.out.println("[TangtuEconomical] 托管放款 #" + id + " 给卖家 " + paid + " 唐兔币");
            return true;
        }
        return false;
    }

    /** 超时/失败：退回买家 */
    public static synchronized boolean refund(long id, String reason) {
        load();
        for (Entry e : ENTRIES) {
            if (e.id != id || !"PENDING".equals(e.state)) {
                continue;
            }
            long got = BankBridge.addMoney(e.buyer, e.total);
            // ★ 把当初从账本扣掉的件数还回去，否则卖家永久失去索赔权
            long price = ShopPrices.price(e.itemId);
            if (price <= 0L) {
                price = 1L;
            }
            int restored = 0;
            for (Map.Entry<String, Long> en : e.counts.entrySet()) {
                if (ShopLedger.deposit(en.getKey(), e.itemId, en.getValue(), price)) {
                    restored++;
                }
            }
            e.state = "REFUNDED";
            save();
            if (restored > 0) {
                System.out.println("[TangtuEconomical] 退款归还账本 #" + id + "：" + e.counts);
            }
            System.out.println("[TangtuEconomical] 托管退款 #" + id + " 给 " + e.buyer
                    + " " + e.total + " 唐兔币（" + reason + "，余额 " + got + "）");
            return true;
        }
        return false;
    }

    /** 把所有超过 maxAgeMs 的 PENDING 退款，返回处理条数 */
    public static synchronized int expire(long maxAgeMs) {
        load();
        long now = ServerClock.now();
        List<Long> ids = new ArrayList<>();
        for (Entry e : ENTRIES) {
            if (!"PENDING".equals(e.state)) {
                continue;
            }
            if (now - e.created > maxAgeMs) {
                ids.add(e.id);
            }
        }
        int n = 0;
        for (Long id : ids) {
            if (refund(id, "超时未到货")) {
                n++;
            }
        }
        return n;
    }

    public static synchronized int pendingCount() {
        load();
        int n = 0;
        for (Entry e : ENTRIES) {
            if ("PENDING".equals(e.state)) {
                n++;
            }
        }
        return n;
    }

    public static synchronized void reloadFromDisk() {
        loaded = false;
        load();
    }

    public static String pathString() {
        return file().toAbsolutePath().toString();
    }
}
