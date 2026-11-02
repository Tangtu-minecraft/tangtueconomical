package com.tangtu.kjbb;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 远洋提单份额的二级市场（玩家之间买卖「份」）
 *
 * 文件：config/kjbb/voyage_shares.txt
 *   id|billId|卖家|份数|单价|状态|创建时间
 *
 * 状态：OPEN / DONE / CANCELLED
 */
public final class VoyageShareMarket {

    public static final String OPEN = "OPEN";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";

    public static final class Row {
        public long id;
        public long billId;
        public String seller;
        public int shares;
        public long price;          // 每份单价
        public String status = OPEN;
        public long created;

        public Row(long id, long billId, String seller, int shares, long price, long created) {
            this.id = id;
            this.billId = billId;
            this.seller = seller;
            this.shares = shares;
            this.price = price;
            this.created = created;
        }
    }

    private static final List<Row> ROWS = new ArrayList<>();
    private static boolean LOADED = false;
    private static long NEXT_ID = 1L;

    private VoyageShareMarket() {
    }

    private static Path file() {
        return Path.of("config", "kjbb", "voyage_shares.txt");
    }

    public static synchronized void load() {
        if (LOADED) {
            return;
        }
        LOADED = true;
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
                if (f.length < 7) {
                    continue;
                }
                Row r = new Row(Long.parseLong(f[0].trim()), Long.parseLong(f[1].trim()),
                        f[2].trim(), Integer.parseInt(f[3].trim()),
                        Long.parseLong(f[4].trim()), Long.parseLong(f[6].trim()));
                r.status = f[5].trim();
                ROWS.add(r);
                NEXT_ID = Math.max(NEXT_ID, r.id + 1);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public static synchronized void save() {
        try {
            Path p = file();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# id|billId|seller|shares|price|status|created\n");
            for (Row r : ROWS) {
                sb.append(r.id).append('|').append(r.billId).append('|').append(r.seller)
                        .append('|').append(r.shares).append('|').append(r.price)
                        .append('|').append(r.status).append('|').append(r.created).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public static synchronized List<Row> open() {
        load();
        List<Row> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (OPEN.equals(r.status) && r.shares > 0) {
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

    public static synchronized Row add(long billId, String seller, int shares, long price) {
        load();
        Row r = new Row(NEXT_ID++, billId, seller, shares, price, ServerClock.now());
        ROWS.add(r);
        save();
        return r;
    }

    /** 作废某张船名下所有未成交挂单（船结算/沉没时调用） */
    public static synchronized int cancelByBill(long billId) {
        load();
        int n = 0;
        for (Row r : ROWS) {
            if (r.billId == billId && OPEN.equals(r.status)) {
                r.status = CANCELLED;
                n++;
            }
        }
        if (n > 0) {
            save();
        }
        return n;
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
}
