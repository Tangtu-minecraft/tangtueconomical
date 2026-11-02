package com.tangtu.kjbb.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.core.component.DataComponents;

import java.util.List;

/**
 * 假人劳动合同
 *
 * 只能用唐兔币从银行柜员（NPC）处购买，
 * 右键地面雇佣一个假人（SiliconeDolls 的真玩家），到期自动解雇。
 */
public class LaborContractItem extends Item {

    public LaborContractItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        String worker = tag.getString("kjbb_worker");
        long minutes = tag.getLong("kjbb_minutes");
        tooltip.add(Component.literal("\u00a76\u5047\u4eba\u52b3\u52a8\u5408\u540c").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("\u00a77\u5de5\u4eba\uff1a\u00a7f" + (worker.isEmpty() ? "\u672a\u547d\u540d" : worker)));
        tooltip.add(Component.literal("\u00a77\u5de5\u671f\uff1a\u00a7e" + minutes + " \u5206\u949f"));
        tooltip.add(Component.literal("\u00a78\u53f3\u952e\u5730\u9762\u96c7\u4f63\uff1b\u5230\u671f\u81ea\u52a8\u89e3\u96c7")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
            Level level, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand) {
        if (!(player instanceof ServerPlayer sp)) {
            return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
        }
        if (!player.isShiftKeyDown()) {
            return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
        }
        var tag = player.getItemInHand(hand).getOrDefault(DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        String worker = tag.getString("kjbb_worker");
        int minutes = (int) tag.getLong("kjbb_minutes");
        String owner = tag.getString("kjbb_owner");
        long price = com.tangtu.kjbb.LaborBots.priceFor(minutes);
        com.tangtu.kjbb.net.LaborNetwork.openDetail(sp, worker, minutes, (int) price, owner);
        return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (!(level instanceof ServerLevel serverLevel) || !(ctx.getPlayer() instanceof ServerPlayer sp)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        var tag = ctx.getItemInHand().getOrDefault(DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        String worker = tag.getString("kjbb_worker");
        if (worker.isEmpty()) {
            worker = "\u5047\u4eba" + (sp.getRandom().nextInt(9000) + 1000);
        }
        BlockPos pos = ctx.getClickedPos().relative(ctx.getClickedFace());
        int contractMinutes = (int) tag.getLong("kjbb_minutes");
        String err = com.tangtu.kjbb.LaborBots.hire(serverLevel, pos, worker, sp, contractMinutes);
        if (err != null) {
            sp.displayClientMessage(Component.literal(err), false);
            return InteractionResult.FAIL;
        }
        ctx.getItemInHand().shrink(1);
        sp.displayClientMessage(Component.literal("\u00a7a\u5df2\u96c7\u4f63\u5047\u4eba \u00a7e" + worker
                + "\u00a7a\uff0c\u5de5\u671f " + tag.getLong("kjbb_minutes") + " \u5206\u949f"), false);
        return InteractionResult.SUCCESS;
    }
}
