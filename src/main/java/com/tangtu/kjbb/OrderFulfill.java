package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * 订单履约：卖家在仓储发报机前「发货」时，自动匹配他已接受（ACCEPTED）的订单。
 *
 * 流程：
 *   卖家接受订单（PENDING → ACCEPTED）
 *       ↓
 *   去仓储发报机把货存进去（这段用现有的存款/发货流程）
 *       ↓
 *   存进去的瞬间调用这里 → 找到他自己的 ACCEPTED 订单 → 打包寄给买家
 *       ↓
 *   订单 ACCEPTED → SUPPLIED
 *       ↓
 *   包裹到达买家提货点 → DeliveryWatcher 放款 → DONE
 */
public final class OrderFulfill {

    private OrderFulfill() {
    }

    /**
     * @return 本次履约成功的订单数
     */
    public static int tryFulfill(ServerPlayer sp, ServerLevel level, BlockPos ticker,
                                 String itemId, int available) {
        if (sp == null || level == null || itemId == null || available <= 0) {
            return 0;
        }
        String me = sp.getScoreboardName();
        int done = 0;
        int left = available;

        for (OrderBook.Row r : OrderBook.byStatus(OrderBook.ACCEPTED)) {
            if (left <= 0) {
                break;
            }
            if (r.seller == null || !r.seller.equals(me)) {
                continue;
            }
            if (!itemId.equals(r.itemId)) {
                continue;
            }
            if (r.count > left) {
                continue;      // 这次存的量还不够发这一单
            }
            // 买家是市场机器人 → 直接入库结账，不打包不发提单
            try {
                if (com.tangtu.kjbb.MarketBots.isBot(r.buyer)) {
                    com.tangtu.kjbb.BotLedger.addStock(r.buyer, r.itemId, r.count);
                    com.tangtu.kjbb.Pay.credit(me, r.total);
                    com.tangtu.kjbb.OrderBook.setStatus(r.id, com.tangtu.kjbb.OrderBook.DONE, me);
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7a[\u6210\u4ea4] " + r.buyer + " \u6536\u4e0b\u4e86\u4f60\u7684 "
                                    + r.count + " \u4e2a\uff0c" + r.total
                                    + " \u5510\u5154\u5e01\u5df2\u5230\u8d26"), false);
                    left -= r.count;
                    done++;
                    continue;
                }
            } catch (Throwable ignored) {
                // 回落到正常发货
            }
            String target = r.address == null || r.address.isEmpty() ? r.buyer : r.address;
            boolean shipped;
            try {
                shipped = com.tangtu.kjbb.net.TickerBillNetwork.mailBillTo(level, sp, ticker,
                        r.itemId, r.count, r.buyer, target, r.id);
            } catch (Throwable t) {
                shipped = false;
            }
            if (!shipped) {
                // 发报机不可用时退回直接投递
                try {
                    Item item = BuiltInRegistries.ITEM.get(
                            ResourceLocation.tryParse(r.itemId));
                    if (item == null) {
                        continue;
                    }
                    java.util.List<ItemStack> goods = new java.util.ArrayList<>();
                    int c = r.count;
                    while (c > 0) {
                        int n = Math.min(c, Math.max(1, item.getDefaultMaxStackSize()));
                        goods.add(new ItemStack(item, n));
                        c -= n;
                    }
                    shipped = AddressBook.deliver(level, sp.blockPosition(), target, goods);
                } catch (Throwable ignored) {
                    shipped = false;
                }
            }
            if (!shipped) {
                continue;
            }
            OrderBook.setStatus(r.id, OrderBook.SUPPLIED, me);
            OrderWatch.add(r.id, r.buyer, r.itemId, r.count, target);
            left -= r.count;
            done++;
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "\u00a7a[\u53d1\u8d27] \u8ba2\u5355 #" + r.id + " \u5df2\u53d1\u51fa\uff0c"
                            + "\u7b49\u5305\u88f9\u5230\u8fbe\u540e\u6536\u6b3e"), false);
            System.out.println("[TangtuEconomical] \u8ba2\u5355\u5c65\u7ea6 #" + r.id
                    + " \u5356\u5bb6=" + me + " \u7269\u54c1=" + r.itemId + " x" + r.count);
        }
        return done;
    }
}
