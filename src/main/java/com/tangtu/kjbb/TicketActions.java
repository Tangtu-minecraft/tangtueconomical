package com.tangtu.kjbb;

import com.tangtu.kjbb.item.StoreReceiptItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 票据市场动作（命令与界面共用）：
 *   buy    —— 买下挂卖的提单/仓单
 *   cancel —— 卖家撤单
 */
public final class TicketActions {

    private TicketActions() {
    }

    public static String buy(ServerPlayer sp, long id) {
        TicketMarket.Row row = TicketMarket.get(id);
        if (row == null || !TicketMarket.ON_SALE.equals(row.status)) {
            return "§c这张票据不在售";
        }
        String buyer = sp.getScoreboardName();
        if (BankBridge.money(buyer) < row.price) {
            return "§c余额不足（需要 " + row.price + " 唐兔币）";
        }
        if (sp.level() instanceof ServerLevel sl && !TicketMarket.stillSealed(sl, row)) {
            TicketMarket.setStatus(id, TicketMarket.CANCELLED);
            return "§c容器内容已被卖家改动，交易取消";
        }
        // 记名仓单：所有权随交易转移（rebuild 用 buyer 重写 owner）
        ItemStack ticket = TicketMarket.rebuild(row.kind, row.data, buyer,
                sp.level().getGameTime());
        if (ticket.isEmpty()) {
            return "§c票据重建失败（可能已失效）";
        }
        if (!com.tangtu.kjbb.Pay.pay(sp, row.price)) {
            return "§c余额不足（钱包 + 硬币 + 保险柜合计不够）";
        }
        com.tangtu.kjbb.Pay.credit(row.seller, row.price);
        // 票据成交税（卖家承担）
        try {
            com.tangtu.kjbb.RegulatorOps.taxBot(row.seller, row.price);
        } catch (Throwable ignored) {
            // ignore
        }
        TicketMarket.setStatus(id, TicketMarket.SOLD);
        // 通知卖家
        try {
            var srv = sp.getServer();
            if (srv != null) {
                for (ServerPlayer seller : srv.getPlayerList().getPlayers()) {
                    if (row.seller != null && row.seller.equalsIgnoreCase(seller.getScoreboardName())) {
                        seller.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a77[\u7968\u636e\u5e02\u573a] \u00a7a\u4f60\u7684\u7968\u636e #"
                                        + id + " \u5df2\u88ab\u4e70\u8d70\uff0c\u6210\u4ea4 "
                                        + row.price + " \u5510\u5154\u5e01\uff08\u4e70\u5bb6\uff1a"
                                        + buyer + "\uff09"), false);
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        BlockPos pos = TicketMarket.posOf(row.kind, row.data);
        if (pos != null) {
            StoreLocks.transfer(pos, buyer);
        }
        if (!sp.getInventory().add(ticket)) {
            sp.drop(ticket, false);
        }
        // 买家提示
        try {
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "\u00a7a[\u7968\u636e\u5e02\u573a] \u4f60\u4e70\u4e0b\u4e86\u7968\u636e #" + id
                            + "\uff08" + TicketMarket.describe(row) + "\uff09\uff0c\u82b1\u8d39 "
                            + row.price + " \u5510\u5154\u5e01\uff0c\u7968\u636e\u5df2\u653e\u8fdb\u80cc\u5305"),
                    false);
        } catch (Throwable ignored) {
            // ignore
        }
        // 提单成交 → 里面物品的单件价格计入 K 线
        if (TicketMarket.KIND_BILL.equals(row.kind)) {
            try {
                String[] f = row.data.split(":");
                long serial = Long.parseLong(f[0].trim());
                var bd = com.tangtu.kjbb.item.BillRegistry.dataOf(serial);
                if (bd != null && bd.count() > 0) {
                    int unit = (int) Math.max(1L, row.price / bd.count());
                    com.tangtu.kjbb.KlineAggregator.onTrade(bd.itemId(), unit, bd.count());
                    com.tangtu.kjbb.MarketSync.sync(bd.itemId(), unit);
                    System.out.println("[TangtuEconomical] 票据成交记入K线 " + bd.itemId()
                            + " unit=" + unit);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        System.out.println("[TangtuEconomical] 票据成交 #" + id + " buyer=" + buyer
                + " price=" + row.price + " seller=" + row.seller);
        return "§a[票据市场] 已买下 #" + id + "  花费 " + row.price + " 唐兔币  "
                + TicketMarket.describe(row);
    }

    public static String cancel(ServerPlayer sp, long id) {
        TicketMarket.Row row = TicketMarket.get(id);
        if (row == null || !TicketMarket.ON_SALE.equals(row.status)) {
            return "§c这张票据不在售";
        }
        if (!row.seller.equalsIgnoreCase(sp.getScoreboardName())) {
            return "§c不是你的挂卖";
        }
        ItemStack ticket = TicketMarket.rebuild(row.kind, row.data, sp.getScoreboardName(),
                sp.level().getGameTime());
        TicketMarket.setStatus(id, TicketMarket.CANCELLED);
        BlockPos pos = TicketMarket.posOf(row.kind, row.data);
        if (pos != null) {
            StoreLocks.transfer(pos, sp.getScoreboardName());
        }
        if (!ticket.isEmpty() && !sp.getInventory().add(ticket)) {
            sp.drop(ticket, false);
        }
        System.out.println("[TangtuEconomical] 票据撤单 #" + id + " by " + sp.getScoreboardName());
        return "§e已撤单 #" + id + "，票据退回背包";
    }

    /** 兑付仓单：按票面记录从容器取货，成功后票作废并重贴封条 */
    public static String redeem(ServerPlayer sp, net.minecraft.core.BlockPos pos, long rid) {
        if (!(sp.level() instanceof ServerLevel level)) {
            return "§c只能在服务端执行";
        }
        // 找背包里对这个容器的第一张有效仓单
        ItemStack held = ItemStack.EMPTY;
        int slot = -1;
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            if (st.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
                continue;
            }
            if (StoreReceiptItem.isVoid(st)) {
                continue;
            }
            String ps = StoreReceiptItem.posText(st);
            String want = pos.getX() + "," + pos.getY() + "," + pos.getZ();
            if (!want.equals(ps)) {
                continue;
            }
            if (rid > 0 && StoreReceiptItem.rid(st) != rid) {
                continue;
            }
            held = st;
            slot = i;
            break;
        }
        if (held.isEmpty()) {
            return "§c你背包里没有这个容器的仓单";
        }
        // 维度校验
        String rdim = StoreReceiptItem.dim(held);
        String here = level.dimension().location().toString();
        if (rdim != null && !rdim.isEmpty() && !rdim.equals(here)) {
            return "§c这张仓单属于「" + rdim + "」，你现在在「" + here + "」";
        }
        // 距离校验：8 格内
        double d2 = sp.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (d2 > 64.0) {
            return "§c离仓库太远（需站在 8 格内，当前约 "
                    + Math.round(Math.sqrt(d2)) + " 格）";
        }
        var lines = StoreReceiptItem.readRecord(held);
        if (lines.isEmpty()) {
            return "§c这张仓单没有物品记录";
        }
        // 逐项取货，任一项不够就整体回滚
        var be = level.getBlockEntity(pos);
        if (be == null) {
            return "§c容器不在了";
        }
        var h = net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK
                .getCapability(level, pos, be.getBlockState(), be, null);
        if (h == null) {
            return "§c这里不是容器";
        }
        int done = 0;
        for (var line : lines) {
            var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.tryParse(line.itemId()));
            if (it == null) {
                continue;
            }
            int want = line.count();
            int got = 0;
            for (int i = 0; i < h.getSlots() && got < want; i++) {
                ItemStack st = h.getStackInSlot(i);
                if (st == null || st.isEmpty() || st.getItem() != it) {
                    continue;
                }
                ItemStack ex = h.extractItem(i, want - got, false);
                if (ex != null && !ex.isEmpty()) {
                    got += ex.getCount();
                }
            }
            if (got < want) {
                // 回滚：把已取的塞回去
                if (got > 0) {
                    int left = got;
                    for (int i = 0; i < h.getSlots() && left > 0; i++) {
                        ItemStack rest = h.insertItem(i, new ItemStack(it, left), false);
                        left = rest == null || rest.isEmpty() ? 0 : rest.getCount();
                    }
                }
                return "§c容器里 " + StoreReceiptItem.niceName(line.itemId()) + " 不足（需要 "
                        + want + "，取到 " + got + "）";
            }
            done += got;
            // 货物寄到买家绑定的邮箱/蛙港；没绑地址才退回背包
            java.util.List<ItemStack> goods = new java.util.ArrayList<>();
            int leftGive = got;
            while (leftGive > 0) {
                int n = Math.min(leftGive, Math.max(1, it.getDefaultMaxStackSize()));
                goods.add(new ItemStack(it, n));
                leftGive -= n;
            }
            boolean mailed = false;
            try {
                mailed = com.tangtu.kjbb.PlayerMail.sendToPlayer(level, sp, goods);
            } catch (Throwable ignored) {
                mailed = false;
            }
            if (mailed) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7a[\u4ed3\u5355] " + got + " \u4ef6\u5df2\u5bc4\u5230\u4f60\u7684\u63d0\u8d27\u70b9"),
                        false);
            } else {
                for (ItemStack give : goods) {
                    if (!sp.getInventory().add(give)) {
                        sp.drop(give, false);
                    }
                }
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e[\u4ed3\u5355] \u672a\u627e\u5230\u63d0\u8d27\u70b9\uff0c\u8d27\u7269\u5df2\u653e\u5165\u80cc\u5305"),
                        false);
            }
        }
        // 票作废 + 释放占用
        StoreReceiptItem.setVoid(held);
        for (var line : lines) {
            StoreClaims.release(pos, line.itemId(), line.count());
        }
        if (StoreClaims.claimedTotal(pos) <= 0) {
            // 全部兑付完 → 解封解锁，仓库恢复开放
            StoreSeals.clear(pos);
            StoreLocks.unlock(pos);
            StoreClaims.clear(pos);
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§a全部仓单已兑付，仓库恢复开放"), false);
        } else {
            // 还有别的票 → 重贴封条（别让巡检误杀）
            StoreSeals.set(pos, StoreSeals.sealOf(StoreSeals.snapshot(level, pos)));
        }
        System.out.println("[TangtuEconomical] 仓单兑付 " + pos + " 共 " + done + " 件 by "
                + sp.getScoreboardName());
        return "§a已兑付 " + done + " 件货物，这张仓单已作废";
    }

    /** 主手仓单拆出「某物品 ×N」一张小票，原票相应减少 */
    public static String split(ServerPlayer sp, String itemId, int want) {
        ItemStack stack = sp.getMainHandItem();
        if (stack == null || stack.isEmpty()
                || stack.getItem() != com.tangtu.kjbb.item.KjbbItems.STORE_RECEIPT.get()) {
            return "§c主手要拿着仓单";
        }
        if (StoreReceiptItem.isVoid(stack)) {
            return "§c这张仓单已作废";
        }
        var lines = StoreReceiptItem.readRecord(stack);
        if (lines.isEmpty()) {
            return "§c这张仓单没有物品记录";
        }
        String want0 = itemId == null ? "" : itemId.trim();
        if (want0.isEmpty()) {
            if (lines.size() != 1) {
                return "§c这张仓单有多种物品，请写清要拆哪种：/ticket split <物品> <数量>";
            }
            want0 = lines.get(0).itemId();
        }
        int has = 0;
        for (var l : lines) {
            if (l.itemId().equalsIgnoreCase(want0)) {
                has += l.count();
            }
        }
        if (has <= 0) {
            return "§c这张仓单里没有 " + StoreReceiptItem.niceName(want0);
        }
        int n = Math.max(1, want);
        if (n >= has) {
            return "§c拆出的数量要小于票面（票面 " + has + "，你填 " + n + "）";
        }
        // 原票扣减
        java.util.List<StoreReceiptItem.Line> rest = new java.util.ArrayList<>();
        int left = n;
        for (var l : lines) {
            if (left > 0 && l.itemId().equalsIgnoreCase(want0)) {
                int take = Math.min(left, l.count());
                left -= take;
                int remain = l.count() - take;
                if (remain > 0) {
                    rest.add(new StoreReceiptItem.Line(l.itemId(), remain));
                }
            } else {
                rest.add(l);
            }
        }
        // 小票
        String posText = StoreReceiptItem.posText(stack);
        net.minecraft.core.BlockPos pos;
        try {
            String[] f = posText.split(",");
            pos = new net.minecraft.core.BlockPos(Integer.parseInt(f[0].trim()),
                    Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()));
        } catch (Throwable e) {
            return "§c仓单位置数据损坏";
        }
        String owner = sp.getScoreboardName();
        long now = sp.level().getGameTime();
        java.util.Map<String, Integer> small = new java.util.LinkedHashMap<>();
        small.put(want0, n);
        ItemStack part = StoreReceiptItem.create(pos, owner, now, small);
        StoreReceiptItem.setBearer(part, StoreReceiptItem.isBearer(stack));
        StoreReceiptItem.setDim(part, StoreReceiptItem.dim(stack));
        // 写回原票
        StoreReceiptItem.writeRecord(stack, pos, owner, now, rest);
        if (!sp.getInventory().add(part)) {
            sp.drop(part, false);
        }
        return "§a已拆出 " + StoreReceiptItem.niceName(want0) + " ×" + n
                + "，原票剩 " + StoreReceiptItem.niceName(want0) + " ×" + (has - n);
    }

    /** 主手票据挂卖 */
    public static String listHeld(ServerPlayer sp, long price) {
        ItemStack stack = sp.getMainHandItem();
        String kind = TicketMarket.kindOf(stack);
        if (kind == null) {
            return "§c主手要拿着提单或仓单";
        }
        if (StoreReceiptItem.isVoid(stack)) {
            return "§c这张票据已作废，不能挂卖";
        }
        String data = TicketMarket.encode(stack);
        if (data == null) {
            return "§c票据数据不完整，不能挂卖";
        }
        BlockPos pos = TicketMarket.posOf(kind, data);
        if (pos != null && sp.level() instanceof ServerLevel sl) {
            if (!StoreSeals.matches(sl, pos)) {
                return "§c容器内容与仓单记录不一致，不能挂卖";
            }
            StoreLocks.lock(pos, "__MARKET__", false);
        }
        long unit = Math.max(1L, price);
        TicketMarket.Row row = TicketMarket.add(kind, sp.getScoreboardName(), unit, data);
        stack.shrink(1);
        System.out.println("[TangtuEconomical] 票据挂卖 #" + row.id + " kind=" + kind
                + " price=" + unit + " seller=" + sp.getScoreboardName());
        return "§a[票据市场] 已挂卖 #" + row.id + "  " + unit + " 唐兔币  "
                + TicketMarket.describe(row);
    }

    /** 按背包槽位挂卖票据（0..35） */
    public static String listSlot(ServerPlayer sp, int slot, long price) {
        if (slot < 0 || slot >= sp.getInventory().getContainerSize()) {
            return "\u00a7c\u69fd\u4f4d\u4e0d\u5b58\u5728";
        }
        ItemStack stack = sp.getInventory().getItem(slot);
        if (stack == null || stack.isEmpty()) {
            return "\u00a7c\u8fd9\u4e2a\u69fd\u4f4d\u662f\u7a7a\u7684";
        }
        String kind = com.tangtu.kjbb.TicketMarket.kindOf(stack);
        if (kind == null) {
            return "\u00a7c\u8fd9\u4e2a\u69fd\u4f4d\u4e0d\u662f\u7968\u636e";
        }
        if (com.tangtu.kjbb.item.StoreReceiptItem.isVoid(stack)) {
            return "\u00a7c\u8fd9\u5f20\u7968\u636e\u5df2\u4f5c\u5e9f\uff0c\u4e0d\u80fd\u6302\u5356";
        }
        String data = com.tangtu.kjbb.TicketMarket.encode(stack);
        if (data == null) {
            return "\u00a7c\u7968\u636e\u6570\u636e\u4e0d\u5b8c\u6574\uff0c\u4e0d\u80fd\u6302\u5356";
        }
        BlockPos pos = com.tangtu.kjbb.TicketMarket.posOf(kind, data);
        if (pos != null && sp.level() instanceof ServerLevel sl) {
            if (!com.tangtu.kjbb.StoreSeals.matches(sl, pos)) {
                return "\u00a7c\u5bb9\u5668\u5185\u5bb9\u4e0e\u7968\u636e\u8bb0\u5f55\u4e0d\u4e00\u81f4\uff0c\u4e0d\u80fd\u6302\u5356";
            }
            com.tangtu.kjbb.StoreLocks.lock(pos, "__MARKET__", false);
        }
        long unit = Math.max(1L, price);
        com.tangtu.kjbb.TicketMarket.Row row = com.tangtu.kjbb.TicketMarket.add(
                kind, sp.getScoreboardName(), unit, data);
        stack.shrink(1);
        System.out.println("[TangtuEconomical] \u7968\u636e\u6302\u5356(\u69fd\u4f4d" + slot + ") #"
                + row.id + " kind=" + kind + " price=" + unit
                + " seller=" + sp.getScoreboardName());
        return "\u00a7a[\u7968\u636e\u5e02\u573a] \u5df2\u6302\u5356 #" + row.id + "  "
                + unit + " \u5510\u5154\u5e01  " + com.tangtu.kjbb.TicketMarket.describe(row);
    }
}
