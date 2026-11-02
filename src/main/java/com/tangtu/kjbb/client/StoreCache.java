package com.tangtu.kjbb.client;

import java.util.ArrayList;
import java.util.List;

/** 仓库货品清单缓存（客户端）：id|count|unitPrice */
public final class StoreCache {

    public record Entry(String id, int count, long unit) {
    }

    private static List<Entry> entries = new ArrayList<>();

    private StoreCache() {
    }

    public static void set(String body) {
        List<Entry> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                String[] f = line.split("\\|");
                if (f.length < 3) {
                    continue;
                }
                int c;
                long u;
                try {
                    c = Integer.parseInt(f[1].trim());
                    u = Long.parseLong(f[2].trim());
                } catch (Throwable t) {
                    continue;
                }
                out.add(new Entry(f[0].trim(), c, u));
            }
        }
        entries = out;
    }

    public static List<Entry> entries() {
        return entries;
    }

    /** 待发货订单：id|itemId|count */
    public record Shipment(long id, String itemId, int count) {
    }

    private static List<Shipment> shipments = new ArrayList<>();

    public static void setShipments(String body) {
        List<Shipment> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                String[] f = line.split("\\|");
                if (f.length < 3) {
                    continue;
                }
                try {
                    out.add(new Shipment(Long.parseLong(f[0].trim()), f[1].trim(),
                            Integer.parseInt(f[2].trim())));
                } catch (Throwable ignored) {
                    // skip
                }
            }
        }
        shipments = out;
        System.out.println("[TangtuEconomical] 待发货收到 " + out.size() + " 条");
    }

    public static List<Shipment> shipments() {
        return shipments;
    }
}
