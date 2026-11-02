package com.tangtu.kjbb.net;

import com.tangtu.kjbb.BillMode;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 提单记名/不记名切换（发报机界面上的按钮用） */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class BillBearerNetwork {

    private BillBearerNetwork() {
    }

    public record SetBillBearer(boolean bearer) implements CustomPacketPayload {
        public static final Type<SetBillBearer> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "set_bill_bearer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetBillBearer> CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, SetBillBearer::bearer, SetBillBearer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BillBearerState(boolean bearer) implements CustomPacketPayload {
        public static final Type<BillBearerState> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "bill_bearer_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BillBearerState> CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, BillBearerState::bearer,
                        BillBearerState::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(SetBillBearer.TYPE, SetBillBearer.CODEC,
                        BillBearerNetwork::handleSet)
                .playToClient(BillBearerState.TYPE, BillBearerState.CODEC,
                        BillBearerNetwork::handleState);
    }

    private static void handleState(BillBearerState p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientBillBearer.setState(p.bearer()));
    }

    private static void handleSet(SetBillBearer p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            BillMode.setBearer(sp, p.bearer());
            sp.displayClientMessage(Component.literal(p.bearer()
                    ? "§a提单模式：不记名"
                    : "§a提单模式：记名"), true);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new BillBearerState(p.bearer()));
        });
    }

    public static void send(boolean bearer) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new SetBillBearer(bearer));
    }

    public static void requestState() {
        // 打开界面时同步一次（服务端在 handleSet 里回发；这里直接读本地缓存即可）
    }
}