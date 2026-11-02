package com.tangtu.kjbb.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提单登记表（服务端权威）：config/kjbb/bills.txt
 * 格式：serial|itemId|count|issuer|status|expiresAt|redeemedBy
 */
public final class BillRegistry {

    private static final Map<Long, String[]> ENTRIES = new LinkedHashMap<>();
    private static long nextSerial = 1L;
    private static boolean loaded = false;

    private BillRegistry() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("bills.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_bills.txt");
        }
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        ENTRIES.clear();
        nextSerial = 1L;
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
                if (f.length < 6) {
                    continue;
                }
                long serial = Long.parseLong(f[0].trim());
                String[] v = new String[]{
                        f[1].trim(), f[2].trim(), f[3].trim(), f[4].trim(),
                        f[5].trim(), f.length > 6 ? f[6].trim() : "",
                        f.length > 7 ? f[7].trim() : "0",
                        f.length > 8 ? f[8].trim() : "0",
                        f.length > 9 ? f[9].trim() : "0",
                        f.length > 10 ? f[10].trim() : ""};
                ENTRIES.put(serial, v);
                if (serial >= nextSerial) {
                    nextSerial = serial + 1L;
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读票据登记表失败: " + t);
        }
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder(
                "# serial|itemId|count|issuer|status|expiresAt|redeemedBy|sx|sy|sz|address\n");
        for (Map.Entry<Long, String[]> e : ENTRIES.entrySet()) {
            String[] v = e.getValue();
            sb.append(e.getKey()).append('|').append(v[0]).append('|').append(v[1])
                    .append('|').append(v[2]).append('|').append(v[3])
                    .append('|').append(v[4]).append('|').append(v[5])
                    .append('|').append(v[6]).append('|').append(v[7])
                    .append('|').append(v[8]).append('|').append(v[9]).append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写票据登记表失败: " + t);
        }
    }

    /** 登记一张新提单 */
    public static synchronized long register(String itemId, int count, String issuer, long expiresAt) {
        return register(itemId, count, issuer, expiresAt, 0, 0, 0);
    }

    public static synchronized long register(String itemId, int count, String issuer, long expiresAt,
                                             int sx, int sy, int sz) {
        return register(itemId, count, issuer, expiresAt, sx, sy, sz, "");
    }

    public static synchronized long register(String itemId, int count, String issuer, long expiresAt,
                                             int sx, int sy, int sz, String address) {
        load();
        long serial = nextSerial++;
        ENTRIES.put(serial, new String[]{itemId, String.valueOf(count), issuer,
                BillData.STATUS_ISSUED, String.valueOf(expiresAt), "",
                String.valueOf(sx), String.valueOf(sy), String.valueOf(sz),
                address == null ? "" : address});
        save();
        return serial;
    }

    /** 兑付 */
    public static synchronized String redeem(long serial, ServerPlayer holder, long gameTime) {
        load();
        try {
            holder.closeContainer();
        } catch (Throwable ignored) {
            // 忽略
        }
        String[] e = ENTRIES.get(serial);
        if (e == null) {
            return "§c这张提单在登记表里查不到（可能是伪造的）";
        }
        // 记名提单：只有票上写的人能提货
        boolean bearer = isBearerBill(holder, serial);
        String issuer = e[2] == null ? "" : e[2];
        if (!bearer && !issuer.isEmpty()
                && !issuer.equalsIgnoreCase(holder.getScoreboardName())) {
            return "§c这是记名提单，只有 " + issuer + " 本人能提货";
        }
        if (BillData.STATUS_REDEEMED.equals(e[3])) {
            return "§c这张提单已经兑付过了（领取人：" + e[5] + "）";
        }
        if (BillData.STATUS_EXPIRED.equals(e[3])) {
            return "§c这张提单已过期作废";
        }
        if (BillData.STATUS_SHIPPED.equals(e[3])) {
            String a = (e.length > 9 && e[9] != null && !e[9].isEmpty()) ? e[9] : "未登记";
            return "§a这张提单的包裹已寄出，请到地址「" + a + "」处领取";
        }
        long exp;
        try {
            exp = Long.parseLong(e[4]);
        } catch (Throwable t) {
            exp = 0L;
        }
        if (exp > 0L && gameTime > exp) {
            e[3] = BillData.STATUS_EXPIRED;
            save();
            return "§c这张提单已过期（有效期已过，货物不再发放）";
        }
        ResourceLocation rl = ResourceLocation.tryParse(e[0]);
        if (rl == null) {
            return "§c票面物品 id 无效：" + e[0];
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null) {
            return "§c票面物品不存在：" + e[0];
        }
        int count;
        try {
            count = Integer.parseInt(e[1]);
        } catch (Throwable t) {
            count = 1;
        }
        // 绑定仓库的票：让 Create 的物流系统打包发货（不在本地造包裹塞目标箱子）
        int sx0 = parseIntOrZero(e[6]);
        int sy0 = parseIntOrZero(e[7]);
        int sz0 = parseIntOrZero(e[8]);
        boolean hasStore0 = sx0 != 0 || sy0 != 0 || sz0 != 0;
        String addr0 = (e.length > 9 && e[9] != null) ? e[9] : "";
        if (hasStore0 && holder.level() instanceof net.minecraft.server.level.ServerLevel sl3) {
            var storePos = new net.minecraft.core.BlockPos(sx0, sy0, sz0);
            // 先看仓库够不够（不够就不发货，票保留）
            int stock = 0;
            try {
                var map = com.tangtu.kjbb.TickerStock.stock(sl3, storePos);
                stock = map.getOrDefault(e[0], 0);
            } catch (Throwable ignored) {
                stock = 0;
            }
            if (stock < count) {
                return "§c这家仓库当前库存不足（有 " + stock + "/" + count
                        + "），暂时无法提货（票仍然有效）";
            }
            String target = addr0.isEmpty() ? holder.getScoreboardName() : addr0;
            // 容器仓单（没有发放地址）：直接从容器取货给玩家
            if (addr0.isEmpty()) {
                int got = extractFromContainer(sl3, storePos, item, count);
                if (got < count) {
                    insertBack(sl3, storePos, item, got);
                    return "§c容器里货不够（" + got + "/" + count + "），票仍然有效";
                }
                java.util.List<ItemStack> goods2 = new java.util.ArrayList<>();
                int left = count;
                while (left > 0) {
                    int n = Math.min(left, Math.max(1, item.getDefaultMaxStackSize()));
                    goods2.add(new ItemStack(item, n));
                    left -= n;
                }
                boolean mailed2 = false;
                try {
                    mailed2 = com.tangtu.kjbb.PlayerMail.sendToPlayer(sl3, holder, goods2);
                } catch (Throwable ignored) {
                    mailed2 = false;
                }
                if (!mailed2) {
                    for (ItemStack give : goods2) {
                        if (!holder.getInventory().add(give)) {
                            holder.drop(give, false);
                        }
                    }
                }
                com.tangtu.kjbb.StoreClaims.release(storePos, e[0], count);
                e[3] = BillData.STATUS_REDEEMED;
                e[5] = holder.getScoreboardName();
                save();
                return "§a已兑付：No." + serial + "  " + count + " 件货物已交付";
            }
            boolean ok = com.tangtu.kjbb.TickerShip.request(sl3, storePos, e[0], count, target);
            if (ok) {
                e[3] = BillData.STATUS_SHIPPED;
                e[5] = holder.getScoreboardName();
                save();
                System.out.println("[TangtuEconomical] 快递发货 No." + serial + " → " + target);
                return "§a已提交发货：No." + serial + "  " + count + " 件货物由物流系统送往「"
                        + target + "」";
            }
            // 广播失败 → 回退：直接打包塞目标箱子
            java.util.List<ItemStack> goods = new java.util.ArrayList<>();
            int remain = Math.max(1, count);
            while (remain > 0) {
                int n = Math.min(remain, Math.max(1, item.getDefaultMaxStackSize()));
                goods.add(new ItemStack(item, n));
                remain -= n;
            }
            if (com.tangtu.kjbb.TickerStock.extract(sl3, storePos, item, count) == count) {
                if (com.tangtu.kjbb.AddressBook.deliver(sl3, storePos, target, goods)) {
                    e[3] = BillData.STATUS_REDEEMED;
                    e[5] = holder.getScoreboardName();
                    save();
                    return "§a已兑付：No." + serial + "  " + count + " 件货物已寄往「" + target + "」";
                }
            }
            return "§c发货失败：发报机没有可用的打包机/链接站，票仍然有效";
        }

        // 有发放地址 → 打成 Create 包裹投递到地址对应的容器
        String address = (e.length > 9 && e[9] != null) ? e[9] : "";
        if (!address.isEmpty() && holder.level() instanceof net.minecraft.server.level.ServerLevel sl2) {
            java.util.List<ItemStack> goods = new java.util.ArrayList<>();
            int remain = Math.max(1, count);
            while (remain > 0) {
                int n = Math.min(remain, Math.max(1, item.getDefaultMaxStackSize()));
                goods.add(new ItemStack(item, n));
                remain -= n;
            }
            if (com.tangtu.kjbb.AddressBook.deliver(sl2, holder.blockPosition(), address, goods)) {
                e[3] = BillData.STATUS_REDEEMED;
                e[5] = holder.getScoreboardName();
                save();
                System.out.println("[TangtuEconomical] 提单投递 No." + serial + " → 地址 " + address
                        + " " + e[0] + " x" + e[1]);
                return "§a已发放：No." + serial + "  包裹已寄往地址「" + address + "」";
            }
            return "§c找不到地址「" + address + "」的收货箱，票仍然有效（站在收货箱旁用 /billaddr "
                    + address + " 绑定）";
        }

        int left = Math.max(1, count);
        while (left > 0) {
            int stackSize = Math.min(left, Math.max(1, item.getDefaultMaxStackSize()));
            ItemStack give = new ItemStack(item, stackSize);
            if (!holder.getInventory().add(give)) {
                holder.drop(give, false);
            }
            left -= stackSize;
        }
        e[3] = BillData.STATUS_REDEEMED;
        e[5] = holder.getScoreboardName();
        save();
        System.out.println("[TangtuEconomical] 提单兑付 No." + serial + " → " + holder.getScoreboardName()
                + " " + e[0] + " x" + e[1]);
        return "§a已兑付：No." + serial + "  " + count + " 件货物已放入背包";
    }

    /** 玩家持有的提单清单（按签发人） */
    /** 把登记表里的某一行还原成 BillData（票据市场重建票据用） */
    public static synchronized BillData dataOf(long serial) {
        load();
        String[] e = ENTRIES.get(serial);
        if (e == null) {
            return null;
        }
        try {
            int count = Integer.parseInt(e[1]);
            long exp = Long.parseLong(e[4]);
            int sx = e.length > 6 ? parseIntOrZero(e[6]) : 0;
            int sy = e.length > 7 ? parseIntOrZero(e[7]) : 0;
            int sz = e.length > 8 ? parseIntOrZero(e[8]) : 0;
            String addr = e.length > 9 && e[9] != null ? e[9] : "";
            String redeemedBy = e.length > 5 && e[5] != null ? e[5] : "";
            return new BillData(e[0], count, e[2], serial, e[3], exp, sx, sy, sz, addr);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 已发货的提单：返回 [serial, address] 列表 */
    public static synchronized java.util.List<String[]> shippedList() {
        load();
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (var e : ENTRIES.entrySet()) {
            String[] v = e.getValue();
            if (v == null || !BillData.STATUS_SHIPPED.equals(v[3])) {
                continue;
            }
            String addr = (v.length > 9 && v[9] != null) ? v[9] : "";
            if (addr.isEmpty()) {
                continue;
            }
            out.add(new String[]{String.valueOf(e.getKey()), addr});
        }
        return out;
    }

    public static synchronized void markDelivered(long serial) {
        load();
        String[] v = ENTRIES.get(serial);
        if (v == null) {
            return;
        }
        v[3] = "DELIVERED";
        save();
    }

    public static synchronized List<String> listOf(String issuer) {
        load();
        List<String> out = new ArrayList<>();
        for (Map.Entry<Long, String[]> en : ENTRIES.entrySet()) {
            String[] v = en.getValue();
            if (!issuer.equals(v[2])) {
                continue;
            }
            out.add("No." + en.getKey() + "  " + v[0] + " ×" + v[1]
                    + "  [" + cnStatus(v[3]) + "]");
        }
        return out;
    }

    /** 单张票据信息 */
    public static synchronized String info(long serial) {
        load();
        String[] e = ENTRIES.get(serial);
        if (e == null) {
            return null;
        }
        return "No." + serial + "\n"
                + "  货物：" + e[0] + " ×" + e[1] + "\n"
                + "  签发：" + e[2] + "\n"
                + "  状态：" + cnStatus(e[3]) + "\n"
                + "  到期：" + (e[4].isEmpty() ? "?" : e[4]) + " (tick)\n"
                + "  领取：" + (e[5].isEmpty() ? "未领取" : e[5]);
    }

    /** 作废（签发人或管理员） */
    public static synchronized String voidBill(long serial, String who, boolean admin) {
        load();
        String[] e = ENTRIES.get(serial);
        if (e == null) {
            return "§c找不到 No." + serial;
        }
        if (!admin && !who.equals(e[2])) {
            return "§c只有签发人才能作废这张提单";
        }
        if (BillData.STATUS_REDEEMED.equals(e[3])) {
            return "§c这张已经兑付，不能作废";
        }
        e[3] = BillData.STATUS_EXPIRED;
        save();
        return "§a已作废 No." + serial;
    }

    /** 直接改状态（出票时已发货就要记 SHIPPED，否则兑付会重复发货） */
    public static synchronized void setStatus(long serial, String status) {
        load();
        String[] e = ENTRIES.get(serial);
        if (e != null && status != null) {
            e[3] = status;
            save();
        }
    }

    /** 找玩家背包里这张票，读它的记名/不记名标记 */
    private static boolean isBearerBill(ServerPlayer holder, long serial) {
        var inv = holder.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var st = inv.getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            if (st.getItem() != com.tangtu.kjbb.item.KjbbItems.BILL.get()) {
                continue;
            }
            var d = com.tangtu.kjbb.item.KjbbItems.getBill(st);
            if (d != null && d.serial() == serial) {
                return com.tangtu.kjbb.item.BillItem.isBearer(st);
            }
        }
        return false;
    }

    private static int extractFromContainer(net.minecraft.server.level.ServerLevel level,
                                            net.minecraft.core.BlockPos pos,
                                            Item item, int count) {
        try {
            var be = level.getBlockEntity(pos);
            if (be == null) {
                return 0;
            }
            var h = net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK
                    .getCapability(level, pos, be.getBlockState(), be, null);
            if (h == null) {
                return 0;
            }
            int left = count;
            for (int i = 0; i < h.getSlots() && left > 0; i++) {
                ItemStack st = h.getStackInSlot(i);
                if (st == null || st.isEmpty() || st.getItem() != item) {
                    continue;
                }
                ItemStack got = h.extractItem(i, left, false);
                if (got != null && !got.isEmpty()) {
                    left -= got.getCount();
                }
            }
            return count - left;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void insertBack(net.minecraft.server.level.ServerLevel level,
                                   net.minecraft.core.BlockPos pos, Item item, int count) {
        if (count <= 0) {
            return;
        }
        try {
            var be = level.getBlockEntity(pos);
            if (be == null) {
                return;
            }
            var h = net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK
                    .getCapability(level, pos, be.getBlockState(), be, null);
            if (h == null) {
                return;
            }
            int left = count;
            for (int i = 0; i < h.getSlots() && left > 0; i++) {
                ItemStack rest = h.insertItem(i, new ItemStack(item, left), false);
                if (rest == null || rest.isEmpty()) {
                    left = 0;
                } else {
                    left = rest.getCount();
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String cnStatus(String st) {
        if (BillData.STATUS_REDEEMED.equals(st)) {
            return "已兑付";
        }
        if (BillData.STATUS_SHIPPED.equals(st)) {
            return "已寄出";
        }
        if (BillData.STATUS_EXPIRED.equals(st)) {
            return "已作废";
        }
        return "待兑付";
    }

    public static synchronized void markExpired(long serial) {
        load();
        String[] e = ENTRIES.get(serial);
        if (e != null && BillData.STATUS_ISSUED.equals(e[3])) {
            e[3] = BillData.STATUS_EXPIRED;
            save();
        }
    }

    /**
     * 记名提单转让：把 issuer 改成新持票人
     *
     * @return 新的 BillData（失败返回 null）
     */
    public static synchronized com.tangtu.kjbb.item.BillData transfer(long serial, String newOwner) {
        load();
        String[] v = ENTRIES.get(serial);
        if (v == null || newOwner == null || newOwner.isEmpty()) {
            return null;
        }
        v[2] = newOwner;            // issuer 位
        save();
        System.out.println("[TangtuEconomical] \u8bb0\u540d\u63d0\u5355\u8f6c\u8ba9 #" + serial
                + " \u2192 " + newOwner);
        return dataOf(serial);
    }
}
