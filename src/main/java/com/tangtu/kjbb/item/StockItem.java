package com.tangtu.kjbb.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * 股票（远洋提单份额凭证）
 *
 * 图标与「提单 / 仓单」一致（note_generic），
 * 目前只作为记账式份额的实体凭证：写清船号、份数、持有人。
 *
 * 说明文本记在物品的自定义名称与 lore 里，由命令生成。
 */
public class StockItem extends Item {

    public StockItem(Properties props) {
        super(props);
    }

    /** 写入票面：船号 + 份数 */
    public static void write(ItemStack stack, long billId, String shipName,
                             int shares, long dueDay) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("bill", billId);
        tag.putInt("shares", shares);
        tag.putString("ship", shipName == null ? "" : shipName);
        tag.putLong("due", dueDay);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        String title = (shipName == null || shipName.isEmpty() ? ("#" + billId) : shipName)
                + "  " + shares + " 份";
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(title)
                .withStyle(ChatFormatting.GOLD));
    }

    public static long billOf(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d == null ? -1L : d.copyTag().getLong("bill");
    }

    public static int sharesOf(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d == null ? 0 : d.copyTag().getInt("shares");
    }

    /** 右键结算：潜行 = 提货，普通 = 折现 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player,
                                                  InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            long billId = billOf(stack);
            if (player.isShiftKeyDown()) {
                // 潜行右键：直接按票面折现（快捷结算）
                String r = com.tangtu.kjbb.VoyageBill.settleStockItem(sp, stack, "cash");
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal(r), false);
            } else {
                // 普通右键：先补发离线期间撤单的票，再打开股票详情界面
                com.tangtu.kjbb.StockMarket.claimPending(sp);
                com.tangtu.kjbb.net.MenuNetwork.sendOpenStock(sp, billId);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx,
                                List<Component> tips, TooltipFlag flag) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        if (d != null && d.copyTag().contains("bill")) {
            CompoundTag tag = d.copyTag();
            tips.add(Component.literal("远洋航次 #" + tag.getLong("bill")
                    + "　" + tag.getInt("shares") + " 份").withStyle(ChatFormatting.GRAY));
            long due = tag.getLong("due");
            if (due > 0) {
                tips.add(Component.literal("到港日："
                        + com.tangtu.kjbb.ServerCalendar.shortDate(due))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            tips.add(Component.literal("手持右键可结算（提货 / 折现）")
                    .withStyle(ChatFormatting.DARK_GREEN));
        } else {
            tips.add(Component.literal("远洋提单份额凭证").withStyle(ChatFormatting.GRAY));
            tips.add(Component.literal("持此票可在到港后提取货物或折现")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        super.appendHoverText(stack, ctx, tips, flag);
    }
}
