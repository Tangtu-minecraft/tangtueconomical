package com.tangtu.kjbb;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 远洋提单（Voyage Bill）—— 三方共同出资的航次
 *
 * 流程：
 *   ① 唐兔国航运公司「招标」：公布本次要运的货（need）+ 需要多少现金（targetCash）+ 截止日
 *   ② 经济部 / 国民 / 大A膏手 自动参与：
 *        · 出库存（contribute）→ 货物进 cargo，按市价折成份额（受库存比例上限约束）
 *        · 出钱（invest）→ 现金进资金池，按股价折成份额
 *   ③ 玩家也可以 invest / contribute，或直接 buy 份额
 *   ④ 截止日到（或份额售完）→ 用资金池按市价补足缺货 → 封盘出海
 *   ⑤ 到期掷风险 → 持股人二选一结算（提货 / 折现）
 *
 * 文件：config/kjbb/voyage_bills.txt
 *   id|name|status|total|sold|price|issueDay|dueDay|outcome|cargo|holders|cash|targetCash|deadline|need
 */
public final class VoyageBill {
    /** 每条船的装货成本（内存记录，重启丢失则按 0 处理） */
    private static final java.util.Map<Long, Long> CARGO_COST =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 登记装货成本 */
    public static void addCargoCost(long billId, long amount) {
        if (amount > 0L) {
            CARGO_COST.merge(billId, amount, Long::sum);
        }
    }

    /** 取装货成本 */
    public static long cargoCost(long billId) {
        return CARGO_COST.getOrDefault(billId, 0L);
    }


    public static final String OPEN = "OPEN";
    public static final String VOYAGING = "VOYAGING";
    public static final String DUE = "DUE";
    public static final String CLOSED = "CLOSED";

    public static final String OUT_GOOD = "顺风";
    public static final String OUT_FAIR = "逆风";
    public static final String OUT_RISK = "遇险";
    public static final String OUT_SUNK = "沉没";

    /**
     * 载货单候选池（按正式服真实流通品类整理）
     *   民生 50% / 工业 35% / 高价 15%
     */
    private static final String[] POOL_FOOD = {
            "minecraft:wheat", "minecraft:potato", "minecraft:carrot",
            "minecraft:beetroot", "minecraft:coal",
    };
    private static final String[] POOL_RAW = {
            "minecraft:copper_ingot", "create:zinc_ingot", "minecraft:iron_ingot",
            "create:andesite_alloy", "create:cogwheel",
    };
    private static final String[] POOL_INDUSTRY = {
            "create:electron_tube", "create:copper_casing", "create:brass_casing",
            "create:precision_mechanism", "create:railway_casing",
            "create:mechanical_arm", "create:crushing_wheel",
    };
    private static final String[] POOL_LUXURY = {
            "minecraft:emerald", "minecraft:diamond", "minecraft:netherite_scrap",
            "minecraft:amethyst_shard",
    };

    public static final class Bill {
        public long id;
        public String name;
        public String status = OPEN;
        public int total = 1000;
        public int sold;
        public long price;
        public long issueDay;
        public long dueDay;
        public String outcome = "";
        public final Map<String, Integer> cargo = new LinkedHashMap<>();
        public final Map<String, Integer> need = new LinkedHashMap<>();
        public final Map<String, Integer> holders = new LinkedHashMap<>();
        public long cash;
        /** 罗马国已付的货款（到港时结算，直接进 cash） */
        public long revenue;
        /** 到港那一刻的本地估值快照（货卖给罗马国后 cargo 会清空，兑付要用它） */
        public long arrivalValue;
        /** 包船：玩家自己出货运货，货款归包船人，公司只收运费 */
        public boolean charter;
        public String charterer = "";
        public long freight;
        /** 往返：中途是否已经完成「交货给罗马国 + 装返程货」 */
        public boolean imported;
        /** 返程货的进货成本（从出口货款里扣） */
        public long importCost;
        public boolean charterSailed;
        public long targetCash;
        public long deadline;
        /** 航行分段：总段数 / 已完成段数 / 船况 0~100 / 累计倍率(百分比) / 下一段判定日 */
        public int legs = 4;
        public int legDone;
        public int condition = 100;
        public int mult = 100;
        public long legNextDay = -1L;
        /** 出海日（用于按比例排航段） */
        public long departDay = -1L;
        /** 每段事件记录（顺风/风暴/…） */
        public final java.util.List<String> legLog = new java.util.ArrayList<>();

        public int heldBy(String player) {
            return holders.getOrDefault(player, 0);
        }

        public long cargoValue() {
            long sum = 0L;
            for (var e : cargo.entrySet()) {
                sum += priceOf(e.getKey()) * e.getValue();
            }
            return sum;
        }

        public long needValue() {
            long sum = 0L;
            for (var e : need.entrySet()) {
                sum += priceOf(e.getKey()) * e.getValue();
            }
            return sum;
        }

        public int missing(String itemId) {
            int want = need.getOrDefault(itemId, 0);
            int have = cargo.getOrDefault(itemId, 0);
            return Math.max(0, want - have);
        }

        public long shareValue() {
            if (total <= 0L) {
                return 0L;
            }
            // 到港后货已卖给罗马国，cargo 会被清空，所以用快照估值
            // 方案 A：到港前价值未知（罗马行情、事件倍率都会改变结果），一律返回 0
            if (arrivalValue <= 0L && (OPEN.equals(status) || VOYAGING.equals(status))) {
                return 0L;
            }
            // 收益权（方案 C）：总收益 ÷ 总份数（分母恒为 total=143）
            // base = 已实现现金 + 剩余货物估值；不能再和 arrivalValue 相加，
            // 否则「卖货所得」会被算两遍（arrivalValue 只是货值快照）。
            // 已结算但还有未领的钱：玩家凭票应拿到「货值 + 未领现金」的份额
            long pool = Math.max(0L, cash);
            long goods = cargoValue();
            long base = pool + goods;
            if (base <= 0L) {
                base = arrivalValue;          // 兜底：只拿到货值快照时用它
            }
            return base / total;
        }

        public double riskMultiplier() {
            if (mult >= 0 && (legDone > 0 || mult != 100)) {
                return Math.max(0.0, Math.min(1.2, mult / 100.0));
            }
            if (OUT_GOOD.equals(outcome)) {
                return 1.0;
            }
            if (OUT_FAIR.equals(outcome)) {
                return 0.8;
            }
            if (OUT_RISK.equals(outcome)) {
                return 0.5;
            }
            if (OUT_SUNK.equals(outcome)) {
                return 0.0;
            }
            return 1.0;
        }
    }

    /** 船名池：罗马风味 */
    private static final String[] SHIP_NAMES = {
        "阿奎拉之鹰", "维斯塔之火", "涅普顿之潮", "玛尔斯之矛", "朱庇特之雷",
        "密涅瓦之智", "刻瑞斯之穗", "墨丘利之翼", "阿波罗之光", "狄安娜之月",
        "武尔坎之炉", "萨图恩之环", "雅努斯之门", "福尔图娜之轮", "维克托利亚之冠",
        "奥斯特之东", "蓬托斯之渊", "奥罗拉之晨", "蓬波尼娅之舟", "卡皮托利之丘",
        "台伯河守望", "奥斯提亚之光", "拜占庭之塔", "亚历山大之灯", "叙拉古之帆",
        "迦太基之影", "高卢之冠", "不列颠之雾", "伊比利亚之脊", "埃及之星",
        "塞维鲁之剑", "奥古斯都凯旋", "图拉真之柱", "哈德良之墙", "加图之誓",
        "庞培之帆", "凯撒之鹰", "克拉苏之金", "西塞罗之辩", "卡托之简",
        "莱茵河之狼", "黑海之盐", "红海之帆", "格劳克斯之歌", "斯库拉之牙"
    };

    /** 从名字池里随机抽一个船名 */
    private static String randomShipName(long id) {
        int i = (int) Math.abs((id * 2_654_435_761L) % SHIP_NAMES.length);
        return SHIP_NAMES[i];
    }    private static final List<Bill> BILLS = new ArrayList<>();
    private static boolean LOADED = false;
    private static long NEXT_ID = 1L;
    private static long LAST_ISSUE_DAY = -1L;
    /** 每个航次、每个 NPC 只自动参与一次 */
    private static final java.util.Set<String> NPC_DONE = new java.util.HashSet<>();
    /** 每艘船最后一次「船东收货」的游戏日，保证一天只收一次 */
    private static final java.util.Map<Long, Long> LAST_BUY = new java.util.HashMap<>();

    private VoyageBill() {
    }

    public static String issuerName() {
        try {
            for (BotConfig.Bot b : BotConfig.get().bots) {
                if (b != null && BotConfig.ROLE_SHIPPING.equals(b.role)) {
                    return b.name;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return "唐兔国航运公司";
    }

    private static long priceOf(String itemId) {
        long p = MarketPrice.price(itemId);
        if (p <= 0L) {
            p = ShopPrices.price(itemId);
        }
        return p <= 0L ? 1L : p;
    }

    private static Path file() {
        return Path.of("config", "kjbb", "voyage_bills.txt");
    }

    // ---------------- 持久化 ----------------

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
                if (line != null && line.startsWith("#LAST_ISSUE|")) {
                    // 上次发船日：不落盘的话，每次重启都会立刻补开一艘
                    try {
                        LAST_ISSUE_DAY = Long.parseLong(line.substring("#LAST_ISSUE|".length()).trim());
                    } catch (Throwable ignored) {
                        // 坏行忽略
                    }
                    continue;
                }
                if (line == null || line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length < 11) {
                    continue;
                }
                Bill b = new Bill();
                b.id = Long.parseLong(f[0].trim());
                b.name = f[1].trim();
                b.status = f[2].trim();
                b.total = Integer.parseInt(f[3].trim());
                b.sold = Integer.parseInt(f[4].trim());
                b.price = Long.parseLong(f[5].trim());
                b.issueDay = Long.parseLong(f[6].trim());
                b.dueDay = Long.parseLong(f[7].trim());
                b.outcome = f[8].trim();
                parseMap(f[9], b.cargo);
                parseMap(f[10], b.holders);
                if (f.length > 11 && !f[11].isBlank()) {
                    b.cash = Long.parseLong(f[11].trim());
                }
                if (f.length > 12 && !f[12].isBlank()) {
                    b.targetCash = Long.parseLong(f[12].trim());
                }
                if (f.length > 13 && !f[13].isBlank()) {
                    b.deadline = Long.parseLong(f[13].trim());
                }
                if (f.length > 14) {
                    parseMap(f[14], b.need);
                }
                if (f.length > 15) {
                    b.legs = Math.max(1, Integer.parseInt(f[15].trim()));
                }
                if (f.length > 16) {
                    b.legDone = Integer.parseInt(f[16].trim());
                }
                if (f.length > 17) {
                    b.condition = Integer.parseInt(f[17].trim());
                }
                if (f.length > 18) {
                    b.mult = Integer.parseInt(f[18].trim());
                }
                if (f.length > 19) {
                    b.legNextDay = Long.parseLong(f[19].trim());
                }
                if (f.length > 21 && !f[21].isBlank()) {
                    b.departDay = Long.parseLong(f[21].trim());
                }
                if (f.length > 20 && !f[20].isBlank()) {
                    for (String s : f[20].split(";")) {
                        if (!s.isBlank()) {
                            b.legLog.add(s.trim());
                        }
                    }
                }
                if (f.length > 22 && !f[22].isBlank()) {
                    try {
                        b.revenue = Long.parseLong(f[22].trim());
                    } catch (Throwable ignored) {
                        // 老存档没有这一列
                    }
                }
                if (f.length > 23 && !f[23].isBlank()) {
                    try {
                        b.arrivalValue = Long.parseLong(f[23].trim());
                    } catch (Throwable ignored) {
                        // 老存档没有这一列
                    }
                }
                if (f.length > 24 && !f[24].isBlank()) {
                    b.charter = "1".equals(f[24].trim());
                }
                if (f.length > 25) {
                    b.charterer = f[25].trim();
                }
                if (f.length > 26 && !f[26].isBlank()) {
                    try {
                        b.freight = Long.parseLong(f[26].trim());
                    } catch (Throwable ignored) {
                        // 老存档没有这一列
                    }
                }
                if (f.length > 27) {
                    b.imported = "1".equals(f[27].trim());
                }
                if (f.length > 28 && !f[28].isBlank()) {
                    try {
                        b.importCost = Long.parseLong(f[28].trim());
                    } catch (Throwable ignored) {
                        // 老存档没有这一列
                    }
                }
                BILLS.add(b);
                NEXT_ID = Math.max(NEXT_ID, b.id + 1);
            }
        } catch (Throwable t) {
            DebugLog.log("远洋提单读取失败: " + t);
        }
    }

    private static void parseMap(String s, Map<String, Integer> out) {
        if (s == null || s.isBlank()) {
            return;
        }
        for (String kv : s.split(";")) {
            if (!kv.contains("=")) {
                continue;
            }
            String[] p = kv.split("=", 2);
            try {
                out.put(p[0].trim(), Integer.parseInt(p[1].trim()));
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static String joinMap(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (var e : m.entrySet()) {
            if (e.getValue() == null || e.getValue() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static synchronized void save() {
        try {
            Path p = file();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# id|name|status|total|sold|price|issueDay|dueDay|outcome|cargo|holders|cash|targetCash|deadline|need\n");
            sb.append("#LAST_ISSUE|").append(LAST_ISSUE_DAY).append('\n');
            for (Bill b : BILLS) {
                sb.append(b.id).append('|').append(b.name).append('|').append(b.status)
                        .append('|').append(b.total).append('|').append(b.sold)
                        .append('|').append(b.price).append('|').append(b.issueDay)
                        .append('|').append(b.dueDay).append('|').append(b.outcome)
                        .append('|').append(joinMap(b.cargo))
                        .append('|').append(joinMap(b.holders))
                        .append('|').append(b.cash)
                        .append('|').append(b.targetCash)
                        .append('|').append(b.deadline)
                        .append('|').append(joinMap(b.need))
                        .append('|').append(b.legs)
                        .append('|').append(b.legDone)
                        .append('|').append(b.condition)
                        .append('|').append(b.mult)
                        .append('|').append(b.legNextDay)
                        .append('|').append(String.join(";", b.legLog))
                        .append('|').append(b.departDay)
                        .append('|').append(b.revenue)
                        .append('|').append(b.arrivalValue)
                        .append('|').append(b.charter ? 1 : 0)
                        .append('|').append(b.charterer)
                        .append('|').append(b.freight)
                        .append('|').append(b.imported ? 1 : 0)
                        .append('|').append(b.importCost)
                        .append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ---------------- 查询 ----------------

    public static synchronized List<Bill> all() {
        load();
        return new ArrayList<>(BILLS);
    }

    /** id → 提单 的索引，避免每次 get 都全表扫（航次多起来后是明显的热点） */
    private static final java.util.Map<Long, Bill> BY_ID = new java.util.HashMap<>();

    private static void reindexBills() {
        BY_ID.clear();
        for (Bill b : BILLS) {
            BY_ID.put(b.id, b);
        }
    }

    public static synchronized Bill get(long id) {
        load();
        Bill hit = BY_ID.get(id);
        if (hit != null) {
            return hit;
        }
        // 索引可能因为 BILLS 被外部改动而失效，兜底重建一次
        reindexBills();
        hit = BY_ID.get(id);
        if (hit != null) {
            return hit;
        }
        for (Bill b : BILLS) {
            if (b.id == id) {
                BY_ID.put(id, b);
                return b;
            }
        }
        return null;
    }

    /** 当前招标中的航次（给机器人用；没有则 null） */
    public static synchronized Bill openBill() {
        return activeBill();
    }

    private static Bill activeBill() {
        load();
        for (int i = BILLS.size() - 1; i >= 0; i--) {
            Bill b = BILLS.get(i);
            if (OPEN.equals(b.status) && !b.charter) {
                return b;
            }
        }
        return null;
    }

    // ---------------- 招标（发行） ----------------

    public static synchronized Bill issue(String company, long day) {
        load();
        var cfg = BotConfig.get();
        Bill b = new Bill();
        b.id = NEXT_ID++;
        b.name = randomShipName(b.id) + "号";
        b.status = OPEN;
        b.total = Math.max(1, cfg.voyageTotalShares);
        b.sold = 0;
        b.issueDay = day;
        b.deadline = day + Math.max(1, cfg.voyageFundDays);
        // 到港日在「出海」时才确定（募资期不占航期）
        b.dueDay = -1L;
        buildNeed(b);
        long value = b.needValue();
        // ① 份数按货值自适应：每份约 200 币，夹在 50~2000 份之间
        int totalShares = (int) Math.max(50L, Math.min(2000L, value / 200L));
        b.total = totalShares;
        double disc = Math.max(0.5, Math.min(1.0, BotConfig.get().voyageDiscount));
        b.price = Math.max(1L, Math.round(value * disc / Math.max(1L, totalShares)));
        b.targetCash = Math.round(value * 0.25);
        // ② 船东分日收货：招标当天先买三分之一，之后每天再买三分之一
        LAST_BUY.put(b.id, day);
        companyStockUp(b, Math.max(1L, b.needValue() / 3L));
        BILLS.add(b);
        NPC_DONE.removeIf(k -> k.startsWith(b.id + ":"));
        LAST_ISSUE_DAY = day;
        save();
        DebugLog.log(company + " 招标 #" + b.id + " " + b.name + "：载货 "
                + b.need.size() + " 种（目标货值 " + value + "），需现金 " + b.targetCash
                + "，募资截止 " + com.tangtu.kjbb.ServerCalendar.shortDate(b.deadline)
                + "，到港 " + com.tangtu.kjbb.ServerCalendar.shortDate(b.dueDay));
        return b;
    }

    /** 按「目标货值」拟定载货单（不再受库存限制） */
    private static void buildNeed(Bill b) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        var cfg = BotConfig.get();
        long target = Math.max(1000L, cfg.voyageTargetValue);
        int kinds = 4 + rnd.nextInt(2);
        int minQ = Math.max(1, cfg.voyageNeedMin);
        int maxQ = Math.max(minQ, cfg.voyageNeedMax);
        long perKind = target / Math.max(1, kinds);

        java.util.Map<String, Integer> combined = new java.util.LinkedHashMap<>();
        try {
            for (BotConfig.Bot bot : cfg.bots) {
                if (bot == null || BotConfig.ROLE_SHIPPING.equals(bot.role)) {
                    continue;
                }
                for (var e : BotLedger.stockMap(bot.name).entrySet()) {
                    if (e.getValue() != null && e.getValue() > 0) {
                        combined.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // 候选顺序：玩家生产榜 → 数据包载货池 → 内置兜底
        java.util.List<String> cand = new java.util.ArrayList<>(liveProduced(combined));
        for (String k : VoyageCargo.pool().keySet()) {
            if (!cand.contains(k)) {
                cand.add(k);
            }
        }
        for (String k : POOL_FOOD) {
            if (!cand.contains(k)) {
                cand.add(k);
            }
        }
        for (String k : POOL_RAW) {
            if (!cand.contains(k)) {
                cand.add(k);
            }
        }
        for (String k : POOL_INDUSTRY) {
            if (!cand.contains(k)) {
                cand.add(k);
            }
        }
        for (String k : POOL_LUXURY) {
            if (!cand.contains(k)) {
                cand.add(k);
            }
        }

        // 拆成「原版 / 模组」两组，保证混搭
        java.util.List<String> vanilla = new java.util.ArrayList<>();
        java.util.List<String> modded = new java.util.ArrayList<>();
        for (String item : cand) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (item.startsWith("minecraft:")) {
                vanilla.add(item);
            } else {
                modded.add(item);
            }
        }
        int wantMod = Math.max(1, kinds / 2);
        int pickMod = 0;
        // 先按「产量权重最高的顺序」挑，但优先补齐模组配额
        for (String item : modded) {
            if (pickMod >= wantMod || b.need.size() >= kinds) {
                break;
            }
            if (addOne(b, item, perKind, minQ, maxQ)) {
                pickMod++;
            }
        }
        for (String item : vanilla) {
            if (b.need.size() >= kinds) {
                break;
            }
            addOne(b, item, perKind, minQ, maxQ);
        }
        for (String item : modded) {
            if (b.need.size() >= kinds) {
                break;
            }
            addOne(b, item, perKind, minQ, maxQ);
        }
        if (b.need.isEmpty()) {
            b.need.put("minecraft:coal", Math.max(minQ, 32));
        }

        // ---- 按货值补足 ----
        // 单种有 maxQ 上限（默认 1024 件），廉价货靠一种永远凑不满目标货值，
        // 所以这里继续从候选池里加种类，直到货值达到目标的 90% 或种类数到顶。
        int maxKinds = Math.max(kinds, 10);
        int guard = 0;
        while (b.needValue() * 100L < target * 90L && b.need.size() < maxKinds && guard++ < 40) {
            long remain = Math.max(perKind, target - b.needValue());
            boolean added = false;
            for (String item : cand) {
                if (item == null || item.isEmpty() || b.need.containsKey(item)) {
                    continue;
                }
                if (addOne(b, item, remain, minQ, maxQ)) {
                    added = true;
                    break;
                }
            }
            if (!added) {
                break;
            }
        }
    }

    /** 玩家真实生产、且三方有库存、且有价格的品类（按产量排序） */
    private static java.util.List<String> liveProduced(java.util.Map<String, Integer> combined) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (var e : PlayerProduction.topProduced(24)) {
                String item = e.getKey();
                if (combined.getOrDefault(item, 0) < 16) {
                    continue;
                }
                if (priceOf(item) <= 1L) {
                    continue;
                }
                out.add(item);
                if (out.size() >= 8) {
                    break;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return out;
    }

    /** 往载货单加一种货，成功返回 true */
    private static boolean addOne(Bill b, String item, long perKind, int minQ, int maxQ) {
        if (item == null || b.need.containsKey(item)) {
            return false;
        }
        long p = priceOf(item);
        if (p <= 0L) {
            return false;
        }
        long qty = perKind / Math.max(1L, p);
        int floorQ = p >= 500L ? 4 : (p >= 100L ? 8 : minQ);
        qty = Math.max(floorQ, Math.min(maxQ, qty));
        long capByBudget = Math.max(floorQ, (perKind * 16 / 10) / Math.max(1L, p));
        qty = Math.min(qty, capByBudget);
        if (qty <= 0) {
            return false;
        }
        b.need.put(item, (int) qty);
        return true;
    }

    private static String pickFrom(String[] pool, ThreadLocalRandom rnd) {
        return pool[rnd.nextInt(pool.length)];
    }

    private static void addPick(Bill b, java.util.Map<String, Integer> combined,
                                String item, ThreadLocalRandom rnd) {
        if (item == null || b.need.containsKey(item)) {
            return;
        }
        int have = combined.getOrDefault(item, 0);
        if (have < 16) {
            return;
        }
        // 需求量 = 三方合计库存的 25%，夹在 8~256 之间
        int qty = Math.max(8, Math.min(256, have / 4));
        b.need.put(item, qty);
    }

    /** 这个名字是不是机器人（包括船东） */
    private static boolean isBotAccount(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.equals(issuerName())) {
            return true;
        }
        try {
            for (BotConfig.Bot b : BotConfig.get().bots) {
                if (b != null && name.equals(b.name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return false;
    }

    /** 统一查余额：机器人查账本，玩家查钱包 */
    public static long balanceOf(String who) {
        return isBotAccount(who) ? BotLedger.money(who) : BankBridge.money(who);
    }

    /** 统一扣钱（成功返回 true） */
    private static boolean debit(String who, long amount) {
        if (amount <= 0L) {
            return false;
        }
        if (isBotAccount(who)) {
            if (BotLedger.money(who) < amount) {
                return false;
            }
            BotLedger.addMoney(who, -amount);
            return true;
        }
        long before = BankBridge.money(who);
        long after = BankBridge.takeMoney(who, amount);
        return before - after >= amount;
    }

    /** 统一加钱 */
    private static void credit(String who, long amount) {
        if (amount <= 0L) {
            return;
        }
        if (isBotAccount(who)) {
            BotLedger.addMoney(who, amount);
        } else {
            BankBridge.addMoney(who, amount);
        }
    }

    /** NPC（含船东）最多能持有的份额：留 30% 给玩家 */
    private static int npcShareCap(Bill b) {
        return (int) Math.max(1L, Math.round(b.total * 0.7));
    }

    /** 当前 NPC（含船东）已持有的份额 */
    private static int npcHeld(Bill b) {
        int n = 0;
        java.util.Set<String> names = new java.util.HashSet<>();
        try {
            for (BotConfig.Bot bot : BotConfig.get().bots) {
                if (bot != null) {
                    names.add(bot.name);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        names.add(issuerName());
        for (var e : b.holders.entrySet()) {
            if (names.contains(e.getKey())) {
                n += e.getValue();
            }
        }
        return n;
    }

    /** NPC 还能拿多少份 */
    private static int npcRoom(Bill b) {
        return Math.max(0, npcShareCap(b) - npcHeld(b));
    }

    /** 船东用自有资金按市价补货（换来份额），把载货单填起来 */
    private static void companyStockUp(Bill b, long budgetCap) {
        try {
            String issuer = issuerName();
            // 船东最多持有 40% 份额，其余留给 NPC 与玩家
            int capShares = (int) Math.max(1L, Math.round(b.total * 0.4));
            int alreadyHas = b.heldBy(issuer);
            int room = Math.min(Math.max(0, capShares - alreadyHas), npcRoom(b));
            long maxValue = (long) room * Math.max(1L, b.price);
            long budget = Math.min(Math.min(BotLedger.money(issuer) * 7 / 10, maxValue),
                    Math.max(0L, budgetCap));
            if (budget <= 0L) {
                return;
            }
            if (budget <= 0L) {
                return;
            }
            long spent = 0L;
            long left = budget;
            long today = com.tangtu.kjbb.ServerCalendar.currentDay();
            int daysLeft = (int) Math.max(1L, b.deadline - today + 1L);
            for (String item : new ArrayList<>(b.need.keySet())) {
                int miss = b.missing(item);
                if (miss <= 0) {
                    continue;
                }
                long p = priceOf(item);
                if (left < p) {
                    break;
                }
                // 把缺口平均摊到剩余天数里，避免一次性只买一种
                int want = Math.max(1, miss / daysLeft);
                want = Math.min(want, miss);
                long cost = p * want;
                if (cost > left) {
                    want = (int) (left / p);
                    cost = p * want;
                }
                if (want <= 0) {
                    break;
                }
                b.cargo.merge(item, want, Integer::sum);
                spent += cost;
                left -= cost;
            }
            if (spent > 0L) {
                BotLedger.addMoney(issuer, -spent);
            addCargoCost(b.id, spent);
                // 船东自购上限：不能超过总份数 × voyageIssuerMaxSharePermille
                {
                    int cap = Math.max(1, b.total * Math.max(0, Math.min(1000,
                            BotConfig.get().voyageIssuerMaxSharePermille)) / 1000);
                    int has = b.holders.getOrDefault(issuer, 0);
                    if (has >= cap) {
                        return;
                    }
                    room = Math.min(room, cap - has);
                }
                int shares = (int) Math.min(Math.min(b.total - b.sold, room),
                        spent / Math.max(1L, b.price));
                if (shares > 0) {
                    b.sold = Math.min(b.total, b.sold + shares);
                    b.holders.merge(issuer, shares, Integer::sum);
                }
                BotTrades.log(issuer, "-", "航次备货#" + b.id, 1, spent, "备货");
                DebugLog.log("航次收货 #" + b.id + " 第 "
                        + com.tangtu.kjbb.ServerCalendar.currentDay() + " 日：船东自购 " + spent
                        + " 唐兔币的货（换 " + Math.max(0, shares) + " 份）");
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ---------------- 出资 / 出货 ----------------

    /** 现金出资（任何账户：玩家或 NPC） */
    public static synchronized String invest(String who, long amount) {
        load();
        Bill b = activeBill();
        if (b == null) {
            return "§c现在没有招标中的航次";
        }
        if (amount <= 0L) {
            return "§c金额要大于 0";
        }
        int room = b.total - b.sold;
        if (room <= 0) {
            return "§c这艘船的份额已经卖完，无法再出资";
        }
        int wantShares = (int) (amount / Math.max(1L, b.price));
        if (wantShares <= 0) {
            return "§c金额太少（每份 " + b.price + " 唐兔币）";
        }
        int shares = Math.min(room, wantShares);
        long realCost = (long) shares * Math.max(1L, b.price);
        long have = balanceOf(who);
        if (have < realCost) {
            return "§c唐兔币不够：需要 " + realCost + "，你有 " + have;
        }
        if (!debit(who, realCost)) {
            return "§c扣款失败";
        }
        b.cash += realCost;
        if (shares > 0) {
            b.sold = Math.min(b.total, b.sold + shares);
            grantShares(b, who, shares, "出资");
        }
        save();
        BotTrades.log(who, issuerName(), "航次出资#" + b.id, 1, realCost, "出资");
        DebugLog.log("航次出资 #" + b.id + " " + who + " 出 " + realCost
                + " 换 " + Math.max(0, shares) + " 份（资金池 " + b.cash + "）");
        return "§a已出资 " + realCost + " 唐兔币，换得 " + shares + " 份，股票已放进背包";
    }

    /** 出库存（受库存比例上限约束） */
    public static synchronized String contribute(String who, String itemId, int want) {
        load();
        Bill b = activeBill();
        if (b == null) {
            return "§c现在没有招标中的航次";
        }
        if (!b.need.containsKey(itemId)) {
            return "§c这次不运 " + itemId;
        }
        if (want <= 0) {
            return "§c数量要大于 0";
        }
        int have = BotLedger.stock(who, itemId);
        double ratio = Math.max(0.0, Math.min(1.0,
                BotConfig.get().voyageStockPermille / 1000.0));
        int cap = (int) Math.floor(have * ratio);
        int give = Math.min(want, Math.min(cap, b.missing(itemId)));
        if (give <= 0) {
            return "§c可交数量不足（持有 " + have + "，上限 " + cap
                    + "，还缺 " + b.missing(itemId) + "）";
        }
        BotLedger.addStock(who, itemId, -give);
        b.cargo.merge(itemId, give, Integer::sum);
        long value = priceOf(itemId) * give;
        int shares = (int) Math.min(b.total - b.sold, value / Math.max(1L, b.price));
        if (shares > 0) {
            b.sold = Math.min(b.total, b.sold + shares);
            grantShares(b, who, shares, "出货");
        }
        save();
        BotTrades.log(who, issuerName(), "航次出货#" + b.id, give, priceOf(itemId), "出货");
        DebugLog.log("航次出货 #" + b.id + " " + who + " 交 " + itemId + " ×" + give
                + "（货值 " + value + "，换 " + Math.max(0, shares) + " 份）");
        return "§a已交货 " + give + " 件（货值 " + value + "，换得 "
                + Math.max(0, shares) + " 份，股票已放进背包）";
    }

    /** 玩家直接买份额 */
    public static synchronized String buy(ServerPlayer sp, long billId, int shares) {
        load();
        Bill b = get(billId);
        if (b == null) {
            return "§c没有这张提单";
        }
        if (!OPEN.equals(b.status)) {
            return "§c这艘船已经封盘（" + b.status + "）";
        }
        if (shares <= 0) {
            return "§c份数要大于 0";
        }
        int left = b.total - b.sold;
        if (left <= 0) {
            return "§c份额已售完";
        }
        if (shares > left) {
            return "§c只剩 " + left + " 份";
        }
        long cost = b.price * shares;
        String me = sp.getScoreboardName();
        long have = balanceOf(me);
        if (have < cost) {
            return "§c唐兔币不够：需要 " + cost + "，你有 " + have;
        }
        if (!debit(me, cost)) {
            return "§c扣款失败";
        }
        b.cash += cost;
        b.sold = Math.min(b.total, b.sold + shares);
        // 玩家认购：直接发实体股票（不再记账，避免双重兑付；同航次已有票就加份数）
        giveStock(sp, b, shares, "认购");
        save();
        BotTrades.log(me, issuerName(), "远洋提单认购#" + b.id, shares, b.price, "认购");
        return "§a已认购 " + shares + " 份（花 " + cost + " 唐兔币），股票已放进背包";
    }

    // ---------------- 每 tick ----------------

    public static void tick(net.minecraft.server.MinecraftServer server) {
        load();
        long day = com.tangtu.kjbb.ServerCalendar.currentDay();
        boolean changed = false;

        Bill open = activeBill();
        if (open != null && day <= open.deadline) {
            changed |= npcJoin(open);
        }

        for (Bill b : new ArrayList<>(BILLS)) {
            if (!OPEN.equals(b.status)) {
                continue;
            }
            if (b.charter) {
                // 包船不招标、不募资、不自动出海，等包船人 /billvoyage sail
                continue;
            }
            // 分日收货：每个游戏日船东再买三分之一（到截止日为止）
            long lastBuy = LAST_BUY.getOrDefault(b.id, Long.MIN_VALUE);
            if (day > lastBuy && day <= b.deadline) {
                companyStockUp(b, Math.max(1L, b.needValue() / 3L));
                LAST_BUY.put(b.id, day);
                changed = true;
            }
            // 固定募资期：到截止日才出海（不再「卖完就走」）
            if (day < b.deadline) {
                continue;
            }
            // 认购太少（不足总量 30%）→ 取消航次并退款
            if (b.sold * 100L < (long) b.total * 30L) {
                cancelVoyage(b);
            } else {
                depart(b);
            }
            changed = true;
        }

        // 航行中：逐段判定风险
        for (Bill b : new ArrayList<>(BILLS)) {
            if (!VOYAGING.equals(b.status)) {
                continue;
            }
            while (b.legDone < b.legs && b.legNextDay > 0 && day >= b.legNextDay
                    && (b.dueDay <= 0L || b.legNextDay < b.dueDay)) {
                advanceLeg(b, day);
                changed = true;
            }
        }

        for (Bill b : new ArrayList<>(BILLS)) {
            boolean active = (OPEN.equals(b.status) || VOYAGING.equals(b.status));
            if (!active || b.dueDay <= 0L || day < b.dueDay) {
                continue;
            }
            if (b.sold <= 0 && !b.charter) {
                b.status = CLOSED;
                com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
                b.outcome = "";
                DebugLog.log("远洋提单 #" + b.id + " 无人出资，到港前作废");
            } else if (b.mult >= 100) {
                b.outcome = OUT_GOOD;
                applyOutcome(b);
            } else if (b.mult >= 75) {
                b.outcome = OUT_FAIR;
                applyOutcome(b);
            } else if (b.mult > 0) {
                b.outcome = OUT_RISK;
                applyOutcome(b);
            } else {
                b.outcome = OUT_SUNK;
                applyOutcome(b);
            }
            // 往返航次回到唐兔国：先卸返程货（公司按本地价收走大部分，留一部分给持股人提货）
            if (BotConfig.get().voyageRoundTrip && b.imported
                    && !OUT_SUNK.equals(b.outcome)) {
                unloadReturnCargo(b);
            }
            // 先记到港估值快照：罗马收货会清空 cargo，兑付要用这个数
            if (b.arrivalValue <= 0L) {
                // 往返航次：留给持股人提货的那部分货**不计入**可折现总额，
                // 否则「折现拿钱 + 提货拿货」会双重支付。
                // 收益归一化：按当前风险分布反推，使整体数学期望 = +5%
        try {
            long cost = b.total * b.price;
            if (cost > 0L) {
                var ev = BotConfig.get().voyageEvents;
                double tg = Math.max(0, ev.tGood) / 100.0;
                double tc = Math.max(0, ev.tCalm) / 100.0;
                double tb = Math.max(0, ev.tBad) / 100.0;
                double ts = Math.max(0, ev.tStorm) / 100.0;
                double tp = Math.max(0, ev.tPirate) / 100.0;
                double pg = tg;
                double pc = Math.max(0, tc - tg);
                double pb = Math.max(0, tb - tc);
                double ps = Math.max(0, ts - tb);
                double pp = Math.max(0, tp - ts);
                double pr = Math.max(0, Math.min(1.0, (100 - tp) / 100.0));
                double pSink = Math.max(0, Math.min(1.0, ev.sinkPct / 100.0));
                double em = pg * (1 + ev.dGood / 100.0) + pc * 1.0
                        + pb * (1 + ev.dBad / 100.0) + ps * (1 + ev.dStorm / 100.0)
                        + pp * (1 + ev.dPirate / 100.0) + pr * (1 + ev.dReef / 100.0);
                double expected = Math.max(0.01, (1.0 - pSink) * em);
                double need = 1.05 / expected;          // 基准倍率
                double mult = Math.max(0.0, b.mult / 100.0);
                // 装货成本也计入本金：装得好 → 收益高
                long cargoSpent = cargoCost(b.id);
                long principal = cost + Math.max(0L, cargoSpent);
                long target = Math.round(principal * need * mult);
                // 装货成本先还给船东（保本 + 同样的收益率）
                long cargoBack = Math.round(Math.max(0L, cargoSpent) * need * mult);
                if (cargoBack > 0L) {
                    try {
                        BotLedger.addMoney(issuerName(), cargoBack);
                        BotTrades.log(issuerName(), "-", "航次货本回收#" + b.id,
                                (int) Math.min(Integer.MAX_VALUE, cargoSpent), cargoBack,
                                "货本回收");
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
                target -= cargoBack;
                target = Math.max(0L, target);
                long have = Math.max(0L, b.cash);
                if (have != target) {
                    DebugLog.log("航次 #" + b.id + " 收益归一：现金 " + have + " → " + target
                            + "（倍率 " + String.format(java.util.Locale.ROOT, "%.2f", mult)
                            + "，风险期望 " + String.format(java.util.Locale.ROOT, "%.3f", expected)
                            + "）");
                }
                b.cash = target;
                b.cargo.clear();                        // 货已卖给罗马，结算按现金算
            }
        } catch (Throwable ignored) {
            // 归一失败不影响结算
        }
        b.arrivalValue = b.imported ? Math.max(0L, b.cash) : b.cargoValue();
        try {
            CARGO_COST.remove(b.id);
        } catch (Throwable ignored) {
            // ignore
        }
            }
            if (OUT_SUNK.equals(b.outcome)) {
                // 沉没：玩家挂在这艘船上的罗马国订单一起作废（货与预付款都不退）
                try {
                    RomaOrders.onSunk(b, day);
                } catch (Throwable t) {
                    DebugLog.log("罗马国订单作废失败 #" + b.id + "：" + t);
                }
            } else {
                // 到港且没沉没：罗马国按行情收货，货款进资金池，成为兑付的真实来源
                if (b.sold > 0 || b.charter) {
                    try {
                        RomaMarket.settle(b, day);
                    } catch (Throwable t) {
                        DebugLog.log("罗马国收货失败 #" + b.id + "：" + t);
                    }
                }
                // 玩家挂在船上做买卖的订单：出口付钱、进口交货
                try {
                    RomaOrders.settleArrival(b, day, server);
                } catch (Throwable t) {
                    DebugLog.log("罗马国订单结算失败 #" + b.id + "：" + t);
                }
            }
            changed = true;
        }

        int activeCount = 0;
        for (Bill bb : BILLS) {
            if (!bb.charter && (OPEN.equals(bb.status) || VOYAGING.equals(bb.status))) {
                activeCount++;
            }
        }
        int maxConcurrent = 1;
        try {
            maxConcurrent = Math.max(1, BotConfig.get().voyageMaxConcurrent);
        } catch (Throwable ignored) {
            maxConcurrent = 1;
        }
        boolean hasActive = activeCount >= maxConcurrent;
        // 只有「上一艘船已经结束」时才自动续开；空表/刚清档不主动发船
        if (!hasActive && !BILLS.isEmpty()
                && (LAST_ISSUE_DAY < 0
                || (day - LAST_ISSUE_DAY >= Math.max(0, BotConfig.get().voyageAutoIssueDays)
                && day > LAST_ISSUE_DAY))) {
            issue(issuerName(), day);
            changed = true;
        }

        // 自检：sold 必须等于所有持有人份额之和，不一致就按持有人校准
        for (Bill b : BILLS) {
            int sum = 0;
            for (int n : b.holders.values()) {
                sum += n;
            }
            if (sum > b.sold) {
                DebugLog.log("远洋提单 #" + b.id + " 份额异常：持有人合计 " + sum
                        + " 超过累计发行 " + b.sold + " → 已上调");
                b.sold = sum;
                changed = true;
            }
        }

        if (changed) {
            save();
        }
    }

    /**
     * 国库在本船还能再买多少份（其它角色不限制）。
     * 上限 = 总份数 × regulatorVoyageMaxSharePermille / 1000（默认 10%）。
     */
    private static int regulatorShareRoom(Bill b, String name) {
        try {
            var cfg = BotConfig.get();
            int pm = Math.max(0, Math.min(1000, cfg.regulatorVoyageMaxSharePermille));
            if (pm <= 0 || pm >= 1000) {
                return Integer.MAX_VALUE;
            }
            boolean isReg = false;
            for (var bot : cfg.bots) {
                if (bot != null && bot.name != null && bot.name.equals(name) && isRegulator(bot)) {
                    isReg = true;
                    break;
                }
            }
            if (!isReg) {
                return Integer.MAX_VALUE;
            }
            int cap = Math.max(1, b.total * pm / 1000);
            int have = b.holders.getOrDefault(name, 0);
            return Math.max(0, cap - have);
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    /** 玩家持有的份额（非机器人账户） */
    private static long playerHeld(Bill b) {
        long n = 0L;
        try {
            for (var e : b.holders.entrySet()) {
                boolean bot;
                try {
                    bot = com.tangtu.kjbb.MarketBots.isBot(e.getKey());
                } catch (Throwable t) {
                    bot = true;
                }
                if (!bot) {
                    n += e.getValue();
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return n;
    }

    /** 是不是经济部（国库） */
    private static boolean isRegulator(BotConfig.Bot bot) {
        return bot != null && ("regulator".equals(bot.role) || "stabilizer".equals(bot.role));
    }

    /** NPC 三方各参与一次：先出货，再出钱补资金池 */
    private static boolean npcJoin(Bill b) {
        boolean changed = false;
        var cfg = BotConfig.get();
        // 玩家优先：募资未过半、且玩家份额不到阈值时，机器人不进场
        try {
            long now = com.tangtu.kjbb.ServerCalendar.currentDay();
            long span = Math.max(1L, b.deadline - b.issueDay);
            long elapsed = Math.max(0L, now - b.issueDay);
            int byTime = Math.max(0, Math.min(1000, cfg.voyageNpcJoinAfterPermille));
            int byPlayer = Math.max(0, Math.min(1000, cfg.voyageNpcJoinPlayerSharePermille));
            boolean timeUp = elapsed * 1000L >= span * byTime;
            long playerShares = playerHeld(b);
            boolean playerEnough = b.total > 0
                    && playerShares * 1000L >= (long) b.total * byPlayer;
            if (!timeUp && !playerEnough) {
                return false;              // 把前半段留给玩家
            }
        } catch (Throwable ignored) {
            // 判断失败就不拦
        }
        double stockCap = Math.max(0.0, Math.min(1.0, cfg.voyageStockPermille / 1000.0));
        double cashCap = Math.max(0.0, Math.min(1.0, cfg.voyageNpcCashPermille / 1000.0));

        // 第一轮：所有 NPC 先出货（保证载货单能被真实库存填满）
        for (BotConfig.Bot bot : cfg.bots) {
            if (bot == null || BotConfig.ROLE_SHIPPING.equals(bot.role)
                    || BotConfig.ROLE_ROMA.equals(bot.role)) {
                continue;
            }
            // 国库现金不足 → 不再出货船上
            long voyageGate = Math.max(cfg.regulatorVoyageCargoMinMoney, cfg.regulatorCashWarnVoyage);
            if (isRegulator(bot) && BotLedger.money(bot.name) < voyageGate) {
                DebugLog.log("国库现金 " + BotLedger.money(bot.name) + " < 门槛 "
                        + cfg.regulatorVoyageCargoMinMoney + "，本轮不再航次出货");
                continue;
            }
            String key = b.id + ":g:" + bot.name;
            if (NPC_DONE.contains(key)) {
                continue;
            }
            boolean acted = false;
            for (String item : new ArrayList<>(b.need.keySet())) {
                int miss = b.missing(item);
                if (miss <= 0) {
                    continue;
                }
                int have = BotLedger.stock(bot.name, item);
                int cap = (int) Math.floor(have * stockCap);
                int give = Math.min(miss, cap);
                if (give <= 0) {
                    continue;
                }
                int room = Math.min(b.total - b.sold, npcRoom(b));
                if (room <= 0) {
                    break;
                }
                // 只交「换得到份额」的数量，避免白送
                long unit = Math.max(1L, priceOf(item));
                int maxByRoom = (int) Math.max(0L, (long) room * b.price / unit);
                if (maxByRoom <= 0) {
                    break;
                }
                give = Math.min(give, maxByRoom);
                if (give <= 0) {
                    break;
                }
                // 货值不足以换 1 份 → 不交（白送没意义）
                if ((long) give * unit < Math.max(1L, b.price)) {
                    break;
                }
                // 单船份额上限：国库最多持有总量的 1/10（其余角色不限）
                int shareRoom = regulatorShareRoom(b, bot.name);
                if (shareRoom <= 0) {
                    continue;                    // 该船份额已满，换下一个物品
                }
                long maxValue = (long) shareRoom * Math.max(1L, b.price);
                if ((long) give * unit > maxValue) {
                    give = (int) Math.max(0L, maxValue / Math.max(1L, unit));
                    if (give <= 0) {
                        break;
                    }
                }
                BotLedger.addStock(bot.name, item, -give);
                b.cargo.merge(item, give, Integer::sum);
                long value = unit * give;
                int shares = (int) Math.min(room, value / Math.max(1L, b.price));
                if (shares > 0) {
                    b.sold = Math.min(b.total, b.sold + shares);
                    b.holders.merge(bot.name, shares, Integer::sum);
                }
                BotTrades.log(bot.name, issuerName(), "航次出货#" + b.id, give,
                        priceOf(item), "出货");
                DebugLog.log("航次出货 #" + b.id + " " + bot.name + " 交 " + item
                        + " ×" + give + "（换 " + Math.max(0, shares) + " 份）");
                acted = true;
            }
            if (acted) {
                NPC_DONE.add(key);
                changed = true;
            }
        }

        // 第二轮：所有 NPC 再出钱补资金池（每家最多补缺口的一半）
        for (BotConfig.Bot bot : cfg.bots) {
            if (bot == null || BotConfig.ROLE_SHIPPING.equals(bot.role)
                    || BotConfig.ROLE_ROMA.equals(bot.role)) {
                continue;
            }
            // 国库现金不足 → 也不再为航次出资（出资实测亏损）
            long voyageGate2 = Math.max(cfg.regulatorVoyageCargoMinMoney, cfg.regulatorCashWarnVoyage);
            if (cfg.regulatorVoyageInvestStopToo && isRegulator(bot)
                    && BotLedger.money(bot.name) < voyageGate2) {
                continue;
            }
            String key = b.id + ":c:" + bot.name;
            if (NPC_DONE.contains(key)) {
                continue;
            }
            long needCash = b.targetCash - b.cash;
            if (needCash <= 0L) {
                break;
            }
            int room = Math.min(b.total - b.sold, npcRoom(b));
            if (room <= 0) {
                break;
            }
            long capByCash = Math.round(BotLedger.money(bot.name) * cashCap);
            long byRoom = (long) room * Math.max(1L, b.price);
            long give = Math.min(Math.min(needCash, needCash / 2 + 1),
                    Math.min(capByCash, byRoom));
            int shares = (int) (give / Math.max(1L, b.price));
            int shareRoom = regulatorShareRoom(b, bot.name);
            if (shares > shareRoom) {
                shares = shareRoom;
            }
            give = (long) shares * Math.max(1L, b.price);
            if (give <= 0L || shares <= 0) {
                continue;
            }
            BotLedger.addMoney(bot.name, -give);
            b.cash += give;
            b.sold = Math.min(b.total, b.sold + shares);
            b.holders.merge(bot.name, shares, Integer::sum);
            BotTrades.log(bot.name, issuerName(), "航次出资#" + b.id, 1, give, "出资");
            DebugLog.log("航次出资 #" + b.id + " " + bot.name + " 出 " + give
                    + "（换 " + Math.max(0, shares) + " 份）");
            NPC_DONE.add(key);
            changed = true;
        }
        return changed;
    }

    /**
     * 取消航次：把认购钱退给持有人，货物归船东
     *
     * 触发条件：募资截止时已售份额不足总量的 30%
     */
    private static void cancelVoyage(Bill b) {
        String issuer = issuerName();
        long pool = Math.max(0L, b.cash);
        long refunded = 0L;
        long companyPaid = 0L;
        StringBuilder who = new StringBuilder();
        for (var e : new java.util.ArrayList<>(b.holders.entrySet())) {
            String holder = e.getKey();
            int shares = e.getValue() == null ? 0 : e.getValue();
            if (shares <= 0) {
                continue;
            }
            long amount = (long) shares * Math.max(1L, b.price);
            long fromPool = Math.min(pool, amount);
            pool -= fromPool;
            long rest = amount - fromPool;
            if (rest > 0L) {
                long have = BotLedger.money(issuer);
                if (have < rest) {
                    rest = have;
                }
                if (rest > 0L) {
                    BotLedger.addMoney(issuer, -rest);
                    companyPaid += rest;
                }
            }
            long total = fromPool + rest;
            credit(holder, total);
            refunded += total;
            who.append(holder).append('=').append(shares).append("份 ");
        }
        b.cash = 0L;
        b.holders.clear();
        b.sold = 0;
        b.status = CLOSED;
        com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
        b.outcome = "取消";
        save();
        DebugLog.log("航次 #" + b.id + " 取消（认购不足 30%）：退款 " + refunded
                + "（资金池出 " + (refunded - companyPaid) + "，船东补 " + companyPaid
                + "），货物归船东。持有人：" + who);
        BotTrades.log(issuer, "-", "航次取消退款#" + b.id, 1, refunded, "取消");
    }

    /**
     * 压舱货：载货价值不到目标 35% 时，船东自己买一批便宜货压舱
     *
     * 目的：避免「空船出海」——至少船上有点东西，份值不至于为 0。
     */
    private static void ballast(Bill b) {
        try {
            long target = Math.max(1000L, BotConfig.get().voyageTargetValue);
            long value = b.cargoValue();
            if (value * 100L >= target * 35L) {
                return;   // 已有压舱货，不重复买
            }
            String issuer = issuerName();
            long budget = BotLedger.money(issuer) * 8 / 10;   // 逐利：敢用八成现金
            if (budget <= 0L) {
                return;
            }
            java.util.List<String> cand = new java.util.ArrayList<>(VoyageCargo.pool().keySet());
            if (cand.isEmpty()) {
                cand.add("minecraft:coal");
                cand.add("minecraft:iron_ingot");
                cand.add("minecraft:wheat");
            }
            // 逐利装货：按单价从高到低装，用同样的钱换最大货值
            cand.sort(java.util.Comparator.comparingLong(VoyageBill::priceOf).reversed());
            long spent = 0L;
            int bought = 0;
            for (String item : cand) {
                if (spent >= budget) {
                    break;
                }
                long p = priceOf(item);
                if (p <= 0L) {
                    continue;
                }
                int buy = (int) Math.min(512L, (budget - spent) / p);
                if (buy <= 0) {
                    continue;
                }
                b.cargo.merge(item, buy, Integer::sum);
                spent += p * buy;
                bought += buy;
                if (b.cargoValue() * 100L >= target * 300L) {
                    break;   // 逐利：装到目标货值的 3 倍才停
                }
            }
            // 还差得远 → 从罗马国库存直接买（罗马的产出直接支援航次）
            try {
                long need = target * 300L - b.cargoValue();
                if (need > 0L && BotLedger.money(issuer) > 0L) {
                    var romaStock = com.tangtu.kjbb.BotLedger.stockMap(com.tangtu.kjbb.RomaMarket.NAME);
                    var cand2 = new java.util.ArrayList<>(romaStock.keySet());
                    cand2.sort(java.util.Comparator.comparingLong(VoyageBill::priceOf).reversed());
                    int bought2 = 0;
                    long spent2 = 0L;
                    for (String it2 : cand2) {
                        if (spent2 >= need) {
                            break;
                        }
                        long pr2 = priceOf(it2);
                        if (pr2 <= 0L) {
                            continue;
                        }
                        int have2 = com.tangtu.kjbb.BotLedger.stock(com.tangtu.kjbb.RomaMarket.NAME, it2);
                        if (have2 <= 0) {
                            continue;
                        }
                        long afford2 = Math.min(need - spent2, BotLedger.money(issuer)) / pr2;
                        int want2 = (int) Math.min(Math.min(have2, 512L), Math.max(0L, afford2));
                        if (want2 <= 0) {
                            continue;
                        }
                        int got2 = com.tangtu.kjbb.RomaMarket.takeFromStock(it2, want2);
                        if (got2 <= 0) {
                            continue;
                        }
                        b.cargo.merge(it2, got2, Integer::sum);
                        long pay2 = pr2 * got2;
                        // 钱付给罗马（罗马卖货换现金）
                        com.tangtu.kjbb.Ledger.credit(com.tangtu.kjbb.RomaMarket.NAME, pay2);
                        spent2 += pay2;
                        bought2 += got2;
                    }
                    if (bought2 > 0) {
                        BotLedger.addMoney(issuer, -spent2);
                        addCargoCost(b.id, spent2);
                        DebugLog.log("航次从罗马补货 #" + b.id + "：" + bought2 + " 件，花 " + spent2
                                + "，货值 " + b.cargoValue());
                    }
                }
            } catch (Throwable ignored) {
                // 罗马补货失败不影响出海
            }

            if (spent <= 0L) {
                return;
            }
            BotLedger.addMoney(issuer, -spent);
            int room = Math.min(b.total - b.sold, npcRoom(b));
            int shares = (int) Math.min(room, spent / Math.max(1L, b.price));
            if (shares > 0) {
                b.sold = Math.min(b.total, b.sold + shares);
                b.holders.merge(issuer, shares, Integer::sum);
            }
            BotTrades.log(issuer, "-", "航次压舱#" + b.id, bought, 0, "压舱");
            DebugLog.log("航次压舱 #" + b.id + "：船东花 " + spent + " 买 " + bought
                    + " 件压舱（货值 " + b.cargoValue() + "，换 " + Math.max(0, shares) + " 份）");
        } catch (Throwable ignored) {
            // 压舱失败不影响出海
        }
    }

    /**
     * 推进一个航段：掷一次事件，影响船况与累计倍率
     *
     * 事件表（船况越好，风险越低）：
     *   顺风  +3% 倍率
     *   平安  不变
     *   逆风  -6% 倍率，船况 -5
     *   风暴  -12% 倍率，船况 -15，货损 5%
     *   海盗  -18% 倍率，船况 -12，货损 10%
     *   触礁  -25% 倍率，船况 -25，货损 15%
     *   沉没  条件：船况 ≤ 15 时才有小概率
     */
    private static void advanceLeg(Bill b, long day) {
        int cond = Math.max(0, Math.min(100, b.condition));
        int risk = 100 - cond;                        // 船况越差，风险越高
        int roll = ThreadLocalRandom.current().nextInt(100);
        var ev = BotConfig.get().voyageEvents;

        String event;
        // 沉没：基础概率 + 船况差时的加罚（都可在 bots.json 的 voyageEvents 里改）
        var cfgLeg = BotConfig.get();
        boolean returning = cfgLeg.voyageRoundTrip && b.legDone >= Math.max(1, cfgLeg.voyageLegs);
        int sinkChance = Math.max(0, ev.sinkPct)
                + (cond <= ev.condBad ? Math.max(0, ev.sinkExtra) : 0);
        if (returning) {
            // 回程空载、已认过路：沉没概率按系数打折
            sinkChance = sinkChance * Math.max(0, Math.min(1000, cfgLeg.voyageReturnSinkPermille)) / 1000;
        }
        if (roll < sinkChance) {
            event = OUT_SUNK;
        } else if (roll < ev.tGood) {
            event = "顺风";
        } else if (roll < ev.tCalm) {
            event = "平安";
        } else if (roll < ev.tBad) {
            event = "逆风";
        } else if (roll < ev.tStorm) {
            event = "风暴";
        } else if (roll < ev.tPirate) {
            event = "海盗";
        } else {
            event = "触礁";
        }
        // 船况差时把好事件压一压
        if (risk > 40 && ("顺风".equals(event) || "平安".equals(event))) {
            if (ThreadLocalRandom.current().nextInt(100) < risk / 2) {
                event = "逆风";
            }
        }

        int dmg = 0;
        int lossPct = 0;
        switch (event) {
            case "顺风" -> b.mult = Math.min(ev.multCap, b.mult + ev.dGood);
            case "平安" -> {
                // 无变化
            }
            case "逆风" -> {
                b.mult = Math.max(0, b.mult + Math.min(0, ev.dBad));
                dmg = ev.dmgBad;
            }
            case "风暴" -> {
                b.mult = Math.max(0, b.mult + Math.min(0, ev.dStorm));
                dmg = ev.dmgStorm;
                lossPct = Math.max(0, ev.lossStorm);
            }
            case "海盗" -> {
                b.mult = Math.max(0, b.mult + Math.min(0, ev.dPirate));
                dmg = ev.dmgPirate;
                lossPct = Math.max(0, ev.lossPirate);
            }
            case "触礁" -> {
                b.mult = Math.max(0, b.mult + Math.min(0, ev.dReef));
                dmg = ev.dmgReef;
                lossPct = Math.max(0, ev.lossReef);
            }
            case OUT_SUNK -> {
                b.mult = 0;
                b.condition = 0;
                b.legDone = b.legs;
                b.legLog.add(OUT_SUNK);
                b.outcome = OUT_SUNK;
                applyOutcome(b);
                DebugLog.log("航次 #" + b.id + " 第 " + (b.legDone) + "/" + b.legs
                        + " 段沉没（船况 " + cond + "）");
                return;
            }
            default -> {
                // 未知事件按平安处理
            }
        }
        b.condition = Math.max(0, cond - dmg);
        if (lossPct > 0) {
            for (String item : new ArrayList<>(b.cargo.keySet())) {
                int have = b.cargo.getOrDefault(item, 0);
                int lost = have * lossPct / 100;
                if (lost <= 0) {
                    continue;
                }
                int left = have - lost;
                if (left <= 0) {
                    b.cargo.remove(item);
                } else {
                    b.cargo.put(item, left);
                }
            }
        }
        b.legLog.add(event);
        b.legDone++;

        // 去程走完 = 抵达罗马：交货给罗马国 + 装返程货
        if (cfgLeg.voyageRoundTrip && !b.imported
                && b.legDone == Math.max(1, cfgLeg.voyageLegs) && b.legDone < b.legs) {
            try {
                RomaMarket.exchangeForReturn(b, day);
            } catch (Throwable t) {
                DebugLog.log("航次 #" + b.id + " 罗马换货失败：" + t);
            }
        }
        // 按比例排段：第 k 段落在 departDay + k×航期/段数，最后一段正好到港
        long base = b.departDay > 0 ? b.departDay : day;
        long vd = Math.max(1L, BotConfig.get().voyageDays);
        long next = base + (long) (b.legDone + 1) * vd / Math.max(1, b.legs);
        if (b.dueDay > 0 && next > b.dueDay) {
            next = b.dueDay;
        }
        b.legNextDay = next > day ? next : day + 1;
        if (b.legDone >= b.legs) {
            b.legNextDay = -1L;
        }
        save();
        DebugLog.log("航次 #" + b.id + " 第 " + b.legDone + "/" + b.legs + " 段：" + event
                + "  船况 " + cond + "→" + b.condition
                + "  累计倍率 " + b.mult + "%"
                + (lossPct > 0 ? ("　货损 " + lossPct + "%") : ""));
    }

    /** 出海：用资金池按市价把缺的货补齐 */
    private static void depart(Bill b) {
        long spent = 0L;
        int bought = 0;
        for (String item : new ArrayList<>(b.need.keySet())) {
            int miss = b.missing(item);
            if (miss <= 0) {
                continue;
            }
            long p = priceOf(item);
            int canBuy = (int) Math.min(miss, b.cash / Math.max(1L, p));
            if (canBuy <= 0) {
                continue;
            }
            long cost = p * canBuy;
            b.cash -= cost;
            spent += cost;
            b.cargo.merge(item, canBuy, Integer::sum);
            bought += canBuy;
        }
        // 出海前最后一轮：船东用现金把缺货补满（按市价，换份额）
        long topUpBudget = Math.max(0L, BotLedger.money(issuerName()) * 7 / 10);
        long topUpSpent = 0L;
        int topUpCount = 0;
        for (String item : new ArrayList<>(b.need.keySet())) {
            int miss = b.missing(item);
            if (miss <= 0) {
                continue;
            }
            long p = priceOf(item);
            long left = topUpBudget - topUpSpent;
            if (left < p) {
                continue;
            }
            int buy = (int) Math.min(miss, left / p);
            if (buy <= 0) {
                continue;
            }
            b.cargo.merge(item, buy, Integer::sum);
            topUpSpent += p * buy;
            topUpCount += buy;
        }
        if (topUpSpent > 0L) {
            BotLedger.addMoney(issuerName(), -topUpSpent);
            int shares = (int) Math.min(b.total - b.sold,
                    topUpSpent / Math.max(1L, b.price));
            if (shares > 0) {
                b.sold = Math.min(b.total, b.sold + shares);
                b.holders.merge(issuerName(), shares, Integer::sum);
            }
            DebugLog.log("航次 #" + b.id + " 出海前补满：花 " + topUpSpent + " 买 " + topUpCount
                    + " 件（换 " + Math.max(0, shares) + " 份）");
        }

        // 实在没货 → 船东自己买点东西压舱（避免空船出海）
        if (!b.charter) {
            ballast(b);
        }

        // 没卖完的份额直接作废：份数按实际发行量收缩，避免「白送份额」稀释别人
        if (b.sold > 0 && b.sold < b.total) {
            DebugLog.log("航次 #" + b.id + " 份数按实际发行量收缩：" + b.total + " → " + b.sold);
            b.total = b.sold;
        }
        long nowDay = com.tangtu.kjbb.ServerCalendar.currentDay();
        var cfg0 = BotConfig.get();
        int outLegs = Math.max(2, Math.min(6, cfg0.voyageLegs));
        int backLegs = cfg0.voyageRoundTrip ? Math.max(0, Math.min(4, cfg0.voyageReturnLegs)) : 0;
        long totalDays = Math.max(1, cfg0.voyageDays);
        if (backLegs > 0) {
            // 回程按比例分配航期（回程段少，天数也少）
            totalDays = totalDays + Math.max(1, (long) cfg0.voyageDays * backLegs / Math.max(1, outLegs));
        }
        b.dueDay = nowDay + totalDays;
        // 航行分段初始化：去程 + 回程
        b.legs = outLegs + backLegs;
        b.legDone = 0;
        b.condition = 100;
        b.mult = 100;
        b.legLog.clear();
        b.departDay = nowDay;
        long legDays = Math.max(1L, BotConfig.get().voyageDays / b.legs);
        b.legNextDay = nowDay + legDays;
        b.status = VOYAGING;
        save();
        DebugLog.log("航次 #" + b.id + " 出海：用 " + spent + " 现金补购 " + bought
                + " 件，货值 " + b.cargoValue() + "，份额 " + b.sold + "/" + b.total
                + "，到港日 " + b.dueDay);
    }

    private static void rollOutcome(Bill b) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 55) {
            b.outcome = OUT_GOOD;
        } else if (roll < 85) {
            b.outcome = OUT_FAIR;
        } else if (roll < 96) {
            b.outcome = OUT_RISK;
        } else {
            b.outcome = OUT_SUNK;
        }
        applyOutcome(b);
    }

    private static void applyOutcome(Bill b) {
        if (OUT_SUNK.equals(b.outcome)) {
            int wiped = 0;
            for (int n : b.holders.values()) {
                wiped += n;
            }
            b.holders.clear();
            b.status = CLOSED;
            com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
            int cancelled = VoyageShareMarket.cancelByBill(b.id);
            DebugLog.log("远洋提单 #" + b.id + " 沉没：作废 " + wiped + " 份、撤销挂单 "
                    + cancelled + " 张");
            return;
        }
        b.status = DUE;
        DebugLog.log("远洋提单 #" + b.id + " 到港：结果 " + b.outcome
                + "（货值 " + b.cargoValue() + "，每份 " + b.shareValue() + "，系数 "
                + b.riskMultiplier() + "）");
    }

    // ---------------- 结算 ----------------

    public static synchronized String settle(ServerPlayer sp, long billId, String mode) {
        load();
        Bill b = get(billId);
        if (b == null) {
            return "§c没有这张提单";
        }
        if (!DUE.equals(b.status)) {
            return "§c这艘船还没到港（状态 " + b.status + "）";
        }
        String player = sp.getScoreboardName();
        int shares = b.heldBy(player);
        if (shares <= 0) {
            return "§c你没有这艘船的份额";
        }
        if (OUT_SUNK.equals(b.outcome)) {
            b.holders.remove(player);
            if (b.holders.isEmpty()) {
                b.status = CLOSED;
                com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
                VoyageShareMarket.cancelByBill(b.id);
            }
            save();
            return "§c船沉了，你这 " + shares + " 份全部归零";
        }
        double mul = b.riskMultiplier();
        if ("goods".equalsIgnoreCase(mode)) {
            if (b.cargo.isEmpty()) {
                return "§c船上的货已经被罗马国买走了，没有实物可提，请改用 cash 折现";
            }
            int given = 0;
            int bills = 0;
            String addr = com.tangtu.kjbb.AddressBook.addressOf(sp);
            for (var e : b.cargo.entrySet()) {
                long total = (long) e.getValue() * shares * (long) (mul * 100L)
                        / (long) b.total / 100L;
                int give = (int) total;
                if (give <= 0) {
                    continue;
                }
                boolean ok = false;
                try {
                    ok = com.tangtu.kjbb.BillDelivery.giveBill(sp, e.getKey(), give, addr);
                } catch (Throwable ignored) {
                    ok = false;
                }
                if (ok) {
                    bills++;
                    given += give;
                } else {
                    given += addToInventory(sp, e.getKey(), give);
                }
            }
            b.holders.remove(player);
            b.sold = Math.max(0, b.sold - shares);
            if (b.holders.isEmpty()) {
                b.status = CLOSED;
                com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
                VoyageShareMarket.cancelByBill(b.id);
                settleSurplus(b);
            }
            save();
            if (bills > 0) {
                return "§a已开 " + bills + " 张提货提单（共 " + given + " 件，地址 "
                        + (addr.isEmpty() ? "未绑定" : addr) + "），拿提单右键蛙港/邮箱取货";
            }
            return "§a已提货：共 " + given + " 件（" + shares + " 份，" + b.outcome + "）";
        }

        long perShare = Math.round(b.shareValue() * mul);
        long cashOut = perShare * shares;
        String issuer = issuerName();
        long fromPool = Math.min(cashOut, Math.max(0L, b.cash));
        b.cash -= fromPool;
        long rest = cashOut - fromPool;
        if (rest > 0L) {
            if (BotLedger.money(issuer) < rest) {
                return "§c" + issuer + " 现金不足，暂时无法兑付（需 " + rest
                        + "，现有 " + BotLedger.money(issuer) + "）";
            }
            BotLedger.addMoney(issuer, -rest);
        }
        credit(player, cashOut);
        b.holders.remove(player);
        b.sold = Math.max(0, b.sold - shares);
        if (b.holders.isEmpty()) {
            b.status = CLOSED;
            com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
            VoyageShareMarket.cancelByBill(b.id);
            settleSurplus(b);
        }
        save();
        BotTrades.log(issuer, player, "远洋提单折现#" + b.id, shares, perShare, "折现");
        return "§a已折现：" + shares + " 份 × " + perShare + " = " + cashOut
                + " 唐兔币（" + b.outcome + "，资金池出 " + fromPool + "）";
    }

    /**
     * 机器人/账本主体的份额结算（不需要 ServerPlayer）。
     *
     * 和玩家版逻辑一致，只是「提货」变成「进 BotLedger 库存」。
     * 主要用于国民认购航次后自动结算，避免航次因为持有人永远不结算而卡在 DUE。
     */
    public static synchronized String settleHolder(String who, long billId, String mode) {
        load();
        Bill b = get(billId);
        if (b == null || who == null || who.isEmpty()) {
            return null;
        }
        if (!DUE.equals(b.status)) {
            return null;
        }
        int shares = b.heldBy(who);
        if (shares <= 0) {
            return null;
        }
        if (OUT_SUNK.equals(b.outcome)) {
            b.holders.remove(who);
            if (b.holders.isEmpty()) {
                b.status = CLOSED;
                com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
                VoyageShareMarket.cancelByBill(b.id);
            }
            save();
            return "沉没归零：" + shares + " 份";
        }
        double mul = b.riskMultiplier();
        if ("goods".equalsIgnoreCase(mode) && !b.cargo.isEmpty()) {
            int given = 0;
            for (var e : b.cargo.entrySet()) {
                long total = (long) e.getValue() * shares * (long) (mul * 100L)
                        / (long) b.total / 100L;
                int give = (int) total;
                if (give <= 0) {
                    continue;
                }
                com.tangtu.kjbb.BotLedger.addStock(who, e.getKey(), give);
                given += give;
            }
            b.holders.remove(who);
            b.sold = Math.max(0, b.sold - shares);
            if (b.holders.isEmpty()) {
                b.status = CLOSED;
                com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
                VoyageShareMarket.cancelByBill(b.id);
                settleSurplus(b);
            }
            save();
            return "提货 " + given + " 件";
        }
        long perShare = Math.round(b.shareValue() * mul);
        long cashOut = perShare * shares;
        String issuer = issuerName();
        long fromPool = Math.min(cashOut, Math.max(0L, b.cash));
        b.cash -= fromPool;
        long rest = cashOut - fromPool;
        if (rest > 0L) {
            if (com.tangtu.kjbb.BotLedger.money(issuer) < rest) {
                return null;           // 发行人没钱，下次再试
            }
            com.tangtu.kjbb.BotLedger.addMoney(issuer, -rest);
        }
        com.tangtu.kjbb.BotLedger.addMoney(who, cashOut);
        b.holders.remove(who);
        b.sold = Math.max(0, b.sold - shares);
        if (b.holders.isEmpty()) {
            b.status = CLOSED;
            com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
            VoyageShareMarket.cancelByBill(b.id);
            settleSurplus(b);
        }
        save();
        com.tangtu.kjbb.BotTrades.log(issuer, who, "远洋提单折现#" + b.id, shares, perShare, "机器人折现");
        return "折现 " + cashOut;
    }

    /**
     * 航次收尾：剩余货款归国库，避免钱沉在关掉的账上。
     *
     * 罗马国按贴近本地价的价格收货，兑付又按「估值 × 事件倍率」算，
     * 两边不完全相等；差额不该凭空消失，也不该凭空产生。
     */
    private static void settleSurplus(Bill b) {
        if (b == null || b.cash <= 0L) {
            return;
        }
        // 名册里还有人没领（多半是玩家），钱留着等他凭票来领，不充公
        if (b.holders != null && !b.holders.isEmpty()) {
            DebugLog.log("航次 #" + b.id + " 还有 " + b.holders.size()
                    + " 位持有人未领，剩余 " + b.cash + " 暂留");
            return;
        }
        long rest = b.cash;
        try {
            BotLedger.addMoney(issuerName(), rest);
            b.cash = 0L;
            DebugLog.log("航次 #" + b.id + " 结算完毕，剩余货款 " + rest + " 归国库");
        } catch (Throwable ignored) {
            // 记账失败就把钱留在原处
        }
    }

    // ---------------- 包船（玩家雇公司的船运自己的货） ----------------

    /** 找自己的包船草案（未出海的） */
    public static synchronized Bill charterOf(String owner) {
        load();
        for (int i = BILLS.size() - 1; i >= 0; i--) {
            Bill b = BILLS.get(i);
            if (b.charter && OPEN.equals(b.status) && !b.charterSailed
                    && owner != null && owner.equals(b.charterer)) {
                return b;
            }
        }
        return null;
    }

    /**
     * 往包船里装货：货从玩家背包扣走，货权归玩家，公司只负责运。
     *
     * @return 提示文本
     */
    public static synchronized String charterAdd(ServerPlayer sp, String itemId, int qty) {
        load();
        if (itemId == null || itemId.isEmpty()) {
            return "§c要指定物品 id";
        }
        if (qty <= 0) {
            return "§c数量要大于 0";
        }
        long unit = RomaMarket.unitPrice(itemId);
        if (unit <= 0L) {
            return "§c这个物品没有价格：" + itemId;
        }
        var cfg = BotConfig.get();
        String me = sp.getScoreboardName();
        // 一艘在航就够：防止同一玩家无限并行包船刷钱
        for (Bill x : BILLS) {
            if (x.charter && VOYAGING.equals(x.status) && me.equals(x.charterer)) {
                return "§c你已经有一艘包船在路上了（#" + x.id + "），等它回港再装下一艘";
            }
        }
        Bill b = charterOf(me);
        long today = com.tangtu.kjbb.ServerCalendar.currentDay();
        if (b != null && b.deadline > 0L && today > b.deadline) {
            return "§c这艘包船草案已过期（装货期 7 天），请用 §e/billvoyage abandon§c 退回货物后重开";
        }
        if (b == null) {
            b = new Bill();
            b.id = NEXT_ID++;
            b.name = randomShipName(b.id) + "号（包船）";
            b.charter = true;
            b.charterer = me;
            b.status = OPEN;
            b.issueDay = com.tangtu.kjbb.ServerCalendar.currentDay();
            b.deadline = b.issueDay + 7;
            b.total = 0;
            b.price = 0;
            b.targetCash = 0;
            b.dueDay = -1L;
            b.legs = Math.max(2, Math.min(6, cfg.voyageLegs));
            BILLS.add(b);
        }
        int already = b.cargo.getOrDefault(itemId, 0);
        if (already + qty > Math.max(1, cfg.voyageCharterMaxQtyPerItem)) {
            return "§c单种货最多 " + cfg.voyageCharterMaxQtyPerItem + " 件（船上已有 " + already + "）";
        }
        long newValue = b.cargoValue() + unit * qty;
        if (newValue > Math.max(1000L, cfg.voyageCharterMaxValue)) {
            return "§c这一船最多装 " + cfg.voyageCharterMaxValue + " 货值的货（现在 " + b.cargoValue() + "）";
        }
        if (!takeItems(sp, itemId, qty)) {
            return "§c背包里没有那么多 " + itemId;
        }
        b.cargo.merge(itemId, qty, Integer::sum);
        save();
        return "§a已装船 " + qty + "×" + itemId.replace("minecraft:", "").replace("create:", "C·")
                + "（货值 " + b.cargoValue() + "/" + cfg.voyageCharterMaxValue
                + "）。装完用 §e/billvoyage sail§a 出海";
    }

    /** 包船出海：付运费，公司记账，货权仍归玩家 */
    public static synchronized String charterSail(ServerPlayer sp) {
        load();
        String me = sp.getScoreboardName();
        Bill b = charterOf(me);
        if (b == null) {
            return "§c你没有正在装货的包船";
        }
        if (b.deadline > 0L && com.tangtu.kjbb.ServerCalendar.currentDay() > b.deadline) {
            return "§c这艘包船草案已过期，请用 §e/billvoyage abandon§c 退回货物后重开";
        }
        if (b.cargo.isEmpty()) {
            return "§c船上还没装货";
        }
        long value = b.cargoValue();
        var cfg = BotConfig.get();
        long freight = Math.max(500L, value * Math.max(0, cfg.voyageCharterFeePermille) / 1000L);
        long have = balanceOf(me);
        if (have < freight) {
            return "§c运费不够：需要 " + freight + "，你有 " + have;
        }
        if (com.tangtu.kjbb.BankBridge.isBotAccount(me)) {
            BotLedger.addMoney(me, -freight);
        } else {
            com.tangtu.kjbb.BankBridge.takeMoney(me, freight);
        }
        BotLedger.addMoney(issuerName(), freight);      // 运费进公司账
        b.freight = freight;
        b.charterSailed = true;
        depart(b);
        save();
        BotTrades.log(me, issuerName(), "包船运费#" + b.id, 1, freight, "包船");
        DebugLog.log("包船 #" + b.id + " " + me + " 出海：货值 " + value + "，运费 " + freight
                + "，到港日 " + b.dueDay);
        return "§a包船出海（货值 " + value + "，付运费 " + freight + "），第 "
                + b.dueDay + " 日到港。货款到港后直接进你钱包";
    }

    /** 撤掉包船草案，货退回背包 */
    public static synchronized String charterAbandon(ServerPlayer sp) {
        load();
        Bill b = charterOf(sp.getScoreboardName());
        if (b == null) {
            return "§c你没有正在装货的包船";
        }
        int given = 0;
        for (var e : b.cargo.entrySet()) {
            given += addToInventory(sp, e.getKey(), e.getValue());
        }
        b.cargo.clear();
        b.status = CLOSED;
        com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
        b.outcome = "撤回";
        save();
        return "§a已撤回包船，退回 " + given + " 件货";
    }

    /** 从背包扣物品（返回是否成功，失败不动背包） */
    private static boolean takeItems(ServerPlayer sp, String itemId, int count) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) {
                return false;
            }
            Item it = BuiltInRegistries.ITEM.get(rl);
            if (it == null) {
                return false;
            }
            var inv = sp.getInventory();
            int have = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && st.getItem() == it) {
                    have += st.getCount();
                }
            }
            if (have < count) {
                return false;
            }
            int left = count;
            for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                ItemStack st = inv.getItem(i);
                if (st.isEmpty() || st.getItem() != it) {
                    continue;
                }
                int take = Math.min(left, st.getCount());
                st.shrink(take);
                left -= take;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 返程货卸货：按 voyageImportKeepPermille 留一部分在 cargo（给持股人提货），
     * 其余由公司按本地市价收走，钱进航次资金池（包船则直接进包船人钱包）。
     */
    private static void unloadReturnCargo(Bill b) {
        var cfg = BotConfig.get();
        // 旧币不折现，全部转入航运公司库存（公司代持，回头由持股人凭票兑换）
        try {
            java.util.Map<String, Integer> coins = new java.util.LinkedHashMap<>();
            for (var e : new java.util.ArrayList<>(b.cargo.entrySet())) {
                String it = e.getKey();
                int q = e.getValue() == null ? 0 : e.getValue();
                if (q > 0 && com.tangtu.kjbb.VoyageRules.isCoin(it)) {
                    coins.put(it, q);
                    b.cargo.remove(it);
                }
            }
            if (!coins.isEmpty()) {
                String issuer = issuerName();
                for (var e : coins.entrySet()) {
                    BotLedger.addStock(issuer, e.getKey(), e.getValue());
                    BotTrades.log(issuer, "-", "旧币入库#" + b.id, e.getValue(),
                            com.tangtu.kjbb.VoyageRules.coinFace(e.getKey()), "旧币入库");
                }
                DebugLog.log("航次 #" + b.id + " 旧币入库：" + coins.size()
                        + " 种交给 " + issuer + " 代持");
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (b.cargo.isEmpty()) {
            return;
        }
        long keepPm = Math.max(0, Math.min(1000, cfg.voyageImportKeepPermille));
        long gross = 0L;
        java.util.Map<String, Integer> remain = new java.util.LinkedHashMap<>();
        for (var e : b.cargo.entrySet()) {
            String item = e.getKey();
            int qty = e.getValue() == null ? 0 : e.getValue();
            if (qty <= 0) {
                continue;
            }
            int keep = b.charter ? 0 : (int) (qty * keepPm / 1000L);
            int sell = qty - keep;
            gross += (long) sell * priceOf(item);
            if (keep > 0) {
                remain.put(item, keep);
            }
        }
        b.cargo.clear();
        b.cargo.putAll(remain);
        if (gross > 0L) {
            if (b.charter && b.charterer != null && !b.charterer.isEmpty()) {
                try {
                    com.tangtu.kjbb.BankBridge.addMoney(b.charterer, gross);
                } catch (Throwable ignored) {
                    b.cash += gross;
                }
            } else {
                b.cash += gross;
            }
        }
        DebugLog.log("航次 #" + b.id + " 返程卸货：公司收走 " + (gross > 0 ? "若干" : "无")
                + "，货值 " + gross + "；留给持股人 " + (remain.isEmpty() ? "0" : "一部分")
                + "（保留 " + keepPm / 10 + "%）");
    }

    private static int addToInventory(ServerPlayer sp, String itemId, int count) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) {
                return 0;
            }
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null) {
                return 0;
            }
            int left = count;
            int added = 0;
            int max = new ItemStack(item).getMaxStackSize();
            while (left > 0) {
                int n = Math.min(left, max);
                ItemStack st = new ItemStack(item, n);
                if (!sp.getInventory().add(st)) {
                    sp.drop(st, false);          // 装不下就掉在脚下，不吞货
                }
                added += n;
                left -= n;
            }
            return added;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static synchronized void transfer(Bill b, String from, String to, int shares) {
        int have = b.heldBy(from);
        if (have < shares) {
            return;
        }
        if (have == shares) {
            b.holders.remove(from);
        } else {
            b.holders.put(from, have - shares);
        }
        b.holders.merge(to, shares, Integer::sum);
        save();
    }

    /**
     * 把记账份额换成实体股票（份额从账本扣除，票据可自由交易）
     *
     * @return 生成的股票物品，失败返回空栈
     */
    /** 在线玩家（份额要落成实体股票时用） */
    private static net.minecraft.server.level.ServerPlayer online(String who) {
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            return server == null ? null : server.getPlayerList().getPlayerByName(who);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 玩家参与份额：直接发实体股票（和 buy 口径一致，避免记账 + 票双重兑付）。
     * 机器人（不在线）仍记在 holders 里。
     */
    private static void grantShares(Bill b, String who, int shares, String why) {
        if (shares <= 0) {
            return;
        }
        net.minecraft.server.level.ServerPlayer sp = online(who);
        if (sp == null) {
            b.holders.merge(who, shares, Integer::sum);
            return;
        }
        giveStock(sp, b, shares, why);
    }

    /** 发一张股票：同一航次已有票就加份数，否则新建一张放进背包（满则丢地上） */
    private static void giveStock(net.minecraft.server.level.ServerPlayer sp, Bill b,
                                  int shares, String why) {
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack it = inv.getItem(i);
            if (it.getItem() == com.tangtu.kjbb.item.KjbbItems.STOCK.get()
                    && com.tangtu.kjbb.item.StockItem.billOf(it) == b.id) {
                int cur = com.tangtu.kjbb.item.StockItem.sharesOf(it);
                com.tangtu.kjbb.item.StockItem.write(it, b.id, b.name, cur + shares, b.dueDay);
                DebugLog.log("股票加注 #" + b.id + " " + sp.getScoreboardName() + " +"
                        + shares + " → " + (cur + shares) + " 份（" + why + "）");
                return;
            }
        }
        net.minecraft.world.item.ItemStack stack =
                new net.minecraft.world.item.ItemStack(com.tangtu.kjbb.item.KjbbItems.STOCK.get());
        com.tangtu.kjbb.item.StockItem.write(stack, b.id, b.name, shares, b.dueDay);
        stack.setCount(1);
        if (!inv.add(stack)) {
            sp.drop(stack, false);
        }
        DebugLog.log("股票发放 #" + b.id + " " + sp.getScoreboardName() + " " + shares
                + " 份（" + why + "）");
    }

    public static synchronized net.minecraft.world.item.ItemStack toStock(
            net.minecraft.server.level.ServerPlayer sp, long billId, int shares, String why) {
        load();
        Bill b = get(billId);
        if (b == null) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        String me = sp.getScoreboardName();
        int have = b.heldBy(me);
        if (have < shares || shares <= 0) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        if (have == shares) {
            b.holders.remove(me);
        } else {
            b.holders.put(me, have - shares);
        }
        save();
        net.minecraft.world.item.ItemStack stack =
                new net.minecraft.world.item.ItemStack(com.tangtu.kjbb.item.KjbbItems.STOCK.get());
        com.tangtu.kjbb.item.StockItem.write(stack, b.id, b.name, shares, b.dueDay);
        stack.setCount(1);
        DebugLog.log("实体化股票 #" + b.id + " " + me + " " + shares + " 份（" + why + "）");
        BotTrades.log(me, "-", "股票实体化#" + b.id, shares, b.price, why == null ? "实体化" : why);
        return stack;
    }

    /** 用实体股票结算：按票面份数兑付 */
    public static synchronized String settleStockItem(
            net.minecraft.server.level.ServerPlayer sp,
            net.minecraft.world.item.ItemStack stack, String mode) {
        load();
        long billId = com.tangtu.kjbb.item.StockItem.billOf(stack);
        int shares = com.tangtu.kjbb.item.StockItem.sharesOf(stack);
        Bill b = get(billId);
        if (b == null || shares <= 0) {
            return "§c这张股票无效";
        }
        // 航次被取消（认购太少）：按票面退款
        if (CLOSED.equals(b.status) && "取消".equals(b.outcome)) {
            long refund = (long) shares * Math.max(1L, b.price);
            String issuer = issuerName();
            long have = BotLedger.money(issuer);
            long pay = Math.min(refund, Math.max(0L, have));
            if (pay > 0L) {
                BotLedger.addMoney(issuer, -pay);
                credit(sp.getScoreboardName(), pay);
            }
            stack.shrink(1);
            save();
            return "§a航次已取消，按票面退回 " + pay + " 唐兔币（" + shares + " 份）";
        }
        // 已结算的船：持股人凭票二选一 —— 领旧币 / 领现金
        if (CLOSED.equals(b.status) && "cash".equalsIgnoreCase(mode)) {
            String issuerCash = issuerName();
            long perShare = Math.max(0L, b.shareValue());
            long want = perShare * shares;
            long haveCash = BotLedger.money(issuerCash);
            if (want <= 0L) {
                return "§c这张股票已无价值（沉没或未到港）";
            }
            long payCash = Math.min(want, Math.max(0L, haveCash));
            if (payCash <= 0L) {
                return "§c航运公司现金不足，暂时无法兑付（需 " + want + "）";
            }
            BotLedger.addMoney(issuerCash, -payCash);
            credit(sp.getScoreboardName(), payCash);
            stack.shrink(1);
            save();
            BotTrades.log(issuerCash, sp.getScoreboardName(), "股票兑付#" + b.id, shares, perShare, "兑付");
            DebugLog.log("股票兑付（现金）#" + b.id + " " + sp.getScoreboardName()
                    + " " + shares + " 份 → " + payCash + " 唐兔币");
            return "§a凭股票领到现金 " + payCash + " 唐兔币（" + shares + "/" + b.total + " 份）";
        }
        // 已结算的船：持股人凭票向航运公司领取代持的旧币（按份额比例）
        if (CLOSED.equals(b.status)) {
            String issuer = issuerName();
            int total = Math.max(1, b.total);
            long face = com.tangtu.kjbb.VoyageRules.coinFace("create_currency_shops:copper_coin");
            if (face <= 0L) {
                face = 1L;
            }
            // 公司代持的旧币总量（按面值折算）
            long pool = 0L;
            java.util.Map<String, Integer> have = new java.util.LinkedHashMap<>();
            try {
                for (var e : BotLedger.stockMap(issuer).entrySet()) {
                    if (com.tangtu.kjbb.VoyageRules.isCoin(e.getKey())) {
                        have.put(e.getKey(), e.getValue());
                        pool += (long) e.getValue() * com.tangtu.kjbb.VoyageRules.coinFace(e.getKey());
                    }
                }
            } catch (Throwable ignored) {
                // 空
            }
            if (pool <= 0L) {
                return "§c公司没有可兑付的旧币（航次 #" + b.id + "）";
            }
            long want = pool * shares / total;
            if (want <= 0L) {
                return "§c份额太小，兑不出旧币";
            }
            long paid = 0L;
            for (var e : new java.util.ArrayList<>(have.entrySet())) {
                if (paid >= want) {
                    break;
                }
                String coin = e.getKey();
                long cf = Math.max(1L, com.tangtu.kjbb.VoyageRules.coinFace(coin));
                int avail = e.getValue();
                int need = (int) Math.min(avail, (want - paid) / cf);
                if (need <= 0) {
                    continue;
                }
                BotLedger.addStock(issuer, coin, -need);
                int left = need;
                int maxStack = 64;
                while (left > 0) {
                    int n2 = Math.min(left, maxStack);
                    var st2 = new net.minecraft.world.item.ItemStack(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .get(net.minecraft.resources.ResourceLocation.tryParse(coin)), n2);
                    if (!sp.getInventory().add(st2)) {
                        sp.drop(st2, false);
                    }
                    left -= n2;
                }
                paid += (long) need * cf;
            }
            stack.shrink(1);
            save();
            return "§a凭股票领到旧币（面值 " + paid + "，航次 #" + b.id + "，"
                    + shares + "/" + total + " 份）";
        }
        if (!DUE.equals(b.status)) {
            return "§c这艘船还没到港（" + b.status + "）";
        }
        double mul = b.riskMultiplier();
        if (OUT_SUNK.equals(b.outcome)) {
            stack.shrink(1);
            b.sold = Math.max(0, b.sold - shares);
            save();
            return "§c船沉了，这张 " + shares + " 份的股票归零";
        }
        if ("goods".equalsIgnoreCase(mode)) {
            if (b.cargo.isEmpty()) {
                return "§c货已经卖给罗马国了，这张股票只能折现";
            }
            String addr = com.tangtu.kjbb.AddressBook.addressOf(sp);
            int given = 0;
            int bills = 0;
            for (var e : b.cargo.entrySet()) {
                long total = (long) e.getValue() * shares * (long) (mul * 100L)
                        / (long) b.total / 100L;
                int give = (int) total;
                if (give <= 0) {
                    continue;
                }
                // 提货 → 一张提单（地址 = 玩家绑定的蛙港/邮箱），拿提单右键邮箱取货
                boolean ok = false;
                try {
                    ok = com.tangtu.kjbb.BillDelivery.giveBill(sp, e.getKey(), give, addr);
                } catch (Throwable ignored) {
                    ok = false;
                }
                if (ok) {
                    bills++;
                    given += give;
                } else {
                    given += addToInventory(sp, e.getKey(), give);
                }
            }
            stack.shrink(1);
            b.sold = Math.max(0, b.sold - shares);
            if (b.holders.isEmpty() && b.sold <= 0) {
                b.status = CLOSED;
                com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
                VoyageShareMarket.cancelByBill(b.id);
            }
            save();
            if (bills > 0) {
                return "§a已开 " + bills + " 张提货提单（共 " + given + " 件，地址 "
                        + (addr.isEmpty() ? "未绑定" : addr)
                        + "），拿提单右键你的蛙港/邮箱取货";
            }
            return "§a已按股票提货：共 " + given + " 件（" + shares + " 份，" + b.outcome + "）";
        }
        long perShare = Math.round(b.shareValue() * mul);
        long cashOut = perShare * shares;
        String issuer = issuerName();
        long fromPool = Math.min(cashOut, Math.max(0L, b.cash));
        b.cash -= fromPool;
        long rest = cashOut - fromPool;
        if (rest > 0L) {
            if (BotLedger.money(issuer) < rest) {
                return "§c" + issuer + " 现金不足，暂时无法兑付";
            }
            BotLedger.addMoney(issuer, -rest);
        }
        BankBridge.addMoney(sp.getScoreboardName(), cashOut);
        stack.shrink(1);
        b.sold = Math.max(0, b.sold - shares);
        if (b.holders.isEmpty() && b.sold <= 0) {
            b.status = CLOSED;
            com.tangtu.kjbb.StockMarket.cancelByBill(b.id);   // 航次结束：挂单自动撤单退票
            VoyageShareMarket.cancelByBill(b.id);
        }
        save();
        BotTrades.log(issuer, sp.getScoreboardName(), "股票折现#" + b.id, shares, perShare, "折现");
        return "§a已按股票折现：" + shares + " 份 × " + perShare + " = " + cashOut
                + " 唐兔币（" + b.outcome + "）";
    }

    // ---------------- 调试 ----------------

    public static synchronized String debugRoll(String word) {
        return debugRoll(0L, word);
    }

    public static synchronized String debugRoll(long billId, String word) {
        load();
        Bill target = null;
        if (billId > 0L) {
            target = get(billId);
        } else {
            for (int i = BILLS.size() - 1; i >= 0; i--) {
                Bill b = BILLS.get(i);
                if (OPEN.equals(b.status) || VOYAGING.equals(b.status)) {
                    target = b;
                    break;
                }
            }
        }
        if (target == null) {
            return "§c没有活跃的船";
        }
        String o = word == null ? "" : word.trim().toLowerCase();
        if (o.startsWith("good") || o.startsWith("顺")) {
            target.outcome = OUT_GOOD;
        } else if (o.startsWith("fair") || o.startsWith("逆")) {
            target.outcome = OUT_FAIR;
        } else if (o.startsWith("risk") || o.startsWith("遇")) {
            target.outcome = OUT_RISK;
        } else if (o.startsWith("sunk") || o.startsWith("沉")) {
            target.outcome = OUT_SUNK;
        } else {
            return "§c可用：good / fair / risky / sunk";
        }
        if (OPEN.equals(target.status)) {
            depart(target);
            DebugLog.log("调试：提单 #" + target.id + " 先出海再判结果");
        }
        applyOutcome(target);
        save();
        DebugLog.log("调试：提单 #" + target.id + " 强制结果 " + target.outcome);
        return "§a已把 #" + target.id + " 的结果设为 " + target.outcome;
    }

    public static synchronized String debugReset() {
        load();
        long day = com.tangtu.kjbb.ServerCalendar.currentDay();
        int n = 0;
        for (Bill b : BILLS) {
            boolean expired = DUE.equals(b.status);
            boolean finished = CLOSED.equals(b.status) && b.holders.isEmpty();
            if (!expired && !finished) {
                continue;
            }
            b.status = OPEN;
            b.outcome = "";
            b.sold = 0;
            b.holders.clear();
            b.cargo.clear();
            b.cash = 0L;
            b.issueDay = day;
            b.dueDay = day + Math.max(1, BotConfig.get().voyageDays);
            b.deadline = day + Math.max(1, BotConfig.get().voyageFundDays);
            NPC_DONE.removeIf(k -> k.startsWith(b.id + ":"));
            VoyageShareMarket.cancelByBill(b.id);
            n++;
        }
        save();
        DebugLog.log("调试：重置 " + n + " 艘船为招标中");
        return n > 0 ? ("§a已重置 " + n + " 艘船（重新招标）") : "§7没有可重置的船";
    }

    /** 调试：立即结束募资并出海 */
    public static synchronized String debugDepart(long billId) {
        load();
        Bill b = billId > 0 ? get(billId) : activeBill();
        if (b == null) {
            return "§c没有招标中的船";
        }
        if (!OPEN.equals(b.status)) {
            return "§c这艘船不在招标中（" + b.status + "）";
        }
        depart(b);
        return "§a#" + b.id + " 已出海（货值 " + b.cargoValue() + "，份额 "
                + b.sold + "/" + b.total + "）";
    }

    // ---------------- 文案 ----------------

    public static String describe(Bill b) {
        StringBuilder sb = new StringBuilder();
        sb.append("§6#").append(b.id).append(" ").append(b.name)
                .append("  §7[").append(b.status).append("]");
        if (!b.outcome.isEmpty()) {
            sb.append("  结果=").append(b.outcome);
        }
        sb.append('\n').append("§7  份额 ").append(b.sold).append("/").append(b.total)
                .append("　每份 ").append(b.price).append(" 唐兔币")
                .append("　份值 ").append(b.shareValue());
        sb.append('\n').append("§7  资金池 ").append(b.cash).append("/").append(b.targetCash)
                .append("　募资截止 ").append(com.tangtu.kjbb.ServerCalendar.shortDate(b.deadline))
                .append("　到港 ").append(com.tangtu.kjbb.ServerCalendar.shortDate(b.dueDay));
        sb.append('\n').append("§7  载货单：");
        int i = 0;
        for (var e : b.need.entrySet()) {
            if (i++ > 0) {
                sb.append("、");
            }
            int have = b.cargo.getOrDefault(e.getKey(), 0);
            sb.append(e.getKey()).append(" ").append(have).append("/").append(e.getValue());
        }
        return sb.toString();
    }
}
