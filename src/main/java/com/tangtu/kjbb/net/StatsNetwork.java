package com.tangtu.kjbb.net;

import com.tangtu.kjbb.EconReport;
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
 * 统计局面板的数据通道：客户端请求 → 服务端现算一份指标 → 回一行一行的文本。
 *
 * 格式：每行 "类型\t左\t右"，行间用 \n；类型 m=页眉 h=小标题 r=数值行。
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class StatsNetwork {

    private StatsNetwork() {
    }

    /** 客户端 → 服务端：要一份统计 */
    public record ReqStats() implements CustomPacketPayload {
        public static final Type<ReqStats> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_stats"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqStats> CODEC =
                StreamCodec.unit(new ReqStats());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：统计行（见类注释的格式） */
    public record StatsReply(String body) implements CustomPacketPayload {
        public static final Type<StatsReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "stats_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatsReply> CODEC =
                StreamCodec.composite(
                        com.tangtu.kjbb.net.ChunkedString.CODEC, StatsReply::body,
                        StatsReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(ReqStats.TYPE, ReqStats.CODEC, StatsNetwork::handleRequest)
                .playToClient(StatsReply.TYPE, StatsReply.CODEC, StatsNetwork::handleReply);
    }

    private static void handleRequest(ReqStats payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            try {
                List<String[]> rows = EconReport.stats();
                for (int i = 0; i < rows.size(); i++) {
                    if (i > 0) {
                        sb.append('\n');
                    }
                    String[] r = rows.get(i);
                    sb.append(r[0]).append('\t').append(r[1]).append('\t').append(r[2]);
                }
            } catch (Throwable t) {
                sb.setLength(0);
                sb.append("r\t\u7edf\u8ba1\u5931\u8d25\t").append(t.getClass().getSimpleName());
            }
            PacketDistributor.sendToPlayer(sp, new StatsReply(sb.toString()));
        });
    }

    private static void handleReply(StatsReply payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientStatsBridge.accept(payload.body()));
    }

    public static void sendRequest() {
        PacketDistributor.sendToServer(new ReqStats());
    }
}
