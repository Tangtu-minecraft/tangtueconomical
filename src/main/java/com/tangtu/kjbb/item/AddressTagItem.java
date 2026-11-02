package com.tangtu.kjbb.item;

import com.tangtu.kjbb.AddressBook;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 绑定钥匙：一键绑定提货点
 *
 *   潜行 + 右键蛙港/包裹港/邮箱 → 绑定为你的提货点
 *   普通右键方块               → 交给方块自己处理（打开邮箱/箱子界面）
 *   对空气右键                 → 查看当前绑定
 *
 * 解除绑定用 /billkey unbind。
 */
public class AddressTagItem extends Item {

    public AddressTagItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(ctx.getPlayer() instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        // 不潜行 → 不抢交互，让邮箱/箱子自己打开
        if (!sp.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        BlockPos pos = ctx.getClickedPos();
        if (!AddressBook.isValidPort((ServerLevel) level, pos)) {
            sp.displayClientMessage(Component.literal(
                    "\u00a7c[\u5730\u5740] \u63d0\u8d27\u70b9\u53ea\u80fd\u662f\u300c\u86d9\u6e2f / \u5305\u88f9\u6e2f / \u90ae\u7bb1\u300d"), false);
            return InteractionResult.SUCCESS;
        }
        String me = sp.getScoreboardName();
        AddressBook.set(me, pos.getX(), pos.getY(), pos.getZ());
        com.tangtu.kjbb.PortNamer.rename((ServerLevel) level, pos, me);
        sp.displayClientMessage(Component.literal(
                "\u00a7a[\u5730\u5740] \u63d0\u8d27\u70b9\u5df2\u7ed1\u5b9a\u5230 "
                        + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), false);
        System.out.println("[TangtuEconomical] \u5730\u5740\u7ed1\u5b9a " + me + " \u2192 "
                + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        Level level = player.level();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        if (!sp.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!sp.hasPermissions(2)) {
            sp.displayClientMessage(Component.literal(
                    "\u00a7c[\u5730\u5740] \u53ea\u6709\u7ba1\u7406\u5458\u80fd\u628a\u5730\u5740\u7ed1\u7ed9\u5176\u4ed6\u5b9e\u4f53"), false);
            return InteractionResult.SUCCESS;
        }
        String name = target.hasCustomName()
                ? target.getCustomName().getString().replaceAll("\u00a7.", "").trim()
                : target.getName().getString();
        if (name.isEmpty()) {
            return InteractionResult.PASS;
        }
        BlockPos pos = target.blockPosition();
        AddressBook.set(name, pos.getX(), pos.getY(), pos.getZ());
        sp.displayClientMessage(Component.literal(
                "\u00a7a[\u5730\u5740] \u5df2\u628a " + name + " \u7684\u63d0\u8d27\u70b9\u7ed1\u5b9a\u5230 "
                        + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), false);
        System.out.println("[TangtuEconomical] \u7ba1\u7406\u5458\u7ed1\u5b9a\u5730\u5740 " + name + " \u2192 " + pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            BlockPos p = AddressBook.get(sp.getScoreboardName());
            if (p == null) {
                sp.displayClientMessage(Component.literal(
                        "\u00a77[\u5730\u5740] \u8fd8\u6ca1\u7ed1\u5b9a\uff1a\u6f5c\u884c + \u53f3\u952e\u4e00\u4e2a\u90ae\u7bb1 / \u86d9\u6e2f"), false);
            } else {
                String blockName = "?";
                boolean valid = false;
                try {
                    if (level instanceof ServerLevel sl && sl.isLoaded(p)) {
                        var bs = sl.getBlockState(p);
                        blockName = new net.minecraft.world.item.ItemStack(bs.getBlock())
                                .getHoverName().getString();
                        valid = AddressBook.isValidPort(sl, p);
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
                sp.displayClientMessage(Component.literal(
                        "\u00a77[\u5730\u5740] \u4f60\u7684\u63d0\u8d27\u70b9\uff1a"
                                + sp.getScoreboardName()
                                + "  \u2192  " + blockName + " @ "
                                + p.getX() + ", " + p.getY() + ", " + p.getZ()
                                + (valid ? "  \u00a7a[\u6709\u6548]" : "  \u00a7c[\u65e0\u6548\u6216\u5757\u672a\u52a0\u8f7d]"))
                        .withStyle(ChatFormatting.YELLOW), false);
            }
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
}
