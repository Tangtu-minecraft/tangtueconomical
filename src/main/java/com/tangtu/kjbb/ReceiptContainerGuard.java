package com.tangtu.kjbb;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 仓单防塞：GUI 的 shift-click / 手动拖拽走的是 Container 接口，绕过了物品能力，
 * 所以在服务端每秒扫一次「玩家开着的容器界面」，发现仓单就抢回背包。
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.GAME)
public final class ReceiptContainerGuard {

    private int counter = 0;
    private static final ReceiptContainerGuard INSTANCE = new ReceiptContainerGuard();

    private ReceiptContainerGuard() {
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        INSTANCE.tick(event.getServer());
    }

    private void tick(MinecraftServer server) {
        if (++counter < 20) {
            return;
        }
        counter = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            try {
                scanOpenMenu(sp);
            } catch (Throwable t) {
                System.out.println("[TangtuEconomical] 仓单防塞扫描异常: " + t);
            }
        }
    }

    private static void scanOpenMenu(ServerPlayer sp) {
        AbstractContainerMenu menu = sp.containerMenu;
        if (menu == null || menu == sp.inventoryMenu) {
            return;
        }
        var inv = sp.getInventory();
        boolean found = false;
        for (Slot slot : menu.slots) {
            // 只处理「容器侧」的槽位，玩家背包槽不管
            if (slot.container == inv) {
                continue;
            }
            ItemStack st = slot.getItem();
            if (st == null || st.isEmpty()) {
                continue;
            }
            if (st.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
                continue;
            }
            // 只拦「这张票记录的就是当前这个容器」
            net.minecraft.core.BlockPos holderPos = null;
            if (slot.container instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
                holderPos = be.getBlockPos();
            }
            if (holderPos == null
                    || !com.tangtu.kjbb.item.StoreReceiptItem.recordsPos(st, holderPos)) {
                continue;
            }
            ItemStack take = st.copy();
            slot.set(ItemStack.EMPTY);
            // 尝试塞回背包，塞不进就掉在脚下
            if (!inv.add(take)) {
                sp.drop(take, false);
            }
            found = true;
            System.out.println("[TangtuEconomical] 阻止仓单塞入容器：" + sp.getScoreboardName()
                    + " menu=" + menu.getClass().getSimpleName());
        }
        if (found) {
            menu.broadcastChanges();
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§c[仓单] 这张仓单不能放入它登记的箱子内，已退回你的背包"), false);
        }
    }
}
