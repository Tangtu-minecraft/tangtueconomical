package com.tangtu.kjbb.net;

import com.tangtu.kjbb.BankBridge;
import com.tangtu.kjbb.ShopPricing;
import com.tangtu.kjbb.TickerStock;
import com.tangtu.kjbb.item.BillData;
import com.tangtu.kjbb.item.BillRegistry;
import com.tangtu.kjbb.item.KjbbItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;

/** 空白单据 × 仓储发报机 联动 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class TickerBillNetwork {

    private TickerBillNetwork() {
    }

    // ---------------------------------------------------------------- 载荷

    /** 服务端 → 客户端：打开「发报机」票据界面 */
    public record OpenTicker(int x, int y, int z) implements CustomPacketPayload {
        public static final Type<OpenTicker> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_ticker"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenTicker> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, OpenTicker::x,
                        ByteBufCodecs.VAR_INT, OpenTicker::y,
                        ByteBufCodecs.VAR_INT, OpenTicker::z,
                        OpenTicker::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：请求仓库货品清单 */
    public record ReqStore(int x, int y, int z) implements CustomPacketPayload {
        public static final Type<ReqStore> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "req_store"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReqStore> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ReqStore::x,
                        ByteBufCodecs.VAR_INT, ReqStore::y,
                        ByteBufCodecs.VAR_INT, ReqStore::z,
                        ReqStore::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：货品清单 body 每行 itemId|count|unitPrice */
    public record StoreReply(String body) implements CustomPacketPayload {
        public static final Type<StoreReply> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "store_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StoreReply> CODEC =
                StreamCodec.composite(
                        com.tangtu.kjbb.net.ChunkedString.CODEC, StoreReply::body,
                        StoreReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：从仓库买货并出票 */
    public record BuyBill(int x, int y, int z, String itemId, int count) implements CustomPacketPayload {
        public static final Type<BuyBill> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "buy_bill"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BuyBill> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, BuyBill::x,
                        ByteBufCodecs.VAR_INT, BuyBill::y,
                        ByteBufCodecs.VAR_INT, BuyBill::z,
                        ByteBufCodecs.STRING_UTF8, BuyBill::itemId,
                        ByteBufCodecs.VAR_INT, BuyBill::count,
                        BuyBill::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：把我的货物存进仓库并出票 */
    public record DepositBill(int x, int y, int z, String itemId, int count) implements CustomPacketPayload {
        public static final Type<DepositBill> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "deposit_bill"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DepositBill> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, DepositBill::x,
                        ByteBufCodecs.VAR_INT, DepositBill::y,
                        ByteBufCodecs.VAR_INT, DepositBill::z,
                        ByteBufCodecs.STRING_UTF8, DepositBill::itemId,
                        ByteBufCodecs.VAR_INT, DepositBill::count,
                        DepositBill::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- 注册

    /** 服务端 → 客户端：我的待发货订单（id|物品|数量） */
    public record ShipmentsReply(String body) implements CustomPacketPayload {
        public static final Type<ShipmentsReply> TYPE =
                new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kjbankbridge", "shipments"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ShipmentsReply> CODEC =
                StreamCodec.composite(com.tangtu.kjbb.net.ChunkedString.CODEC, ShipmentsReply::body, ShipmentsReply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：从发报机发货某张订单 */
    public record ShipOrder(int x, int y, int z, long id) implements CustomPacketPayload {
        public static final Type<ShipOrder> TYPE =
                new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kjbankbridge", "ship_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ShipOrder> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ShipOrder::x,
                        ByteBufCodecs.VAR_INT, ShipOrder::y,
                        ByteBufCodecs.VAR_INT, ShipOrder::z,
                        ByteBufCodecs.VAR_LONG, ShipOrder::id,
                        ShipOrder::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(OpenTicker.TYPE, OpenTicker.CODEC, TickerBillNetwork::handleOpen)
                .playToServer(ReqStore.TYPE, ReqStore.CODEC, TickerBillNetwork::handleReqStore)
                .playToClient(StoreReply.TYPE, StoreReply.CODEC, TickerBillNetwork::handleStoreReply)
                .playToServer(BuyBill.TYPE, BuyBill.CODEC, TickerBillNetwork::handleBuy)
                .playToServer(DepositBill.TYPE, DepositBill.CODEC, TickerBillNetwork::handleDeposit);
    }

    // ---------------------------------------------------------------- 处理

    private static void handleOpen(OpenTicker p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientTickerBridge.open(
                new BlockPos(p.x(), p.y(), p.z())));
    }

    private static void handleStoreReply(StoreReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientTickerBridge.accept(p.body()));
    }

    private static void handleReqStore(ReqStore p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (!(sp.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            Map<String, Integer> stock = TickerStock.stock(level, pos);
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (var e : stock.entrySet()) {
                long unit = ShopPricing.price(e.getKey(), e.getValue());
                if (unit <= 0L) {
                    continue;
                }
                if (!first) {
                    sb.append('\n');
                }
                sb.append(e.getKey()).append('|').append(e.getValue()).append('|').append(unit);
                first = false;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new StoreReply(sb.toString()));
            sendShipments(sp);
        });
    }

    private static void sendShipments(ServerPlayer sp) {
        StringBuilder sb = new StringBuilder();
        try {
            for (var r : com.tangtu.kjbb.OrderBook.byStatus(com.tangtu.kjbb.OrderBook.ACCEPTED)) {
                if (r.seller == null || !r.seller.equals(sp.getScoreboardName())) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(r.id).append('|').append(r.itemId).append('|').append(r.count);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        System.out.println("[TangtuEconomical] 待发货发送 player=" + sp.getScoreboardName()
                + " 条带=" + (sb.length() == 0 ? "(空)" : sb.toString().replace('\n', ';')));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new ShipmentsReply(sb.toString()));
    }

    private static void handleShipments(ShipmentsReply p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.tangtu.kjbb.net.ClientTickerBridge.acceptShipments(p.body()));
    }

    private static void handleShipOrder(ShipOrder p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (!(sp.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            var row = com.tangtu.kjbb.OrderBook.get(p.id());
            if (row == null || !com.tangtu.kjbb.OrderBook.ACCEPTED.equals(row.status)
                    || row.seller == null || !row.seller.equals(sp.getScoreboardName())) {
                sp.displayClientMessage(Component.literal("\u00a7c\u8fd9\u5f20\u5355\u4e0d\u662f\u4f60\u9501\u5b9a\u7684"), false);
                return;
            }
            // 仓库里要有对应货
            Map<String, Integer> stock = TickerStock.stock(level, pos);
            int have = stock.getOrDefault(row.itemId, 0);
            if (have < row.count) {
                sp.displayClientMessage(Component.literal("\u00a7c\u4ed3\u5e93\u91cc \u53ea\u6709 "
                        + have + " / " + row.count), false);
                return;
            }
            // 扣仓库货 + 履约
            Item shipItem = BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.tryParse(row.itemId));
            if (shipItem == null) {
                sp.displayClientMessage(Component.literal("\u00a7c\u7269\u54c1\u4e0d\u5b58\u5728"), false);
                return;
            }
            TickerStock.extract(level, pos, shipItem, row.count);
            String target = row.address == null || row.address.isEmpty() ? row.buyer : row.address;
            boolean ok = false;
            try {
                ok = com.tangtu.kjbb.TickerShip.request(level, pos, row.itemId, row.count, target);
            } catch (Throwable ignored) {
                ok = false;
            }
            if (!ok) {
                TickerStock.insert(level, pos, new ItemStack(shipItem, row.count));
                sp.displayClientMessage(Component.literal("\u00a7c\u53d1\u8d27\u5931\u8d25\uff08\u53d1\u62a5\u673a\u4e0d\u53ef\u7528\uff09"), false);
                return;
            }
            com.tangtu.kjbb.OrderBook.setStatus(row.id, com.tangtu.kjbb.OrderBook.SUPPLIED,
                    sp.getScoreboardName());
            com.tangtu.kjbb.OrderWatch.add(row.id, row.buyer, row.itemId, row.count, target);
            sp.displayClientMessage(Component.literal("\u00a7a[\u53d1\u8d27] \u8ba2\u5355 #"
                    + row.id + " \u5df2\u53d1\u51fa"), false);
            sendShipments(sp);
        });
    }

    private static void handleBuy(BuyBill p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (!(sp.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            int want = Math.max(1, p.count());
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(p.itemId()));
            if (item == null) {
                sp.displayClientMessage(Component.literal("§c物品不存在"), false);
                return;
            }
            Map<String, Integer> stock = TickerStock.stock(level, pos);
            int have = stock.getOrDefault(p.itemId(), 0);
            if (have < want) {
                sp.displayClientMessage(Component.literal("§c仓库里只剩 " + have + " 个"), false);
                return;
            }
            long unit = ShopPricing.price(p.itemId(), have);
            long total = unit * want;
            String name = sp.getScoreboardName();
            if (BankBridge.money(name) < total) {
                sp.displayClientMessage(Component.literal(
                        "§c余额不足：需要 " + total + " 唐兔币，你有 " + BankBridge.money(name) + " 唐兔币"), false);
                return;
            }
            // ★ 票 = 预付提货凭证：只扣钱，货仍留在仓库里
            long left = BankBridge.takeMoney(name, total);
            giveBill(sp, p.itemId(), want, level.getGameTime(), pos);
            sp.displayClientMessage(Component.literal("§a已预付 " + total
                    + " 唐兔币（单价 " + unit + "），提单已生成；货物仍存在仓库，凭票随时提货"), false);
        });
    }

    private static void handleDeposit(DepositBill p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (!(sp.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            int want = Math.max(1, p.count());
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(p.itemId()));
            if (item == null) {
                sp.displayClientMessage(Component.literal("§c物品不存在"), false);
                return;
            }
            // 从背包扣货
            int have = 0;
            var inv = sp.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st != null && !st.isEmpty() && st.getItem() == item) {
                    have += st.getCount();
                }
            }
            if (have < want) {
                sp.displayClientMessage(Component.literal("§c货物不足：你只有 " + have + " 个"), false);
                return;
            }
            int left = want;
            for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                ItemStack st = inv.getItem(i);
                if (st != null && !st.isEmpty() && st.getItem() == item) {
                    int take = Math.min(st.getCount(), left);
                    st.shrink(take);
                    left -= take;
                }
            }
            // 塞进仓库
            ItemStack toStore = new ItemStack(item, want);
            int notStored = TickerStock.insert(level, pos, toStore);
            int stored = want - notStored;
            if (stored <= 0) {
                // 全塞不进去，退回背包
                giveBack(sp, item, want);
                sp.displayClientMessage(Component.literal("§c仓库放不下（需要容器）"), false);
                return;
            }
            giveBill(sp, p.itemId(), stored, level.getGameTime(), pos);
            // 存进去的货如果正好能履约自己接受的订单 → 直接寄给买家
            try {
                com.tangtu.kjbb.OrderFulfill.tryFulfill(sp, level, pos, p.itemId(), stored);
            } catch (Throwable ignored) {
                // ignore
            }
            sp.displayClientMessage(Component.literal("§a已存入仓库 " + stored + " 个，提单已生成"
                    + (notStored > 0 ? "（" + notStored + " 个退回背包）" : "")), false);
            if (notStored > 0) {
                giveBack(sp, item, notStored);
            }
        });
    }

    // ---------------------------------------------------------------- 工具

    private static void giveBill(ServerPlayer sp, String itemId, int count, long now,
                                 net.minecraft.core.BlockPos store) {
        long expiresAt = now + BillData.LIFETIME_TICKS;
        int sx = store == null ? 0 : store.getX();
        int sy = store == null ? 0 : store.getY();
        int sz = store == null ? 0 : store.getZ();
        String addr = com.tangtu.kjbb.AddressBook.addressOf(sp);
        long serial = BillRegistry.register(itemId, count, sp.getScoreboardName(), expiresAt,
                sx, sy, sz, addr);
        ItemStack bill = new ItemStack(KjbbItems.BILL.get());
        KjbbItems.setBill(bill, new BillData(itemId, count, sp.getScoreboardName(),
                serial, BillData.STATUS_ISSUED, expiresAt, sx, sy, sz, addr));
        if (!sp.getInventory().add(bill)) {
            sp.drop(bill, false);
        }
    }

    /**
     * 把「提单」寄给买家（货物仍留在卖家发报机仓库里，买家凭票提货）
     *
     * @return 寄出成功与否
     */
    /**
     * 发货：提单直接进买家背包，货物走物流寄到买家邮箱
     *
     * @return 寄出成功与否（提单给了 + 货已发出）
     */
    public static boolean mailBillTo(ServerLevel level, ServerPlayer seller,
                                     BlockPos store, String itemId, int count,
                                     String buyerName, String buyerAddr, long orderId) {
        if (level == null || store == null) {
            return false;
        }
        try {
            long expiresAt = level.getGameTime() + com.tangtu.kjbb.item.BillData.LIFETIME_TICKS;
            String addr = buyerAddr == null ? "" : buyerAddr;
            long serial = com.tangtu.kjbb.item.BillRegistry.register(itemId, count,
                    seller.getScoreboardName(), expiresAt,
                    store.getX(), store.getY(), store.getZ(), addr);
            ItemStack bill = new ItemStack(KjbbItems.BILL.get());
            KjbbItems.setBill(bill, new com.tangtu.kjbb.item.BillData(itemId, count,
                    seller.getScoreboardName(), serial,
                    com.tangtu.kjbb.item.BillData.STATUS_ISSUED, expiresAt,
                    store.getX(), store.getY(), store.getZ(), addr));
            com.tangtu.kjbb.OrderBillLink.put(serial, orderId);

            // ① 提单直接给买家
            ServerPlayer buyer = buyerName == null ? null
                    : level.getServer().getPlayerList().getPlayerByName(buyerName);
            boolean gave;
            if (buyer != null) {
                gave = buyer.getInventory().add(bill);
                if (!gave) {
                    buyer.drop(bill, false);
                    gave = true;
                }
                buyer.displayClientMessage(Component.literal(
                        "\u00a7e[\u63d0\u5355] \u8ba2\u5355 #" + orderId
                                + " \u7684\u63d0\u5355\u5df2\u5230\u4f60\u624b\u4e0a\uff0c"
                                + "\u8d27\u7269\u6b63\u5728\u7269\u6d41\u9014\u4e2d"), false);
            } else {
                // 买家不在线 → 寄到提货点
                java.util.List<ItemStack> goods = new java.util.ArrayList<>();
                goods.add(bill);
                gave = com.tangtu.kjbb.AddressBook.deliver(level, seller.blockPosition(), addr, goods);
            }

            // ② 货物走物流寄到买家提货点
            boolean shipped = false;
            try {
                shipped = com.tangtu.kjbb.TickerShip.request(level, store, itemId, count, addr);
            } catch (Throwable ignored) {
                shipped = false;
            }
            System.out.println("[TangtuEconomical] 发货 #" + orderId + " buyer=" + buyerName
                    + " 提单=" + gave + " 物流=" + shipped + " addr=" + addr
                    + " " + itemId + " x" + count);
            return gave;
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 发货失败: " + t);
            return false;
        }
    }

    private static void giveBack(ServerPlayer sp, Item item, int count) {
        int left = count;
        while (left > 0) {
            int n = Math.min(left, Math.max(1, item.getDefaultMaxStackSize()));
            ItemStack st = new ItemStack(item, n);
            if (!sp.getInventory().add(st)) {
                sp.drop(st, false);
            }
            left -= n;
        }
    }

    public static void open(ServerPlayer sp, BlockPos pos) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                new OpenTicker(pos.getX(), pos.getY(), pos.getZ()));
    }

    public static void requestStore(BlockPos pos) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ReqStore(pos.getX(), pos.getY(), pos.getZ()));
    }

    public static void buy(BlockPos pos, String itemId, int count) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new BuyBill(pos.getX(), pos.getY(), pos.getZ(), itemId, count));
    }

    public static void deposit(BlockPos pos, String itemId, int count) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new DepositBill(pos.getX(), pos.getY(), pos.getZ(), itemId, count));
    }
}
