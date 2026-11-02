package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 锁定的容器：禁止自动化（漏斗、机械动力的漏斗/溜槽/传送带等）抽取或塞入。
 *
 * 做法：给原版容器类型注册一层包装 handler，
 * 锁定时 insert/extract 一律无效，但仍可读取内容（界面/仓单需要读）。
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD)
public final class LockedCapabilityGuard {

    private LockedCapabilityGuard() {
    }

    @SubscribeEvent
    public static void onRegisterCaps(RegisterCapabilitiesEvent event) {
        register(event, BlockEntityType.CHEST);
        register(event, BlockEntityType.BARREL);
        register(event, BlockEntityType.SHULKER_BOX);
        register(event, BlockEntityType.HOPPER);
        register(event, BlockEntityType.DISPENSER);
        register(event, BlockEntityType.DROPPER);
    }

    private static <T extends BlockEntity> void register(RegisterCapabilitiesEvent event,
                                                         BlockEntityType<T> type) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, type, (be, side) -> {
            if (!(be instanceof Container container)) {
                return null;
            }
            BlockPos pos = be.getBlockPos();
            return new LockedHandler(new InvWrapper(container), pos);
        });
    }

    /** 锁定容器时封住 insert/extract */
    private record LockedHandler(IItemHandler base, BlockPos pos) implements IItemHandler {

        private boolean locked() {
            return StoreLocks.get(pos) != null && StoreLocks.get(pos).locked();
        }

        @Override
        public int getSlots() {
            return base.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return base.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            // 仓单不能放进【它自己记录的那个容器】（放进别的容器没问题）
            if (stack != null && !stack.isEmpty()
                    && stack.getItem() == com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()
                    && com.tangtu.kjbb.item.StoreReceiptItem.recordsPos(stack, pos)) {
                return stack;
            }
            if (locked()) {
                return stack;
            }
            return base.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (locked()) {
                return ItemStack.EMPTY;
            }
            return base.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            return base.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return !locked() && base.isItemValid(slot, stack);
        }
    }
}
