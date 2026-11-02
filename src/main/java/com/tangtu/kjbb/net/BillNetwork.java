package com.tangtu.kjbb.net;

import com.tangtu.kjbb.item.BillData;
import com.tangtu.kjbb.item.KjbbItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.tangtu.kjbb.item.BillRegistry;

/** 票据网络：签发提单 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class BillNetwork {

    private BillNetwork() {
    }

    public record IssueBill(String itemId, int count) implements CustomPacketPayload {
        public static final Type<IssueBill> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "issue_bill"));
        public static final StreamCodec<RegistryFriendlyByteBuf, IssueBill> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, IssueBill::itemId,
                        ByteBufCodecs.VAR_INT, IssueBill::count,
                        IssueBill::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：请求我的提单列表 */
    public record ReqMyBills() implements CustomPacketPayload {
        public static final Type<ReqMyBills> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_my_bills"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqMyBills> CODEC =
                StreamCodec.unit(new ReqMyBills());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：提单列表文本 */
    public record MyBillsReply(String body) implements CustomPacketPayload {
        public static final Type<MyBillsReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "my_bills_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MyBillsReply> CODEC =
                StreamCodec.composite(
                        com.tangtu.kjbb.net.ChunkedString.CODEC, MyBillsReply::body,
                        MyBillsReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：打开纸质票据界面 */
    /** 客户端 → 服务端：请打开票据界面 */
    public record ReqPaper() implements CustomPacketPayload {
        public static final Type<ReqPaper> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_paper"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqPaper> CODEC =
                StreamCodec.unit(new ReqPaper());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenPaper() implements CustomPacketPayload {
        public static final Type<OpenPaper> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_paper"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPaper> CODEC =
                StreamCodec.unit(new OpenPaper());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：打开某张提单的界面 */
    public record OpenBill(String itemId, int count, String issuer, long serial,
                           long expiresAt, String status, String address) implements CustomPacketPayload {
        public static final Type<OpenBill> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_bill"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenBill> CODEC =
                new StreamCodec<>() {
                    @Override
                    public OpenBill decode(RegistryFriendlyByteBuf buf) {
                        String itemId = ByteBufCodecs.STRING_UTF8.decode(buf);
                        int count = ByteBufCodecs.VAR_INT.decode(buf);
                        String issuer = ByteBufCodecs.STRING_UTF8.decode(buf);
                        long serial = ByteBufCodecs.VAR_LONG.decode(buf);
                        long expiresAt = ByteBufCodecs.VAR_LONG.decode(buf);
                        String status = ByteBufCodecs.STRING_UTF8.decode(buf);
                        String address = ByteBufCodecs.STRING_UTF8.decode(buf);
                        return new OpenBill(itemId, count, issuer, serial, expiresAt, status, address);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, OpenBill v) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, v.itemId());
                        ByteBufCodecs.VAR_INT.encode(buf, v.count());
                        ByteBufCodecs.STRING_UTF8.encode(buf, v.issuer());
                        ByteBufCodecs.VAR_LONG.encode(buf, v.serial());
                        ByteBufCodecs.VAR_LONG.encode(buf, v.expiresAt());
                        ByteBufCodecs.STRING_UTF8.encode(buf, v.status());
                        ByteBufCodecs.STRING_UTF8.encode(buf, v.address() == null ? "" : v.address());
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：凭这张票领取货物 */
    public record RedeemBill(long serial) implements CustomPacketPayload {
        public static final Type<RedeemBill> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "redeem_bill"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RedeemBill> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG, RedeemBill::serial,
                        RedeemBill::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(IssueBill.TYPE, IssueBill.CODEC, BillNetwork::handleIssue)
                .playToServer(ReqMyBills.TYPE, ReqMyBills.CODEC, BillNetwork::handleReqMyBills)
                .playToClient(MyBillsReply.TYPE, MyBillsReply.CODEC, BillNetwork::handleMyBillsReply)
                .playToServer(ReqPaper.TYPE, ReqPaper.CODEC, BillNetwork::handleReqPaper)
                .playToClient(OpenPaper.TYPE, OpenPaper.CODEC, BillNetwork::handleOpenPaper)
                .playToClient(OpenBill.TYPE, OpenBill.CODEC, BillNetwork::handleOpenBill)
                .playToServer(RedeemBill.TYPE, RedeemBill.CODEC, BillNetwork::handleRedeem);
    }

    private static void handleIssue(IssueBill payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            int want = Math.max(1, payload.count());
            ResourceLocation rl = ResourceLocation.tryParse(payload.itemId());
            if (rl == null) {
                sp.displayClientMessage(Component.literal("§c物品 id 无效：" + payload.itemId()), false);
                return;
            }
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null) {
                sp.displayClientMessage(Component.literal("§c物品不存在：" + payload.itemId()), false);
                return;
            }

            // 从背包里扣货（优先主手）
            int have = 0;
            for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
                ItemStack st = sp.getInventory().getItem(i);
                if (st != null && !st.isEmpty() && st.getItem() == item) {
                    have += st.getCount();
                }
            }
            if (have < want) {
                sp.displayClientMessage(Component.literal(
                        "§c货物不足：你只有 " + have + " 个 " + payload.itemId()), false);
                return;
            }
            int left = want;
            for (int i = 0; i < sp.getInventory().getContainerSize() && left > 0; i++) {
                ItemStack st = sp.getInventory().getItem(i);
                if (st != null && !st.isEmpty() && st.getItem() == item) {
                    int take = Math.min(st.getCount(), left);
                    st.shrink(take);
                    left -= take;
                }
            }

            long now = sp.level().getGameTime();
            long expiresAt = now + BillData.LIFETIME_TICKS;
            String addr = com.tangtu.kjbb.AddressBook.addressOf(sp);
            long serial = BillRegistry.register(payload.itemId(), want, sp.getScoreboardName(),
                    expiresAt, 0, 0, 0, addr);
            ItemStack bill = new ItemStack(KjbbItems.BILL.get());
            KjbbItems.setBill(bill, new BillData(payload.itemId(), want, sp.getScoreboardName(),
                    serial, BillData.STATUS_ISSUED, expiresAt, 0, 0, 0, addr));
            if (!sp.getInventory().add(bill)) {
                sp.drop(bill, false);
            }
            sp.displayClientMessage(Component.literal("§a已签发提单 No." + serial
                    + "：" + want + " 个 " + payload.itemId() + "（拿走货，票可转交他人领取）"), false);
            System.out.println("[TangtuEconomical] 签发提单 No." + serial + " " + sp.getScoreboardName()
                    + " " + payload.itemId() + " x" + want);
        });
    }

    private static void handleReqMyBills(ReqMyBills payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            java.util.List<String> list = BillRegistry.listOf(sp.getScoreboardName());
            StringBuilder sb = new StringBuilder();
            if (list.isEmpty()) {
                sb.append("（还没有签发的提单）");
            } else {
                for (int i = 0; i < list.size() && i < 6; i++) {
                    if (i > 0) {
                        sb.append('\n');
                    }
                    sb.append(list.get(i));
                }
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new MyBillsReply(sb.toString()));
        });
    }

    private static void handleMyBillsReply(MyBillsReply payload, IPayloadContext context) {
        context.enqueueWork(() -> com.tangtu.kjbb.net.ClientBillBridge.accept(payload.body()));
    }

    private static void handleOpenBill(OpenBill payload, IPayloadContext context) {
        context.enqueueWork(() -> com.tangtu.kjbb.net.ClientBillBridge.openBill(
                payload.itemId(), payload.count(), payload.issuer(),
                payload.serial(), payload.expiresAt(), payload.status(), payload.address()));
    }

    private static void handleRedeem(RedeemBill payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            String res = BillRegistry.redeem(payload.serial(), sp, sp.level().getGameTime());
            sp.displayClientMessage(Component.literal(res), false);
            if (res.startsWith("§a")) {
                // 从背包里扣掉这张票
                for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
                    ItemStack st = sp.getInventory().getItem(i);
                    if (st == null || st.isEmpty()) {
                        continue;
                    }
                    BillData d = st.get(KjbbItems.BILL_DATA.get());
                    if (d != null && d.serial() == payload.serial()) {
                        st.shrink(1);
                        break;
                    }
                }
            }
        });
    }

    /** 服务端：把提单数据发给客户端并打开界面 */
    public static void sendOpenBill(ServerPlayer sp, BillData data) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                new OpenBill(data.itemId(), data.count(), data.issuer(),
                        data.serial(), data.expiresAt(), data.status(),
                        data.address() == null ? "" : data.address()));
    }

    public static void sendRedeem(long serial) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new RedeemBill(serial));
    }

    private static void handleOpenPaper(OpenPaper payload, IPayloadContext context) {
        context.enqueueWork(() -> com.tangtu.kjbb.net.ClientBillBridge.openPaper());
    }

    /** 服务端：通知客户端打开纸质界面 */
    private static void handleReqPaper(ReqPaper p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) {
                sendOpenPaper(sp);
            }
        });
    }

    /** 客户端：请求打开票据界面 */
    public static void requestPaper() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqPaper());
    }

    public static void sendOpenPaper(ServerPlayer sp) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new OpenPaper());
    }

    public static void sendMyBills() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ReqMyBills());
    }

    public static void sendIssue(String itemId, int count) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new IssueBill(itemId, count));
    }
}
