package com.tangtu.kjbb;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Rabbit;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 唐兔国国民（兔子外形）
 *
 * EasyNPC 7.17.1 没有 rabbit 实体类型，所以用原版兔子 + 自定义标签，
 * 右键时由我们直接把国民信息打到聊天栏。
 */
@EventBusSubscriber(modid = "kjbankbridge")
public final class CitizenRabbit {

    /** 标记：这只兔子是国民 */
    public static final String TAG = "kjbb_citizen";

    private CitizenRabbit() {
    }

    /** 在玩家位置生成一只国民兔子 */
    public static boolean spawn(ServerPlayer sp) {
        if (!(sp.level() instanceof ServerLevel level)) {
            return false;
        }
        Rabbit rabbit = EntityType.RABBIT.create(level);
        if (rabbit == null) {
            return false;
        }
        BlockPos pos = sp.blockPosition();
        rabbit.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, sp.getYRot(), 0.0F);
        rabbit.setCustomName(Component.literal("\u00a7b\u5510\u5154\u56fd\u56fd\u6c11"));
        rabbit.setCustomNameVisible(true);
        rabbit.setNoAi(true);
        rabbit.setSilent(true);
        rabbit.setPersistenceRequired();
        rabbit.addTag(TAG);
        return level.addFreshEntity(rabbit);
    }

    /** 国民免疫一切伤害（玩家打不到、摔不死、烧不着） */
    @SubscribeEvent
    public static void onHurt(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        var entity = event.getEntity();
        if (!(entity instanceof Rabbit)) {
            return;
        }
        if (entity.getTags().contains(TAG)) {
            event.setCanceled(true);
            event.setAmount(0.0F);
        }
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        var target = event.getTarget();
        if (!(target instanceof Rabbit)) {
            return;
        }
        boolean tagged = target.getTags().contains(TAG);
        if (!tagged) {
            return;
        }
        // 潜行 + 拿地址铭牌 = 管理员绑地址，交给物品自己处理
        if (sp.isShiftKeyDown()
                && sp.getMainHandItem().getItem() instanceof com.tangtu.kjbb.item.AddressTagItem) {
            return;
        }
        showInfo(sp);
        event.setCanceled(true);
    }

    /** 国民信息 */
    public static void showInfo(ServerPlayer sp) {
        String bot = "\u5510\u5154\u56fd\u56fd\u6c11";
        sp.displayClientMessage(Component.literal("\u00a76\u2500\u2500 \u5510\u5154\u56fd\u56fd\u6c11 \u2500\u2500"), false);
        sp.displayClientMessage(Component.literal("\u00a77\u8d44\u91d1\uff1a\u00a7e"
                + BotLedger.money(bot) + " \u5510\u5154\u5e01"), false);
        sp.displayClientMessage(Component.literal("\u00a77\u6536\u8d2d\u4ef7\uff1a\u00a7f"
                + MarketBots.citizenPrices()), false);
        sp.displayClientMessage(Component.literal("\u00a77\u5e93\u5b58\uff1a\u00a7f\u5c0f\u9ea6 "
                + BotLedger.stock(bot, "minecraft:wheat") + "\u3000\u80e1\u841d\u535c "
                + BotLedger.stock(bot, "minecraft:carrot") + "\u3000\u9762\u5305 "
                + BotLedger.stock(bot, "minecraft:bread")), false);
        sp.displayClientMessage(Component.literal("\u00a78\u6bcf 30 \u6e38\u620f\u65e5\u53d1\u5de5\u8d44 3000\uff1b"
                + "\u6bcf\u5929\u5403 \u5c0f\u9ea6/\u80e1\u841d\u535c/\u9762\u5305 \u5404 1").withStyle(ChatFormatting.DARK_GRAY), false);
    }
}
