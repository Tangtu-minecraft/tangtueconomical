package com.tangtu.kjbb.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** 提单：凭票取货。票面数据在组件里，真伪由 BillRegistry 序列号表校验 */
public class BillItem extends Item {

    /** 不记名提单（谁拿着谁能提货）；默认记名 */
    public static boolean isBearer(ItemStack stack) {
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        var tag = data.copyTag();
        return tag.contains("kjbb_bearer") && tag.getBoolean("kjbb_bearer");
    }

    public static void setBearer(ItemStack stack, boolean bearer) {
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putBoolean("kjbb_bearer", bearer));
    }

    public BillItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> lines, TooltipFlag flag) {
        BillData data = stack.get(KjbbItems.BILL_DATA.get());
        if (data == null) {
            lines.add(Component.literal("（空白票面 · 需在票据界面签发）").withStyle(ChatFormatting.GRAY));
            return;
        }
        String name = displayName(data.itemId());
        lines.add(Component.literal("货物：" + name + " ×" + data.count()).withStyle(ChatFormatting.DARK_GREEN));
        lines.add(Component.literal("签发：" + data.issuer()).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("编号：No." + data.serial()).withStyle(ChatFormatting.DARK_GRAY));
        boolean hasAddr = data.address() != null && !data.address().isEmpty();
        if (hasAddr) {
            lines.add(Component.literal("提货仓库：" + data.address())
                    .withStyle(ChatFormatting.DARK_AQUA));
        } else if (data.hasStore()) {
            lines.add(Component.literal("提货仓库：" + data.storeX() + ", "
                    + data.storeY() + ", " + data.storeZ()).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (data.expiresAt() > 0L) {
            lines.add(Component.literal("有效期：7 游戏日").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (BillData.STATUS_REDEEMED.equals(data.status())) {
            lines.add(Component.literal("类型：" + (isBearer(stack) ? "不记名"
                : "记名")).withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("状态：已兑付").withStyle(ChatFormatting.RED));
        } else if (BillData.STATUS_EXPIRED.equals(data.status())) {
            lines.add(Component.literal("状态：已作废/过期").withStyle(ChatFormatting.RED));
        } else {
            lines.add(Component.literal("状态：待发货").withStyle(ChatFormatting.GOLD));
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BillData data = stack.get(KjbbItems.BILL_DATA.get());
        if (data == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.pass(stack);
        }
        // 右键打开票面界面（只读：提单是发货证明）
        com.tangtu.kjbb.net.BillNetwork.sendOpenBill(sp, data);
        return InteractionResultHolder.success(stack);
    }

    private static String displayName(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item != null) {
                    return new ItemStack(item).getHoverName().getString();
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return id;
    }
}