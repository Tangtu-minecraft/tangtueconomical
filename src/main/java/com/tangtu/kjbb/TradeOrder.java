package com.tangtu.kjbb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 托管交易订单（方案 B）。
 *
 * 流程：
 *   卖家挂单（只展示，不扣货）
 *      ↓ 买家锁单 → 钱进托管
 *   订单 PENDING（等卖家发货）
 *      ↓ 卖家 /billtrade ship <订单号> → 走机械动力物流
 *   订单 SHIPPED（等买家取货）
 *      ↓ 买家 /billtrade confirm <订单号>
 *   订单 DONE（钱给卖家）
 *
 * 超时：PENDING 超过 30 分钟未发货 → 自动取消退款
 *
 * 文件：config/kjbb/trade_orders.txt
 * 格式：id|offerId|seller|buyer|item|count|unit|escrow|status|created|shipped
 */
public final class TradeOrder {

    public static final String PENDING = "PENDING";
    public static final String SHIPPED = "SHIPPED";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";

    /** 卖家发货期限：30 分钟 */
    private static final long SHIP_DEADLINE_MS = 30L * 60_000L;

    public static final class Row {
        public long id;
        public long offerId;
        public String seller;
        public String buyer;
        public String item;
        public int count;
        public long unit;
        public long escrow;
        public String status;
        public long created;
        public long shipped;
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static long NEXT_ID = 1L;
    private static boolean LOADED = false;

    private TradeOrder() {
    }

    private static File file() {
        try {
            var dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("kjbb");
            java.nio.file.Files.createDirectories(dir);
            return dir.resolve("trade_orders.txt").toFile();
        } catch (Throwable t) {
            return new File("config/kjbb/trade_orders.txt");
        }
    }

    public static synchronized void load() {
        if (LOADED) {
            return;
        }
        LOADED = true;
        File f = file();
        if (!f.exists()) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\|");
                if (p.length < 11) {
                    continue;
                }
                Row r = new Row();
                r.id = Long.parseLong(p[0].trim());
                r.offerId = Long.parseLong(p[1].trim());
                r.seller = p[2];
                r.buyer = p[3];
                r.item = p[4];
                r.count = Integer.parseInt(p[5].trim());
                r.unit = Long.parseLong(p[6].trim());
                r.escrow = Long.parseLong(p[7].trim());
                r.status = p[8];
                r.created = Long.parseLong(p[9].trim());
                r.shipped = Long.parseLong(p[10].trim());
                ROWS.add(r);
                if (r.id >= NEXT_ID) {
                    NEXT_ID = r.id + 1;
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomico] 读托管订单失败: " + t);
        }
    }

    public static synchronized void save() {
        try (PrintWriter pw = new PrintWriter(file(), "UTF-8")) {
            pw.println("# id|offerId|seller|buyer|item|count|unit|escrow|status|created|shipped");
            for (Row r : ROWS) {
                pw.println(r.id + "|" + r.offerId + "|" + r.seller + "|" + r.buyer + "|"
                        + r.item + "|" + r.count + "|" + r.unit + "|" + r.escrow + "|"
                        + r.status + "|" + r.created + "|" + r.shipped);
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写托管订单失败: " + t);
        }
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

    public static synchronized List<Row> byStatus(String status) {
        load();
        List<Row> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (status.equals(r.status)) {
                out.add(r);
            }
        }
        return out;
    }

    /** 买家锁单：扣钱进托管，生成待发货订单 */
    public static synchronized String lock(String buyer, long offerId, int want) {
        load();
        var row = OfferBook.get(offerId);
        if (row == null || !OfferBook.OPEN.equals(row.status)) {
            return "§c这个挂单不可交易";
        }
        if (buyer == null || buyer.isEmpty()) {
            return "§c买家无效";
        }
        if (buyer.equals(row.seller)) {
            return "§c不能买自己的挂单";
        }
        int take = Math.min(Math.max(1, want), row.count);
        long cost = row.price * take;
        long have = Ledger.money(buyer);
        if (have < cost) {
            return "§c唐兔币不够：需要 " + cost + "，你有 " + have;
        }
        if (Ledger.take(buyer, cost) != cost) {
            return "§c扣款失败";
        }
        Row t = new Row();
        t.id = NEXT_ID++;
        t.offerId = row.id;
        t.seller = row.seller;
        t.buyer = buyer;
        t.item = row.itemId;
        t.count = take;
        t.unit = row.price;
        t.escrow = cost;
        t.status = PENDING;
        t.created = ServerClock.now();
        t.shipped = 0L;
        ROWS.add(t);
        // 挂单数量递减（货还在卖家手上，只是不能再卖给别人）
        row.count -= take;
        if (row.count <= 0) {
            row.status = OfferBook.FILLED;
        }
        OfferBook.save();
        save();
        DebugLog.log("锁单 #" + t.id + "：" + buyer + " 锁定 " + row.seller + " 的 "
                + t.item + " ×" + take + "，托管 " + cost);
        return "§a已锁单 #" + t.id + "，托管 " + cost + " 唐兔币；等卖家发货（30 分钟内）";
    }

    /** 卖家发货：走机械动力物流 */
    public static synchronized String ship(String seller, long orderId) {
        load();
        Row t = get(orderId);
        if (t == null) {
            return "§c没有这个订单";
        }
        if (!seller.equals(t.seller)) {
            return "§c这不是你的订单";
        }
        if (!PENDING.equals(t.status)) {
            return "§c订单状态不对（" + t.status + "）";
        }
        // ① 先从卖家身上把货取走（不取就等于凭空造货）
        net.minecraft.world.item.ItemStack taken = takeGoods(seller, t.item, t.count);
        if (taken == null || taken.isEmpty() || taken.getCount() < t.count) {
            return "§c货不够：背包和仓库里凑不出 " + t.count + " 个（发货失败，订单还在待发货）";
        }
        // ② 投递到买家提货点
        boolean delivered = false;
        String addr = t.buyer;
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv != null) {
                net.minecraft.core.BlockPos port = com.tangtu.kjbb.AddressBook.get(t.buyer);
                if (port != null) {
                    var level = srv.overworld();
                    var sp2 = srv.getPlayerList().getPlayerByName(t.buyer);
                    if (sp2 != null) {
                        level = sp2.serverLevel();
                    }
                    var goods = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
                    goods.add(taken);
                    delivered = com.tangtu.kjbb.AddressBook.deliverTo(
                            new com.tangtu.kjbb.AddressBook.PortInfo(level, port, addr), goods);
                }
            }
        } catch (Throwable ignored) {
            delivered = false;
        }
        // ③ 投不出去就进离线队列，等提货点加载
        if (!delivered) {
            try {
                com.tangtu.kjbb.MailQueue.add(addr, addr, t.item, t.count);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        try {
            com.tangtu.kjbb.MailLog.add(seller, t.buyer, t.item, t.count,
                    delivered ? "物流中" : "排队");
        } catch (Throwable ignored) {
            // ignore
        }
        t.status = SHIPPED;
        t.shipped = ServerClock.now();
        save();
        DebugLog.log("发货 #" + t.id + "：" + seller + " → " + t.buyer + " " + t.item + " ×" + t.count
                + (delivered ? "（已投递）" : "（已进队列）"));
        return "§a已发货 #" + t.id + "，" + t.item + " ×" + t.count
                + (delivered ? " 已送到买家提货点" : " 提货点未加载，已排队等待投递");
    }

    /** 从卖家身上取货：背包优先，不够再找仓库 */
    private static net.minecraft.world.item.ItemStack takeGoods(String seller, String itemId, int count) {
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null) {
                return null;
            }
            net.minecraft.world.item.Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.tryParse(itemId));
            if (it == null) {
                return null;
            }
            int left = count;
            var sp = srv.getPlayerList().getPlayerByName(seller);
            if (sp != null) {
                var inv = sp.getInventory();
                for (int k = 0; k < inv.getContainerSize() && left > 0; k++) {
                    var st = inv.getItem(k);
                    if (st == null || st.isEmpty() || st.getItem() != it) {
                        continue;
                    }
                    int n = Math.min(left, st.getCount());
                    st.shrink(n);
                    left -= n;
                }
                try {
                    sp.inventoryMenu.broadcastChanges();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            if (left > 0) {
                try {
                    int fromStore = com.tangtu.kjbb.TickerStock.takeFromAnyWarehouse(seller, it, left);
                    if (fromStore > 0) {
                        left -= fromStore;
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            int got = count - left;
            if (got <= 0) {
                return null;
            }
            return new net.minecraft.world.item.ItemStack(it, got);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 买家确认收货：托管款给卖家 */
    public static synchronized String confirm(String buyer, long orderId) {
        load();
        Row t = get(orderId);
        if (t == null) {
            return "§c没有这个订单";
        }
        if (!buyer.equals(t.buyer)) {
            return "§c这不是你的订单";
        }
        if (!SHIPPED.equals(t.status)) {
            return "§c卖家还没发货";
        }
        Ledger.credit(t.seller, t.escrow);
        t.status = DONE;
        save();
        DebugLog.log("确认收货 #" + t.id + "：托管 " + t.escrow + " 付给 " + t.seller);
        return "§a已确认收货，卖家收到 " + t.escrow + " 唐兔币";
    }

    /** 每 tick 检查超时：PENDING 超过 30 分钟自动退款取消 */
    public static synchronized int tick() {
        load();
        long now = ServerClock.now();
        int n = 0;
        Iterator<Row> it = ROWS.iterator();
        while (it.hasNext()) {
            Row t = it.next();
            if (!PENDING.equals(t.status) || now - t.created < SHIP_DEADLINE_MS) {
                continue;
            }
            Ledger.credit(t.buyer, t.escrow);
            t.status = CANCELLED;
            n++;
            DebugLog.log("超时退款 #" + t.id + "：" + t.buyer + " 退回 " + t.escrow
                    + "（卖家 " + t.seller + " 未发货）");
        }
        if (n > 0) {
            save();
        }
        return n;
    }

    /** 我的订单 */
    public static synchronized List<Row> mine(String who) {
        load();
        List<Row> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (who.equals(r.buyer) || who.equals(r.seller)) {
                out.add(r);
            }
        }
        return out;
    }
}