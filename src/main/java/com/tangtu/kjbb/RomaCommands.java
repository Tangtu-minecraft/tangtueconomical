package com.tangtu.kjbb;

import com.mojang.brigadier.arguments.LongArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /billroma —— 罗马国（目的港买方）
 *
 *   book                 罗马国账目：备付现金、预算、累计收购
 *   stock               罗马国库存（粮/金属/机械/油/酒/羊毛/方解石）
 *   fx                   各航次的罗马行情系数与整船货款
 *   quote <id>           对某航次的报价单（逐货明细）
 *   sell <id> <物品> <数量>  出口：把货卖给罗马国（挂在该航次上，到港付款）
 *   want <id> <物品> <数量>  进口：向罗马国订货（预付，到港提货）
 *   orders               我的罗马国订单
 *   cancel <订单号>       撤单（仅募资期内）
 *   accept <id>          管理员：立刻让罗马国收货（正常等到港自动收）
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.GAME)
public final class RomaCommands {

    private RomaCommands() {
    }

    private static void msg(ServerPlayer sp, String s) {
        sp.displayClientMessage(Component.literal(s), false);
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("billroma")
                        .executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            msg(sp, RomaMarket.book());
                            msg(sp, "§7/billroma quote <航次> 看报价　/billroma fx 看行情");
                            return 1;
                        })
                        .then(Commands.literal("book").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            msg(sp, RomaMarket.book());
                            return 1;
                        }))
                        .then(Commands.literal("stock").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            msg(sp, RomaMarket.stockReport());
                            return 1;
                        }))
                        .then(Commands.literal("fx").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            msg(sp, RomaMarket.fxBoard());
                            return 1;
                        }))
                        .then(Commands.literal("quote")
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            long id = LongArgumentType.getLong(ctx, "id");
                                            var b = VoyageBill.get(id);
                                            if (b == null) {
                                                msg(sp, "§c没有这张提单");
                                                return 0;
                                            }
                                            for (String line : RomaMarket.quote(b).split("\n")) {
                                                msg(sp, line);
                                            }
                                            return 1;
                                        })))
                        .then(Commands.literal("sell")
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                                .then(Commands.argument("qty", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                        .executes(ctx -> {
                                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                                            if (sp == null) {
                                                                return 0;
                                                            }
                                                            msg(sp, RomaOrders.sell(sp,
                                                                    LongArgumentType.getLong(ctx, "id"),
                                                                    net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item").toString(),
                                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "qty")));
                                                            return 1;
                                                        })))))
                        .then(Commands.literal("want")
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                                .then(Commands.argument("qty", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                        .executes(ctx -> {
                                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                                            if (sp == null) {
                                                                return 0;
                                                            }
                                                            msg(sp, RomaOrders.want(sp,
                                                                    LongArgumentType.getLong(ctx, "id"),
                                                                    net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item").toString(),
                                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "qty")));
                                                            return 1;
                                                        })))))
                        .then(Commands.literal("orders").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            for (String line : RomaOrders.list(sp).split("\n")) {
                                msg(sp, line);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("order", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            msg(sp, RomaOrders.cancel(sp,
                                                    LongArgumentType.getLong(ctx, "order")));
                                            return 1;
                                        })))
                        .then(Commands.literal("accept")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            long id = LongArgumentType.getLong(ctx, "id");
                                            var b = VoyageBill.get(id);
                                            if (b == null) {
                                                msg(sp, "§c没有这张提单");
                                                return 0;
                                            }
                                            String r = RomaMarket.settle(b,
                                                    com.tangtu.kjbb.ServerCalendar.currentDay());
                                            msg(sp, r == null ? "§7这艘船已经收过货了（或船上没有货）"
                                                    : "§a" + r);
                                            return 1;
                                        })))
        );
    }
}
