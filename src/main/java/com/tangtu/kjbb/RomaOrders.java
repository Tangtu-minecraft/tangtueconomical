package com.tangtu.kjbb;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 罗马国的**玩家贸易订单** —— 买卖货物，但必须挂在某艘远洋提船上
 *
 * 设计约束（用户要求）：不开放「随时找罗马国买卖」，一切交易都必须挂靠一个在募资中的
 * 航次，因此玩家要承担航行风险：
 *
 *   SELL 出口：玩家把货卖给罗马国。下单时货从背包扣走、价格锁定罗马价；
 *              到港未沉没 → 罗马国付钱给玩家；沉没 → 货随船一起没了。
 *
 *   BUY  进口：玩家向罗马国订货。下单时预付货款（罗马价 × 1.15 罗马国加价）；
 *              到港未沉没 → 货物进背包；沉没 → 预付款不退（风险自负）。
 *
 * 货币口径：出口是投放（罗马国付钱），进口是回收（玩家付钱），两者挂在同一批航次上，
 * 正好互相抵消，不会让贸易变成单向印钱。
 *
 * 数据：config/kjbb/roma_orders.txt
 *   # id|billId|owner|side|item|qty|unitPrice|status|day
 */
public final class RomaOrders {

    public static final String SELL = "SELL";
    public static final String BUY = "BUY";

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String VOID = "VOID";

    /** 罗马国进口加价（千分比）：1150 = 加 15% */
    private static final int IMPORT_MARKUP = 1150;

    public static final class Order {
        public long id;
        public long billId;
        public String owner = "";
        public String side = SELL;
        public String item = "";
        public int qty;
        public long unitPrice;
        public String status = PENDING;
        public long day;

        public long total() {
            return unitPrice * qty;
        }
    }

    private static final List<Order> ORDERS = new ArrayList<>();
    private static long NEXT_ID = 1L;
    private static boolean loaded = false;

    private RomaOrders() {
    }

    // ---------------------------------------------------------------- 落盘

    private static Path file() {
        return Path.of("config", "kjbb", "roma_orders.txt");
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path p = file();
            if (!Files.exists(p)) {
                return;
            }
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length < 8) {
                    continue;
                }
                try {
                    Order o = new Order();
                    o.id = Long.parseLong(f[0].trim());
                    o.billId = Long.parseLong(f[1].trim());
                    o.owner = f[2].trim();
                    o.side = f[3].trim();
                    o.item = f[4].trim();
                    o.qty = Integer.parseInt(f[5].trim());
                    o.unitPrice = Long.parseLong(f[6].trim());
                    o.status = f[7].trim();
                    o.day = f.length > 8 ? Long.parseLong(f[8].trim()) : 0L;
                    ORDERS.add(o);
                    NEXT_ID = Math.max(NEXT_ID, o.id + 1);
                } catch (Throwable ignored) {
                    // 坏行跳过
                }
            }
        } catch (Throwable ignored) {
            // 读不到就当空账
        }
    }

    public static synchronized void flushNow() {
        try {
            Path p = file();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            StringBuilder sb = new StringBuilder("# id|billId|owner|side|item|qty|unitPrice|status|day\n");
            for (Order o : ORDERS) {
                sb.append(o.id).append('|').append(o.billId).append('|').append(o.owner)
                        .append('|').append(o.side).append('|').append(o.item)
                        .append('|').append(o.qty).append('|').append(o.unitPrice)
                        .append('|').append(o.status).append('|').append(o.day).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 标脏：真正写盘由 DirtySave 每 saveIntervalTicks 批量执行 */
    public static synchronized void save() {
        DirtySave.mark("romaorders");
    }

    static {
        DirtySave.register("romaorders", RomaOrders::flushNow);
    }


    // ---------------------------------------------------------------- 价格

    /** 某航次某种货已经挂出去的量（作废的不算） */
    public static synchronized long committedQty(long billId, String itemId) {
        load();
        long sum = 0L;
        for (Order o : ORDERS) {
            if (o.billId == billId && o.item.equals(itemId) && !VOID.equals(o.status)) {
                sum += o.qty;
            }
        }
        return sum;
    }

    /** 出口价：罗马国收货价 = 罗马成交价（已按累计量打折） */
    public static synchronized long sellPrice(String itemId, long billId) {
        return RomaMarket.salePriceForRange(itemId, billId, committedQty(billId, itemId), 1);
    }

    /** 进口价：罗马国出货价 = 罗马成交价 × 加价 */
    public static long buyPrice(String itemId, long billId) {
        // 进口价不用罗马预算系数：储备低时罗马国愿意出货回血，不该跟着压价
        long base = RomaMarket.basePrice(itemId, billId, 0L);
        return Math.max(1L, base * IMPORT_MARKUP / 1000L);
    }

    public static synchronized boolean hasPending(long billId) {
        load();
        for (Order o : ORDERS) {
            if (o.billId == billId && PENDING.equals(o.status)) {
                return true;
            }
        }
        return false;
    }

    /** 某个玩家的订单（航运面板/统计用）；pendingOnly = 只要还没结算的 */
    public static synchronized java.util.List<Order> of(String owner, boolean pendingOnly) {
        load();
        java.util.List<Order> out = new java.util.ArrayList<>();
        if (owner == null || owner.isEmpty()) {
            return out;
        }
        for (Order o : ORDERS) {
            if (!owner.equals(o.owner)) {
                continue;
            }
            if (pendingOnly && !PENDING.equals(o.status)) {
                continue;
            }
            out.add(o);
        }
        return out;
    }

    // ---------------------------------------------------------------- 下单

    /** 出口：把背包里的货卖给罗马国，挂在指定航次上 */
    public static synchronized String sell(ServerPlayer sp, long billId, String itemId, int qty) {
        load();
        VoyageBill.Bill b = VoyageBill.get(billId);
        String check = checkBill(b);
        if (check != null) {
            return check;
        }
        if (qty <= 0) {
            return "§c数量要大于 0";
        }
        ItemStack probe = stackOf(itemId, 1);
        if (probe.isEmpty()) {
            return "§c不认识这个物品：" + itemId;
        }
        String me = sp.getScoreboardName();
        if (!takeFromInventory(sp, probe.getItem(), qty)) {
            return "§c背包里没有那么多 " + itemId;
        }
        long already = committedQty(billId, itemId);
        long fullPrice = RomaMarket.salePrice(itemId, billId);
        long unit = RomaMarket.salePriceForRange(itemId, billId, already, qty);
        Order o = new Order();
        o.id = NEXT_ID++;
        o.billId = billId;
        o.owner = me;
        o.side = SELL;
        o.item = itemId;
        o.qty = qty;
        o.unitPrice = unit;
        o.status = PENDING;
        o.day = com.tangtu.kjbb.ServerCalendar.currentDay();
        ORDERS.add(o);
        save();
        DebugLog.log("罗马国出口订单 #" + o.id + "：" + me + " 卖 " + qty + "×" + itemId
                + " @ " + o.unitPrice + "（航次 #" + billId + "）");
        String note = unit < fullPrice
                ? "§7（量大折价 " + Math.round(unit * 100.0 / Math.max(1L, fullPrice)) + "% 原价）"
                : "";
        return "§a已交给罗马国 " + qty + "×" + shortName(itemId) + "，锁价 " + o.unitPrice
                + "/件" + note + "，合计 " + o.total() + " 唐兔币，到港后付款（订单 #" + o.id + "）";
    }

    /** 进口：向罗马国订货，到港提货 */
    public static synchronized String want(ServerPlayer sp, long billId, String itemId, int qty) {
        load();
        VoyageBill.Bill b = VoyageBill.get(billId);
        String check = checkBill(b);
        if (check != null) {
            return check;
        }
        if (qty <= 0) {
            return "§c数量要大于 0";
        }
        if (stackOf(itemId, 1).isEmpty()) {
            return "§c不认识这个物品：" + itemId;
        }
        String me = sp.getScoreboardName();
        long unit = buyPrice(itemId, billId);
        long cost = unit * qty;
        long have = VoyageBill.balanceOf(me);
        if (have < cost) {
            return "§c唐兔币不够：需要 " + cost + "，你有 " + have;
        }
        // 罗马国现在有真实库存：库存不够就下不了单
        long inStock = com.tangtu.kjbb.RomaMarket.stockOf(itemId);
        if (inStock < qty) {
            return "§c罗马国库存不足：" + shortName(itemId) + " 只有 " + inStock
                    + " 件（生产在推进，等几天再来）";
        }
        int reserved = com.tangtu.kjbb.RomaMarket.takeFromStock(itemId, qty);
        if (reserved < qty) {
            com.tangtu.kjbb.RomaMarket.addToStock(itemId, reserved);   // 退回预留
            return "§c罗马国库存不足，暂时无法接单";
        }
        com.tangtu.kjbb.BankBridge.takeMoney(me, cost);
        Order o = new Order();
        o.id = NEXT_ID++;
        o.billId = billId;
        o.owner = me;
        o.side = BUY;
        o.item = itemId;
        o.qty = qty;
        o.unitPrice = unit;
        o.status = PENDING;
        o.day = com.tangtu.kjbb.ServerCalendar.currentDay();
        ORDERS.add(o);
        save();
        DebugLog.log("罗马国进口订单 #" + o.id + "：" + me + " 订 " + qty + "×" + itemId
                + " @ " + unit + "（航次 #" + billId + "）");
        return "§a已向罗马国订购 " + qty + "×" + shortName(itemId) + "，预付 " + cost
                + " 唐兔币，到港后提货（订单 #" + o.id + "）";
    }

    /** 撤单：募资期内可撤，货和钱原路退回 */
    public static synchronized String cancel(ServerPlayer sp, long orderId) {
        load();
        Order o = find(orderId);
        if (o == null) {
            return "§c没有这张订单";
        }
        String me = sp.getScoreboardName();
        if (!o.owner.equals(me)) {
            return "§c这不是你的订单";
        }
        if (!PENDING.equals(o.status)) {
            return "§c这张订单已经结算过了";
        }
        VoyageBill.Bill b = VoyageBill.get(o.billId);
        if (b == null || !VoyageBill.OPEN.equals(b.status)) {
            return "§c船已经开了，不能撤单";
        }
        if (SELL.equals(o.side)) {
            if (!giveToInventory(sp, o.item, o.qty)) {
                return "§c背包放不下，先清点空间再撤单";
            }
        } else {
            com.tangtu.kjbb.BankBridge.addMoney(me, o.total());
            // 进口单撤掉：预留的货退回罗马国库存
            com.tangtu.kjbb.RomaMarket.addToStock(o.item, o.qty);
        }
        o.status = VOID;
        save();
        return "§a已撤单 #" + o.id + "（" + (SELL.equals(o.side) ? "货已退回" : "预付款已退回") + "）";
    }

    /** 我的订单 */
    public static synchronized String list(ServerPlayer sp) {
        load();
        String me = sp.getScoreboardName();
        StringBuilder sb = new StringBuilder("§6===== 我的罗马国订单 =====\n");
        boolean any = false;
        for (Order o : ORDERS) {
            if (!o.owner.equals(me)) {
                continue;
            }
            any = true;
            sb.append("§e#").append(o.id).append(' ')
                    .append(SELL.equals(o.side) ? "§a卖" : "§b买")
                    .append("§7 ").append(o.qty).append("×").append(shortName(o.item))
                    .append(" @").append(o.unitPrice)
                    .append("　合计 ").append(o.total())
                    .append("　航次 #").append(o.billId)
                    .append("　§7[").append(o.status).append("]\n");
        }
        if (!any) {
            sb.append("§7（没有订单）");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 到港结算

    /** 到港：出口付钱、进口给货 */
    public static synchronized String settleArrival(VoyageBill.Bill b, long day,
                                                    net.minecraft.server.MinecraftServer server) {
        load();
        int paid = 0;
        int delivered = 0;
        List<Order> todo = new ArrayList<>();
        for (Order o : ORDERS) {
            if (o.billId == b.id && PENDING.equals(o.status)) {
                todo.add(o);
            }
        }
        if (todo.isEmpty()) {
            return null;
        }
        for (Order o : todo) {
            ServerPlayer sp = playerOf(server, o.owner);
            if (sp == null) {
                continue;   // 人不在线，等他上线再结算（保持 PENDING）
            }
            if (SELL.equals(o.side)) {
                com.tangtu.kjbb.BankBridge.addMoney(o.owner, o.total());
                paid++;
            } else {
                if (!giveToInventory(sp, o.item, o.qty)) {
                    continue;   // 背包放不下，留到下次
                }
                delivered++;
            }
            o.status = DONE;
            o.day = day;
        }
        save();
        DebugLog.log("罗马国订单结算 #" + b.id + "：付出口款 " + paid + " 单，交付进口 "
                + delivered + " 单");
        if (paid == 0 && delivered == 0) {
            return null;
        }
        return "罗马国订单结算：付出口款 " + paid + " 单、交付进口 " + delivered + " 单";
    }

    /** 沉没：订单全部作废，货和预付款都不退 */
    public static synchronized String onSunk(VoyageBill.Bill b, long day) {
        load();
        int n = 0;
        for (Order o : ORDERS) {
            if (o.billId == b.id && PENDING.equals(o.status)) {
                o.status = VOID;
                o.day = day;
                n++;
            }
        }
        if (n > 0) {
            save();
            DebugLog.log("罗马国订单随船沉没作废 #" + b.id + "：共 " + n + " 单");
        }
        return n > 0 ? ("随船沉没，作废 " + n + " 张罗马国订单") : null;
    }

    // ---------------------------------------------------------------- 工具

    private static String checkBill(VoyageBill.Bill b) {
        if (b == null) {
            return "§c没有这张提单";
        }
        if (!VoyageBill.OPEN.equals(b.status)) {
            return "§c这艘船已经封盘（" + b.status + "），罗马国只收募资期的单";
        }
        return null;
    }

    /** id 索引：订单多起来后 find() 不再全表扫 */
    private static final java.util.Map<Long, Order> BY_ID = new java.util.HashMap<>();

    private static void reindexOrders() {
        BY_ID.clear();
        for (Order o : ORDERS) {
            BY_ID.put(o.id, o);
        }
    }

    private static Order find(long id) {
        Order hit = BY_ID.get(id);
        if (hit != null) {
            return hit;
        }
        reindexOrders();
        hit = BY_ID.get(id);
        if (hit != null) {
            return hit;
        }
        for (Order o : ORDERS) {
            if (o.id == id) {
                BY_ID.put(id, o);
                return o;
            }
        }
        return null;
    }

    private static String shortName(String itemId) {
        return itemId == null ? "?" : itemId.replace("minecraft:", "").replace("create:", "C·");
    }

    private static ItemStack stackOf(String itemId, int count) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) {
                return ItemStack.EMPTY;
            }
            Item it = BuiltInRegistries.ITEM.get(rl);
            if (it == null || it == net.minecraft.world.item.Items.AIR) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(it, Math.max(1, count));
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean takeFromInventory(ServerPlayer sp, Item item, int count) {
        var inv = sp.getInventory();
        int have = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.getItem() == item) {
                have += st.getCount();
            }
        }
        if (have < count) {
            return false;
        }
        int left = count;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty() || st.getItem() != item) {
                continue;
            }
            int take = Math.min(left, st.getCount());
            st.shrink(take);
            left -= take;
        }
        return true;
    }

    private static boolean giveToInventory(ServerPlayer sp, String itemId, int count) {
        try {
            ItemStack st = stackOf(itemId, count);
            if (st.isEmpty()) {
                return false;
            }
            int max = st.getMaxStackSize();
            int left = count;
            while (left > 0) {
                int n = Math.min(left, max);
                ItemStack part = st.copy();
                part.setCount(n);
                if (!sp.getInventory().add(part)) {
                    sp.drop(part, false);
                }
                left -= n;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ServerPlayer playerOf(net.minecraft.server.MinecraftServer server, String name) {
        try {
            if (server == null) {
                return null;
            }
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.getScoreboardName().equals(name)) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }
}
