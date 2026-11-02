package com.tangtu.kjbb.net;

import com.tangtu.kjbb.ShopBridge;
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

/** 面板数据通道：客户端请求「我的挂牌」，服务端回一段文本 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class PanelNetwork {

    private PanelNetwork() {
    }

    /** 客户端 → 服务端：请求我的挂牌 */
    public record ReqMyListings() implements CustomPacketPayload {
        public static final Type<ReqMyListings> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_my_listings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqMyListings> CODEC =
                StreamCodec.unit(new ReqMyListings());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：挂牌数据（行用 \n 分隔，第一行是摘要） */
    public record MyListingsReply(String body) implements CustomPacketPayload {
        public static final Type<MyListingsReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "my_listings_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MyListingsReply> CODEC =
                StreamCodec.composite(
                        com.tangtu.kjbb.net.ChunkedString.CODEC, MyListingsReply::body,
                        MyListingsReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(ReqMyListings.TYPE, ReqMyListings.CODEC, PanelNetwork::handleRequest)
                .playToClient(MyListingsReply.TYPE, MyListingsReply.CODEC, PanelNetwork::handleReply);
    }

    private static void handleRequest(ReqMyListings payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            String name = sp.getScoreboardName();
            StringBuilder sb = new StringBuilder();
            long pending = ShopBridge.pendingCount();
            sb.append("待放款 ").append(pending).append(" 单\n");
            String raw = ShopBridge.listings(name);
            if (raw == null || raw.isEmpty()) {
                sb.append("（还没有挂牌）");
            } else {
                String[] items = raw.split(";");
                for (int i = 0; i < items.length; i++) {
                    if (i > 0) {
                        sb.append('\n');
                    }
                    sb.append(items[i]);
                }
            }
            PacketDistributor.sendToPlayer(sp, new MyListingsReply(sb.toString()));
        });
    }

    private static void handleReply(MyListingsReply payload, IPayloadContext context) {
        context.enqueueWork(() -> com.tangtu.kjbb.net.ClientPanelBridge.accept(payload.body()));
    }

    /** 客户端侧接受数据（真正实现放在客户端类里，由 ClientPanelBridge 转调） */
    public static void sendRequest() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqMyListings());
    }
}
