package com.tangtu.kjbb;

/**
 * 商城价格桥（供 KubeJS 调用）。
 *
 * 价格存在 config/kjbb/shop_prices.properties —— mixin 扣款和脚本查价读的是同一份。
 */
public final class ShopBridge {

    private ShopBridge() {
    }

    /** 单价；0 = 未定价 */
    public static long price(String itemId) {
        try {
            return ShopPrices.price(itemId);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static boolean hasPrice(String itemId) {
        try {
            return ShopPrices.hasPrice(itemId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean setPrice(String itemId, int price) {
        try {
            return ShopPrices.setPrice(itemId, price);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean removePrice(String itemId) {
        try {
            return ShopPrices.removePrice(itemId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** "id=price;id=price" */
    public static String dump() {
        try {
            return ShopPrices.dump();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static int size() {
        try {
            return ShopPrices.size();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static String path() {
        try {
            return ShopPrices.pathString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean reload() {
        try {
            ShopPrices.reloadFromDisk();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---- 托管账本（C2C）----

    /** 挂牌（带卖家定价）：只记账，不给钱 */
    public static boolean list(String seller, String itemId, int count, int price) {
        try {
            return ShopLedger.deposit(seller, itemId, count, price);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 兼容旧调用：按当前最低卖价（没有就用官方价）挂牌 */
    public static boolean deposit(String seller, String itemId, int count) {
        try {
            long price = ShopLedger.lowestAsk(itemId);
            if (price <= 0L) {
                price = ShopPrices.price(itemId);
            }
            if (price <= 0L) {
                price = 1L;
            }
            return ShopLedger.deposit(seller, itemId, count, price);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 动态单价（库存驱动）；0 = 未定价 */
    public static long dynamicPrice(String itemId, int stock) {
        try {
            return ShopPricing.price(itemId, stock);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static double stockFactor(int stock) {
        try {
            return ShopPricing.factor(stock);
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    public static long targetStock() {
        try {
            return ShopPrices.targetStock();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** 当前最低卖价；没有挂牌返回 0 */
    public static long lowestAsk(String itemId) {
        try {
            return ShopLedger.lowestAsk(itemId);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 零售机成交上报 —— 由 KubeJS 在玩家付款成功后调用。
     *
     * 效果：计入市场价、K 线、玩家活跃度统计。
     *   price 用【实际成交单价】，count 用【卖出的件数】。
     */
    public static boolean recordRetailSale(String itemId, long price, int count) {
        if (itemId == null || itemId.isEmpty() || price <= 0L) {
            return false;
        }
        try {
            MarketPulse.record(itemId, price);
            MarketPrice.onTrade(itemId, price);
            KlineAggregator.onTrade(itemId, (int) Math.max(1L, price), Math.max(1, count));
            TradeActivity.recordPlayerTrade();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把当前最低卖价推到 K 线（让发报机界面显示真实报价） */
    public static boolean pushAsk(String itemId) {
        try {
            long ask = ShopLedger.lowestAsk(itemId);
            if (ask <= 0L) {
                return false;
            }
            return ShopKline.push(itemId, (int) ask);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 报价："total;filled;enough;seller=money,..." */
    public static String quote(String itemId, int count) {
        try {
            ShopLedger.Fill fill = ShopLedger.quote(itemId, count);
            StringBuilder sb = new StringBuilder();
            sb.append(fill.total).append(';').append(fill.filled).append(';')
                    .append(fill.enough ? 1 : 0).append(';');
            boolean first = true;
            for (java.util.Map.Entry<String, Long> e : fill.payouts.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 某物品当前托管总数 */
    public static long remaining(String itemId) {
        try {
            return ShopLedger.remaining(itemId);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** 某卖家的挂牌："itemId=count;..." */
    public static String listings(String seller) {
        try {
            return ShopLedger.listings(seller);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 全服挂牌汇总 */
    public static String allListings() {
        try {
            return ShopLedger.all();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String ledgerPath() {
        try {
            return ShopLedger.pathString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean reloadLedger() {
        try {
            ShopLedger.reloadFromDisk();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---- 货到付款托管（C2C 用）----

    /** 待放款快照：id|buyer|itemId|count|total|ageMs|state，多条用 \n 分隔 */
    public static String pending() {
        try {
            return ShopEscrow.dumpPending();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static int pendingCount() {
        try {
            return ShopEscrow.pendingCount();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 到货：放款给卖家 */
    public static boolean release(long id) {
        try {
            return ShopEscrow.release(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 退款给买家 */
    public static boolean refund(long id) {
        try {
            return ShopEscrow.refund(id, "手动退款");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 超时退款，返回处理条数 */
    public static int expire(long maxAgeMs) {
        try {
            return ShopEscrow.expire(maxAgeMs);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static String escrowPath() {
        try {
            return ShopEscrow.pathString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String version() {
        return "1.8.0";
    }
}
