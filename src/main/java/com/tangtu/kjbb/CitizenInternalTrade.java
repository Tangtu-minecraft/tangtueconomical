package com.tangtu.kjbb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 国民内部交易（取代机器人对敲的价格发现方式）
 *
 * 思路：
 *   唐兔国国民自己分成三层（温饱 / 小康 / 富足），
 *   定时在内部做一批买卖：
 *     温饱层买粮、 小康层买建材、 富足层买珍宝。
 *   这些成交不改动机器人账本，只用来：
 *     ① 推动市价（MarketPrice 的 EMA）
 *     ② 产生 K 线
 *     ③ 给「交易活跃度」提供成交量
 *
 * 这样即使没有玩家、没有机器人对敲，物价依然会「有生命」。
 */
public final class CitizenInternalTrade {

    private CitizenInternalTrade() {
    }

    private static int TICK = 0;
    private static long LAST_LOG = 0L;

    /** 每 5 秒调用一次，内部按分钟节流 */
    public static void tick() {
        var cfg = BotConfig.get();
        if (!cfg.citizenInternalTradeEnabled) {
            return;
        }
        int everyMin = Math.max(1, cfg.citizenInternalTradeIntervalMinutes);
        int needCalls = Math.max(1, everyMin * 60 / 5);
        if (++TICK < needCalls) {
            return;
        }
        TICK = 0;
        try {
            batch(cfg);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u56fd\u6c11\u5185\u90e8\u4ea4\u6613\u51fa\u9519: " + t);
        }
    }

    /** 生成一批内部成交 */
    private static void batch(BotConfig cfg) {
        String citizen = MarketBots.citizenName();
        if (citizen == null) {
            return;
        }
        double pop = Math.max(1.0, MarketBots.citizenPopulation());
        int[] layers = MarketBots.citizenLayers();   // 温饱 / 小康 / 富足（百分比）
        var rnd = ThreadLocalRandom.current();

        List<String> pool = ShopPrices.items();
        if (pool.isEmpty()) {
            return;
        }

        // 本批成交笔数：随人口增长，但有上限
        int trades = (int) Math.min(Math.max(1, cfg.citizenInternalTradeMaxTrades),
                Math.max(1, Math.round(pop / 4.0)));

        long volume = 0L;
        int done = 0;
        for (int i = 0; i < trades * 3 && done < trades; i++) {
            String item = pool.get(rnd.nextInt(pool.size()));
            long base = ShopPrices.price(item);
            if (base <= 0L) {
                continue;
            }
            // 按阶层需求加权：温饱看粮、小康看建材、富足看珍宝
            double weight = weightOf(item, layers, cfg);
            if (weight <= 0.0 || rnd.nextDouble() > weight) {
                continue;
            }
            long market = MarketPrice.price(item);
            if (market <= 0L) {
                market = base;
            }
            // 内部成交价围绕市价小幅波动
            double f = 0.96 + rnd.nextDouble() * 0.08;
            long price = Math.max(1L, Math.round(market * f));
            int count = Math.max(1, cfg.citizenInternalTradeCountMin
                    + rnd.nextInt(Math.max(1,
                    cfg.citizenInternalTradeCountMax - cfg.citizenInternalTradeCountMin + 1)));

            // 推动行情
            try {
                MarketPulse.record(item, price);
                MarketPrice.onTrade(item, price);
                KlineAggregator.onTrade(item, (int) price, count);
            } catch (Throwable ignored) {
                // ignore
            }
            // 记账（不涉及资金转移，只记成交量）
            try {
                BotTrades.log(citizen, citizen, item, count, price,
                        "\u56fd\u6c11\u5185\u90e8\u4ea4\u6613");
            } catch (Throwable ignored) {
                // ignore
            }
            volume += price * count;
            done++;
        }

        if (done > 0) {
            long now = ServerClock.now();
            if (now - LAST_LOG > 60_000L) {
                LAST_LOG = now;
                DebugLog.log("\u56fd\u6c11\u5185\u90e8\u4ea4\u6613 " + done + " \u7b14\uff0c\u6210\u4ea4\u989d " + volume);
                System.out.println("[TangtuEconomical] \u56fd\u6c11\u5185\u90e8\u4ea4\u6613\uff1a" + done
                        + " \u7b14\uff0c\u6210\u4ea4\u989d " + volume
                        + "\uff08\u6e29\u9971 " + layers[0] + "% / \u5c0f\u5eb7 " + layers[1] + "%\uff09");
            }
        }
    }

    /** 物品对某一阶层的吸引力（0~1） */
    private static double weightOf(String item, int[] layers, BotConfig cfg) {
        try {
            boolean food = FoodTable.isFood(item);
            boolean premium = FoodTiers.isPremium(item);
            // materialOf / treasureOf 返回的是「弹性系数」，用它是否等于默认值判断品类
            double materialE = ElasticityReloadListener.materialOf(item);
            double treasureE = ElasticityReloadListener.treasureOf(item);
            double defaultE = ElasticityReloadListener.of(item, 0, false);
            boolean isMaterial = Math.abs(materialE - defaultE) > 1.0e-6;
            boolean isTreasure = treasureE > 0.0 && Math.abs(treasureE - defaultE) > 1.0e-6;

            double poorWant = food && !premium ? 0.80 : (food ? 0.40 : 0.05);
            double midWant = isMaterial || (!food && !isTreasure) ? 0.55 : 0.20;
            double richWant = isTreasure ? 0.60 : (premium ? 0.40 : 0.10);

            double w = (layers[0] / 100.0) * poorWant
                    + (layers[1] / 100.0) * midWant
                    + (Math.max(0, 100 - layers[0] - layers[1]) / 100.0) * richWant;
            return Math.max(0.0, Math.min(1.0, w * Math.max(0.1, cfg.citizenInternalTradeChance)));
        } catch (Throwable ignored) {
            return 0.1;
        }
    }
}
