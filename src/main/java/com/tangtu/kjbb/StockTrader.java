package com.tangtu.kjbb;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 股票做市机器人（大A膏手）
 *
 * 它不看概率表，只看「这艘船现在值多少」：
 *   · 公允价 = 载货货值 ÷ 总份数 × 船况修正（敞口越大越保守）
 *   · 挂单价明显低于公允价（默认 90%）→ 买
 *   · 自己手里的票，公允价明显高于成本（默认 115%）→ 挂出去卖
 *
 * 参数都在 BotConfig.traderStock* 里。
 */
public final class StockTrader {

    /** 上一次扫描的「时间槽」：每天两个槽（0~12000 tick 与 12000~24000 tick） */
    private static long LAST_SLOT = Long.MIN_VALUE;

    private StockTrader() {
    }

    /** 当前时间槽 = 游戏日 × 2 + （半天前/半天后） */
    private static long currentSlot() {
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null || srv.overworld() == null) {
                return -1L;
            }
            long time = srv.overworld().getDayTime();
            long day = time / 24000L;
            long part = (time % 24000L) >= 12000L ? 1L : 0L;
            return day * 2L + part;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** 大A膏手（噪声角色里第一个） */
    private static String trader() {
        try {
            for (BotConfig.Bot b : BotConfig.get().bots) {
                if (b != null && BotConfig.ROLE_NOISE.equals(b.role)) {
                    return b.name;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return "大A膏手";
    }

    /** 公允价：份值 × 船况修正（船况差就压低估值） */
    private static long fairOf(VoyageBill.Bill b) {
        // 参考航次事件：用「发行价 × 当前倍率 × 船况」估预期，不再读到港前的账面份值
        double evt = Math.max(0.2, Math.min(1.5, b.mult / 100.0));
        double cond = Math.max(0.3, Math.min(1.2, b.condition / 100.0));
        long base = Math.max(1L, Math.round(b.price * evt * cond));
        if (base <= 0L) {
            return 0L;
        }
        return Math.max(1L, base);
    }

    public static void tick(MinecraftServer server) {
        var cfg = BotConfig.get();
        if (!cfg.traderStockEnabled) {
            return;
        }
        String me = trader();
        if (me == null) {
            return;
        }
        // 每天只扫两次（上午 / 下午各一次）
        long slot = currentSlot();
        if (slot < 0L || slot == LAST_SLOT) {
            return;
        }
        LAST_SLOT = slot;
        long cash = BotLedger.money(me);
        long budget = cash * Math.max(0, Math.min(1000, cfg.traderStockPermille)) / 1000L;
        if (budget <= 0L) {
            return;
        }

        // ① 一级市场：招标中的船，价格合适就认购
        if (cfg.traderStockPrimary) {
            for (VoyageBill.Bill b : VoyageBill.all()) {
                if (!VoyageBill.OPEN.equals(b.status)) {
                    continue;
                }
                if (b.sold >= b.total || budget <= 0L) {
                    continue;
                }
                long need = b.needValue();
                if (need <= 0L || b.total <= 0) {
                    continue;
                }
                // 预期每份价值 = 计划货值 × 85% ÷ 总份数
                long expected = Math.max(1L, need * 85L / 100L / b.total);
                long limit = expected * Math.max(50, Math.min(100, cfg.traderStockBuyFactor)) / 100L;
                if (b.price > limit) {
                    continue;                       // 发行价太高，不认购
                }
                int wantShares = Math.min(20, b.total - b.sold);
                long amount = (long) wantShares * Math.max(1L, b.price);
                if (amount > budget) {
                    amount = budget;
                }
                if (amount < b.price) {
                    continue;
                }
                String res = VoyageBill.invest(me, amount);
                if (res != null && res.startsWith("§a")) {
                    budget -= amount;
                    DebugLog.log("大A膏手 一级市场认购 船#" + b.id + " " + amount
                            + " 唐兔币（发行价 " + b.price + "，预期 " + expected + "）");
                }
            }
        }

        // ② 买便宜货
        List<StockMarket.Row> rows = new ArrayList<>(StockMarket.open());
        rows.sort(Comparator.comparingLong(r -> r.price));
        for (StockMarket.Row r : rows) {
            if (budget <= 0L) {
                break;
            }
            if (me.equals(r.seller)) {
                continue;
            }
            VoyageBill.Bill b = VoyageBill.get(r.billId);
            if (b == null || VoyageBill.CLOSED.equals(b.status)) {
                continue;
            }
            long fair = fairOf(b);
            if (fair <= 0L) {
                continue;
            }
            long limit = fair * Math.max(50, Math.min(100, cfg.traderStockBuyFactor)) / 100L;
            if (r.price > limit) {
                continue;                       // 太贵，不接
            }
            long cost = r.price * r.shares;
            if (cost > budget) {
                continue;
            }
            String res = StockMarket.botBuy(r, me);
            if ("ok".equals(res)) {
                budget -= cost;
                DebugLog.log("大A膏手在交易板买入 船#" + r.billId + " " + r.shares
                        + " 份 @" + r.price + "（公允 " + fair + "）");
            }
        }

        // ③ 把手里的票挂出去（价格高才挂）
        for (long[] h : StockMarket.botHoldings(me)) {
            long billId = h[0];
            int shares = (int) h[1];
            VoyageBill.Bill b = VoyageBill.get(billId);
            if (b == null || VoyageBill.CLOSED.equals(b.status)) {
                continue;
            }
            long fair = fairOf(b);
            if (fair <= 0L) {
                continue;
            }
            long ask = fair * Math.max(100, Math.min(200, cfg.traderStockSellFactor)) / 100L;
            StockMarket.botList(billId, shares, ask, me);
        }
    }
}
