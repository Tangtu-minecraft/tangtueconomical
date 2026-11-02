package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 买家订单簿：config/kjbb/orders.txt
 *
 * 格式：id|买家|物品|数量|单价|总额|提货地址|状态|卖家|创建时间
 * 状态：PENDING（等卖家供货） / SUPPLIED（已发货待收货） / DONE / CANCELLED
 *
 * 流程：先有买家订单 → 卖家供货 → 货到买家提货点 → 放款给卖家。
 */
public final class OrderBook {

    public static final String PENDING = "PENDING";
    /** 已被某个卖家锁单（别人不能接） */
    public static final String ACCEPTED = "ACCEPTED";
    public static final String SUPPLIED = "SUPPLIED";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";

    public static final class Row {
        public long id;
        public String buyer;
        public String itemId;
        public int count;
        public long unit;
        public long total;
        public String address;
        public String status;
        public String seller;
        public long created;

        public Row(long id, String buyer, String itemId, int count, long unit, long total,
                   String address, String status, String seller, long created) {
            this.id = id;
            this.buyer = buyer;
            this.itemId = itemId;
            this.count = count;
            this.unit = unit;
            this.total = total;
            this.address = address;
            this.status = status;
            this.seller = seller;
            this.created = created;
        }
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static long nextId = 1L;
    private static boolean loaded = false;

    private OrderBook() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("orders.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_orders.txt");
        }
    }

    /** 锁单时间（内存态，重启后 ACCEPTED 一律释放为 PENDING） */
    private static final java.util.Map<Long, Long> ACCEPT_AT = new java.util.LinkedHashMap<>();

    /** 锁单后不发货物禁接期：key = "订单号|玩家名" → 到期时间戳 */
    private static final java.util.Map<String, Long> PENALTY = new java.util.LinkedHashMap<>();

    /** 锁单不发货的禁接时长（30 分钟） */
    public static final long PENALTY_MS = 30L * 60_000L;

    private static String key(long id, String player) {
        return id + "|" + (player == null ? "" : player);
    }

    /** 该玩家现在能不能接这张单；返回 null 表示可以，否则返回原因 */
    public static synchronized String acceptBlockReason(long id, String player) {
        long until = PENALTY.getOrDefault(key(id, player), 0L);
        long now = ServerClock.now();
        if (until > now) {
            long left = Math.max(1L, (until - now + 59_999L) / 60_000L);
            return "\u4f60\u521a\u9501\u4e86\u8fd9\u5f20\u5355\u6ca1\u53d1\u8d27\uff0c"
                    + left + " \u5206\u949f\u5185\u4e0d\u80fd\u518d\u63a5";
        }
        return null;
    }

    /** 记一次「锁单未发货」，30 分钟内不能再接这张单 */
    public static synchronized void addPenalty(long id, String player) {
        PENALTY.put(key(id, player), ServerClock.now() + PENALTY_MS);
        System.out.println("[TangtuEconomical] \u9501\u5355\u672a\u53d1\u8d27\u7f5a\u7981 #" + id
                + " \u73a9\u5bb6=" + player + " 30 \u5206\u949f");
    }

    /** 卖家锁单 */
    public static synchronized boolean accept(long id, String seller) {
        load();
        Row r = get(id);
        if (r == null || !PENDING.equals(r.status)) {
            return false;
        }
        if (acceptBlockReason(id, seller) != null) {
            return false;      // 30 分钟内被罚过，不许再接
        }
        r.status = ACCEPTED;
        r.seller = seller;
        ACCEPT_AT.put(id, ServerClock.now());
        save();
        return true;
    }

    /** 锁单超时释放（默认 10 分钟没发货就放回市场） */
    public static synchronized int releaseStaleAccepted(long maxAgeMs) {
        load();
        int n = 0;
        long now = ServerClock.now();
        for (Row r : new ArrayList<>(ROWS)) {
            if (!ACCEPTED.equals(r.status)) {
                continue;
            }
            long at = ACCEPT_AT.getOrDefault(r.id, 0L);
            if (at <= 0L) {
                at = now - maxAgeMs - 1;
            }
            if (now - at >= maxAgeMs) {
                if (r.seller != null && !r.seller.isEmpty()) {
                    addPenalty(r.id, r.seller);      // 锁了不发货 → 记罚
                }
                r.status = PENDING;
                r.seller = null;
                ACCEPT_AT.remove(r.id);
                n++;
            }
        }
        if (n > 0) {
            save();
        }
        return n;
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        ROWS.clear();
        ACCEPT_AT.clear();
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
                if (f.length < 10) {
                    continue;
                }
                try {
                    long id = Long.parseLong(f[0].trim());
                    Row r = new Row(id, f[1].trim(), f[2].trim(),
                            Integer.parseInt(f[3].trim()),
                            Long.parseLong(f[4].trim()),
                            Long.parseLong(f[5].trim()),
                            f[6].trim(), f[7].trim(), f[8].trim(),
                            Long.parseLong(f[9].trim()));
                    ROWS.add(r);
                    if (id >= nextId) {
                        nextId = id + 1;
                    }
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读订单簿失败: " + t);
        }
    }

    private static synchronized void flushNow() {
        StringBuilder sb = new StringBuilder(
                "# id|买家|物品|数量|单价|总额|提货地址|状态|卖家|创建时间\n");
        for (Row r : ROWS) {
            sb.append(r.id).append('|').append(r.buyer).append('|').append(r.itemId).append('|')
                    .append(r.count).append('|').append(r.unit).append('|').append(r.total)
                    .append('|').append(r.address).append('|').append(r.status).append('|')
                    .append(r.seller).append('|').append(r.created).append('\n');
        }
        try {
            SafeFile.write(file(), sb.toString());
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写订单簿失败: " + t);
        }
    }

    /** 标脏：真正写盘由 DirtySave 每 saveIntervalTicks 批量执行 */
    private static synchronized void save() {
        DirtySave.mark("orders");
    }

    static {
        DirtySave.register("orders", OrderBook::flushNow);
    }


    public static synchronized Row add(String buyer, String itemId, int count, long unit,
                                       String address) {
        load();
        // 单号上限 99999：超过就从 1 重新开始，并跳过仍在用的号
        if (nextId > 99999L) {
            nextId = 1L;
            int guard = 0;
            while (get(nextId) != null && guard++ < 100000) {
                nextId++;
                if (nextId > 99999L) {
                    nextId = 1L;
                }
            }
        }
        Row r = new Row(nextId++, buyer, itemId, count, unit, unit * count, address,
                PENDING, "", ServerClock.now());
        ROWS.add(r);
        save();
        return r;
    }

    public static synchronized List<Row> byStatus(String status) {
        load();
        List<Row> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (status == null || r.status.equals(status)) {
                out.add(r);
            }
        }
        return out;
    }

    public static synchronized Row get(long id) {
        load();
        for (Row r : ROWS) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    public static synchronized void setStatus(long id, String status, String seller) {
        load();
        for (Row r : ROWS) {
            if (r.id != id) {
                continue;
            }
            r.status = status;
            if (seller != null) {
                r.seller = seller;
            }
        }
        save();
    }

    /**
     * 部分成交：从订单里扣掉成交数量
     *
     * @return true = 已全部成交（状态变 DONE）；false = 还有剩余
     */
    public static synchronized boolean fillPartial(long id, int take, String seller) {
        load();
        for (Row r : ROWS) {
            if (r.id != id) {
                continue;
            }
            if (take >= r.count) {
                r.status = DONE;
                r.count = 0;
                r.total = 0L;
                if (seller != null) {
                    r.seller = seller;
                }
                save();
                return true;
            }
            r.count -= take;
            r.total = r.unit * r.count;
            if (seller != null) {
                r.seller = seller;
            }
            save();
            return false;
        }
        return false;
    }

    /** 清理已完成/已取消且早于 cutoff 的订单，返回清理条数 */
    public static synchronized int purge(long cutoffMs) {
        load();
        int before = ROWS.size();
        ROWS.removeIf(r -> (DONE.equals(r.status) || CANCELLED.equals(r.status))
                && r.created < cutoffMs);
        int n = before - ROWS.size();
        if (n > 0) {
            save();
        }
        return n;
    }

    /** 某物品的最高买价（0 = 没有买单） */
    public static synchronized long bestBid(String itemId) {
        load();
        long best = 0L;
        for (Row r : ROWS) {
            if (!PENDING.equals(r.status)) {
                continue;
            }
            if (itemId != null && !itemId.isEmpty() && !r.itemId.equalsIgnoreCase(itemId)) {
                continue;
            }
            if (r.unit > best) {
                best = r.unit;
            }
        }
        return best;
    }

    /** 订单展示排序：0=国库高买 1=国库其它 2=玩家/bot */
    private static int rankOf(Row r, String reg) {
        if (r == null || r.buyer == null) {
            return 2;
        }
        if (reg != null && reg.equals(r.buyer)) {
            try {
                long base = ShopPrices.price(r.itemId);
                if (base > 0L && r.unit >= Math.round(base * 1.05)) {
                    return 0;
                }
            } catch (Throwable ignored) {
                // ignore
            }
            return 1;
        }
        return 2;
    }

    public static synchronized String dump(String status) {
        load();
        StringBuilder sb = new StringBuilder();
        // 排序：国库高买 → 国库其它单 → 玩家单
        java.util.List<Row> sorted = new java.util.ArrayList<>(ROWS);
        String reg = null;
        try {
            reg = MarketBots.regulatorName();
        } catch (Throwable ignored) {
            // ignore
        }
        final String regF = reg;
        sorted.sort((a, c2) -> Integer.compare(rankOf(a, regF), rankOf(c2, regF)));
        for (Row r : sorted) {
            if (status != null && !status.equals(r.status)) {
                continue;
            }
            sb.append("#").append(r.id).append("  ").append(r.itemId).append(" ×").append(r.count)
                    .append("  ").append(r.unit).append(" 唐兔币/个  共 ").append(r.total)
                    .append(" 唐兔币  [").append(r.status).append("]  买家=").append(r.buyer)
                    .append("  地址=").append(r.address)
                    .append(r.seller == null || r.seller.isEmpty() ? "" : "  卖家=" + r.seller)
                    .append('\n');
        }
        return sb.length() == 0 ? "（没有订单）" : sb.toString();
    }

    /**
     * 取消超过时限仍未成交的买单，把钱退回买家。
     *
     * @return 取消的订单数
     */
    /**
     * 机器人下的单：超过 maxAgeMs 还没人供货就撤单退款。
     * （玩家单仍走 cancelExpired 的 30 分钟逻辑）
     */
    public static synchronized int cancelStaleBotOrders(long nowMs, long maxAgeMs) {
        int n = 0;
        for (Row r : new ArrayList<>(ROWS)) {
            if (!PENDING.equals(r.status)) {
                continue;
            }
            boolean botBuyer = false;
            try {
                botBuyer = com.tangtu.kjbb.MarketBots.isBot(r.buyer);
            } catch (Throwable ignored) {
                // ignore
            }
            if (!botBuyer) {
                continue;
            }
            if (nowMs - r.created <= maxAgeMs) {
                continue;
            }
            r.status = CANCELLED;
            n++;
            try {
                com.tangtu.kjbb.Pay.credit(r.buyer, r.total);
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                com.tangtu.kjbb.MarketBots.markSupplyFail(r.itemId);
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                DebugLog.log("机器人撤单 #" + r.id + " " + r.buyer + " " + r.itemId
                        + " ×" + r.count + " 超 2 日无人供货，退回 " + r.total);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        if (n > 0) {
            save();
        }
        return n;
    }

    public static synchronized int cancelExpired(long nowMs, long maxAgeMs) {
        int n = 0;
        for (Row r : new ArrayList<>(ROWS)) {
            if (!PENDING.equals(r.status)) {
                continue;
            }
            if (nowMs - r.created <= maxAgeMs) {
                continue;
            }
            r.status = CANCELLED;
            n++;
            try {
                com.tangtu.kjbb.Pay.credit(r.buyer, r.total);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        if (n > 0) {
            save();
        }
        return n;
    }
}
