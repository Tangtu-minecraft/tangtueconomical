package com.tangtu.kjbb.net;

import com.tangtu.kjbb.BankBridge;
import com.tangtu.kjbb.MarketBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.tangtu.kjbb.TickerStock;
import com.tangtu.kjbb.TickerShip;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;

/** 行情（K 线）界面：看盘 + 下单 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class MarketNetwork {

    private MarketNetwork() {
    }

    public record ReqMarkets() implements CustomPacketPayload {
        public static final Type<ReqMarkets> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_markets"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqMarkets> CODEC =
                StreamCodec.unit(new ReqMarkets());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * body 每行：itemId|当前价|K线条数。
     * <p>行情行数会随物品数增长，单包字符串上限是 32767 字符，所以这里分片发：
     * {@code seq} 从 0 开始，{@code parts} 是总片数，客户端收齐再拼起来。
     */
    public record MarketsReply(int seq, int parts, String body) implements CustomPacketPayload {
        public static final Type<MarketsReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "markets_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MarketsReply> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, MarketsReply::seq,
                        ByteBufCodecs.VAR_INT, MarketsReply::parts,
                        com.tangtu.kjbb.net.ChunkedString.CODEC, MarketsReply::body,
                        MarketsReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 收包缓冲：一次请求的各分片拼起来再交给界面 */
    private static final Chunk.Inbox MARKETS_INBOX = new Chunk.Inbox();
    private static final Chunk.Inbox HISTORY_INBOX = new Chunk.Inbox();
    private static final Chunk.Inbox ORDERS_INBOX = new Chunk.Inbox();

    private static void sendMarkets(net.minecraft.server.level.ServerPlayer sp, String body) {
        String safe = body == null ? "" : body;
        int parts = Chunk.parts(safe);
        for (int i = 0; i < parts; i++) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new MarketsReply(i, parts, Chunk.slice(safe, i)));
        }
    }

    private static void sendHistory(net.minecraft.server.level.ServerPlayer sp, String body) {
        String safe = body == null ? "" : body;
        int parts = Chunk.parts(safe);
        for (int i = 0; i < parts; i++) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new HistoryReply(i, parts, Chunk.slice(safe, i)));
        }
    }

    private static void sendMyOrders(net.minecraft.server.level.ServerPlayer sp, String body) {
        String safe = body == null ? "" : body;
        int parts = Chunk.parts(safe);
        for (int i = 0; i < parts; i++) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new MyOrdersReply(i, parts, Chunk.slice(safe, i)));
        }
    }

    public record ReqHistory(String itemId) implements CustomPacketPayload {
        public static final Type<ReqHistory> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_history"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqHistory> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ReqHistory::itemId, ReqHistory::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** body 每行：open|high|low|close */
    public record HistoryReply(int seq, int parts, String body) implements CustomPacketPayload {
        public static final Type<HistoryReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "history_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HistoryReply> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, HistoryReply::seq,
                        ByteBufCodecs.VAR_INT, HistoryReply::parts,
                        com.tangtu.kjbb.net.ChunkedString.CODEC, HistoryReply::body,
                        HistoryReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：挂单（从最近的发报机仓库扣货） */
    public record PlaceOffer(String itemId, int count, long price) implements CustomPacketPayload {
        public static final Type<PlaceOffer> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "place_offer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceOffer> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, PlaceOffer::itemId,
                        ByteBufCodecs.VAR_INT, PlaceOffer::count,
                        ByteBufCodecs.VAR_LONG, PlaceOffer::price,
                        PlaceOffer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：某个物品的所有订单 */
    public record ReqItemOrders(String itemId) implements CustomPacketPayload {
        public static final Type<ReqItemOrders> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_item_orders"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqItemOrders> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ReqItemOrders::itemId,
                        ReqItemOrders::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** body 每行：id|count|unit|total|status|buyer */
    public record ItemOrdersReply(String itemId, String body) implements CustomPacketPayload {
        public static final Type<ItemOrdersReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "item_orders_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ItemOrdersReply> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, ItemOrdersReply::itemId,
                        com.tangtu.kjbb.net.ChunkedString.CODEC, ItemOrdersReply::body,
                        ItemOrdersReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：买票据 */
    public record BuyTicket(long id) implements CustomPacketPayload {
        public static final Type<BuyTicket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "buy_ticket"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BuyTicket> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_LONG, BuyTicket::id, BuyTicket::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：撤票据挂卖 */
    public record CancelTicket(long id) implements CustomPacketPayload {
        public static final Type<CancelTicket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "cancel_ticket"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CancelTicket> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_LONG, CancelTicket::id, CancelTicket::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：票据详情 */
    public record ReqTicketDetail(long id) implements CustomPacketPayload {
        public static final Type<ReqTicketDetail> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_ticket_detail"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqTicketDetail> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_LONG, ReqTicketDetail::id,
                        ReqTicketDetail::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** id~kind~price~seller~status~bearer~pos~serial~itemId~count~items */
    public record TicketDetailReply(String body) implements CustomPacketPayload {
        public static final Type<TicketDetailReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "ticket_detail_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TicketDetailReply> CODEC =
                StreamCodec.composite(com.tangtu.kjbb.net.ChunkedString.CODEC, TicketDetailReply::body,
                        TicketDetailReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：票据市场列表 */
    public record ReqTickets() implements CustomPacketPayload {
        public static final Type<ReqTickets> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_tickets"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqTickets> CODEC =
                StreamCodec.unit(new ReqTickets());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** body 每行：id|price|kind|desc|seller */
    /** 服务端 → 客户端：全市场未完成订单快照 */
    /** 服务器日期（服务端 → 客户端，进服/换日时推送） */
    public record ClockSync(String text) implements CustomPacketPayload {
        public static final Type<ClockSync> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "clock_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClockSync> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ClockSync::text,
                        ClockSync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenOrdersReply(String body) implements CustomPacketPayload {
        public static final Type<OpenOrdersReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_orders_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenOrdersReply> CODEC =
                StreamCodec.composite(com.tangtu.kjbb.net.ChunkedString.CODEC, OpenOrdersReply::body,
                        OpenOrdersReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：请求未完成订单快照 */
    public record ReqOpenOrders() implements CustomPacketPayload {
        public static final Type<ReqOpenOrders> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_open_orders"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqOpenOrders> CODEC =
                StreamCodec.unit(new ReqOpenOrders());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TicketsReply(String body) implements CustomPacketPayload {
        public static final Type<TicketsReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "tickets_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TicketsReply> CODEC =
                StreamCodec.composite(com.tangtu.kjbb.net.ChunkedString.CODEC, TicketsReply::body, TicketsReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：看某张订单详情 */
    public record ReqOrderDetail(long id) implements CustomPacketPayload {
        public static final Type<ReqOrderDetail> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_order_detail"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqOrderDetail> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_LONG, ReqOrderDetail::id, ReqOrderDetail::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 订单详情回包：id|itemId|count|unit|total|status|buyer|seller|address|created */
    public record OrderDetailReply(String body) implements CustomPacketPayload {
        public static final Type<OrderDetailReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "order_detail_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OrderDetailReply> CODEC =
                StreamCodec.composite(com.tangtu.kjbb.net.ChunkedString.CODEC, OrderDetailReply::body,
                        OrderDetailReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：接单 */
    public record TakeOrder(long id) implements CustomPacketPayload {
        public static final Type<TakeOrder> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "take_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TakeOrder> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_LONG, TakeOrder::id, TakeOrder::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：撤单 */
    public record CancelOrder(long id) implements CustomPacketPayload {
        public static final Type<CancelOrder> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "cancel_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CancelOrder> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_LONG, CancelOrder::id, CancelOrder::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：打开行情界面 */
    public record OpenMarket() implements CustomPacketPayload {
        public static final Type<OpenMarket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_market"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenMarket> CODEC =
                StreamCodec.unit(new OpenMarket());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ReqMyOrders() implements CustomPacketPayload {
        public static final Type<ReqMyOrders> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_my_orders"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqMyOrders> CODEC =
                StreamCodec.unit(new ReqMyOrders());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** body 每行：id|itemId|count|unit|total|status|seller|created */
    public record MyOrdersReply(int seq, int parts, String body) implements CustomPacketPayload {
        public static final Type<MyOrdersReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "my_orders_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MyOrdersReply> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, MyOrdersReply::seq,
                        ByteBufCodecs.VAR_INT, MyOrdersReply::parts,
                        com.tangtu.kjbb.net.ChunkedString.CODEC, MyOrdersReply::body,
                        MyOrdersReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：按当前行情价买入 */
    /** 客户端 → 服务端：查某物品的国民购买意愿 */
    public record ReqWillingness(String itemId) implements CustomPacketPayload {
        public static final Type<ReqWillingness> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_willingness"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqWillingness> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ReqWillingness::itemId,
                        ReqWillingness::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：意愿等级 */
    public record WillingnessReply(String itemId, int level, int prob) implements CustomPacketPayload {
        public static final Type<WillingnessReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "willingness"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WillingnessReply> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, WillingnessReply::itemId,
                        ByteBufCodecs.VAR_INT, WillingnessReply::level,
                        ByteBufCodecs.VAR_INT, WillingnessReply::prob,
                        WillingnessReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：按玩家指定价格挂买单 */
    public record PlaceBuyOrder(String itemId, int count, long unit) implements CustomPacketPayload {
        public static final Type<PlaceBuyOrder> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "place_buy_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceBuyOrder> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, PlaceBuyOrder::itemId,
                        ByteBufCodecs.VAR_INT, PlaceBuyOrder::count,
                        ByteBufCodecs.VAR_LONG, PlaceBuyOrder::unit,
                        PlaceBuyOrder::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：把某个物品加入市场（自动给一个初始价） */
    public record AddMarketItem(String itemId, int price) implements CustomPacketPayload {
        public static final Type<AddMarketItem> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "add_market_item"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AddMarketItem> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, AddMarketItem::itemId,
                        ByteBufCodecs.VAR_INT, AddMarketItem::price,
                        AddMarketItem::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BuyMarket(String itemId, int count) implements CustomPacketPayload {
        public static final Type<BuyMarket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "buy_market"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BuyMarket> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, BuyMarket::itemId,
                        ByteBufCodecs.VAR_INT, BuyMarket::count,
                        BuyMarket::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(ReqMarkets.TYPE, ReqMarkets.CODEC, MarketNetwork::handleReqMarkets)
                .playToClient(MarketsReply.TYPE, MarketsReply.CODEC, MarketNetwork::handleMarkets)
                .playToServer(ReqHistory.TYPE, ReqHistory.CODEC, MarketNetwork::handleReqHistory)
                .playToClient(HistoryReply.TYPE, HistoryReply.CODEC, MarketNetwork::handleHistory)
                .playToServer(BuyMarket.TYPE, BuyMarket.CODEC, MarketNetwork::handleBuy)
                .playToServer(AddMarketItem.TYPE, AddMarketItem.CODEC,
                        MarketNetwork::handleAddMarketItem)
                .playToServer(PlaceBuyOrder.TYPE, PlaceBuyOrder.CODEC,
                        MarketNetwork::handlePlaceBuyOrder)
                .playToServer(ReqWillingness.TYPE, ReqWillingness.CODEC,
                        MarketNetwork::handleReqWillingness)
                .playToClient(WillingnessReply.TYPE, WillingnessReply.CODEC,
                        MarketNetwork::handleWillingnessReply)
                .playToServer(ReqMyOrders.TYPE, ReqMyOrders.CODEC, MarketNetwork::handleReqMyOrders)
                .playToClient(MyOrdersReply.TYPE, MyOrdersReply.CODEC, MarketNetwork::handleMyOrders)
                .playToClient(OpenMarket.TYPE, OpenMarket.CODEC, MarketNetwork::handleOpenMarket)
                .playToServer(PlaceOffer.TYPE, PlaceOffer.CODEC, MarketNetwork::handlePlaceOffer)
                .playToServer(BuyTicket.TYPE, BuyTicket.CODEC, MarketNetwork::handleBuyTicket)
                .playToServer(CancelTicket.TYPE, CancelTicket.CODEC,
                        MarketNetwork::handleCancelTicket)
                .playToServer(ReqTicketDetail.TYPE, ReqTicketDetail.CODEC,
                        MarketNetwork::handleReqTicketDetail)
                .playToClient(TicketDetailReply.TYPE, TicketDetailReply.CODEC,
                        MarketNetwork::handleTicketDetailReply)
                .playToServer(ReqTickets.TYPE, ReqTickets.CODEC, MarketNetwork::handleReqTickets)
                .playToClient(TicketsReply.TYPE, TicketsReply.CODEC, MarketNetwork::handleTicketsReply)
                .playToServer(ReqOpenOrders.TYPE, ReqOpenOrders.CODEC,
                        MarketNetwork::handleReqOpenOrders)
                .playToClient(OpenOrdersReply.TYPE, OpenOrdersReply.CODEC,
                        MarketNetwork::handleOpenOrdersReply)
                .playToServer(ReqOrderDetail.TYPE, ReqOrderDetail.CODEC,
                        MarketNetwork::handleReqOrderDetail)
                .playToClient(OrderDetailReply.TYPE, OrderDetailReply.CODEC,
                        MarketNetwork::handleOrderDetailReply)
                .playToServer(ReqItemOrders.TYPE, ReqItemOrders.CODEC,
                        MarketNetwork::handleReqItemOrders)
                .playToClient(ItemOrdersReply.TYPE, ItemOrdersReply.CODEC,
                        MarketNetwork::handleItemOrdersReply)
                .playToClient(ClockSync.TYPE, ClockSync.CODEC, MarketNetwork::handleClockSync)
                .playToServer(TakeOrder.TYPE, TakeOrder.CODEC, MarketNetwork::handleTakeOrder)
                .playToServer(CancelOrder.TYPE, CancelOrder.CODEC, MarketNetwork::handleCancelOrder);
    }

    private static void handleBuyTicket(BuyTicket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            String msg = com.tangtu.kjbb.TicketActions.buy(sp, p.id());
            sp.displayClientMessage(Component.literal(msg), false);
            if (sp.containerMenu != null) {
                sp.containerMenu.broadcastChanges();
            }
        });
    }

    private static void handleCancelTicket(CancelTicket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            String msg = com.tangtu.kjbb.TicketActions.cancel(sp, p.id());
            sp.displayClientMessage(Component.literal(msg), false);
            if (sp.containerMenu != null) {
                sp.containerMenu.broadcastChanges();
            }
        });
    }

    private static void handleTicketDetailReply(TicketDetailReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientMarketBridge.ticketDetail(p.body()));
    }

    private static void handleReqTicketDetail(ReqTicketDetail p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            var r = com.tangtu.kjbb.TicketMarket.get(p.id());
            if (r == null) {
                sp.displayClientMessage(Component.literal("§c这张票据已经不在了"), false);
                return;
            }
            boolean bearer = r.data.contains(":1");
            String pos = "";
            String serial = "";
            String itemId = "";
            String count = "";
            String items = "";
            if (com.tangtu.kjbb.TicketMarket.KIND_BILL.equals(r.kind)) {
                String[] f = r.data.split(":");
                serial = f.length > 0 ? f[0] : "";
                try {
                    var bd = com.tangtu.kjbb.item.BillRegistry.dataOf(Long.parseLong(serial));
                    if (bd != null) {
                        itemId = bd.itemId();
                        count = String.valueOf(bd.count());
                        pos = bd.address();
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            } else {
                String[] f = r.data.split(":", 3);
                pos = f.length > 0 ? f[0] : "";
                items = f.length > 2 ? f[2] : "";
            }
            String body = r.id + "~" + r.kind + "~" + r.price + "~" + r.seller + "~" + r.status
                    + "~" + (bearer ? "1" : "0") + "~" + pos + "~" + serial + "~" + itemId
                    + "~" + count + "~" + items;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new TicketDetailReply(body));
        });
    }

    /** 组装全市场未完成订单快照：type|id|itemId|count|unit|owner */
    private static String buildOpenOrders() {
        StringBuilder sb = new StringBuilder();
        try {
            for (var o : com.tangtu.kjbb.OrderBook.byStatus(com.tangtu.kjbb.OrderBook.PENDING)) {
                if (o.count <= 0) {
                    continue;
                }
                sb.append("BUY|").append(o.id).append('|').append(o.itemId).append('|')
                        .append(o.count).append('|').append(o.unit).append('|')
                        .append(o.buyer == null ? "" : o.buyer).append('|')
                        .append(o.created).append('|')
                        .append(com.tangtu.kjbb.BankBridge.isBotAccount(o.buyer) ? 1 : 0)
                        .append('\n');
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            for (var o : com.tangtu.kjbb.OfferBook.open()) {
                if (o.count <= 0) {
                    continue;
                }
                sb.append("SELL|").append(o.id).append('|').append(o.itemId).append('|')
                        .append(o.count).append('|').append(o.price).append('|')
                        .append(o.seller == null ? "" : o.seller).append('|')
                        .append(o.created).append('|')
                        .append(com.tangtu.kjbb.BankBridge.isBotAccount(o.seller) ? 1 : 0)
                        .append('\n');
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return sb.toString();
    }

    private static void handleClockSync(ClockSync p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.client.MarketCache.setServerDate(p.text()));
    }

    /** 全体玩家推送当前日期 */
    public static void broadcastClock() {
        try {
            String text = com.tangtu.kjbb.ServerCalendar.now();
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null) {
                return;
            }
            for (var sp : srv.getPlayerList().getPlayers()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                        new ClockSync(text));
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void handleReqOpenOrders(ReqOpenOrders p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) {
            ctx.enqueueWork(() -> net.neoforged.neoforge.network.PacketDistributor
                    .sendToPlayer(sp, new OpenOrdersReply(buildOpenOrders())));
        }
    }

    private static void handleOpenOrdersReply(OpenOrdersReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.client.MarketCache.setOpenOrders(p.body()));
    }

    private static void handleTicketsReply(TicketsReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientMarketBridge.tickets(p.body()));
    }

    private static void handleReqTickets(ReqTickets p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (var r : com.tangtu.kjbb.TicketMarket.onSale()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                String desc = com.tangtu.kjbb.TicketMarket.describe(r)
                        .replace("|", "/");
                sb.append(r.id).append('|').append(r.price).append('|').append(r.kind)
                        .append('|').append(desc).append('|').append(r.seller);
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new TicketsReply(sb.toString()));
        });
    }

    private static void handleOrderDetailReply(OrderDetailReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientMarketBridge.orderDetail(p.body()));
    }

    private static void handleReqOrderDetail(ReqOrderDetail p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            var r = com.tangtu.kjbb.OrderBook.get(p.id());
            if (r == null) {
                sp.displayClientMessage(Component.literal("§c订单 #" + p.id() + " 已不存在"), false);
                return;
            }
            String body = r.id + "|" + r.itemId + "|" + r.count + "|" + r.unit + "|" + r.total
                    + "|" + r.status + "|" + r.buyer + "|"
                    + (r.seller == null ? "" : r.seller) + "|"
                    + (r.address == null ? "" : r.address) + "|" + r.created;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new OrderDetailReply(body));
        });
    }

    private static void handleItemOrdersReply(ItemOrdersReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientMarketBridge.itemOrders(p.body()));
    }

    private static void handleReqItemOrders(ReqItemOrders p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            String itemId = p.itemId();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            var all = com.tangtu.kjbb.OrderBook.byStatus(null);
            for (int i = all.size() - 1; i >= 0 && n < 24; i--) {
                var r = all.get(i);
                if (!r.itemId.equalsIgnoreCase(itemId)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(r.id).append('|').append(r.count).append('|').append(r.unit)
                        .append('|').append(r.total).append('|').append(r.status).append('|')
                        .append(r.buyer);
                n++;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new ItemOrdersReply(itemId, sb.toString()));
        });
    }

    private static void handleTakeOrder(TakeOrder p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            String msg = com.tangtu.kjbb.OrderActions.take(sp, p.id());
            sp.displayClientMessage(Component.literal(msg), false);
        });
    }

    private static void handleCancelOrder(CancelOrder p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            String msg = com.tangtu.kjbb.OrderActions.cancel(sp, p.id());
            sp.displayClientMessage(Component.literal(msg), false);
        });
    }

    private static void handlePlaceOffer(PlaceOffer p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (!(sp.level() instanceof ServerLevel level)) {
                return;
            }
            String itemId = p.itemId();
            int count = Math.max(1, p.count());
            long price = Math.max(1L, p.price());
            BlockPos ticker = TickerShip.nearestTicker(level, sp.blockPosition(), 32);
            if (ticker == null) {
                sp.displayClientMessage(Component.literal("§c附近 32 格内没有仓储发报机，取不到货"),
                        false);
                return;
            }
            net.minecraft.world.item.Item it =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                            ResourceLocation.tryParse(itemId));
            if (it == null) {
                sp.displayClientMessage(Component.literal("§c物品不存在"), false);
                return;
            }
            int stock = TickerStock.networkCount(level, ticker, new ItemStack(it));
            if (stock < count) {
                sp.displayClientMessage(Component.literal(
                        "§c仓库里 " + itemId + " 只有 " + stock + " 个，挂不了 " + count + " 个"), false);
                return;
            }
            int got = TickerStock.extractFromWarehouse(level, ticker, it, count);
            if (got < count) {
                if (got > 0) {
                    TickerStock.insertIntoWarehouse(level, ticker, new ItemStack(it, got));
                }
                sp.displayClientMessage(Component.literal("§c从仓库取货失败"), false);
                return;
            }
            var row = com.tangtu.kjbb.OfferBook.add(sp.getScoreboardName(), itemId, count, price);
            sp.displayClientMessage(Component.literal("§a[挂单] #" + row.id + "  " + itemId
                    + " ×" + count + "  " + price + " 唐兔币/个（共 " + (price * count) + " 唐兔币）"), false);
            sp.displayClientMessage(Component.literal("§7别人用 /billoffer buy " + row.id
                    + " 购买；/billoffer cancel " + row.id + " 撤单"), false);
            System.out.println("[TangtuEconomical] 挂单 #" + row.id + " " + itemId + " x" + count
                    + " price=" + price + " seller=" + sp.getScoreboardName());
        });
    }

    private static void handleOpenMarket(OpenMarket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientMarketOpen.open());
    }

    /** 服务端调用：给玩家打开行情界面 */
    public static void sendOpen(ServerPlayer sp) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new OpenMarket());
    }

    private static void handleMyOrders(MyOrdersReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            String all = ORDERS_INBOX.feed(p.seq(), p.parts(), p.body());
            if (all != null) {
                com.tangtu.kjbb.net.ClientMarketBridge.myOrders(all);
            }
        });
    }

    private static void handleReqMyOrders(ReqMyOrders p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            String who = sp.getScoreboardName();
            StringBuilder sb = new StringBuilder();
            for (var r : com.tangtu.kjbb.OrderBook.byStatus(null)) {
                if (!who.equalsIgnoreCase(r.buyer)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(r.id).append('|').append(r.itemId).append('|').append(r.count)
                        .append('|').append(r.unit).append('|').append(r.total)
                        .append('|').append(r.status).append('|')
                        .append(r.seller == null ? "" : r.seller).append('|')
                        .append(r.created);
            }
            sendMyOrders(sp, sb.toString());
        });
    }

    private static void handleMarkets(MarketsReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            String all = MARKETS_INBOX.feed(p.seq(), p.parts(), p.body());
            if (all != null) {
                com.tangtu.kjbb.net.ClientMarketBridge.markets(all);
            }
        });
    }

    private static void handleHistory(HistoryReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            String all = HISTORY_INBOX.feed(p.seq(), p.parts(), p.body());
            if (all != null) {
                com.tangtu.kjbb.net.ClientMarketBridge.history(all);
            }
        });
    }

    private static void handleReqMarkets(ReqMarkets p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (!(sp.level() instanceof ServerLevel level)) {
                return;
            }
            // 只列【我们自己定价】的商品，价格用动态价（官方基础价 × 库存系数）
            BlockPos ticker = TickerShip.nearestTicker(level, sp.blockPosition(), 32);
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            try {
                String dump = com.tangtu.kjbb.ShopPrices.dump();
                // dump() 是 "id=price;id=price" 形式，且 id 里的冒号被转义成 \\:
                for (String part : dump.split(";")) {
                    String s = part.trim();
                    if (s.isEmpty()) {
                        continue;
                    }
                    int eq = s.lastIndexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String id = s.substring(0, eq).replace("\\:", ":").trim();
                    long base;
                    try {
                        base = Long.parseLong(s.substring(eq + 1).trim());
                    } catch (Throwable t2) {
                        continue;
                    }
                    if (base <= 0) {
                        continue;
                    }
                    Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
                    if (it == null) {
                        continue;
                    }
                    int stock = 0;
                    if (ticker != null) {
                        stock = TickerStock.networkCount(level, ticker, new ItemStack(it));
                    }
                    long unit = com.tangtu.kjbb.MarketPrice.uiPrice(id, stock);
                    if (unit <= 0) {
                        unit = base;
                    }
                    long ask = com.tangtu.kjbb.OfferBook.bestPrice(id);      // 卖一
                    long bid = com.tangtu.kjbb.OrderBook.bestBid(id);         // 买一
                    if (ask <= 0) {
                        ask = unit;
                    }
                    int supply = com.tangtu.kjbb.OfferBook.available(id);      // 挂单在售总数
                    if (!first) {
                        sb.append('\n');          // ← 行分隔符（之前被我改丢了）
                    }
                    sb.append(id).append('|').append(ask).append('|').append(bid)
                            .append('|').append(supply).append('|').append(unit);
                    first = false;
                }
            } catch (Throwable t) {
                System.out.println("[TangtuEconomical] 行情列表异常: " + t);
            }
            // 兜底：如果一条都没拼出来，就直接按 items() 列
            if (sb.length() == 0) {
                for (String id : com.tangtu.kjbb.ShopPrices.items()) {
                    long b = com.tangtu.kjbb.ShopPrices.price(id);
                    if (b <= 0) {
                        continue;
                    }
                    Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
                    if (it == null) {
                        continue;
                    }
                    sb.append(id).append('|').append(b).append('|').append(0)
                            .append('|').append(com.tangtu.kjbb.OfferBook.available(id))
                            .append('|').append(b).append('\n');
                }
            }
            // 调试：把结果写文件（System.out 被 KubeJS 吞了，只能写文件）
            try {
                java.nio.file.Path dp = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                        .resolve("kjbb").resolve("_debug_markets.txt");
                java.nio.file.Files.createDirectories(dp.getParent());
                String info = "time=" + System.currentTimeMillis()
                        + " rows=" + (sb.length() == 0 ? 0 : sb.toString().split("\n").length)
                        + " dumpLen=" + com.tangtu.kjbb.ShopPrices.dump().length()
                        + " items=" + com.tangtu.kjbb.ShopPrices.items().size()
                        + "\n---\n" + sb + "\n";
                java.nio.file.Files.writeString(dp, info, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
                // 忽略
            }
            sendMarkets(sp, sb.toString());
        });
    }

    private static void handleReqHistory(ReqHistory p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            try {
                // 读我们自己的 K 线存储（不依赖股市 mod）
                long last = com.tangtu.kjbb.KlineStore.lastStart(p.itemId());
                if (last > 0L && System.currentTimeMillis() - last
                        > 60L * 60_000L) {
                    // 60 分钟内没有任何成交 → 当作没有行情
                    sendHistory(sp, "");
                    return;
                }
                for (var c : com.tangtu.kjbb.KlineStore.recent(p.itemId(), 40)) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(c.open()).append('|').append(c.high()).append('|')
                            .append(c.low()).append('|').append(c.close()).append('|')
                            .append(c.volume());
                }
            } catch (Throwable t) {
                System.out.println("[TangtuEconomical] K线读取异常: " + t);
            }
            sendHistory(sp, sb.toString());
        });
    }

    private static int historySize(String itemId) {
        return com.tangtu.kjbb.KlineStore.size(itemId);
    }

    /**
     * 买家下单（K 线界面）：先有买家订单，后有卖家放货。
     *
     *   ① 校验余额 → 扣钱（钱先挂起，等卖家供货到货后才转给卖家）
     *   ② 写订单（PENDING）
     *   ③ 不发包、不广播 —— 等卖家执行 /billsupply 供货
     */
    private static void handleAddMarketItem(AddMarketItem p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            String id = p.itemId();
            if (id == null || id.isEmpty()) {
                return;
            }
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    ResourceLocation.tryParse(id));
            if (item == null) {
                sp.displayClientMessage(Component.literal("\u00a7c\u7269\u54c1\u4e0d\u5b58\u5728\uff1a" + id), false);
                return;
            }
            long have = (long) com.tangtu.kjbb.ShopPrices.price(id);
            if (have > 0) {
                sp.displayClientMessage(Component.literal("\u00a77[\u5e02\u573a] "
                        + id + " \u5df2\u5728\u5e02\u573a\u91cc\uff08\u57fa\u51c6\u4ef7 "
                        + have + "\uff09\uff0c\u76f4\u63a5\u9009\u4e2d\u5b83\u4ea4\u6613\u5373\u53ef"), false);
                return;
            }
            // 初始价：玩家填了就用玩家的；没填按食物营养或 12
            long price = Math.max(0, p.price());
            if (price <= 0) {
                price = 12L;
            }
            try {
                if (p.price() <= 0) {
                    var stack = new net.minecraft.world.item.ItemStack(item);
                    var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
                    if (food != null) {
                        long v = (long) food.nutrition() + (long) food.saturation() * 2L;
                        price = Math.max(3L, v * 6L);
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
            boolean ok = com.tangtu.kjbb.ShopPrices.setPrice(id, (int) price);
            if (!ok) {
                sp.displayClientMessage(Component.literal("\u00a7c\u6dfb\u52a0\u5931\u8d25"), false);
                return;
            }
            try {
                com.tangtu.kjbb.MarketSync.sync(id, (int) price);
            } catch (Throwable ignored) {
                // ignore
            }
            sp.displayClientMessage(Component.literal("\u00a7a[\u5e02\u573a] \u5df2\u628a "
                    + item.getDescription().getString() + " \u52a0\u5165\u5e02\u573a\uff0c\u521d\u59cb\u4ef7 "
                    + price + " \u5510\u5154\u5e01\uff1b\u73b0\u5728\u53ef\u4ee5\u4e70\u5356\u4e86"), false);
            System.out.println("[TangtuEconomical] \u5e02\u573a\u6dfb\u52a0\u7269\u54c1 " + id
                    + " price=" + price + " by " + sp.getScoreboardName());
            // 刷新市场列表
            sendMarkets(sp, com.tangtu.kjbb.MarketBridge.markets());
        });
    }

    private static void handleReqWillingness(ReqWillingness p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            int level = com.tangtu.kjbb.MarketBots.willingness(p.itemId());
            int prob = com.tangtu.kjbb.MarketBots.orderFillProbability(p.itemId(), 16);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new WillingnessReply(p.itemId(), level, prob));
        });
    }

    private static void handleWillingnessReply(WillingnessReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            com.tangtu.kjbb.client.MarketCache.setWillingness(p.itemId(), p.level());
            com.tangtu.kjbb.client.MarketCache.setFillProb(p.itemId(), p.prob());
        });
    }

    /** 挂买单：扣钱托管 + 入订单簿 */
    private static void handlePlaceBuyOrder(PlaceBuyOrder p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            String id = p.itemId();
            int want = Math.max(1, p.count());
            long unit = Math.max(1L, p.unit());
            long total = unit * want;
            if (id == null || id.isEmpty()) {
                return;
            }
            var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    ResourceLocation.tryParse(id));
            if (it == null) {
                sp.displayClientMessage(Component.literal("\u00a7c\u7269\u54c1\u4e0d\u5b58\u5728"), false);
                return;
            }
            String buyer = sp.getScoreboardName();
            if (BankBridge.money(buyer) < total) {
                sp.displayClientMessage(Component.literal("\u00a7c\u4f59\u989d\u4e0d\u8db3\uff1a\u9700\u8981 "
                        + total + "\uff0c\u4f60\u6709 " + BankBridge.money(buyer)), false);
                return;
            }
            String addr = com.tangtu.kjbb.AddressBook.addressOf(sp);
            if (addr == null || addr.isEmpty()) {
                var reg = com.tangtu.kjbb.PortRegistry.pick(sp.blockPosition(), addr);
                if (reg != null && reg.address() != null && !reg.address().isEmpty()) {
                    addr = reg.address();
                }
            }
            String finalAddr = addr == null ? "" : addr;
            long left = BankBridge.takeMoney(buyer, total);
            var row = com.tangtu.kjbb.OrderBook.add(buyer, id, want, unit, finalAddr);
            // 买单建成功了 → 这个物品正式进入市场（没有基准价就用玩家出的价）
            try {
                if (com.tangtu.kjbb.ShopPrices.price(id) <= 0) {
                    com.tangtu.kjbb.ShopPrices.setPrice(id, (int) unit);
                }
                com.tangtu.kjbb.ShopKline.push(id, (int) unit);
                com.tangtu.kjbb.MarketSync.sync(id, (int) unit);
            } catch (Throwable ignored) {
                // ignore
            }
            sp.displayClientMessage(Component.literal("\u00a7a[\u4e0b\u5355] \u8ba2\u5355 #" + row.id
                    + "\uff1a" + want + " \u4e2a \u00d7 " + unit + " = " + total
                    + " \u5510\u5154\u5e01\uff08\u5df2\u6263\uff0c\u7b49\u5356\u5bb6\u4f9b\u8d27\uff09"), false);
            sp.displayClientMessage(Component.literal("\u00a77\u4f59\u989d " + left + "\uff1b\u8d27\u5230\u63d0\u8d27\u70b9\u300c"
                    + finalAddr + "\u300d\u540e\u94b1\u624d\u8f6c\u7ed9\u5356\u5bb6"), false);
            System.out.println("[TangtuEconomical] \u6302\u4e70\u5355 #" + row.id + " " + buyer
                    + " " + id + " x" + want + " unit=" + unit);
            com.tangtu.kjbb.MarketBroadcast.buyOrder(sp.getServer(), buyer, id, want, unit, total);
        });
    }

    private static void handleBuy(BuyMarket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (!(sp.level() instanceof ServerLevel level)) {
                return;
            }
            String id = p.itemId();
            int want = Math.max(1, p.count());
            net.minecraft.world.item.Item it =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                            ResourceLocation.tryParse(id));
            if (it == null) {
                sp.displayClientMessage(Component.literal("§c这个标的不是物品：" + id), false);
                return;
            }
            BlockPos tickerPos = TickerShip.nearestTicker(level, sp.blockPosition(), 32);
            int stock = 0;
            if (tickerPos != null) {
                stock = TickerStock.networkCount(level, tickerPos, new ItemStack(it));
            }
            // 价格：卖一优先 → 最近市场价 → 定价表
            long unitL = com.tangtu.kjbb.OfferBook.bestPrice(id);
            if (unitL <= 0) {
                unitL = com.tangtu.kjbb.MarketPrice.uiPrice(id, stock);
            }
            // 基准价只给机器人用：玩家之间买卖只看真实挂单/成交
            // （没有卖单时不再拿基准价兜底）
            int unit = (int) (unitL > 0 ? unitL : 0);
            if (unit <= 0) {
                sp.displayClientMessage(Component.literal(
                        "§c现在没人卖这个，不能直接买入；"
                                + "想买请挂买单：/billorder place " + id + " <数量> <你愿意出的单价>"), false);
                return;
            }
            long total = (long) unit * want;
            String buyer = sp.getScoreboardName();
            if (BankBridge.money(buyer) < total) {
                sp.displayClientMessage(Component.literal(
                        "§c余额不足：需要 " + total + " 唐兔币，你有 " + BankBridge.money(buyer) + " 唐兔币"), false);
                return;
            }
            boolean paidMarket = com.tangtu.kjbb.Pay.pay(sp, total);
            long after = paidMarket ? com.tangtu.kjbb.Wallet.money(buyer) : -1L;
            if (after < 0L) {
                sp.displayClientMessage(Component.literal("§c余额不足"), false);
                return;
            }
            // 自己的绑定地址优先；没有才用附近提货点登记表的地址
            String addr = com.tangtu.kjbb.AddressBook.addressOf(sp);
            if (addr == null || addr.isEmpty()) {
                var reg = com.tangtu.kjbb.PortRegistry.pick(
                        tickerPos == null ? sp.blockPosition() : tickerPos, addr);
                if (reg != null && reg.address() != null && !reg.address().isEmpty()) {
                    addr = reg.address();
                }
            }
            var row = com.tangtu.kjbb.OrderBook.add(buyer, id, want, unit, addr);
            com.tangtu.kjbb.ShopKline.push(id, (int) unit);
            com.tangtu.kjbb.MarketSync.sync(id, unit);

            sp.displayClientMessage(Component.literal("§a[下单] 订单 #" + row.id + "：" + want
                    + " 个 × " + unit + " = " + total + " 唐兔币（已扣，等卖家供货）"), false);
            sp.displayClientMessage(Component.literal("§7余额 " + after
                    + " 唐兔币；货到提货点「" + addr + "」后钱才转给卖家"), false);
            System.out.println("[TangtuEconomical] 买家下单 #" + row.id + " " + buyer + " " + id
                    + " x" + want + " unit=" + unit);
            com.tangtu.kjbb.MarketBroadcast.buyOrder(sp.getServer(), buyer, id, want,
                    (long) unit, total);
        });
    }

    private static BlockPos nearestTicker(ServerLevel level, BlockPos center) {
        for (int r = 0; r <= 32; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    var p = center.offset(dx, 0, dz);
                    if (com.tangtu.kjbb.TickerStock.isTicker(level, p)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    public static void requestMyOrders() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqMyOrders());
    }

    public static void requestMarkets() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqMarkets());
    }

    public static void requestHistory(String itemId) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqHistory(itemId));
    }

    public static void requestTicketDetail(long id) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqTicketDetail(id));
    }

    public static void ticketBuy(long id) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new BuyTicket(id));
    }

    public static void ticketCancel(long id) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new CancelTicket(id));
    }

    public static void requestOpenOrders() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqOpenOrders());
    }

    public static void requestTickets() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqTickets());
    }

    public static void requestOrderDetail(long id) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqOrderDetail(id));
    }

    public static void requestItemOrders(String itemId) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqItemOrders(itemId));
    }

    public static void takeOrder(long id) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new TakeOrder(id));
    }

    public static void cancelOrder(long id) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new CancelOrder(id));
    }

    public static void placeOffer(String itemId, int count, long price) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new PlaceOffer(itemId, count, price));
    }

    public static void requestWillingness(String itemId) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ReqWillingness(itemId));
    }

    public static void buyOrder(String itemId, int count, long unit) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new PlaceBuyOrder(itemId, Math.max(1, count), Math.max(1L, unit)));
    }

    public static void addItem(String itemId) {
        addItem(itemId, 0);
    }

    /** price &lt;= 0 表示让系统给建议价 */
    public static void addItem(String itemId, int price) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new AddMarketItem(itemId, Math.max(0, price)));
    }

    public static void buy(String itemId, int count) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new BuyMarket(itemId, count));
    }
}
