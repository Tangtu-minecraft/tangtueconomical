package com.tangtu.kjbb;

/**
 * 兼容层：以前直接把价格写进 stockmarket 的 PriceHistory。
 * 现在全部走自建的 KlineStore / KlineAggregator，这里只保留同名 API，
 * 让旧的调用点（ShopBridge、mixin）不报错，内部转成我们的聚合器。
 */
public final class ShopKline {

    private ShopKline() {
    }

    /** 单笔成交价（自动并入 1 分钟 K 线） */
    public static boolean push(String itemId, int price) {
        if (itemId == null || itemId.isEmpty() || price <= 0) {
            return false;
        }
        KlineAggregator.onTrade(itemId, price, 1);
        return true;
    }

    /** 一根完整蜡烛 */
    public static boolean pushCandle(String itemId, int open, int high, int low, int close) {
        return push(itemId, close);
    }

    public static String version() {
        return "2.0.0-selfhost";
    }
}
