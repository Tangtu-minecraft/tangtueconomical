package com.tangtu.kjbb.item;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/** /bill 命令：list / info <No.> / void <No.> */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.GAME)
public final class BillCommands {

    private BillCommands() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("bill")
                        .executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            List<String> list = BillRegistry.listOf(sp.getScoreboardName());
                            sp.displayClientMessage(Component.literal("§6===== 我签发的提单 ====="), false);
                            if (list.isEmpty()) {
                                sp.displayClientMessage(Component.literal("§7（没有）"), false);
                            } else {
                                for (String s : list) {
                                    sp.displayClientMessage(Component.literal("§7" + s), false);
                                }
                            }
                            return 1;
                        })
                        .then(Commands.literal("list").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            List<String> list = BillRegistry.listOf(sp.getScoreboardName());
                            if (list.isEmpty()) {
                                sp.displayClientMessage(Component.literal("§7（没有签发的提单）"), false);
                            } else {
                                for (String s : list) {
                                    sp.displayClientMessage(Component.literal("§7" + s), false);
                                }
                            }
                            return 1;
                        }))
                        .then(Commands.literal("info")
                                .then(Commands.argument("no", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            long no = LongArgumentType.getLong(ctx, "no");
                                            String info = BillRegistry.info(no);
                                            if (info == null) {
                                                if (sp != null) {
                                                    sp.displayClientMessage(
                                                            Component.literal("§c找不到 No." + no), false);
                                                }
                                                return 0;
                                            }
                                            for (String line : info.split("\n")) {
                                                if (sp != null) {
                                                    sp.displayClientMessage(
                                                            Component.literal("§7" + line), false);
                                                }
                                            }
                                            return 1;
                                        })))
                        .then(Commands.literal("open").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            com.tangtu.kjbb.net.BillNetwork.sendOpenPaper(sp);
                            return 1;
                        }))
                        .then(Commands.literal("shop").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                                return 0;
                            }
                            // 找最近的仓储发报机（32 格内）
                            net.minecraft.core.BlockPos best = null;
                            double bestDist = Double.MAX_VALUE;
                            var origin = sp.blockPosition();
                            for (int dx = -32; dx <= 32; dx++) {
                                for (int dy = -16; dy <= 16; dy++) {
                                    for (int dz = -32; dz <= 32; dz++) {
                                        net.minecraft.core.BlockPos p2 = origin.offset(dx, dy, dz);
                                        if (!level.isLoaded(p2)) {
                                            continue;
                                        }
                                        if (!com.tangtu.kjbb.TickerStock.isTicker(level, p2)) {
                                            continue;
                                        }
                                        double d = origin.distSqr(p2);
                                        if (d < bestDist) {
                                            bestDist = d;
                                            best = p2;
                                        }
                                    }
                                }
                            }
                            if (best == null) {
                                sp.displayClientMessage(Component.literal(
                                        "§c32 格内没找到仓储发报机"), false);
                                return 0;
                            }
                            com.tangtu.kjbb.net.OrderNetwork.open(sp, best);
                            return 1;
                        }))
                        .then(Commands.literal("addrhere").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            String name = sp.getScoreboardName();
                            var pos = sp.blockPosition();
                            com.tangtu.kjbb.AddressBook.set(name,
                                    pos.getX(), pos.getY(), pos.getZ());
                            sp.displayClientMessage(Component.literal(
                                    "§a已把你的收货地址绑定到当前位置："
                                            + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                            + "（地址名 = " + name + "）"), false);
                            return 1;
                        }))
                        .then(Commands.literal("addr")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            String name = StringArgumentType.getString(ctx, "name");
                                            var pos = sp.blockPosition();
                                            com.tangtu.kjbb.AddressBook.set(name,
                                                    pos.getX(), pos.getY(), pos.getZ());
                                            sp.displayClientMessage(Component.literal(
                                                    "§a已把地址「" + name + "」绑定到 "
                                                            + pos.getX() + ", " + pos.getY()
                                                            + ", " + pos.getZ()
                                                            + "（此处需要能放物品的容器）"), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("port")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("address", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                                    if (sp == null) {
                                                        return 0;
                                                    }
                                                    String addr = StringArgumentType.getString(ctx, "address");
                                                    var hit = sp.pick(6.0D, 0.0F, false);
                                                    net.minecraft.core.BlockPos bp = null;
                                                    Object be = null;
                                                    if (hit != null && hit.getType()
                                                            == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                                                        bp = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
                                                        be = sp.level().getBlockEntity(bp);
                                                    }
                                                    String type = com.tangtu.kjbb.PortRegistry.typeOf(be);
                                                    if (type.isEmpty()) {
                                                        // 找不到就扫附近 8 格
                                                        for (var dp : net.minecraft.core.BlockPos.betweenClosed(
                                                                sp.blockPosition().offset(-8, -4, -8),
                                                                sp.blockPosition().offset(8, 4, 8))) {
                                                            var cand = sp.level().getBlockEntity(dp);
                                                            String cy = com.tangtu.kjbb.PortRegistry.typeOf(cand);
                                                            if (!cy.isEmpty()) {
                                                                bp = dp.immutable();
                                                                be = cand;
                                                                type = cy;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                    if (bp == null || type.isEmpty()) {
                                                        sp.displayClientMessage(Component.literal(
                                                                "§c附近没找到白色邮箱或蛙港（站到它旁边再试）"), false);
                                                        return 0;
                                                    }
                                                    com.tangtu.kjbb.PortRegistry.bind(sp.getScoreboardName(), addr, bp, type);
                                                    sp.displayClientMessage(Component.literal(
                                                            "§a已登记提货点「" + addr + "」[" + type + "] "
                                                                    + bp.getX() + ", " + bp.getY() + ", " + bp.getZ()), false);
                                                    return 1;
                                                })))
                                .then(Commands.literal("addself").executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    String addr = sp.getScoreboardName();
                                    var hit = sp.pick(6.0D, 0.0F, false);
                                    net.minecraft.core.BlockPos bp = null;
                                    Object be = null;
                                    if (hit != null && hit.getType()
                                            == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                                        bp = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
                                        be = sp.level().getBlockEntity(bp);
                                    }
                                    String type = com.tangtu.kjbb.PortRegistry.typeOf(be);
                                    if (type.isEmpty()) {
                                        double best = Double.MAX_VALUE;
                                        for (var dp : net.minecraft.core.BlockPos.betweenClosed(
                                                sp.blockPosition().offset(-8, -4, -8),
                                                sp.blockPosition().offset(8, 4, 8))) {
                                            var cand = sp.level().getBlockEntity(dp);
                                            String cy = com.tangtu.kjbb.PortRegistry.typeOf(cand);
                                            if (cy.isEmpty()) {
                                                continue;
                                            }
                                            double d = sp.blockPosition().distSqr(dp);
                                            if (d < best) {
                                                best = d;
                                                bp = dp.immutable();
                                                be = cand;
                                                type = cy;
                                            }
                                        }
                                    }
                                    if (bp == null || type.isEmpty()) {
                                        sp.displayClientMessage(Component.literal(
                                                "§c附近没找到白色邮箱或蛙港，站到它旁边再说一次"), false);
                                        return 0;
                                    }
                                    com.tangtu.kjbb.PortRegistry.bind(sp.getScoreboardName(), addr, bp, type);
                                    sp.displayClientMessage(Component.literal(
                                            "§a✔ 已把你的提货点登记为「" + addr + "」[" + type + "] "
                                                    + bp.getX() + ", " + bp.getY() + ", " + bp.getZ()), false);
                                    sp.displayClientMessage(Component.literal(
                                            "§7以后在发报机下单，包裹会送到这里"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("sync").executes(ctx -> {
                                    int n = com.tangtu.kjbb.MarketSync.syncAll();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "§a已把 " + n + " 个标的接入股市（新建或开市）"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("list").executes(ctx -> {
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(com.tangtu.kjbb.PortRegistry.dump()), false);
                                    return 1;
                                }))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("address", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String addr = StringArgumentType.getString(ctx, "address");
                                                    com.tangtu.kjbb.PortRegistry.unbind(ctx.getSource().getPlayer() == null ? "" : ctx.getSource().getPlayer().getScoreboardName(), addr);
                                                    ctx.getSource().sendSuccess(
                                                            () -> Component.literal("§a已移除「" + addr + "」"), false);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("addresses").executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(com.tangtu.kjbb.AddressBook.dump()), false);
                            return 1;
                        }))
                        .then(Commands.literal("void")
                                .then(Commands.argument("no", LongArgumentType.longArg())
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            long no = LongArgumentType.getLong(ctx, "no");
                                            String who = sp == null ? "" : sp.getScoreboardName();
                                            boolean admin = ctx.getSource().hasPermission(2);
                                            String res = BillRegistry.voidBill(no, who, admin);
                                            ctx.getSource().sendSuccess(() -> Component.literal(res), false);
                                            return 1;
                                        })))
        );

        // 提单类型偏好
        event.getDispatcher().register(Commands.literal("billbearer")
                .executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    boolean cur = com.tangtu.kjbb.BillMode.bearerPref(sp);
                    sp.displayClientMessage(Component.literal("§e当前签发模式："
                            + (cur ? "不记名" : "记名")), false);
                    sp.displayClientMessage(Component.literal(
                            "§7用 /billbearer on 或 /billbearer off 切换"), false);
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    com.tangtu.kjbb.BillMode.setBearer(sp, true);
                    sp.displayClientMessage(Component.literal(
                            "§a之后签发的提单都是【不记名】"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    com.tangtu.kjbb.BillMode.setBearer(sp, false);
                    sp.displayClientMessage(Component.literal(
                            "§a之后签发的提单都是【记名】"), false);
                    return 1;
                })));


        // 挂单
        // K 线聚合查看
        event.getDispatcher().register(Commands.literal("billkline")
                .then(Commands.argument("item",
                                net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .executes(ctx -> {
                            String id = net.minecraft.commands.arguments.ResourceLocationArgument
                                    .getId(ctx, "item").toString();
                            var cs = com.tangtu.kjbb.KlineStore.recent(id, 10);
                            if (cs.isEmpty()) {
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "§7这个物品还没有成交"), false);
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§e" + id + "  最近 " + cs.size() + " 根（每根 "
                                            + (com.tangtu.kjbb.KlineStore.periodMs() / 60_000)
                                            + " 分钟）"), false);
                            var fmt = new java.text.SimpleDateFormat("HH:mm");
                            for (var c : cs) {
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "§8" + fmt.format(new java.util.Date(c.start()))
                                                + "  开" + c.open() + " 高§a" + c.high()
                                                + "§f 低§c" + c.low() + "§f 收§e" + c.close()
                                                + "§8  量 " + c.volume()), false);
                            }
                            return 1;
                        })));

        // K 线导出为 HTML（浏览器直接看）
        event.getDispatcher().register(Commands.literal("billkline")
                .then(Commands.literal("export")
                        .then(Commands.argument("item",
                                        net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .executes(ctx -> {
                                    var src2 = ctx.getSource();
                                    String id2 = net.minecraft.commands.arguments
                                            .ResourceLocationArgument.getId(ctx, "item").toString();
                                    var cs2 = com.tangtu.kjbb.KlineStore.recent(id2, 200);
                                    if (cs2.isEmpty()) {
                                        src2.sendSuccess(() -> Component.literal(
                                                "\u00a77\u8fd9\u4e2a\u7269\u54c1\u8fd8\u6ca1\u6709\u6210\u4ea4"), false);
                                        return 0;
                                    }
                                    String path = com.tangtu.kjbb.KlineExport.write(id2, cs2);
                                    src2.sendSuccess(() -> Component.literal(
                                            "\u00a7a\u5df2\u5bfc\u51fa " + cs2.size()
                                                    + " \u6839 K \u7ebf \u2192 " + path), false);
                                    return 1;
                                }))));

        // 管理员发币
        event.getDispatcher().register(Commands.literal("billmint")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("coin")
                        .then(Commands.argument("item",
                                        net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .then(Commands.argument("count", LongArgumentType.longArg(1))
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            String id = net.minecraft.commands.arguments
                                                    .ResourceLocationArgument.getId(ctx, "item").toString();
                                            int n = (int) LongArgumentType.getLong(ctx, "count");
                                            if (com.tangtu.kjbb.Coins.faceValue(id) <= 0) {
                                                sp.displayClientMessage(Component.literal(
                                                        "§c不是硬币：" + id), false);
                                                return 0;
                                            }
                                            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                                    .get(net.minecraft.resources.ResourceLocation.tryParse(id));
                                            var st = new net.minecraft.world.item.ItemStack(item,
                                                    Math.min(n, 64));
                                            int left = n;
                                            while (left > 0) {
                                                int k = Math.min(left, 64);
                                                var give = new net.minecraft.world.item.ItemStack(item, k);
                                                if (!sp.getInventory().add(give)) {
                                                    sp.drop(give, false);
                                                }
                                                left -= k;
                                            }
                                            com.tangtu.kjbb.Wallet.save();
                                            com.tangtu.kjbb.IssuanceLog.log("coin", id, n,
                                                    sp.getScoreboardName());
                                            sp.displayClientMessage(Component.literal(
                                                    "§a已发行 " + id + " ×" + n), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("money")
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    long n = LongArgumentType.getLong(ctx, "amount");
                                    com.tangtu.kjbb.Wallet.addMoney(sp.getScoreboardName(), n);
                                    com.tangtu.kjbb.IssuanceLog.log("money", "-", (int) n,
                                            sp.getScoreboardName());
                                    sp.displayClientMessage(Component.literal(
                                            "§a已发行 " + n + " 唐兔币"), false);
                                    return 1;
                                }))));

        // 生成一只国民兔子（OP）
        event.getDispatcher().register(Commands.literal("billcitizenspawn")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    boolean ok = com.tangtu.kjbb.CitizenRabbit.spawn(sp);
                    sp.displayClientMessage(Component.literal(ok
                            ? "\u00a7a\u5df2\u751f\u6210\u5510\u5154\u56fd\u56fd\u6c11\uff08\u5154\u5b50\uff09"
                            : "\u00a7c\u751f\u6210\u5931\u8d25"), false);
                    return 1;
                }));

        // 绑定钥匙的保底命令（不依赖右键交互）
        event.getDispatcher().register(Commands.literal("billkey")
                .then(Commands.literal("bindhere").executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayer sp = src.getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                        return 0;
                    }
                    net.minecraft.core.BlockPos best = null;
                    double bestD = Double.MAX_VALUE;
                    for (var p : com.tangtu.kjbb.AddressBook.portsNear(level, sp.blockPosition(), 16)) {
                        double d = p.distSqr(sp.blockPosition());
                        if (d < bestD) {
                            bestD = d;
                            best = p;
                        }
                    }
                    if (best == null) {
                        sp.displayClientMessage(Component.literal(
                                "\u00a7c\u9644\u8fd1 16 \u683c\u5185\u6ca1\u6709\u86d9\u6e2f/\u5305\u88f9\u6e2f/\u90ae\u7bb1"), false);
                        return 0;
                    }
                    com.tangtu.kjbb.AddressBook.set(sp.getScoreboardName(),
                            best.getX(), best.getY(), best.getZ());
                    com.tangtu.kjbb.PortNamer.rename(level, best, sp.getScoreboardName());
                    sp.displayClientMessage(Component.literal(
                            "\u00a7a[\u5730\u5740] \u5df2\u7ed1\u5b9a\u5230 " + best.getX() + ", "
                                    + best.getY() + ", " + best.getZ()), false);
                    return 1;
                }))
                .then(Commands.literal("unbind").executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayer sp = src.getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    boolean had = com.tangtu.kjbb.AddressBook.remove(sp.getScoreboardName());
                    sp.displayClientMessage(Component.literal(had
                            ? "\u00a7e[\u5730\u5740] \u5df2\u89e3\u9664\u7ed1\u5b9a"
                            : "\u00a77[\u5730\u5740] \u4f60\u672c\u6765\u5c31\u6ca1\u7ed1\u5b9a"), false);
                    return 1;
                }))
                .then(Commands.literal("info").executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayer sp = src.getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    var p = com.tangtu.kjbb.AddressBook.get(sp.getScoreboardName());
                    sp.displayClientMessage(Component.literal(p == null
                            ? "\u00a77[\u5730\u5740] \u8fd8\u6ca1\u7ed1\u5b9a"
                            : "\u00a77[\u5730\u5740] \u5f53\u524d\uff1a" + p.getX() + ", " + p.getY() + ", " + p.getZ()), false);
                    return 1;
                })));

        // 唐兔国国民：管理员直接给药/粮（测试用）
        event.getDispatcher().register(Commands.literal("billcitizen")
                .then(Commands.literal("gui").executes(ctx -> {
                    var src = ctx.getSource();
                    var sp = src.getPlayer();
                    if (sp == null) {
                        src.sendSuccess(() -> Component.literal("\u00a7c\u4ec5\u9650\u73a9\u5bb6"), false);
                        return 0;
                    }
                    // 管理员看完整报表，普通玩家只看人口/城镇/饱食
                    var lines = sp.hasPermissions(2)
                            ? com.tangtu.kjbb.CitizenReport.build()
                            : com.tangtu.kjbb.MarketBots.publicStatus();
                    StringBuilder sb = new StringBuilder();
                    for (String s : lines) {
                        sb.append(s.replaceAll("\u00a7.", "")).append('\n');
                    }
                    com.tangtu.kjbb.net.MenuNetwork.sendOpenReport(sp, sb.toString(),
                            com.tangtu.kjbb.CitizenReport.topItems(8));
                    return 1;
                }))
                .then(Commands.literal("report").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    var src = ctx.getSource();
                    var lines = com.tangtu.kjbb.CitizenReport.build();
                    var sp = src.getPlayer();
                    for (String s : lines) {
                        src.sendSuccess(() -> Component.literal(s), false);
                    }
                    com.tangtu.kjbb.CitizenReport.writeFile(lines);
                    if (sp != null) {
                        sp.displayClientMessage(Component.literal(
                                "\u00a78\u62a5\u8868\u5df2\u5199\u5165 config/kjbb/citizen_report.txt"), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("public").executes(ctx -> {
                    var src = ctx.getSource();
                    var sp = src.getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    var lines = com.tangtu.kjbb.MarketBots.publicStatus();
                    StringBuilder sb = new StringBuilder();
                    for (String s : lines) {
                        sb.append(s.replaceAll("\u00a7.", "")).append('\n');
                    }
                    String icons = com.tangtu.kjbb.CitizenReport.publicIconData(4, 2);
                    com.tangtu.kjbb.net.MenuNetwork.sendOpenReport(sp, sb.toString(), icons);
                    return 1;
                }))
                .then(Commands.literal("setmoney").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                .executes(ctx -> {
                                    String bot = com.tangtu.kjbb.MarketBots.citizenName();
                                    if (bot == null) {
                                        return 0;
                                    }
                                    long want = LongArgumentType.getLong(ctx, "amount");
                                    long now = com.tangtu.kjbb.BotLedger.money(bot);
                                    com.tangtu.kjbb.BotLedger.addMoney(bot, want - now);
                                    com.tangtu.kjbb.BotLedger.save();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "\u00a7a[\u56fd\u6c11] \u8d44\u91d1 " + now
                                                    + " \u2192 " + want), false);
                                    System.out.println("[TangtuEconomical] \u7ba1\u7406\u5458\u8bbe\u7f6e\u56fd\u6c11\u8d44\u91d1 "
                                            + now + " \u2192 " + want);
                                    return 1;
                                })))
                .then(Commands.literal("retail").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    int n = com.tangtu.kjbb.CitizenRetail.buildOffers();
                    var src = ctx.getSource();
                    src.sendSuccess(() -> Component.literal(
                            "\u00a7a[\u96f6\u552e] \u5f3a\u5236\u4e0a\u67b6\u5b8c\u6210\uff0c\u5171 "
                                    + n + " \u79cd"), false);
                    return 1;
                }))
                .then(Commands.literal("debug").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    var src = ctx.getSource();
                    for (String line : com.tangtu.kjbb.MarketBots.debugStatus()) {
                        src.sendSuccess(() -> Component.literal(line), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    var src = ctx.getSource();
                    var sp = src.getPlayer();
                    var lines = com.tangtu.kjbb.MarketBots.publicStatus();
                    for (String s : lines) {
                        if (sp != null) {
                            sp.displayClientMessage(Component.literal(s), false);
                        } else {
                            src.sendSuccess(() -> Component.literal(s), false);
                        }
                    }
                    return 1;
                }))
                .then(Commands.literal("feed")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("item",
                                        net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .then(Commands.argument("count", LongArgumentType.longArg(1))
                                        .executes(ctx -> {
                                            var src = ctx.getSource();
                                            String id = net.minecraft.commands.arguments
                                                    .ResourceLocationArgument.getId(ctx, "item").toString();
                                            int n = (int) LongArgumentType.getLong(ctx, "count");
                                            String bot = "\u5510\u5154\u56fd\u56fd\u6c11";
                                            com.tangtu.kjbb.BotLedger.addStock(bot, id, n);
                                            com.tangtu.kjbb.BotLedger.save();
                                            src.sendSuccess(() -> Component.literal(
                                                    "\u00a7a\u5df2\u7ed9\u56fd\u6c11\u8865\u8d27 " + id
                                                            + " \u00d7" + n), true);
                                            return 1;
                                        })))));

        // 唐兔国国民：查看当前收购价与库存
        event.getDispatcher().register(Commands.literal("billcitizen").executes(ctx -> {
            var src = ctx.getSource();
            src.sendSuccess(() -> Component.literal(
                    "\u00a76\u2500\u2500 \u5510\u5154\u56fd\u56fd\u6c11 \u2500\u2500"), false);
            String bot = "\u5510\u5154\u56fd\u56fd\u6c11";
            src.sendSuccess(() -> Component.literal("\u00a77\u5e02\u6c11\u5206\u5c42\uff1a\u00a7e"
                    + com.tangtu.kjbb.MarketBots.citizenLayerText()), false);
            src.sendSuccess(() -> Component.literal("\u00a77\u8d44\u91d1\uff1a\u00a7e"
                    + com.tangtu.kjbb.BotLedger.money(bot) + " \u5510\u5154\u5e01"), false);
            src.sendSuccess(() -> Component.literal("\u00a77\u5f53\u524d\u6536\u8d2d\u4ef7\uff1a\u00a7f"
                    + com.tangtu.kjbb.MarketBots.citizenPrices()), false);
            src.sendSuccess(() -> Component.literal("\u00a77\u5e93\u5b58\uff1a\u00a7f"
                    + "\u5c0f\u9ea6 " + com.tangtu.kjbb.BotLedger.stock(bot, "minecraft:wheat")
                    + "\u3000\u80e1\u841d\u535c " + com.tangtu.kjbb.BotLedger.stock(bot, "minecraft:carrot")
                    + "\u3000\u9762\u5305 " + com.tangtu.kjbb.BotLedger.stock(bot, "minecraft:bread")), false);
            src.sendSuccess(() -> Component.literal("\u00a77\u5de5\u8d44 3000 / 30 \u6e38\u620f\u65e5\uff1b"
                    + "\u6bcf\u65e5\u5403 \u5c0f\u9ea6/\u80e1\u841d\u535c/\u9762\u5305 \u5404 1"), false);
            return 1;
        }));

        // 经济系统主菜单
        event.getDispatcher().register(Commands.literal("billmenu").executes(ctx -> {
            ServerPlayer sp = com.tangtu.kjbb.CommandTargets.player(ctx.getSource());
            if (sp == null) {
                ctx.getSource().sendFailure(Component.literal(
                        "\u00a7c\u627e\u4e0d\u5230\u73a9\u5bb6\uff08\u8bf7\u9760\u8fd1 NPC \u518d\u8bd5\uff09"));
                return 0;
            }
            com.tangtu.kjbb.net.MenuNetwork.open(sp);
            return 1;
        }));

        // 劳动合同：打开购买界面（NPC 按钮调用 /billcontract）
        event.getDispatcher().register(Commands.literal("billcontract").executes(ctx -> {
            ServerPlayer sp = com.tangtu.kjbb.CommandTargets.player(ctx.getSource());
            if (sp == null) {
                ctx.getSource().sendFailure(Component.literal(
                        "\u00a7c\u627e\u4e0d\u5230\u73a9\u5bb6\uff08\u8bf7\u9760\u8fd1 NPC \u518d\u8bd5\uff09"));
                return 0;
            }
            com.tangtu.kjbb.net.LaborNetwork.open(sp);
            return 1;
        }));

        // 银行柜台菜单（NPC 右键或 /billbank 都会调用）
        event.getDispatcher().register(Commands.literal("billbank").executes(ctx -> {
            ServerPlayer sp = com.tangtu.kjbb.CommandTargets.player(ctx.getSource());
            if (sp == null) {
                ctx.getSource().sendFailure(Component.literal(
                        "\u00a7c\u627e\u4e0d\u5230\u73a9\u5bb6\uff08\u8bf7\u9760\u8fd1 NPC \u518d\u8bd5\uff09"));
                return 0;
            }
            com.tangtu.kjbb.net.MenuNetwork.openBank(sp);
            return 1;
        }));

        // 硬币
        event.getDispatcher().register(Commands.literal("billcoins")
                .executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            com.tangtu.kjbb.Coins.describe(sp)), false);
                    return 1;
                })
                .then(Commands.literal("deposit").executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    long got = com.tangtu.kjbb.Coins.depositAll(sp);
                    sp.displayClientMessage(Component.literal(got > 0
                            ? "§a已兑换背包硬币 " + got + " 唐兔币"
                            : "§7背包里没有硬币"), false);
                    return 1;
                }))
                .then(Commands.literal("combo")
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    long n = LongArgumentType.getLong(ctx, "amount");
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "§e" + n + " 高兔币 = §f"
                                                    + com.tangtu.kjbb.Coins.combo(n)), false);
                                    return 1;
                                })))
                .then(Commands.literal("cash")
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    long n = LongArgumentType.getLong(ctx, "amount");
                                    long got = com.tangtu.kjbb.Coins.withdrawWalletToCoins(sp, n);
                                    sp.displayClientMessage(Component.literal(got > 0
                                            ? "§a已从钱包取出 " + got + " 唐兔币换成硬币"
                                            : "§7钱包余额不足或金额为 0"), false);
                                    return 1;
                                })))
                .then(Commands.literal("togear").executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    long v = com.tangtu.kjbb.Coins.convertOldToGear(sp);
                    sp.displayClientMessage(Component.literal(v > 0
                            ? "§a旧币已换成齿轮币，面值 " + v
                            : "§7背包里没有旧币"), false);
                    return 1;
                }))
                .then(Commands.literal("toold").executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    long v = com.tangtu.kjbb.Coins.convertGearToOld(sp);
                    sp.displayClientMessage(Component.literal(v > 0
                            ? "§a齿轮币已换成旧币，面值 " + v
                            : "§7背包里没有齿轮币"), false);
                    return 1;
                }))
                .then(Commands.literal("safe").executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    long safe = com.tangtu.kjbb.Coins.safeBalance(sp);
                    long got = safe > 0 ? com.tangtu.kjbb.Coins.takeFromSafe(sp, safe) : 0;
                    sp.displayClientMessage(Component.literal(got > 0
                            ? "§a已从保险柜取出 " + got + " 唐兔币"
                            : "§7保险柜里没有余额（或没装 create_currency_shops）"), false);
                    return 1;
                })));

        // 旧余额迁移
        event.getDispatcher().register(Commands.literal("billmigrate")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> {
                    String msg = com.tangtu.kjbb.WalletMigration.run();
                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                }));

        // 机器人成交流水
        event.getDispatcher().register(Commands.literal("billtrades")
                .executes(ctx -> {
                    var rows = com.tangtu.kjbb.BotTrades.tail(20);
                    if (rows.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "§7还没有机器人成交记录"), false);
                        return 1;
                    }
                    for (String r : rows) {
                        String[] f = r.split("\\|", -1);
                        if (f.length < 8) {
                            continue;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "§8" + f[0] + " §f" + f[1] + " §7← " + f[2] + "  §f"
                                        + com.tangtu.kjbb.item.StoreReceiptItem.niceName(f[3])
                                        + " §7×" + f[4] + " @" + f[5] + " §8[" + f[7] + "]"), false);
                    }
                    return 1;
                }));

        // 价格表
        event.getDispatcher().register(Commands.literal("billprices")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            com.tangtu.kjbb.ShopPrices.describeSources()), false);
                    var ids = com.tangtu.kjbb.ShopPrices.items();
                    for (String id : ids) {
                        long v = com.tangtu.kjbb.ShopPrices.price(id);
                        if (v <= 0) {
                            continue;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "§7  " + id + "  §f" + v + " §8["
                                        + com.tangtu.kjbb.ShopPrices.sourceOf(id) + "]"), false);
                    }
                    return 1;
                })
                .then(Commands.argument("item",
                                net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .executes(ctx -> {
                            String id = net.minecraft.commands.arguments.ResourceLocationArgument
                                    .getId(ctx, "item").toString();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§f" + id + "  " + com.tangtu.kjbb.ShopPrices.detail(id)), false);
                            return 1;
                        }))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2))
                        .executes(ctx -> {
                            com.tangtu.kjbb.ShopPrices.reloadFromDisk();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a已重读 " + com.tangtu.kjbb.ShopPrices.pathString()), false);
                            return 1;
                        })));

        // 机器人
        event.getDispatcher().register(Commands.literal("billbot")
                .then(Commands.literal("setmoney").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("bot",
                                        com.mojang.brigadier.arguments.StringArgumentType.string())
                                .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(ctx -> {
                                            String key = com.mojang.brigadier.arguments
                                                    .StringArgumentType.getString(ctx, "bot");
                                            String bot = com.tangtu.kjbb.MarketBots.resolveBot(key);
                                            if (bot == null) {
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "\u00a7c\u6ca1\u6709\u8fd9\u4e2a\u673a\u5668\u4eba\uff1a" + key
                                                                + "\u00a77\uff08\u53ef\u7528\u5e8f\u53f7 0/1/2 \u6216\u89d2\u8272 regulator/neutral/noise\uff09"));
                                                return 0;
                                            }
                                            long want = LongArgumentType.getLong(ctx, "amount");
                                            long now = com.tangtu.kjbb.BotLedger.money(bot);
                                            com.tangtu.kjbb.BotLedger.addMoney(bot, want - now);
                                            com.tangtu.kjbb.BotLedger.save();
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "\u00a7a[\u673a\u5668\u4eba] " + bot + " \u8d44\u91d1 "
                                                            + now + " \u2192 " + want), false);
                                            System.out.println("[TangtuEconomical] \u8bbe\u7f6e " + bot
                                                    + " \u8d44\u91d1 " + now + " \u2192 " + want);
                                            return 1;
                                        }))))
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§e" + com.tangtu.kjbb.TradeActivity.describe()), false);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            com.tangtu.kjbb.BotLedger.dump()), false);
                    for (String b : com.tangtu.kjbb.BotConfig.names()) {
                        String st = com.tangtu.kjbb.MarketBots.longBuyStatus(b);
                        if (!st.isEmpty()) {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§e" + b + " 长期收购报价："), false);
                            ctx.getSource().sendSuccess(() -> Component.literal(st), false);
                        }
                    }
                    return 1;
                })
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2))
                        .executes(ctx -> {
                            String msg = com.tangtu.kjbb.BotConfig.loadFile();
                            ctx.getSource().sendSuccess(() -> Component.literal("§a" + msg), false);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    com.tangtu.kjbb.BotConfig.describe()), false);
                            return 1;
                        }))
                .then(Commands.literal("config").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            com.tangtu.kjbb.BotConfig.describe()), false);
                    return 1;
                }))
                .then(Commands.literal("pnl").requires(s -> s.hasPermission(2))
                        .executes(ctx -> {
                            for (String line : com.tangtu.kjbb.TraderPnl.report().split("\n")) {
                                final String l2 = line;
                                ctx.getSource().sendSuccess(() -> Component.literal(l2), false);
                            }
                            return 1;
                        })
                        .then(Commands.literal("reset").requires(s -> s.hasPermission(2))
                                .executes(ctx -> {
                                    com.tangtu.kjbb.TraderPnl.reset();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "\u00a7a\u6295\u673a\u76c8\u4e8f\u8d26\u5df2\u6e05\u7a7a"), false);
                                    return 1;
                                })))
                .then(Commands.literal("reset").requires(s -> s.hasPermission(2))
                        .executes(ctx -> {
                            com.tangtu.kjbb.BotLedger.resetAll();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a三位机器人已恢复本金与初始库存"), false);
                            return 1;
                        })));

        // 服务器日期
        event.getDispatcher().register(Commands.literal("billdate")
                .executes(ctx -> {
                    String msg = "\u00a76[\u5510\u5154\u56fd] \u00a7f"
                            + com.tangtu.kjbb.ServerCalendar.prettyNow()
                            + "\u00a77\uff081 \u5e74 = 360 \u65e5\uff0c1 \u6708 = 30 \u65e5\uff09";
                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                }));

        // 经济体检报告
        event.getDispatcher().register(Commands.literal("billecon")
                .executes(ctx -> {
                    if (!ctx.getSource().hasPermission(2)) {
                        ctx.getSource().sendFailure(Component.literal(
                                "\u00a7c\u53ea\u6709\u7ba1\u7406\u5458\u80fd\u67e5\u770b\u7ecf\u6d4e\u62a5\u544a"));
                        return 0;
                    }
                    for (String line : com.tangtu.kjbb.EconReport.build()) {
                        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return 1;
                })
                .then(Commands.literal("save").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    var lines = com.tangtu.kjbb.EconReport.build();
                    com.tangtu.kjbb.EconReport.save(lines);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "\u00a7a\u5df2\u4fdd\u5b58\u5230 config/kjbb/econ_report.txt"), false);
                    return 1;
                }))
                .then(Commands.literal("baseline").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    com.tangtu.kjbb.EconReport.ensureBaseline();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "\u00a7a\u57fa\u671f\u4ef7\u5feb\u7167\u5df2\u5c31\u7eea"), false);
                    return 1;
                })));

        // 票据市场（提单 + 仓单）
        event.getDispatcher().register(Commands.literal("ticket")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            com.tangtu.kjbb.TicketMarket.dump()), false);
                    return 1;
                })
                .then(Commands.literal("listslot")
                        .then(Commands.argument("slot", LongArgumentType.longArg(0, 35))
                                .then(Commands.argument("price", LongArgumentType.longArg(1))
                                        .executes(ctx -> {
                                            var sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            String msg = com.tangtu.kjbb.TicketActions.listSlot(sp,
                                                    (int) LongArgumentType.getLong(ctx, "slot"),
                                                    LongArgumentType.getLong(ctx, "price"));
                                            sp.displayClientMessage(Component.literal(msg), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("list")
                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                .executes(ctx -> doTicketList(ctx,
                                        LongArgumentType.getLong(ctx, "price")))))
                .then(Commands.literal("buy")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .executes(ctx -> doTicketBuy(ctx,
                                        LongArgumentType.getLong(ctx, "id")))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .executes(ctx -> doTicketCancel(ctx,
                                        LongArgumentType.getLong(ctx, "id")))))
                .then(Commands.literal("split")
                        .then(Commands.argument("args",
                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    String raw = com.mojang.brigadier.arguments.StringArgumentType
                                            .getString(ctx, "args").trim();
                                    String itemId = "";
                                    int cnt = 0;
                                    String[] parts = raw.split("\\s+");
                                    if (parts.length >= 2) {
                                        itemId = parts[0].trim();
                                        try {
                                            cnt = Integer.parseInt(parts[1].trim());
                                        } catch (Throwable e) {
                                            cnt = 0;
                                        }
                                    } else {
                                        try {
                                            cnt = Integer.parseInt(raw);
                                        } catch (Throwable e) {
                                            itemId = raw;
                                            cnt = 0;
                                        }
                                    }
                                    if (cnt <= 0) {
                                        sp.displayClientMessage(Component.literal(
                                                "§c用法：/ticket split <数量> 或 /ticket split <物品> <数量>"),
                                                false);
                                        return 0;
                                    }
                                    String msg = com.tangtu.kjbb.TicketActions.split(sp, itemId, cnt);
                                    sp.displayClientMessage(Component.literal(msg), false);
                                    return 1;
                                })))
                .then(Commands.literal("mine").executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (var r : com.tangtu.kjbb.TicketMarket.of(sp.getScoreboardName())) {
                        sb.append("#").append(r.id).append("  ").append(r.price)
                                .append(" 唐兔币  ").append(com.tangtu.kjbb.TicketMarket.describe(r))
                                .append("  [").append(r.status).append("]\n");
                    }
                    final String out = sb.length() == 0 ? "（你还没有挂卖票据）" : sb.toString();
                    sp.displayClientMessage(Component.literal(out), false);
                    return 1;
                })));

        // 买家下单 / 卖家接单
        event.getDispatcher().register(Commands.literal("billorder")
                .executes(ctx -> doOrders(ctx, com.tangtu.kjbb.OrderBook.PENDING))
                .then(Commands.literal("all").executes(ctx -> doOrders(ctx, null)))
                .then(Commands.literal("place")
                        .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .then(Commands.argument("count", LongArgumentType.longArg(1))
                                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                                .executes(ctx -> doOrderPlace(ctx,
                                                        net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item").toString(),
                                                        LongArgumentType.getLong(ctx, "count"),
                                                        LongArgumentType.getLong(ctx, "price")))))))
                .then(Commands.literal("shipnext")
                        .executes(ctx -> doShipNext(ctx, null))
                        .then(Commands.argument("pos",
                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(ctx -> doShipNext(ctx,
                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(
                                                ctx, "pos")))))
                .then(Commands.literal("pending")
                        .executes(ctx -> doPendingShipments(ctx)))
                .then(Commands.literal("ship")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .executes(ctx -> doShipOrder(ctx,
                                        LongArgumentType.getLong(ctx, "id"), null))
                                .then(Commands.argument("pos",
                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .executes(ctx -> doShipOrder(ctx,
                                                LongArgumentType.getLong(ctx, "id"),
                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(
                                                        ctx, "pos"))))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .executes(ctx -> {
                                    var sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    long id = LongArgumentType.getLong(ctx, "id");
                                    var rowBefore = com.tangtu.kjbb.OrderBook.get(id);
                                    String buyerName = rowBefore == null ? "" : rowBefore.buyer;
                                    String itemName = rowBefore == null ? "" : rowBefore.itemId;
                                    int cnt = rowBefore == null ? 0 : rowBefore.count;
                                    // 买家是市场机器人 → 不需要锁单发货，直接结算
                                    try {
                                        if (rowBefore != null
                                                && com.tangtu.kjbb.MarketBots.isBot(rowBefore.buyer)) {
                                            String msg = com.tangtu.kjbb.OrderActions.take(sp, id);
                                            sp.displayClientMessage(Component.literal(msg), false);
                                            return 1;
                                        }
                                    } catch (Throwable ignored) {
                                        // 回落到锁单流程
                                    }
                                    String blocked = com.tangtu.kjbb.OrderBook.acceptBlockReason(
                                            id, sp.getScoreboardName());
                                    if (blocked != null) {
                                        sp.displayClientMessage(Component.literal(
                                                "\u00a7c[\u9501\u5355] " + blocked), false);
                                        return 0;
                                    }
                                    boolean ok = com.tangtu.kjbb.OrderBook.accept(id, sp.getScoreboardName());
                                    if (ok) {
                                        // 通知买家：他的单被人接下了
                                        try {
                                            var bsp = sp.getServer() == null ? null
                                                    : sp.getServer().getPlayerList().getPlayerByName(buyerName);
                                            if (bsp != null) {
                                                bsp.displayClientMessage(Component.literal(
                                                        "\u00a76[\u9501\u5355] \u4f60\u7684\u8ba2\u5355 #" + id
                                                                + "\uff08" + cnt + " \u4ef6\uff09\u5df2\u88ab "
                                                                + sp.getScoreboardName()
                                                                + " \u63a5\u53d7\uff0c\u7b49\u5f85\u53d1\u8d27"), false);
                                            }
                                        } catch (Throwable ignored) {
                                            // ignore
                                        }
                                    }
                                    sp.displayClientMessage(Component.literal(ok
                                            ? "\u00a7a[\u9501\u5355] \u8ba2\u5355 #" + id
                                                    + " \u5df2\u9501\u5b9a\u7ed9\u4f60\uff0c10 \u5206\u949f\u5185\u53d1\u8d27"
                                            : "\u00a7c\u9501\u5355\u5931\u8d25\uff08\u5df2\u88ab\u4eba\u63a5\u8d70\u6216\u72b6\u6001\u4e0d\u5bf9\uff09"), false);
                                    return ok ? 1 : 0;
                                })))
                .then(Commands.literal("take")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .executes(ctx -> doSupply(ctx,
                                        LongArgumentType.getLong(ctx, "id"))))));

        event.getDispatcher().register(Commands.literal("billtrade")
                .executes(ctx -> {
                    var sp = ctx.getSource().getPlayer();
                    if (sp == null) { return 0; }
                    var mine = com.tangtu.kjbb.TradeOrder.mine(sp.getScoreboardName());
                    sp.displayClientMessage(Component.literal("§6===== 我的托管订单（" + mine.size() + "）====="), false);
                    for (var t : mine) {
                        sp.displayClientMessage(Component.literal("§e#" + t.id + " §f" + t.item
                                + " ×" + t.count + " @ " + t.unit + " §7[" + t.status + "] "
                                + "卖家 " + t.seller + " 买家 " + t.buyer), false);
                    }
                    return 1;
                })
                .then(Commands.literal("lock")
                        .then(Commands.argument("offer", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            var sp = ctx.getSource().getPlayer();
                                            if (sp == null) { return 0; }
                                            sp.displayClientMessage(Component.literal(
                                                    com.tangtu.kjbb.TradeOrder.lock(sp.getScoreboardName(),
                                                            com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "offer"),
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"))), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("ship")
                        .then(Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                .executes(ctx -> {
                                    var sp = ctx.getSource().getPlayer();
                                    if (sp == null) { return 0; }
                                    sp.displayClientMessage(Component.literal(
                                            com.tangtu.kjbb.TradeOrder.ship(sp.getScoreboardName(),
                                                    com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"))), false);
                                    return 1;
                                })))
                .then(Commands.literal("confirm")
                        .then(Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                .executes(ctx -> {
                                    var sp = ctx.getSource().getPlayer();
                                    if (sp == null) { return 0; }
                                    sp.displayClientMessage(Component.literal(
                                            com.tangtu.kjbb.TradeOrder.confirm(sp.getScoreboardName(),
                                                    com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"))), false);
                                    return 1;
                                }))));
        
        event.getDispatcher().register(Commands.literal("billpurge")
                .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            var sp = src.getPlayer();
                            String itemId = net.minecraft.commands.arguments.ResourceLocationArgument
                                    .getId(ctx, "item").toString();
                            if (sp == null || !src.hasPermission(2)) {
                                return 0;
                            }
                            int total = 0;
                            // ① 机器人账本
                            try {
                                for (String b : com.tangtu.kjbb.BotLedger.allNames()) {
                                    int n = com.tangtu.kjbb.BotLedger.stock(b, itemId);
                                    if (n > 0) {
                                        com.tangtu.kjbb.BotLedger.addStock(b, itemId, -n);
                                        total += n;
                                    }
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                            // ①.5 玩家仓库（发报机网络）
                    try {
                        var itemW = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .get(net.minecraft.resources.ResourceLocation.tryParse(itemId));
                        if (itemW != null) {
                            for (var pl2 : src.getServer().getPlayerList().getPlayers()) {
                                var lv2 = pl2.serverLevel();
                                var tpos = com.tangtu.kjbb.TickerShip.nearestTicker(lv2,
                                        pl2.blockPosition(), 64);
                                if (tpos != null) {
                                    int got = com.tangtu.kjbb.TickerStock.extractFromWarehouse(
                                            lv2, tpos, itemW, 1_000_000);
                                    total += got;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }

                    // ② 玩家背包
                            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .get(net.minecraft.resources.ResourceLocation.tryParse(itemId));
                            if (item != null) {
                                for (var pl : src.getServer().getPlayerList().getPlayers()) {
                                    var inv = pl.getInventory();
                                    for (int k = 0; k < inv.getContainerSize(); k++) {
                                        var st = inv.getItem(k);
                                        if (st != null && !st.isEmpty() && st.getItem() == item) {
                                            total += st.getCount();
                                            st.setCount(0);
                                        }
                                    }
                                }
                            }
                            // ③ 挂单
                            try {
                                for (var r : com.tangtu.kjbb.OfferBook.open()) {
                                    if (itemId.equals(r.itemId)) {
                                        com.tangtu.kjbb.OfferBook.setStatus(r.id,
                                                com.tangtu.kjbb.OfferBook.CANCELLED);
                                    }
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                            final int cnt = total;
                            sp.displayClientMessage(Component.literal(
                                    "§a已清理 " + itemId + " 共 " + cnt + " 个"), false);
                            com.tangtu.kjbb.DebugLog.log("清理物品：" + itemId + " ×" + cnt);
                            return 1;
                        })));
        
        event.getDispatcher().register(Commands.literal("billmail")
                .executes(ctx -> {
                    var src = ctx.getSource();
                    var rec = com.tangtu.kjbb.MailLog.recent(15);
                    if (rec.isEmpty()) {
                        src.sendSuccess(() -> Component.literal("§7还没有投递记录"), false);
                        return 0;
                    }
                    src.sendSuccess(() -> Component.literal("§6===== 最近投递（" + rec.size() + " 条）====="), false);
                    for (String l : rec) {
                        String[] f = l.split("\\|");
                        String txt = f.length >= 6
                                ? ("§7" + f[0] + " §f" + f[1] + " §7→ §f" + f[2]
                                    + " §7" + f[3] + " ×" + f[4] + " §e[" + f[5] + "]")
                                : ("§7" + l);
                        src.sendSuccess(() -> Component.literal(txt), false);
                    }
                    return 1;
                }));
        
        event.getDispatcher().register(Commands.literal("billoffer")
                .then(Commands.literal("mine")
                        .executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            var mine = com.tangtu.kjbb.OfferBook.of(sp.getScoreboardName());
                            if (mine.isEmpty()) {
                                sp.displayClientMessage(Component.literal("§7你没有在售的挂单"), false);
                                return 0;
                            }
                            long total = 0L;
                            sp.displayClientMessage(Component.literal("§6===== 我的挂单（" + mine.size() + " 条）====="), false);
                            for (var r : mine) {
                                if (!com.tangtu.kjbb.OfferBook.OPEN.equals(r.status)) {
                                    continue;
                                }
                                total += r.price * r.count;
                                sp.displayClientMessage(Component.literal("§e#" + r.id + "  §f" + r.itemId
                                        + " §7×" + r.count + " @" + r.price
                                        + " = §f" + (r.price * r.count) + " §7唐兔币"), false);
                            }
                            sp.displayClientMessage(Component.literal("§7合计市值 §f" + total + " §7唐兔币"), false);
                            return 1;
                        }))
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            com.tangtu.kjbb.OfferBook.dump()), false);
                    return 1;
                })
                .then(Commands.literal("list")
                        .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .then(Commands.argument("count", LongArgumentType.longArg(1))
                                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                                .executes(ctx -> doOfferList(ctx,
                                                        net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item").toString(),
                                                        LongArgumentType.getLong(ctx, "count"),
                                                        LongArgumentType.getLong(ctx, "price")))))))
                .then(Commands.literal("sell")
                        .then(Commands.argument("count", LongArgumentType.longArg(1))
                                .then(Commands.argument("price", LongArgumentType.longArg(1))
                                        .executes(ctx -> doOfferSellHeld(ctx,
                                                LongArgumentType.getLong(ctx, "count"),
                                                LongArgumentType.getLong(ctx, "price"))))))
                .then(Commands.literal("mine").executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (var r : com.tangtu.kjbb.OfferBook.of(sp.getScoreboardName())) {
                        sb.append("#").append(r.id).append("  ").append(r.itemId)
                                .append(" ×").append(r.count).append("  ").append(r.price)
                                .append(" 唐兔币/个  [").append(r.status).append("]\n");
                    }
                    final String out = sb.length() == 0 ? "（你还没有挂单）" : sb.toString();
                    sp.displayClientMessage(Component.literal(out), false);
                    return 1;
                }))
                .then(Commands.literal("buy")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .executes(ctx -> doOfferBuy(ctx,
                                        LongArgumentType.getLong(ctx, "id"), 0))
                                .then(Commands.argument("count", LongArgumentType.longArg(1))
                                        .executes(ctx -> doOfferBuy(ctx,
                                                LongArgumentType.getLong(ctx, "id"),
                                                (int) LongArgumentType.getLong(ctx, "count"))))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .executes(ctx -> doOfferCancel(ctx,
                                        LongArgumentType.getLong(ctx, "id"))))));

        // 交易市场虚拟仓
        // 仓单按票面货物定价
        event.getDispatcher().register(Commands.literal("billreceiptvalue")
                .executes(ctx -> {
                    var sp3 = ctx.getSource().getPlayer();
                    if (sp3 == null) {
                        return 0;
                    }
                    var held = sp3.getMainHandItem();
                    if (held == null || held.isEmpty()
                            || held.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
                        sp3.displayClientMessage(Component.literal("§c请手持仓单"), false);
                        return 0;
                    }
                    long total = 0L;
                    sp3.displayClientMessage(Component.literal("§6===== 仓单票面估值 ====="), false);
                    for (var line : com.tangtu.kjbb.item.StoreReceiptItem.readRecord(held)) {
                        long unit = com.tangtu.kjbb.ItemValuation.unitPrice(line.itemId());
                        long val = (unit > 0L ? unit : 1L) * line.count();
                        total += val;
                        sp3.displayClientMessage(Component.literal("§7"
                                + com.tangtu.kjbb.item.StoreReceiptItem.niceName(line.itemId())
                                + " ×" + line.count() + "  @ " + unit + " = " + val), false);
                    }
                    final long tt = total;
                    sp3.displayClientMessage(Component.literal(
                            "§e合计市值：§f" + tt + " 唐兔币"), false);
                    return 1;
                }));


        event.getDispatcher().register(Commands.literal("billvault")
                .executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    sp.displayClientMessage(Component.literal(
                            com.tangtu.kjbb.VirtualStore.dump(sp.getScoreboardName())), false);
                    return 1;
                })
                .then(Commands.literal("shipnext")
                        .executes(ctx -> doShipNext(ctx, null))
                        .then(Commands.argument("pos",
                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(ctx -> doShipNext(ctx,
                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(
                                                ctx, "pos")))))
                .then(Commands.literal("pending")
                        .executes(ctx -> doPendingShipments(ctx)))
                .then(Commands.literal("ship")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .executes(ctx -> doShipOrder(ctx,
                                        LongArgumentType.getLong(ctx, "id"), null))
                                .then(Commands.argument("pos",
                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .executes(ctx -> doShipOrder(ctx,
                                                LongArgumentType.getLong(ctx, "id"),
                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(
                                                        ctx, "pos"))))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .executes(ctx -> {
                                    var sp = ctx.getSource().getPlayer();
                                    if (sp == null) {
                                        return 0;
                                    }
                                    long id = LongArgumentType.getLong(ctx, "id");
                                    var rowBefore = com.tangtu.kjbb.OrderBook.get(id);
                                    String buyerName = rowBefore == null ? "" : rowBefore.buyer;
                                    String itemName = rowBefore == null ? "" : rowBefore.itemId;
                                    int cnt = rowBefore == null ? 0 : rowBefore.count;
                                    String blocked = com.tangtu.kjbb.OrderBook.acceptBlockReason(
                                            id, sp.getScoreboardName());
                                    if (blocked != null) {
                                        sp.displayClientMessage(Component.literal(
                                                "\u00a7c[\u9501\u5355] " + blocked), false);
                                        return 0;
                                    }
                                    boolean ok = com.tangtu.kjbb.OrderBook.accept(id, sp.getScoreboardName());
                                    if (ok) {
                                        // 通知买家：他的单被人接下了
                                        try {
                                            var bsp = sp.getServer() == null ? null
                                                    : sp.getServer().getPlayerList().getPlayerByName(buyerName);
                                            if (bsp != null) {
                                                bsp.displayClientMessage(Component.literal(
                                                        "\u00a76[\u9501\u5355] \u4f60\u7684\u8ba2\u5355 #" + id
                                                                + "\uff08" + cnt + " \u4ef6\uff09\u5df2\u88ab "
                                                                + sp.getScoreboardName()
                                                                + " \u63a5\u53d7\uff0c\u7b49\u5f85\u53d1\u8d27"), false);
                                            }
                                        } catch (Throwable ignored) {
                                            // ignore
                                        }
                                    }
                                    sp.displayClientMessage(Component.literal(ok
                                            ? "\u00a7a[\u9501\u5355] \u8ba2\u5355 #" + id
                                                    + " \u5df2\u9501\u5b9a\u7ed9\u4f60\uff0c10 \u5206\u949f\u5185\u53d1\u8d27"
                                            : "\u00a7c\u9501\u5355\u5931\u8d25\uff08\u5df2\u88ab\u4eba\u63a5\u8d70\u6216\u72b6\u6001\u4e0d\u5bf9\uff09"), false);
                                    return ok ? 1 : 0;
                                })))
                .then(Commands.literal("take")
                        .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .then(Commands.argument("count", LongArgumentType.longArg(1))
                                        .executes(ctx -> doVaultTake(ctx,
                                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item").toString(),
                                                LongArgumentType.getLong(ctx, "count")))))));

        // 仓单交易
        event.getDispatcher().register(Commands.literal("billreceipt")

                .then(Commands.literal("list")
                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                .executes(ctx -> doReceiptList(ctx,
                                        LongArgumentType.getLong(ctx, "price")))))
                .then(Commands.literal("shop").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            com.tangtu.kjbb.ReceiptMarket.dump()), false);
                    return 1;
                }))
                .then(Commands.literal("buy")
                        .then(Commands.argument("id", LongArgumentType.longArg())
                                .executes(ctx -> doReceiptBuy(ctx,
                                        LongArgumentType.getLong(ctx, "id")))))
                .then(Commands.literal("void").executes(ctx -> doReceiptVoid(ctx)))
                .then(Commands.literal("unlockall").executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    int n = 0;
                    for (var pos0 : com.tangtu.kjbb.StoreLocks.lockedBy(sp.getScoreboardName())) {
                        com.tangtu.kjbb.StoreLocks.unlock(pos0);
                        com.tangtu.kjbb.StoreSeals.clear(pos0);
                        com.tangtu.kjbb.StoreClaims.clear(pos0);
                        n++;
                    }
                    final int cnt = n;
                    sp.displayClientMessage(Component.literal(cnt == 0
                            ? "§7你名下没有锁定的容器"
                            : "§a已解锁并解封你名下的 " + cnt + " 个容器"), false);
                    return 1;
                }))
                .then(Commands.literal("unlock").executes(ctx -> doReceiptUnlock(ctx)))
                .then(Commands.literal("locks").executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    String who = sp == null ? null : sp.getScoreboardName();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            com.tangtu.kjbb.StoreLocks.dump(who)), false);
                    return 1;
                })));

        // 打开行情界面
        event.getDispatcher().register(Commands.literal("billmarket")
                .then(Commands.literal("buy")
                        .then(Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            ServerPlayer sp = ctx.getSource().getPlayer();
                                            if (sp == null) {
                                                return 0;
                                            }
                                            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                                    com.tangtu.kjbb.OfferBook.buy(sp.getScoreboardName(),
                                                            com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"),
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"))));
                                            return 1;
                                        }))))
                .executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayer();
                    if (sp == null) {
                        return 0;
                    }
                    com.tangtu.kjbb.net.MarketNetwork.sendOpen(sp);
                    return 1;
                }));

        // 订单
        event.getDispatcher().register(Commands.literal("billorders")
                .executes(ctx -> doOrders(ctx, com.tangtu.kjbb.OrderBook.PENDING))
                .then(Commands.literal("all").executes(ctx -> doOrders(ctx, null))));
        event.getDispatcher().register(Commands.literal("billsupply")
                .then(Commands.argument("id", LongArgumentType.longArg())
                        .executes(ctx -> doSupply(ctx, LongArgumentType.getLong(ctx, "id")))));

        // 独立根命令 /billport —— 提货点只由 NPC / 管理员绑定
        event.getDispatcher().register(
                Commands.literal("billport")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(com.tangtu.kjbb.PortRegistry.dump()), false);
                            return 1;
                        })
                        .then(Commands.literal("bind")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("address", StringArgumentType.word())
                                                .executes(ctx -> doBind(ctx,
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "address"))))))
                        .then(Commands.literal("default")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("address", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String pl = StringArgumentType.getString(ctx, "player");
                                                    String ad = StringArgumentType.getString(ctx, "address");
                                                    boolean ok = com.tangtu.kjbb.PortRegistry.setDefault(pl, ad);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(ok
                                                            ? "§a已把「" + ad + "」设为 " + pl + " 的默认提货点"
                                                            : "§c没有这个提货点"), false);
                                                    return ok ? 1 : 0;
                                                }))))
                        .then(Commands.literal("unbind")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("address", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String pl = StringArgumentType.getString(ctx, "player");
                                                    String ad = StringArgumentType.getString(ctx, "address");
                                                    boolean ok = com.tangtu.kjbb.PortRegistry.unbind(pl, ad);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(ok
                                                            ? "§a已解绑「" + ad + "」"
                                                            : "§c没有这个提货点"), false);
                                                    return ok ? 1 : 0;
                                                }))))
                        .then(Commands.literal("claim")
                                .executes(ctx -> doClaim(ctx, null))
                                .then(Commands.argument("address", StringArgumentType.word())
                                        .executes(ctx -> doClaim(ctx,
                                                StringArgumentType.getString(ctx, "address")))))
                        .then(Commands.literal("list").executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(com.tangtu.kjbb.PortRegistry.dump()), false);
                            return 1;
                        }))
                  .then(Commands.literal("mine").executes(ctx -> {
                            ServerPlayer sp = ctx.getSource().getPlayer();
                            if (sp == null) {
                                return 0;
                            }
                            var list = com.tangtu.kjbb.PortRegistry.of(sp.getScoreboardName());
                            if (list.isEmpty()) {
                                sp.displayClientMessage(Component.literal(
                                        "§7你还没有提货点，找商店管理员绑定"), false);
                                return 1;
                            }
                            sp.displayClientMessage(Component.literal("§6我的提货点："), false);
                            for (var e : list) {
                                sp.displayClientMessage(Component.literal("§7· " + e.address()
                                        + (e.isDefault() ? " §a[默认]" : "")
                                        + " §7[" + e.type() + "] " + e.x() + ", " + e.y() + ", " + e.z()), false);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("sync").executes(ctx -> {
                            int n = com.tangtu.kjbb.MarketSync.syncAll();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a已把 " + n + " 个标的接入股市（新建或开市）"), false);
                            return 1;
                        }))
        );
    }

    /** /billvault take <物品> <数量> —— 从虚拟仓取出 */
    private static int doVaultTake(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            String itemId, long count) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        net.minecraft.world.item.Item it =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.tryParse(itemId));
        if (it == null) {
            sp.displayClientMessage(Component.literal("§c物品不存在"), false);
            return 0;
        }
        int got = com.tangtu.kjbb.VirtualStore.take(sp.getScoreboardName(), itemId, (int) count);
        if (got <= 0) {
            sp.displayClientMessage(Component.literal("§c虚拟仓里没有这个物品"), false);
            return 0;
        }
        int left = got;
        while (left > 0) {
            int n = Math.min(left, Math.max(1, it.getDefaultMaxStackSize()));
            var st = new net.minecraft.world.item.ItemStack(it, n);
            if (!sp.getInventory().add(st)) {
                sp.drop(st, false);
            }
            left -= n;
        }
        final int g = got;
        sp.displayClientMessage(Component.literal("§a已从虚拟仓取出 " + itemId + " ×" + g), false);
        return 1;
    }

    /** /billreceipt list <价格> —— 把手上的仓单挂到市场 */
    private static int doReceiptList(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, long price) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        var stack = sp.getMainHandItem();
        if (stack == null || stack.isEmpty()
                || stack.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
            sp.displayClientMessage(Component.literal("§c主手要拿着仓单"), false);
            return 0;
        }
        String posText = com.tangtu.kjbb.item.StoreReceiptItem.posText(stack);
        if (posText == null || posText.isEmpty()) {
            sp.displayClientMessage(Component.literal("§c这张仓单没有记录位置"), false);
            return 0;
        }
        // 位置会被锁定；挂单后仓单物品销毁，成交时重新生成
        String[] xyz = posText.split(",");
        net.minecraft.core.BlockPos pos;
        try {
            pos = new net.minecraft.core.BlockPos(Integer.parseInt(xyz[0].trim()),
                    Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (Throwable t) {
            sp.displayClientMessage(Component.literal("§c仓单位置数据损坏"), false);
            return 0;
        }
        var lines = com.tangtu.kjbb.item.StoreReceiptItem.readRecord(stack);
        if (lines.isEmpty()) {
            sp.displayClientMessage(Component.literal("§c这张仓单没有物品记录"), false);
            return 0;
        }

        // 挂单前校验封条（内容没被换过）
        if (sp.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            if (!com.tangtu.kjbb.StoreSeals.matches(sl, pos)) {
                sp.displayClientMessage(Component.literal(
                        "§c容器内容与仓单记录不一致，不能挂单"), false);
                return 0;
            }

        // 挂单即冻结：锁交给市场托管，连卖家自己也打不开
            com.tangtu.kjbb.StoreLocks.lock(pos, "__MARKET__", false);
        }
        var row = com.tangtu.kjbb.ReceiptMarket.add(sp.getScoreboardName(), price, pos, lines);
        stack.shrink(1);
        sp.displayClientMessage(Component.literal("§a[仓单市场] 已挂单 #" + row.id + "  "
                + price + " 唐兔币，共 " + lines.size() + " 种物品"), false);
        sp.displayClientMessage(Component.literal("§7其他人可 /billreceipt shop 查看、"
                + "/billreceipt buy " + row.id + " 购买"), false);
        return 1;
    }

    /** /billreceipt buy <id> —— 买下仓单 */
    private static int doReceiptBuy(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, long id) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        var row = com.tangtu.kjbb.ReceiptMarket.get(id);
        if (row == null || !com.tangtu.kjbb.ReceiptMarket.ON_SALE.equals(row.status)) {
            sp.displayClientMessage(Component.literal("§c这个仓单不在售"), false);
            return 0;
        }
        String buyer = sp.getScoreboardName();
        if (com.tangtu.kjbb.BankBridge.money(buyer) < row.price) {
            sp.displayClientMessage(Component.literal("§c余额不足（需要 " + row.price + " 唐兔币）"), false);
            return 0;
        }
        if (!com.tangtu.kjbb.Pay.pay(sp, row.price)) {
            sp.displayClientMessage(Component.literal("§c余额不足"), false);
            return 0;
        }
        // 成交前再校验封条
        if (sp.level() instanceof net.minecraft.server.level.ServerLevel slb) {
            String[] xyz0 = row.pos.split(",");
            net.minecraft.core.BlockPos pos0;
            try {
                pos0 = new net.minecraft.core.BlockPos(Integer.parseInt(xyz0[0].trim()),
                        Integer.parseInt(xyz0[1].trim()), Integer.parseInt(xyz0[2].trim()));
            } catch (Throwable t) {
                pos0 = null;
            }
            if (pos0 != null && !com.tangtu.kjbb.StoreSeals.matches(slb, pos0)) {
                com.tangtu.kjbb.BankBridge.addMoney(buyer, row.price);
                com.tangtu.kjbb.ReceiptMarket.setStatus(id, "CANCELLED");
                sp.displayClientMessage(Component.literal(
                        "§c容器内容已被卖家改动，交易取消，款项已退回"), false);
                return 0;
            }
        }
        com.tangtu.kjbb.Pay.credit(row.seller, row.price);
        com.tangtu.kjbb.ReceiptMarket.setStatus(id, com.tangtu.kjbb.ReceiptMarket.SOLD);

        String[] xyz = row.pos.split(",");
        net.minecraft.core.BlockPos pos;
        try {
            pos = new net.minecraft.core.BlockPos(Integer.parseInt(xyz[0].trim()),
                    Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (Throwable t) {
            pos = new net.minecraft.core.BlockPos(0, 0, 0);
        }
        java.util.List<com.tangtu.kjbb.item.StoreReceiptItem.Line> lines = new java.util.ArrayList<>();
        for (String part : row.items.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                lines.add(new com.tangtu.kjbb.item.StoreReceiptItem.Line(part.substring(0, eq),
                        Integer.parseInt(part.substring(eq + 1).trim())));
            } catch (Throwable ignored) {
                // 跳过
            }
        }
        var stack = new net.minecraft.world.item.ItemStack(
                com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get());
        com.tangtu.kjbb.item.StoreReceiptItem.writeRecord(stack, pos, buyer,
                sp.level().getGameTime(), lines);
        if (!sp.getInventory().add(stack)) {
            sp.drop(stack, false);
        }
        // 容器锁转给买家
        com.tangtu.kjbb.StoreLocks.transfer(pos, buyer);
        sp.displayClientMessage(Component.literal("§a[仓单市场] 已买下 #" + id + "，花费 "
                + row.price + " 唐兔币；容器已转到你名下"), false);
        return 1;
    }

    /** /billoffer sell <数量> <单价> —— 用主手物品的 id 挂单 */
    private static int doOfferSellHeld(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            long count, long price) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        var held = sp.getMainHandItem();
        if (held == null || held.isEmpty()) {
            sp.displayClientMessage(Component.literal("§c主手要拿着要卖的物品"), false);
            return 0;
        }
        var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem());
        if (rl == null) {
            return 0;
        }
        return doOfferList(ctx, rl.toString(), count, price);
    }

    /** /billoffer list <物品> <数量> <单价> —— 优先从背包取货，不够再补仓库 */
    private static int doOfferList(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            String itemId, long count, long price) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        int want = (int) Math.max(1L, count);
        long unit = Math.max(1L, price);
        net.minecraft.world.item.Item it =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.tryParse(itemId));
        if (it == null) {
            sp.displayClientMessage(Component.literal("§c物品不存在：" + itemId), false);
            return 0;
        }

        // ⓪ 可交易性：功能道具 / 无配方物品一律不准挂单
        try {
            if (!com.tangtu.kjbb.Tradability.sellable(sp.getServer(), itemId)) {
                sp.displayClientMessage(Component.literal(
                        com.tangtu.kjbb.Tradability.rejectReason(itemId)), false);
                return 0;
            }
        } catch (Throwable ignored) {
            // 校验失败不拦
        }

        // ① 先从背包拿
        int fromBag = takeFromInventory(sp, it, want);
        int need = want - fromBag;

        // ② 不够再从附近的发报机仓库补（没有发报机也不报错）
        int fromStore = 0;
        if (need > 0 && sp.level() instanceof net.minecraft.server.level.ServerLevel level) {
            var ticker = com.tangtu.kjbb.TickerShip.nearestTicker(level, sp.blockPosition(), 64);
            if (ticker != null) {
                int stock = com.tangtu.kjbb.TickerStock.networkCount(level, ticker,
                        new net.minecraft.world.item.ItemStack(it));
                if (stock > 0) {
                    fromStore = com.tangtu.kjbb.TickerStock.extractFromWarehouse(level, ticker, it,
                            Math.min(need, stock));
                    if (fromStore > 0) {
                        System.out.println("[TangtuEconomical] 挂单补充库存 " + fromStore + " 个 @ " + ticker);
                    }
                }
            }
        }

        // 仓库有货但没取出来 → 明确报错并回滚，避免静默挂单
        if (fromStore < 0) {
            fromStore = 0;
        }
        if (fromBag > 0 && fromBag + fromStore < want && fromBag < want
                && sp.level() instanceof net.minecraft.server.level.ServerLevel lvx) {
            var tkx = com.tangtu.kjbb.TickerShip.nearestTicker(lvx, sp.blockPosition(), 64);
            if (tkx != null) {
                int stx = com.tangtu.kjbb.TickerStock.networkCount(lvx, tkx,
                        new net.minecraft.world.item.ItemStack(it));
                if (stx > 0 && fromStore == 0) {
                    giveBack(sp, it, fromBag);
                    sp.displayClientMessage(Component.literal(
                            "§c仓库里有 " + stx + " 个，但取货失败了（发报机网络可能没加载）"), false);
                    return 0;
                }
            }
        }
        int total = fromBag + fromStore;
        if (total < want) {
            // 不够就把已拿的退回去
            if (fromBag > 0) {
                giveBack(sp, it, fromBag);
            }
            if (fromStore > 0 && sp.level() instanceof net.minecraft.server.level.ServerLevel lv2) {
                var tk = com.tangtu.kjbb.TickerShip.nearestTicker(lv2, sp.blockPosition(), 64);
                if (tk != null) {
                    com.tangtu.kjbb.TickerStock.insertIntoWarehouse(lv2, tk,
                            new net.minecraft.world.item.ItemStack(it, fromStore));
                }
            }
            sp.displayClientMessage(Component.literal(
                    "§c货不够：背包 " + fromBag + " + 仓库 " + fromStore + "，挂不了 " + want + " 个"),
                    false);
            return 0;
        }

        var row = com.tangtu.kjbb.OfferBook.add(sp.getScoreboardName(), itemId, want, unit);
        sp.displayClientMessage(Component.literal("§a[挂单] #" + row.id + "  " + itemId
                + " ×" + want + "  " + unit + " 唐兔币/个（共 " + (unit * want) + " 唐兔币）"), false);
        sp.displayClientMessage(Component.literal("§7来源：背包 " + fromBag + " / 仓库 " + fromStore
                + "　用 /billoffer cancel " + row.id + " 可撤单"), false);
        System.out.println("[TangtuEconomical] 挂单 #" + row.id + " " + itemId + " x" + want
                + " price=" + unit + " bag=" + fromBag + " store=" + fromStore
                + " seller=" + sp.getScoreboardName());
        return 1;
    }

    /** 把「地址」解析成容器坐标：先查地址簿，再查提货点登记表（按地址、再按玩家） */
    private static net.minecraft.core.BlockPos resolvePort(String address, String ownerName) {
        if (address != null && !address.isEmpty()) {
            var p1 = com.tangtu.kjbb.AddressBook.get(address);
            if (p1 != null) {
                return p1;
            }
            for (var e : com.tangtu.kjbb.PortRegistry.all()) {
                if (e.address() != null && e.address().equalsIgnoreCase(address)) {
                    return e.pos();
                }
            }
        }
        if (ownerName != null && !ownerName.isEmpty()) {
            var list = com.tangtu.kjbb.PortRegistry.of(ownerName);
            if (!list.isEmpty()) {
                return list.get(0).pos();
            }
        }
        return null;
    }

    /** 从背包扣物品，返回实际扣到的数量 */
    private static int takeFromInventory(ServerPlayer sp, net.minecraft.world.item.Item it,
                                         int want) {
        int left = want;
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            var st = inv.getItem(i);
            if (st == null || st.isEmpty() || st.getItem() != it) {
                continue;
            }
            int take = Math.min(st.getCount(), left);
            st.shrink(take);
            left -= take;
        }
        return want - left;
    }

    /** 把物品退回背包（满则掉地上） */
    private static void giveBack(ServerPlayer sp, net.minecraft.world.item.Item it, int count) {
        int left = count;
        while (left > 0) {
            int n = Math.min(left, Math.max(1, it.getDefaultMaxStackSize()));
            var st = new net.minecraft.world.item.ItemStack(it, n);
            if (!sp.getInventory().add(st)) {
                sp.drop(st, false);
            }
            left -= n;
        }
    }

    /** /billoffer buy <id> —— 买下挂单（全部或部分） */
    private static int doOfferBuy(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            long id, int wantCount) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        var row = com.tangtu.kjbb.OfferBook.get(id);
        if (row == null || !com.tangtu.kjbb.OfferBook.OPEN.equals(row.status)) {
            sp.displayClientMessage(Component.literal("§c这个挂单不在售"), false);
            return 0;
        }
        int take = wantCount <= 0 ? row.count : Math.min(wantCount, row.count);
        long total = row.price * take;
        String buyer = sp.getScoreboardName();
        if (com.tangtu.kjbb.BankBridge.money(buyer) < total) {
            sp.displayClientMessage(Component.literal("§c余额不足（需要 " + total + " 唐兔币）"), false);
            return 0;
        }
        // 结算货币规则：外贸类物品只能用实物硬币
        if (com.tangtu.kjbb.CurrencyRules.isCoinOnly(row.itemId)) {
            if (!com.tangtu.kjbb.Pay.payWithCoinsOnly(sp, total)) {
                sp.displayClientMessage(Component.literal(
                        "§c这个物品只能用实物硬币结算（旧币或齿轮币），你的硬币不够"), false);
                return 0;
            }
        } else {
            com.tangtu.kjbb.BankBridge.takeMoney(buyer, total);
        }
        com.tangtu.kjbb.MarketPulse.record(row.itemId, row.price);
        com.tangtu.kjbb.TradeActivity.recordPlayerTrade();
        com.tangtu.kjbb.MarketPrice.onTrade(row.itemId, row.price);
        com.tangtu.kjbb.KlineAggregator.onTrade(row.itemId, (int) Math.max(1L, row.price), take);
        if (com.tangtu.kjbb.MarketBots.isBot(row.seller)) {
            com.tangtu.kjbb.Pay.credit(row.seller, total);
        } else {
            com.tangtu.kjbb.Pay.credit(row.seller, total);
        }
        // 投机客盈亏记账
        try {
            String tn = com.tangtu.kjbb.MarketBots.traderName();
            if (tn != null && tn.equals(row.seller)) {
                com.tangtu.kjbb.TraderPnl.sell(row.itemId, take, row.price);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // 交易税 1%（经济部收）
        try {
            com.tangtu.kjbb.RegulatorOps.tax(sp, total);
        } catch (Throwable ignored) {
            // ignore
        }

        // 打包给买家提货点
        // 自己的绑定地址优先；没有才用附近提货点登记表的地址
        String addr = com.tangtu.kjbb.AddressBook.addressOf(sp);
        if (addr == null || addr.isEmpty()) {
            var reg = com.tangtu.kjbb.PortRegistry.pick(sp.blockPosition(), addr);
            if (reg != null && reg.address() != null && !reg.address().isEmpty()) {
                addr = reg.address();
            }
        }
        // 改为「发取货提单」：玩家拿着提单右键邮箱才把货塞进去
        if (com.tangtu.kjbb.BillDelivery.giveBill(sp, row.itemId, take, addr)) {
            row.count -= take;
            if (row.count <= 0) {
                com.tangtu.kjbb.OfferBook.setStatus(id, com.tangtu.kjbb.OfferBook.FILLED);
            }
            sp.displayClientMessage(Component.literal("\u00a7a[\u6210\u4ea4] \u4e70\u4e0b " + take
                    + " \u4e2a\uff0c\u5171 " + total + " \u5510\u5154\u5e01\uff0c"
                    + "\u5df2\u751f\u6210\u53d6\u8d27\u63d0\u5355\uff08\u53f3\u952e\u4f60\u7684\u90ae\u7bb1\u53d6\u8d27\uff09"), false);
            com.tangtu.kjbb.BotTrades.log(row.seller, buyer, row.itemId, take, row.price,
                    "\u6302\u5355\u6210\u4ea4");
            System.out.println("[TangtuEconomical] \u5356\u5355\u6210\u4ea4 #" + id + " \u4e70\u5bb6=" + buyer
                    + " \u6570\u91cf=" + take + " \u91d1\u989d=" + total + " \u5356\u5bb6=" + row.seller);
            return 1;
        }
        net.minecraft.core.BlockPos port = com.tangtu.kjbb.AddressBook.get(addr);
        java.util.List<net.minecraft.world.item.ItemStack> goods = new java.util.ArrayList<>();
        net.minecraft.world.item.Item it =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.tryParse(row.itemId));
        int left = take;
        while (it != null && left > 0) {
            int n = Math.min(left, Math.max(1, it.getDefaultMaxStackSize()));
            goods.add(new net.minecraft.world.item.ItemStack(it, n));
            left -= n;
        }
        boolean shipped = false;
        if (port != null && sp.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            shipped = com.tangtu.kjbb.AddressBook.deliverTo(
                    new com.tangtu.kjbb.AddressBook.PortInfo(sl, port, addr), goods);
        }
        if (!shipped) {
            for (var g : goods) {
                if (!sp.getInventory().add(g)) {
                    sp.drop(g, false);
                }
            }
        }
        row.count -= take;
        if (row.count <= 0) {
            com.tangtu.kjbb.OfferBook.setStatus(id, com.tangtu.kjbb.OfferBook.FILLED);
        }
        sp.displayClientMessage(Component.literal("§a[成交] 挂单 #" + id + "  买下 " + take
                + " 个，共 " + total + " 唐兔币" + (shipped ? "，包裹已寄往「" + addr + "」" : "，已放入背包")),
                false);
        System.out.println("[TangtuEconomical] 挂单成交 #" + id + " 买家=" + buyer + " 数量=" + take
                + " 金额=" + total + " 卖家=" + row.seller);
        return 1;
    }

    /** /billoffer cancel <id> —— 撤单，货退回卖家 */
    private static int doOfferCancel(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, long id) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        var row = com.tangtu.kjbb.OfferBook.get(id);
        if (row == null || !com.tangtu.kjbb.OfferBook.OPEN.equals(row.status)) {
            sp.displayClientMessage(Component.literal("§c这个挂单不在了"), false);
            return 0;
        }
        if (!row.seller.equalsIgnoreCase(sp.getScoreboardName())) {
            sp.displayClientMessage(Component.literal("§c不是你的挂单"), false);
            return 0;
        }
        net.minecraft.world.item.Item it =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.tryParse(row.itemId));
        int left = row.count;
        while (it != null && left > 0) {
            int n = Math.min(left, Math.max(1, it.getDefaultMaxStackSize()));
            var st = new net.minecraft.world.item.ItemStack(it, n);
            if (!sp.getInventory().add(st)) {
                sp.drop(st, false);
            }
            left -= n;
        }
        com.tangtu.kjbb.OfferBook.setStatus(id, com.tangtu.kjbb.OfferBook.CANCELLED);
        sp.displayClientMessage(Component.literal("§e已撤单 #" + id + "，货物退回背包"), false);
        return 1;
    }

    /** /billreceipt void —— 主手仓单作废（解封 + 解锁，之后可重新开单） */
    private static int doReceiptVoid(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        var stack = sp.getMainHandItem();
        if (stack == null || stack.isEmpty()
                || stack.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
            sp.displayClientMessage(Component.literal("§c主手要拿着仓单"), false);
            return 0;
        }
        if (com.tangtu.kjbb.item.StoreReceiptItem.isVoid(stack)) {
            sp.displayClientMessage(Component.literal("§c这张仓单已经作废了"), false);
            return 0;
        }
        String posText = com.tangtu.kjbb.item.StoreReceiptItem.posText(stack);
        net.minecraft.core.BlockPos pos = null;
        try {
            String[] xyz = posText.split(",");
            pos = new net.minecraft.core.BlockPos(Integer.parseInt(xyz[0].trim()),
                    Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (Throwable ignored) {
            // 忽略
        }
        com.tangtu.kjbb.item.StoreReceiptItem.setVoid(stack);
        if (pos != null) {
            com.tangtu.kjbb.StoreSeals.clear(pos);
            com.tangtu.kjbb.StoreLocks.unlock(pos);
        }
        sp.displayClientMessage(Component.literal(
                "§e仓单已作废，容器解封解锁（现在可以重新开单）"), false);
        System.out.println("[TangtuEconomical] 仓单作废 by " + sp.getScoreboardName()
                + " pos=" + posText);
        return 1;
    }

    /** /billreceipt unlock —— 解锁准星指向的容器 */
    private static int doReceiptUnlock(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        var hit = sp.pick(6.0D, 0.0F, false);
        if (hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            sp.displayClientMessage(Component.literal("§c对准要解锁的容器"), false);
            return 0;
        }
        var pos = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
        var lock = com.tangtu.kjbb.StoreLocks.get(pos);
        String seal = com.tangtu.kjbb.StoreSeals.get(pos);
        int claims = com.tangtu.kjbb.StoreClaims.claimedTotal(pos);
        if (lock == null && (seal == null || seal.isEmpty()) && claims <= 0) {
            sp.displayClientMessage(Component.literal("§c这个容器既没锁也没封条，本来就是开放的"), false);
            return 0;
        }
        boolean owner = lock != null && lock.owner() != null
                && lock.owner().equalsIgnoreCase(sp.getScoreboardName());
        boolean op = sp.hasPermissions(2);
        if (lock != null && !owner && !op) {
            sp.displayClientMessage(Component.literal("§c不是你的容器"), false);
            return 0;
        }
        com.tangtu.kjbb.StoreLocks.unlock(pos);
        com.tangtu.kjbb.StoreSeals.clear(pos);
        com.tangtu.kjbb.StoreClaims.clear(pos);
        sp.displayClientMessage(Component.literal("§a已解锁并解封 " + pos.getX() + ", "
                + pos.getY() + ", " + pos.getZ() + "（原来的仓单已失效，可以正常开关了）"), false);
        return 1;
    }

    /** /billorders —— 看买家订单 */
    private static int doOrders(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                               String status) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                com.tangtu.kjbb.OrderBook.dump(status)), false);
        return 1;
    }

    /** /ticket list <价格> —— 主手票据挂卖 */
    private static int doTicketList(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, long price) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        var stack = sp.getMainHandItem();
        String kind = com.tangtu.kjbb.TicketMarket.kindOf(stack);
        if (kind == null) {
            sp.displayClientMessage(Component.literal("§c主手要拿着提单或仓单"), false);
            return 0;
        }
        if (com.tangtu.kjbb.item.StoreReceiptItem.isVoid(stack)) {
            sp.displayClientMessage(Component.literal("§c这张票据已作废，不能挂卖"), false);
            return 0;
        }
        String data = com.tangtu.kjbb.TicketMarket.encode(stack);
        if (data == null) {
            sp.displayClientMessage(Component.literal("§c票据数据不完整，不能挂卖"), false);
            return 0;
        }
        // 仓单：校验封条 + 冻结容器
        net.minecraft.core.BlockPos pos = com.tangtu.kjbb.TicketMarket.posOf(kind, data);
        if (pos != null && sp.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            if (!com.tangtu.kjbb.StoreSeals.matches(sl, pos)) {
                sp.displayClientMessage(Component.literal("§c容器内容与仓单记录不一致，不能挂卖"), false);
                return 0;
            }
            com.tangtu.kjbb.StoreLocks.lock(pos, "__MARKET__", false);
        }
        var row = com.tangtu.kjbb.TicketMarket.add(kind, sp.getScoreboardName(),
                Math.max(1L, price), data);
        stack.shrink(1);
        sp.displayClientMessage(Component.literal("§a[票据市场] 已挂卖 #" + row.id + "  "
                + Math.max(1L, price) + " 唐兔币  " + com.tangtu.kjbb.TicketMarket.describe(row)),
                false);
        sp.displayClientMessage(Component.literal("§7别人用 /ticket buy " + row.id
                + " 购买；/ticket cancel " + row.id + " 撤单"), false);
        System.out.println("[TangtuEconomical] 票据挂卖 #" + row.id + " kind=" + kind
                + " price=" + price + " seller=" + sp.getScoreboardName());
        return 1;
    }

    /** /ticket buy <id> —— 买下票据 */
    private static int doTicketBuy(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, long id) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        var row = com.tangtu.kjbb.TicketMarket.get(id);
        if (row == null || !com.tangtu.kjbb.TicketMarket.ON_SALE.equals(row.status)) {
            sp.displayClientMessage(Component.literal("§c这张票据不在售"), false);
            return 0;
        }
        String buyer = sp.getScoreboardName();
        if (com.tangtu.kjbb.BankBridge.money(buyer) < row.price) {
            sp.displayClientMessage(Component.literal("§c余额不足（需要 " + row.price
                    + " 唐兔币）"), false);
            return 0;
        }
        // 仓单：成交前再验一次封条
        if (sp.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            if (!com.tangtu.kjbb.TicketMarket.stillSealed(sl, row)) {
                com.tangtu.kjbb.TicketMarket.setStatus(id, com.tangtu.kjbb.TicketMarket.CANCELLED);
                sp.displayClientMessage(Component.literal(
                        "§c容器内容已被卖家改动，交易取消"), false);
                return 0;
            }
        }
        net.minecraft.world.item.ItemStack ticket = com.tangtu.kjbb.TicketMarket.rebuild(row.kind, row.data, buyer,
                sp.level().getGameTime());
        if (ticket.isEmpty()) {
            sp.displayClientMessage(Component.literal("§c票据重建失败（可能已失效）"), false);
            return 0;
        }
        com.tangtu.kjbb.BankBridge.takeMoney(buyer, row.price);
        com.tangtu.kjbb.Pay.credit(row.seller, row.price);
        com.tangtu.kjbb.TicketMarket.setStatus(id, com.tangtu.kjbb.TicketMarket.SOLD);
        // 仓单：锁转给买家
        net.minecraft.core.BlockPos pos = com.tangtu.kjbb.TicketMarket.posOf(row.kind, row.data);
        if (pos != null) {
            com.tangtu.kjbb.StoreLocks.transfer(pos, buyer);
        }
        if (!sp.getInventory().add(ticket)) {
            sp.drop(ticket, false);
        }
        sp.displayClientMessage(Component.literal("§a[票据市场] 已买下 #" + id + "  花费 "
                + row.price + " 唐兔币  " + com.tangtu.kjbb.TicketMarket.describe(row)), false);
        System.out.println("[TangtuEconomical] 票据成交 #" + id + " buyer=" + buyer
                + " price=" + row.price + " seller=" + row.seller);
        return 1;
    }

    /** /ticket cancel <id> —— 撤单，票据退回卖家 */
    private static int doTicketCancel(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, long id) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        var row = com.tangtu.kjbb.TicketMarket.get(id);
        if (row == null || !com.tangtu.kjbb.TicketMarket.ON_SALE.equals(row.status)) {
            sp.displayClientMessage(Component.literal("§c这张票据不在售"), false);
            return 0;
        }
        if (!row.seller.equalsIgnoreCase(sp.getScoreboardName())) {
            sp.displayClientMessage(Component.literal("§c不是你的挂卖"), false);
            return 0;
        }
        net.minecraft.world.item.ItemStack ticket = com.tangtu.kjbb.TicketMarket.rebuild(row.kind, row.data,
                sp.getScoreboardName(), sp.level().getGameTime());
        if (ticket.isEmpty()) {
            sp.displayClientMessage(Component.literal(
                    "§c撤单失败：票据重建不出来（kind=" + row.kind + " data=" + row.data + "）"), false);
            return 0;
        }
        com.tangtu.kjbb.TicketMarket.setStatus(id, com.tangtu.kjbb.TicketMarket.CANCELLED);
        net.minecraft.core.BlockPos pos = com.tangtu.kjbb.TicketMarket.posOf(row.kind, row.data);
        if (pos != null) {
            com.tangtu.kjbb.StoreLocks.transfer(pos, sp.getScoreboardName());
        }
        if (!ticket.isEmpty() && !sp.getInventory().add(ticket)) {
            sp.drop(ticket, false);
        }
        sp.displayClientMessage(Component.literal("§e已撤单 #" + id + "，票据退回背包"), false);
        return 1;
    }

    /** /billorder place <物品> <数量> <单价> —— 买家下单（自定价，钱先扣） */
    private static int doOrderPlace(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            String itemId, long count, long price) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        int want = (int) Math.max(1L, count);
        long unit = Math.max(1L, price);
        long total = unit * want;
        String buyer = sp.getScoreboardName();
        if (com.tangtu.kjbb.BankBridge.money(buyer) < total) {
            sp.displayClientMessage(Component.literal("§c余额不足：需要 " + total + " 唐兔币"), false);
            return 0;
        }
        net.minecraft.world.item.Item it =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.tryParse(itemId));
        if (it == null) {
            sp.displayClientMessage(Component.literal("§c物品不存在：" + itemId), false);
            return 0;
        }
        // 自己的绑定地址优先；没有才用附近提货点登记表的地址
        String addr = com.tangtu.kjbb.AddressBook.addressOf(sp);
        if (addr == null || addr.isEmpty()) {
            var reg = com.tangtu.kjbb.PortRegistry.pick(sp.blockPosition(), addr);
            if (reg != null && reg.address() != null && !reg.address().isEmpty()) {
                addr = reg.address();
            }
        }
        com.tangtu.kjbb.BankBridge.takeMoney(buyer, total);
        var row = com.tangtu.kjbb.OrderBook.add(buyer, itemId, want, unit, addr);
        sp.displayClientMessage(Component.literal("§a[下单] #" + row.id + "  " + itemId + " ×" + want
                + "  " + unit + " 唐兔币/个（共 " + total + " 唐兔币，已扣）"), false);
        sp.displayClientMessage(Component.literal("§7等卖家接单；30 分钟没人接自动退款"), false);
        System.out.println("[TangtuEconomical] 买家下单 #" + row.id + " " + itemId + " x" + want
                + " unit=" + unit + " buyer=" + buyer);
        com.tangtu.kjbb.MarketBroadcast.buyOrder(sp.getServer(), buyer, itemId, want, unit, total);
        return 1;
    }

    /** /billsupply（/billorder take）<订单号> —— 卖家接单：背包优先，仓库补，然后打包发给买家 */
    private static int doSupply(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                long orderId) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("§c需要在游戏内执行"), false);
            return 0;
        }
        if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return 0;
        }
        var row = com.tangtu.kjbb.OrderBook.get(orderId);
        if (row == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("§c没有 #" + orderId), false);
            return 0;
        }
        if (!com.tangtu.kjbb.OrderBook.PENDING.equals(row.status)) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§c订单 #" + orderId + " 状态是 " + row.status + "，不能接单"), false);
            return 0;
        }
        net.minecraft.world.item.Item it =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.tryParse(row.itemId));
        if (it == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("§c物品不存在"), false);
            return 0;
        }

        int fromBag = takeFromInventory(sp, it, row.count);
        int need = row.count - fromBag;
        int fromStore = 0;
        if (need > 0) {
            var ticker = com.tangtu.kjbb.TickerShip.nearestTicker(level, sp.blockPosition(), 64);
            if (ticker != null) {
                int stock = com.tangtu.kjbb.TickerStock.networkCount(level, ticker,
                        new net.minecraft.world.item.ItemStack(it));
                if (stock > 0) {
                    fromStore = com.tangtu.kjbb.TickerStock.extractFromWarehouse(level, ticker, it,
                            Math.min(need, stock));
                }
            }
        }
        if (fromBag + fromStore < row.count) {
            if (fromBag > 0) {
                giveBack(sp, it, fromBag);
            }
            if (fromStore > 0) {
                var tk = com.tangtu.kjbb.TickerShip.nearestTicker(level, sp.blockPosition(), 64);
                if (tk != null) {
                    com.tangtu.kjbb.TickerStock.insertIntoWarehouse(level, tk,
                            new net.minecraft.world.item.ItemStack(it, fromStore));
                }
            }
            final int tb = fromBag;
            final int ts = fromStore;
            sp.displayClientMessage(Component.literal("§c货不够：背包 " + tb + " + 仓库 " + ts
                    + "，接不了 " + row.count + " 个的单"), false);
            return 0;
        }

        // 买家是市场机器人 → 不用发货，直接入库 + 结账给玩家
        if (com.tangtu.kjbb.MarketBots.isBot(row.buyer)) {
            com.tangtu.kjbb.BotLedger.addStock(row.buyer, row.itemId, row.count);
            com.tangtu.kjbb.BotLedger.save();
            com.tangtu.kjbb.MarketPulse.record(row.itemId, row.unit);
            com.tangtu.kjbb.MarketPrice.onTrade(row.itemId, row.unit);
            com.tangtu.kjbb.KlineAggregator.onTrade(row.itemId, (int) Math.max(1L, row.unit), row.count);
            com.tangtu.kjbb.Pay.credit(sp.getScoreboardName(), row.total);
            // 交易税 1%（经济部收，从卖家所得中抽）
            try {
                com.tangtu.kjbb.RegulatorOps.tax(sp, row.total);
            } catch (Throwable ignored) {
                // ignore
            }
            com.tangtu.kjbb.OrderBook.setStatus(orderId, com.tangtu.kjbb.OrderBook.DONE,
                    sp.getScoreboardName());
            final long paid = row.total;
            final int cnt = row.count;
            final String who = row.buyer;
            sp.displayClientMessage(Component.literal(
                    "\u00a7a[\u6210\u4ea4] " + who + " \u6536\u4e0b\u4e86\u4f60\u7684 " + cnt
                            + " \u4e2a\uff0c" + paid + " \u5510\u5154\u5e01\u5df2\u5230\u8d26"), false);
            System.out.println("[TangtuEconomical] \u673a\u5668\u4eba\u6536\u8d2d #" + orderId
                    + " \u5356\u5bb6=" + sp.getScoreboardName() + " \u91d1\u989d=" + row.total);
            return 1;
        }

        String addr = row.address == null || row.address.isEmpty() ? row.buyer : row.address;
        boolean shipped = false;
        net.minecraft.core.BlockPos port = resolvePort(addr, row.buyer);
        if (port == null) {
            if (fromBag > 0) {
                giveBack(sp, it, fromBag);
            }
            sp.displayClientMessage(Component.literal(
                    "§c找不到买家的提货点（地址「" + addr + "」）。让买家先 /billport claim 或用 /shopaddr 登记"), false);
            return 0;
        }
        if (port != null) {
            java.util.List<net.minecraft.world.item.ItemStack> goods = new java.util.ArrayList<>();
            int left = row.count;
            while (left > 0) {
                int n = Math.min(left, Math.max(1, it.getDefaultMaxStackSize()));
                goods.add(new net.minecraft.world.item.ItemStack(it, n));
                left -= n;
            }
            shipped = com.tangtu.kjbb.AddressBook.deliverTo(
                    new com.tangtu.kjbb.AddressBook.PortInfo(level, port, addr), goods);
        }
        if (!shipped) {
            giveBack(sp, it, row.count);
            sp.displayClientMessage(Component.literal(
                    "§c买家提货点找不到/塞不进包裹，接单失败（货已退回）"), false);
            return 0;
        }

        com.tangtu.kjbb.OrderBook.setStatus(orderId, com.tangtu.kjbb.OrderBook.SUPPLIED,
                sp.getScoreboardName());
        com.tangtu.kjbb.OrderWatch.add(orderId, row.buyer, row.itemId, row.count, addr);
        final int fb = fromBag;
        final int fs = fromStore;
        sp.displayClientMessage(Component.literal("§a[接单] 订单 #" + orderId + "  " + row.itemId
                + " ×" + row.count + " 已发货至「" + addr + "」（背包 " + fb + " / 仓库 " + fs + "）"),
                false);
        System.out.println("[TangtuEconomical] 接单 #" + orderId + " 卖家=" + sp.getScoreboardName()
                + " bag=" + fromBag + " store=" + fromStore);
        return 1;
    }

    /** NPC 按钮用：把脚下的邮箱/蛙港绑给自己（不需要权限，只能绑自己） */
    private static int doClaim(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                               String address) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        String addr = (address == null || address.isBlank()) ? sp.getScoreboardName() : address;
        int r = doBind(ctx, sp.getScoreboardName(), addr);
        if (r > 0) {
            sp.displayClientMessage(Component.literal(
                    "§7以后你在发报机下单，包裹会送到这里"), false);
        }
        return r;
    }

    /** 绑定：把执行者（NPC/管理员）脚下或准星指向的邮箱/蛙港绑给某个玩家 */
    private static int doBind(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                              String playerName, String address) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§c需要在游戏内执行（站在邮箱/蛙港旁边）"), false);
            return 0;
        }
        net.minecraft.core.BlockPos bp = null;
        Object be = null;
        var hit = sp.pick(6.0D, 0.0F, false);
        if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            bp = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
            be = sp.level().getBlockEntity(bp);
        }
        String type = com.tangtu.kjbb.PortRegistry.typeOf(be);
        if (type.isEmpty()) {
            double best = Double.MAX_VALUE;
            for (var dp : net.minecraft.core.BlockPos.betweenClosed(
                    sp.blockPosition().offset(-8, -4, -8),
                    sp.blockPosition().offset(8, 4, 8))) {
                var cand = sp.level().getBlockEntity(dp);
                String cy = com.tangtu.kjbb.PortRegistry.typeOf(cand);
                if (cy.isEmpty()) {
                    continue;
                }
                double d = sp.blockPosition().distSqr(dp);
                if (d < best) {
                    best = d;
                    bp = dp.immutable();
                    be = cand;
                    type = cy;
                }
            }
        }
        if (bp == null || type.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§c附近没找到白色邮箱或蛙港"), false);
            return 0;
        }
        com.tangtu.kjbb.PortRegistry.bind(playerName, address, bp, type);
        final String msg = "§a已把「" + address + "」[" + type + "] 绑定给 " + playerName
                + "（" + bp.getX() + ", " + bp.getY() + ", " + bp.getZ() + "）";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }


    /** 可点击按钮 */
    private static net.minecraft.network.chat.MutableComponent button(String label, String command) {
        return Component.literal(label).withStyle(st -> st
                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                        Component.literal("\u00a77" + command))));
    }

    /** /billorder pending —— 列出我锁定了但没发货的订单 */
    private static int doPendingShipments(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null) {
            return 0;
        }
        String me = sp.getScoreboardName();
        var list = com.tangtu.kjbb.OrderBook.byStatus(com.tangtu.kjbb.OrderBook.ACCEPTED);
        int n = 0;
        sp.displayClientMessage(Component.literal("\u00a76\u5f85\u53d1\u8d27\u8ba2\u5355\uff1a"), false);
        for (var r : list) {
            if (r.seller == null || !r.seller.equals(me)) {
                continue;
            }
            n++;
            sp.displayClientMessage(Component.literal("\u00a77  #" + r.id + "  "
                    + r.itemId + " x" + r.count + "  \u4e70\u5bb6=" + r.buyer), false);
        }
        if (n == 0) {
            sp.displayClientMessage(Component.literal("\u00a77  \uff08\u6ca1\u6709\uff09"), false);
        } else {
            sp.displayClientMessage(Component.literal(
                    "\u00a7e\u53d1\u8d27\uff1a/billorder ship <\u5355\u53f7>\uff08\u9700\u7ad9\u5728\u4ed3\u50a8\u53d1\u62a5\u673a\u65c1\uff09"), false);
        }
        return n;
    }

    /** /billorder ship <id> —— 用身边的发报机发货 */
    private static int doShipOrder(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, long orderId,
            net.minecraft.core.BlockPos forcedPos) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null || !(sp.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return 0;
        }
        var row = com.tangtu.kjbb.OrderBook.get(orderId);
        if (row == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("\u00a7c\u6ca1\u6709\u8ba2\u5355 #" + orderId), false);
            return 0;
        }
        if (!com.tangtu.kjbb.OrderBook.ACCEPTED.equals(row.status)
                || row.seller == null || !row.seller.equals(sp.getScoreboardName())) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "\u00a7c\u8fd9\u5f20\u5355\u4e0d\u662f\u4f60\u9501\u5b9a\u7684\uff08\u5f53\u524d " + row.status + "\uff09"), false);
            return 0;
        }
        // 优先用界面传进来的发报机坐标；否则退回身边 16 格扫描
        net.minecraft.core.BlockPos tickerPos = forcedPos;
        if (tickerPos != null && !com.tangtu.kjbb.TickerStock.isTicker(level, tickerPos)) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "\u00a7c\u6307\u5b9a\u4f4d\u7f6e\u4e0d\u662f\u4ed3\u50a8\u53d1\u62a5\u673a"), false);
            return 0;
        }
        var base = sp.blockPosition();
        for (int dx = -16; dx <= 16 && tickerPos == null; dx++) {
            for (int dy = -8; dy <= 8 && tickerPos == null; dy++) {
                for (int dz = -16; dz <= 16; dz++) {
                    var q = base.offset(dx, dy, dz);
                    if (com.tangtu.kjbb.TickerStock.isTicker(level, q)) {
                        tickerPos = q;
                        break;
                    }
                }
            }
        }
        if (tickerPos == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "\u00a7c\u9644\u8fd1 16 \u683c\u5185\u6ca1\u6709\u4ed3\u50a8\u53d1\u62a5\u673a"), false);
            return 0;
        }
        // 机械动力的物流链：发报机旁必须有打包机，否则 Create 无处打包
        boolean hasPackager = false;
        for (int dx = -8; dx <= 8 && !hasPackager; dx++) {
            for (int dy = -4; dy <= 4 && !hasPackager; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    var q = tickerPos.offset(dx, dy, dz);
                    var bs = level.getBlockState(q);
                    if (bs.getBlock().builtInRegistryHolder().key().location().getPath()
                            .equals("packager")) {
                        hasPackager = true;
                        break;
                    }
                }
            }
        }
        if (!hasPackager) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "\u00a7c\u53d1\u62a5\u673a\u65c1\u8fb9\u6ca1\u6709\u6253\u5305\u673a\uff0c\u673a\u68b0\u52a8\u529b\u65e0\u6cd5\u6253\u5305\uff08\u53d1\u8d27\u9700\u8981\uff1a\u53d1\u62a5\u673a + \u6253\u5305\u673a + \u4ed3\u50a8\u94fe\u63a5\u7ad9\uff09"), false);
            return 0;
        }
        var stock = com.tangtu.kjbb.TickerStock.stock(level, tickerPos);
        int have = stock.getOrDefault(row.itemId, 0);
        if (have < row.count) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "\u00a7c\u4ed3\u5e93\u91cc\u53ea\u6709 " + have + " / " + row.count), false);
            return 0;
        }
        // 买家是市场机器人 → 直接从发报机仓库扣货并结账，不打包不发提单
        try {
            if (com.tangtu.kjbb.MarketBots.isBot(row.buyer)) {
                int took = com.tangtu.kjbb.TickerStock.extractFromWarehouse(
                        level, tickerPos,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                net.minecraft.resources.ResourceLocation.tryParse(row.itemId)),
                        row.count);
                if (took < row.count) {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "\u00a7c\u4ed3\u5e93\u53d6\u8d27\u5931\u8d25"), false);
                    return 0;
                }
                com.tangtu.kjbb.BotLedger.addStock(row.buyer, row.itemId, row.count);
                com.tangtu.kjbb.Pay.credit(sp.getScoreboardName(), row.total);
                com.tangtu.kjbb.OrderBook.setStatus(row.id, com.tangtu.kjbb.OrderBook.DONE,
                        sp.getScoreboardName());
                sp.displayClientMessage(Component.literal(
                        "\u00a7a[\u6210\u4ea4] " + row.buyer + " \u6536\u4e0b\u4e86\u4f60\u7684 "
                                + row.count + " \u4e2a\uff0c" + row.total
                                + " \u5510\u5154\u5e01\u5df2\u5230\u8d26"), false);
                return 1;
            }
        } catch (Throwable ignored) {
            // 回落到正常发货
        }
        String target = row.address == null || row.address.isEmpty() ? row.buyer : row.address;
        boolean ok = com.tangtu.kjbb.net.TickerBillNetwork.mailBillTo(
                level, sp, tickerPos, row.itemId, row.count, row.buyer, target, row.id);
        if (!ok) {
            ctx.getSource().sendSuccess(() -> Component.literal("\u00a7c\u53d1\u8d27\u5931\u8d25\uff08\u4e70\u5bb6\u63d0\u8d27\u70b9\u5f02\u5e38\uff09"), false);
            return 0;
        }
        com.tangtu.kjbb.OrderBook.setStatus(row.id, com.tangtu.kjbb.OrderBook.SUPPLIED,
                sp.getScoreboardName());
        com.tangtu.kjbb.OrderWatch.add(row.id, row.buyer, row.itemId, row.count, target);
        sp.displayClientMessage(Component.literal("\u00a7a[\u53d1\u8d27] \u8ba2\u5355 #"
                + row.id + " \u5df2\u53d1\u51fa\uff08\u63d0\u5355\u5df2\u7ed9\u4e70\u5bb6\uff09"), false);
        return 1;
    }

    /** /billorder shipnext —— 把第一张锁定给我的订单从身边发报机发出去 */
    private static int doShipNext(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            net.minecraft.core.BlockPos pos) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null || !(sp.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return 0;
        }
        String me = sp.getScoreboardName();
        long orderId = -1L;
        for (var r : com.tangtu.kjbb.OrderBook.byStatus(com.tangtu.kjbb.OrderBook.ACCEPTED)) {
            if (r.seller != null && r.seller.equals(me)) {
                orderId = r.id;
                break;
            }
        }
        if (orderId < 0L) {
            sp.displayClientMessage(Component.literal("\u00a77\u6ca1\u6709\u5f85\u53d1\u8d27\u7684\u8ba2\u5355"), false);
            return 0;
        }
        final long id = orderId;
        return doShipOrder(ctx, id, pos);
    }
}
