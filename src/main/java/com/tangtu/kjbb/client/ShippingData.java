package com.tangtu.kjbb.client;

import java.util.ArrayList;
import java.util.List;

/** 航运中心的数据缓存（客户端）—— 服务端 {@code ShippingNetwork} 回包解析 */
public final class ShippingData {

    public static final class Bill {
        public long id;
        public String name = "";
        public String status = "";
        public String outcome = "";
        public int sold;
        public int total;
        public int legs;
        public int legDone;
        public int condition;
        public int mult;
        public int mine;
        public long price;
        public long deadline;
        public long dueDay;
        public long revenue;
        public long arrivalValue;
        public long shareValue;
        public long tariff;
        /** 逐段事件，`;` 分隔（平安 / 逆风 / 风暴 / 海盗 / 触礁 / 沉没） */
        public String legLog = "";
        /** 每段判定日，`,` 分隔，与 legLog 一一对应 */
        public String legDays = "";
    }

    public static final class Line {
        public long billId;
        public String item = "";
        public int have;
        public int need;
    }

    public static final class Holder {
        public long billId;
        public String name = "";
        public int shares;
    }

    public static final class Listing {
        public long id;
        public long billId;
        public int shares;
        public long price;
        public String seller = "";
    }

    public static final class Order {
        public long id;
        public long billId;
        public String side = "";
        public String item = "";
        public int qty;
        public long unit;
        public String status = "";
    }

    public static final class Good {
        public String item = "";
        public long sell;
        public long buy;
        public long stock;
    }

    public static final class CharterLine {
        public String item = "";
        public int qty;
        public long value;
    }

    public static final class Fleet {
        public long id;
        public String name = "";
        public String status = "";
        public int sold;
        public int total;
        public long shareValue;
        public long cash;
        public int mult;
        public long dueDay;
        public int mine;
    }

    private static long day;
    private static long activeBillId;
    private static String activeName = "";
    private static long deadline;
    private static int fx;
    private static long romaCash;
    private static long paid;
    private static long injected;
    private static int tariff;
    private static int importTariff;
    private static int charterFee;
    private static int charterMaxValue;
    private static int charterMaxQty;

    private static boolean hasCharter;
    private static long charterValue;
    private static long freight;

    private static List<Order> orders = new ArrayList<>();
    private static List<Good> goods = new ArrayList<>();
    private static List<CharterLine> charter = new ArrayList<>();
    private static List<Fleet> fleet = new ArrayList<>();
    private static List<Bill> bills = new ArrayList<>();
    private static List<Line> lines = new ArrayList<>();
    private static List<Holder> holders = new ArrayList<>();
    private static List<Listing> listings = new ArrayList<>();

    private static String error = "";
    private static long stamp;

    private ShippingData() {
    }

    public static void set(String body) {
        List<Order> o = new ArrayList<>();
        List<Good> g = new ArrayList<>();
        List<CharterLine> c = new ArrayList<>();
        List<Fleet> f = new ArrayList<>();
        List<Bill> bl = new ArrayList<>();
        List<Line> ln = new ArrayList<>();
        List<Holder> hd = new ArrayList<>();
        List<Listing> ls = new ArrayList<>();
        String err = "";
        long now = 0L;
        long activeId = 0L;
        String activeNm = "";
        long dl = 0L;
        int fxV = 0;
        long cash = 0L;
        long paidV = 0L;
        long inj = 0L;
        int tar = 0;
        int itar = 0;
        int fee = 0;
        int cMax = 0;
        int cMaxQ = 0;
        boolean hasC = false;
        long cVal = 0L;
        long fr = 0L;

        if (body != null) {
            for (String line : body.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split("\t", -1);
                try {
                    switch (p[0]) {
                        case "W" -> {
                            now = Long.parseLong(p[1]);
                            activeId = Long.parseLong(p[2]);
                            activeNm = p[3];
                            dl = Long.parseLong(p[4]);
                            fxV = Integer.parseInt(p[5]);
                            cash = Long.parseLong(p[6]);
                            paidV = Long.parseLong(p[7]);
                            inj = Long.parseLong(p[8]);
                            tar = Integer.parseInt(p[9]);
                            itar = Integer.parseInt(p[10]);
                            fee = Integer.parseInt(p[11]);
                            cMax = Integer.parseInt(p[12]);
                            cMaxQ = Integer.parseInt(p[13]);
                        }
                        case "O" -> {
                            Order or = new Order();
                            or.id = Long.parseLong(p[1]);
                            or.billId = Long.parseLong(p[2]);
                            or.side = p[3];
                            or.item = p[4];
                            or.qty = Integer.parseInt(p[5]);
                            or.unit = Long.parseLong(p[6]);
                            or.status = p[7];
                            o.add(or);
                        }
                        case "G" -> {
                            Good gd = new Good();
                            gd.item = p[1];
                            gd.sell = Long.parseLong(p[2]);
                            gd.buy = Long.parseLong(p[3]);
                            gd.stock = Long.parseLong(p[4]);
                            g.add(gd);
                        }
                        case "C" -> {
                            CharterLine cl = new CharterLine();
                            cl.item = p[1];
                            cl.qty = Integer.parseInt(p[2]);
                            cl.value = Long.parseLong(p[3]);
                            c.add(cl);
                        }
                        case "H" -> {
                            hasC = "1".equals(p[1]);
                            cVal = Long.parseLong(p[2]);
                            fr = Long.parseLong(p[3]);
                        }
                        case "F" -> {
                            Fleet fl = new Fleet();
                            fl.id = Long.parseLong(p[1]);
                            fl.name = p[2];
                            fl.status = p[3];
                            fl.sold = Integer.parseInt(p[4]);
                            fl.total = Integer.parseInt(p[5]);
                            fl.shareValue = Long.parseLong(p[6]);
                            fl.cash = Long.parseLong(p[7]);
                            fl.mult = Integer.parseInt(p[8]);
                            fl.dueDay = Long.parseLong(p[9]);
                            fl.mine = Integer.parseInt(p[10]);
                            f.add(fl);
                        }
                        case "B" -> {
                            Bill b = new Bill();
                            b.id = Long.parseLong(p[1]);
                            b.name = p[2];
                            b.status = p[3];
                            b.sold = Integer.parseInt(p[4]);
                            b.total = Integer.parseInt(p[5]);
                            b.price = Long.parseLong(p[6]);
                            b.deadline = Long.parseLong(p[7]);
                            b.dueDay = Long.parseLong(p[8]);
                            b.legs = Integer.parseInt(p[9]);
                            b.legDone = Integer.parseInt(p[10]);
                            b.condition = Integer.parseInt(p[11]);
                            b.mult = Integer.parseInt(p[12]);
                            b.outcome = p[13];
                            b.mine = Integer.parseInt(p[14]);
                            b.revenue = Long.parseLong(p[15]);
                            b.arrivalValue = Long.parseLong(p[16]);
                            b.shareValue = p.length > 17 ? Long.parseLong(p[17]) : 0L;
                            b.tariff = p.length > 18 ? Long.parseLong(p[18]) : 0L;
                            b.legLog = p.length > 19 ? p[19] : "";
                            b.legDays = p.length > 20 ? p[20] : "";
                            bl.add(b);
                        }
                        case "L" -> {
                            Line l = new Line();
                            l.billId = Long.parseLong(p[1]);
                            l.item = p[2];
                            l.have = Integer.parseInt(p[3]);
                            l.need = Integer.parseInt(p[4]);
                            ln.add(l);
                        }
                        case "P" -> {
                            Holder h = new Holder();
                            h.billId = Long.parseLong(p[1]);
                            h.name = p[2];
                            h.shares = Integer.parseInt(p[3]);
                            hd.add(h);
                        }
                        case "S" -> {
                            Listing l = new Listing();
                            l.id = Long.parseLong(p[1]);
                            l.billId = Long.parseLong(p[2]);
                            l.shares = Integer.parseInt(p[3]);
                            l.price = Long.parseLong(p[4]);
                            l.seller = p[5];
                            ls.add(l);
                        }
                        case "X" -> err = (p.length > 2 ? p[1] + " " + p[2] : "读取失败");
                        default -> {
                            // 忽略未知类型，方便以后加字段
                        }
                    }
                } catch (Throwable ignored) {
                    // 单行坏掉不影响其它
                }
            }
        }

        day = now;
        activeBillId = activeId;
        activeName = activeNm;
        deadline = dl;
        fx = fxV;
        romaCash = cash;
        paid = paidV;
        injected = inj;
        tariff = tar;
        importTariff = itar;
        charterFee = fee;
        charterMaxValue = cMax;
        charterMaxQty = cMaxQ;
        hasCharter = hasC;
        charterValue = cVal;
        freight = fr;
        orders = o;
        goods = g;
        charter = c;
        fleet = f;
        bills = bl;
        lines = ln;
        holders = hd;
        listings = ls;
        error = err;
        stamp = System.currentTimeMillis();
    }

    public static boolean empty() {
        return stamp == 0L;
    }

    public static long stamp() {
        return stamp;
    }

    public static String error() {
        return error;
    }

    public static long day() {
        return day;
    }

    public static long activeBillId() {
        return activeBillId;
    }

    public static String activeName() {
        return activeName;
    }

    public static long deadline() {
        return deadline;
    }

    public static int fx() {
        return fx;
    }

    public static long romaCash() {
        return romaCash;
    }

    public static long paid() {
        return paid;
    }

    public static long injected() {
        return injected;
    }

    public static int tariff() {
        return tariff;
    }

    public static int importTariff() {
        return importTariff;
    }

    public static int charterFee() {
        return charterFee;
    }

    public static int charterMaxValue() {
        return charterMaxValue;
    }

    public static int charterMaxQty() {
        return charterMaxQty;
    }

    public static boolean hasCharter() {
        return hasCharter;
    }

    public static long charterValue() {
        return charterValue;
    }

    public static long freight() {
        return freight;
    }

    public static List<Order> orders() {
        return orders;
    }

    public static List<Good> goods() {
        return goods;
    }

    public static List<CharterLine> charter() {
        return charter;
    }

    public static List<Fleet> fleet() {
        return fleet;
    }

    public static List<Bill> bills() {
        return bills;
    }

    public static Bill bill(long id) {
        for (Bill b : bills) {
            if (b.id == id) {
                return b;
            }
        }
        return null;
    }

    /** 状态筛选：OPEN / VOYAGING / CLOSED；空串 = 全部 */
    public static List<Bill> bills(String status) {
        List<Bill> out = new ArrayList<>();
        for (Bill b : bills) {
            if (status == null || status.isEmpty() || status.equals(b.status)) {
                out.add(b);
            }
        }
        return out;
    }

    public static Bill first(String status) {
        for (Bill b : bills) {
            if (status.equals(b.status)) {
                return b;
            }
        }
        return null;
    }

    public static List<Line> linesOf(long billId) {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            if (l.billId == billId) {
                out.add(l);
            }
        }
        return out;
    }

    public static List<Holder> holdersOf(long billId) {
        List<Holder> out = new ArrayList<>();
        for (Holder h : holders) {
            if (h.billId == billId) {
                out.add(h);
            }
        }
        out.sort((a, b) -> Integer.compare(b.shares, a.shares));
        return out;
    }

    /** 我持有的航次（份额 > 0） */
    public static List<Bill> mine() {
        List<Bill> out = new ArrayList<>();
        for (Bill b : bills) {
            if (b.mine > 0) {
                out.add(b);
            }
        }
        return out;
    }

    public static List<Listing> listings() {
        return listings;
    }
}
