package com.tangtu.kjbb.net;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 背包物品清单：客户端请求 → 服务端回包（供界面做可视化选物品） */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class InvNetwork {

    private InvNetwork() {
    }

    public record ReqInv() implements CustomPacketPayload {
        public static final Type<ReqInv> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_inv"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqInv> CODEC =
                StreamCodec.unit(new ReqInv());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** body 每行：itemId|count */
    public record InvReply(String body) implements CustomPacketPayload {
        public static final Type<InvReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "inv_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, InvReply> CODEC =
                StreamCodec.composite(
                        com.tangtu.kjbb.net.ChunkedString.CODEC, InvReply::body,
                        InvReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(ReqInv.TYPE, ReqInv.CODEC, InvNetwork::handleReq)
                .playToClient(InvReply.TYPE, InvReply.CODEC, InvNetwork::handleReply);
    }

    private static void handleReq(ReqInv payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            java.util.Map<String, Integer> agg = new java.util.LinkedHashMap<>();
            var inv = sp.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                ResourceLocation rl = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (rl == null) {
                    continue;
                }
                String id = rl.toString();
                agg.merge(id, st.getCount(), Integer::sum);
            }
            boolean first = true;
            for (var e : agg.entrySet()) {
                if (!first) {
                    sb.append('\n');
                }
                sb.append(e.getKey()).append('|').append(e.getValue());
                first = false;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new InvReply(sb.toString()));
        });
    }

    private static void handleReply(InvReply payload, IPayloadContext context) {
        context.enqueueWork(() -> com.tangtu.kjbb.net.ClientInvBridge.accept(payload.body()));
    }

    public static void request() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqInv());
    }
}
