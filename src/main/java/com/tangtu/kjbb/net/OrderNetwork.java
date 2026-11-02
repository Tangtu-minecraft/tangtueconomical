package com.tangtu.kjbb.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 「下单」界面：把选好的货一次提交，扣钱并出票 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class OrderNetwork {

    private OrderNetwork() {
    }

    /** 服务端 → 客户端：打开下单界面 */
    public record OpenOrder(int x, int y, int z) implements CustomPacketPayload {
        public static final Type<OpenOrder> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenOrder> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, OpenOrder::x,
                        ByteBufCodecs.VAR_INT, OpenOrder::y,
                        ByteBufCodecs.VAR_INT, OpenOrder::z,
                        OpenOrder::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：提交订单 body = "id:count;id:count" */
    public record SubmitOrder(int x, int y, int z, String body) implements CustomPacketPayload {
        public static final Type<SubmitOrder> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "submit_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SubmitOrder> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SubmitOrder::x,
                        ByteBufCodecs.VAR_INT, SubmitOrder::y,
                        ByteBufCodecs.VAR_INT, SubmitOrder::z,
                        com.tangtu.kjbb.net.ChunkedString.CODEC, SubmitOrder::body,
                        SubmitOrder::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(OpenOrder.TYPE, OpenOrder.CODEC, OrderNetwork::handleOpen)
                .playToServer(SubmitOrder.TYPE, SubmitOrder.CODEC, OrderNetwork::handleSubmit);
    }

    private static void handleOpen(OpenOrder p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientOrderBridge.open(
                new BlockPos(p.x(), p.y(), p.z())));
    }

    private static void handleSubmit(SubmitOrder p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) {
                return;
            }
            if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                return;
            }
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            int okCount = 0;
            long paid = 0L;
            StringBuilder fail = new StringBuilder();
            java.util.Map<String, Integer> stock = com.tangtu.kjbb.TickerStock.stock(level, pos);
            for (String part : p.body().split(";")) {
                if (part.isEmpty()) {
                    continue;
                }
                int c = part.lastIndexOf(':');
                if (c <= 0) {
                    continue;
                }
                String id = part.substring(0, c);
                int want;
                try {
                    want = Integer.parseInt(part.substring(c + 1));
                } catch (Throwable t) {
                    continue;
                }
                if (want <= 0) {
                    continue;
                }
                int have = stock.getOrDefault(id, 0);
                if (have < want) {
                    fail.append(" ").append(id).append("(剩").append(have).append(")");
                    continue;
                }
                net.minecraft.world.item.Item item =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                ResourceLocation.tryParse(id));
                if (item == null) {
                    continue;
                }
                long unit = com.tangtu.kjbb.ShopPricing.price(id, have);
                long total = unit * want;
                String name = sp.getScoreboardName();
                if (com.tangtu.kjbb.BankBridge.money(name) < total) {
                    fail.append(" ").append(id).append("(钱不够)");
                    continue;
                }
                int got = com.tangtu.kjbb.TickerStock.extract(level, pos, item, want);
                if (got < want) {
                    if (got > 0) {
                        com.tangtu.kjbb.TickerStock.insert(level, pos,
                                new net.minecraft.world.item.ItemStack(item, got));
                    }
                    fail.append(" ").append(id).append("(取货失败)");
                    continue;
                }
                com.tangtu.kjbb.BankBridge.takeMoney(name, total);
                paid += total;
                long now = level.getGameTime();
                long expiresAt = now + com.tangtu.kjbb.item.BillData.LIFETIME_TICKS;
                String addr = com.tangtu.kjbb.AddressBook.addressOf(sp);
                long serial = com.tangtu.kjbb.item.BillRegistry.register(id, want, name, expiresAt,
                        pos.getX(), pos.getY(), pos.getZ(), addr);
                net.minecraft.world.item.ItemStack bill =
                        new net.minecraft.world.item.ItemStack(
                                com.tangtu.kjbb.item.KjbbItems.BILL.get());
                com.tangtu.kjbb.item.KjbbItems.setBill(bill,
                        new com.tangtu.kjbb.item.BillData(id, want, name, serial,
                                com.tangtu.kjbb.item.BillData.STATUS_ISSUED, expiresAt,
                                pos.getX(), pos.getY(), pos.getZ(), addr));
                if (!sp.getInventory().add(bill)) {
                    sp.drop(bill, false);
                }
                okCount++;
            }
            String msg = "§a下单完成：" + okCount + " 项，共付 " + paid + " 唐兔币，提单已生成"
                    + (fail.length() > 0 ? "；未成功：" + fail : "");
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), false);
        });
    }

    public static void open(net.minecraft.server.level.ServerPlayer sp, BlockPos pos) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                new OpenOrder(pos.getX(), pos.getY(), pos.getZ()));
    }

    public static void submit(BlockPos pos, String body) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new SubmitOrder(pos.getX(), pos.getY(), pos.getZ(), body));
    }
}
