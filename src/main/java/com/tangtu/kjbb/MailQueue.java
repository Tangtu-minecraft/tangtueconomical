package com.tangtu.kjbb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 离线邮寄队列（方案 A）。
 *
 * 当收件方的蛙港/邮箱所在地块没有加载时，包裹不能直接投递，
 * 先排进这个队列，等区块加载了再交给 Create 的物流目标。
 *
 * 文件：config/kjbb/mail_queue.txt
 * 格式：地址|收件人|物品id|数量|登记时间(毫秒)
 */
public final class MailQueue {

    private static final List<Entry> QUEUE = new ArrayList<>();
    private static boolean LOADED = false;
    /** 超过这个时间还没投出去，就退回发件人（7 个游戏日 ≈ 7 × 20 分钟真实时间） */
    private static final long EXPIRE_MS = 7L * 20L * 60_000L;

    private MailQueue() {
    }

    public static final class Entry {
        public String address;
        public String receiver;
        public String itemId;
        public int count;
        public long created;
    }

    private static File file() {
        try {
            java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR
                    .get().resolve("kjbb");
            java.nio.file.Files.createDirectories(dir);
            return dir.resolve("mail_queue.txt").toFile();
        } catch (Throwable t) {
            return new File("config/kjbb/mail_queue.txt");
        }
    }

    public static synchronized void load() {
        if (LOADED) {
            return;
        }
        LOADED = true;
        File f = file();
        if (!f.exists()) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\|");
                if (p.length < 5) {
                    continue;
                }
                Entry e = new Entry();
                e.address = p[0];
                e.receiver = p[1];
                e.itemId = p[2];
                try {
                    e.count = Integer.parseInt(p[3]);
                    e.created = Long.parseLong(p[4]);
                } catch (Throwable t) {
                    continue;
                }
                if (e.count > 0) {
                    QUEUE.add(e);
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读邮寄队列失败: " + t);
        }
    }

    public static synchronized void save() {
        File f = file();
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            try (PrintWriter pw = new PrintWriter(f, "UTF-8")) {
                pw.println("# address|receiver|item|count|created");
                for (Entry e : QUEUE) {
                    pw.println(e.address + "|" + e.receiver + "|" + e.itemId + "|"
                            + e.count + "|" + e.created);
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写邮寄队列失败: " + t);
        }
    }

    /** 登记一个待投递包裹 */
    public static synchronized void add(String address, String receiver, String itemId, int count) {
        load();
        Entry e = new Entry();
        e.address = address == null ? "" : address;
        e.receiver = receiver == null ? "" : receiver;
        e.itemId = itemId;
        e.count = count;
        e.created = ServerClock.now();
        QUEUE.add(e);
        save();
        DebugLog.log("邮寄排队：" + receiver + " " + itemId + " x" + count
                + " 地址=" + e.address + "（区块未加载，等加载后投递）");
    }

    /** 队列长度 */
    public static synchronized int size() {
        load();
        return QUEUE.size();
    }

    /**
     * 每个 tick 尝试投递。返回本次成功投出多少件。
     * 真正的投递委托给 AddressBook，避免这里引用 Create。
     */
    public static synchronized int tick(net.minecraft.server.MinecraftServer server) {
        load();
        if (QUEUE.isEmpty() || server == null) {
            return 0;
        }
        long now = ServerClock.now();
        int done = 0;
        Iterator<Entry> it = QUEUE.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Entry e = it.next();
            if (now - e.created > EXPIRE_MS) {
                // 过期：退回发件人（记到账本，不吞货）
                try {
                    BotLedger.addStock(e.receiver, e.itemId, e.count);
                } catch (Throwable ignored) {
                    // ignore
                }
                DebugLog.log("邮寄过期退回：" + e.receiver + " " + e.itemId + " x" + e.count);
                it.remove();
                changed = true;
                continue;
            }
            boolean ok;
            try {
                ok = AddressBook.resendQueued(server.overworld(), e.address, e.receiver,
                        e.itemId, e.count);
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) {
                it.remove();
                changed = true;
                done++;
                DebugLog.log("邮寄投出：" + e.receiver + " " + e.itemId + " x" + e.count);
            }
        }
        if (changed) {
            save();
        }
        return done;
    }
}