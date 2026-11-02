package com.tangtu.kjbb;

import com.tangtu.kjbb.item.BillData;
import com.tangtu.kjbb.item.BillItem;
import com.tangtu.kjbb.item.BillRegistry;
import com.tangtu.kjbb.item.KjbbItems;
import com.tangtu.kjbb.item.StoreReceiptItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 票据市场（提单 + 仓单）。
 *
 * config/kjbb/ticket_market.txt
 *   id|kind|卖家|价格|data|状态|创建时间
 *
 * kind = BILL / RECEIPT
 *   BILL    data = serial:bearer          （例 7:1）
 *   RECEIPT data = x,y,z:bearer:item=count;item=count
 *
 * 挂卖时票据物品从背包移除、由市场保管；成交时按 data 重建票据物品给买家。
 */
public final class TicketMarket {

    public static final String ON_SALE = "ON_SALE";
    public static final String SOLD = "SOLD";
    public static final String CANCELLED = "CANCELLED";

    public static final String KIND_BILL = "BILL";
    public static final String KIND_RECEIPT = "RECEIPT";

    public static final class Row {
        public long id;
        public String kind;
        public String seller;
        public long price;
        public String data;
        public String status;
        public long created;

        public Row(long id, String kind, String seller, long price, String data,
                   String status, long created) {
            this.id = id;
            this.kind = kind;
            this.seller = seller;
            this.price = price;
            this.data = data;
            this.status = status;
            this.created = created;
        }
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static long nextId = 1L;
    private static boolean loaded = false;

    private TicketMarket() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("ticket_market.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_ticket_market.txt");
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
                            Long.parseLong(f[3].trim()), f[4].trim(), f[5].trim(),
                            Long.parseLong(f[6].trim())));
                    if (id >= nextId) {
                        nextId = id + 1;
                    }
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读票据市场失败: " + t);
        }
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder("# id|kind|卖家|价格|data|状态|创建时间\n");
        for (Row r : ROWS) {
            sb.append(r.id).append('|').append(r.kind).append('|').append(r.seller).append('|')
                    .append(r.price).append('|').append(r.data).append('|').append(r.status)
                    .append('|').append(r.created).append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写票据市场失败: " + t);
        }
    }

    public static synchronized Row add(String kind, String seller, long price, String data) {
        load();
        Row r = new Row(nextId++, kind, seller, price, data, ON_SALE, ServerClock.now());
        ROWS.add(r);
        save();
        return r;
    }

    public static synchronized List<Row> onSale() {
        load();
        List<Row> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (ON_SALE.equals(r.status)) {
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
        load();
        for (Row r : ROWS) {
            if (r.id == id) {
                r.status = status;
            }
        }
        save();
    }

    public static synchronized int purge(long cutoffMs) {
        load();
        int before = ROWS.size();
        ROWS.removeIf(r -> (SOLD.equals(r.status) || CANCELLED.equals(r.status))
                && r.created < cutoffMs);
        int n = before - ROWS.size();
        if (n > 0) {
            save();
        }
        return n;
    }

    // ------------------------------------------------------------------ 物品 ↔ data

    /** 把主手票据编码成挂牌数据；不是票据返回 null */
    public static String encode(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() == KjbbItems.BILL.get()) {
            BillData d = KjbbItems.getBill(stack);
            if (d == null) {
                return null;
            }
            return d.serial() + ":" + (BillItem.isBearer(stack) ? "1" : "0");
        }
        if (stack.getItem() == KjbbItems.STORE_RECEIPT.get()) {
            if (StoreReceiptItem.isVoid(stack)) {
                return null;
            }
            String pos = StoreReceiptItem.posText(stack);
            if (pos == null || pos.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (var l : StoreReceiptItem.readRecord(stack)) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(l.itemId()).append('=').append(l.count());
            }
            return pos + ":" + (StoreReceiptItem.isBearer(stack) ? "1" : "0") + ":" + sb;
        }
        return null;
    }

    public static String kindOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() == KjbbItems.BILL.get()) {
            return KIND_BILL;
        }
        if (stack.getItem() == KjbbItems.STORE_RECEIPT.get()) {
            return KIND_RECEIPT;
        }
        return null;
    }

    /** 按挂牌数据重建票据物品（买家拿到的那一张） */
    public static ItemStack rebuild(String kind, String data, String newOwner, long gameTime) {
        if (KIND_BILL.equals(kind)) {
            String[] f = data.split(":");
            long serial;
            try {
                serial = Long.parseLong(f[0].trim());
            } catch (Throwable t) {
                return ItemStack.EMPTY;
            }
            boolean bearer = f.length > 1 && "1".equals(f[1].trim());
            ItemStack bill = new ItemStack(KjbbItems.BILL.get());
            BillData d = BillRegistry.dataOf(serial);
            if (d == null) {
                return ItemStack.EMPTY;
            }
            // 记名提单：成交后把记名人改成新持票人；不记名保持原样
            if (!bearer && newOwner != null && !newOwner.isEmpty()) {
                var moved = BillRegistry.transfer(serial, newOwner);
                if (moved != null) {
                    d = moved;
                }
            }
            KjbbItems.setBill(bill, d);
            BillItem.setBearer(bill, bearer);
            return bill;
        }
        if (KIND_RECEIPT.equals(kind)) {
            // data 形如 "minecraft:overworld#-7,-60,3:1:minecraft:diamond=330"
            // 先剥掉「维度#」，避免 minecraft: 里的冒号干扰解析
            String fixed = data;
            int hash0 = fixed.indexOf('#');
            if (hash0 < 0) {
                hash0 = fixed.indexOf('|');   // 兼容旧写法
            }
            if (hash0 >= 0) {
                fixed = fixed.substring(hash0 + 1);
            }
            String[] f = fixed.split(":", 3);
            if (f.length < 3) {
                return ItemStack.EMPTY;
            }
            String[] xyz = f[0].split(",");
            BlockPos pos;
            try {
                pos = new BlockPos(Integer.parseInt(xyz[0].trim()),
                        Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
            } catch (Throwable t) {
                return ItemStack.EMPTY;
            }
            boolean bearer = "1".equals(f[1].trim());
            java.util.List<StoreReceiptItem.Line> lines = new ArrayList<>();
            for (String part : f[2].split(";")) {
                int eq = part.lastIndexOf('=');
                if (eq <= 0) {
                    continue;
                }
                try {
                    lines.add(new StoreReceiptItem.Line(part.substring(0, eq),
                            Integer.parseInt(part.substring(eq + 1).trim())));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
            ItemStack receipt = new ItemStack(KjbbItems.STORE_RECEIPT.get());
            StoreReceiptItem.writeRecord(receipt, pos, newOwner, gameTime, lines);
            StoreReceiptItem.setBearer(receipt, bearer);
            return receipt;
        }
        return ItemStack.EMPTY;
    }

    /** 挂牌数据里的容器坐标（仓单用；提单返回 null） */
    /** 挂牌数据里的容器坐标（仓单用；提单返回 null） */
    public static BlockPos posOf(String kind, String data) {
        String d0 = stripDim(data);
        if (KIND_BILL.equals(kind)) {
            try {
                String[] f = d0.split(":");
                long serial = Long.parseLong(f[0].trim());
                var bd = com.tangtu.kjbb.item.BillRegistry.dataOf(serial);
                if (bd == null) {
                    return null;
                }
                if (bd.storeX() == 0 && bd.storeY() == 0 && bd.storeZ() == 0) {
                    return null;
                }
                return new BlockPos(bd.storeX(), bd.storeY(), bd.storeZ());
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (!KIND_RECEIPT.equals(kind)) {
            return null;
        }
        try {
            String[] f = d0.split(":", 3);
            String[] xyz = f[0].split(",");
            return new BlockPos(Integer.parseInt(xyz[0].trim()),
                    Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 去掉「维度#」或「维度|」前缀 */
    public static String stripDim(String data) {
        if (data == null) {
            return "";
        }
        int h = data.indexOf('#');
        if (h < 0) {
            h = data.indexOf('|');
        }
        return h >= 0 ? data.substring(h + 1) : data;
    }

    public static String describe(Row r) {
        String kindName = KIND_BILL.equals(r.kind) ? "提单" : "仓单";
        if (KIND_BILL.equals(r.kind)) {
            String[] f = r.data.split(":");
            long serial = 0L;
            try {
                serial = Long.parseLong(f[0].trim());
            } catch (Throwable ignored) {
                // 忽略
            }
            BillData d = BillRegistry.dataOf(serial);
            String item = d == null ? "?" : StoreReceiptItem.niceName(d.itemId());
            String cnt = d == null ? "?" : String.valueOf(d.count());
            String addr = d == null ? "" : d.address();
            String type = (f.length > 1 && "1".equals(f[1].trim())) ? "不记名" : "记名";
            return kindName + "｜" + item + " ×" + cnt + " → " + addr + "（" + type + "）";
        }
        String[] f = r.data.split(":", 3);
        String pos = f.length > 0 ? f[0] : "?";
        String type = (f.length > 1 && "1".equals(f[1].trim())) ? "不记名" : "记名";
        String items = f.length > 2 ? f[2] : "";
        int kinds = items.isEmpty() ? 0 : items.split(";").length;
        return kindName + "｜容器 " + pos + "｜" + kinds + " 种物品（" + type + "）";
    }

    public static synchronized String dump() {
        load();
        List<Row> list = onSale();
        if (list.isEmpty()) {
            return "（没有在售票据）";
        }
        StringBuilder sb = new StringBuilder();
        for (Row r : list) {
            sb.append("#").append(r.id).append("  ").append(r.price).append(" 唐兔币  ")
                    .append(describe(r)).append("  卖家=").append(r.seller).append('\n');
        }
        return sb.toString();
    }

    /** 玩家是否持有这张仓单对应的容器（用于锁） */
    public static boolean stillSealed(ServerLevel level, Row r) {
        BlockPos pos = posOf(r.kind, r.data);
        if (pos == null) {
            return true;
        }
        return StoreSeals.matches(level, pos);
    }

    /**
     * 票据对应的「货物容器」：
     *   坐标本身就是容器 → 用它
     *   否则在周围 8 格内找一个装着该物品的容器
     *
     * @param itemId 票据里的物品（可为 null，只找任意容器）
     */
    public static BlockPos findGoodsContainer(net.minecraft.server.level.ServerLevel level,
                                              BlockPos near, String itemId) {
        if (level == null || near == null) {
            return null;
        }
        if (TickerStock.handlerAt(level, near) != null) {
            return near;
        }
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos q = near.offset(dx, dy, dz);
                    if (!level.isLoaded(q)) {
                        continue;
                    }
                    if (TickerStock.handlerAt(level, q) == null) {
                        continue;
                    }
                    if (itemId == null) {
                        return q;
                    }
                    try {
                        var h = TickerStock.handlerAt(level, q);
                        for (int i = 0; i < h.getSlots(); i++) {
                            var st = h.getStackInSlot(i);
                            if (st == null || st.isEmpty()) {
                                continue;
                            }
                            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(st.getItem()).toString();
                            if (itemId.equals(id)) {
                                return q;
                            }
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        }
        return null;
    }
}
