package com.tangtu.kjbb;

/**
 * 把仓库里的货物接进股市（stockmarket）。
 *
 * 规则：
 *   · 有货 + 有价 → 在股市里建立该标的并开市
 *   · 价格由官方基础价给出（后续成交会把真实价格推进 K 线）
 */
public final class MarketSync {

    private MarketSync() {
    }

    /** 确保某物品在股市里存在并开市；返回 true 表示做了改动 */
    public static boolean sync(String itemId, int price) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        if (price <= 0) {
            price = 1;
        }
        try {
            boolean changed = false;
            if (!MarketBridge.hasMarket(itemId)) {
                if (!MarketBridge.addMarketIfMissing(itemId, price)) {
                    if (!MarketBridge.addMarket(itemId, price)) {
                        return false;
                    }
                }
                changed = true;
            }
            if (!MarketBridge.isMarketOpen(itemId)) {
                MarketBridge.setMarketOpen(itemId, true);
                changed = true;
            }
            return changed;
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 接入股市失败 " + itemId + ": " + t);
            return false;
        }
    }

    /** 把价格表里的所有物品都接进股市；返回新建/开市的数量 */
    public static int syncAll() {
        int n = 0;
        try {
            String dump = ShopPrices.dump();
            if (dump == null) {
                return 0;
            }
            for (String line : dump.split(";")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int eq = s.lastIndexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String id = s.substring(0, eq).replace("\\:", ":").trim();
                long p;
                try {
                    p = Long.parseLong(s.substring(eq + 1).trim());
                } catch (Throwable t) {
                    continue;
                }
                if (sync(id, (int) Math.max(1L, p))) {
                    n++;
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 批量接入股市异常: " + t);
        }
        return n;
    }
}