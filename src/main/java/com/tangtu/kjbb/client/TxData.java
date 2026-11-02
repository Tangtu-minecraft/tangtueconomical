package com.tangtu.kjbb.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易中心的客户端缓存：我的挂单 / 托管订单 / 投递记录 / 提货点 / 服务器时钟。
 *
 * <p>数据由 {@code net/TxNetwork} 的 TxInfo 分段下发，这里解析成可读对象。
 */
public final class TxData {

    private TxData() {
    }

    /** 我的挂单 */
    public static final class Offer {
        public long id;
        public String item = "";
        public int count;
        public long price;
    }

    /** 托管订单 */
    public static final class Order {
        public long id;
        public String item = "";
        public int count;
        public long unit;
        public String status = "";
        public String other = "";
        public long created;
        public long escrow;
    }

    /** 投递记录 */
    public static final class Mail {
        public String time = "";
        public String seller = "";
        public String buyer = "";
        public String item = "";
        public int count;
        public String mode = "";
    }

    private static final List<Offer> OFFERS = new ArrayList<>();
    private static final List<Order> ORDERS = new ArrayList<>();
    private static final List<Mail> MAILS = new ArrayList<>();
    private static long serverNow;
    private static String address = "";

    public static void set(String body) {
        OFFERS.clear();
        ORDERS.clear();
        MAILS.clear();
        serverNow = 0L;
        address = "";
        if (body == null || body.isEmpty()) {
            return;
        }
        for (String line : body.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            try {
                switch (f[0]) {
                    case "N" -> serverNow = Long.parseLong(f[1].trim());
                    case "A" -> address = f.length > 1 ? f[1] : "";
                    case "M" -> {
                        Offer o = new Offer();
                        o.id = Long.parseLong(f[1]);
                        o.item = f[2];
                        o.count = Integer.parseInt(f[3]);
                        o.price = Long.parseLong(f[4]);
                        OFFERS.add(o);
                    }
                    case "O" -> {
                        Order t = new Order();
                        t.id = Long.parseLong(f[1]);
                        t.item = f[2];
                        t.count = Integer.parseInt(f[3]);
                        t.unit = Long.parseLong(f[4]);
                        t.status = f[5];
                        t.other = f[6];
                        t.created = Long.parseLong(f[7]);
                        t.escrow = Long.parseLong(f[8]);
                        ORDERS.add(t);
                    }
                    case "L" -> {
                        Mail m = new Mail();
                        String raw = f.length > 1 ? f[1] : "";
                        String[] p = raw.split("\\|", -1);
                        if (p.length >= 6) {
                            m.time = p[0];
                            m.seller = p[1];
                            m.buyer = p[2];
                            m.item = p[3];
                            try {
                                m.count = Integer.parseInt(p[4].trim());
                            } catch (Throwable ignored) {
                                m.count = 0;
                            }
                            m.mode = p[5];
                        } else {
                            m.time = raw;
                        }
                        MAILS.add(m);
                    }
                    default -> {
                        // 未知段忽略
                    }
                }
            } catch (Throwable ignored) {
                // 坏行跳过
            }
        }
    }

    public static List<Offer> offers() {
        return OFFERS;
    }

    public static List<Order> orders() {
        return ORDERS;
    }

    public static List<Mail> mails() {
        return MAILS;
    }

    /** 服务器时钟（毫秒口径），0 = 还没收到 */
    public static long now() {
        return serverNow;
    }

    public static String address() {
        return address;
    }

    /** 我的挂单合计市值 */
    public static long offersValue() {
        long t = 0L;
        for (Offer o : OFFERS) {
            t += o.price * o.count;
        }
        return t;
    }
}
