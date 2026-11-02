package com.tangtu.kjbb;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 机器人参数（可由数据包 / 配置文件编辑）。
 *
 * ① 数据包：data/&lt;命名空间&gt;/kjbb/bots/*.json（重载数据包即生效）
 * ② 配置文件：config/kjbb/bots.json（启动时读取，/billbot reload 可手动重读）
 *
 * 支持的字段（都可省略）：
 * {
 *   "intervalTicks": 100,          // 多少次服务器 tick 尝试一次（20 tick = 1 秒）
 *   "maxOffers": 12,               // 同时存在的机器人挂卖上限
 *   "maxOrders": 8,                // 同时存在的机器人买单上限
 *   "offerTtlMs": 600000,          // 挂单存活时间（毫秒）
 *   "minMoney": 20000,             // 钱包低于此值不再收购
 *   "minStock": 32,                // 某物品库存低于此值不再挂卖
 *   "sellMin": 0.98, "sellMax": 1.12,   // 卖价区间（相对市价）
 *   "buyMin": 0.86,  "buyMax": 0.96,    // 收购价区间（相对市价）
 *   "sellCountMin": 8, "sellCountMax": 32,
 *   "buyCountMin": 8,  "buyCountMax": 24,
 *   "bots": [
 *     { "name": "经济部", "money": 500000, "stock": 1024, "idleChance": 0.35 }
 *   ]
 * }
 */
public final class BotConfig {

    public static final class Bot {
        public String name = "机器人";
        public long money = 200_000L;
        public int stock = 512;
        public double idleChance = 0.35;
        /** stabilizer = 调节市场；noise = 搅乱市场；neutral = 普通 */
        public String role = "neutral";
        /** 长期收购的物品（货越多，收购价越低） */
        public List<String> longBuy = new ArrayList<>();
        /** 货量达到这个数时价格降到 floor */
        public int longBuyCapacity = 4000;
        /** 价格下限（相对基准价的比例） */
        public double longBuyFloor = 0.25;
        /** 每笔收购数量 */
        public int longBuyCount = 128;
        /** 每轮尝试长期收购的概率 */
        public double longBuyChance = 0.65;

        // ---- 调节者（role = regulator）专用 ----
        /** 允许的价格偏离带：市价超出 基础价×(1±band) 就出手 */
        public double targetBand = 0.15;
        /** 每次干预的下单量 */
        public int interveneCount = 128;
        /** 每轮干预概率 */
        public double interveneChance = 0.85;

        public double longBuyFactor(int stock) {
            double f = 1.0 - (double) Math.max(0, stock) / Math.max(1, longBuyCapacity);
            if (f < longBuyFloor) {
                f = longBuyFloor;
            }
            if (f > 1.0) {
                f = 1.0;
            }
            return f;
        }
    }

    public static Bot bot(String name) {
        return byName(name);
    }

    public static final String ROLE_STABILIZER = "stabilizer";
    public static final String ROLE_NOISE = "noise";
    public static final String ROLE_REGULATOR = "regulator";
    public static final String ROLE_NEUTRAL = "neutral";
    /** 远洋提单：到期游戏日数 / 每船总份数 / 发行折价（0.85 = 货值的 85%） */
    public int voyageDays = 30;
    /** 每个航次的目标货值（招标时按此反推需求量） */
    public long voyageTargetValue = 50000L;
    /** 每种货的最少/最多需求量 */
    public int voyageNeedMin = 16;
    public int voyageNeedMax = 1024;
    /** 招标募资期（游戏日）：到截止日若没募满也照常出海 */
    public int voyageFundDays = 3;
    /** 两艘船之间至少隔多少游戏日（上一艘结束后，航运公司自动续开的间隔） */
    public int voyageAutoIssueDays = 8;
    /** 罗马国评估货物的参考量：累计量达到这个数时，单价约打七折 */
    public int voyageEvalRefQty = 512;
    /** 整船货款是否也套量价曲线（默认关：整船是批发，量价曲线只用在玩家零售出口单上） */
    public boolean voyageEvalShipCargo = true;
    /** 整船批发的量价参考量（比零售大，价格掉得慢） */
    public int voyageEvalShipRefQty = 2048;
    /** 包船运费（千分比 × 货值），最低 500 */
    /** 罗马国收购价倍率（千分比）：1150 = 比本地价高 15%，用来让包船有利可图 */
    /** 是否跑往返航次（去程交货给罗马国，装返程货回国） */
    /**
     * 罗马国的相对价格（千分比，相对唐兔国本地价）
     *
     * 比较优势：唐兔国粮食富余 → 卖到罗马贵；罗马工业强 → 机械在罗马便宜。
     * 于是「出口粮食、进口机械」才是有利可图的方向。
     */
    /** 罗马储备基准：储备等于这个数时预算系数为 1.0 */
    /** 罗马国生产：把粮食和金属转化成产能，产机械/油/酒 */
    public boolean romaProductionEnabled = true;
    /** 每日最多消耗的粮食 */
    /** 罗马城税：每趟航次按出口货款的千分比，直接归罗马国库 */
    public int romaCityTaxPermille = 30;
    /** 罗马库存日耗千分比（机械/油/酒/羊毛/方解石，被罗马城自己消化掉） */
    public int romaStockDecayPermille = 60;
    public int romaProdFoodPerDay = 160;
    /** 每日最多消耗的金属 */
    public int romaProdMetalPerDay = 96;
    /** 每单位粮食折算的生产力点 */
    public int romaProdFoodValue = 1;
    /** 每单位金属折算的生产力点 */
    public int romaProdMetalValue = 3;
    /** 保底生产力：每日固定产出件数，不消耗任何输入 */
    public int romaProdFloorPerDay = 4;
    /** 生产力点 → 产出件数（千分比，250 = 每 4 点产 1 件） */
    public int romaProdPerPointPermille = 250;
    /** 产出分配（千分比）：机械 / 油脂，剩下的给葡萄酒 */
    public int romaProdMechPermille = 400;
    public int romaProdOilPermille = 200;
    /** 纺织（羊毛）与石料（方解石）的产出比例 */
    public int romaProdTextilePermille = 100;
    public int romaProdStonePermille = 100;
    /** 产出对应的物品 */
    public String romaProdMechItem = "create:cogwheel";
    public String romaProdOilItem = "kaleidoscope_cookery:oil";
    public String romaProdWineItem = "kaleidoscope_tavern:wine";
    public String romaProdTextileItem = "minecraft:white_wool";
    public String romaProdStoneItem = "minecraft:calcite";
    /** 罗马库存单项上限 */
    public int romaStockCapPerItem = 8192;
    public int romaBudgetRef = 400000;
    /** 储备见底时的最低出价系数（千分比） */
    public int romaBudgetFloorPermille = 600;
    /** 储备充裕时的抢货系数（千分比） */
    public int romaBudgetCeilPermille = 1200;
    /** 金属：罗马需求量大 → 出价高、跌价慢 */
    /** 纺织品（羊毛）：罗马产量大 → 便宜 */
    public int romaMulTextilePermille = 600;
    /** 石料（方解石）：罗马产量大 → 便宜 */
    public int romaMulStonePermille = 550;
    public int romaMulMetalPermille = 1350;
    /** 油脂：罗马产量大 → 便宜（唐兔国该进口） */
    public int romaMulOilPermille = 620;
    /** 葡萄酒：罗马产量大 → 便宜 */
    public int romaMulWinePermille = 650;
    public int romaMulFoodPermille = 1550;
    public int romaMulRawPermille = 1100;
    public int romaMulMechPermille = 720;
    public int romaMulOtherPermille = 1000;
    /** 罗马对粮食需求的参考量（越小越快降价） */
    public int romaRefFoodQty = 2048;
    /** 金属的需求参考量 */
    public int romaRefMetalQty = 2048;
    public int romaRefTextileQty = 1024;
    public int romaRefStoneQty = 1024;
    /** 油脂/酒的供给参考量（产量大，价格本来就不高） */
    public int romaRefOilQty = 1024;
    public int romaRefWineQty = 1024;
    /** 罗马对机械需求的参考量（工业品需求大，价格掉得慢） */
    public int romaRefMechQty = 1024;
    public boolean voyageRoundTrip = true;
    /** 回程段数 */
    public int voyageReturnLegs = 2;
    /** 回程沉没概率系数（千分比，500 = 减半） */
    public int voyageReturnSinkPermille = 500;
    /** 罗马国给船的返程货价 = 本地价 × 该折扣 */
    public double voyageImportDiscount = 0.90;
    /** 返程货留给持股人提货的比例（千分比） */
    /** 返程货留给持股人提货的比例（千分比）。0 = 全部由公司收走折现；
     *  注意：提货只拿货、不发现金，和折现相比价值会低一些。 */
    public int voyageImportKeepPermille = 0;
    public int voyageRomaPricePermille = 2600;
    public int voyageCharterFeePermille = 80;
    /** 单次包船的货值上限 */
    public int voyageCharterMaxValue = 50000;
    /** 单次包船每种货的件数上限 */
    public int voyageCharterMaxQtyPerItem = 1024;
    /** 出库存上限（千分比，300 = 最多动用库存的 30%） */
    public int voyageStockPermille = 300;
    /** NPC 出钱上限（千分比，占其现金比例） */
    public int voyageNpcCashPermille = 300;
    public int voyageTotalShares = 1000;
    public double voyageDiscount = 0.80;

    /**
     * 航行事件表（可在 bots.json 的 "voyageEvents" 里覆盖）
     *
     * 判定方式：每段掷 0~99 一次，先判沉没，再按 tGood/tCalm/tBad/tStorm/tPirate
     * 这几个上界依次落桶，落在最后的桶（tPirate 之上）就是触礁。
     */
    public static final class VoyageEventCfg {
        /** 基础沉没概率（%） */
        public int sinkPct = 8;
        /** 船况低于 condBad 时额外增加的沉没概率（%） */
        public int sinkExtra = 15;
        /** 「船况差」的阈值 */
        public int condBad = 45;
        /** 各桶上界（0~100，必须递增） */
        public int tGood = 22;
        public int tCalm = 45;
        public int tBad = 62;
        public int tStorm = 80;
        public int tPirate = 93;
        /** mult 增减 */
        public int dGood = 2;
        public int dBad = -9;
        public int dStorm = -18;
        public int dPirate = -26;
        public int dReef = -35;
        /** 船况损伤 */
        public int dmgBad = 5;
        public int dmgStorm = 22;
        public int dmgPirate = 18;
        public int dmgReef = 35;
        /** 货损（%） */
        public int lossStorm = 10;
        public int lossPirate = 18;
        public int lossReef = 25;
        /** mult 上限 */
        public int multCap = 130;

        public VoyageEventCfg copy() {
            VoyageEventCfg c = new VoyageEventCfg();
            c.sinkPct = sinkPct;
            c.sinkExtra = sinkExtra;
            c.condBad = condBad;
            c.tGood = tGood;
            c.tCalm = tCalm;
            c.tBad = tBad;
            c.tStorm = tStorm;
            c.tPirate = tPirate;
            c.dGood = dGood;
            c.dBad = dBad;
            c.dStorm = dStorm;
            c.dPirate = dPirate;
            c.dReef = dReef;
            c.dmgBad = dmgBad;
            c.dmgStorm = dmgStorm;
            c.dmgPirate = dmgPirate;
            c.dmgReef = dmgReef;
            c.lossStorm = lossStorm;
            c.lossPirate = lossPirate;
            c.lossReef = lossReef;
            c.multCap = multCap;
            return c;
        }
    }

    /** 当前生效的航行事件表 */
    public VoyageEventCfg voyageEvents = new VoyageEventCfg();
    /** 大A膏手是否参与股票交易板 */
    public boolean traderStockEnabled = true;
    /** 是否参加一级市场认购（招标期直接出钱换份额） */
    public boolean traderStockPrimary = true;
    /** 每轮行动概率（千分比，600 = 60%） */
    public int traderStockChance = 600;
    /** 最多动用多少现金（千分比） */
    public int traderStockPermille = 400;
    /** 买入阈值：挂单价 ≤ 公允价 × 该百分比（90 = 九折才买） */
    public int traderStockBuyFactor = 90;
    /** 卖出挂价：公允价 × 该百分比（115 = 溢价 15% 才挂） */
    public int traderStockSellFactor = 115;

    /** 航行分几段判定风险（段数越多越平滑） */
    public int voyageLegs = 3;
    /** 航运公司：不参与日常做市，只做远洋航次（后续股票系统的载体） */
    public static final String ROLE_SHIPPING = "shipping";
    /** 罗马国：目的港买方（外贸对手方） */
    public static final String ROLE_ROMA = "roma";

    /** 经济部是否允许收购该物品（白名单为空则用内置默认） */
    public boolean regulatorAllows(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        var list = regulatorBuyWhitelist;
        if (list.isEmpty()) {
            for (String s : REGULATOR_DEFAULT_WHITELIST) {
                if (s.equals(itemId)) {
                    return true;
                }
            }
            return false;
        }
        return list.contains(itemId);
    }

    public static String role(String bot) {
        Bot b = byName(bot);
        return b == null ? ROLE_NEUTRAL : (b.role == null ? ROLE_NEUTRAL : b.role);
    }

    private static volatile BotConfig INSTANCE = defaults();
    /** 最近一次数据包覆盖（/billbot reload 重读 bots.json 后需要重新套用） */
    private static volatile java.util.Map<String, com.google.gson.JsonElement> LAST_OVERRIDES = null;

    /** 经济部默认白名单：粮食 + 基础原料 */
    private static final String[] REGULATOR_DEFAULT_WHITELIST = {
            "minecraft:wheat", "minecraft:carrot", "minecraft:potato",
            "minecraft:beetroot", "minecraft:bread",
            "minecraft:iron_ingot", "minecraft:copper_ingot",
            "create:zinc_ingot", "minecraft:coal"
    };

    /** 批量落盘间隔（tick）：100 = 5 秒刷一次脏数据 */
    /** 时间源：game=游戏刻（/time 可推进，用于测试），real=系统时钟 */
    public String timeSource = "game";
    public int saveIntervalTicks = 100;
    /** 性能计时开关 */
    public boolean perfEnabled = true;
    /** 单段耗时预算（毫秒），超了写一条 [PERF] 日志 */
    public int perfBudgetMs = 5;
    /** 单个 tick 内允许花在市场计算上的毫秒数；超了就推迟重活到下一轮 */
    public int taskBudgetMs = 8;
    /** 性能摘要日志间隔（秒），0 = 关闭 */
    public int perfLogSeconds = 120;
    public int intervalTicks = 100;
    public int maxOffers = 12;
    public int maxOrders = 8;
    /** 每 N 笔成交合成一根 K 线（<=1 表示按时间周期） */
    public int klineTradesPerBar = 10;
    /** 没成交的周期是否补一根平价空柱（默认 false：直接跳过） */
    public boolean klineFillEmptyBars = false;
    public long offerTtlMs = 600_000L;
    public long minMoney = 20_000L;
    public int minStock = 32;
    public double sellMin = 0.98;
    public double sellMax = 1.12;
    public double buyMin = 0.86;
    public double buyMax = 0.96;
    public int sellCountMin = 8;
    public int sellCountMax = 32;
    public int buyCountMin = 8;
    public int buyCountMax = 24;
    /** 每分钟向基础价回拉的比例（0 = 完全不拉，价格完全由成交决定） */
    public double priceAnchorPerMin = 0.0;
    /** 库存对界面价的影响（0 = 不影响，1 = 完整影响） */
    public double stockInfluence = 0.0;
    /** 库存弹性指数（0 或负 = 用 shop_prices.properties 里的值） */
    public double stockElasticity = 0.3;
    /** 库存因子上下限（0 = 用配置文件值） */
    public double stockFactorMin = 0.5;
    public double stockFactorMax = 2.0;
    /** 目标库存按人口缩放：人口 = 该值时目标库存为配置值本身 */
    public int stockTargetBasePopulation = 100;
    /** 单物品库存上限 = 目标库存 × 该倍率（下限 256） */
    public double citizenStockCapRatio = 1.5;
    /** 仓库固定损耗（每游戏日千分比）：普通食物 / 高级食物 / 建材 */
    public int citizenSpoilageFoodPermille = 10;
    public int citizenSpoilagePremiumPermille = 30;
    public int citizenSpoilageMaterialPermille = 3;
    /** 基准价长期跟随（技术革新/量产带来的永久降价能反映到基准价） */
    public boolean baseLongRunEnabled = true;
    public double baseLongRunIntervalHours = 6.0;
    public double baseLongRunWindowHours = 24.0;
    public double baseLongRunTrackRatio = 0.10;
    public int baseLongRunMinTrades = 20;
    /** 仓单仓储管理费：每日费率（千分比）与欠费宽限天数 */
    public int storageFeePermille = 5;
    public int storageFeeGraceDays = 3;
    /** 传送石碑：激活价、传送基础价、每 100 格加价、跨维度附加 */
    /** 破产危机：警戒线 = minMoney × 该倍率；抛售折扣 */
    /** 单次最多补结算多少个游戏日（防止 /time 跳变） */
    /** 服务器无人时暂停国民经济（不吃饭、不损耗） */
    /** 特殊商品（传送石碑/传送石）：每次零售上架的概率与固定价 */
    /** 大A膏手的精明决策概率（越低越常亏钱） */
    public double traderSmartChance = 0.5;
    /** 大A膏手：资金低于该值就停止交易；每 N 日补助 M 唐兔币 */
    /** 经济部交易税（千分比，10 = 1%） */
    /** 投机客只做金融（股票/票据/航次），不碰实物市场 */
    /** NPC 参与航次的门槛：募资期过了百分之多少才让机器人进场 */
    /** 船东自购上限：最多占总份数的千分比（300 = 30%），避免船东自己买满 */
    /** 允许同时在跑的航次数（1 = 串行，>1 = 船队并行） */
    public int voyageMaxConcurrent = 1;
    public int voyageIssuerMaxSharePermille = 400;
    public int voyageNpcJoinAfterPermille = 300;
    /** 玩家认购达到总份数的百分之多少就提前解锁机器人 */
    public int voyageNpcJoinPlayerSharePermille = 300;
    public boolean traderFinancialOnly = true;
    public int regulatorTaxPermille = 30;
    /** 人口增长是否「自带人均财富」（false = 不凭空注资，彻底取消印钱） */
    public boolean citizenBirthMintEnabled = false;
    /** 国民可以向国库的收购单供货（第二条进钱渠道） */
    public boolean citizenSellToRegulatorEnabled = true;
    public int citizenSellToRegulatorMaxPerRound = 6;
    /** 经济部允许收购的物品白名单（空 = 不限制） */
    public final java.util.List<String> regulatorBuyWhitelist = new java.util.ArrayList<>();
    /** 国库常平仓：低价收粮托底 */
    /**
     * 经济部是否主动「把货价调回基准价」。
     *
     * false（默认）= 不做价格调控：
     *   · 常平仓不再按基准价托市收购
     *   · 国库不再按基准价放货压价
     *   · 农产品托底买单不下
     *   · 长期收购（longBuy）不下
     * 仍然保留：征税、石碑费、接管国民滞销单、危机收购、给国民卖口粮。
     */
    /** 国库现金低于这个数时，不再参与航次出货（交货换份额） */
    /** 国库向玩家「高买粮食」：价格 = 基准价 × 这个倍数（1.15 = 高于市价 15%） */
    public double regulatorGrainBuyFactor = 1.05;
    /** 高买每天预算 */
    public long regulatorGrainBuyBudget = 7_500L;
    /** 高买每种一次挂多少件 */
    /** 高买粮食只允许玩家供货（国民不许接高买单） */
    public boolean regulatorGrainBuyPlayerOnly = true;
    public int regulatorGrainBuyCount = 128;
    /** 收购项是否取自共享贸易品目录（goods.json）里的农作物 */
    public boolean regulatorGrainUseCatalog = true;
    /** 目录里收哪些：制作难度上限（0=只收生鲜作物，1=含简单加工） */
    public int regulatorGrainMaxTier = 0;
    /** 高买每天最多挂几种 */
    public int regulatorGrainBuyKinds = 4;
    /** 国库向国民「低卖粮食」：价格 = 基准价 × 这个倍数（0.70 = 打七折） */
    public double regulatorGrainSellFactor = 0.70;
    /** 低卖是否启用 */
    public boolean regulatorGrainLowSellEnabled = true;
    /** 国库单船最多持有多少份额（千分比，100 = 船的十分之一） */
    /** 国库按量放货：单种库存超过这个件数就把多出来的卖掉 */
    public boolean regulatorStockSellEnabled = true;
    public int regulatorStockSellThreshold = 1024;
    /** 卖价 = 基准价 × 这个系数（1.0 = 平价挂） */
    /** 远洋关税：每趟航次出口货款抽取的千分比（80 = 8%），归国库 */
    /** 国库现金警戒线：低于此值停止高买粮食 */
    public long regulatorCashWarnHighBuy = 300_000L;
    /** 国库现金警戒线：低于此值停止参与航次 */
    public long regulatorCashWarnVoyage = 150_000L;
    public int voyageTariffPermille = 80;
    /** 返程货是否也抽税（按进货成本） */
    public int voyageImportTariffPermille = 40;
    public double regulatorStockSellPriceFactor = 1.15;
    /** 每种每天最多放多少件 */
    public int regulatorStockSellMaxPerItem = 256;
    /** 每天最多放几种 */
    public int regulatorStockSellDailyKinds = 8;
    public int regulatorVoyageMaxSharePermille = 100;
    public long regulatorVoyageCargoMinMoney = 200_000L;
    /** 同一阈值下是否也不再为航次出资（出钱补资金池）——出资实测亏损，默认一并停 */
    public boolean regulatorVoyageInvestStopToo = true;
    public boolean regulatorPriceControlEnabled = false;
    public boolean regulatorGranaryEnabled = true;
    public int regulatorGranaryTargetDays = 15;
    public double regulatorGranaryBuyBand = 0.92;
    public double regulatorGranaryBuyPriceFactor = 0.95;
    public long regulatorGranaryDailyBudget = 20000L;
    /** 国库放货：市价高于基准价 × regulatorSellBand 时把库存卖出去 */
    public boolean regulatorSellEnabled = true;
    public double regulatorSellBand = 1.10;
    public double regulatorSellPriceFactor = 1.02;
    public int regulatorSellMaxPerItem = 256;
    public int regulatorSellDailyKinds = 6;
    /** 国民内部交易：定时生成一批成交，用来驱动物价（替代对敲） */
    public boolean citizenInternalTradeEnabled = true;
    public int citizenInternalTradeIntervalMinutes = 10;
    public int citizenInternalTradeMaxTrades = 24;
    public int citizenInternalTradeCountMin = 4;
    public int citizenInternalTradeCountMax = 32;
    public double citizenInternalTradeChance = 1.0;
    /** 国民自己种粮/挖矿（不再领固定收入） */
    /** 只在「本轮赚了钱」时才花现金买食物（粮食危机线以下例外） */
    /** 是否按共享贸易品目录扩大可产池（粮/金属/建材三类里的基础品） */
    public boolean citizenProducePoolEnabled = true;
    /** 可产池里食物的最高制作难度（0=只产生鲜，1=含简单加工） */
    public int citizenProduceFoodMaxTier = 2;
    /** 可产池每类最多收录多少种（防止一次产太分散） */
    public int citizenProducePoolMaxKinds = 120;
    /** 每轮只挑库存最少的多少种来生产（避免几十种各产几件） */
    public int citizenProducePickKinds = 12;
    /** 玩家买单挂多久没成交 → 国民开始生产它（分钟） */
    public int citizenStaleOrderMinutes = 10;
    /** 为「玩家长期买不到的东西」留多少产能（千分比） */
    public int citizenStaleDemandSharePermille = 300;
    /** 国民是否把闲置资金投到航次股票（交易行的份额挂单） */
    public boolean citizenInvestEnabled = true;
    /** 保留多少现金不用于投资（运营备用金） */
    public long citizenInvestReserveCash = 60000L;
    /** 单笔投资不超过可用闲置资金的千分比 */
    public int citizenInvestMaxSharePermille = 300;
    /** 每轮最多买几笔 */
    public int citizenInvestMaxPerRound = 3;
    /** 只买「挂单价 ≤ 公允价 × 这个千分比」的票（1150 = 允许溢价 15%） */
    public int citizenInvestMaxPremiumPermille = 1150;
    public boolean citizenBuyOnlyWhenProfit = true;
    /** 买食物最多花掉现金的千分比（原来硬编码 50%） */
    public int citizenBuyCashPermille = 300;
    /** 目标粮食储备天数 */
    public int citizenFoodReserveDays = 20;
    /** 粮食危机线：低于这个天数不看盈亏，必须补 */
    public int citizenFoodCrisisDays = 10;
    /** 粮食低于危机线时不再把粮挂零售（保命粮） */
    public boolean citizenReserveFoodWhenCrisis = true;
    /** 粮食恢复到多少天才重新开卖（迟滞，必须 > 危机线，避免来回抖） */
    public int citizenFoodResumeDays = 8;
    public boolean citizenProductionEnabled = true;
    /** 每人每天产出件数 */
    public int citizenProductionPerDay = 8;
    /** 国民每轮被动收入（默认 0：必须靠卖产出赚钱） */
    public long citizenPassiveIncome = 0L;
    /** 国民零售刷新间隔（分钟） */
    public int citizenRetailRefreshMinutes = 30;
    /** 国民挂单超过多少分钟没被玩家买走，允许机器人接管 */
    public int citizenOfferTakeoverMinutes = 15;
    /** 单轮接管最多花多少钱（唐兔币） */
    public long citizenOfferTakeoverMaxPerRound = 5000L;
    /** 单轮接管最多花掉国库的多少（千分比，50 = 5%） */
    public int citizenOfferTakeoverTreasuryPermille = 50;
    /** 是否允许机器人接管「没人要」的国民挂单 */
    public boolean botTakeoverCitizenOffers = true;
    /** 机器人之间是否互相对敲（false = 机器人只和玩家交易） */
    public boolean botCrossTradeEnabled = false;
    /** 基础口粮：国库按折扣价卖粮给国民，保证不饿死（但吃不饱，不生育） */
    public boolean citizenRationEnabled = true;
    /** 口粮价 = 基准价 × 该系数 */
    public double citizenRationDiscount = 0.5;
    /** 只在国民存粮不足这些天时供应 */
    public int citizenRationReserveDays = 3;
    /** 吃口粮的满意度上限（低于生育线 → 玩家不供粮就不会增人口） */
    public double citizenRationSatisfactionCap = 0.55;
    /** 生育所需的最低平滑满意度 */
    public double citizenBirthMinSatisfaction = 0.6;
    /** 国民经济主体名字（留空 = 自动推断：role=neutral 的机器人） */
    public String citizenName = "";
    /** 机器人之间的成交是否收税（false = 只对玩家收） */
    public boolean regulatorTaxBots = true;
    public long traderStopMoney = 25000L;
    /** 库存价值达到「停工线 × 该倍数」时不算穷（防止资金少但货多时卡死） */
    public double traderStopStockValueFactor = 2.0;
    public long traderGrantAmount = 5000L;
    public int traderGrantDays = 30;
    public int citizenSpecialChance = 3;
    public long citizenSpecialPrice = 25600L;
    /** 附魔金苹果的固定售价 */
    public long citizenSpecialApplePrice = 12800L;
    public boolean citizenPauseWhenEmpty = true;
    public int citizenMaxCatchupDays = 3;
    /** 破产清算参数 */
    public double citizenBankruptRatio = 0.4;
    public double citizenBankruptHours = 2.0;
    public double citizenBankruptSellDiscount = 0.5;
    public double citizenBankruptPopRatio = 0.7;
    public int citizenBankruptFreezeDays = 7;
    public double citizenCrisisWarnRatio = 1.6;
    public double citizenCrisisDiscount = 0.30;
    public long waystoneActivatePrice = 500L;
    public long waystoneTeleportBase = 0L;
    /** 每格传送距离的价钱（唐兔币/格） */
    public long waystoneTeleportPerBlock = 1L;
    public long waystoneTeleportDimension = 6400L;
    /** 只有这些机器人参与「经营周期」（默认只有唐兔国国民） */
    public final java.util.List<String> botOperateBots = new java.util.ArrayList<>();

    /** 国民每天吃掉多少（小麦/胡萝卜/面包） */
    public int citizenEatWheat = 1;
    public int citizenEatCarrot = 1;
    public int citizenEatBread = 1;

    /** 价格每分钟向基准价回弹的速度（千分比，15 = 1.5%/分钟） */
    public int citizenPriceRecoverRate = 15;

    /** 国民零售：加价率(%)、上架比例(%)、单笔上限、每周期种类 */
    public int citizenRetailMarkup = 160;
    public int citizenRetailRatio = 30;
    public int citizenRetailMax = 512;
    public int citizenRetailKinds = 6;

    /** 仓单收购：折价门槛(%)、每周期最多买几张 */
    public int citizenReceiptDiscount = 85;
    public int citizenReceiptMaxPerCycle = 2;
    /** 仓单收购的库存上限：该物品库存已达此数就不再买 */
    public int citizenReceiptStockCap = 1024;
    /** 国民总库存件数上限：所有物品加起来不超过这个数 */
    public int citizenTotalStockCap = 50000;

    /** 稀有物品需求：触发概率(%)、单次上限 */
    public int citizenRareChance = 5;
    public int citizenRareMax = 64;

    /** 大单播报阈值（总价低于此值不公告） */
    public long bigOrderAnnounceMin = 50_000L;

    /** 城建需求：最低单价、单笔占池子上限(%) */
    public int citizenCityDemandMinPrice = 1;
    public int citizenCityDemandPoolRatio = 15;

    /** 城建需求：每人折合件数、单笔上限 */
    public int citizenCityDemandPerHead = 16;
    public int citizenCityDemandMax = 4096;

    /** 食物偏低价值过滤：偏好分低于此值不收 */
    public int citizenMinFoodScore = 15;
    /** 单笔最少件数，低于此值跳过 */
    public int citizenMinOrderCount = 32;

    /** 大城市批发：触发概率(%)、最小人口、每人件数、单笔上限 */
    public int citizenBigOrderChance = 50;
    public int citizenBigOrderMinPeople = 30;
    public int citizenBigOrderPerHead = 40;
    public int citizenBigOrderMax = 4096;

    /** 每次最多收购几种食物 */
    public int citizenBuyKinds = 6;
    /** 单种食物最多收购件数 */
    public int citizenBuyMaxEach = 256;

    /** 每人每天需要的饱食度（面包 11 / 胡萝卜 5 / 小麦 1） */
    public int citizenFoodPerDay = 4;
    /** 每点饱食度的收购价（唐兔币） */
    public int citizenPricePerFood = 34;

    /** 人均财富（资金池 / 该值 = 人口） */
    public int citizenPerCapita = 5000;
    /** 承载上限（人口） */
    public int citizenCarryCapacity = 200;
    /** 饿肚子时每天的基础增长率（千分比，2 = 0.2%/天） */
    public int citizenDailyGrowthPermille = 2;

    /** 资金池每周期复利增长（千分比，5 = 0.5%） */
    public int citizenGrowthPermille = 5;

    /** 国民每 30 天工资（唐兔币） */
    public long citizenSalary = 3000L;

    /** 每个在线玩家对应的收购件数（每周期） */
    public int citizenBuyPerPlayer = 64;

    /** 国民收粮周期（游戏天数，默认 30） */
    public int citizenCycleGameDays = 30;

    /** 机器人每周期基础收入（唐兔币） */
    public int botIncomePerCycle = 300;
    /** 机器人每周期消耗的原材料件数 */
    public int botConsumePerCycle = 32;
    /** 经营周期（tick，默认 6000 = 5 分钟） */
    public int botCycleTicks = 6000;

    /** 全服同时在线假人上限 */
    public int laborMaxBots = 16;
    /** 每个玩家同时在线假人上限 */
    public int laborMaxPerPlayer = 3;

    /**
     * 合同按「铁锭篮子」计价：1 小时 = N 个铁锭的当前市值。
     * 默认 120（正好等于 1 台铁钻 1 小时的产出，即单钻刚好回本）。
     */
    public int laborBasketIron = 120;

    /** 劳动合同兜底工价（篮子价算不出来时使用） */
    /** 劳动合同工价（唐兔币）与工期（分钟） */
    public long laborPrice = 2000L;
    public int laborMinutes = 60;

    /** 旧币按齿轮币档位等值（copper=spur, iron=bevel …），避免找零一钱包硬币 */
    public boolean oldCoinTierMap = false;

    /** 这些物品只能用实物硬币（旧币/齿轮币）结算，不能用唐兔币 */
    public final java.util.List<String> coinOnlyItems = new java.util.ArrayList<>();

    /** 右键零售方块时，自动把旧币换成齿轮币 */
    public boolean autoConvertCoins = true;
    /** 右键零售方块时，自动从钱包取多少钱换成齿轮币（0 = 不取） */
    public long autoCashOut = 8192L;

    /** 玩家挂单价 ≤ 市场价 ×(1-margin) 时机器人会收（0.05 = 便宜 5% 才收） */
    public double playerOfferMargin = 0.05;
    /** 每轮机器人收玩家挂单的概率（再乘活跃度） */
    public double playerOfferChance = 0.6;
    /** 单笔最多收多少件（0 = 不限） */
    public int playerOfferMaxCount = 256;

    /** 玩家成交达到这个数量（笔/小时）时，机器人退到最低活跃度 */
    public int activityFullAt = 40;
    /** 机器人最低活跃度（0 = 完全退场，1 = 不收缩） */
    public double activityFloor = 0.15;

    /** 价格绝对下限（一般 1） */
    public long priceFloor = 1L;

    /** K 线一根多长（毫秒），默认 5 分钟 */
    public long klinePeriodMs = 12_000_000L;   // 10 游戏日

    public final List<Bot> bots = new ArrayList<>();
    /** 只让机器人交易这些物品（空 = 全部） */
    public final List<String> onlyItems = new ArrayList<>();

    public static java.util.List<String> onlyItems() {
        return get().onlyItems;
    }

    /** 必须用实物硬币结算的物品 */
    public static java.util.List<String> coinOnlyItems() {
        return get().coinOnlyItems;
    }

    public static BotConfig get() {
        return INSTANCE;
    }

    public static void set(BotConfig c) {
        if (c != null && !c.bots.isEmpty()) {
            INSTANCE = c;
        }
    }

    public static BotConfig defaults() {
        BotConfig c = new BotConfig();
        Bot a = new Bot();
        a.name = "经济部";
        a.money = 500_000L;
        a.stock = 1024;
        a.idleChance = 0.30;
        a.role = ROLE_STABILIZER;
        a.longBuy.add("minecraft:wheat");
        a.longBuy.add("minecraft:carrot");
        a.longBuy.add("minecraft:potato");
        a.longBuy.add("minecraft:beetroot");
        a.longBuy.add("minecraft:sugar_cane");
        a.longBuy.add("minecraft:pumpkin");
        Bot b = new Bot();
        b.name = "唐兔国国民";
        b.money = 200_000L;
        b.stock = 512;
        b.idleChance = 0.40;
        b.role = ROLE_NEUTRAL;
        Bot d = new Bot();
        d.name = "大A膏手";
        d.money = 80_000L;
        d.stock = 256;
        d.idleChance = 0.50;
        d.role = ROLE_NOISE;
        Bot e = new Bot();
        e.name = "唐兔国航运公司";
        e.money = 800_000L;
        e.stock = 1024;
        e.idleChance = 0.20;
        e.role = ROLE_SHIPPING;
        Bot f = new Bot();
        f.name = "罗马国";
        f.money = 600_000L;
        f.stock = 0;
        f.idleChance = 1.00;
        f.role = ROLE_ROMA;
        c.bots.add(a);
        c.bots.add(b);
        c.bots.add(d);
        c.bots.add(e);
        c.bots.add(f);
        return c;
    }

    public static String[] names() {
        List<Bot> list = get().bots;
        String[] out = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i).name;
        }
        return out;
    }

    public static Bot byName(String name) {
        if (name == null) {
            return null;
        }
        for (Bot b : get().bots) {
            if (b.name.equals(name)) {
                return b;
            }
        }
        return null;
    }

    public static long principal(String bot) {
        Bot b = byName(bot);
        return b == null ? 200_000L : b.money;
    }

    public static int stock0(String bot) {
        Bot b = byName(bot);
        return b == null ? 512 : b.stock;
    }

    public static double idleChance(String bot) {
        Bot b = byName(bot);
        return b == null ? 0.35 : b.idleChance;
    }

    // ---------------------------------------------------------------- 读取

    /** 合并一份 JSON（数据包里的每个文件都调一次） */
    public static synchronized void merge(JsonElement el) {
        try {
            if (el == null || !el.isJsonObject()) {
                return;
            }
            BotConfig c = copy(INSTANCE);
            JsonObject o = el.getAsJsonObject();
            if (o.has("timeSource")) {
                try { c.timeSource = o.get("timeSource").getAsString(); }
                catch (Throwable ignored) { }
            }
            c.saveIntervalTicks = optInt(o, "saveIntervalTicks", c.saveIntervalTicks);
            c.perfBudgetMs = optInt(o, "perfBudgetMs", c.perfBudgetMs);
            c.taskBudgetMs = optInt(o, "taskBudgetMs", c.taskBudgetMs);
            c.perfLogSeconds = optInt(o, "perfLogSeconds", c.perfLogSeconds);
            if (o.has("perfEnabled")) {
                c.perfEnabled = o.get("perfEnabled").getAsBoolean();
            }
            c.intervalTicks = optInt(o, "intervalTicks", c.intervalTicks);
            c.voyageAutoIssueDays = optInt(o, "voyageAutoIssueDays", c.voyageAutoIssueDays);
            c.voyageEvalRefQty = optInt(o, "voyageEvalRefQty", c.voyageEvalRefQty);
            c.voyageEvalShipRefQty = optInt(o, "voyageEvalShipRefQty", c.voyageEvalShipRefQty);
            if (o.has("romaProductionEnabled")) {
                c.romaProductionEnabled = o.get("romaProductionEnabled").getAsBoolean();
            }
            c.romaProdFoodPerDay = optInt(o, "romaProdFoodPerDay", c.romaProdFoodPerDay);
            c.romaProdMetalPerDay = optInt(o, "romaProdMetalPerDay", c.romaProdMetalPerDay);
            c.romaProdFoodValue = optInt(o, "romaProdFoodValue", c.romaProdFoodValue);
            c.romaProdMetalValue = optInt(o, "romaProdMetalValue", c.romaProdMetalValue);
            c.romaProdFloorPerDay = optInt(o, "romaProdFloorPerDay", c.romaProdFloorPerDay);
            c.romaProdPerPointPermille = optInt(o, "romaProdPerPointPermille", c.romaProdPerPointPermille);
            c.romaProdMechPermille = optInt(o, "romaProdMechPermille", c.romaProdMechPermille);
            c.romaProdOilPermille = optInt(o, "romaProdOilPermille", c.romaProdOilPermille);
            c.romaProdTextilePermille = optInt(o, "romaProdTextilePermille", c.romaProdTextilePermille);
            c.romaProdStonePermille = optInt(o, "romaProdStonePermille", c.romaProdStonePermille);
            if (o.has("romaProdTextileItem")) {
                c.romaProdTextileItem = o.get("romaProdTextileItem").getAsString();
            }
            if (o.has("romaProdStoneItem")) {
                c.romaProdStoneItem = o.get("romaProdStoneItem").getAsString();
            }
            c.romaProdOilPermille = optInt(o, "romaProdOilPermille", c.romaProdOilPermille);
            c.romaStockCapPerItem = optInt(o, "romaStockCapPerItem", c.romaStockCapPerItem);
            if (o.has("romaProdMechItem")) {
                c.romaProdMechItem = o.get("romaProdMechItem").getAsString();
            }
            if (o.has("romaProdOilItem")) {
                c.romaProdOilItem = o.get("romaProdOilItem").getAsString();
            }
            if (o.has("romaProdWineItem")) {
                c.romaProdWineItem = o.get("romaProdWineItem").getAsString();
            }
            c.romaBudgetRef = optInt(o, "romaBudgetRef", c.romaBudgetRef);
            c.romaBudgetFloorPermille = optInt(o, "romaBudgetFloorPermille", c.romaBudgetFloorPermille);
            c.romaBudgetCeilPermille = optInt(o, "romaBudgetCeilPermille", c.romaBudgetCeilPermille);
            c.romaMulTextilePermille = optInt(o, "romaMulTextilePermille", c.romaMulTextilePermille);
            c.romaMulStonePermille = optInt(o, "romaMulStonePermille", c.romaMulStonePermille);
            c.romaMulMetalPermille = optInt(o, "romaMulMetalPermille", c.romaMulMetalPermille);
            c.romaMulOilPermille = optInt(o, "romaMulOilPermille", c.romaMulOilPermille);
            c.romaMulWinePermille = optInt(o, "romaMulWinePermille", c.romaMulWinePermille);
            c.romaMulFoodPermille = optInt(o, "romaMulFoodPermille", c.romaMulFoodPermille);
            c.romaMulRawPermille = optInt(o, "romaMulRawPermille", c.romaMulRawPermille);
            c.romaMulMechPermille = optInt(o, "romaMulMechPermille", c.romaMulMechPermille);
            c.romaMulOtherPermille = optInt(o, "romaMulOtherPermille", c.romaMulOtherPermille);
            c.romaRefTextileQty = optInt(o, "romaRefTextileQty", c.romaRefTextileQty);
            c.romaRefStoneQty = optInt(o, "romaRefStoneQty", c.romaRefStoneQty);
            c.romaRefMetalQty = optInt(o, "romaRefMetalQty", c.romaRefMetalQty);
            c.romaRefOilQty = optInt(o, "romaRefOilQty", c.romaRefOilQty);
            c.romaRefWineQty = optInt(o, "romaRefWineQty", c.romaRefWineQty);
            c.romaRefFoodQty = optInt(o, "romaRefFoodQty", c.romaRefFoodQty);
            c.romaRefMechQty = optInt(o, "romaRefMechQty", c.romaRefMechQty);
            if (o.has("voyageRoundTrip")) {
                c.voyageRoundTrip = o.get("voyageRoundTrip").getAsBoolean();
            }
            c.voyageReturnLegs = optInt(o, "voyageReturnLegs", c.voyageReturnLegs);
            c.voyageReturnSinkPermille = optInt(o, "voyageReturnSinkPermille", c.voyageReturnSinkPermille);
            c.voyageImportDiscount = optDouble(o, "voyageImportDiscount", c.voyageImportDiscount);
            c.voyageImportKeepPermille = optInt(o, "voyageImportKeepPermille", c.voyageImportKeepPermille);
            c.voyageRomaPricePermille = optInt(o, "voyageRomaPricePermille", c.voyageRomaPricePermille);
            c.voyageCharterFeePermille = optInt(o, "voyageCharterFeePermille", c.voyageCharterFeePermille);
            c.voyageCharterMaxValue = optInt(o, "voyageCharterMaxValue", c.voyageCharterMaxValue);
            c.voyageCharterMaxQtyPerItem = optInt(o, "voyageCharterMaxQtyPerItem", c.voyageCharterMaxQtyPerItem);
            if (o.has("voyageEvalShipCargo")) {
                c.voyageEvalShipCargo = o.get("voyageEvalShipCargo").getAsBoolean();
            }
            if (o.has("voyageEvents") && o.get("voyageEvents").isJsonObject()) {
                JsonObject ev = o.getAsJsonObject("voyageEvents");
                VoyageEventCfg e = c.voyageEvents;
                e.sinkPct = optInt(ev, "sinkPct", e.sinkPct);
                e.sinkExtra = optInt(ev, "sinkExtra", e.sinkExtra);
                e.condBad = optInt(ev, "condBad", e.condBad);
                e.tGood = optInt(ev, "tGood", e.tGood);
                e.tCalm = optInt(ev, "tCalm", e.tCalm);
                e.tBad = optInt(ev, "tBad", e.tBad);
                e.tStorm = optInt(ev, "tStorm", e.tStorm);
                e.tPirate = optInt(ev, "tPirate", e.tPirate);
                e.dGood = optInt(ev, "dGood", e.dGood);
                e.dBad = optInt(ev, "dBad", e.dBad);
                e.dStorm = optInt(ev, "dStorm", e.dStorm);
                e.dPirate = optInt(ev, "dPirate", e.dPirate);
                e.dReef = optInt(ev, "dReef", e.dReef);
                e.dmgBad = optInt(ev, "dmgBad", e.dmgBad);
                e.dmgStorm = optInt(ev, "dmgStorm", e.dmgStorm);
                e.dmgPirate = optInt(ev, "dmgPirate", e.dmgPirate);
                e.dmgReef = optInt(ev, "dmgReef", e.dmgReef);
                e.lossStorm = optInt(ev, "lossStorm", e.lossStorm);
                e.lossPirate = optInt(ev, "lossPirate", e.lossPirate);
                e.lossReef = optInt(ev, "lossReef", e.lossReef);
                e.multCap = optInt(ev, "multCap", e.multCap);
            }
            c.klinePeriodMs = optLong(o, "klinePeriodMs", c.klinePeriodMs);
            c.priceAnchorPerMin = optDouble(o, "priceAnchorPerMin", c.priceAnchorPerMin);
            c.stockInfluence = optDouble(o, "stockInfluence", c.stockInfluence);
            c.priceFloor = optLong(o, "priceFloor", c.priceFloor);
            if (o.has("coinOnlyItems") && o.get("coinOnlyItems").isJsonArray()) {
                c.coinOnlyItems.clear();
                for (JsonElement e : o.getAsJsonArray("coinOnlyItems")) {
                    try {
                        c.coinOnlyItems.add(e.getAsString().trim());
                    } catch (Throwable ignored) {
                        // 跳过
                    }
                }
            }
            if (o.has("citizenBirthMintEnabled")) {
                try { c.citizenBirthMintEnabled = o.get("citizenBirthMintEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("citizenSellToRegulatorEnabled")) {
                try { c.citizenSellToRegulatorEnabled = o.get("citizenSellToRegulatorEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("citizenSellToRegulatorMaxPerRound")) {
                c.citizenSellToRegulatorMaxPerRound = (int) optLong(o,
                        "citizenSellToRegulatorMaxPerRound", c.citizenSellToRegulatorMaxPerRound);
            }
            if (o.has("regulatorBuyWhitelist") && o.get("regulatorBuyWhitelist").isJsonArray()) {
                c.regulatorBuyWhitelist.clear();
                for (JsonElement e2 : o.getAsJsonArray("regulatorBuyWhitelist")) {
                    try {
                        c.regulatorBuyWhitelist.add(e2.getAsString().trim());
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
            c.regulatorGrainBuyFactor = (double) optDouble(o, "regulatorGrainBuyFactor",
                    c.regulatorGrainBuyFactor);
            c.regulatorGrainBuyBudget = optLong(o, "regulatorGrainBuyBudget", c.regulatorGrainBuyBudget);
            if (o.has("regulatorGrainUseCatalog")) {
                try { c.regulatorGrainUseCatalog =
                        o.get("regulatorGrainUseCatalog").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            c.regulatorGrainMaxTier = (int) optLong(o, "regulatorGrainMaxTier", c.regulatorGrainMaxTier);
            if (o.has("regulatorGrainBuyPlayerOnly")) {
            try {
                c.regulatorGrainBuyPlayerOnly = o.get("regulatorGrainBuyPlayerOnly").getAsBoolean();
            } catch (Throwable ignored) {
                // ignore
            }
        }
        c.regulatorGrainBuyCount = (int) optLong(o, "regulatorGrainBuyCount", c.regulatorGrainBuyCount);
            c.regulatorGrainBuyKinds = (int) optLong(o, "regulatorGrainBuyKinds", c.regulatorGrainBuyKinds);
            c.regulatorGrainSellFactor = (double) optDouble(o, "regulatorGrainSellFactor",
                    c.regulatorGrainSellFactor);
            if (o.has("regulatorGrainLowSellEnabled")) {
                try { c.regulatorGrainLowSellEnabled =
                        o.get("regulatorGrainLowSellEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("regulatorStockSellEnabled")) {
                try { c.regulatorStockSellEnabled =
                        o.get("regulatorStockSellEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            c.regulatorStockSellThreshold = (int) optLong(o, "regulatorStockSellThreshold",
                    c.regulatorStockSellThreshold);
            c.regulatorCashWarnHighBuy = optLong(o, "regulatorCashWarnHighBuy",
                    c.regulatorCashWarnHighBuy);
            c.regulatorCashWarnVoyage = optLong(o, "regulatorCashWarnVoyage",
                    c.regulatorCashWarnVoyage);
            c.voyageMaxConcurrent = (int) optLong(o, "voyageMaxConcurrent", c.voyageMaxConcurrent);
            c.voyageIssuerMaxSharePermille = (int) optLong(o, "voyageIssuerMaxSharePermille",
                    c.voyageIssuerMaxSharePermille);
            c.voyageNpcJoinAfterPermille = (int) optLong(o, "voyageNpcJoinAfterPermille",
                    c.voyageNpcJoinAfterPermille);
            c.voyageNpcJoinPlayerSharePermille = (int) optLong(o, "voyageNpcJoinPlayerSharePermille",
                    c.voyageNpcJoinPlayerSharePermille);
            if (o.has("traderFinancialOnly")) {
                try { c.traderFinancialOnly = o.get("traderFinancialOnly").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            c.voyageTariffPermille = (int) optLong(o, "voyageTariffPermille", c.voyageTariffPermille);
            c.voyageImportTariffPermille = (int) optLong(o, "voyageImportTariffPermille",
                    c.voyageImportTariffPermille);
            c.regulatorStockSellPriceFactor = (double) optDouble(o, "regulatorStockSellPriceFactor",
                    c.regulatorStockSellPriceFactor);
            c.regulatorStockSellMaxPerItem = (int) optLong(o, "regulatorStockSellMaxPerItem",
                    c.regulatorStockSellMaxPerItem);
            c.regulatorStockSellDailyKinds = (int) optLong(o, "regulatorStockSellDailyKinds",
                    c.regulatorStockSellDailyKinds);
            c.regulatorVoyageMaxSharePermille = (int) optLong(o, "regulatorVoyageMaxSharePermille",
                    c.regulatorVoyageMaxSharePermille);
            c.regulatorVoyageCargoMinMoney = optLong(o, "regulatorVoyageCargoMinMoney",
                    c.regulatorVoyageCargoMinMoney);
            if (o.has("regulatorVoyageInvestStopToo")) {
                try { c.regulatorVoyageInvestStopToo =
                        o.get("regulatorVoyageInvestStopToo").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("regulatorPriceControlEnabled")) {
                try { c.regulatorPriceControlEnabled =
                        o.get("regulatorPriceControlEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("regulatorGranaryEnabled")) {
                try { c.regulatorGranaryEnabled = o.get("regulatorGranaryEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("regulatorGranaryTargetDays")) {
                c.regulatorGranaryTargetDays = (int) optLong(o, "regulatorGranaryTargetDays",
                        c.regulatorGranaryTargetDays);
            }
            if (o.has("regulatorGranaryBuyBand")) {
                c.regulatorGranaryBuyBand = (double) optDouble(o, "regulatorGranaryBuyBand",
                        c.regulatorGranaryBuyBand);
            }
            if (o.has("regulatorGranaryBuyPriceFactor")) {
                c.regulatorGranaryBuyPriceFactor = (double) optDouble(o,
                        "regulatorGranaryBuyPriceFactor", c.regulatorGranaryBuyPriceFactor);
            }
            if (o.has("regulatorSellEnabled")) {
                try { c.regulatorSellEnabled = o.get("regulatorSellEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("regulatorSellBand")) {
                c.regulatorSellBand = (double) optDouble(o, "regulatorSellBand", c.regulatorSellBand);
            }
            if (o.has("regulatorSellPriceFactor")) {
                c.regulatorSellPriceFactor = (double) optDouble(o, "regulatorSellPriceFactor",
                        c.regulatorSellPriceFactor);
            }
            if (o.has("regulatorSellMaxPerItem")) {
                c.regulatorSellMaxPerItem = (int) optLong(o, "regulatorSellMaxPerItem",
                        c.regulatorSellMaxPerItem);
            }
            if (o.has("regulatorSellDailyKinds")) {
                c.regulatorSellDailyKinds = (int) optLong(o, "regulatorSellDailyKinds",
                        c.regulatorSellDailyKinds);
            }
            if (o.has("regulatorGranaryDailyBudget")) {
                c.regulatorGranaryDailyBudget = optLong(o, "regulatorGranaryDailyBudget",
                        c.regulatorGranaryDailyBudget);
            }
            if (o.has("citizenInternalTradeEnabled")) {
                try { c.citizenInternalTradeEnabled = o.get("citizenInternalTradeEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("citizenInternalTradeIntervalMinutes")) {
                c.citizenInternalTradeIntervalMinutes = (int) optLong(o,
                        "citizenInternalTradeIntervalMinutes", c.citizenInternalTradeIntervalMinutes);
            }
            if (o.has("citizenInternalTradeMaxTrades")) {
                c.citizenInternalTradeMaxTrades = (int) optLong(o,
                        "citizenInternalTradeMaxTrades", c.citizenInternalTradeMaxTrades);
            }
            if (o.has("citizenInternalTradeCountMin")) {
                c.citizenInternalTradeCountMin = (int) optLong(o,
                        "citizenInternalTradeCountMin", c.citizenInternalTradeCountMin);
            }
            if (o.has("citizenInternalTradeCountMax")) {
                c.citizenInternalTradeCountMax = (int) optLong(o,
                        "citizenInternalTradeCountMax", c.citizenInternalTradeCountMax);
            }
            if (o.has("citizenInternalTradeChance")) {
                c.citizenInternalTradeChance = (double) optDouble(o,
                        "citizenInternalTradeChance", c.citizenInternalTradeChance);
            }
            if (o.has("citizenProductionEnabled")) {
                try { c.citizenProductionEnabled = o.get("citizenProductionEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("citizenInvestEnabled")) {
                c.citizenInvestEnabled = o.get("citizenInvestEnabled").getAsBoolean();
            }
            c.citizenInvestReserveCash = optLong(o, "citizenInvestReserveCash", c.citizenInvestReserveCash);
            c.citizenInvestMaxSharePermille = (int) optLong(o, "citizenInvestMaxSharePermille", c.citizenInvestMaxSharePermille);
            c.citizenInvestMaxPerRound = (int) optLong(o, "citizenInvestMaxPerRound", c.citizenInvestMaxPerRound);
            c.citizenInvestMaxPremiumPermille = (int) optLong(o, "citizenInvestMaxPremiumPermille", c.citizenInvestMaxPremiumPermille);
            c.citizenStaleOrderMinutes = (int) optLong(o, "citizenStaleOrderMinutes", c.citizenStaleOrderMinutes);
            c.citizenStaleDemandSharePermille = (int) optLong(o, "citizenStaleDemandSharePermille", c.citizenStaleDemandSharePermille);
            if (o.has("citizenProducePoolEnabled")) {
                c.citizenProducePoolEnabled = o.get("citizenProducePoolEnabled").getAsBoolean();
            }
            c.citizenProduceFoodMaxTier = (int) optLong(o, "citizenProduceFoodMaxTier", c.citizenProduceFoodMaxTier);
            c.citizenProducePickKinds = (int) optLong(o, "citizenProducePickKinds", c.citizenProducePickKinds);
            c.citizenProducePoolMaxKinds = (int) optLong(o, "citizenProducePoolMaxKinds", c.citizenProducePoolMaxKinds);
            if (o.has("citizenBuyOnlyWhenProfit")) {
                c.citizenBuyOnlyWhenProfit = o.get("citizenBuyOnlyWhenProfit").getAsBoolean();
            }
            c.citizenBuyCashPermille = (int) optLong(o, "citizenBuyCashPermille", c.citizenBuyCashPermille);
            c.citizenFoodReserveDays = (int) optLong(o, "citizenFoodReserveDays", c.citizenFoodReserveDays);
            c.citizenFoodResumeDays = (int) optLong(o, "citizenFoodResumeDays", c.citizenFoodResumeDays);
            if (o.has("citizenReserveFoodWhenCrisis")) {
                c.citizenReserveFoodWhenCrisis = o.get("citizenReserveFoodWhenCrisis").getAsBoolean();
            }
            c.citizenFoodCrisisDays = (int) optLong(o, "citizenFoodCrisisDays", c.citizenFoodCrisisDays);
            if (o.has("citizenProductionPerDay")) {
                c.citizenProductionPerDay = (int) optLong(o, "citizenProductionPerDay",
                        c.citizenProductionPerDay);
            }
            if (o.has("citizenPassiveIncome")) {
                c.citizenPassiveIncome = optLong(o, "citizenPassiveIncome", c.citizenPassiveIncome);
            }
            if (o.has("citizenRetailRefreshMinutes")) {
                c.citizenRetailRefreshMinutes = (int) optLong(o,
                        "citizenRetailRefreshMinutes", c.citizenRetailRefreshMinutes);
            }
            if (o.has("citizenOfferTakeoverMaxPerRound")) {
                c.citizenOfferTakeoverMaxPerRound = optLong(o,
                        "citizenOfferTakeoverMaxPerRound", c.citizenOfferTakeoverMaxPerRound);
            }
            if (o.has("citizenOfferTakeoverTreasuryPermille")) {
                c.citizenOfferTakeoverTreasuryPermille = (int) optLong(o,
                        "citizenOfferTakeoverTreasuryPermille",
                        c.citizenOfferTakeoverTreasuryPermille);
            }
            if (o.has("citizenOfferTakeoverMinutes")) {
                c.citizenOfferTakeoverMinutes = (int) optLong(o,
                        "citizenOfferTakeoverMinutes", c.citizenOfferTakeoverMinutes);
            }
            if (o.has("botTakeoverCitizenOffers")) {
                try { c.botTakeoverCitizenOffers = o.get("botTakeoverCitizenOffers").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("botCrossTradeEnabled")) {
                try { c.botCrossTradeEnabled = o.get("botCrossTradeEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("citizenRationEnabled")) {
                try { c.citizenRationEnabled = o.get("citizenRationEnabled").getAsBoolean(); }
                catch (Throwable ignored) { }
            }
            if (o.has("citizenRationDiscount")) {
                c.citizenRationDiscount = (double) optDouble(o, "citizenRationDiscount", c.citizenRationDiscount);
            }
            if (o.has("citizenRationReserveDays")) {
                c.citizenRationReserveDays = (int) optLong(o, "citizenRationReserveDays", c.citizenRationReserveDays);
            }
            if (o.has("citizenRationSatisfactionCap")) {
                c.citizenRationSatisfactionCap = (double) optDouble(o, "citizenRationSatisfactionCap", c.citizenRationSatisfactionCap);
            }
            if (o.has("citizenBirthMinSatisfaction")) {
                c.citizenBirthMinSatisfaction = (double) optDouble(o, "citizenBirthMinSatisfaction", c.citizenBirthMinSatisfaction);
            }
            if (o.has("citizenName")) {
                try {
                    c.citizenName = o.get("citizenName").getAsString().trim();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (o.has("botOperateBots") && o.get("botOperateBots").isJsonArray()) {
                c.botOperateBots.clear();
                for (JsonElement e2 : o.getAsJsonArray("botOperateBots")) {
                    try {
                        c.botOperateBots.add(e2.getAsString().trim());
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
            c.citizenEatWheat = (int) optLong(o, "citizenEatWheat", c.citizenEatWheat);
            c.citizenEatCarrot = (int) optLong(o, "citizenEatCarrot", c.citizenEatCarrot);
            c.citizenEatBread = (int) optLong(o, "citizenEatBread", c.citizenEatBread);
            c.citizenPriceRecoverRate = (int) optLong(o, "citizenPriceRecoverRate", c.citizenPriceRecoverRate);
            c.citizenRetailMarkup = (int) optLong(o, "citizenRetailMarkup", c.citizenRetailMarkup);
            c.citizenRetailRatio = (int) optLong(o, "citizenRetailRatio", c.citizenRetailRatio);
            c.citizenRetailMax = (int) optLong(o, "citizenRetailMax", c.citizenRetailMax);
            c.citizenRetailKinds = (int) optLong(o, "citizenRetailKinds", c.citizenRetailKinds);
            c.citizenReceiptDiscount = (int) optLong(o, "citizenReceiptDiscount", c.citizenReceiptDiscount);
            c.citizenReceiptMaxPerCycle = (int) optLong(o, "citizenReceiptMaxPerCycle", c.citizenReceiptMaxPerCycle);
        c.citizenReceiptStockCap = (int) optLong(o, "citizenReceiptStockCap", c.citizenReceiptStockCap);
        c.citizenTotalStockCap = (int) optLong(o, "citizenTotalStockCap", c.citizenTotalStockCap);
            c.citizenRareChance = (int) optLong(o, "citizenRareChance", c.citizenRareChance);
            c.citizenRareMax = (int) optLong(o, "citizenRareMax", c.citizenRareMax);
            c.bigOrderAnnounceMin = optLong(o, "bigOrderAnnounceMin", c.bigOrderAnnounceMin);
            c.citizenCityDemandMinPrice = (int) optLong(o, "citizenCityDemandMinPrice", c.citizenCityDemandMinPrice);
            c.citizenCityDemandPoolRatio = (int) optLong(o, "citizenCityDemandPoolRatio", c.citizenCityDemandPoolRatio);
            c.citizenCityDemandPerHead = (int) optLong(o, "citizenCityDemandPerHead", c.citizenCityDemandPerHead);
            c.citizenCityDemandMax = (int) optLong(o, "citizenCityDemandMax", c.citizenCityDemandMax);
            c.citizenMinFoodScore = (int) optLong(o, "citizenMinFoodScore", c.citizenMinFoodScore);
            c.citizenMinOrderCount = (int) optLong(o, "citizenMinOrderCount", c.citizenMinOrderCount);
            c.citizenBigOrderChance = (int) optLong(o, "citizenBigOrderChance", c.citizenBigOrderChance);
            c.citizenBigOrderMinPeople = (int) optLong(o, "citizenBigOrderMinPeople", c.citizenBigOrderMinPeople);
            c.citizenBigOrderPerHead = (int) optLong(o, "citizenBigOrderPerHead", c.citizenBigOrderPerHead);
            c.citizenBigOrderMax = (int) optLong(o, "citizenBigOrderMax", c.citizenBigOrderMax);
            c.citizenBuyKinds = (int) optLong(o, "citizenBuyKinds", c.citizenBuyKinds);
            c.citizenBuyMaxEach = (int) optLong(o, "citizenBuyMaxEach", c.citizenBuyMaxEach);
            c.citizenFoodPerDay = (int) optLong(o, "citizenFoodPerDay", c.citizenFoodPerDay);
            c.citizenPricePerFood = (int) optLong(o, "citizenPricePerFood", c.citizenPricePerFood);
            c.citizenPerCapita = (int) optLong(o, "citizenPerCapita", c.citizenPerCapita);
            c.citizenCarryCapacity = (int) optLong(o, "citizenCarryCapacity", c.citizenCarryCapacity);
            c.citizenDailyGrowthPermille = (int) optLong(o, "citizenDailyGrowthPermille", c.citizenDailyGrowthPermille);
            c.citizenGrowthPermille = (int) optLong(o, "citizenGrowthPermille", c.citizenGrowthPermille);
            c.citizenSalary = optLong(o, "citizenSalary", c.citizenSalary);
            c.citizenBuyPerPlayer = (int) optLong(o, "citizenBuyPerPlayer", c.citizenBuyPerPlayer);
            c.citizenCycleGameDays = (int) optLong(o, "citizenCycleGameDays", c.citizenCycleGameDays);
            c.botIncomePerCycle = (int) optLong(o, "botIncomePerCycle", c.botIncomePerCycle);
            c.botConsumePerCycle = (int) optLong(o, "botConsumePerCycle", c.botConsumePerCycle);
            c.botCycleTicks = (int) optLong(o, "botCycleTicks", c.botCycleTicks);
            c.laborMaxBots = (int) optLong(o, "laborMaxBots", c.laborMaxBots);
            c.laborMaxPerPlayer = (int) optLong(o, "laborMaxPerPlayer", c.laborMaxPerPlayer);
            c.laborBasketIron = (int) optLong(o, "laborBasketIron", c.laborBasketIron);
            c.laborPrice = optLong(o, "laborPrice", c.laborPrice);
            c.laborMinutes = (int) optLong(o, "laborMinutes", c.laborMinutes);
            c.oldCoinTierMap = o.has("oldCoinTierMap")
                    ? o.get("oldCoinTierMap").getAsBoolean() : c.oldCoinTierMap;
            c.autoConvertCoins = o.has("autoConvertCoins")
                    ? o.get("autoConvertCoins").getAsBoolean() : c.autoConvertCoins;
            c.autoCashOut = optLong(o, "autoCashOut", c.autoCashOut);
            c.playerOfferMargin = optDouble(o, "playerOfferMargin", c.playerOfferMargin);
            c.playerOfferChance = optDouble(o, "playerOfferChance", c.playerOfferChance);
            c.playerOfferMaxCount = optInt(o, "playerOfferMaxCount", c.playerOfferMaxCount);
            c.activityFullAt = optInt(o, "activityFullAt", c.activityFullAt);
            c.activityFloor = optDouble(o, "activityFloor", c.activityFloor);
            c.maxOffers = optInt(o, "maxOffers", c.maxOffers);
            c.maxOrders = optInt(o, "maxOrders", c.maxOrders);
        c.klineTradesPerBar = (int) optLong(o, "klineTradesPerBar", c.klineTradesPerBar);
        if (o.has("klineFillEmptyBars")) {
            try {
                c.klineFillEmptyBars = o.get("klineFillEmptyBars").getAsBoolean();
            } catch (Throwable ignored) {
                // ignore
            }
        }
            c.offerTtlMs = optLong(o, "offerTtlMs", c.offerTtlMs);
            c.minMoney = optLong(o, "minMoney", c.minMoney);
            c.minStock = optInt(o, "minStock", c.minStock);
            c.sellMin = optDouble(o, "sellMin", c.sellMin);
            c.sellMax = optDouble(o, "sellMax", c.sellMax);
            c.buyMin = optDouble(o, "buyMin", c.buyMin);
            c.buyMax = optDouble(o, "buyMax", c.buyMax);
            c.sellCountMin = optInt(o, "sellCountMin", c.sellCountMin);
            c.sellCountMax = optInt(o, "sellCountMax", c.sellCountMax);
            c.buyCountMin = optInt(o, "buyCountMin", c.buyCountMin);
            c.buyCountMax = optInt(o, "buyCountMax", c.buyCountMax);
            if (o.has("onlyItems") && o.get("onlyItems").isJsonArray()) {
                List<String> only = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("onlyItems")) {
                    try {
                        only.add(e.getAsString().trim());
                    } catch (Throwable ignored) {
                        // 跳过
                    }
                }
                c.onlyItems.clear();
                c.onlyItems.addAll(only);
            }
            if (o.has("bots") && o.get("bots").isJsonArray()) {
                JsonArray arr = o.getAsJsonArray("bots");
                List<Bot> list = new ArrayList<>();
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject bo = e.getAsJsonObject();
                    Bot bt = new Bot();
                    bt.name = bo.has("name") ? bo.get("name").getAsString() : "机器人";
                    bt.money = optLong(bo, "money", 200_000L);
                    bt.stock = optInt(bo, "stock", 512);
                    bt.idleChance = optDouble(bo, "idleChance", 0.35);
                    bt.role = bo.has("role") ? bo.get("role").getAsString().trim() : "neutral";
                    if (bo.has("longBuy") && bo.get("longBuy").isJsonArray()) {
                        for (JsonElement li : bo.getAsJsonArray("longBuy")) {
                            try {
                                bt.longBuy.add(li.getAsString().trim());
                            } catch (Throwable ignored) {
                                // 跳过
                            }
                        }
                    }
                    bt.longBuyCapacity = optInt(bo, "longBuyCapacity", bt.longBuyCapacity);
                    bt.longBuyFloor = optDouble(bo, "longBuyFloor", bt.longBuyFloor);
                    bt.longBuyCount = optInt(bo, "longBuyCount", bt.longBuyCount);
                    bt.longBuyChance = optDouble(bo, "longBuyChance", bt.longBuyChance);
                    bt.targetBand = optDouble(bo, "targetBand", bt.targetBand);
                    bt.interveneCount = optInt(bo, "interveneCount", bt.interveneCount);
                    bt.interveneChance = optDouble(bo, "interveneChance", bt.interveneChance);
                    list.add(bt);
                }
                if (!list.isEmpty()) {
                    c.bots.clear();
                    c.bots.addAll(list);
                }
            }
            sanitize(c);
            INSTANCE = c;
            // 重读 bots.json 后，把数据包覆盖重新套回去（否则 /billbot reload 会冲掉）
            try {
                var keep = LAST_OVERRIDES;
                if (keep != null && !keep.isEmpty()) {
                    applyOverrides(keep);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 机器人配置解析失败: " + t);
        }
    }

    /** 读 config/kjbb/bots.json（启动与 /billbot reload 时） */
    public static synchronized String loadFile() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            Path p = dir.resolve("bots.json");
            if (!Files.exists(p)) {
                writeExample(p);
                return "已生成示例 config/kjbb/bots.json";
            }
            String s = Files.readString(p, StandardCharsets.UTF_8);
            JsonElement el = new Gson().fromJson(s, JsonElement.class);
            merge(el);
            return "已读取 config/kjbb/bots.json";
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    private static void writeExample(Path p) {
        String s = """
                {
                  "_说明": "机器人参数：改完执行 /billbot reload；也可放数据包 data/<命名空间>/kjbb/bots/*.json",
                  "intervalTicks": 100,
                  "saveIntervalTicks": 100,
                  "perfEnabled": true, "perfBudgetMs": 5, "perfLogSeconds": 120,
                  "taskBudgetMs": 8,
                  "voyageAutoIssueDays": 20,
                  "voyageEvalRefQty": 512, "voyageEvalShipRefQty": 2048,
                  "voyageEvalShipCargo": true,
                  "romaProductionEnabled": true,
                  "romaProdFoodPerDay": 64, "romaProdMetalPerDay": 32,
                  "romaProdFoodValue": 1, "romaProdMetalValue": 3,
                  "romaProdFloorPerDay": 4, "romaProdPerPointPermille": 250,
                  "romaProdMechPermille": 400, "romaProdOilPermille": 200,
                  "romaProdTextilePermille": 100, "romaProdStonePermille": 100,
                  "romaProdMechItem": "create:cogwheel",
                  "romaProdOilItem": "kaleidoscope_cookery:oil",
                  "romaProdWineItem": "kaleidoscope_tavern:wine",
                  "romaProdTextileItem": "minecraft:white_wool",
                  "romaProdStoneItem": "minecraft:calcite",
                  "romaStockCapPerItem": 8192,
                  "romaBudgetRef": 400000, "romaBudgetFloorPermille": 600,
                  "romaBudgetCeilPermille": 1200,
                  "romaMulTextilePermille": 600, "romaMulStonePermille": 550,
                  "romaMulMetalPermille": 1350, "romaMulOilPermille": 620,
                  "romaMulWinePermille": 650,
                  "romaMulFoodPermille": 1550, "romaMulRawPermille": 1100,
                  "romaMulMechPermille": 720, "romaMulOtherPermille": 1000,
                  "romaRefFoodQty": 2048, "romaRefTextileQty": 1024,
                  "romaRefStoneQty": 1024, "romaRefMetalQty": 2048,
                  "romaRefOilQty": 1024, "romaRefWineQty": 1024, "romaRefMechQty": 1024,
                  "voyageRoundTrip": true, "voyageReturnLegs": 2,
                  "voyageReturnSinkPermille": 500, "voyageImportDiscount": 0.90,
                  "voyageImportKeepPermille": 0,
                  "voyageRomaPricePermille": 1150, "voyageCharterFeePermille": 80, "voyageCharterMaxValue": 50000, "voyageCharterMaxQtyPerItem": 1024,,
                  "voyageEvents": {
                    "sinkPct": 1, "sinkExtra": 8, "condBad": 30,
                    "tGood": 35, "tCalm": 65, "tBad": 80, "tStorm": 91, "tPirate": 97,
                    "dGood": 3, "dBad": -6, "dStorm": -12, "dPirate": -18, "dReef": -25,
                    "dmgBad": 5, "dmgStorm": 15, "dmgPirate": 12, "dmgReef": 25,
                    "lossStorm": 5, "lossPirate": 10, "lossReef": 15, "multCap": 130
                  },
                  "klinePeriodMs": 12000000,
                  "priceAnchorPerMin": 0.0,
                  "stockInfluence": 0.0,
                  "priceFloor": 1,
                  "botOperateBots": ["\u5546\u884c\u00b7\u4e59"],
                  "citizenEatWheat": 1,
                  "citizenEatCarrot": 1,
                  "citizenEatBread": 1,
                  "citizenPriceRecoverRate": 15,
                  "citizenRetailMarkup": 160,
                  "citizenRetailRatio": 30,
                  "citizenRetailMax": 512,
                  "citizenRetailKinds": 6,
                  "citizenReceiptDiscount": 85,
                  "citizenReceiptMaxPerCycle": 2,
        "citizenReceiptStockCap": 1024,
        "citizenTotalStockCap": 50000,
                  "citizenRareChance": 5,
                  "citizenRareMax": 64,
                  "bigOrderAnnounceMin": 50000,
                  "citizenCityDemandMinPrice": 1,
                  "citizenCityDemandPoolRatio": 15,
                  "citizenCityDemandPerHead": 16,
                  "citizenCityDemandMax": 4096,
                  "citizenMinFoodScore": 15,
                  "citizenMinOrderCount": 32,
                  "citizenBigOrderChance": 50,
                  "citizenBigOrderMinPeople": 30,
                  "citizenBigOrderPerHead": 40,
                  "citizenBigOrderMax": 4096,
                  "citizenBuyKinds": 6,
                  "citizenBuyMaxEach": 256,
                  "citizenFoodPerDay": 4,
                  "citizenPricePerFood": 34,
                  "citizenPerCapita": 5000,
                  "citizenCarryCapacity": 200,
                  "citizenDailyGrowthPermille": 2,
                  "citizenGrowthPermille": 5,
                  "citizenSalary": 3000,
                  "citizenBuyPerPlayer": 64,
                  "citizenCycleGameDays": 30,
                  "botIncomePerCycle": 300,
                  "botConsumePerCycle": 32,
                  "botCycleTicks": 6000,
                  "laborMaxBots": 16,
                  "laborMaxPerPlayer": 3,
                  "laborBasketIron": 0,
                  "laborPrice": 2000,
                  "laborMinutes": 60,
                  "oldCoinTierMap": false,
                  "coinOnlyItems": [],
                  "autoConvertCoins": true,
                  "autoCashOut": 8192,
                  "playerOfferMargin": 0.05,
                  "playerOfferChance": 0.6,
                  "playerOfferMaxCount": 256,
                  "activityFullAt": 40,
                  "activityFloor": 0.15,
                  "maxOffers": 12,
                  "maxOrders": 8,
                  "offerTtlMs": 600000,
                  "minMoney": 20000,
                  "minStock": 32,
                  "sellMin": 0.98,
                  "sellMax": 1.12,
                  "buyMin": 0.86,
                  "buyMax": 0.96,
                  "sellCountMin": 8,
                  "sellCountMax": 32,
                  "buyCountMin": 8,
                  "buyCountMax": 24,
                  "onlyItems": [],
                  "bots": [
                    { "name": "调节者", "role": "regulator", "targetBand": 0.15, "interveneCount": 128 },
                    { "name": "经济部", "money": 500000, "stock": 1024, "idleChance": 0.30, "role": "stabilizer",
                      "longBuy": ["minecraft:wheat", "minecraft:carrot", "minecraft:potato",
                                  "minecraft:beetroot", "minecraft:sugar_cane", "minecraft:pumpkin"],
                      "longBuyCapacity": 4000, "longBuyFloor": 0.25, "longBuyCount": 128 }, 
                    { "name": "唐兔国国民", "money": 200000, "stock": 512,  "idleChance": 0.40, "role": "neutral" },
                    { "name": "大A膏手", "money": 80000,  "stock": 256,  "idleChance": 0.50, "role": "noise" }
                  ]
                }
                """;
        try {
            Files.writeString(p, s, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static BotConfig copy(BotConfig src) {
        BotConfig c = new BotConfig();
        c.timeSource = src.timeSource;
        c.saveIntervalTicks = src.saveIntervalTicks;
        c.perfEnabled = src.perfEnabled;
        c.perfBudgetMs = src.perfBudgetMs;
        c.taskBudgetMs = src.taskBudgetMs;
        c.perfLogSeconds = src.perfLogSeconds;
        c.intervalTicks = src.intervalTicks;
        c.voyageAutoIssueDays = src.voyageAutoIssueDays;
        c.voyageEvalRefQty = src.voyageEvalRefQty;
        c.voyageEvalShipRefQty = src.voyageEvalShipRefQty;
        c.voyageEvalShipCargo = src.voyageEvalShipCargo;
        c.romaProductionEnabled = src.romaProductionEnabled;
        c.romaProdFoodPerDay = src.romaProdFoodPerDay;
        c.romaProdMetalPerDay = src.romaProdMetalPerDay;
        c.romaProdFoodValue = src.romaProdFoodValue;
        c.romaProdMetalValue = src.romaProdMetalValue;
        c.romaProdFloorPerDay = src.romaProdFloorPerDay;
        c.romaProdPerPointPermille = src.romaProdPerPointPermille;
        c.romaProdMechPermille = src.romaProdMechPermille;
        c.romaProdOilPermille = src.romaProdOilPermille;
        c.romaProdTextilePermille = src.romaProdTextilePermille;
        c.romaProdStonePermille = src.romaProdStonePermille;
        c.romaProdTextileItem = src.romaProdTextileItem;
        c.romaProdStoneItem = src.romaProdStoneItem;
        c.romaProdOilPermille = src.romaProdOilPermille;
        c.romaProdMechItem = src.romaProdMechItem;
        c.romaProdOilItem = src.romaProdOilItem;
        c.romaProdWineItem = src.romaProdWineItem;
        c.romaStockCapPerItem = src.romaStockCapPerItem;
        c.romaBudgetRef = src.romaBudgetRef;
        c.romaBudgetFloorPermille = src.romaBudgetFloorPermille;
        c.romaBudgetCeilPermille = src.romaBudgetCeilPermille;
        c.romaMulTextilePermille = src.romaMulTextilePermille;
        c.romaMulStonePermille = src.romaMulStonePermille;
        c.romaMulMetalPermille = src.romaMulMetalPermille;
        c.romaMulOilPermille = src.romaMulOilPermille;
        c.romaMulWinePermille = src.romaMulWinePermille;
        c.romaMulFoodPermille = src.romaMulFoodPermille;
        c.romaMulRawPermille = src.romaMulRawPermille;
        c.romaMulMechPermille = src.romaMulMechPermille;
        c.romaMulOtherPermille = src.romaMulOtherPermille;
        c.romaRefTextileQty = src.romaRefTextileQty;
        c.romaRefStoneQty = src.romaRefStoneQty;
        c.romaRefMetalQty = src.romaRefMetalQty;
        c.romaRefOilQty = src.romaRefOilQty;
        c.romaRefWineQty = src.romaRefWineQty;
        c.romaRefFoodQty = src.romaRefFoodQty;
        c.romaRefMechQty = src.romaRefMechQty;
        c.voyageRoundTrip = src.voyageRoundTrip;
        c.voyageReturnLegs = src.voyageReturnLegs;
        c.voyageReturnSinkPermille = src.voyageReturnSinkPermille;
        c.voyageImportDiscount = src.voyageImportDiscount;
        c.voyageImportKeepPermille = src.voyageImportKeepPermille;
        c.voyageRomaPricePermille = src.voyageRomaPricePermille;
        c.voyageCharterFeePermille = src.voyageCharterFeePermille;
        c.voyageCharterMaxValue = src.voyageCharterMaxValue;
        c.voyageCharterMaxQtyPerItem = src.voyageCharterMaxQtyPerItem;
        c.voyageEvents = src.voyageEvents.copy();
        c.klinePeriodMs = src.klinePeriodMs;
        c.priceAnchorPerMin = src.priceAnchorPerMin;
        c.stockInfluence = src.stockInfluence;
        c.priceFloor = src.priceFloor;
        c.coinOnlyItems.addAll(src.coinOnlyItems);
        c.botOperateBots.addAll(src.botOperateBots);
        c.citizenEatWheat = src.citizenEatWheat;
        c.citizenEatCarrot = src.citizenEatCarrot;
        c.citizenEatBread = src.citizenEatBread;
        c.citizenPriceRecoverRate = src.citizenPriceRecoverRate;
        c.citizenInvestEnabled = src.citizenInvestEnabled;
        c.citizenInvestReserveCash = src.citizenInvestReserveCash;
        c.citizenInvestMaxSharePermille = src.citizenInvestMaxSharePermille;
        c.citizenInvestMaxPerRound = src.citizenInvestMaxPerRound;
        c.citizenInvestMaxPremiumPermille = src.citizenInvestMaxPremiumPermille;
        c.citizenStaleOrderMinutes = src.citizenStaleOrderMinutes;
        c.citizenStaleDemandSharePermille = src.citizenStaleDemandSharePermille;
        c.citizenProducePoolEnabled = src.citizenProducePoolEnabled;
        c.citizenProduceFoodMaxTier = src.citizenProduceFoodMaxTier;
        c.citizenProducePickKinds = src.citizenProducePickKinds;
        c.citizenProducePoolMaxKinds = src.citizenProducePoolMaxKinds;
        c.citizenBuyOnlyWhenProfit = src.citizenBuyOnlyWhenProfit;
        c.citizenBuyCashPermille = src.citizenBuyCashPermille;
        c.citizenFoodReserveDays = src.citizenFoodReserveDays;
        c.citizenFoodResumeDays = src.citizenFoodResumeDays;
        c.citizenReserveFoodWhenCrisis = src.citizenReserveFoodWhenCrisis;
        c.citizenFoodCrisisDays = src.citizenFoodCrisisDays;
        c.citizenRetailMarkup = src.citizenRetailMarkup;
        c.citizenRetailRatio = src.citizenRetailRatio;
        c.citizenRetailMax = src.citizenRetailMax;
        c.citizenRetailKinds = src.citizenRetailKinds;
        c.citizenReceiptDiscount = src.citizenReceiptDiscount;
        c.citizenReceiptMaxPerCycle = src.citizenReceiptMaxPerCycle;
        c.citizenReceiptStockCap = src.citizenReceiptStockCap;
        c.citizenTotalStockCap = src.citizenTotalStockCap;
        c.citizenRareChance = src.citizenRareChance;
        c.citizenRareMax = src.citizenRareMax;
        c.bigOrderAnnounceMin = src.bigOrderAnnounceMin;
        c.citizenCityDemandMinPrice = src.citizenCityDemandMinPrice;
        c.citizenCityDemandPoolRatio = src.citizenCityDemandPoolRatio;
        c.citizenCityDemandPerHead = src.citizenCityDemandPerHead;
        c.citizenCityDemandMax = src.citizenCityDemandMax;
        c.citizenMinFoodScore = src.citizenMinFoodScore;
        c.citizenMinOrderCount = src.citizenMinOrderCount;
        c.citizenBigOrderChance = src.citizenBigOrderChance;
        c.citizenBigOrderMinPeople = src.citizenBigOrderMinPeople;
        c.citizenBigOrderPerHead = src.citizenBigOrderPerHead;
        c.citizenBigOrderMax = src.citizenBigOrderMax;
        c.citizenBuyKinds = src.citizenBuyKinds;
        c.citizenBuyMaxEach = src.citizenBuyMaxEach;
        c.citizenFoodPerDay = src.citizenFoodPerDay;
        c.citizenPricePerFood = src.citizenPricePerFood;
        c.citizenPerCapita = src.citizenPerCapita;
        c.citizenCarryCapacity = src.citizenCarryCapacity;
        c.citizenDailyGrowthPermille = src.citizenDailyGrowthPermille;
        c.citizenGrowthPermille = src.citizenGrowthPermille;
        c.citizenSalary = src.citizenSalary;
        c.citizenBuyPerPlayer = src.citizenBuyPerPlayer;
        c.citizenCycleGameDays = src.citizenCycleGameDays;
        c.botIncomePerCycle = src.botIncomePerCycle;
        c.botConsumePerCycle = src.botConsumePerCycle;
        c.botCycleTicks = src.botCycleTicks;
        c.laborMaxBots = src.laborMaxBots;
        c.laborMaxPerPlayer = src.laborMaxPerPlayer;
        c.laborBasketIron = src.laborBasketIron;
        c.laborPrice = src.laborPrice;
        c.laborMinutes = src.laborMinutes;
        c.oldCoinTierMap = src.oldCoinTierMap;
        c.autoConvertCoins = src.autoConvertCoins;
        c.autoCashOut = src.autoCashOut;
        c.playerOfferMargin = src.playerOfferMargin;
        c.playerOfferChance = src.playerOfferChance;
        c.playerOfferMaxCount = src.playerOfferMaxCount;
        c.activityFullAt = src.activityFullAt;
        c.activityFloor = src.activityFloor;
        c.maxOffers = src.maxOffers;
        c.maxOrders = src.maxOrders;
        c.offerTtlMs = src.offerTtlMs;
        // —— 经济部 / 国库（原来漏拷，reload 时会退回默认值）——
        c.regulatorTaxPermille = src.regulatorTaxPermille;
        c.regulatorTaxBots = src.regulatorTaxBots;
        c.regulatorGrainBuyFactor = src.regulatorGrainBuyFactor;
        c.regulatorGrainBuyBudget = src.regulatorGrainBuyBudget;
        c.regulatorGrainUseCatalog = src.regulatorGrainUseCatalog;
        c.regulatorGrainMaxTier = src.regulatorGrainMaxTier;
        c.regulatorGrainBuyCount = src.regulatorGrainBuyCount;
        c.regulatorGrainBuyKinds = src.regulatorGrainBuyKinds;
        c.regulatorGrainSellFactor = src.regulatorGrainSellFactor;
        c.regulatorGrainLowSellEnabled = src.regulatorGrainLowSellEnabled;
        c.regulatorStockSellEnabled = src.regulatorStockSellEnabled;
        c.regulatorStockSellThreshold = src.regulatorStockSellThreshold;
        c.regulatorCashWarnHighBuy = src.regulatorCashWarnHighBuy;
        c.regulatorCashWarnVoyage = src.regulatorCashWarnVoyage;
        c.voyageMaxConcurrent = src.voyageMaxConcurrent;
        c.voyageIssuerMaxSharePermille = src.voyageIssuerMaxSharePermille;
        c.voyageNpcJoinAfterPermille = src.voyageNpcJoinAfterPermille;
        c.voyageNpcJoinPlayerSharePermille = src.voyageNpcJoinPlayerSharePermille;
        c.traderFinancialOnly = src.traderFinancialOnly;
        c.voyageTariffPermille = src.voyageTariffPermille;
        c.voyageImportTariffPermille = src.voyageImportTariffPermille;
        c.regulatorStockSellPriceFactor = src.regulatorStockSellPriceFactor;
        c.regulatorStockSellMaxPerItem = src.regulatorStockSellMaxPerItem;
        c.regulatorStockSellDailyKinds = src.regulatorStockSellDailyKinds;
        c.regulatorVoyageMaxSharePermille = src.regulatorVoyageMaxSharePermille;
        c.regulatorVoyageCargoMinMoney = src.regulatorVoyageCargoMinMoney;
        c.regulatorVoyageInvestStopToo = src.regulatorVoyageInvestStopToo;
        c.regulatorPriceControlEnabled = src.regulatorPriceControlEnabled;
        c.regulatorGranaryEnabled = src.regulatorGranaryEnabled;
        c.regulatorGranaryTargetDays = src.regulatorGranaryTargetDays;
        c.regulatorGranaryBuyBand = src.regulatorGranaryBuyBand;
        c.regulatorGranaryBuyPriceFactor = src.regulatorGranaryBuyPriceFactor;
        c.regulatorGranaryDailyBudget = src.regulatorGranaryDailyBudget;
        c.regulatorSellEnabled = src.regulatorSellEnabled;
        c.regulatorSellBand = src.regulatorSellBand;
        c.regulatorSellPriceFactor = src.regulatorSellPriceFactor;
        c.regulatorSellMaxPerItem = src.regulatorSellMaxPerItem;
        c.regulatorSellDailyKinds = src.regulatorSellDailyKinds;
        c.minMoney = src.minMoney;
        c.minStock = src.minStock;
        c.sellMin = src.sellMin;
        c.sellMax = src.sellMax;
        c.buyMin = src.buyMin;
        c.buyMax = src.buyMax;
        c.sellCountMin = src.sellCountMin;
        c.sellCountMax = src.sellCountMax;
        c.buyCountMin = src.buyCountMin;
        c.buyCountMax = src.buyCountMax;
        c.onlyItems.addAll(src.onlyItems);
        for (Bot b : src.bots) {
            Bot n = new Bot();
            n.name = b.name;
            n.money = b.money;
            n.stock = b.stock;
            n.idleChance = b.idleChance;
            n.role = b.role;
            n.longBuy = new ArrayList<>(b.longBuy);
            n.longBuyCapacity = b.longBuyCapacity;
            n.longBuyFloor = b.longBuyFloor;
            n.longBuyCount = b.longBuyCount;
            n.longBuyChance = b.longBuyChance;
            n.targetBand = b.targetBand;
            n.interveneCount = b.interveneCount;
            n.interveneChance = b.interveneChance;
            c.bots.add(n);
        }
        return c;
    }

    private static void sanitize(BotConfig c) {
        c.intervalTicks = Math.max(20, c.intervalTicks);
        c.voyageAutoIssueDays = Math.max(0, c.voyageAutoIssueDays);
        c.voyageEvalRefQty = Math.max(32, c.voyageEvalRefQty);
        if (c.priceAnchorPerMin < 0.0) {
            c.priceAnchorPerMin = 0.0;
        }
        if (c.priceAnchorPerMin > 1.0) {
            c.priceAnchorPerMin = 1.0;
        }
        if (c.stockInfluence < 0.0) {
            c.stockInfluence = 0.0;
        }
        if (c.stockInfluence > 1.0) {
            c.stockInfluence = 1.0;
        }
        if (c.priceFloor < 1L) {
            c.priceFloor = 1L;
        }
        if (c.klinePeriodMs < 60_000L) {
            c.klinePeriodMs = 60_000L;
        }
        if (c.klinePeriodMs > 12_000_000L) {
            c.klinePeriodMs = 12_000_000L;
        }
        c.maxOffers = Math.max(0, c.maxOffers);
        c.maxOrders = Math.max(0, c.maxOrders);
        c.offerTtlMs = Math.max(30_000L, c.offerTtlMs);
        c.minMoney = Math.max(0L, c.minMoney);
        c.minStock = Math.max(0, c.minStock);
        if (c.sellMin < 0.1) {
            c.sellMin = 0.1;
        }
        if (c.sellMax < c.sellMin) {
            double t = c.sellMax;
            c.sellMax = c.sellMin;
            c.sellMin = t;
        }
        if (c.buyMin < 0.1) {
            c.buyMin = 0.1;
        }
        if (c.buyMax < c.buyMin) {
            double t = c.buyMax;
            c.buyMax = c.buyMin;
            c.buyMin = t;
        }
        if (c.sellCountMin < 1) {
            c.sellCountMin = 1;
        }
        if (c.sellCountMax < c.sellCountMin) {
            c.sellCountMax = c.sellCountMin;
        }
        if (c.buyCountMin < 1) {
            c.buyCountMin = 1;
        }
        if (c.buyCountMax < c.buyCountMin) {
            c.buyCountMax = c.buyCountMin;
        }
    }

    public static String describe() {
        BotConfig c = get();
        StringBuilder sb = new StringBuilder("§e机器人参数：\n");
        sb.append("§7  尝试间隔 ").append(c.intervalTicks).append(" tick")
                .append("　挂卖上限 ").append(c.maxOffers)
                .append("　买单上限 ").append(c.maxOrders).append('\n');
        sb.append("§7  卖价 ").append(c.sellMin).append("~").append(c.sellMax)
                .append("　买价 ").append(c.buyMin).append("~").append(c.buyMax).append('\n');
        sb.append("§7  数量 卖 ").append(c.sellCountMin).append("~").append(c.sellCountMax)
                .append("　买 ").append(c.buyCountMin).append("~").append(c.buyCountMax).append('\n');
        sb.append("§7  收玩家挂单：低于市价 ").append(Math.round(c.playerOfferMargin * 100))
                .append("% 才收，概率 ").append(c.playerOfferChance)
                .append("，单笔上限 ").append(c.playerOfferMaxCount).append(" 件\n");
        sb.append("§7  活跃度：玩家 ").append(c.activityFullAt)
                .append(" 笔/小时封顶退场，最低 ").append(c.activityFloor).append('\n');
        sb.append("§7  价格锚定/分钟 ").append(c.priceAnchorPerMin)
                .append("　库存影响 ").append(c.stockInfluence)
                .append("　价格下限 ").append(c.priceFloor).append('\n');
        sb.append("§7  K线周期 ").append(c.klinePeriodMs / 60_000).append(" 分钟\n");
        sb.append("§7  限定物品 ").append(c.onlyItems.isEmpty() ? "（全部）" : c.onlyItems.toString())
                .append('\n');
        sb.append("§7  挂单存活 ").append(c.offerTtlMs / 1000).append(" 秒")
                .append("　钱包下限 ").append(c.minMoney)
                .append("　库存下限 ").append(c.minStock).append('\n');
        for (Bot b : c.bots) {
            sb.append("§f  ").append(b.name).append(" §7本金 ").append(b.money)
                    .append("　库存 ").append(b.stock)
                    .append("　摸鱼率 ").append(b.idleChance)
                    .append("　角色 ").append(b.role)
                    .append(b.longBuy.isEmpty() ? "" : ("　长期收购 " + b.longBuy.size() + " 种"))
                    .append("regulator".equals(b.role)
                            ? ("　调节带 ±" + Math.round(b.targetBand * 100) + "%　单次 "
                                    + b.interveneCount + " 件") : "")
                    .append('\n');
        }
        return sb.toString();
    }

    private static int optInt(JsonObject o, String k, int def) {
        try {
            return o.has(k) ? o.get(k).getAsInt() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static long optLong(JsonObject o, String k, long def) {
        try {
            return o.has(k) ? o.get(k).getAsLong() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static double optDouble(JsonObject o, String k, double def) {
        try {
            return o.has(k) ? o.get(k).getAsDouble() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 数据包参数覆盖：按键名（= 字段名）反射写入当前配置实例。
     * 支持 int / long / double / boolean / String。
     */
    public static synchronized void applyOverrides(java.util.Map<String, com.google.gson.JsonElement> data) {
        if (data != null && !data.isEmpty()) {
            LAST_OVERRIDES = new java.util.LinkedHashMap<>(data);
        }
        var cfg = get();
        int n = 0;
        for (var e : data.entrySet()) {
            String key = e.getKey();
            com.google.gson.JsonElement je = e.getValue();
            if (je == null || je.isJsonNull()) {
                continue;
            }
            try {
                var f = BotConfig.class.getField(key);
                Class<?> ty = f.getType();
                if (ty == int.class) {
                    f.setInt(cfg, je.getAsInt());
                } else if (ty == long.class) {
                    f.setLong(cfg, je.getAsLong());
                } else if (ty == double.class) {
                    f.setDouble(cfg, je.getAsDouble());
                } else if (ty == boolean.class) {
                    f.setBoolean(cfg, je.getAsBoolean());
                } else if (ty == String.class) {
                    f.set(cfg, je.getAsString());
                } else {
                    continue;
                }
                n++;
            } catch (Throwable ignored) {
                // 字段不存在或类型不符 → 跳过
            }
        }
        System.out.println("[TangtuEconomical] \u6570\u636e\u5305\u53c2\u6570\u8986\u76d6 " + n + " \u9879");
    }
}
