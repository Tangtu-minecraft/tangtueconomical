package com.tangtu.kjbb;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 经济部税收 + 危机收购
 *
 * ① 税收：玩家参与的每笔成交，抽 1% 交给「经济部」
 *    （只对玩家抽，机器人之间交易不抽，避免左手倒右手）
 *
 * ② 危机收购：唐兔国国民资金低于警戒线时，
 *    经济部按挂牌价把国民的「甩卖单」全部买下（不碰大A膏手的单）
 */
public final class RegulatorOps {

    private RegulatorOps() {
    }

    /** 税率（千分比）：10 = 1% */
    public static int TAX_PERMILLE = 10;

    /** 经济部名字 */
    private static String regulator() {
        try {
            for (var b : BotConfig.get().bots) {
                if (b != null && "regulator".equals(b.role)) {
                    return b.name;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return "\u7ecf\u6d4e\u90e8";
    }

    /**
     * 收税：从 payer 身上抽 amount 的 1% 给经济部
     *
     * @return 实际收到的税额（0 表示没抽到）
     */
    /**
     * 按名字收税（玩家和机器人都能用）。
     *
     * 玩家从钱包扣，机器人从账本扣，钱统一进国库。
     * 这是唯一可无头测试的入口——`tax(ServerPlayer,...)` 只是包了一层。
     */
    public static long taxByName(String who, long amount) {
        if (who == null || who.isEmpty() || amount <= 0L) {
            return 0L;
        }
        String reg = regulator();
        if (reg == null || reg.isEmpty() || reg.equals(who)) {
            return 0L;                    // 自己交易不抽自己的税
        }
        int pm;
        try {
            pm = Math.max(0, BotConfig.get().regulatorTaxPermille);
        } catch (Throwable t) {
            pm = 10;
        }
        if (pm <= 0) {
            return 0L;
        }
        long fee = Math.max(1L, amount * pm / 1000L);
        long have;
        try {
            have = com.tangtu.kjbb.BankBridge.money(who);
        } catch (Throwable t) {
            have = 0L;
        }
        if (have < fee) {
            return 0L;
        }
        try {
            long after = com.tangtu.kjbb.BankBridge.takeMoney(who, fee);
            if (after < 0L) {
                return 0L;
            }
        } catch (Throwable t) {
            return 0L;
        }
        BotLedger.addMoney(reg, fee);
        BotTrades.log(who, reg, "\u7a0e\u6536", 1, fee, "\u7a0e\u6536");
        return fee;
    }

    public static long tax(ServerPlayer payer, long amount) {
        if (payer == null || amount <= 0L) {
            return 0L;
        }
        try {
            TAX_PERMILLE = Math.max(0, BotConfig.get().regulatorTaxPermille);
        } catch (Throwable ignored) {
            // 用默认
        }
        int pm = Math.max(0, TAX_PERMILLE);
        if (pm <= 0) {
            return 0L;
        }
        long fee = Math.max(1L, amount * pm / 1000L);
        try {
            long got = BankBridge.takeMoney(payer.getScoreboardName(), fee);
            if (got <= 0L) {
                return 0L;
            }
            String reg = regulator();
            BotLedger.addMoney(reg, got);
            BotLedger.save();
            BotTrades.log("-", reg, "\u7a0e\u6536", 1, got, "\u7a0e\u6536");
            payer.displayClientMessage(Component.literal(
                    "\u00a78[\u7a0e] \u4ea4\u6613\u7a0e " + got + " \u5510\u5154\u5e01"), false);
            return got;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 对机器人收税：从卖家收益里抽 1% 给经济部
     *
     * @return 实收税额
     */
    /** 常平仓行为免税：国库参与的成交不抽税 */
    private static boolean isRegulator(String name) {
        try {
            String reg = MarketBots.regulatorName();
            return reg != null && reg.equals(name);
        } catch (Throwable t) {
            return false;
        }
    }

    public static long taxBot(String seller, long amount) {
        if (isRegulator(seller)) {
            return 0L;
        }
        if (seller == null || seller.isEmpty() || amount <= 0L) {
            return 0L;
        }
        // 开关：机器人之间的成交是否收税
        try {
            if (!BotConfig.get().regulatorTaxBots) {
                return 0L;
            }
        } catch (Throwable ignored) {
            // 用默认
        }
        try {
            TAX_PERMILLE = Math.max(0, BotConfig.get().regulatorTaxPermille);
        } catch (Throwable ignored) {
            // 用默认
        }
        int pm = Math.max(0, TAX_PERMILLE);
        if (pm <= 0) {
            return 0L;
        }
        String reg = regulator();
        if (reg == null || reg.equals(seller)) {
            return 0L;              // 自己交易不抽自己的税
        }
        long fee = Math.max(1L, amount * pm / 1000L);
        if (BotLedger.money(seller) < fee) {
            return 0L;
        }
        BotLedger.addMoney(seller, -fee);
        BotLedger.addMoney(reg, fee);
        BotTrades.log(seller, reg, "\u7a0e\u6536", 1, fee, "\u7a0e\u6536");
        return fee;
    }

    /** 是否属于「破产拍卖/甩卖」——这类不抽税 */
    public static boolean isBankruptcyDeal(String itemId, long price) {
        try {
            long base = ShopPrices.price(itemId);
            if (base <= 0L) {
                return false;
            }
            return price * 100L < base * 60L;    // 低于基准价 60% 视为贱卖
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 玩家支付的「服务费」上缴国库
     *
     * 覆盖：传送石碑激活费、传送费、假人雇佣费
     *
     * 以前这些钱是直接销毁的（货币湮灭），现在改为进入经济部账户，
     * 让国库有真实的收入来源。
     *
     * @param amount 金额（唐兔币）
     * @param reason 流水里的原因（石碑激活 / 石碑使用 / 假人雇佣）
     */
    public static void creditTreasury(long amount, String reason) {
        if (amount <= 0L) {
            return;
        }
        try {
            String reg = regulator();
            if (reg == null || reg.isEmpty()) {
                return;
            }
            BotLedger.addMoney(reg, amount);
            BotTrades.log("-", reg, reason == null ? "\u670d\u52a1\u8d39" : reason,
                    1, amount, "\u4e0a\u7f34");
            DebugLog.log("\u4e0a\u7f34\u56fd\u5e93 " + amount + "\uff08"
                    + (reason == null ? "\u670d\u52a1\u8d39" : reason) + "\uff09");
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * 国民向国库的收购单供货
     *
     * 逻辑：国库挂出来的收购单（粮食/基础原料，钱已预扣）
     *       → 国民用手里的库存接单 → 拿到唐兔币
     *
     * 这是「卖货换钱」框架内的第二条稳定销路，
     * 不额外印钱（国库的钱在下单时就扣掉了）。
     *
     * @return 本轮成交条数
     */

    /** 判断是否是「高买」单：出价明显高于基准价 */
    private static boolean isHighBuyOrder(String citizen, com.tangtu.kjbb.OrderBook.Row o) {
        try {
            long base = com.tangtu.kjbb.ShopPrices.price(o.itemId);
            if (base <= 0L) {
                return false;
            }
            return o.unit >= Math.round(base * 1.05);
        } catch (Throwable t) {
            return false;
        }
    }

    public static int citizenSupplyToRegulator() {
        var cfg = BotConfig.get();
        if (!cfg.citizenSellToRegulatorEnabled) {
            return 0;
        }
        String citizen = MarketBots.citizenName();
        if (citizen == null) {
            return 0;
        }
        String reg = regulator();
        if (reg == null || reg.equals(citizen)) {
            return 0;
        }
        int done = 0;
        for (var o : new java.util.ArrayList<>(OrderBook.byStatus(OrderBook.PENDING))) {
            if (done >= Math.max(1, cfg.citizenSellToRegulatorMaxPerRound)) {
                break;
            }
            if (o == null || !reg.equals(o.buyer)) {
                continue;                     // 只接国库的单
            }
            String item = o.itemId;
            if (!BotConfig.get().regulatorAllows(item)) {
                continue;                     // 只接白名单（粮食/基础原料）
            }
            // 高买单只留给玩家：国民不许接
            if (BotConfig.get().regulatorGrainBuyPlayerOnly
                    && isHighBuyOrder(citizen, o)) {
                continue;
            }
            // 保命粮：封存期间不把粮食卖给国库
            if (com.tangtu.kjbb.MarketBots.isFoodItem(item)
                    && com.tangtu.kjbb.MarketBots.foodSellingBlocked()) {
                continue;
            }
            int available = BotLedger.stock(citizen, item);
            if (available <= 0) {
                continue;                     // 没货
            }
            // 部分成交：有多少供多少（不再要求凑够整单）
            int take = Math.min(available, o.count);
            long total = o.unit * (long) take;
            // 货款在国库下单时已预扣 → 这里只给国民加钱
            BotLedger.addStock(citizen, item, -take);
            BotLedger.addStock(reg, item, take);
            BotLedger.addMoney(citizen, total);
            OrderBook.fillPartial(o.id, take, citizen);
            BotTrades.log(citizen, reg, item, take, o.unit, "\u56fd\u6c11\u4f9b\u8d27");
            DebugLog.log("\u56fd\u6c11\u4f9b\u8d27 " + item + " x" + take
                    + " @" + o.unit + " = " + total
                    + "\uff08\u56fd\u5e93\u6536\u5355 #" + o.id
                    + (take < o.count ? "\uff0c\u5269\u4f59 " + (o.count - take) : "") + "\uff09");
            done++;
        }
        if (done > 0) {
            BotLedger.save();
        }
        return done;
    }

    /**
     * 接管「没人要」的国民挂单
     *
     *   · 玩家有优先权：挂出后 citizenOfferTakeoverMinutes 分钟内只卖给玩家
     *   · 超时仍无人买 → 经济部按挂牌价接下，避免国民货砸手里
     *
     * @return 本次接管的条数
     */
    public static int takeoverCitizenOffers() {
        var cfg = BotConfig.get();
        if (!cfg.botTakeoverCitizenOffers) {
            return 0;
        }
        String citizen = MarketBots.citizenName();
        if (citizen == null) {
            return 0;
        }
        String reg = regulator();
        if (reg == null || reg.equals(citizen)) {
            return 0;
        }
        long ttlMs = Math.max(1, cfg.citizenOfferTakeoverMinutes) * 60_000L;
        long now = ServerClock.now();
        // 每轮预算：固定上限 + 占国库比例，取两者较小值
        long treasury = BotLedger.money(reg);
        long capByPermille = treasury
                * Math.max(0, cfg.citizenOfferTakeoverTreasuryPermille) / 1000L;
        long budget = Math.min(Math.max(0L, cfg.citizenOfferTakeoverMaxPerRound),
                Math.max(0L, capByPermille));
        if (budget <= 0L) {
            return 0;
        }
        long spent = 0L;
        int took = 0;
        for (var row : new java.util.ArrayList<>(OfferBook.open())) {
            if (row == null || row.count <= 0) {
                continue;
            }
            if (!citizen.equals(row.seller)) {
                continue;                 // 只管国民的挂单
            }
            if (row.created <= 0L || now - row.created < ttlMs) {
                continue;                 // 还在玩家优先期内
            }
            long total = row.price * row.count;
            if (spent + total > budget) {
                continue;                 // 超出本轮预算
            }
            if (BotLedger.money(reg) < total) {
                continue;                 // 国库不够就不接
            }
            BotLedger.addMoney(reg, -total);
            BotLedger.addMoney(citizen, total);
            BotLedger.addStock(reg, row.itemId, row.count);
            OfferBook.setStatus(row.id, OfferBook.FILLED);
            BotTrades.log(reg, citizen, row.itemId, row.count, row.price, "\u8d85\u65f6\u63a5\u7ba1");
            System.out.println("[TangtuEconomical] \u7ecf\u6d4e\u90e8\u63a5\u7ba1\u56fd\u6c11\u6ede\u9500\u5355 #"
                    + row.id + " " + row.itemId + " \u00d7" + row.count + " @" + row.price);
            spent += total;
            took++;
            if (took >= 6 || spent >= budget) {
                break;
            }
        }
        if (took > 0) {
            BotLedger.save();
            DebugLog.log("\u8d85\u65f6\u63a5\u7ba1 " + took + " \u6761\uff0c\u82b1\u8d39 " + spent);
            System.out.println("[TangtuEconomical] \u8d85\u65f6\u63a5\u7ba1\uff1a" + took
                    + " \u6761\uff0c\u82b1\u8d39 " + spent + " / \u9884\u7b97 " + budget);
        }
        return took;
    }

    /** 危机收购：国民甩卖时，经济部接盘（不管大A膏手） */
    public static void crisisBuyout() {
        var cfg = BotConfig.get();
        String citizen = MarketBots.citizenName();
        if (citizen == null) {
            return;
        }
        String reg = regulator();
        if (reg == null || reg.equals(citizen)) {
            return;
        }
        long pool = BotLedger.money(citizen);
        long warnAt = (long) (cfg.minMoney * Math.max(1.0, cfg.citizenCrisisWarnRatio));
        if (pool > warnAt) {
            return;
        }
        long regMoney = BotLedger.money(reg);
        if (regMoney <= cfg.minMoney) {
            return;
        }
        int bought = 0;
        for (var row : new java.util.ArrayList<>(OfferBook.open())) {
            if (row == null || !citizen.equals(row.seller)) {
                continue;                 // 只买国民的，不碰大A膏手
            }
            if (row.count <= 0) {
                continue;
            }
            long total = row.price * row.count;
            if (total > BotLedger.money(reg)) {
                continue;
            }
            BotLedger.addMoney(reg, -total);
            BotLedger.addMoney(citizen, total);
            BotLedger.addStock(reg, row.itemId, row.count);
            OfferBook.setStatus(row.id, OfferBook.FILLED);
            BotTrades.log(reg, citizen, row.itemId, row.count, row.price, "\u5371\u673a\u6536\u8d2d");
            System.out.println("[TangtuEconomical] \u7ecf\u6d4e\u90e8\u6536\u8d2d\u56fd\u6c11\u7529\u5355 #"
                    + row.id + " " + row.itemId + " \u00d7" + row.count + " @" + row.price);
            bought++;
            if (bought >= 8) {
                break;
            }
        }
        if (bought > 0) {
            BotLedger.save();
            try {
                var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (srv != null) {
                    srv.getPlayerList().broadcastSystemMessage(Component.literal(
                            "\u00a77[\u7ecf\u6d4e\u90e8] \u5df2\u6536\u8d2d\u5510\u5154\u56fd\u56fd\u6c11\u7684 "
                                    + bought + " \u6761\u7529\u5355\uff0c\u7a33\u4f4f\u5e02\u573a"), false);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
}
