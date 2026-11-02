package com.tangtu.kjbb;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 股票交易板（股票进交易行）
 *
 * 卖方把「实体股票」押上来（从背包扣除），买方付钱拿票（生成新票）。
 * 文件：config/kjbb/stock_market.txt
 *   id|billId|shares|price|seller|status
 *
 * 状态：OPEN（在售） / DONE（成交） / CANCELLED（撤单）
 */
public final class StockMarket {

    public static final String OPEN = "OPEN";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";

    public static final class Row {
        public long id;
        public long billId;
        public int shares;
        public long price;          // 每份价格
        public String seller;
        public String status = OPEN;
        public long created;
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static boolean LOADED = false;
    private static long NEXT_ID = 1L;

    private StockMarket() {
    }

    private static Path file() {
        return Path.of("config", "kjbb", "stock_market.txt");
    }

    public static synchronized void load() {
        if (LOADED) {
            return;
        }
        LOADED = true;
        try {
            Path p = file();
            if (!Files.exists(p)) {
                return;
            }
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length < 6) {
                    continue;
                }
                Row r = new Row();
                r.id = Long.parseLong(f[0].trim());
                r.billId = Long.parseLong(f[1].trim());
                r.shares = Integer.parseInt(f[2].trim());
                r.price = Long.parseLong(f[3].trim());
                r.seller = f[4].trim();
                r.status = f[5].trim();
                ROWS.add(r);
                NEXT_ID = Math.max(NEXT_ID, r.id + 1);
            }
        } catch (Throwable t) {
            DebugLog.log("股票交易板读取失败: " + t);
        }
    }

    public static synchronized void flushNow() {
        try {
            Path p = file();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# id|billId|shares|price|seller|status\n");
            for (Row r : ROWS) {
                sb.append(r.id).append('|').append(r.billId).append('|').append(r.shares)
                        .append('|').append(r.price).append('|').append(r.seller)
                        .append('|').append(r.status).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 标脏：真正写盘由 DirtySave 每 saveIntervalTicks 批量执行 */
    public static synchronized void save() {
        DirtySave.mark("stock");
    }

    static {
        DirtySave.register("stock", StockMarket::flushNow);
    }


    public static synchronized List<Row> open() {
        load();
        List<Row> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (OPEN.equals(r.status)) {
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

    /** 把手里的股票押上交易板（整张） */
    public static synchronized String list(ServerPlayer sp, long pricePerShare) {
        return list(sp, pricePerShare, 0);
    }

    /**
     * 把手里的股票押上交易板。
     *
     * @param wantShares 只挂这么多份（0 或超过票面 = 整张挂）；小于票面时会把剩下的份数写回手里的票
     */
    public static synchronized String list(ServerPlayer sp, long pricePerShare, int wantShares) {
        load();
        ItemStack held = sp.getMainHandItem();
        if (!(held.getItem() instanceof com.tangtu.kjbb.item.StockItem)) {
            return "§c请手持股票再上架";
        }
        long billId = com.tangtu.kjbb.item.StockItem.billOf(held);
        int shares = com.tangtu.kjbb.item.StockItem.sharesOf(held);
        if (billId <= 0L || shares <= 0) {
            return "§c这张股票没有票面数据";
        }
        if (pricePerShare <= 0L) {
            return "§c价格必须大于 0";
        }
        // 拆票：只挂一部分时，剩下的份数写回手里这张票
        int listed = (wantShares <= 0 || wantShares > shares) ? shares : wantShares;
        if (listed < shares) {
            var b = VoyageBill.get(billId);
            com.tangtu.kjbb.item.StockItem.write(held, billId,
                    b == null ? "" : b.name, shares - listed, b == null ? -1L : b.dueDay);
        } else {
            held.shrink(1);
        }
        long total = pricePerShare * listed;
        Row r = new Row();
        r.id = NEXT_ID++;
        r.billId = billId;
        r.shares = listed;
        r.price = pricePerShare;
        r.seller = sp.getScoreboardName();
        r.status = OPEN;
        r.created = ServerClock.now();
        ROWS.add(r);
        save();
        DebugLog.log("股票上架 #" + r.id + "：" + r.seller + " 船#" + billId + " " + listed
                + " 份 @" + pricePerShare + "（总价 " + total + "）");
        return "§a已上架 #" + r.id + "：" + listed + " 份 × " + pricePerShare
                + " = " + total + " 唐兔币"
                + (listed < shares ? ("（票面剩 " + (shares - listed) + " 份）") : "");
    }

    /** 在线玩家 */
    private static ServerPlayer online(String who) {
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            return server == null ? null : server.getPlayerList().getPlayerByName(who);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 造一张票塞给玩家（背包满就丢脚下） */
    private static void giveTicket(ServerPlayer sp, long billId, int shares) {
        var b = VoyageBill.get(billId);
        ItemStack stack = new ItemStack(com.tangtu.kjbb.item.KjbbItems.STOCK.get());
        com.tangtu.kjbb.item.StockItem.write(stack, billId,
                b == null ? "" : b.name, shares, b == null ? -1L : b.dueDay);
        stack.setCount(1);
        if (!sp.getInventory().add(stack)) {
            sp.drop(stack, false);
        }
    }

    /** 航次结束时：撤掉这艘船的所有挂单，票退回卖家（离线就记待领） */
    public static synchronized int cancelByBill(long billId) {
        load();
        int n = 0;
        for (Row r : ROWS) {
            if (r.billId != billId || !OPEN.equals(r.status)) {
                continue;
            }
            r.status = CANCELLED;
            n++;
            ServerPlayer sp = online(r.seller);
            if (sp != null) {
                giveTicket(sp, r.billId, r.shares);
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e[股票] \u822a\u6b21 #" + billId + " \u5df2\u7ed3\u675f\uff0c"
                                + "\u4f60\u7684\u6302\u5355 #" + r.id + " \u5df2\u64a4\u5355\uff0c"
                                + r.shares + " \u4efd\u5df2\u9000\u56de\u80cc\u5305"), false);
            } else {
                PENDING.add(r.seller + "|" + r.billId + "|" + r.shares);
            }
        }
        if (n > 0) {
            save();
            savePending();
        }
        return n;
    }

    // ---------------- 离线卖家的待领票 ----------------

    private static final List<String> PENDING = new ArrayList<>();
    private static boolean PENDING_LOADED = false;

    private static Path pendingFile() {
        return Path.of("config", "kjbb", "stock_pending.txt");
    }

    private static void loadPending() {
        if (PENDING_LOADED) {
            return;
        }
        PENDING_LOADED = true;
        try {
            Path p = pendingFile();
            if (!Files.exists(p)) {
                return;
            }
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line != null && !line.isBlank() && !line.startsWith("#")) {
                    PENDING.add(line.trim());
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读 stock_pending 失败: " + t);
        }
    }

    private static void savePending() {
        try {
            Path p = pendingFile();
            Files.createDirectories(p.getParent());
            StringBuilder sb = new StringBuilder("# seller|billId|shares\n");
            for (String s : PENDING) {
                sb.append(s).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写 stock_pending 失败: " + t);
        }
    }

    /** 把离线期间被撤单的票补给玩家（打开股票界面 / 登录时调） */
    public static synchronized int claimPending(ServerPlayer sp) {
        loadPending();
        if (PENDING.isEmpty() || sp == null) {
            return 0;
        }
        String who = sp.getScoreboardName();
        int got = 0;
        java.util.Iterator<String> it = PENDING.iterator();
        while (it.hasNext()) {
            String[] f = it.next().split("\\|");
            if (f.length < 3 || !who.equals(f[0])) {
                continue;
            }
            try {
                long billId = Long.parseLong(f[1]);
                int shares = Integer.parseInt(f[2]);
                giveTicket(sp, billId, shares);
                got++;
                it.remove();
            } catch (Throwable ignored) {
                // 坏行直接跳过
            }
        }
        if (got > 0) {
            savePending();
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "\u00a7a[股票] \u8865\u53d1 " + got + " \u5f20\u56e0\u822a\u6b21\u7ed3\u675f"
                            + "\u800c\u64a4\u56de\u7684\u7968"), false);
        }
        return got;
    }

    /** 买下挂单（生成新股票给买家） */
    public static synchronized String buy(ServerPlayer sp, long listingId) {
        load();
        Row r = get(listingId);
        if (r == null || !OPEN.equals(r.status)) {
            return "§c这个挂单不可交易";
        }
        String me = sp.getScoreboardName();
        if (me.equals(r.seller)) {
            return "§c不能买自己的挂单";
        }
        VoyageBill.Bill b = VoyageBill.get(r.billId);
        if (b == null || VoyageBill.CLOSED.equals(b.status)) {
            return "§c这张船的航次已结束";
        }
        long cost = r.price * r.shares;
        long have = BankBridge.money(me);
        if (have < cost) {
            return "§c唐兔币不够：需要 " + cost + "，你有 " + have;
        }
        long after = BankBridge.takeMoney(me, cost);
        if (have - after < cost) {
            return "§c扣款失败";
        }
        BankBridge.addMoney(r.seller, cost);
        // 股票交易税：卖方成交后交税进国库
        try {
            if (!com.tangtu.kjbb.MarketBots.isBot(r.seller)) {
                com.tangtu.kjbb.RegulatorOps.taxByName(r.seller, cost);
            }
        } catch (Throwable ignored) {
            // 收税失败不影响成交
        }
        ItemStack stack = new ItemStack(com.tangtu.kjbb.item.KjbbItems.STOCK.get());
        com.tangtu.kjbb.item.StockItem.write(stack, r.billId, b.name, r.shares, b.dueDay);
        stack.setCount(1);
        if (!sp.getInventory().add(stack)) {
            sp.drop(stack, false);
        }
                // 股东名册同步：卖家减、买家加（否则买家结算时拿不到钱）
                try {
                    int held = b.holders.getOrDefault(r.seller, 0);
                    int move = Math.min(held, r.shares);
                    if (move > 0) {
                        if (held - move <= 0) {
                            b.holders.remove(r.seller);
                        } else {
                            b.holders.put(r.seller, held - move);
                        }
                        b.holders.merge(me, move, Integer::sum);
                        com.tangtu.kjbb.VoyageBill.save();
                        DebugLog.log("股票过户 #" + r.billId + "：" + r.seller + " → " + me
                                + " " + move + " 份");
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
        r.status = DONE;
        save();
        BotTrades.log(me, r.seller, "股票买入#" + r.billId, r.shares, r.price, "交易行");
        DebugLog.log("股票成交 #" + r.id + "：" + r.shares + " 份 @" + r.price
                + " 买家=" + me + " 卖家=" + r.seller);
        return "§a买下 " + r.shares + " 份（花 " + cost + " 唐兔币）";
    }

    /** 撤单：把股票退回卖方 */
    public static synchronized String cancel(ServerPlayer sp, long listingId) {
        load();
        Row r = get(listingId);
        if (r == null || !OPEN.equals(r.status)) {
            return "§c这个挂单不可撤销";
        }
        String me = sp.getScoreboardName();
        if (!me.equals(r.seller)) {
            return "§c这不是你的挂单";
        }
        VoyageBill.Bill b = VoyageBill.get(r.billId);
        ItemStack stack = new ItemStack(com.tangtu.kjbb.item.KjbbItems.STOCK.get());
        com.tangtu.kjbb.item.StockItem.write(stack, r.billId, b == null ? "" : b.name,
                r.shares, b == null ? -1L : b.dueDay);
        stack.setCount(1);
        if (!sp.getInventory().add(stack)) {
            sp.drop(stack, false);
        }
        r.status = CANCELLED;
        save();
        return "§a已撤单 #" + r.id + "（股票退回背包）";
    }

    // ---------------- 机器人参与 ----------------

    /** 机器人买入挂单（钱走 BankBridge，票记在机器人账本里的伪物品） */
    public static synchronized String botBuy(Row r, String buyer) {
        load();
        if (r == null || !OPEN.equals(r.status)) {
            return "不可交易";
        }
        VoyageBill.Bill b = VoyageBill.get(r.billId);
        if (b == null || VoyageBill.CLOSED.equals(b.status)) {
            return "航次已结束";
        }
        long cost = r.price * r.shares;
        long have = BankBridge.money(buyer);
        if (have < cost) {
            return "现金不足";
        }
        long after = BankBridge.takeMoney(buyer, cost);
        if (have - after < cost) {
            return "扣款失败";
        }
        BankBridge.addMoney(r.seller, cost);
        // 股票交易税：卖方成交后交税进国库
        try {
            if (!com.tangtu.kjbb.MarketBots.isBot(r.seller)) {
                com.tangtu.kjbb.RegulatorOps.taxByName(r.seller, cost);
            }
        } catch (Throwable ignored) {
            // 收税失败不影响成交
        }
        BotLedger.addStock(buyer, stockKey(r.billId, r.shares), 1);
        r.status = DONE;
        save();
        BotTrades.log(buyer, r.seller, "股票买入#" + r.billId, r.shares, r.price, "交易行");
        DebugLog.log("股票成交 #" + r.id + "：" + r.shares + " 份 @" + r.price
                + " 买家=" + buyer + " 卖家=" + r.seller);
        return "ok";
    }

    /** 机器人上架（票从它的伪物品里扣） */
    public static synchronized String botList(long billId, int shares, long pricePerShare,
                                              String seller) {
        load();
        String key = stockKey(billId, shares);
        if (BotLedger.stock(seller, key) <= 0) {
            return "没票";
        }
        for (Row r : ROWS) {
            if (OPEN.equals(r.status) && r.seller.equals(seller)
                    && r.billId == billId && r.shares == shares) {
                return "已在售";
            }
        }
        BotLedger.addStock(seller, key, -1);
        Row r = new Row();
        r.id = NEXT_ID++;
        r.billId = billId;
        r.shares = shares;
        r.price = Math.max(1L, pricePerShare);
        r.seller = seller;
        r.status = OPEN;
        r.created = ServerClock.now();
        ROWS.add(r);
        save();
        DebugLog.log("股票上架 #" + r.id + "：" + seller + " 船#" + billId + " " + shares
                + " 份 @" + r.price);
        return "ok";
    }

    /** 机器人持仓的伪物品 key */
    public static String stockKey(long billId, int shares) {
        return "kjbankbridge:stock:" + billId + ":" + shares;
    }

    /** 扫描某机器人持有的所有股票（返回 [billId, shares, 数量] 列表字符串） */
    public static synchronized java.util.List<long[]> botHoldings(String bot) {
        load();
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (var e : BotLedger.stockMap(bot).entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("kjbankbridge:stock:")) {
                continue;
            }
            String[] parts = k.split(":");
            if (parts.length < 4) {
                continue;
            }
            try {
                long billId = Long.parseLong(parts[2]);
                int shares = Integer.parseInt(parts[3]);
                out.add(new long[]{billId, shares, e.getValue() == null ? 0 : e.getValue()});
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return out;
    }
}
