package com.tangtu.kjbb;

import com.tangtu.kjbb.item.KjbbItems;
import com.tangtu.kjbb.net.StoreFormNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 空白单据右键容器 → 打开仓单界面。
 *
 * 箱子/保险库自己会消费右键（打开 GUI），物品的 useOn 根本轮不到，
 * 所以必须在 RightClickBlock 事件里抢在方块之前处理。
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.GAME)
public final class StoreFormInteraction {

    private StoreFormInteraction() {
    }

    /** 封条校验：容器内容与仓单记录不一致 → 作废所有相关仓单 */
    private static void verifySeal(net.minecraft.server.level.ServerLevel level,
                                   ServerPlayer sp, BlockPos pos) {
        if (com.tangtu.kjbb.StoreSeals.get(pos) == null) {
            return;
        }
        if (com.tangtu.kjbb.StoreSeals.matches(level, pos)) {
            return;
        }
        com.tangtu.kjbb.StoreSeals.clear(pos);
        com.tangtu.kjbb.StoreLocks.unlock(pos);
        // 所有持有该容器仓单的人：票作废
        int n = 0;
        for (ServerPlayer p2 : level.getServer().getPlayerList().getPlayers()) {
            var inv = p2.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var st = inv.getItem(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                if (st.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
                    continue;
                }
                String want = pos.getX() + "," + pos.getY() + "," + pos.getZ();
                if (want.equals(com.tangtu.kjbb.item.StoreReceiptItem.posText(st))) {
                    com.tangtu.kjbb.item.StoreReceiptItem.setVoid(st);
                    n++;
                }
            }
            p2.containerMenu.broadcastChanges();
        }
        System.out.println("[TangtuEconomical] 封条失效 " + pos + " 作废仓单=" + n);
        sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§c[仓单] 这个容器的内容已被改动，相关仓单已作废（" + n + " 张）"), false);
    }

    /** 锁定容器：不是主人就拦下所有右键 */
    @SubscribeEvent
    public static void onLockedContainer(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return;   // 只处理主手，避免提示刷屏
        }
        // 手持空白单据 → 让行（打开只读的仓单记录界面，不算「开箱子」）
        ItemStack heldNow = sp.getItemInHand(event.getHand());
        if (heldNow != null && !heldNow.isEmpty()
                && heldNow.getItem() == com.tangtu.kjbb.item.KjbbItems.BLANK_FORM.get()) {
            return;
        }
        BlockPos pos = event.getPos();
        // 封条校验：内容被改过 → 仓单作废
        if (event.getLevel() instanceof ServerLevel lv) {
            verifySeal(lv, sp, pos);
        }
        // 手持该容器的仓单 = 持有者，直接放行并刷新锁归属
        if (StoreLocks.playerHoldsReceipt(sp, pos)) {
            var lockRow0 = StoreLocks.get(pos);
            if (lockRow0 == null || !lockRow0.locked()
                    || !lockRow0.owner().equalsIgnoreCase(sp.getScoreboardName())) {
                StoreLocks.lock(pos, sp.getScoreboardName());
                System.out.println("[TangtuEconomical] 凭仓单开门 " + pos + " → "
                        + sp.getScoreboardName());
            }
            return;
        }
        // 其他人即使有仓单存续也不能打开（持单人已在上面放行）
        // 有仓单存续（封条在）→ 谁都不能打开，只能通过「挂卖」转售或兑付
        // 锁的主人也放行（自己的仓库）
        var lockRowM = StoreLocks.get(pos);
        if (lockRowM != null && lockRowM.locked()
                && lockRowM.owner() != null
                && lockRowM.owner().equalsIgnoreCase(sp.getScoreboardName())) {
            return;
        }
        if (com.tangtu.kjbb.StoreSeals.get(pos) != null) {
            try {
                com.tangtu.kjbb.DebugLog.log("仓单拦截 " + sp.getScoreboardName() + " @ " + pos
                        + " 持单数=" + StoreLocks.countReceipts(sp, pos)
                        + " 锁主=" + (lockRowM == null ? "?" : lockRowM.owner()));
            } catch (Throwable ignored) {
                // ignore
            }
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§c这个仓库有仓单存续，不能直接打开。请把仓单拿到票据市场「挂卖」，"
                            + "或先作废仓单 / 执行 /billreceipt unlock"), false);
            event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
            event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
            return;
        }
        if (!StoreLocks.canOpen(pos, sp.getScoreboardName(), false)) {
            var lockRow = StoreLocks.get(pos);
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§c这个容器已被仓单锁定（持有人：" + (lockRow == null ? "?" : lockRow.owner()) + "）"),
                    false);
            event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
            event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ItemStack held = sp.getItemInHand(event.getHand());
        if (held == null || held.isEmpty() || held.getItem() != KjbbItems.BLANK_FORM.get()) {
            return;
        }
        if (sp.isShiftKeyDown()) {
            return;   // Shift = 交给原逻辑（开行情界面）
        }
        BlockPos pos = event.getPos();
        if (TickerStock.isTicker(level, pos)) {
            return;   // 发报机走原版流程
        }
        var be = level.getBlockEntity(pos);
        if (be == null) {
            return;
        }
        IItemHandler handler = findHandler(level, pos, be);
        if (handler == null) {
            return;   // 不是容器
        }
        System.out.println("[TangtuEconomical] RightClickBlock 接管: " + be.getClass().getSimpleName()
                + " @ " + pos);
        StoreFormNetwork.sendOpen(sp, level, pos);
        event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
        event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
    }

    /** null 面找不到就逐个方向试 */
    public static IItemHandler findHandler(ServerLevel level, BlockPos pos,
                                           net.minecraft.world.level.block.entity.BlockEntity be) {
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
