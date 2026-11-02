package com.tangtu.kjbb;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 挂单簿：config/kjbb/offers.txt
 *
 * 格式：id|卖家|物品|数量|单价|状态|创建时间
 * 状态：OPEN / FILLED / CANCELLED
 *
 * 挂单时货从卖家的发报机仓库扣出，由系统暂存；
 * 成交时打包寄给买家提货点，撤单时退回卖家背包。
 */
public final class OfferBook {

    public static final String OPEN = "OPEN";
    public static final String FILLED = "FILLED";
    public static final String CANCELLED = "CANCELLED";

    public static final class Row {
        public long id;
        public String seller;
        public String itemId;
        public int count;
        public long price;
        public String status;
        public long created;

        public Row(long id, String seller, String itemId, int count, long price,
                   String status, long created) {
            this.id = id;
            this.seller = seller;
            this.itemId = itemId;
            this.count = count;
            this.price = price;
            this.status = status;
            this.created = created;
        }
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static long nextId = 1L;
    private static boolean loaded = false;

    private OfferBook() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("offers.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_offers.txt");
        }
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        ROWS.clear();
        nextId = 1L;
        Path p = file();
        if (!Files.exists(p)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line == null || line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length < 7) {
                    continue;
                }
                try {
                    long id = Long.parseLong(f[0].trim());
                    ROWS.add(new Row(id, f[1].trim(), f[2].trim(),
                            Integer.parseInt(f[3].trim()), Long.parseLong(f[4].trim()),
                            f[5].trim(), Long.parseLong(f[6].trim())));
                    if (id >= nextId) {
                        nextId = id + 1;
                    }
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读挂单簿失败: " + t);
        }
    }

    public static synchronized void save() {
        StringBuilder sb = new StringBuilder("# id|卖家|物品|数量|单价|状态|创建时间\n");
        for (Row r : ROWS) {
            sb.append(r.id).append('|').append(r.seller).append('|').append(r.itemId).append('|')
                    .append(r.count).append('|').append(r.price).append('|').append(r.status)
                    .append('|').append(r.created).append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写挂单簿失败: " + t);
        }
    }

    public static synchronized Row add(String seller, String itemId, int count, long price) {
        load();
        // 黑名单物品（基岩/命令方块/背包升级…）一律不允许上架
        if (com.tangtu.kjbb.Tradability.banned(itemId)) {
            Row dead = new Row(nextId++, seller, itemId, 0, price, CANCELLED, ServerClock.now());
            ROWS.add(dead);
            save();
            try {
                DebugLog.log("拦截上架：" + seller + " 试图卖 " + itemId + "（黑名单物品）");
            } catch (Throwable ignored) {
                // ignore
            }
            return dead;
        }
                // 单号上限 99999：超过就从 1 重新开始
        if (nextId > 99999L) {
            nextId = 1L;
            int guard = 0;
            while (get(nextId) != null && guard++ < 100000) {
                nextId++;
                if (nextId > 99999L) {
                    nextId = 1L;
                }
            }
        }
Row r = new Row(nextId++, seller, itemId, count, price, OPEN, ServerClock.now());
        ROWS.add(r);
        save();
        return r;
    }

    /**
     * 玩家买玩家的挂单（方案 B）。
     *
     * 资金：买家钱包 → 卖家钱包（不收税，和商品买单一致）
     * 物品：直接进买家背包，背包满就掉在脚下，不吞货
     */
    /** 面对面交付：买卖双方同维度 8 格内才算 */
    /**
     * 从卖家身上取货：优先在线玩家背包，其次仓库。
     * 返回实际取到的物品堆；取不到返回 null。
     */
    /** 公开版取货（供国民经济调用）：返回实取件数 */
    public static int takeFromSellerPublic(String seller, String itemId, int count) {
        try {
            var st = takeFromSeller(seller, itemId, count);
            return st == null ? 0 : st.getCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static net.minecraft.world.item.ItemStack takeFromSeller(String seller, String itemId, int count) {
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null) {
                return null;
            }
            net.minecraft.world.item.Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.tryParse(itemId));
            if (it == null) {
                return null;
            }
            int left = count;
            // ① 机器人：货在账本里，直接扣账本
            try {
                if (com.tangtu.kjbb.Ledger.isBot(seller)) {
                    int have = com.tangtu.kjbb.BotLedger.stock(seller, itemId);
                    if (have < count) {
                        return null;
                    }
                    com.tangtu.kjbb.BotLedger.addStock(seller, itemId, -count);
                    return new net.minecraft.world.item.ItemStack(it, count);
                }
            } catch (Throwable ignored) {
                // 落到背包逻辑
            }
            // ② 在线卖家：直接从背包取
            var sp = srv.getPlayerList().getPlayerByName(seller);
            if (sp != null) {
                var inv = sp.getInventory();
                for (int k = 0; k < inv.getContainerSize() && left > 0; k++) {
                    var st = inv.getItem(k);
                    if (st == null || st.isEmpty() || st.getItem() != it) {
                        continue;
                    }
                    int n = Math.min(left, st.getCount());
                    st.shrink(n);
                    left -= n;
                }
                try {
                    sp.inventoryMenu.broadcastChanges();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            // ② 背包不够：从仓库补
            if (left > 0) {
                try {
                    Integer fromStore = com.tangtu.kjbb.TickerStock.takeFromAnyWarehouse(seller, it, left);
                    if (fromStore != null && fromStore > 0) {
                        left -= fromStore;
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            int got = count - left;
            if (got <= 0) {
                return null;
            }
            return new net.minecraft.world.item.ItemStack(it, got);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean nearEachOther(String buyer, String seller) {
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null) {
                return false;
            }
            var b = srv.getPlayerList().getPlayerByName(buyer);
            var s = srv.getPlayerList().getPlayerByName(seller);
            if (b == null || s == null) {
                return false;
            }
            if (b.serverLevel() != s.serverLevel()) {
                return false;
            }
            return b.distanceToSqr(s) <= 64.0;
        } catch (Throwable ignored) {
            return false;
        }
    }
    public static synchronized String buy(String buyer, long offerId, int want) {
        load();
        Row r = get(offerId);
        if (r == null || !OPEN.equals(r.status)) {
            return "§c这个挂单不可交易";
        }
        if (buyer == null || buyer.isEmpty()) {
            return "§c买家无效";
        }
        if (buyer.equals(r.seller)) {
            return "§c不能买自己的挂单";
        }
        int take = Math.min(Math.max(1, want), r.count);
        long cost = r.price * take;
        long have = com.tangtu.kjbb.BankBridge.money(buyer);
        if (have < cost) {
            return "§c唐兔币不够：需要 " + cost + "，你有 " + have;
        }
        // 成交前先从卖家取货（挂单不扣货，这里必须扣，否则等于凭空造货）
        net.minecraft.world.item.ItemStack gotGoods = takeFromSeller(r.seller, r.itemId, take);
        if (gotGoods == null || gotGoods.isEmpty() || gotGoods.getCount() < take) {
            if (gotGoods != null && !gotGoods.isEmpty()) {
                return null;   // 取到的货要还回去，调用方不该走到这里
            }
            return "§c卖家货不足（或不在线），这单成交不了";
        }
        // 扣钱给卖家
        com.tangtu.kjbb.BankBridge.takeMoney(buyer, cost);
        com.tangtu.kjbb.BankBridge.addMoney(r.seller, cost);
        // 面对面交付：双方站得近就直接给货，不打包不寄送
        boolean handMode = nearEachOther(buyer, r.seller)
                || com.tangtu.kjbb.Ledger.isBot(r.seller);   // 机器人没有位置，直接给货
        if (handMode) {
            try {
                var srvH = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                var spH = srvH == null ? null : srvH.getPlayerList().getPlayerByName(buyer);
                var itemH = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .get(net.minecraft.resources.ResourceLocation.tryParse(r.itemId));
                if (spH != null && itemH != null) {
                    if (!spH.getInventory().add(gotGoods)) {
                        spH.drop(gotGoods, false);
                    }
                    try {
                        spH.inventoryMenu.broadcastChanges();
                    } catch (Throwable ignored4) {
                        // ignore
                    }
                    r.count -= take;
                    if (r.count <= 0) {
                        r.status = FILLED;
                    }
                    save();
                    try {
                        com.tangtu.kjbb.BotTrades.log(buyer, r.seller, "面对面#" + r.id, take, r.price, "交易行");
                        DebugLog.log("面对面成交 #" + r.id + "：" + buyer + " 从 " + r.seller + " 手上拿走 " + r.itemId + " ×" + take);
                    } catch (Throwable ignored2) {
                        // ignore
                    }
                    return "§a面对面交易成功：" + r.itemId + " ×" + take
                            + "，付 " + cost + " 唐兔币";
                }
            } catch (Throwable ignored3) {
                // 落到物流分支
            }
        }
        
        // 方案 B：强制走物流 —— 货只送到买家绑定的提货点，不进背包
        int left = take;
        boolean delivered = false;
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv != null) {
                var sp = srv.getPlayerList().getPlayerByName(buyer);
                var level = sp != null ? sp.serverLevel() : srv.overworld();
                net.minecraft.core.BlockPos port = com.tangtu.kjbb.AddressBook.get(buyer);
                if (port == null) {
                    return "§c你还没有绑定提货点，请先站到蛙港/邮箱旁用 /billaddr 绑定";
                }
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .get(net.minecraft.resources.ResourceLocation.tryParse(r.itemId));
                if (item == null) {
                    return "§c物品不存在：" + r.itemId;
                }
                var goods = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
                if (gotGoods != null && !gotGoods.isEmpty()) {
                    goods.add(gotGoods);
                }
                com.tangtu.kjbb.AddressBook.lastOrigin = r.seller;
                com.tangtu.kjbb.AddressBook.lastOrderId = r.id;
                try {
                    delivered = com.tangtu.kjbb.AddressBook.deliverTo(
                            new com.tangtu.kjbb.AddressBook.PortInfo(level, port, buyer), goods);
                } catch (Throwable t) {
                    delivered = false;
                }
                try {
                    com.tangtu.kjbb.MailLog.add(r.seller, buyer, r.itemId, take,
                            delivered ? "\u7269\u6d41\u4e2d" : "\u6392\u961f");
                } catch (Throwable ignored) {
                    // ignore
                }
                if (!delivered) {
                    // 区块没加载等情况：进离线邮寄队列，等加载后补投
                    com.tangtu.kjbb.AddressBook.lastOrigin = r.seller;
                    com.tangtu.kjbb.AddressBook.lastOrderId = r.id;
                    com.tangtu.kjbb.MailQueue.add(buyer, buyer, r.itemId, take);
                    left = 0;
                } else {
                    left = 0;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (left > 0) {
            com.tangtu.kjbb.MailQueue.add(buyer, buyer, r.itemId, left);
        }
        // 玩家买入时给卖家发一条成交通知（部分成交也通知）
        try {
        if (r.seller != null && !r.seller.isEmpty()) {
        var srv2 = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (srv2 != null) {
        var sp3 = srv2.getPlayerList().getPlayerByName(r.seller);
        if (sp3 != null) {
        sp3.displayClientMessage(
        net.minecraft.network.chat.Component.literal(
        "\u00a7a[\u6210\u4ea4] \u4f60\u7684\u6302\u5355 #" + r.id
        + " \u5356\u51fa " + take + " \u4e2a\uff1a"
        + r.itemId + " @" + r.price
        + "\uff0c\u8fdb\u8d26 " + cost + " \u5510\u5154\u5e01"),
        false);
        }
        }
        }
        } catch (Throwable ignored) {
        // ignore
        }
        // 挂单扣量
        r.count -= take;
        if (r.count <= 0) {
            r.status = FILLED;
        }
        save();
        try {
            com.tangtu.kjbb.BotTrades.log(buyer, r.seller, "挂单买入#" + r.id, take, r.price, "交易行");
            DebugLog.log("玩家挂单成交 #" + r.id + "：" + buyer + " 买 " + r.seller
                    + " 的 " + r.itemId + " ×" + take + " @" + r.price);
        } catch (Throwable ignored) {
            // ignore
        }
        return "§a买下 " + r.itemId + " ×" + take + "，付 " + cost + " 唐兔币"
                + (r.count > 0 ? ("（剩 " + r.count + "）") : "");
    }
    public static synchronized List<Row> open() {
        load();
        List<Row> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (OPEN.equals(r.status)) {
                out.add(r);
            }
        }
        return out;
    }

    public static synchronized List<Row> of(String seller) {
        load();
        List<Row> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (r.seller.equalsIgnoreCase(seller)) {
                out.add(r);
            }
        }
        return out;
    }

    public static synchronized Row get(long id) {
        load();
        for (Row r : ROWS) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    public static synchronized void setStatus(long id, String status) {
        // 成交通知：挂单卖完时给卖家发一条消息（离线玩家不收）
        if (FILLED.equals(status)) {
            try {
                Row r2 = get(id);
                if (r2 != null && r2.seller != null && !r2.seller.isEmpty()) {
                    var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                    if (srv != null) {
                        var sp2 = srv.getPlayerList().getPlayerByName(r2.seller);
                        if (sp2 != null) {
                            sp2.displayClientMessage(
                                    net.minecraft.network.chat.Component.literal(
                                            "\u00a7a[\u6210\u4ea4] \u4f60\u7684\u6302\u5355 #" + r2.id
                                                    + " \u5df2\u5356\u5b8c\uff1a" + r2.itemId
                                                    + " \u00d7" + r2.count + " @"
                                                    + r2.price + "\uff08\u5171 "
                                                    + (r2.price * r2.count) + " \u5510\u5154\u5e01\uff09"),
                                    false);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        load();
        for (Row r : ROWS) {
            if (r.id == id) {
                r.status = status;
            }
        }
        save();
    }

    /** 该物品的最低挂单价（没有挂单返回 0） */
    public static synchronized long bestPrice(String itemId) {
        load();
        long best = 0L;
        for (Row r : ROWS) {
            if (!OPEN.equals(r.status) || !r.itemId.equals(itemId)) {
                continue;
            }
            if (best == 0L || r.price < best) {
                best = r.price;
            }
        }
        return best;
    }

    public static synchronized int available(String itemId) {
        load();
        int n = 0;
        for (Row r : ROWS) {
            if (OPEN.equals(r.status) && r.itemId.equals(itemId)) {
                n += r.count;
            }
        }
        return n;
    }

    /** 清理已完成/已取消的挂单 */
    public static synchronized int purge(long cutoffMs) {
        load();
        int before = ROWS.size();
        ROWS.removeIf(r -> (FILLED.equals(r.status) || CANCELLED.equals(r.status))
                && r.created < cutoffMs);
        int n = before - ROWS.size();
        if (n > 0) {
            save();
        }
        return n;
    }

    public static synchronized String dump() {
        load();
        List<Row> list = open();
        if (list.isEmpty()) {
            return "（没有挂单）";
        }
        StringBuilder sb = new StringBuilder();
        for (Row r : list) {
            sb.append("#").append(r.id).append("  ")
                    .append(com.tangtu.kjbb.item.StoreReceiptItem.niceName(r.itemId))
                    .append(" ×").append(r.count).append("  ").append(r.price).append(" 唐兔币/个")
                    .append("  卖家=").append(r.seller).append('\n');
        }
        return sb.toString();
    }
}