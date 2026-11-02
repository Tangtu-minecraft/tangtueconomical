package com.tangtu.kjbb.net;

import com.tangtu.kjbb.BotConfig;
import com.tangtu.kjbb.LaborBots;
import com.tangtu.kjbb.Pay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** 劳动合同购买界面：开界面 + 下单 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class LaborNetwork {

    private LaborNetwork() {
    }

    /** S→C：打开购买界面，带上单价参数 */
    public record Open(int pricePerHour, int minMinutes, int maxMinutes, int defaultMinutes,
                       int usedGlobal, int maxGlobal, int usedMine, int maxMine)
            implements CustomPacketPayload {
        public static final Type<Open> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "labor_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC =
                new StreamCodec<>() {
                    @Override
                    public Open decode(RegistryFriendlyByteBuf buf) {
                        return new Open(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                                buf.readVarInt(), buf.readVarInt());
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, Open v) {
                        buf.writeVarInt(v.pricePerHour());
                        buf.writeVarInt(v.minMinutes());
                        buf.writeVarInt(v.maxMinutes());
                        buf.writeVarInt(v.defaultMinutes());
                        buf.writeVarInt(v.usedGlobal());
                        buf.writeVarInt(v.maxGlobal());
                        buf.writeVarInt(v.usedMine());
                        buf.writeVarInt(v.maxMine());
                    }
                };
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S：请求现有假人列表 */
    public record ReqList() implements CustomPacketPayload {
        public static final Type<ReqList> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "labor_req_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqList> CODEC =
                StreamCodec.unit(new ReqList());
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S→C：假人列表 */
    public record LaborListReply(String text) implements CustomPacketPayload {
        public static final Type<LaborListReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "labor_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LaborListReply> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, LaborListReply::text,
                        LaborListReply::new);
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S→C：打开合同详情 */
    public record OpenDetail(String worker, int minutes, int price, String owner)
            implements CustomPacketPayload {
        public static final Type<OpenDetail> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "labor_detail"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenDetail> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, OpenDetail::worker,
                        ByteBufCodecs.VAR_INT, OpenDetail::minutes,
                        ByteBufCodecs.VAR_INT, OpenDetail::price,
                        ByteBufCodecs.STRING_UTF8, OpenDetail::owner,
                        OpenDetail::new);
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void openDetail(ServerPlayer sp, String worker, int minutes, int price, String owner) {
        PacketDistributor.sendToPlayer(sp, new OpenDetail(worker, minutes, price, owner));
    }

    /** C→S：下单 */
    public record Buy(String name, int minutes) implements CustomPacketPayload {
        public static final Type<Buy> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "labor_buy"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Buy> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Buy::name,
                ByteBufCodecs.VAR_INT, Buy::minutes,
                Buy::new);
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void open(ServerPlayer sp) {
        var cfg = BotConfig.get();
        int perHour = (int) LaborBots.pricePerHour();
        PacketDistributor.sendToPlayer(sp, new Open(perHour, 10, 10080, cfg.laborMinutes,
                LaborBots.countActive(), cfg.laborMaxBots,
                LaborBots.countByOwner(sp.getScoreboardName()), cfg.laborMaxPerPlayer));
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(Open.TYPE, Open.CODEC, LaborNetwork::handleOpen);
        r.playToServer(Buy.TYPE, Buy.CODEC, LaborNetwork::handleBuy);
        r.playToClient(OpenDetail.TYPE, OpenDetail.CODEC, LaborNetwork::handleDetail);
        r.playToServer(ReqList.TYPE, ReqList.CODEC, LaborNetwork::handleReqList);
        r.playToClient(LaborListReply.TYPE, LaborListReply.CODEC, LaborNetwork::handleList);
    }

    private static void handleReqList(ReqList p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) {
            ctx.enqueueWork(() -> PacketDistributor.sendToPlayer(sp,
                    new LaborListReply(LaborBots.listText())));
        }
    }

    private static void handleList(LaborListReply p, IPayloadContext ctx) {
        // 只更新缓存，界面每帧自己读，不重建（重建会触发无限刷新）
        com.tangtu.kjbb.client.MarketCache.setLaborList(p.text());
    }

    private static void handleDetail(OpenDetail p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientLaborOpen.openDetail(p.worker(), p.minutes(), p.price(), p.owner()));
    }

    private static void handleOpen(Open p, IPayloadContext ctx) {
        // 必须切回主线程再开界面
        ctx.enqueueWork(() -> ClientLaborOpen.open(p.pricePerHour(), p.minMinutes(),
                p.maxMinutes(), p.defaultMinutes(), p.usedGlobal(), p.maxGlobal(),
                p.usedMine(), p.maxMine()));
    }

    private static void handleBuy(Buy p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        String name = p.name() == null ? "" : p.name().trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            sp.displayClientMessage(Component.literal(
                    "\u00a7c\u5047\u4eba\u540d\u53ea\u80fd\u662f 3~16 \u4f4d\u5b57\u6bcd\u3001\u6570\u5b57\u6216\u4e0b\u5212\u7ebf"), false);
            return;
        }
        var cfg = BotConfig.get();
        int minutes = Math.max(10, Math.min(10080, p.minutes()));
        long price = LaborBots.priceFor(minutes);
        if (!Pay.pay(sp, price)) {
            sp.displayClientMessage(Component.literal("\u00a7c\u5510\u5154\u5e01\u4e0d\u591f\uff08\u9700 "
                    + price + "\uff09"), false);
            return;
        }
        com.tangtu.kjbb.RegulatorOps.creditTreasury(price, "\u5047\u4eba\u96c7\u4f63");
        LaborBots.issueItem(sp, name, minutes);
        com.tangtu.kjbb.IssuanceLog.log("labor_contract", name, price, sp.getScoreboardName());
        sp.displayClientMessage(Component.literal("\u00a7a\u5df2\u7b7e\u8ba2\uff1a\u5047\u4eba \u00a7e"
                + name + "\u00a7a\uff0c" + minutes + " \u5206\u949f\uff0c\u82b1\u8d39 " + price
                + " \u5510\u5154\u5e01"), false);
    }
}
