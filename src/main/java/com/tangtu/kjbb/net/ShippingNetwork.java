package com.tangtu.kjbb.net;

import com.tangtu.kjbb.BotConfig;
import com.tangtu.kjbb.RomaMarket;
import com.tangtu.kjbb.RomaOrders;
import com.tangtu.kjbb.ServerCalendar;
import com.tangtu.kjbb.VoyageBill;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 航运中心（航次 / 份额 / 历史 / 罗马国 / 包船）的数据通道。
 *
 * 回包每行一个记录，字段用 \t 分隔，首列是类型：
 * <pre>
 *   W  day activeBillId activeName deadline fx romaCash paid injected
 *      tariff importTariff charterFee charterMaxValue charterMaxQty
 *   B  id name status sold total price deadline dueDay legs legDone
 *      condition mult outcome mine revenue arrivalValue tariff
 *   L  billId item have need          （载货清单，按 need 的顺序）
 *   P  billId holder shares           （持有人分布）
 *   O  id billId side item qty unit status   （我的罗马贸易单）
 *   G  item sell buy stock            （可贸易品类）
 *   C  item qty value                 （包船草案货物）
 *   H  hasDraft cargoValue freight
 * </pre>
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class ShippingNetwork {

    private ShippingNetwork() {
    }

    /** 历史航次最多带几条 */
    private static final int HISTORY_MAX = 8;

    public record ReqShipping() implements CustomPacketPayload {
        public static final Type<ReqShipping> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_shipping"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqShipping> CODEC =
                StreamCodec.unit(new ReqShipping());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ShippingInfo(String body) implements CustomPacketPayload {
        public static final Type<ShippingInfo> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "shipping_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ShippingInfo> CODEC =
                StreamCodec.composite(
                        com.tangtu.kjbb.net.ChunkedString.CODEC, ShippingInfo::body,
                        ShippingInfo::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(ReqShipping.TYPE, ReqShipping.CODEC, ShippingNetwork::handleRequest)
                .playToClient(ShippingInfo.TYPE, ShippingInfo.CODEC, ShippingNetwork::handleReply);
    }

    private static void handleRequest(ReqShipping payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            String body;
            try {
                body = build(sp);
            } catch (Throwable t) {
                body = "X\t" + t.getClass().getSimpleName() + "\t" + t.getMessage();
            }
            PacketDistributor.sendToPlayer(sp, new ShippingInfo(body));
        });
    }

    private static void handleReply(ShippingInfo payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientShippingBridge.accept(payload.body()));
    }

    public static void sendRequest() {
        PacketDistributor.sendToServer(new ReqShipping());
    }

    /** 每段判定日（逗号分隔）：第 k 段 = 出海日 + k×航期/段数，不超过到港日 */
    private static String legDaysCsv(com.tangtu.kjbb.VoyageBill.Bill b) {
        if (b.legDone <= 0 || b.departDay <= 0) {
            return "";
        }
        long vd = Math.max(1L, com.tangtu.kjbb.BotConfig.get().voyageDays);
        StringBuilder sb = new StringBuilder();
        for (int k = 1; k <= b.legDone; k++) {
            long d = b.departDay + (long) k * vd / Math.max(1, b.legs);
            if (b.dueDay > 0 && d > b.dueDay) {
                d = b.dueDay;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(d);
        }
        return sb.toString();
    }

    private static String build(ServerPlayer sp) {        StringBuilder sb = new StringBuilder();
        String me = sp.getScoreboardName();
        long day = ServerCalendar.currentDay();
        VoyageBill.Bill active = VoyageBill.openBill();
        BotConfig cfg = BotConfig.get();
        int fx = 0;
        if (active != null) {
            try {
                fx = RomaMarket.fxPermille(active.id);
            } catch (Throwable ignored) {
                fx = 0;
            }
        }
        sb.append("W\t").append(day)
                .append('\t').append(active == null ? 0L : active.id)
                .append('\t').append(active == null ? "" : active.name)
                .append('\t').append(active == null ? 0L : active.deadline)
                .append('\t').append(fx)
                .append('\t').append(RomaMarket.cash())
                .append('\t').append(RomaMarket.totalPaid())
                .append('\t').append(RomaMarket.totalInjected())
                .append('\t').append(cfg.voyageTariffPermille)
                .append('\t').append(cfg.voyageImportTariffPermille)
                .append('\t').append(cfg.voyageCharterFeePermille)
                .append('\t').append(cfg.voyageCharterMaxValue)
                .append('\t').append(cfg.voyageCharterMaxQtyPerItem)
                .append('\n');

        // 航次：在招 + 航行中全带，已结束只带最近 HISTORY_MAX 条
        List<VoyageBill.Bill> all = VoyageBill.all();
        int hist = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            VoyageBill.Bill b = all.get(i);
            boolean closed = VoyageBill.CLOSED.equals(b.status);
            if (closed) {
                if (hist >= HISTORY_MAX) {
                    continue;
                }
                hist++;
            }
            long tariff = 0L;
            try {
                tariff = Math.max(0L, b.revenue) * Math.max(0, cfg.voyageTariffPermille) / 1000L
                        + Math.max(0L, b.importCost) * Math.max(0, cfg.voyageImportTariffPermille) / 1000L;
            } catch (Throwable ignored) {
                tariff = 0L;
            }
            sb.append("B\t").append(b.id)
                    .append('\t').append(b.name)
                    .append('\t').append(b.status)
                    .append('\t').append(b.sold)
                    .append('\t').append(b.total)
                    .append('\t').append(b.price)
                    .append('\t').append(b.deadline)
                    .append('\t').append(b.dueDay)
                    .append('\t').append(b.legs)
                    .append('\t').append(b.legDone)
                    // 船况 / 倍率是内部参数：字段保留占位，但不下发真值
                    .append("\t0\t0")
                    .append('\t').append(b.outcome)
                    .append('\t').append(b.heldBy(me))
                    .append('\t').append(b.revenue)
                    .append('\t').append(b.arrivalValue)
                    .append('\t').append(b.shareValue())
                    .append('\t').append(tariff)
                    // 航程记录：逐段事件 + 每段判定日（详情页按「日期 + 遭遇」逐条写）
                    .append('\t').append(String.join(";", b.legLog))
                    .append('\t').append(legDaysCsv(b))
                    .append('\n');
            for (var e : b.need.entrySet()) {
                sb.append("L\t").append(b.id)
                        .append('\t').append(e.getKey())
                        .append('\t').append(b.cargo.getOrDefault(e.getKey(), 0))
                        .append('\t').append(e.getValue())
                        .append('\n');
            }
            for (var e : b.holders.entrySet()) {
                sb.append("P\t").append(b.id)
                        .append('\t').append(e.getKey())
                        .append('\t').append(e.getValue())
                        .append('\n');
            }
        }

        // 我的罗马订单（未结算的）
        try {
            List<RomaOrders.Order> mine = RomaOrders.of(me, true);
            for (int i = 0; i < mine.size() && i < 6; i++) {
                RomaOrders.Order o = mine.get(i);
                sb.append("O\t").append(o.id)
                        .append('\t').append(o.billId)
                        .append('\t').append(o.side)
                        .append('\t').append(o.item)
                        .append('\t').append(o.qty)
                        .append('\t').append(o.unitPrice)
                        .append('\t').append(o.status)
                        .append('\n');
            }
        } catch (Throwable ignored) {
            // 没装罗马国订单模块就跳过
        }

        // 份额二级市场（我的 / 市场 两页用）
        try {
            for (com.tangtu.kjbb.StockMarket.Row r : com.tangtu.kjbb.StockMarket.open()) {
                sb.append("S\t").append(r.id)
                        .append('\t').append(r.billId)
                        .append('\t').append(r.shares)
                        .append('\t').append(r.price)
                        .append('\t').append(r.seller == null ? "" : r.seller)
                        .append('\n');
            }
        } catch (Throwable ignored) {
            // 没开二级市场就跳过
        }

        // 可贸易品类：当前航次的载货清单
        if (active != null) {
            for (String item : active.need.keySet()) {
                long sell;
                long buy;
                long stock;
                try {
                    sell = RomaOrders.sellPrice(item, active.id);
                } catch (Throwable t) {
                    sell = 0L;
                }
                try {
                    buy = RomaOrders.buyPrice(item, active.id);
                } catch (Throwable t) {
                    buy = 0L;
                }
                try {
                    stock = RomaMarket.stockOf(item);
                } catch (Throwable t) {
                    stock = 0L;
                }
                sb.append("G\t").append(item)
                        .append('\t').append(sell)
                        .append('\t').append(buy)
                        .append('\t').append(stock)
                        .append('\n');
            }
        }

        // 包船草案
        VoyageBill.Bill ch = VoyageBill.charterOf(me);
        long chValue = 0L;
        if (ch != null) {
            for (var e : ch.cargo.entrySet()) {
                long unit = 0L;
                try {
                    unit = RomaMarket.unitPrice(e.getKey());
                } catch (Throwable ignored) {
                    unit = 0L;
                }
                long v = unit * e.getValue();
                chValue += v;
                sb.append("C\t").append(e.getKey())
                        .append('\t').append(e.getValue())
                        .append('\t').append(v)
                        .append('\n');
            }
        }
        long freight = Math.max(500L, chValue * Math.max(0, cfg.voyageCharterFeePermille) / 1000L);
        sb.append("H\t").append(ch != null ? 1 : 0)
                .append('\t').append(chValue)
                .append('\t').append(ch == null ? 0L : freight)
                .append('\n');
        return sb.toString();
    }
}
