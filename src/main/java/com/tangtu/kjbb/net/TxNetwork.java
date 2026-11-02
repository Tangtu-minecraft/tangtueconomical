package com.tangtu.kjbb.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 交易中心（我的挂单 / 托管订单 / 投递记录）的数据通道。
 *
 * <p>body 分段（制表符分隔，一段一行）：
 * <pre>
 * N  服务器时钟（毫秒，口径同 ServerClock，用于 PENDING 倒计时）
 * A  我绑定的提货点地址（空 = 未绑定）
 * M  挂单号  物品  数量  单价            ← 我自己的、仍 OPEN 的挂单
 * O  订单号  物品  数量  单价  状态  对手方  创建时间  托管金额
 * L  时间  卖家  买家  物品  数量  方式   ← 最近投递记录（MailLog 原始行）
 * </pre>
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class TxNetwork {

    private TxNetwork() {
    }

    /** C→S：请求交易中心数据 */
    public record TxReq() implements CustomPacketPayload {
        public static final Type<TxReq> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "tx_req"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TxReq> CODEC =
                StreamCodec.unit(new TxReq());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S→C：交易中心数据（body 可能较长，用 ChunkedString 自动分片） */
    public record TxInfo(String body) implements CustomPacketPayload {
        public static final Type<TxInfo> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "tx_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TxInfo> CODEC =
                StreamCodec.composite(ChunkedString.CODEC, TxInfo::body, TxInfo::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToServer(TxReq.TYPE, TxReq.CODEC, TxNetwork::handleReq);
        r.playToClient(TxInfo.TYPE, TxInfo.CODEC, TxNetwork::handleInfo);
    }

    private static void handleReq(TxReq p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        ctx.enqueueWork(() -> net.neoforged.neoforge.network.PacketDistributor
                .sendToPlayer(sp, new TxInfo(build(sp))));
    }

    private static void handleInfo(TxInfo p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.client.TxData.set(p.body()));
    }

    /** 客户端请求一次刷新 */
    public static void sendRequest() {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new TxReq());
        } catch (Throwable ignored) {
            // 单人还没进世界时会失败，忽略
        }
    }

    private static String build(ServerPlayer sp) {
        String me = sp.getScoreboardName();
        StringBuilder sb = new StringBuilder();
        sb.append("N\t").append(com.tangtu.kjbb.ServerClock.now()).append('\n');

        String addr = "";
        try {
            addr = com.tangtu.kjbb.AddressBook.addressOf(sp);
        } catch (Throwable ignored) {
            addr = "";
        }
        sb.append("A\t").append(addr == null ? "" : addr).append('\n');

        // 我自己的挂单（只列还开着的）
        try {
            for (var r : com.tangtu.kjbb.OfferBook.of(me)) {
                if (!com.tangtu.kjbb.OfferBook.OPEN.equals(r.status) || r.count <= 0) {
                    continue;
                }
                sb.append("M\t").append(r.id).append('\t')
                        .append(r.itemId).append('\t')
                        .append(r.count).append('\t')
                        .append(r.price).append('\n');
            }
        } catch (Throwable ignored) {
            // 没有挂单文件就当空
        }

        // 我的托管订单（买卖两边都算）
        try {
            for (var t : com.tangtu.kjbb.TradeOrder.mine(me)) {
                String opp = me.equals(t.seller) ? t.buyer : t.seller;
                sb.append("O\t").append(t.id).append('\t')
                        .append(t.item).append('\t')
                        .append(t.count).append('\t')
                        .append(t.unit).append('\t')
                        .append(t.status).append('\t')
                        .append(opp == null ? "" : opp).append('\t')
                        .append(t.created).append('\t')
                        .append(t.escrow).append('\n');
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        // 最近投递记录
        try {
            for (String line : com.tangtu.kjbb.MailLog.recent(15)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                sb.append("L\t").append(line.replace('\n', ' ')).append('\n');
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return sb.toString();
    }
}
