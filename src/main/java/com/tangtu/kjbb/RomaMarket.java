package com.tangtu.kjbb;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 罗马国 —— 远洋航次的**目的港买方**（外贸对手方）
 *
 * 在引入这个实体之前，「远洋提单」在经济上是封闭的自融资：玩家认购 → 公司买货 →
 * 到期按本地市价估值兑付，钱从玩家的认购款和发行人账上出，货其实没卖给任何人。
 *
 * 罗马国补上了「卖」的另一半：
 *   - 到港时按罗马行情**全量收购**载货，付出唐兔币；
 *   - 收购款进航次资金池，才是持股人兑付的真实来源；
 *   - 买走的货离场（出口），不再分给持股人；
 *   - 收购价 = 本地价 × 品类系数（0.95~1.05）× 行情系数（0.85~1.15）× 整体倍率
 *     `voyageRomaPricePermille`（默认 1150，即比本地价高约 15%），罗马国不赚差价。
 *
 * 货币口径：罗马国代表**外部部门**，它的付款就是出口收汇。账上不够付时差额记为
 * 「贸易投放」（等价于外汇占款），记进台账并可在 /billroma book 查看；这部分
 * 货币由经济部的税收回收。这样既不会凭空造钱不留痕，也不会几船之后就买不起货。
 *
 * 定价拆成两层，和事件系统解耦：
 *   数量侧：船况 / 事件 / 货损（VoyageBill.advanceLeg 决定）
 *   价格侧：罗马行情系数 × 品类系数（本类决定）
 *
 * 行情系数按船号播种，同一航次恒定，避免来回刷新。
 */
public final class RomaMarket {

    public static final String NAME = "罗马国";
    public static final String ROLE = "roma";

    private static final List<String> LOG = new ArrayList<>();
    private static boolean loaded = false;
    /** 累计实际付款（罗马国账上掏出去的） */
    private static long TOTAL_PAID = 0L;
    /** 累计贸易投放（账上不够、由外部部门垫付的部分） */
    private static long TOTAL_INJECTED = 0L;

    private RomaMarket() {
    }

    // ---------------------------------------------------------------- 落盘

    private static Path file() {
        return Path.of("config", "kjbb", "roma_market.txt");
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
                if (line == null || line.isBlank()) {
                    continue;
                }
                if (line.startsWith("#TOTAL|")) {
                    String[] f = line.split("\\|");
                    if (f.length >= 3) {
                        try {
                            TOTAL_PAID = Long.parseLong(f[1].trim());
                            TOTAL_INJECTED = Long.parseLong(f[2].trim());
                        } catch (Throwable ignored) {
                            // 坏行跳过
                        }
                    }
                    continue;
                }
                if (line.startsWith("#")) {
                    continue;
                }
                LOG.add(line);
            }
        } catch (Throwable ignored) {
            // 读不到就当空账
        }
    }

    private static synchronized void save() {
        try {
            Path p = file();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            StringBuilder sb = new StringBuilder(
                    "# billId|day|item|qty|unitPrice|revenue|fxPermille|exportMul\n");
            sb.append("#TOTAL|").append(TOTAL_PAID).append('|').append(TOTAL_INJECTED).append('\n');
            for (String s : LOG) {
                sb.append(s).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ---------------------------------------------------------------- 定价

    /** 罗马行情系数（千分比）：按船号播种，850~1150，同一航次恒定 */
    public static int fxPermille(long billId) {
        Random r = new Random(billId * 31_337L + 20_261_030L);
        return 850 + r.nextInt(301);
    }

    /** 物品分类：优先查数据包目录，查不到再按关键词猜 */
    public static String catOf(String itemId) {
        String fromPack = RomaGoods.catOf(itemId);
        if (fromPack != null) {
            // 目录里用的是 FOOD/MECH/METAL/BUILD/LUXURY/OTHER，统一成价格判定的四种
            switch (fromPack) {
                case "FOOD": return "FOOD";
                case "MECH": return "MECH";
                case "METAL":
                case "RAW": return "METAL";
                case "OIL": return "OIL";
                case "WINE": return "WINE";
                case "TEXTILE": return "TEXTILE";
                case "STONE": return "STONE";
                default: return "OTHER";
            }
        }
        String id = itemId == null ? "" : itemId.toLowerCase(java.util.Locale.ROOT);
        // 油 / 酒先判，避免被后面的粮食或机械规则吃掉
        // 油脂 = 森罗物语（kaleidoscope）的食用油
        if (id.startsWith("kaleidoscope") && id.contains("oil")) {
            return "OIL";
        }
        if (id.contains("_wool") || id.contains("carpet")) {
            return "TEXTILE";
        }
        if (id.contains("calcite") || id.endsWith("_tuff") || id.contains("limestone")) {
            return "STONE";
        }
        if (id.contains("wine") || id.contains("beer") || id.contains("cider")
                || id.contains("whiskey") || id.contains("vodka") || id.contains("mead")
                || id.contains("liquor") || id.contains("brew")) {
            return "WINE";
        }
        if (id.contains("ingot") || id.contains("nugget") || id.contains("scrap")
                || id.contains("_ore") || id.contains("raw_") || id.contains("crushed_raw")
                || id.contains("_sheet") || id.contains("_plate")) {
            return "METAL";
        }
        if (id.startsWith("create:")) {
            return "MECH";
        }
        if (id.contains("wheat") || id.contains("carrot") || id.contains("potato")
                || id.contains("beetroot") || id.contains("sugar_cane")
                || id.contains("pumpkin") || id.contains("bread") || id.contains("rice")) {
            return "FOOD";
        }
        if (id.contains("diamond") || id.contains("emerald") || id.contains("redstone")
                || id.contains("coal") || id.contains("_log") || id.contains("planks")) {
            return "RAW";
        }
        return "OTHER";
    }

    /**
     * 罗马国对这类货的相对价格（比较优势）
     *
     * 唐兔国粮食富余 → 罗马缺粮，粮价高（1.55）
     * 罗马工业强 → 机械便宜（0.72）
     * 原料大体自给（1.10）、其余（1.00）
     */
    public static double exportMul(String itemId) {
        var cfg = BotConfig.get();
        String cat = catOf(itemId);
        int fallback;
        switch (cat) {
            case "FOOD" -> fallback = cfg.romaMulFoodPermille;
            case "METAL" -> fallback = cfg.romaMulMetalPermille;
            case "MECH" -> fallback = cfg.romaMulMechPermille;
            case "OIL" -> fallback = cfg.romaMulOilPermille;
            case "WINE" -> fallback = cfg.romaMulWinePermille;
            case "TEXTILE" -> fallback = cfg.romaMulTextilePermille;
            case "STONE" -> fallback = cfg.romaMulStonePermille;
            default -> fallback = cfg.romaMulOtherPermille;
        }
        // 目录里若给了该品类的相对价，优先用目录的
        String packCat = RomaGoods.catOf(itemId);
        int pm = RomaGoods.pricePermille(packCat, fallback);
        return Math.max(0.1, pm / 1000.0);
    }

    /** 罗马对该品类的需求参考量（越小越快降价） */
    public static int refQtyOf(String itemId) {
        var cfg = BotConfig.get();
        switch (catOf(itemId)) {
            case "FOOD":
                return Math.max(16, cfg.romaRefFoodQty);
            case "METAL":
                return Math.max(16, cfg.romaRefMetalQty);
            case "MECH":
                return Math.max(16, cfg.romaRefMechQty);
            case "OIL":
                return Math.max(16, cfg.romaRefOilQty);
            case "WINE":
                return Math.max(16, cfg.romaRefWineQty);
            case "TEXTILE":
                return Math.max(16, cfg.romaRefTextileQty);
            case "STONE":
                return Math.max(16, cfg.romaRefStoneQty);
            default:
                return Math.max(16, cfg.voyageEvalRefQty);
        }
    }

    /** 本地估值单价（和 VoyageBill.priceOf 同源） */
    public static long unitPrice(String itemId) {
        long p = MarketPrice.price(itemId);
        if (p <= 0L) {
            p = ShopPrices.price(itemId);
        }
        return p <= 0L ? 1L : p;
    }

    /**
     * 量价系数：罗马国对商品的评估——**累计量越大，单价越低**
     *
     * 用反比曲线 f(q) = R / (R + q/2)：
     *   q = 0     → 1.000（原价）
     *   q = R     → 0.667（约七折）
     *   q = 3R    → 0.400
     * 永远不会到 0，也不会出现硬台阶。R 由 voyageEvalRefQty 配置。
     */
    public static double evalFactor(long cumulativeQty) {
        return evalFactor(cumulativeQty, Math.max(32, BotConfig.get().voyageEvalRefQty));
    }

    /** 按该品类的参考量算量价系数 */
    public static double evalFactor(long cumulativeQty, int ref) {
        if (cumulativeQty <= 0L) {
            return 1.0;
        }
        int r = Math.max(16, ref);
        return r / (r + cumulativeQty / 2.0);
    }

    /** 到港时的罗马成交单价（不含量价折扣，展示用） */
    public static long salePrice(String itemId, long billId) {
        return salePrice(itemId, billId, 0L);
    }

    /** 基础价：不含罗马预算系数（进口用它，避免被储备波动带偏） */
    public static long basePrice(String itemId, long billId, long cumulativeQty) {
        double mul = exportMul(itemId) * fxPermille(billId) / 1000.0;
        double price = BotConfig.get().voyageRomaPricePermille / 1000.0;
        double f = evalFactor(cumulativeQty, refQtyOf(itemId));
        return Math.max(1L, Math.round(unitPrice(itemId) * mul * price * f));
    }

    /** 到港时的罗马成交单价：累计量越大越低，且随罗马储备浮动 */
    public static long salePrice(String itemId, long billId, long cumulativeQty) {
        long base = basePrice(itemId, billId, cumulativeQty);
        return Math.max(1L, Math.round(base * budgetFactor()));
    }

    /**
     * 一次成交的**均价**：取累计量的中点代入曲线，避免整单按最低价结算。
     *
     * 例如已成交 q0 件，本次再收 qty 件 → 用 (q0 + qty/2) 处的单价作为本单均价。
     */
    /** 指定参考量的均价（整船批发用 voyageEvalShipRefQty） */
    public static long salePriceForRange(String itemId, long billId, long alreadyQty, int qty, int refQty) {
        long mid = alreadyQty + Math.max(1, qty / 2);
        double mul = exportMul(itemId) * fxPermille(billId) / 1000.0;
        double price = BotConfig.get().voyageRomaPricePermille / 1000.0;
        double f = evalFactor(mid, Math.max(16, refQty));
        return Math.max(1L, Math.round(unitPrice(itemId) * mul * price * f));
    }

    public static long salePriceForRange(String itemId, long billId, long alreadyQty, int qty) {
        long mid = alreadyQty + Math.max(1, qty / 2);
        return salePrice(itemId, billId, mid);
    }

    /** 整船货款（罗马国全量收购，按每种的实际装载量套量价曲线） */
    public static long fullRevenue(VoyageBill.Bill b) {
        long sum = 0L;
        for (Map.Entry<String, Integer> e : b.cargo.entrySet()) {
            Integer q = e.getValue();
            if (q == null || q <= 0) {
                continue;
            }
            var cfg0 = BotConfig.get();
            boolean useCurve = cfg0.voyageEvalShipCargo;
            // 整船是批发：参考量取「品类参考量」和「批发参考量」里更大的那个
            int ref = Math.max(refQtyOf(e.getKey()), Math.max(16, cfg0.voyageEvalShipRefQty));
            long unit = useCurve
                    ? salePriceForRange(e.getKey(), b.id, 0L, q, ref)
                    : salePrice(e.getKey(), b.id);
            sum += unit * q;
        }
        return sum;
    }

    /**
     * 罗马的预算系数：储备越少，出价越低。
     *
     * 储备 = 罗马国账上现金（玩家进口付款会补回来，出口收购会抽走），
     * 系数 = 储备 ÷ 基准，夹在 [floor, ceil] 之间。
     *
     * 效果：罗马没钱 → 压价 → 玩家自然少卖；玩家来进口 → 储备回血 → 出价回升。
     */
    public static double budgetFactor() {
        var cfg = BotConfig.get();
        int ref = Math.max(1000, cfg.romaBudgetRef);
        double floor = Math.max(100, cfg.romaBudgetFloorPermille) / 1000.0;
        double ceil = Math.max(100, cfg.romaBudgetCeilPermille) / 1000.0;
        double cashNow = Math.max(0L, cash());
        double f = cashNow / ref;
        return Math.max(floor, Math.min(ceil, f));
    }

    /** 罗马国账上现有现金（会随收货减少） */
    public static long cash() {
        try {
            BotLedger.load();
            return BotLedger.money(NAME);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static long totalPaid() {
        load();
        return TOTAL_PAID;
    }

    public static long totalInjected() {
        load();
        return TOTAL_INJECTED;
    }

    // ---------------------------------------------------------------- 收购

    /**
     * 到港收购：**全量收货**，货款进航次资金池。
     *
     * 罗马国账上钱不够时，差额记为贸易投放（外部部门垫付），不会因为现金不足停止收货。
     *
     * @return 给日志看的摘要，没交易返回 null
     */
    public static synchronized String settle(VoyageBill.Bill b, long day) {
        load();
        if (b == null || b.cargo.isEmpty()) {
            return null;
        }
        if (b.revenue > 0L) {
            return null;    // 这船已经收过货了
        }
        long revenue = fullRevenue(b);
        if (revenue <= 0L) {
            return null;
        }
        long have = cash();
        long paid = Math.min(revenue, Math.max(0L, have));
        long injected = revenue - paid;
        if (paid > 0L) {
            try {
                BotLedger.addMoney(NAME, -paid);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        TOTAL_PAID += paid;
        TOTAL_INJECTED += injected;

        int fx = fxPermille(b.id);
        int soldQty = 0;
        for (Map.Entry<String, Integer> e : b.cargo.entrySet()) {
            Integer qty = e.getValue();
            if (qty == null || qty <= 0) {
                continue;
            }
            long price = salePrice(e.getKey(), b.id);
            LOG.add(b.id + "|" + day + "|" + e.getKey() + "|" + qty + "|" + price
                    + "|" + (price * qty) + "|" + fx + "|"
                    + String.format(java.util.Locale.ROOT, "%.2f", exportMul(e.getKey())));
            soldQty += qty;
        }
        // 收来的货进罗马国库存（会被生产消耗、也会再出口给玩家）
        for (var en : b.cargo.entrySet()) {
            if (en.getValue() != null && en.getValue() > 0) {
                addToStock(en.getKey(), en.getValue());
            }
        }
        b.cargo.clear();
        b.revenue += revenue;
        if (b.charter && b.charterer != null && !b.charterer.isEmpty()) {
            // 包船：货是包船人的，货款直接进他钱包（公司只收过运费）
            try {
                BankBridge.addMoney(b.charterer, revenue);
            } catch (Throwable ignored) {
                // 记账失败就退化成进资金池，避免钱凭空消失
                b.cash += revenue;
            }
        } else {
            b.cash += revenue;
        }
        save();
        try {
            long avg = Math.max(1L, revenue / Math.max(1, soldQty));
            BotTrades.log(NAME, VoyageBill.issuerName(), "远洋货款#" + b.id, soldQty, avg, "收货");
        } catch (Throwable ignored) {
            // ignore
        }
        VoyageBill.save();
        DebugLog.log("罗马国收购 #" + b.id + " " + b.name + "：" + soldQty + " 件，货款 "
                + revenue + "，行情 " + fx + "‰，账上付 " + paid
                + (injected > 0 ? "，贸易投放 " + injected : ""));
        return "罗马国收购 " + soldQty + " 件，付出 " + revenue + " 唐兔币"
                + (injected > 0 ? "（其中贸易投放 " + injected + "）" : "");
    }

    // ---------------------------------------------------------------- 往返：到罗马换货

    /**
     * 船到罗马：把去程的货卖给罗马国，再用货款进一批返程货。
     *
     * 钱怎么走（方案 A：罗马国垫货、从货款里扣）：
     *   罗马国付出口货款 exportRev
     *   罗马国卖返程货给船，收回 spent（= 本地价 × voyageImportDiscount）
     *   净额 = exportRev − spent：自营进航次资金池，包船直接进包船人钱包
     *
     * @return 日志摘要；没换货返回 null
     */
    public static synchronized String exchangeForReturn(VoyageBill.Bill b, long day) {
        load();
        if (b == null || b.cargo.isEmpty() || b.imported) {
            return null;
        }
        var cfg = BotConfig.get();
        long exportRev = fullRevenue(b);
        if (exportRev <= 0L) {
            return null;
        }

        // ① 去程货入库：粮食/金属成为罗马国的生产原料与存货
        for (var en : new java.util.LinkedHashMap<>(b.cargo).entrySet()) {
            if (en.getValue() != null && en.getValue() > 0) {
                addToStock(en.getKey(), en.getValue());
            }
        }

        // ② 返程货从**罗马国库存**里挑（只挑本国出口的品类），预算 = 出口收入的 60%
        java.util.Map<String, Integer> stock = new java.util.LinkedHashMap<>();
        try {
            stock.putAll(BotLedger.stockMap(NAME));
        } catch (Throwable ignored) {
            // 库存读不到就当没有
        }
        java.util.List<String> avail = new java.util.ArrayList<>();
        for (var en : stock.entrySet()) {
            Integer q = en.getValue();
            if (q == null || q <= 0) {
                continue;
            }
            String cat = catOf(en.getKey());
            if ("MECH".equals(cat) || "OIL".equals(cat) || "WINE".equals(cat)
                    || "TEXTILE".equals(cat) || "STONE".equals(cat)
                    || "LUXURY".equals(cat) || "BUILD".equals(cat)) {
                avail.add(en.getKey());
            }
        }
        avail.sort(java.util.Comparator.comparingLong(RomaMarket::unitPrice).reversed());

        long budget = exportRev * 600L / 1000L;
        // ③ 旧币优先：先拿一批罗马旧币（面值 1 的铜币），剩下的钱再买货
        long spent = 0L;
        java.util.Map<String, Integer> back = new java.util.LinkedHashMap<>();
        try {
            long coinBudget = Math.max(0L, budget);   // 罗马只回旧币：返程预算全部换成硬币
            if (coinBudget > 0L) {
                spent += fillRandomCoins(back, coinBudget);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (back.isEmpty()) {
            DebugLog.log("航次 #" + b.id + " 抵罗马：罗马国库存不足，返程空船（出口货款 "
                    + exportRev + " 全数结算）");
        }
        if (back.isEmpty() && exportRev <= 0L) {
            return null;
        }

        b.cargo.clear();
        b.cargo.putAll(back);
        b.importCost = spent;
        b.imported = true;
        b.revenue += exportRev;

        long net = exportRev - spent;
        if (net < 0L) {
            net = 0L;
        }
        // 远洋关税：出口货款 + 返程货成本各抽一份，归国库
        try {
            var tcfg = BotConfig.get();
            long tariff = Math.max(0L, exportRev) * Math.max(0, tcfg.voyageTariffPermille) / 1000L;
            tariff += Math.max(0L, spent) * Math.max(0, tcfg.voyageImportTariffPermille) / 1000L;
            if (tariff > 0L) {
                String reg = MarketBots.regulatorName();
                if (reg != null && !reg.isEmpty()) {
                    // 罗马自留一半关税（罗马也有税赋）
                    long romaCut = tariff / 2L;
                    long tangCut = tariff - romaCut;
                    b.cash = Math.max(0L, b.cash - tariff);
                    net = Math.max(0L, net - tariff);
                    if (tangCut > 0L) {
                        BotLedger.addMoney(reg, tangCut);
                        BotTrades.log(safeIssuer(), reg, "远洋关税#" + b.id, 1, tangCut, "关税");
                    }
                    if (romaCut > 0L) {
                        Ledger.credit(NAME, romaCut);
                        BotTrades.log(safeIssuer(), NAME, "罗马税赋#" + b.id, 1, romaCut, "罗马税");
                        DebugLog.log("罗马税赋 #" + b.id + "：" + romaCut + " 归罗马国库");
                    }
                    // 罗马城税：额外按出口货款抽一份，直接归罗马
                    try {
                        long cityTax = Math.max(0L, exportRev)
                                * Math.max(0, tcfg.romaCityTaxPermille) / 1000L;
                        if (cityTax > 0L) {
                            b.cash = Math.max(0L, b.cash - cityTax);
                            net = Math.max(0L, net - cityTax);
                            Ledger.credit(NAME, cityTax);
                            BotTrades.log(safeIssuer(), NAME, "罗马城税#" + b.id, 1, cityTax, "城税");
                            DebugLog.log("罗马城税 #" + b.id + "：" + cityTax + " 归罗马国库");
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    DebugLog.log("远洋关税 #" + b.id + " 共 " + tariff
                            + "（出口 " + exportRev + " × " + tcfg.voyageTariffPermille
                            + "‰ + 返程 " + spent + " × " + tcfg.voyageImportTariffPermille + "‰）"
                            + " 唐兔 " + tangCut + " / 罗马 " + romaCut);
                }
            }
        } catch (Throwable ignored) {
            // 关税失败不影响结算
        }
        if (b.charter && b.charterer != null && !b.charterer.isEmpty()) {
            try {
                BankBridge.addMoney(b.charterer, net);
            } catch (Throwable ignored) {
                b.cash += net;
            }
        } else {
            b.cash += net;
        }

        // 罗马国这边：先付出口款，再收回返程货款
        long romaHave = cash();
        long paid = Math.min(net, Math.max(0L, romaHave));
        long injected = net - paid;
        if (paid > 0L) {
            try {
                BotLedger.addMoney(NAME, -paid);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        TOTAL_PAID += paid;
        TOTAL_INJECTED += injected;

        save();
        VoyageBill.save();
        DebugLog.log("航次 #" + b.id + " 抵罗马：出口货款 " + exportRev + "，进返程货 "
                + back.size() + " 种花 " + spent + "，净 " + net
                + (injected > 0 ? "（贸易投放 " + injected + "）" : ""));
        return "抵罗马：卖货 " + exportRev + "，进返程货 " + spent + "，净入 " + net;
    }

    /** 罗马国库存报表（给 /billroma stock 用） */
    public static String stockReport() {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        try {
            m.putAll(BotLedger.stockMap(NAME));
        } catch (Throwable ignored) {
            // 为空
        }
        StringBuilder sb = new StringBuilder("§6===== 罗马国库存 =====\n");
        sb.append("§7储备 ").append(String.format("%,d", cash()))
                .append("   预算系数 §f").append(String.format("%.2f", budgetFactor()))
                .append("§7  物品种类 §f").append(m.size()).append("\n");
        String[][] cats = {
            {"FOOD", "粮食"}, {"METAL", "金属"}, {"MECH", "机械"},
            {"OIL", "油脂"}, {"WINE", "葡萄酒"}, {"TEXTILE", "羊毛"},
            {"STONE", "石料"}, {"BUILD", "建材"}, {"LUXURY", "奢侈"},
            {"OTHER", "其他"},
        };
        for (String[] c : cats) {
            long sum = 0L;
            java.util.List<String> top = new java.util.ArrayList<>();
            for (var e : m.entrySet()) {
                if (c[0].equals(catOf(e.getKey())) && e.getValue() != null && e.getValue() > 0) {
                    sum += e.getValue();
                    top.add(e.getKey().replace("minecraft:", "") + "×" + e.getValue());
                }
            }
            if (sum <= 0L) {
                continue;
            }
            sb.append("§e").append(c[1]).append("§7 共 §f").append(sum).append("§7 件  ");
            int shown = 0;
            for (String t : top) {
                if (shown++ >= 4) {
                    sb.append("§8…");
                    break;
                }
                sb.append("§f").append(t).append("§7 ");
            }
            sb.append("\n");
        }
        sb.append("§7生产节奏：每日保底 ").append(BotConfig.get().romaProdFloorPerDay)
                .append(" 件，满产约 ")
                .append(BotConfig.get().romaProdFloorPerDay
                        + (BotConfig.get().romaProdFoodPerDay * BotConfig.get().romaProdFoodValue
                        + BotConfig.get().romaProdMetalPerDay * BotConfig.get().romaProdMetalValue)
                        * BotConfig.get().romaProdPerPointPermille / 1000)
                .append(" 件");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 生产与库存

    /** 罗马国的库存（复用 BotLedger 的库存表，键就是罗马国这个机器人） */
    public static long stockOf(String itemId) {
        try {
            return BotLedger.stock(NAME, itemId);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 某品类在库存里的总量（用数据包分类，兜底关键词） */
    public static long stockOfCategory(String category) {
        long sum = 0L;
        try {
            var m = BotLedger.stockMap(NAME);
            for (var e : m.entrySet()) {
                if (category.equals(catOf(e.getKey()))) {
                    sum += e.getValue();
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return sum;
    }

    /** 收货入库（带上限，防止无限膨胀） */
    public static void addToStock(String itemId, int qty) {
        if (itemId == null || itemId.isEmpty() || qty <= 0) {
            return;
        }
        try {
            int cap = Math.max(64, BotConfig.get().romaStockCapPerItem);
            long have = BotLedger.stock(NAME, itemId);
            int room = (int) Math.max(0L, Math.min(qty, cap - have));
            if (room > 0) {
                BotLedger.addStock(NAME, itemId, room);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 从库存取货，返回实取数量 */
    public static int takeFromStock(String itemId, int qty) {
        if (itemId == null || itemId.isEmpty() || qty <= 0) {
            return 0;
        }
        try {
            long have = BotLedger.stock(NAME, itemId);
            int take = (int) Math.max(0L, Math.min(qty, have));
            if (take > 0) {
                BotLedger.addStock(NAME, itemId, -take);
            }
            return take;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 罗马国的每日生产：**把粮食和金属转化成生产力**
     *
     * <pre>
     *   消耗  粮食 ≤ romaProdFoodPerDay × 天数，  金属 ≤ romaProdMetalPerDay × 天数
     *   点数  = 粮食 × romaProdFoodValue + 金属 × romaProdMetalValue
     *   产出  = 保底（romaProdFloorPerDay × 天数）
     *         + 点数 × romaProdPerPointPermille / 1000
     *   分配  机械 50% / 油脂 25% / 葡萄酒 25%
     * </pre>
     *
     * 保底产出**不吃任何输入**，所以哪怕国库空了、粮价崩了，罗马国也还在生产，
     * 不会一夜之间彻底停摆。
     */
    public static synchronized void productionTick(long day, long days) {
        var cfg = BotConfig.get();
        if (!cfg.romaProductionEnabled || days <= 0L) {
            return;
        }
        long maxFood = Math.max(0L, (long) cfg.romaProdFoodPerDay * days);
        long maxMetal = Math.max(0L, (long) cfg.romaProdMetalPerDay * days);

        // 按「先吃便宜的」顺序从库存里扣粮和金属
        long foodUsed = consumeCategory("FOOD", maxFood);
        long metalUsed = consumeCategory("METAL", maxMetal);

        long points = foodUsed * Math.max(0, cfg.romaProdFoodValue)
                + metalUsed * Math.max(0, cfg.romaProdMetalValue);
        long floorOut = Math.max(0L, (long) cfg.romaProdFloorPerDay * days);
        long pointsOut = points * Math.max(0, cfg.romaProdPerPointPermille) / 1000L;
        long total = floorOut + pointsOut;
        if (total <= 0L) {
            return;
        }

        long mech = total * Math.max(0, cfg.romaProdMechPermille) / 1000L;
        long oil = total * Math.max(0, cfg.romaProdOilPermille) / 1000L;
        long textile = total * Math.max(0, cfg.romaProdTextilePermille) / 1000L;
        long stone = total * Math.max(0, cfg.romaProdStonePermille) / 1000L;
        long wine = Math.max(0L, total - mech - oil - textile - stone);

        addToStock(cfg.romaProdMechItem, (int) Math.min(Integer.MAX_VALUE, mech));
        addToStock(cfg.romaProdOilItem, (int) Math.min(Integer.MAX_VALUE, oil));
        addToStock(cfg.romaProdWineItem, (int) Math.min(Integer.MAX_VALUE, wine));
        addToStock(cfg.romaProdTextileItem, (int) Math.min(Integer.MAX_VALUE, textile));
        addToStock(cfg.romaProdStoneItem, (int) Math.min(Integer.MAX_VALUE, stone));

        // 罗马城自身消化：产物库存按日耗千分比递减
        try {
            int decay = Math.max(0, cfg.romaStockDecayPermille);
            if (decay > 0) {
                long lost = 0L;
                for (String it : new String[]{cfg.romaProdMechItem, cfg.romaProdOilItem,
                        cfg.romaProdWineItem, cfg.romaProdTextileItem, cfg.romaProdStoneItem}) {
                    int have = BotLedger.stock(NAME, it);
                    if (have <= 0) {
                        continue;
                    }
                    int cut = (int) Math.min(have, (long) have * decay * days / 1000L);
                    if (cut > 0) {
                        BotLedger.addStock(NAME, it, -cut);
                        lost += cut;
                    }
                }
                if (lost > 0L) {
                    DebugLog.log("罗马城内消化：" + lost + " 件（" + decay + "‰/日 × " + days + "日）");
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }

        DebugLog.log("罗马国生产：消耗粮 " + foodUsed + "、金属 " + metalUsed
                + " → 生产力 " + points + " 点，产出 机械 " + mech + " / 油脂 " + oil
                + " / 葡萄酒 " + wine + " / 羊毛 " + textile + " / 方解石 " + stone
                + "（其中保底 " + floorOut + "）"
                + "，库存 机械 " + stockOf(cfg.romaProdMechItem)
                + " / 羊毛 " + stockOf(cfg.romaProdTextileItem)
                + " / 方解石 " + stockOf(cfg.romaProdStoneItem));
    }

    /** 从某品类库存里扣最多 want 件（先扣单价最低的），返回实扣数量 */
    private static long consumeCategory(String category, long want) {
        if (want <= 0L) {
            return 0L;
        }
        long left = want;
        long used = 0L;
        java.util.Map<String, Integer> m;
        try {
            m = new java.util.LinkedHashMap<>(BotLedger.stockMap(NAME));
        } catch (Throwable t) {
            return 0L;
        }
        java.util.List<String> items = new java.util.ArrayList<>();
        for (var e : m.entrySet()) {
            if (category.equals(catOf(e.getKey())) && e.getValue() != null && e.getValue() > 0) {
                items.add(e.getKey());
            }
        }
        items.sort(java.util.Comparator.comparingLong(RomaMarket::unitPrice));
        for (String item : items) {
            if (left <= 0L) {
                break;
            }
            int have = m.getOrDefault(item, 0);
            int take = (int) Math.min(left, have);
            if (take <= 0) {
                continue;
            }
            int got = takeFromStock(item, take);
            used += got;
            left -= got;
        }
        return used;
    }

    // ---------------------------------------------------------------- 展示

    /** 给玩家看的报价单 */
    public static String quote(VoyageBill.Bill b) {
        if (b == null) {
            return "§c没有这张提单";
        }
        if (b.cargo.isEmpty()) {
            return "§7船上还没装货，无从报价";
        }
        int fx = fxPermille(b.id);
        long full = fullRevenue(b);
        StringBuilder sb = new StringBuilder();
        sb.append("§6===== 罗马国 报价 · #").append(b.id).append(" ").append(b.name)
                .append(" =====\n");
        sb.append("§7行情系数 ").append(fx).append("‰（").append(fx / 10.0).append("%）");
        if (b.arrivalValue > 0L) {
            sb.append("　到港估值 ").append(b.arrivalValue);
        }
        sb.append('\n');
        for (Map.Entry<String, Integer> e : b.cargo.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            String item = e.getKey();
            long unit = salePriceForRange(item, b.id, 0L, e.getValue());
            sb.append("§e").append(item.replace("minecraft:", "").replace("create:", "C·"))
                    .append("§7 ×").append(e.getValue())
                    .append("　单价 §f").append(unit)
                    .append("§7（×").append(String.format(java.util.Locale.ROOT, "%.2f", exportMul(item)))
                    .append("）　小计 §f").append(unit * e.getValue()).append('\n');
        }
        sb.append("§6整船货款 §f").append(full);
        return sb.toString();
    }

    /** 罗马国账目 */
    public static String book() {
        load();
        return "§6===== 罗马国 =====\n"
                + "§7账上现金　§f" + cash() + '\n'
                + "§7累计付款　§f" + TOTAL_PAID + '\n'
                + "§7贸易投放　§f" + TOTAL_INJECTED + "§7（账上不够付、由外部部门垫付的部分）\n"
                + "§7成交批次　§f" + LOG.size();
    }

    /** 行情速览：在售/在航各船的系数 */
    public static String fxBoard() {
        StringBuilder sb = new StringBuilder("§6===== 罗马行情 =====\n");
        boolean any = false;
        for (VoyageBill.Bill b : VoyageBill.all()) {
            if (VoyageBill.CLOSED.equals(b.status)) {
                continue;
            }
            any = true;
            int fx = fxPermille(b.id);
            sb.append("§e#").append(b.id).append(" ").append(b.name)
                    .append("§7　行情 ").append(fx).append("‰")
                    .append("　整船货款 ").append(fullRevenue(b))
                    .append('\n');
        }
        if (!any) {
            sb.append("§7（没有在册航次）\n");
        }
        sb.append("§7品类系数：机械 1.05　原料 1.00　粮食 0.95　其他 0.98");
        return sb.toString();
    }

    /**
     * 返程旧币：把预算随机拆成各种面额的硬币，而不是全给铜币。
     * 面额越大越稀有，所以按面值加权随机。
     */
    private static long fillRandomCoins(java.util.Map<String, Integer> out, long budget) {
        long left = Math.max(0L, budget);
        long spent = 0L;
        java.util.List<String> ids = VoyageRules.coinIds();
        if (ids.isEmpty() || left <= 0L) {
            return 0L;
        }
        java.util.Random rnd = new java.util.Random();
        // 从大到小试，每种随机决定拿多少
        ids.sort((x, y) -> Long.compare(VoyageRules.coinFace(y), VoyageRules.coinFace(x)));
        for (String id : ids) {
            long face = Math.max(1L, VoyageRules.coinFace(id));
            if (left < face) {
                continue;
            }
            long part = (long) (left * (0.15 + rnd.nextDouble() * 0.45));
            long cnt = part / face;
            if (cnt <= 0L) {
                continue;
            }
            cnt = Math.min(cnt, 2048L);
            out.merge(id, (int) cnt, Integer::sum);
            spent += cnt * face;
            left -= cnt * face;
        }
        // 剩下的零头全用最小面额补齐
        if (left > 0L) {
            long face = Math.max(1L, VoyageRules.coinFace(ids.get(ids.size() - 1)));
            long cnt = left / face;
            if (cnt > 0L) {
                out.merge(ids.get(ids.size() - 1), (int) cnt, Integer::sum);
                spent += cnt * face;
            }
        }
        return spent;
    }
    /** 关税记账用：发行人名字（取不到就 "-"） */
    private static String safeIssuer() {
        try {
            String n = VoyageBill.issuerName();
            return n == null || n.isEmpty() ? "-" : n;
        } catch (Throwable t) {
            return "-";
        }
    }
}