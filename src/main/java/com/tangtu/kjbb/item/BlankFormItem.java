package com.tangtu.kjbb.item;

import com.tangtu.kjbb.net.BillNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** 空白单据：右键打开纸质票据界面（填好后签发成提单） */
public class BlankFormItem extends Item {

    public BlankFormItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("右键：打开服务中心").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("潜行 + 右键：打开市场界面").withStyle(ChatFormatting.YELLOW));
        lines.add(Component.literal("潜行 + 右键箱子：给箱子开仓单").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("右键仓储发报机：打开仓库管理员").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 右键仓储发报机：交给原版打开仓储界面（我们要的就是原版界面） */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        // 潜行右键（非发报机）= 开行情界面
        if (ctx.getPlayer() != null && ctx.getPlayer().isShiftKeyDown()
                && !com.tangtu.kjbb.TickerStock.isTicker(ctx.getLevel(), ctx.getClickedPos())) {
            if (ctx.getLevel().isClientSide()) {
                com.tangtu.kjbb.net.ClientMarketOpen.open();
            }
            return InteractionResult.SUCCESS;
        }
        // 右键存储装置（箱子/保险库…）→ 打开仓单界面，读取容器内容
        if (!com.tangtu.kjbb.TickerStock.isTicker(ctx.getLevel(), ctx.getClickedPos())) {
            if (!ctx.getLevel().isClientSide() && ctx.getPlayer() instanceof ServerPlayer sp
                    && ctx.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                var be = sl.getBlockEntity(ctx.getClickedPos());
                if (be == null) {
                    System.out.println("[TangtuEconomical] useOn 容器: 方块实体为 null @ "
                            + ctx.getClickedPos());
                } else {
                    var handler = findHandler(sl, ctx.getClickedPos(), be);
                    System.out.println("[TangtuEconomical] useOn 容器: " + be.getClass().getSimpleName()
                            + " handler=" + (handler != null) + " @ " + ctx.getClickedPos());
                    if (handler != null) {
                        com.tangtu.kjbb.net.StoreFormNetwork.sendOpen(sp, sl, ctx.getClickedPos());
                        return InteractionResult.SUCCESS;
                    }
                }
            }
            return InteractionResult.PASS;
        }
        // 标记：接下来这单用「出票」而不是普通发货
        if (ctx.getLevel().isClientSide()) {
            com.tangtu.kjbb.client.BillModeClient.mark();
        } else if (ctx.getPlayer() instanceof ServerPlayer sp) {
            com.tangtu.kjbb.BillMode.mark(sp);
        }
        return InteractionResult.PASS;   // PASS = 让原版继续处理右键，打开原界面
    }

    /** 找容器的物品能力（null 面找不到就逐个方向试） */
    private static net.neoforged.neoforge.items.IItemHandler findHandler(
            net.minecraft.server.level.ServerLevel sl, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.entity.BlockEntity be) {
        var cap = net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK;
        var h = cap.getCapability(sl, pos, be.getBlockState(), be, null);
        if (h != null) {
            return h;
        }
        for (var d : net.minecraft.core.Direction.values()) {
            h = cap.getCapability(sl, pos, be.getBlockState(), be, d);
            if (h != null) {
                return h;
            }
        }
        return null;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 潜行右键空气 = 打开行情（K 线）界面；普通右键 = 开票界面
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) {
                com.tangtu.kjbb.net.ClientMarketOpen.open();
            }
            return InteractionResultHolder.success(stack);
        }
        // 普通右键 = 打开「票据中心」（选类型）
        if (level.isClientSide()) {
            com.tangtu.kjbb.net.ClientFormCenter.open();
        }
        return InteractionResultHolder.success(stack);
    }
}