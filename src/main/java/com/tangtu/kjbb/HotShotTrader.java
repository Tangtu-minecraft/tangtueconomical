package com.tangtu.kjbb;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 大A膏手：一切为了盈利的投机客
 *
 * 行为特征：
 *   · 低买高卖，主动挂单抢生意（不像做市商那样维稳）
 *   · 但「看得准」不是必然：只有一部分决策是精明的，
 *     其余会追高买入 / 恐慌贱卖 —— 所以经常亏钱
 *
 * 结果：长期看他的资金曲线会上下大幅波动，
 *       而不是稳定盈利。
 */
public final class HotShotTrader {

    private HotShotTrader() {
    }

    /** 精明决策的概率（0.6 = 六成看准，四成追涨杀跌） */
    public static double SMART_CHANCE = 0.60;
    /** 每次行动最多挂几单 */
    private static final int MAX_ACTIONS = 3;
    /** 每次挂单量 */
    private static final int TRADE_COUNT = 32;

    private static int TICK = 0;
    private static long LAST_LOG = 0L;

    /** 上次发放补助的游戏日 */
    private static long GRANT_DAY = -1L;
    private static boolean STOPPED_LOGGED = false;

    private static java.nio.file.Path stateFile() {
        return java.nio.file.Path.of("config", "kjbb", "trader_state.txt");
    }

    private static synchronized void loadState() {
        if (GRANT_DAY >= 0L) {
            return;
        }
        try {
            var p = stateFile();
            if (java.nio.file.Files.exists(p)) {
                String s = java.nio.file.Files.readString(p,
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    GRANT_DAY = Long.parseLong(s);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        GRANT_DAY = 0L;
    }

    private static synchronized void saveState() {
        try {
            var p = stateFile();
            if (p.getParent() != null) {
                java.nio.file.Files.createDirectories(p.getParent());
            }
            java.nio.file.Files.writeString(p, Long.toString(GRANT_DAY),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static long currentDay() {
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv != null && srv.overworld() != null) {
                return srv.overworld().getDayTime() / 24000L;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0L;
    }

    /** 每 30 日发 5000 补助（濒临破产者的救济金） */
    private static void grantIfDue(String me, BotConfig cfg) {
        loadState();
        long day = currentDay();
        if (GRANT_DAY <= 0L) {
            GRANT_DAY = day;
            saveState();
            return;
        }
        int period = Math.max(1, cfg.traderGrantDays);
        if (day - GRANT_DAY < period) {
            return;
        }
        GRANT_DAY = day;
        saveState();
        long amount = Math.max(0L, cfg.traderGrantAmount);
        if (amount <= 0L) {
            return;
        }
        BotLedger.addMoney(me, amount);
        BotLedger.save();
        BotTrades.log("-", me, "\u8865\u52a9\u91d1", 1, amount, "\u8865\u52a9");
        System.out.println("[TangtuEconomical] \u5927A\u818f\u624b\u9886\u53d6\u8865\u52a9\u91d1 +"
                + amount + "\uff08\u6bcf " + period + " \u65e5\uff09");
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv != null) {
                for (var sp : srv.getPlayerList().getPlayers()) {
                    if (me.equalsIgnoreCase(sp.getScoreboardName())) {
                        sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7a[\u5927A\u818f\u624b] \u9886\u53d6\u8865\u52a9\u91d1 "
                                        + amount + " \u5510\u5154\u5e01"), false);
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 找到「投机客」机器人（role = noise） */
    private static String trader() {
        try {
            for (var b : BotConfig.get().bots) {
                if (b != null && "noise".equals(b.role)) {
                    return b.name;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return "\u5927A\u818f\u624b";
    }

    /** 每 5 秒调用一次；内部按计数节流 */
    /** 库存按市价折算的总价值 */
    private static long stockValue(String bot) {
        long sum = 0L;
        try {
            for (var e : BotLedger.stockMap(bot).entrySet()) {
                if (e.getValue() <= 0) {
                    continue;
                }
                long p = MarketPrice.price(e.getKey());
                if (p <= 0L) {
                    p = ShopPrices.price(e.getKey());
                }
                if (p <= 0L) {
                    p = 1L;
                }
                sum += p * e.getValue();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return sum;
    }

    public static void tick() {
        // 投机客设为「只做金融」时，不做任何实物买卖
        try {
            if (com.tangtu.kjbb.BotConfig.get().traderFinancialOnly) {
                return;
            }
        } catch (Throwable ignored) {
            // 配置读不到按老行为
        }
        if (++TICK < 24) {          // 24 × 5 秒 = 2 分钟一轮
            return;
        }
        TICK = 0;
        try {
            act();
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u5927A\u818f\u624b\u51fa\u9519: " + t);
        }
    }

    private static void act() {
        String me = trader();
        long pool = BotLedger.money(me);
        var cfg = BotConfig.get();
        // 补助（每 30 日一次）
        try {
            grantIfDue(me, cfg);
            pool = BotLedger.money(me);
        } catch (Throwable ignored) {
            // ignore
        }

        // 资金紧张 → 只清库存（不买入、不追高），等待补助
        long stopAt = Math.max(0L, cfg.traderStopMoney);
        // 只有「钱少 且 货也不值钱」才算穷；货多时不该恐慌抛售
        long stockValue = stockValue(me);
        boolean poorCash = pool < stopAt;
        boolean richStock = stockValue >= Math.round(
                stopAt * Math.max(0.0, BotConfig.get().traderStopStockValueFactor));
        boolean liquidateOnly = poorCash && !richStock;
        if (liquidateOnly && !STOPPED_LOGGED) {
            STOPPED_LOGGED = true;
            System.out.println("[TangtuEconomical] \u5927A\u818f\u624b\u8d44\u91d1 " + pool
                    + " \u4f4e\u4e8e " + stopAt + "\uff0c\u5e93\u5b58\u4ef7\u503c " + stockValue
                    + "\uff0c\u8f6c\u4e3a\u6e05\u5e93\u5b58\u6a21\u5f0f");
        } else if (!liquidateOnly && STOPPED_LOGGED) {
            STOPPED_LOGGED = false;
            System.out.println("[TangtuEconomical] \u5927A\u818f\u624b\u6062\u590d\u4ea4\u6613\uff08\u8d44\u91d1 "
                    + pool + "\uff09");
        }
        try {
            SMART_CHANCE = Math.max(0.0, Math.min(1.0, cfg.traderSmartChance));
        } catch (Throwable ignored) {
            // 用默认
        }
        List<String> items = ShopPrices.items();
        if (items.isEmpty()) {
            return;
        }
        var rnd = ThreadLocalRandom.current();
        int stockTotal0 = 0;
        for (var e3 : BotLedger.stockMap(me).entrySet()) {
            stockTotal0 += e3.getValue();
        }
        System.out.println("[TangtuEconomical] \u5927A\u818f\u624b act\uff1a\u8d44\u91d1 " + pool
                + "\uff0c\u505c\u5de5\u7ebf " + stopAt + "\uff0c\u6e05\u5e93\u5b58=" + liquidateOnly
                + "\uff0c\u7269\u54c1\u6c60 " + items.size() + "\uff0c\u5e93\u5b58 " + stockTotal0);
        int actions = 0;
        int guard = 0;
        while (actions < MAX_ACTIONS && guard++ < 24) {
            String item = items.get(rnd.nextInt(items.size()));
            long base = ShopPrices.price(item);
            if (base <= 0L) {
                continue;
            }
            // 看盘：优先用当前市场价做参考（没有市价才退回基准价）
            long marketRef = MarketPrice.price(item);
            if (marketRef <= 0L) {
                marketRef = base;
            }
            boolean smart = rnd.nextDouble() < SMART_CHANCE;
            int have = BotLedger.stock(me, item);
            // 全库存太重时强制卖；太空时才买（避免只买不卖囤成仓库）
            int stockTotal = 0;
            for (var e2 : BotLedger.stockMap(me).entrySet()) {
                stockTotal += e2.getValue();
            }
            if (liquidateOnly || stockTotal > 3000) {
                have = Math.max(have, 64);   // 强制卖
            } else if (stockTotal < 400) {
                have = 0;                    // 强制买
            }
            if (have >= 64) {
                // 卖出：精明 → 抬价；失误 → 贱卖
                double mul = liquidateOnly
                        ? 0.92 + rnd.nextDouble() * 0.10      // 清库存：贴近市价
                        : (smart ? 0.98 + rnd.nextDouble() * 0.10   // 精明：略高于市价，玩家愿意买
                        : 0.72 + rnd.nextDouble() * 0.12);        // 失误：贱卖
                long price = Math.max(1L, Math.round(marketRef * mul));
                int count = Math.min(have, TRADE_COUNT);
                if (count < 8) {
                    continue;
                }
                OfferBook.add(me, item, count, price);
                BotLedger.addStock(me, item, -count);
                // 挂卖单不算成交，成交时由 crossTrade/买单记录
                String sellTag = liquidateOnly ? "\u6e05\u4ed3\u5356\u51fa"
                        : (smart ? "\u62ac\u4ef7\u5356\u51fa" : "\u8d31\u5356\u4e86");
                BotTrades.log(me, "-", item, count, price, sellTag);
                System.out.println("[TangtuEconomical] \u5927A\u818f\u624b " + sellTag + " " + item
                        + " \u00d7" + count + " @" + price);
            } else {
                // 买入：精明 → 压价；失误 → 追高
                double mul = smart ? 0.90 + rnd.nextDouble() * 0.08
                        : 1.02 + rnd.nextDouble() * 0.10;
                long price = Math.max(1L, Math.round(marketRef * mul));
                // 失误时才会追高；精明时绝不高于市价
                if (smart && price >= marketRef) {
                    price = Math.max(1L, Math.round(marketRef * 0.97));
                }
                int count = TRADE_COUNT;
                long total = price * count;
                if (total > pool / 4) {
                    continue;
                }
                OrderBook.add(me, item, count, price, me);
                BotLedger.addMoney(me, -total);
                com.tangtu.kjbb.TraderPnl.buy(item, count, price);
                BotTrades.log(me, "-", item, count, price,
                        smart ? "\u4f4e\u4ef7\u626b\u8d27" : "\u8ffd\u9ad8\u4e86");
                System.out.println("[TangtuEconomical] \u5927A\u818f\u624b "
                        + (smart ? "\u4f4e\u4ef7\u626b\u8d27" : "\u8ffd\u9ad8") + " " + item
                        + " \u00d7" + count + " @" + price);
            }
            actions++;
        }
        BotLedger.save();
        System.out.println("[TangtuEconomical] \u5927A\u818f\u624b \u672c\u8f6e\u52a8\u4f5c " + actions
                + " \u7b14\uff08\u5c1d\u8bd5 " + guard + " \u6b21\uff09");
    }
}
