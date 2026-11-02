package com.tangtu.kjbb;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 运维指令：性能报表 / 强制落盘。
 *
 * <pre>
 *   /billperf          性能报表（各段耗时、峰值、超预算次数）
 *   /billperf reset    清零
 *   /billflush         立刻把脏数据写盘（默认每 saveIntervalTicks 自动刷）
 *   /billsave status   看脏标记统计
 * </pre>
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.GAME)
public final class PerfCommands {

    private PerfCommands() {
    }

    private static void msg(ServerPlayer sp, String s) {
        sp.displayClientMessage(Component.literal(s), false);
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("billgoods")
                        .then(Commands.argument("query", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    String id = net.minecraft.commands.arguments.ResourceLocationArgument
                                            .getId(ctx, "query").toString();
                                    String cat = RomaGoods.catOf(id);
                                    msg(sp, cat == null
                                            ? "§c" + id + " 不在共享贸易品目录里（会走关键词兜底）"
                                            : "§a" + id + "  §7品类 §f" + cat
                                              + "§7　粮食=" + RomaGoods.isFood(id)
                                              + " 金属=" + RomaGoods.isMetal(id)
                                              + " 奢侈=" + RomaGoods.isLuxury(id));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            msg(sp, "§6共享贸易品目录：§f" + RomaGoods.size() + " §7种物品");
                            for (String c : new String[]{"FOOD", "METAL", "MECH", "OIL", "WINE",
                                    "TEXTILE", "STONE", "BUILD", "LUXURY"}) {
                                msg(sp, "§e" + c + "§7 共 §f" + RomaGoods.of(c).size() + "§7 种");
                            }
                            msg(sp, "§7/billgoods <物品id> 查某个物品的品类");
                            return 1;
                        }));
        event.getDispatcher().register(
                Commands.literal("billtime")
                        .executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp != null) {
                                msg(sp, "§6当前时间流速：§f×" + TimeSpeed.get() + " §7（1=原速）");
                            }
                            return 1;
                        })
                        .then(Commands.argument("speed",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1000))
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                    int n = com.mojang.brigadier.arguments.IntegerArgumentType
                                            .getInteger(ctx, "speed");
                                    TimeSpeed.set(n);
                                    if (sp != null) {
                                        msg(sp, "§a时间流速 ×" + TimeSpeed.get()
                                                + " §7（一天约 " + Math.max(1, 1200 / TimeSpeed.get()) + " 秒）");
                                    }
                                    return 1;
                                })));
        
        event.getDispatcher().register(
                Commands.literal("billperf")
                        .executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            for (String line : Perf.report().split("\n")) {
                                msg(sp, line);
                            }
                            msg(sp, "§7" + DirtySave.stats());
                            return 1;
                        })
                        .then(Commands.literal("reset").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            Perf.reset();
                            if (sp != null) {
                                msg(sp, "§a性能计时已清零");
                            }
                            return 1;
                        })));

        event.getDispatcher().register(
                Commands.literal("billflush")
                        .executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            int before = DirtySave.dirtyCount();
                            DirtySave.flushAll();
                            if (sp != null) {
                                msg(sp, "§a已落盘 " + before + " 项脏数据（" + DirtySave.stats() + "）");
                            }
                            return 1;
                        })
                        .then(Commands.literal("status").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp != null) {
                                msg(sp, "§6" + DirtySave.stats());
                                msg(sp, "§7间隔 " + BotConfig.get().saveIntervalTicks
                                        + " tick；性能预算 " + BotConfig.get().perfBudgetMs
                                        + "ms / 市场预算 " + BotConfig.get().taskBudgetMs + "ms");
                            }
                            return 1;
                        })));
    }
}
