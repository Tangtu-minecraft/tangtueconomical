package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 被仓单锁定的容器被破坏 → 里面的货全部流进【交易市场虚拟仓】，
 * 挂在仓单持有人名下，不掉在地上。
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.GAME)
public final class LockedStoreBreakHandler {

    private LockedStoreBreakHandler() {
    }

    /** 把所有指向这个容器的仓单标记为作废 */
    public static void voidReceipts(net.minecraft.server.MinecraftServer server,
                                     BlockPos pos) {
        String want = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        int n = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            var inv = sp.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var st = inv.getItem(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                if (st.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
                    continue;
                }
                if (want.equals(com.tangtu.kjbb.item.StoreReceiptItem.posText(st))) {
                    com.tangtu.kjbb.item.StoreReceiptItem.setVoid(st);
                    n++;
                }
            }
            sp.containerMenu.broadcastChanges();
        }
        if (n > 0) {
            System.out.println("[TangtuEconomical] 已作废 " + n + " 张指向 " + pos + " 的仓单");
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        var lock = StoreLocks.get(pos);
        if (lock == null || !lock.locked()) {
            return;
        }
        var be = level.getBlockEntity(pos);
        if (be == null) {
            return;
        }
        IItemHandler handler = StoreFormInteraction.findHandler(level, pos, be);
        if (handler == null) {
            return;
        }
        // 持有人：不记名时优先给破坏者，记名时给票上的人
        String holder = lock.owner() == null ? "" : lock.owner();
        if (lock.bearer() && event.getPlayer() instanceof ServerPlayer sp) {
            holder = sp.getScoreboardName();
        }
        if (holder.isEmpty()) {
            holder = event.getPlayer() == null ? "unknown" : event.getPlayer().getName().getString();
        }

        Map<String, Integer> moved = new LinkedHashMap<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack st = handler.getStackInSlot(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            var rl = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (rl == null) {
                continue;
            }
            int n = st.getCount();
            ItemStack ext = handler.extractItem(i, n, false);
            if (ext != null && !ext.isEmpty()) {
                moved.merge(rl.toString(), ext.getCount(), Integer::sum);
            }
        }
        if (moved.isEmpty()) {
            StoreLocks.unlock(pos);
        voidReceipts(level.getServer(), pos);
            return;
        }
        // 国民已买下仓单的箱子：货物已在国民账本里，这里只清空、不再转出（避免重复计量）
        if (com.tangtu.kjbb.ReceiptClearWatcher.isPending(pos)) {
            com.tangtu.kjbb.ReceiptClearWatcher.removePending(pos);
            StoreLocks.unlock(pos);
            voidReceipts(level.getServer(), pos);
            int drained = 0;
            for (var e : moved.entrySet()) {
                drained += e.getValue();
            }
            System.out.println("[TangtuEconomical] \u5df2\u552e\u4ed3\u5355\u5bb9\u5668\u88ab\u62c6 "
                    + pos + "\uff0c" + drained + " \u4ef6\u5df2\u5c5e\u56fd\u6c11\uff0c\u76f4\u63a5\u6e05\u7a7a");
            return;
        }

        // 货物流入【经济部】（规范：锁定容器被拆，货物归公）
        String treasury = com.tangtu.kjbb.MarketBots.regulatorName();
        int total = 0;
        for (var e : moved.entrySet()) {
            com.tangtu.kjbb.BotLedger.addStock(treasury, e.getKey(), e.getValue());
            total += e.getValue();
        }
        com.tangtu.kjbb.BotLedger.save();
        StoreLocks.unlock(pos);
        voidReceipts(level.getServer(), pos);
        System.out.println("[TangtuEconomical] \u9501\u5b9a\u5bb9\u5668\u88ab\u7834\u574f " + pos
                + " \u8d27\u7269\u8f6c\u5165\u7ecf\u6d4e\u90e8 \u4ef6\u6570=" + total
                + " \u79cd\u7c7b=" + moved.size());
        if (event.getPlayer() instanceof ServerPlayer sp) {
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "\u00a76[\u4ed3\u5355] \u8fd9\u4e2a\u5bb9\u5668\u662f\u9501\u5b9a\u7684\uff0c\u91cc\u9762\u7684 "
                            + total + " \u4ef6\u8d27\u7269\u5df2\u8f6c\u5165\u7ecf\u6d4e\u90e8"), false);
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "\u00a77\u89c4\u5219\uff1a\u9501\u5b9a\u5bb9\u5668\u88ab\u62c6\uff0c\u8d27\u7269\u5f52\u516c"), false);
        }
    }
}