package com.tangtu.kjbb.net;

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
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仓单：拿空白单据右键箱子/保险库 → 读取容器内容 → 在单子上列出 → 开仓单。
 *
 * 货不搬走，仓单只是凭证；用 StoreClaims 记录已开单数量防止重复开单。
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class StoreFormNetwork {

    private StoreFormNetwork() {
    }

    /** 服务端 → 客户端：打开仓单界面（body 每行 itemId|count） */
    public record OpenStoreForm(int x, int y, int z, String body, String diag)
            implements CustomPacketPayload {
        public static final Type<OpenStoreForm> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "open_store_form"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenStoreForm> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, OpenStoreForm::x,
                        ByteBufCodecs.VAR_INT, OpenStoreForm::y,
                        ByteBufCodecs.VAR_INT, OpenStoreForm::z,
                        com.tangtu.kjbb.net.ChunkedString.CODEC, OpenStoreForm::body,
                        ByteBufCodecs.STRING_UTF8, OpenStoreForm::diag,
                        OpenStoreForm::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：给这个容器开具一张仓单（记录内容） */
    public record IssueReceipt(int x, int y, int z, boolean bearer, String itemId, int count)
            implements CustomPacketPayload {
        public static final Type<IssueReceipt> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "issue_receipt"));
        public static final StreamCodec<RegistryFriendlyByteBuf, IssueReceipt> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, IssueReceipt::x,
                        ByteBufCodecs.VAR_INT, IssueReceipt::y,
                        ByteBufCodecs.VAR_INT, IssueReceipt::z,
                        ByteBufCodecs.BOOL, IssueReceipt::bearer,
                        ByteBufCodecs.STRING_UTF8, IssueReceipt::itemId,
                        ByteBufCodecs.VAR_INT, IssueReceipt::count,
                        IssueReceipt::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：上锁/解锁这个容器 */
    public record ToggleLock(int x, int y, int z, boolean lock) implements CustomPacketPayload {
        public static final Type<ToggleLock> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "toggle_lock"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToggleLock> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ToggleLock::x,
                        ByteBufCodecs.VAR_INT, ToggleLock::y,
                        ByteBufCodecs.VAR_INT, ToggleLock::z,
                        ByteBufCodecs.BOOL, ToggleLock::lock,
                        ToggleLock::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：兑付这张仓单 */
    public record RedeemReceipt(int x, int y, int z, long rid) implements CustomPacketPayload {
        public static final Type<RedeemReceipt> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "redeem_receipt"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RedeemReceipt> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, RedeemReceipt::x,
                        ByteBufCodecs.VAR_INT, RedeemReceipt::y,
                        ByteBufCodecs.VAR_INT, RedeemReceipt::z,
                        ByteBufCodecs.VAR_LONG, RedeemReceipt::rid,
                        RedeemReceipt::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：当前锁定状态 */
    public record LockState(boolean locked) implements CustomPacketPayload {
        public static final Type<LockState> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "lock_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LockState> CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, LockState::locked, LockState::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：查看仓单内容 */
    public record ReceiptView(String pos, String owner, String body, long rid) implements CustomPacketPayload {
        public static final Type<ReceiptView> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "receipt_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReceiptView> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, ReceiptView::pos,
                        ByteBufCodecs.STRING_UTF8, ReceiptView::owner,
                        com.tangtu.kjbb.net.ChunkedString.CODEC, ReceiptView::body,
                        ByteBufCodecs.VAR_LONG, ReceiptView::rid,
                        ReceiptView::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(OpenStoreForm.TYPE, OpenStoreForm.CODEC, StoreFormNetwork::handleOpen)
                .playToServer(IssueReceipt.TYPE, IssueReceipt.CODEC, StoreFormNetwork::handleIssue)
                .playToClient(ReceiptView.TYPE, ReceiptView.CODEC, StoreFormNetwork::handleView)
                .playToServer(RedeemReceipt.TYPE, RedeemReceipt.CODEC,
                        StoreFormNetwork::handleRedeemReceipt)
                .playToServer(ToggleLock.TYPE, ToggleLock.CODEC, StoreFormNetwork::handleToggleLock)
                .playToClient(LockState.TYPE, LockState.CODEC, StoreFormNetwork::handleLockState);
    }

    private static void handleOpen(OpenStoreForm p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientStoreBridge.open(p.x(), p.y(), p.z(), p.body(), p.diag()));
    }

    private static void handleRedeemReceipt(RedeemReceipt p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            String msg = com.tangtu.kjbb.TicketActions.redeem(sp,
                    new BlockPos(p.x(), p.y(), p.z()), p.rid());
            sp.displayClientMessage(Component.literal(msg), false);
            sp.containerMenu.broadcastChanges();
        });
    }

    private static void handleLockState(LockState p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientStoreBridge.lockState(p.locked()));
    }

    private static void handleToggleLock(ToggleLock p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            boolean want = p.lock();
            boolean holds = com.tangtu.kjbb.StoreLocks.playerHoldsReceipt(sp, pos);
            var row = com.tangtu.kjbb.StoreLocks.get(pos);
            boolean isOwner = row != null && row.owner() != null
                    && row.owner().equalsIgnoreCase(sp.getScoreboardName());
            if (!holds && !isOwner) {
                sp.displayClientMessage(Component.literal("§c你手里没有这个容器的仓单"), false);
                return;
            }
            if (want) {
                boolean bearerNow = row != null && row.bearer();
                  com.tangtu.kjbb.StoreLocks.lock(pos, sp.getScoreboardName(), bearerNow);
                sp.displayClientMessage(Component.literal("§a已上锁：这个容器只有仓单持有者能打开"),
                        false);
            } else {
                com.tangtu.kjbb.StoreLocks.unlock(pos);
                sp.displayClientMessage(Component.literal("§e已解锁：任何人都能打开（自动化也恢复）"),
                        false);
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new LockState(want));
            System.out.println("[TangtuEconomical] 仓单按钮 " + (want ? "上锁" : "解锁") + " " + pos
                    + " by " + sp.getScoreboardName());
        });
    }

    private static void handleView(ReceiptView p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientStoreBridge.view(p.pos(), p.owner(), p.body(), p.rid()));
    }

    /** 服务端：把仓单内容发给客户端查看 */
    public static void sendReceiptView(ServerPlayer sp, ItemStack stack) {
        StringBuilder sb = new StringBuilder();
        for (var l : com.tangtu.kjbb.item.StoreReceiptItem.readRecord(stack)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(l.itemId()).append('|').append(l.count());
        }
        String posTag = com.tangtu.kjbb.item.StoreReceiptItem.posText(stack)
                + (com.tangtu.kjbb.item.StoreReceiptItem.isBearer(stack) ? "#B" : "#R");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                new ReceiptView(posTag,
                        com.tangtu.kjbb.item.StoreReceiptItem.ownerText(stack), sb.toString(), com.tangtu.kjbb.item.StoreReceiptItem.rid(stack)));
        // 附带当前锁定状态
        String ptxt = com.tangtu.kjbb.item.StoreReceiptItem.posText(stack);
        boolean lockedNow = false;
        try {
            String[] xyz = ptxt.split(",");
            BlockPos p0 = new BlockPos(Integer.parseInt(xyz[0].trim()),
                    Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
            var lr = com.tangtu.kjbb.StoreLocks.get(p0);
            lockedNow = lr != null && lr.locked();
        } catch (Throwable ignored) {
            // 忽略
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new LockState(lockedNow));
    }

    /** 服务端：读容器内容并发给客户端 */
    public static void sendOpen(ServerPlayer sp, ServerLevel level, BlockPos pos) {
        Map<String, Integer> counts = readCounts(level, pos);
        IItemHandler h = handler(level, pos);
        if (h == null && counts.isEmpty()) {
            sp.displayClientMessage(Component.literal("§c这里不是容器"), false);
            System.out.println("[TangtuEconomical] 仓单: 容器读不到 @ " + pos);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (var e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('|').append(e.getValue());
        }
        System.out.println("[TangtuEconomical] 打开仓单界面 " + pos + " 物品种类=" + counts.size());
        String diag = "?";
        try {
            var be0 = level.getBlockEntity(pos);
            int slots = 0;
            IItemHandler hh = handler(level, pos);
            if (hh != null) {
                slots = hh.getSlots();
            }
            String kind = "容器";
            if (be0 != null) {
                String cn = be0.getClass().getSimpleName();
                if (cn.contains("Chest")) {
                    kind = "箱子";
                } else if (cn.contains("Barrel")) {
                    kind = "木桶";
                } else if (cn.contains("Shulker")) {
                    kind = "潜影盒";
                } else if (cn.contains("Hopper")) {
                    kind = "漏斗";
                } else if (cn.contains("Dispenser")) {
                    kind = "发射器";
                } else if (cn.contains("Dropper")) {
                    kind = "投掷器";
                } else if (cn.contains("Vault")) {
                    kind = "保险库";
                } else if (cn.length() > 10) {
                    kind = cn.substring(0, 10);
                } else {
                    kind = cn;
                }
            }
            diag = kind + " " + slots + "槽 · " + counts.size() + "种";
        } catch (Throwable ignored) {
            // 忽略
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                new OpenStoreForm(pos.getX(), pos.getY(), pos.getZ(), sb.toString(), diag));
    }

    private static void handleIssue(IssueReceipt p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (!(sp.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            if (!level.isLoaded(pos)) {
                return;
            }
            boolean bearer = p.bearer();
            // 先关掉玩家当前开着的容器界面，再等它真正关闭后才动数据
            try {
                sp.closeContainer();
            } catch (Throwable ignored) {
                // 忽略
            }
            whenContainerClosed(sp, () -> doIssue(sp, level, pos, bearer, p.itemId(), p.count()), 40);
        });
    }

    /** 等玩家的容器界面关闭之后再执行（最多等 tries 个 tick） */
    private static void whenContainerClosed(ServerPlayer sp, Runnable action, int tries) {
        if (tries <= 0 || sp.containerMenu == sp.inventoryMenu) {
            action.run();
            return;
        }
        sp.server.execute(() -> whenContainerClosed(sp, action, tries - 1));
    }

    private static void doIssue(ServerPlayer sp, ServerLevel level, BlockPos pos, boolean bearer,
                                String itemId, int count) {
        // 已经开过仓单的容器 → 拒绝，避免覆盖封条
        String existingSeal = com.tangtu.kjbb.StoreSeals.get(pos);
        if (existingSeal != null && !existingSeal.isEmpty()) {
            var lr = com.tangtu.kjbb.StoreLocks.get(pos);
            sp.displayClientMessage(Component.literal(
                    "§c这个容器已经开过仓单了（持有人：" + (lr == null ? "?" : lr.owner()) + "）"),
                    false);
            sp.displayClientMessage(Component.literal(
                    "§7要重新开单，先拿着旧仓单执行 /billreceipt void 作废"), false);
            return;
        }
        Map<String, Integer> counts = readCounts(level, pos);
        // 整箱开单：票面 = 容器全部内容（严格对应）
        if (counts.isEmpty()) {
            sp.displayClientMessage(Component.literal("§c容器是空的，没什么可记录的"), false);
            return;
        }
        ItemStack receipt = com.tangtu.kjbb.item.StoreReceiptItem.create(
                pos, sp.getScoreboardName(), level.getGameTime(), counts);
        com.tangtu.kjbb.item.StoreReceiptItem.setBearer(receipt, bearer);
        if (!sp.getInventory().add(receipt)) {
            sp.drop(receipt, false);
        }
        for (var e : counts.entrySet()) {
            com.tangtu.kjbb.StoreClaims.add(pos, e.getKey(), e.getValue());
        }
        com.tangtu.kjbb.StoreLocks.lock(pos, sp.getScoreboardName(), bearer);
        // 封条必须盖【整个容器】，不能只盖票面那一种物品
        com.tangtu.kjbb.StoreSeals.set(pos, com.tangtu.kjbb.StoreSeals.sealOf(
                com.tangtu.kjbb.StoreSeals.snapshot(level, pos)));
        com.tangtu.kjbb.item.StoreReceiptItem.setDim(receipt,
                level.dimension().location().toString());
        sp.displayClientMessage(Component.literal("§a[仓单] 已记录这个容器："
                + counts.size() + " 种物品，共 "
                + counts.values().stream().mapToInt(Integer::intValue).sum() + " 件"), false);
        sp.displayClientMessage(Component.literal("§7位置 " + pos.getX() + ", " + pos.getY()
                + ", " + pos.getZ() + "　" + (bearer
                        ? "不记名"
                        : "记名")), false);
        sp.displayClientMessage(Component.literal("§7封条已盖：之后内容一变，仓单自动作废"), false);
        System.out.println("[TangtuEconomical] 开具仓单 " + pos + " 种类=" + counts.size()
                + " bearer=" + bearer);
    }

    /** 读取容器内容：原版容器直接走 Container 接口，其它模组走能力 */
    public static Map<String, Integer> readCounts(ServerLevel level, BlockPos pos) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        var be = level.getBlockEntity(pos);
        if (be == null) {
            return counts;
        }
        if (be instanceof net.minecraft.world.Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                ResourceLocation rl = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (rl == null) {
                    continue;
                }
                counts.merge(rl.toString(), st.getCount(), Integer::sum);
            }
            if (!counts.isEmpty()) {
                return counts;
            }
        }
        var h = handler(level, pos);
        if (h == null) {
            return counts;
        }
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack st = h.getStackInSlot(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (rl == null) {
                continue;
            }
            counts.merge(rl.toString(), st.getCount(), Integer::sum);
        }
        return counts;
    }

    private static IItemHandler handler(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        var cap = Capabilities.ItemHandler.BLOCK;
        IItemHandler h = cap.getCapability(level, pos, be.getBlockState(), be, null);
        if (h != null) {
            return h;
        }
        for (var d : net.minecraft.core.Direction.values()) {
            h = cap.getCapability(level, pos, be.getBlockState(), be, d);
            if (h != null) {
                return h;
            }
        }
        return null;
    }
}
