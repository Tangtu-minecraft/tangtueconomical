package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 给本模组所有界面统一加一个「← 返回」按钮（左上角）
 *
 * 返回目标：
 *   · 市场界面本身 → 关闭（回到游戏）
 *   · 其他界面     → 回到市场界面
 */
@EventBusSubscriber(modid = "kjbankbridge", value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.GAME)
public final class BackButtonInjector {

    private BackButtonInjector() {
    }

    private static final String PKG = "com.tangtu.kjbb.client.";

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        String name = screen.getClass().getName();
        if (!name.startsWith(PKG)) {
            return;
        }
        // 已经有返回键的界面不再加；服务中心自身是根，也不加
        if (name.endsWith("OrderDetailScreen") || name.endsWith("MarketItemScreen")
                || name.endsWith("FormCenterScreen") || name.endsWith("CitizenReportScreen")) {
            return;
        }
        // 统一返回服务中心
        WoodBackButton back = new WoodBackButton(6, 6, 66, 18,
                Component.literal("\u2190 \u8fd4\u56de"),
                () -> Minecraft.getInstance().setScreen(new FormCenterScreen()));
        event.addListener(back);
    }
}
