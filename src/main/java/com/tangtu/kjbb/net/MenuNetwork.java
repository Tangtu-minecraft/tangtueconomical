package com.tangtu.kjbb.net;

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

/** 经济系统主菜单：打开界面 + 按钮触发的服务端动作 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class MenuNetwork {

    private MenuNetwork() {
    }

    /** S→C：打开主菜单 */
    public record OpenMenu() implements CustomPacketPayload {
        public static final Type<OpenMenu> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_menu"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenMenu> CODEC =
                StreamCodec.unit(new OpenMenu());
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S：请求银行余额 */
    public record ReqBank() implements CustomPacketPayload {
        public static final Type<ReqBank> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_bank"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqBank> CODEC =
                StreamCodec.unit(new ReqBank());
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S→C：银行余额 */
    public record BankInfo(int wallet, int coins, int safe, int total) implements CustomPacketPayload {
        public static final Type<BankInfo> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "bank_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BankInfo> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BankInfo::wallet,
                ByteBufCodecs.VAR_INT, BankInfo::coins,
                ByteBufCodecs.VAR_INT, BankInfo::safe,
                ByteBufCodecs.VAR_INT, BankInfo::total,
                BankInfo::new);
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S：执行一条命令（以玩家身份） */
    public record Action(String command) implements CustomPacketPayload {
        public static final Type<Action> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "menu_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Action::command, Action::new);
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void open(ServerPlayer sp) {
        PacketDistributor.sendToPlayer(sp, new OpenMenu());
    }

    /** 打开银行界面（并推一次余额） */
    public static void openBank(ServerPlayer sp) {
        PacketDistributor.sendToPlayer(sp, new OpenBank());
        pushBank(sp);
    }

    /** 推送余额 */
    public static void pushBank(ServerPlayer sp) {
        long wallet = com.tangtu.kjbb.Wallet.money(sp.getScoreboardName());
        long coins = com.tangtu.kjbb.Coins.inventoryValue(sp);
        long safe = com.tangtu.kjbb.Coins.safeBalance(sp);
        long total = wallet + coins + safe;
        PacketDistributor.sendToPlayer(sp, new BankInfo((int) wallet, (int) coins,
                (int) safe, (int) total));
    }

    /** S→C：打开银行界面 */
    public record OpenBank() implements CustomPacketPayload {
        public static final Type<OpenBank> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_bank"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenBank> CODEC =
                StreamCodec.unit(new OpenBank());
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：打开国民统计报表界面 */
    /** 服务端 → 客户端：打开票据挂卖小窗 */
    public record OpenSell(int slot) implements CustomPacketPayload {
        public static final Type<OpenSell> TYPE =
                new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "kjbankbridge", "open_sell"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenSell> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, OpenSell::slot, OpenSell::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 公民报告：text / items 都可能超过 32767 字符上限，按 {@link Chunk} 分片发。
     * 两个字段各切各的，客户端用两个收包缓冲分别拼。
     */
    public record OpenReport(int seq, int parts, String text, String items)
            implements CustomPacketPayload {
        public static final Type<OpenReport> TYPE =
                new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_report"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenReport> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, OpenReport::seq,
                        ByteBufCodecs.VAR_INT, OpenReport::parts,
                        ByteBufCodecs.STRING_UTF8, OpenReport::text,
                        ByteBufCodecs.STRING_UTF8, OpenReport::items,
                        OpenReport::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final Chunk.Inbox REPORT_TEXT_INBOX = new Chunk.Inbox();
    private static final Chunk.Inbox REPORT_ITEMS_INBOX = new Chunk.Inbox();

    /** 发公民报告（自动分片） */
    public static void sendOpenReport(net.minecraft.server.level.ServerPlayer sp,
                                      String text, String items) {
        String t = text == null ? "" : text;
        String it = items == null ? "" : items;
        int parts = Math.max(Chunk.parts(t), Chunk.parts(it));
        for (int i = 0; i < parts; i++) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new OpenReport(i, parts, Chunk.slice(t, i), Chunk.slice(it, i)));
        }
    }

    /** C→S：请求远洋提单数据 */
    public record VoyageReq() implements CustomPacketPayload {
        public static final Type<VoyageReq> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "voyage_req"));
        public static final StreamCodec<RegistryFriendlyByteBuf, VoyageReq> CODEC =
                StreamCodec.unit(new VoyageReq());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S→C：远洋提单数据（每行 B|… 或 L|…） */
    public record VoyageInfo(String data) implements CustomPacketPayload {
        public static final Type<VoyageInfo> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "voyage_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, VoyageInfo> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, VoyageInfo::data,
                        VoyageInfo::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

        /** S→C：打开某张股票的详情界面 */
    public record OpenStock(long billId) implements CustomPacketPayload {
        public static final Type<OpenStock> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_stock"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenStock> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG, OpenStock::billId,
                        OpenStock::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(OpenMenu.TYPE, OpenMenu.CODEC, MenuNetwork::handleOpen);
        r.playToServer(Action.TYPE, Action.CODEC, MenuNetwork::handleAction);
        r.playToClient(OpenBank.TYPE, OpenBank.CODEC, MenuNetwork::handleOpenBank);
        r.playToServer(ReqBank.TYPE, ReqBank.CODEC, MenuNetwork::handleReqBank);
        r.playToClient(BankInfo.TYPE, BankInfo.CODEC, MenuNetwork::handleBankInfo);
        r.playToClient(OpenReport.TYPE, OpenReport.CODEC, MenuNetwork::handleOpenReport);
        r.playToClient(OpenSell.TYPE, OpenSell.CODEC, MenuNetwork::handleOpenSell);
        r.playToServer(VoyageReq.TYPE, VoyageReq.CODEC, MenuNetwork::handleVoyageReq);
        r.playToClient(VoyageInfo.TYPE, VoyageInfo.CODEC, MenuNetwork::handleVoyageInfo);
        r.playToClient(OpenStock.TYPE, OpenStock.CODEC, MenuNetwork::handleOpenStock);
    }

    private static void handleOpenSell(OpenSell p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientSellBridge.open(p.slot()));
    }

    /** 服务端调用：让玩家打开挂卖小窗 */
    public static void sendOpenSell(ServerPlayer sp, int slot) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new OpenSell(slot));
    }

    private static void handleOpenReport(OpenReport p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            String text = REPORT_TEXT_INBOX.feed(p.seq(), p.parts(), p.text());
            String items = REPORT_ITEMS_INBOX.feed(p.seq(), p.parts(), p.items());
            if (text != null && items != null) {
                ClientMenuOpen.openReport(text, items);
            }
        });
    }

    private static void handleOpenBank(OpenBank p, IPayloadContext ctx) {
        ctx.enqueueWork(ClientMenuOpen::openBank);
    }

    private static void handleReqBank(ReqBank p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) {
            ctx.enqueueWork(() -> pushBank(sp));
        }
    }

    private static void handleBankInfo(BankInfo p, IPayloadContext ctx) {
        com.tangtu.kjbb.client.MarketCache.setBank(p.wallet(), p.coins(), p.safe(), p.total());
        ctx.enqueueWork(com.tangtu.kjbb.client.BankScreen::refreshIfOpen);
    }

    private static void handleOpenStock(OpenStock p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientMenuOpen.openStock(p.billId()));
    }

    /** 服务端调用：让玩家打开股票详情 */
    public static void sendOpenStock(ServerPlayer sp, long billId) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new OpenStock(billId));
    }

    private static void handleVoyageInfo(VoyageInfo p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.client.MarketCache.setVoyage(p.data()));
    }

    private static void handleVoyageReq(VoyageReq p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        ctx.enqueueWork(() -> buildVoyageInfo(sp));
    }

    /** 组装远洋提单数据并发给客户端 */
    public static void buildVoyageInfo(ServerPlayer sp) {
        StringBuilder sb = new StringBuilder();
        String me = sp.getScoreboardName();
        for (com.tangtu.kjbb.VoyageBill.Bill b : com.tangtu.kjbb.VoyageBill.all()) {
            StringBuilder cargo = new StringBuilder();
            for (var e : b.need.entrySet()) {
                if (cargo.length() > 0) {
                    cargo.append(';');
                }
                cargo.append(e.getKey()).append('=')
                        .append(b.cargo.getOrDefault(e.getKey(), 0)).append('/')
                        .append(e.getValue());
            }
            sb.append("B|").append(b.id).append('|').append(b.name).append('|').append(b.status)
                    .append('|').append(b.sold).append('|').append(b.total)
                    .append('|').append(b.price).append('|').append(b.shareValue())
                    .append('|').append(b.cash).append('|').append(b.targetCash)
                    .append('|').append(b.deadline).append('|').append(b.dueDay)
                    .append('|').append(b.outcome).append('|').append(b.heldBy(me))
                    .append('|').append(b.issueDay).append('|').append(cargo)
                    .append('|').append(b.legs).append('|').append(b.legDone)
                    // 船况 / 倍率是内部参数，占位发 0，不给客户端真值
                    .append("|0|0")
                    .append('|').append(String.join(";", b.legLog))
                    .append('\n');
        }
        for (com.tangtu.kjbb.StockMarket.Row r : com.tangtu.kjbb.StockMarket.open()) {
            sb.append("S|").append(r.id).append('|').append(r.billId).append('|').append(r.shares)
                    .append('|').append(r.price).append('|').append(r.seller).append('\n');
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                new VoyageInfo(sb.toString()));
    }

    private static void handleOpen(OpenMenu p, IPayloadContext ctx) {
        ctx.enqueueWork(ClientMenuOpen::open);
    }

    private static void handleAction(Action p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        ctx.enqueueWork(() -> {
            try {
                var server = sp.getServer();
                if (server == null || p.command() == null) {
                    return;
                }
                server.getCommands().performPrefixedCommand(
                        sp.createCommandSourceStack(), p.command().trim());
            } catch (Throwable t) {
                sp.displayClientMessage(Component.literal("\u00a7c\u547d\u4ee4\u6267\u884c\u5931\u8d25\uff1a"
                        + t.getMessage()), false);
            }
        });
    }
}
