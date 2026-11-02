package com.tangtu.kjbb;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /billvoyage —— 远洋提单
 *
 *   list                 在售提单 + 我的份额
 *   info <id>            一张提单的详情
 *   buy <id> <份数>      一级市场认购
 *   my                   我持有的份额
 *   sell <id> <份数> <单价>   挂到二级市场
 *   market [id]          看二级市场的挂单
 *   take <挂单号> [份数]  买二级市场的份额
 *   unlist <挂单号>      撤单
 *   settle <id> goods|cash   到期结算（提货 / 折现）
 *   charter <物品> <数量>  包船：把自己的货运上公司的船（可多次追加）
 *   sail                 包船出海（付运费）
 *   abandon              撤掉包船，货退回
 *   issue                （管理员）立刻发行一船
 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.GAME)
public final class VoyageCommands {

    private VoyageCommands() {
    }

    private static void msg(ServerPlayer sp, String s) {
        sp.displayClientMessage(Component.literal(s), false);
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("billvoyage")
                        .executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            return help(sp);
                        })
                        .then(Commands.literal("help").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            return sp == null ? 0 : help(sp);
                        }))
                        .then(Commands.literal("list").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            msg(sp, "§6===== 远洋提单 =====（唐兔国航运公司）");
                            java.util.List<VoyageBill.Bill> all = VoyageBill.all();
                            if (all.isEmpty()) {
                                msg(sp, "§7（暂时没有船）");
                                return 1;
                            }
                            for (VoyageBill.Bill b : all) {
                                if (VoyageBill.CLOSED.equals(b.status)) {
                                    continue;
                                }
                                int mine = b.heldBy(sp.getScoreboardName());
                                msg(sp, "§e#" + b.id + " " + b.name + "  §7[" + b.status + "]"
                                        + "  份 " + b.sold + "/" + b.total
                                        + "  发行价 " + b.price + "/份"
                                        + "  份值 " + b.shareValue()
                                        + (mine > 0 ? ("  §a我的 " + mine + " 份") : ""));
                            }
                            return 1;
                        }))
                        .then(Commands.literal("info")
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            VoyageBill.Bill b = VoyageBill.get(
                                                    LongArgumentType.getLong(ctx, "id"));
                                            if (b == null) {
                                                msg(sp, "§c没有这张提单");
                                                return 0;
                                            }
                                            for (String line : VoyageBill.describe(b).split("\n")) {
                                                msg(sp, line);
                                            }
                                            int mine = b.heldBy(sp.getScoreboardName());
                                            if (mine > 0) {
                                                msg(sp, "§a我持有 " + mine + " 份");
                                            }
                                            return 1;
                                        })))
                        .then(Commands.literal("buy")
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .then(Commands.argument("shares", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    String r = VoyageBill.buy(sp,
                                                            LongArgumentType.getLong(ctx, "id"),
                                                            IntegerArgumentType.getInteger(ctx, "shares"));
                                                    msg(sp, r);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("my").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            String me = sp.getScoreboardName();
                            msg(sp, "§6===== 我的远洋份额 =====");
                            boolean any = false;
                            for (VoyageBill.Bill b : VoyageBill.all()) {
                                int mine = b.heldBy(me);
                                if (mine <= 0) {
                                    continue;
                                }
                                any = true;
                                msg(sp, "§e#" + b.id + " " + b.name + "  " + mine + " 份"
                                        + "  §7[" + b.status + "]"
                                        + "  每份 " + Math.round(b.shareValue() * b.riskMultiplier())
                                        + (VoyageBill.DUE.equals(b.status) ? "  §a→ 用 settle 结算" : ""));
                            }
                            if (!any) {
                                msg(sp, "§7（没有持有）");
                            }
                            return 1;
                        }))
                        .then(Commands.literal("invest")
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            msg(sp, VoyageBill.invest(sp.getScoreboardName(),
                                                    LongArgumentType.getLong(ctx, "amount")));
                                            return 1;
                                        })))
                        .then(Commands.literal("cargo")
                                .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    msg(sp, VoyageBill.contribute(
                                                            sp.getScoreboardName(),
                                                            net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item").toString(),
                                                            IntegerArgumentType.getInteger(ctx, "count")));
                                                    return 1;
                                                }))))
                        // 别名：老界面 / 攻略里写的是 contribute
                        .then(Commands.literal("contribute")
                                .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    msg(sp, VoyageBill.contribute(
                                                            sp.getScoreboardName(),
                                                            net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item").toString(),
                                                            IntegerArgumentType.getInteger(ctx, "count")));
                                                    return 1;
                                                }))))
                        .then(Commands.literal("stock")
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .then(Commands.argument("shares", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    var stack = VoyageBill.toStock(sp,
                                                            LongArgumentType.getLong(ctx, "id"),
                                                            IntegerArgumentType.getInteger(ctx, "shares"),
                                                            "实体化");
                                                    if (stack.isEmpty()) {
                                                        msg(sp, "§c份额不足或船号无效");
                                                        return 0;
                                                    }
                                                    if (!sp.getInventory().add(stack)) {
                                                        sp.drop(stack, false);
                                                    }
                                                    msg(sp, "§a已生成实体股票（可交易 / 可手持右键结算）");
                                                    return 1;
                                                }))))
                        .then(Commands.literal("redeem")
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            var held = sp.getMainHandItem();
                                            if (!(held.getItem() instanceof com.tangtu.kjbb.item.StockItem)) {
                                                msg(sp, "§c请手持股票再执行");
                                                return 0;
                                            }
                                            msg(sp, VoyageBill.settleStockItem(sp, held,
                                                    StringArgumentType.getString(ctx, "mode")));
                                            return 1;
                                        })))
                        .then(Commands.literal("stall")
                                .then(Commands.argument("price", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            String s = StringArgumentType.getString(ctx, "price");
                                            long price;
                                            if ("auto".equalsIgnoreCase(s)) {
                                                long bill = com.tangtu.kjbb.item.StockItem.billOf(
                                                        sp.getMainHandItem());
                                                VoyageBill.Bill b = VoyageBill.get(bill);
                                                price = b == null ? 200L
                            : Math.max(1L, b.shareValue() > 0L ? b.shareValue() : b.price);
                                            } else {
                                                try {
                                                    price = Long.parseLong(s);
                                                } catch (Exception e) {
                                                    msg(sp, "§c价格要写数字，或写 auto");
                                                    return 0;
                                                }
                                            }
                                            msg(sp, StockMarket.list(sp, price, 0));
                                            return 1;
                                        })
                                        .then(Commands.argument("shares", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    String s = StringArgumentType.getString(ctx, "price");
                                                    long price;
                                                    if ("auto".equalsIgnoreCase(s)) {
                                                        long bill = com.tangtu.kjbb.item.StockItem.billOf(
                                                                sp.getMainHandItem());
                                                        VoyageBill.Bill b = VoyageBill.get(bill);
                                                        price = b == null ? 200L
                                                                : Math.max(1L, b.shareValue());
                                                    } else {
                                                        try {
                                                            price = Long.parseLong(s);
                                                        } catch (Exception e) {
                                                            msg(sp, "§c价格要写数字，或写 auto");
                                                            return 0;
                                                        }
                                                    }
                                                    msg(sp, StockMarket.list(sp, price,
                                                            IntegerArgumentType.getInteger(ctx, "shares")));
                                                    return 1;
                                                }))))
                        .then(Commands.literal("stabuy")
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            msg(sp, StockMarket.buy(sp,
                                                    LongArgumentType.getLong(ctx, "id")));
                                            return 1;
                                        })))
                        .then(Commands.literal("stacancel")
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            msg(sp, StockMarket.cancel(sp,
                                                    LongArgumentType.getLong(ctx, "id")));
                                            return 1;
                                        })))
                        .then(Commands.literal("stalist").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            java.util.List<StockMarket.Row> rows = StockMarket.open();
                            if (rows.isEmpty()) {
                                msg(sp, "§7（股票交易板暂无挂单）");
                                return 1;
                            }
                            msg(sp, "§6===== 股票交易板 =====");
                            for (StockMarket.Row r : rows) {
                                VoyageBill.Bill b = VoyageBill.get(r.billId);
                                msg(sp, "§e#" + r.id + " 船#" + r.billId
                                        + (b == null ? "" : (" " + b.name))
                                        + "  " + r.shares + " 份 × " + r.price
                                        + "  卖家 " + r.seller);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("sell")
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .then(Commands.argument("shares", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("price", LongArgumentType.longArg(1))
                                                        .executes(ctx -> {
                                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                                            if (sp == null) {
                                                                return 0;
                                                            }
                                                            VoyageBill.Bill b = VoyageBill.get(
                                                                    LongArgumentType.getLong(ctx, "id"));
                                                            if (b == null) {
                                                                msg(sp, "§c没有这张提单");
                                                                return 0;
                                                            }
                                                            int shares = IntegerArgumentType.getInteger(ctx, "shares");
                                                            long price = LongArgumentType.getLong(ctx, "price");
                                                            String me = sp.getScoreboardName();
                                                            if (b.heldBy(me) < shares) {
                                                                msg(sp, "§c你的份额不够（持有 " + b.heldBy(me) + "）");
                                                                return 0;
                                                            }
                                                            VoyageShareMarket.Row r =
                                                                    VoyageShareMarket.add(b.id, me, shares, price);
                                                            msg(sp, "§a已挂单 #" + r.id + "：" + shares + " 份 × "
                                                                    + price + " 唐兔币/份");
                                                            return 1;
                                                        })))))
                        .then(Commands.literal("market")
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    return market(sp, -1L);
                                })
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            return market(sp, LongArgumentType.getLong(ctx, "id"));
                                        })))
                        .then(Commands.literal("take")
                                .then(Commands.argument("listing", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            VoyageShareMarket.Row r = VoyageShareMarket.get(
                                                    LongArgumentType.getLong(ctx, "listing"));
                                            if (r == null) {
                                                msg(sp, "§c找不到这个挂单");
                                                return 0;
                                            }
                                            return take(sp, r, r.shares);
                                        })
                                        .then(Commands.argument("shares", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    VoyageShareMarket.Row r = VoyageShareMarket.get(
                                                            LongArgumentType.getLong(ctx, "listing"));
                                                    if (r == null) {
                                                        msg(sp, "§c找不到这个挂单");
                                                        return 0;
                                                    }
                                                    return take(sp, r,
                                                            IntegerArgumentType.getInteger(ctx, "shares"));
                                                }))))
                        .then(Commands.literal("unlist")
                                .then(Commands.argument("listing", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            VoyageShareMarket.Row r = VoyageShareMarket.get(
                                                    LongArgumentType.getLong(ctx, "listing"));
                                            if (r == null || !VoyageShareMarket.OPEN.equals(r.status)) {
                                                msg(sp, "§c这个挂单不存在或已成交");
                                                return 0;
                                            }
                                            if (!r.seller.equals(sp.getScoreboardName())) {
                                                msg(sp, "§c这不是你的挂单");
                                                return 0;
                                            }
                                            VoyageShareMarket.setStatus(r.id, VoyageShareMarket.CANCELLED);
                                            msg(sp, "§a已撤单 #" + r.id + "（" + r.shares + " 份退回）");
                                            return 1;
                                        })))
                        .then(Commands.literal("settle")
                                .then(Commands.argument("id", LongArgumentType.longArg())
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    String r = VoyageBill.settle(sp,
                                                            LongArgumentType.getLong(ctx, "id"),
                                                            StringArgumentType.getString(ctx, "mode"));
                                                    msg(sp, r);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("debug")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.literal("roll")
                                        .then(Commands.argument("outcome", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    String o = StringArgumentType.getString(ctx, "outcome");
                                                    msg(sp, VoyageBill.debugRoll(o));
                                                    return 1;
                                                })
                                                .then(Commands.argument("bill", LongArgumentType.longArg(1))
                                                        .executes(ctx -> {
                                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                                            if (sp == null) {
                                                                return 0;
                                                            }
                                                            String o = StringArgumentType.getString(ctx, "outcome");
                                                            long id = LongArgumentType.getLong(ctx, "bill");
                                                            msg(sp, VoyageBill.debugRoll(id, o));
                                                            return 1;
                                                        }))))
                                .then(Commands.literal("depart")
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            msg(sp, VoyageBill.debugDepart(0L));
                                            return 1;
                                        })
                                        .then(Commands.argument("bill", LongArgumentType.longArg(1))
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    msg(sp, VoyageBill.debugDepart(
                                                            LongArgumentType.getLong(ctx, "bill")));
                                                    return 1;
                                                })))
                                .then(Commands.literal("reset").executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    String r = VoyageBill.debugReset();
                                    msg(sp, r);
                                    return 1;
                                })))
                        .then(Commands.literal("prod").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            for (String line : PlayerProduction.describe(10).split("\n")) {
                                msg(sp, line);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("charter")
                                .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    msg(sp, VoyageBill.charterAdd(sp,
                                                            net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item").toString(),
                                                            IntegerArgumentType.getInteger(ctx, "qty")));
                                                    return 1;
                                                }))))
                        .then(Commands.literal("sail").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            msg(sp, VoyageBill.charterSail(sp));
                            return 1;
                        }))
                        .then(Commands.literal("abandon").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            msg(sp, VoyageBill.charterAbandon(sp));
                            return 1;
                        }))
                        .then(Commands.literal("issue")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.literal("force").executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    long d = com.tangtu.kjbb.ServerCalendar.currentDay();
                                    VoyageBill.Bill nb = VoyageBill.issue(VoyageBill.issuerName(), d);
                                    msg(sp, "§a已强制发行 " + nb.name + "（# " + nb.id
                                            + "，每份 " + nb.price + "，共 " + nb.total + " 份）");
                                    return 1;
                                }))
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    // 已经有在售/航行中的船时不再重复发行，避免同时挂两张
                                    for (VoyageBill.Bill ex : VoyageBill.all()) {
                                        if (VoyageBill.OPEN.equals(ex.status)) {
                                            msg(sp, "§e现在正在招标：#" + ex.id + " " + ex.name
                                                    + "（已售 " + ex.sold + "/" + ex.total + " 份）");
                                            msg(sp, "§7募集截止第 " + ex.deadline
                                                    + " 日，到点自动出海；也可以 /billvoyage buy "
                                                    + ex.id + " <份数> 参与");
                                            msg(sp, "§7（管理员想立刻开新船：/billvoyage issue force）");
                                            return 0;
                                        }
                                        if (VoyageBill.VOYAGING.equals(ex.status)) {
                                            msg(sp, "§e已经有一艘在航：#" + ex.id + " " + ex.name
                                                    + "，第 " + ex.dueDay + " 日到港");
                                            msg(sp, "§7（管理员想立刻开新船：/billvoyage issue force）");
                                            return 0;
                                        }
                                    }
                                    long day = com.tangtu.kjbb.ServerCalendar.currentDay();
                                    VoyageBill.Bill b = VoyageBill.issue(VoyageBill.issuerName(), day);
                                    msg(sp, "§a已发行 " + b.name + "（# " + b.id + "，每份 "
                                            + b.price + "，到港日 " + b.dueDay + "）");
                                    return 1;
                                }))
        );
    }

    private static int help(ServerPlayer sp) {
        msg(sp, "§6===== 远洋提单 /billvoyage =====");
        msg(sp, "§7  list                       看所有在售的船");
        msg(sp, "§7  info <id>                 看一船详情");
        msg(sp, "§7  buy <id> <份数>            一级市场认购");
        msg(sp, "§7  my                        我持有的份额");
        msg(sp, "§7  invest <金额>               现金出资（换份额）");
        msg(sp, "§7  cargo <物品> <数量>         出库存（换份额）");
        msg(sp, "§7  stock <id> <份数>           把份额换成实体股票");
        msg(sp, "§7  redeem goods|cash           手持股票结算");
        msg(sp, "§7  stall <价格|auto>           把手里股票上架交易板");
        msg(sp, "§7  stabuy <挂单号>             买股票");
        msg(sp, "§7  stacancel <挂单号>          撤单");
        msg(sp, "§7  stalist                     看股票交易板");
        msg(sp, "§7  sell <id> <份数> <单价>     挂到二级市场");
        msg(sp, "§7  market [id]               看二级市场挂单");
        msg(sp, "§7  take <挂单号> [份数]        买二级市场");
        msg(sp, "§7  unlist <挂单号>             撤单");
        msg(sp, "§7  settle <id> goods|cash     到港后结算（提货 / 折现）");
        return 1;
    }

    private static int market(ServerPlayer sp, long billId) {
        msg(sp, "§6===== 远洋提单二级市场 =====");
        java.util.List<VoyageShareMarket.Row> rows = VoyageShareMarket.open();
        if (rows.isEmpty()) {
            msg(sp, "§7（没有挂单）");
            return 1;
        }
        int shown = 0;
        for (VoyageShareMarket.Row r : rows) {
            if (billId > 0 && r.billId != billId) {
                continue;
            }
            VoyageBill.Bill b = VoyageBill.get(r.billId);
            msg(sp, "§e#" + r.id + "  船 #" + r.billId
                    + (b == null ? "" : (" " + b.name))
                    + "  §7" + r.shares + " 份 × " + r.price + "/份"
                    + "  卖家 " + r.seller);
            if (++shown >= 15) {
                msg(sp, "§7（还有更多，用 market <船号> 过滤）");
                break;
            }
        }
        return 1;
    }

    private static int take(ServerPlayer sp, VoyageShareMarket.Row r, int want) {
        if (!VoyageShareMarket.OPEN.equals(r.status)) {
            msg(sp, "§c这个挂单已成交或已撤销");
            return 0;
        }
        VoyageBill.Bill b = VoyageBill.get(r.billId);
        if (b == null) {
            msg(sp, "§c这张提单不存在了");
            return 0;
        }
        if (!VoyageBill.OPEN.equals(b.status) && !VoyageBill.VOYAGING.equals(b.status)) {
            msg(sp, "§c这张提单已经到港，不能再转让份额");
            return 0;
        }
        String me = sp.getScoreboardName();
        if (r.seller.equals(me)) {
            msg(sp, "§c不能买自己的挂单");
            return 0;
        }
        int shares = Math.min(Math.max(1, want), r.shares);
        // 先确认卖家确实还持有这些份（防止重复挂牌导致买家付钱拿不到货）
        if (b.heldBy(r.seller) < shares) {
            msg(sp, "§c卖家份额不足（可能已重复挂牌），这笔挂单作废");
            VoyageShareMarket.setStatus(r.id, VoyageShareMarket.CANCELLED);
            return 0;
        }
        long cost = r.price * shares;
        long have = BankBridge.money(me);
        if (have < cost) {
            msg(sp, "§c唐兔币不够：需要 " + cost + "，你有 " + have);
            return 0;
        }
        long after = BankBridge.takeMoney(me, cost);
        if (have - after < cost) {
            msg(sp, "§c扣款失败");
            return 0;
        }
        BankBridge.addMoney(r.seller, cost);
        VoyageBill.transfer(b, r.seller, me, shares);
        r.shares -= shares;
        if (r.shares <= 0) {
            VoyageShareMarket.setStatus(r.id, VoyageShareMarket.DONE);
        } else {
            VoyageShareMarket.save();
        }
        BotTrades.log(me, r.seller, "远洋提单份额#" + b.id, shares, r.price, "二级市场");
        DebugLog.log("远洋份额成交 船#" + b.id + " " + shares + " 份 @" + r.price
                + "  买家=" + me + " 卖家=" + r.seller);
        msg(sp, "§a买下 " + shares + " 份，花 " + cost + " 唐兔币"
                + (r.shares > 0 ? ("（挂单还剩 " + r.shares + " 份）") : ""));
        return 1;
    }
}
