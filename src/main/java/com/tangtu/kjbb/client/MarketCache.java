package com.tangtu.kjbb.client;

import java.util.ArrayList;
import java.util.List;

/** 行情缓存（客户端） */
public final class MarketCache {

    /** 服务器日期（由 ClockSync 推送） */
    private static volatile String SERVER_DATE = "";


    public record Market(String id, int ask, int bid, int supply, int last) {
    }

    public record Candle(int open, int high, int low, int close, long volume) {
    }

    public record Order(long id, String itemId, int count, int unit, long total,
                        String status, String seller, long created) {
    }

    public record ItemOrder(long id, int count, int unit, long total, String status, String buyer) {
    }

    public record OrderDetail(long id, String itemId, int count, int unit, long total,
                              String status, String buyer, String seller, String address,
                              long created) {
    }

    /** 市场未完成订单（快照）；bot = 这一单是不是机器人挂的 */
    public record OpenOrder(boolean buy, long id, String itemId, int count, long unit,
                            String owner, long created, boolean bot) {
    }

    public record Ticket(long id, long price, String kind, String desc, String seller) {
    }

    public record TicketDetail(long id, String kind, long price, String seller, String status,
                               boolean bearer, String pos, String serial, String itemId,
                               int count, String items) {
    }

    private static TicketDetail ticketDetail = null;
    private static List<Ticket> tickets = new ArrayList<>();
    private static OrderDetail orderDetail = null;
    private static List<ItemOrder> itemOrders = new ArrayList<>();
    private static List<Order> orders = new ArrayList<>();
    private static List<Market> markets = new ArrayList<>();
    private static List<Candle> history = new ArrayList<>();

    private MarketCache() {
    }

    public static void setMarkets(String body) {
        List<Market> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                String[] f = line.split("\\|");
                if (f.length < 5) {
                    continue;
                }
                try {
                    out.add(new Market(f[0].trim(), Integer.parseInt(f[1].trim()),
                            Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim()),
                            Integer.parseInt(f[4].trim())));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        }
        markets = out;
    }

    public static void setHistory(String body) {
        List<Candle> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                String[] f = line.split("\\|");
                if (f.length < 5) {
                    continue;
                }
                try {
                    out.add(new Candle(Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()),
                            Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim()),
                            Long.parseLong(f[4].trim())));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        }
        history = out;
    }

    public static void setMyOrders(String body) {
        List<Order> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                String[] f = line.split("\\|", -1);
                if (f.length < 8) {
                    continue;
                }
                try {
                    out.add(new Order(Long.parseLong(f[0].trim()), f[1].trim(),
                            Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim()),
                            Long.parseLong(f[4].trim()), f[5].trim(), f[6].trim(),
                            Long.parseLong(f[7].trim())));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        }
        orders = out;
    }

    public static void setItemOrders(String body) {
        List<ItemOrder> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                String[] f = line.split("\\|", -1);
                if (f.length < 6) {
                    continue;
                }
                try {
                    out.add(new ItemOrder(Long.parseLong(f[0].trim()),
                            Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()),
                            Long.parseLong(f[3].trim()), f[4].trim(), f[5].trim()));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        }
        itemOrders = out;
    }

    public static void setTickets(String body) {
        List<Ticket> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                String[] f = line.split("\\|", -1);
                if (f.length < 5) {
                    continue;
                }
                try {
                    out.add(new Ticket(Long.parseLong(f[0].trim()), Long.parseLong(f[1].trim()),
                            f[2].trim(), f[3].trim(), f[4].trim()));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        }
        tickets = out;
    }

    public static void setTicketDetail(String body) {
        if (body == null || body.isEmpty()) {
            ticketDetail = null;
            return;
        }
        String[] f = body.split("~", -1);
        if (f.length < 11) {
            ticketDetail = null;
            return;
        }
        try {
            ticketDetail = new TicketDetail(Long.parseLong(f[0].trim()), f[1].trim(),
                    Long.parseLong(f[2].trim()), f[3].trim(), f[4].trim(),
                    "1".equals(f[5].trim()), f[6].trim(), f[7].trim(), f[8].trim(),
                    f[9].trim().isEmpty() ? 0 : Integer.parseInt(f[9].trim()), f[10].trim());
        } catch (Throwable t) {
            ticketDetail = null;
        }
    }

    public static TicketDetail ticketDetail() {
        return ticketDetail;
    }

    public static List<Ticket> tickets() {
        return tickets;
    }

    public static void setOrderDetail(String body) {
        if (body == null || body.isEmpty()) {
            orderDetail = null;
            return;
        }
        String[] f = body.split("\\|", -1);
        if (f.length < 10) {
            orderDetail = null;
            return;
        }
        try {
            orderDetail = new OrderDetail(Long.parseLong(f[0].trim()), f[1].trim(),
                    Integer.parseInt(f[2].trim()), Integer.parseInt(f[3].trim()),
                    Long.parseLong(f[4].trim()), f[5].trim(), f[6].trim(), f[7].trim(),
                    f[8].trim(), Long.parseLong(f[9].trim()));
        } catch (Throwable t) {
            orderDetail = null;
        }
    }

    public static OrderDetail orderDetail() {
        return orderDetail;
    }

    public static List<ItemOrder> itemOrders() {
        return itemOrders;
    }

    public static List<Order> orders() {
        return orders;
    }

    public static List<Market> markets() {
        return markets;
    }

    public static List<Candle> history() {
        return history;
    }

    // ---------------- 银行余额 ----------------
    private static int bankWallet = -1;
    private static int bankCoins = -1;
    private static int bankSafe = -1;
    private static int bankTotal = -1;

    public static void setBank(int wallet, int coins, int safe, int total) {
        bankWallet = wallet;
        bankCoins = coins;
        bankSafe = safe;
        bankTotal = total;
    }

    public static int wallet() { return bankWallet; }
    public static int coins() { return bankCoins; }
    public static int safe() { return bankSafe; }
    public static int total() { return bankTotal; }

    // ---------------- 假人列表 ----------------
    private static String laborList = "";

    public static void setLaborList(String s) {
        laborList = s == null ? "" : s;
    }

    public static String laborList() {
        return laborList;
    }

    /** 国民购买意愿：物品 → 0 低 / 1 中 / 2 高 */
    private static final java.util.Map<String, Integer> WILL = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setWillingness(String itemId, int level) {
        if (itemId != null) {
            WILL.put(itemId, level);
        }
    }

    public static int willingness(String itemId) {
        Integer v = WILL.get(itemId);
        return v == null ? -1 : v;
    }

    /** 参考价下的接单概率（0..100） */
    private static final java.util.Map<String, Integer> FILL = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setFillProb(String itemId, int prob) {
        if (itemId != null) {
            FILL.put(itemId, prob);
        }
    }

    public static int fillProb(String itemId) {
        Integer v = FILL.get(itemId);
        return v == null ? -1 : v;
    }

    private static List<OpenOrder> openOrders = new ArrayList<>();

    public static void setOpenOrders(String body) {
        List<OpenOrder> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                String[] f = line.split("\\|", -1);
                if (f.length < 6) {
                    continue;
                }
                try {
                    long created = 0L;
                    if (f.length >= 7) {
                        try {
                            created = Long.parseLong(f[6].trim());
                        } catch (Throwable ignored) {
                            created = 0L;
                        }
                    }
                    out.add(new OpenOrder("BUY".equals(f[0].trim()),
                            Long.parseLong(f[1].trim()), f[2].trim(),
                            Integer.parseInt(f[3].trim()),
                            Long.parseLong(f[4].trim()), f[5].trim(), created,
                            f.length > 7 && "1".equals(f[7].trim())));
                } catch (Throwable ignored) {
                    // 跳过坏行
                }
            }
        }
        openOrders = out;
    }

    /**
     * 市场未完成订单排序：**我自己 → 别的玩家 → 机器人**（同组内保持服务端原顺序）。
     *
     * <p>用稳定排序，翻页不会跳；机器人标记由服务端在每行第 8 列给出。
     */
    public static List<OpenOrder> openOrders() {
        List<OpenOrder> list = new ArrayList<>(openOrders);
        String me = "";
        try {
            var p = net.minecraft.client.Minecraft.getInstance().player;
            me = p == null ? "" : p.getScoreboardName();
        } catch (Throwable ignored) {
            me = "";
        }
        final String who = me;
        list.sort((a, b) -> Integer.compare(rank(a, who), rank(b, who)));
        return list;
    }

    /** 0 = 自己，1 = 别的玩家，2 = 机器人 */
    private static int rank(OpenOrder o, String me) {
        if (!me.isEmpty() && me.equals(o.owner())) {
            return 0;
        }
        return o.bot() ? 2 : 1;
    }

    public static void setServerDate(String text) {
        SERVER_DATE = text == null ? "" : text;
    }

    public static String serverDate() {
        return SERVER_DATE;
    }

    // ---------------- 订单详情来源 ----------------
    /** true = 从「活跃订单」列表进来的，返回时应回到列表 */
    private static volatile boolean ORDER_DETAIL_FROM_ORDERS = false;

    public static void setOrderDetailFromOrders(boolean v) {
        ORDER_DETAIL_FROM_ORDERS = v;
    }

    public static boolean orderDetailFromOrders() {
        return ORDER_DETAIL_FROM_ORDERS;
    }

    // ---------------- 远洋提单 ----------------
    private static volatile String voyageData = "";

    public static void setVoyage(String s) {
        voyageData = s == null ? "" : s;
    }

    public static String voyage() {
        return voyageData;
    }
}
