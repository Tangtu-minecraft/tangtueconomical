package com.tangtu.kjbb;

import java.util.ArrayList;
import java.util.List;

/**
 * 「市场」的 Java 侧封装 —— 供 KubeJS 调用。
 *
 * 已改为完全基于自建系统（不再依赖 stockmarket / banksystem）：
 *   markets()           → 所有有定价的物品
 *   price(itemId)       → 当前市场价（成交驱动的浮动价）
 *   orders(itemId)      → 「订单 + 挂单」合并快照
 *
 * 订单快照格式（每行一张单，'\n' 分隔）：
 *   id|类型|玩家|B或S|物品|数量|单价|状态
 */
public final class MarketBridge {

    private MarketBridge() {
    }

    /** 所有有定价的物品 id，逗号分隔 */
    public static String markets() {
        try {
            // 基准价物品（机器人交易用）+ 玩家真实挂单/买单里的物品（玩家交易用）
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(ShopPrices.items());
            try {
                for (var o : OrderBook.byStatus(OrderBook.PENDING)) {
                    if (o.itemId != null && !o.itemId.isEmpty()) {
                        set.add(o.itemId);
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                for (var o : OfferBook.open()) {
                    if (o.itemId != null && !o.itemId.isEmpty()) {
                        set.add(o.itemId);
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
            List<String> ids = new java.util.ArrayList<>(set);
            return ids.isEmpty() ? "" : String.join(",", ids);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static int marketCount() {
        try {
            return ShopPrices.items().size();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static boolean hasMarket(String itemId) {
        return ShopPrices.price(itemId) > 0;
    }

    /** 市场是否「开市」：有定价就算开市 */
    public static boolean isMarketOpen(String itemId) {
        return hasMarket(itemId);
    }

    public static boolean setMarketOpen(String itemId, boolean open) {
        // 自建系统没有开关市概念，保留接口返回成功
        return hasMarket(itemId);
    }

    public static boolean addMarket(String itemId, int price) {
        return ShopPrices.setPrice(itemId, price);
    }

    public static boolean addMarketIfMissing(String itemId, int price) {
        if (hasMarket(itemId)) {
            return false;
        }
        return ShopPrices.setPrice(itemId, price);
    }

    public static boolean removeMarket(String itemId) {
        return ShopPrices.removePrice(itemId);
    }

    /** 当前市场价（成交驱动的浮动价） */
    public static int price(String itemId) {
        try {
            return (int) MarketPrice.price(itemId);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 某物品的「订单 + 挂单」快照 */
    public static String orders(String itemId) {
        try {
            StringBuilder sb = new StringBuilder();
            for (OrderBook.Row r : OrderBook.byStatus(OrderBook.PENDING)) {
                if (itemId != null && !itemId.isEmpty() && !r.itemId.equalsIgnoreCase(itemId)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(r.id).append("|ORDER|").append(r.buyer).append('|')
                        .append("B|").append(r.itemId).append('|').append(r.count).append('|')
                        .append(r.unit).append('|').append(r.status);
            }
            for (OfferBook.Row r : OfferBook.open()) {
                if (itemId != null && !itemId.isEmpty() && !r.itemId.equalsIgnoreCase(itemId)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(r.id).append("|OFFER|").append(r.seller).append('|')
                        .append("S|").append(r.itemId).append('|').append(r.count).append('|')
                        .append(r.price).append('|').append(r.status);
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static int orderCount(String itemId) {
        try {
            int n = 0;
            for (OrderBook.Row r : OrderBook.byStatus(OrderBook.PENDING)) {
                if (itemId == null || itemId.isEmpty() || r.itemId.equalsIgnoreCase(itemId)) {
                    n++;
                }
            }
            for (OfferBook.Row r : OfferBook.open()) {
                if (itemId == null || itemId.isEmpty() || r.itemId.equalsIgnoreCase(itemId)) {
                    n++;
                }
            }
            return n;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static String ordersOf(String playerId) {
        try {
            StringBuilder sb = new StringBuilder();
            // 我下的单（PENDING，部分 DONE 也显示）
            for (OrderBook.Row r : OrderBook.byStatus(OrderBook.PENDING)) {
                if (!r.buyer.equalsIgnoreCase(playerId)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(r.id).append("|ORDER|").append(r.itemId).append('|').append(r.count)
                        .append('|').append(r.unit).append('|').append(r.status);
            }
            // 我锁定的单（ACCEPTED，seller = 我）
            for (OrderBook.Row r : OrderBook.byStatus(OrderBook.ACCEPTED)) {
                if (r.seller == null || !r.seller.equalsIgnoreCase(playerId)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(r.id).append("|ORDER|").append(r.itemId).append('|').append(r.count)
                        .append('|').append(r.unit).append("|ACCEPTED");
            }
            for (OfferBook.Row r : OfferBook.open()) {
                if (!r.seller.equalsIgnoreCase(playerId)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(r.id).append("|OFFER|").append(r.itemId).append('|').append(r.count)
                        .append('|').append(r.price).append('|').append(r.status);
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean cancelOrder(long orderId) {
        try {
            if (OrderBook.get(orderId) != null) {
                OrderBook.setStatus(orderId, OrderBook.CANCELLED, null);
                return true;
            }
            if (OfferBook.get(orderId) != null) {
                OfferBook.setStatus(orderId, OfferBook.CANCELLED);
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int cancelAllOf(String playerId) {
        try {
            int n = 0;
            for (OrderBook.Row r : new ArrayList<>(OrderBook.byStatus(OrderBook.PENDING))) {
                if (r.buyer.equalsIgnoreCase(playerId)) {
                    OrderBook.setStatus(r.id, OrderBook.CANCELLED, null);
                    n++;
                }
            }
            for (OfferBook.Row r : new ArrayList<>(OfferBook.open())) {
                if (r.seller.equalsIgnoreCase(playerId)) {
                    OfferBook.setStatus(r.id, OfferBook.CANCELLED);
                    n++;
                }
            }
            return n;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static String version() {
        return "2.0.0-selfhost";
    }
}
