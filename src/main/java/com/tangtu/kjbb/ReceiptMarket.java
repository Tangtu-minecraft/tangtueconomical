package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 仓单交易：把仓单挂到市场上卖。
 *
 * config/kjbb/receipt_market.txt
 *   id|卖家|价格|pos|物品串(item=count;item=count)|状态
 *
 * 状态：ON_SALE / SOLD / CANCELLED
 */
public final class ReceiptMarket {

    public static final String ON_SALE = "ON_SALE";
    public static final String SOLD = "SOLD";

    public static final class Row {
        public long id;
        public String seller;
        public long price;
        public String pos;
        public String items;
        public String status;

        public Row(long id, String seller, long price, String pos, String items, String status) {
            this.id = id;
            this.seller = seller;
            this.price = price;
            this.pos = pos;
            this.items = items;
            this.status = status;
        }
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static long nextId = 1L;
    private static boolean loaded = false;

    private ReceiptMarket() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("receipt_market.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_receipt_market.txt");
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
                if (f.length < 6) {
                    continue;
                }
                try {
                    long id = Long.parseLong(f[0].trim());
                    ROWS.add(new Row(id, f[1].trim(), Long.parseLong(f[2].trim()),
                            f[3].trim(), f[4].trim(), f[5].trim()));
                    if (id >= nextId) {
                        nextId = id + 1;
                    }
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读仓单市场失败: " + t);
        }
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder("# id|卖家|价格|pos|物品|状态\n");
        for (Row r : ROWS) {
            sb.append(r.id).append('|').append(r.seller).append('|').append(r.price).append('|')
                    .append(r.pos).append('|').append(r.items).append('|').append(r.status)
                    .append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写仓单市场失败: " + t);
        }
    }

    public static synchronized Row add(String seller, long price, BlockPos pos,
                                       List<com.tangtu.kjbb.item.StoreReceiptItem.Line> items) {
        load();
        StringBuilder sb = new StringBuilder();
        for (var l : items) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(l.itemId()).append('=').append(l.count());
        }
        Row r = new Row(nextId++, seller, price,
                pos.getX() + "," + pos.getY() + "," + pos.getZ(), sb.toString(), ON_SALE);
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

    public static synchronized void remove(long id) {
        load();
        if (ROWS.removeIf(r -> r.id == id)) {
            save();
        }
    }

    public static String itemsText(String items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] parts = items.split(";");
        int shown = 0;
        for (String p : parts) {
            int eq = p.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (shown >= 3) {
                sb.append(" …");
                break;
            }
            if (shown > 0) {
                sb.append("，");
            }
            sb.append(com.tangtu.kjbb.item.StoreReceiptItem.niceName(p.substring(0, eq)))
                    .append(" ×").append(p.substring(eq + 1));
            shown++;
        }
        return sb.toString();
    }

    public static synchronized String dump() {
        load();
        List<Row> list = onSale();
        if (list.isEmpty()) {
            return "（没有在售的仓单）";
        }
        StringBuilder sb = new StringBuilder();
        for (Row r : list) {
            sb.append("#").append(r.id).append("  ").append(r.price).append(" 唐兔币  卖家=")
                    .append(r.seller).append("  位置=").append(r.pos).append('\n')
                    .append("   ").append(itemsText(r.items)).append('\n');
        }
        return sb.toString();
    }
}
