package com.tangtu.kjbb;

import com.tangtu.kjbb.item.StoreReceiptItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单动作（命令与界面共用）：
 *   take   —— 卖家接单：背包优先 → 仓库补 → 打包寄到买家提货点
 *   cancel —— 买家撤单：全额退款
 */
public final class OrderActions {

    private OrderActions() {
    }

    /** 解析「地址」→ 容器坐标（地址簿 → 提货点表按地址 → 提货点表按玩家） */
    public static BlockPos resolvePort(String address, String ownerName) {
        if (address != null && !address.isEmpty()) {
            BlockPos p1 = AddressBook.get(address);
            if (p1 != null) {
                return p1;
            }
            for (var e : PortRegistry.all()) {
                if (e.address() != null && e.address().equalsIgnoreCase(address)) {
                    return e.pos();
                }
            }
        }
        if (ownerName != null && !ownerName.isEmpty()) {
            var list = PortRegistry.of(ownerName);
            if (!list.isEmpty()) {
                return list.get(0).pos();
            }
        }
        return null;
    }

    /** 卖家接单 */
    public static String take(ServerPlayer sp, long orderId) {
        if (!(sp.level() instanceof ServerLevel level)) {
            return "§c只能在服务端执行";
        }
        OrderBook.Row row = OrderBook.get(orderId);
        if (row == null) {
            return "§c没有订单 #" + orderId;
        }
        boolean acceptedByMe = OrderBook.ACCEPTED.equals(row.status)
                && sp.getScoreboardName().equals(row.seller);
        if (!OrderBook.PENDING.equals(row.status) && !acceptedByMe) {
            if (OrderBook.ACCEPTED.equals(row.status)) {
                return "§c订单 #" + orderId + " 已被 "
                        + (row.seller == null ? "他人" : row.seller) + " 锁单，不能接";
            }
            return "§c订单 #" + orderId + " 状态是 " + row.status + "，不能接单";
        }
        Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(row.itemId));
        if (it == null) {
            return "§c物品不存在：" + row.itemId;
        }
        int fromBag = takeFromInventory(sp, it, row.count);
        int need = row.count - fromBag;
        int fromStore = 0;
        BlockPos ticker = com.tangtu.kjbb.TickerShip.nearestTicker(level, sp.blockPosition(), 64);
        if (need > 0 && ticker != null) {
            int stock = TickerStock.networkCount(level, ticker, new ItemStack(it));
            if (stock > 0) {
                fromStore = TickerStock.extractFromWarehouse(level, ticker, it, Math.min(need, stock));
            }
        }
        if (fromBag + fromStore < row.count) {
            if (fromBag > 0) {
                giveBack(sp, it, fromBag);
            }
            if (fromStore > 0 && ticker != null) {
                TickerStock.insertIntoWarehouse(level, ticker, new ItemStack(it, fromStore));
            }
            return "§c货不够：背包 " + fromBag + " + 仓库 " + fromStore + "，接不了 "
                    + row.count + " 个的单";
        }

        // 买家是市场机器人 → 不用发货，直接结账
        if (com.tangtu.kjbb.MarketBots.isBot(row.buyer)) {
            com.tangtu.kjbb.Ledger.addStock(row.buyer, row.itemId, row.count);
            com.tangtu.kjbb.MarketPulse.record(row.itemId, row.unit);
            com.tangtu.kjbb.MarketPrice.onTrade(row.itemId, row.unit);
            com.tangtu.kjbb.KlineAggregator.onTrade(row.itemId, (int) Math.max(1L, row.unit), row.count);
            BankBridge.addMoney(sp.getScoreboardName(), row.total);
            // 玩家交易税：卖家是玩家 → 抽 1% 进国库
            long taxPaid = 0L;
            try {
                if (!com.tangtu.kjbb.MarketBots.isBot(sp.getScoreboardName())) {
                    taxPaid = com.tangtu.kjbb.RegulatorOps.tax(sp, row.total);
                }
            } catch (Throwable ignored) {
                // 收税失败不影响成交
            }
            OrderBook.setStatus(orderId, OrderBook.DONE, sp.getScoreboardName());
            System.out.println("[TangtuEconomical] 机器人收购 #" + orderId + " 卖家="
                    + sp.getScoreboardName() + " 金额=" + row.total);
            return "§a[成交] " + row.buyer + " 收下了你的 " + row.count + " 个「"
                    + StoreReceiptItem.niceName(row.itemId) + "」，"
                    + row.total + " 唐兔币已到账"
                        + (taxPaid > 0L ? "（交易税 " + taxPaid + "）" : "");
        }
        // 面对面：买家在线且两人距离 8 格内 → 不打包不寄送，直接给货
        try {
            var srvF = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            var buyerP = srvF == null ? null : srvF.getPlayerList().getPlayerByName(row.buyer);
            if (buyerP != null && buyerP.serverLevel() == level
                    && buyerP.distanceToSqr(sp) <= 64.0) {
                Item itF = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(row.itemId));
                if (itF != null) {
                    int restF = row.count;
                    int maxF = Math.max(1, itF.getDefaultMaxStackSize());
                    while (restF > 0) {
                        int nF = Math.min(restF, maxF);
                        ItemStack stF = new ItemStack(itF, nF);
                        if (!buyerP.getInventory().add(stF)) {
                            buyerP.drop(stF, false);
                        }
                        restF -= nF;
                    }
                    com.tangtu.kjbb.MarketPulse.record(row.itemId, row.unit);
                    com.tangtu.kjbb.MarketPrice.onTrade(row.itemId, row.unit);
                    com.tangtu.kjbb.KlineAggregator.onTrade(row.itemId,
                            (int) Math.max(1L, row.unit), row.count);
                    BankBridge.addMoney(sp.getScoreboardName(), row.total);
                    long taxF = 0L;
                    try {
                        if (!com.tangtu.kjbb.MarketBots.isBot(sp.getScoreboardName())) {
                            taxF = com.tangtu.kjbb.RegulatorOps.tax(sp, row.total);
                        }
                    } catch (Throwable ignored) {
                        // 收税失败不影响成交
                    }
                    OrderBook.setStatus(orderId, OrderBook.DONE, sp.getScoreboardName());
                    com.tangtu.kjbb.TradeActivity.recordPlayerTrade();
                    try {
                        com.tangtu.kjbb.MailLog.add(sp.getScoreboardName(), row.buyer,
                                row.itemId, row.count, "面对面");
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    System.out.println("[TangtuEconomical] 面对面接单 #" + orderId + " 卖家="
                            + sp.getScoreboardName() + " 买家=" + row.buyer);
                    final long taxFF = taxF;
                    return "§a[面对面] 订单 #" + orderId + "  "
                            + StoreReceiptItem.niceName(row.itemId) + " ×" + row.count
                            + " 已直接交给 " + row.buyer + "，" + row.total + " 唐兔币已到账"
                            + (taxFF > 0L ? "（交易税 " + taxFF + "）" : "");
                }
            }
        } catch (Throwable ignoredF) {
            // 面对面失败则落到物流
        }
        String addr = row.address == null || row.address.isEmpty() ? row.buyer : row.address;
        BlockPos port = resolvePort(addr, row.buyer);
        if (port == null) {
            if (fromBag > 0) {
                giveBack(sp, it, fromBag);
            }
            if (fromStore > 0 && ticker != null) {
                TickerStock.insertIntoWarehouse(level, ticker, new ItemStack(it, fromStore));
            }
            return "§c找不到买家的提货点（地址「" + addr + "」）。让买家先 /billport claim";
        }
        List<ItemStack> goods = new ArrayList<>();
        int left = row.count;
        while (left > 0) {
            int n = Math.min(left, Math.max(1, it.getDefaultMaxStackSize()));
            goods.add(new ItemStack(it, n));
            left -= n;
        }
        boolean shipped = AddressBook.deliverTo(new AddressBook.PortInfo(level, port, addr), goods);
        if (!shipped) {
            if (fromBag > 0) {
                giveBack(sp, it, fromBag);
            }
            if (fromStore > 0 && ticker != null) {
                TickerStock.insertIntoWarehouse(level, ticker, new ItemStack(it, fromStore));
            }
            return "§c提货点塞不进包裹，接单失败（货已退回）";
        }
        OrderBook.setStatus(orderId, OrderBook.SUPPLIED, sp.getScoreboardName());
        com.tangtu.kjbb.TradeActivity.recordPlayerTrade();
        OrderWatch.add(orderId, row.buyer, row.itemId, row.count, addr);
        System.out.println("[TangtuEconomical] 接单 #" + orderId + " 卖家=" + sp.getScoreboardName()
                + " bag=" + fromBag + " store=" + fromStore);
        return "§a[接单] 订单 #" + orderId + "  " + StoreReceiptItem.niceName(row.itemId)
                + " ×" + row.count + " 已发货至「" + addr + "」（背包 " + fromBag
                + " / 仓库 " + fromStore + "）";
    }

    /** 买家撤单（退款） */
    public static String cancel(ServerPlayer sp, long orderId) {
        OrderBook.Row row = OrderBook.get(orderId);
        if (row == null) {
            return "§c没有订单 #" + orderId;
        }
        String blocked = OrderBook.acceptBlockReason(orderId, sp.getScoreboardName());
        if (blocked != null) {
            return "\u00a7c" + blocked;
        }
        if (!OrderBook.PENDING.equals(row.status)) {
            return "§c订单 #" + orderId + " 状态是 " + row.status + "，不能撤单";
        }
        if (!row.buyer.equalsIgnoreCase(sp.getScoreboardName())) {
            return "§c不是你的订单";
        }
        BankBridge.addMoney(row.buyer, row.total);
        OrderBook.setStatus(orderId, OrderBook.CANCELLED, null);
        System.out.println("[TangtuEconomical] 撤单 #" + orderId + " 退款=" + row.total);
        return "§e已撤单 #" + orderId + "，" + row.total + " 唐兔币已退回余额";
    }

    /** 从背包扣物品，返回实际扣到的数量 */
    public static int takeFromInventory(ServerPlayer sp, Item it, int want) {
        int left = want;
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty() || st.getItem() != it) {
                continue;
            }
            int take = Math.min(st.getCount(), left);
            st.shrink(take);
            left -= take;
        }
        return want - left;
    }

    /** 退回背包（满则掉地上） */
    public static void giveBack(ServerPlayer sp, Item it, int count) {
        int left = count;
        while (left > 0) {
            int n = Math.min(left, Math.max(1, it.getDefaultMaxStackSize()));
            ItemStack st = new ItemStack(it, n);
            if (!sp.getInventory().add(st)) {
                sp.drop(st, false);
            }
            left -= n;
        }
    }
}
