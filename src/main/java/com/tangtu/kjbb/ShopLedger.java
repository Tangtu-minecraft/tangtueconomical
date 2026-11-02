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
 * 交易挂牌账本（卖家定价）。
 *
 * 入口：玩家把货交进商店库存时，记录 谁挂了多少个、每个要多少钱。
 *
 * 撮合规则：**价格优先 → 时间优先**（跟真实交易所一样）
 *   买家要 64 个，先从最便宜的挂牌吃起，同价按挂得早的优先。
 *
 * 结算：买家按实际吃到的价格付钱，钱全部给卖家（系统不经手）。
 *
 * 存储：config/kjbb/shop_ledger.txt
 *   seq|itemId|seller|count|price
 */
public final class ShopLedger {

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static long nextSeq = 1L;
    private static boolean loaded = false;
    private static Path filePath;

    private ShopLedger() {
    }

    private static final class Entry {
        long seq;
        String itemId;
        String seller;
        long count;
        long price;

        Entry(long seq, String itemId, String seller, long count, long price) {
            this.seq = seq;
            this.itemId = itemId;
            this.seller = seller;
            this.count = count;
            this.price = price;
        }
    }

    /** 撮合结果 */
    public static final class Fill {
        public long total;                       // 买家应付总额
        public long filled;                      // 实际成交件数
        public boolean enough;                   // 库存是否够
        public final Map<String, Long> payouts = new LinkedHashMap<>(); // 卖家 → 金额
    }

    private static Path file() {
        if (filePath == null) {
            try {
                Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
                Files.createDirectories(dir);
                filePath = dir.resolve("shop_ledger.txt");
            } catch (Throwable t) {
                filePath = Path.of("kjbb_shop_ledger.txt");
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
        nextSeq = 1L;
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
                if (f.length < 4) {
                    continue;
                }
                try {
                    long seq = Long.parseLong(f[0].trim());
                    String itemId = f[1].trim();
                    String seller = f[2].trim();
                    long count = Long.parseLong(f[3].trim());
                    long price = f.length > 4 ? Long.parseLong(f[4].trim()) : 0L;
                    if (count <= 0L || itemId.isEmpty() || seller.isEmpty()) {
                        continue;
                    }
                    // 旧格式没有价格 → 用当前官方价兜底
                    if (price <= 0L) {
                        price = ShopPrices.price(itemId);
                        if (price <= 0L) {
                            price = 1L;
                        }
                    }
                    ENTRIES.add(new Entry(seq, itemId, seller, count, price));
                    if (seq >= nextSeq) {
                        nextSeq = seq + 1L;
                    }
                } catch (Throwable ignored) {
                    // 跳过坏行
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读挂牌账本失败: " + t);
        }
        sort();
    }

    private static void sort() {
        ENTRIES.sort((a, b) -> {
            int c = Long.compare(a.price, b.price);
            if (c != 0) {
                return c;
            }
            return Long.compare(a.seq, b.seq);
        });
    }

    public static synchronized void flushNow() {
        sort();
        StringBuilder sb = new StringBuilder();
        sb.append("# seq|itemId|seller|count|price\n");
        for (Entry e : ENTRIES) {
            if (e.count <= 0L) {
                continue;
            }
            sb.append(e.seq).append('|').append(e.itemId).append('|').append(e.seller)
                    .append('|').append(e.count).append('|').append(e.price).append('\n');
        }
        try {
            SafeFile.write(file(), sb.toString());
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写挂牌账本失败: " + t);
        }
    }

    /** 标脏：真正写盘由 DirtySave 每 saveIntervalTicks 批量执行 */
    public static synchronized void save() {
        DirtySave.mark("shopledger");
    }

    static {
        DirtySave.register("shopledger", ShopLedger::flushNow);
    }


    /** 挂牌：同卖家 + 同物品 + 同价格 才合并 */
    public static synchronized boolean deposit(String seller, String itemId, long count, long price) {
        if (seller == null || seller.isEmpty() || itemId == null || itemId.isEmpty()
                || count <= 0L || price <= 0L) {
            return false;
        }
        load();
        for (Entry e : ENTRIES) {
            if (e.count > 0L && e.seller.equals(seller)
                    && e.itemId.equals(itemId) && e.price == price) {
                e.count += count;
                save();
                return true;
            }
        }
        ENTRIES.add(new Entry(nextSeq++, itemId, seller, count, price));
        save();
        return true;
    }

    /** 预估：买 count 个要花多少（不消耗） */
    public static synchronized Fill quote(String itemId, long count) {
        Fill fill = new Fill();
        if (itemId == null || count <= 0L) {
            return fill;
        }
        load();
        long left = count;
        for (Entry e : ENTRIES) {
            if (left <= 0L) {
                break;
            }
            if (e.count <= 0L || !e.itemId.equals(itemId)) {
                continue;
            }
            long take = Math.min(e.count, left);
            long money = take * e.price;
            Long prev = fill.payouts.get(e.seller);
            fill.payouts.put(e.seller, (prev == null ? 0L : prev) + money);
            fill.total += money;
            fill.filled += take;
            left -= take;
        }
        fill.enough = (left <= 0L);
        return fill;
    }

    /** 成交：按同样规则真正扣减（价格优先→时间优先） */
    public static synchronized Fill take(String itemId, long count) {
        Fill fill = new Fill();
        if (itemId == null || count <= 0L) {
            return fill;
        }
        load();
        long left = count;
        for (Entry e : ENTRIES) {
            if (left <= 0L) {
                break;
            }
            if (e.count <= 0L || !e.itemId.equals(itemId)) {
                continue;
            }
            long take = Math.min(e.count, left);
            e.count -= take;
            long money = take * e.price;
            Long prev = fill.payouts.get(e.seller);
            fill.payouts.put(e.seller, (prev == null ? 0L : prev) + money);
            fill.total += money;
            fill.filled += take;
            left -= take;
        }
        fill.enough = (left <= 0L);
        if (fill.filled > 0L) {
            ENTRIES.removeIf(e -> e.count <= 0L);
            save();
        }
        return fill;
    }

    /** 按 FIFO 扣账本，只返回「卖家 → 件数」（价格由官方公式决定） */
    public static synchronized Fill takeCounts(String itemId, long count) {
        Fill fill = new Fill();
        if (itemId == null || count <= 0L) {
            return fill;
        }
        load();
        long left = count;
        for (Entry e : ENTRIES) {
            if (left <= 0L) {
                break;
            }
            if (e.count <= 0L || !e.itemId.equals(itemId)) {
                continue;
            }
            long take = Math.min(e.count, left);
            e.count -= take;
            Long prev = fill.payouts.get(e.seller);
            fill.payouts.put(e.seller, (prev == null ? 0L : prev) + take);
            fill.filled += take;
            left -= take;
        }
        fill.enough = (left <= 0L);
        if (fill.filled > 0L) {
            ENTRIES.removeIf(e -> e.count <= 0L);
            save();
        }
        return fill;
    }

    /** 当前最低卖价；没有挂牌返回 0 */
    public static synchronized long lowestAsk(String itemId) {
        load();
        long best = 0L;
        for (Entry e : ENTRIES) {
            if (e.count <= 0L || !e.itemId.equals(itemId)) {
                continue;
            }
            if (best == 0L || e.price < best) {
                best = e.price;
            }
        }
        return best;
    }

    /** 某物品挂牌总数 */
    public static synchronized long remaining(String itemId) {
        load();
        long total = 0L;
        for (Entry e : ENTRIES) {
            if (e.count > 0L && e.itemId.equals(itemId)) {
                total += e.count;
            }
        }
        return total;
    }

    /** 某卖家的挂牌："itemId=count@price;..." */
    public static synchronized String listings(String seller) {
        load();
        StringBuilder sb = new StringBuilder();
        for (Entry e : ENTRIES) {
            if (e.count <= 0L || !e.seller.equals(seller)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.itemId).append('=').append(e.count).append('@').append(e.price);
        }
        return sb.toString();
    }

    /** 全服挂牌（按物品聚合，显示最低价）："itemId=count@lowest;..." */
    public static synchronized String all() {
        load();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Entry e : ENTRIES) {
            if (e.count <= 0L) {
                continue;
            }
            Long prev = counts.get(e.itemId);
            counts.put(e.itemId, (prev == null ? 0L : prev) + e.count);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> en : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(en.getKey()).append('=').append(en.getValue())
                    .append('@').append(lowestAsk(en.getKey()));
        }
        return sb.toString();
    }

    /** 深拷贝一份快照（调试用） */
    public static synchronized String raw() {
        load();
        StringBuilder sb = new StringBuilder();
        for (Entry e : ENTRIES) {
            if (e.count <= 0L) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.seq).append('|').append(e.itemId).append('|').append(e.seller)
                    .append('|').append(e.count).append('|').append(e.price);
        }
        return sb.toString();
    }

    public static synchronized void reloadFromDisk() {
        loaded = false;
        load();
    }

    public static String pathString() {
        return file().toAbsolutePath().toString();
    }
}
