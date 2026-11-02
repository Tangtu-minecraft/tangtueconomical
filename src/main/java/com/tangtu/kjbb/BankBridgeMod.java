package com.tangtu.kjbb;

import com.tangtu.kjbb.item.KjbbItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * TangtuEconomical —— 银行/交易所/票据 桥接 mod
 */
@Mod("kjbankbridge")
public class BankBridgeMod {

    public BankBridgeMod(IEventBus modEventBus) {
        KjbbItems.register(modEventBus);
        System.out.println("[TangtuEconomical] " + BotConfig.loadFile());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.AddReloadListenerEvent e) ->
                        e.addListener(new PriceReloadListener()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.AddReloadListenerEvent e) ->
                        e.addListener(new DemandReloadListener()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.AddReloadListenerEvent e) ->
                        e.addListener(new TuningReloadListener()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.AddReloadListenerEvent e) ->
                        e.addListener(new ElasticityReloadListener()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.AddReloadListenerEvent e) ->
                        e.addListener(new VoyageCargo()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.AddReloadListenerEvent e) ->
                        e.addListener(new RomaGoods()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.AddReloadListenerEvent e) ->
                        e.addListener(new VoyageRules()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.AddReloadListenerEvent e) ->
                        e.addListener(new TradeBlacklist()));

        // 关服/停服：把所有脏数据强制落盘，避免最后 5 秒的改动丢失
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> {
                    try {
                        com.tangtu.kjbb.DirtySave.flushEverything();
                        com.tangtu.kjbb.DebugLog.log("关服落盘完成：" + com.tangtu.kjbb.DirtySave.stats());
                    } catch (Throwable t) {
                        System.out.println("[TangtuEconomical] 关服落盘失败: " + t);
                    }
                });
        // 定期把性能摘要写进日志
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> {
                    try {
                        int sec = com.tangtu.kjbb.BotConfig.get().perfLogSeconds;
                        if (sec <= 0) {
                            return;
                        }
                        if (e.getServer().getTickCount() % (sec * 20) != 0) {
                            return;
                        }
                        com.tangtu.kjbb.DebugLog.log(com.tangtu.kjbb.Perf.summary());
                    } catch (Throwable ignored) {
                        // ignore
                    }
                });
        // 关键配置快照（写文件，便于核对数据包是否生效）
        try {
            var c = com.tangtu.kjbb.BotConfig.get();
            com.tangtu.kjbb.DebugLog.log("=== 启动配置快照 ===");
            com.tangtu.kjbb.DebugLog.log("citizen\u56fd\u6c11=" + com.tangtu.kjbb.MarketBots.citizenName()
                    + "  operaateBots=" + c.botOperateBots);
            com.tangtu.kjbb.DebugLog.log("botCrossTradeEnabled=" + c.botCrossTradeEnabled
                    + "  botTakeoverCitizenOffers=" + c.botTakeoverCitizenOffers
                    + "  takeoverMinutes=" + c.citizenOfferTakeoverMinutes);
            com.tangtu.kjbb.DebugLog.log("citizenSalary=" + c.citizenSalary
                    + "  citizenPassiveIncome=" + c.citizenPassiveIncome
                    + "  productionPerDay=" + c.citizenProductionPerDay);
            com.tangtu.kjbb.DebugLog.log("retailKinds=" + c.citizenRetailKinds
                    + "  retailRefreshMin=" + c.citizenRetailRefreshMinutes
                    + "  internalTradeMin=" + c.citizenInternalTradeIntervalMinutes);
            com.tangtu.kjbb.DebugLog.log("granaryEnabled=" + c.regulatorGranaryEnabled
                    + "  granaryBudget=" + c.regulatorGranaryDailyBudget
                    + "  whitelist=" + c.regulatorBuyWhitelist.size());
        } catch (Throwable ignored) {
            // ignore
        }

        // 经济报告：首次运行记录价格基线
        try {
            com.tangtu.kjbb.EconReport.ensureBaseline();
        } catch (Throwable ignored) {
            // ignore
        }

        // 玩家进服时推送服务器日期
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) -> {
                    try {
                        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
                            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                                    new com.tangtu.kjbb.net.MarketNetwork.ClockSync(
                                            com.tangtu.kjbb.ServerCalendar.now()));
                            // 离线期间因航次结束被撤单的股票，进服时补发
                            com.tangtu.kjbb.StockMarket.claimPending(sp);
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                });

        // 提单式投递：右键蛙港/邮筒取货
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(BillDelivery::onUsePort);

        // 传送石碑收费（Waystones API 事件）
        try {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    net.blay09.mods.waystones.api.event.WaystoneActivatedEvent.class,
                    WaystoneFee::onActivated);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    net.blay09.mods.waystones.api.event.WaystoneTeleportEvent.Pre.class,
                    WaystoneFee::onTeleportPre);
            System.out.println("[TangtuEconomical] \u4f20\u9001\u77f3\u7891\u6536\u8d39\u5df2\u6302\u8f7d");
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u77f3\u7891\u4e8b\u4ef6\u6302\u8f7d\u5931\u8d25: " + t);
        }
        System.out.println("[TangtuEconomical] loaded — BankBridge / ShopBridge / 票据系统 已就绪");
    }
}