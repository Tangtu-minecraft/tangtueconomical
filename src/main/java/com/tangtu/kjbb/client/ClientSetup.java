package com.tangtu.kjbb.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * 客户端入口（MOD 总线）：
 * 注册一个【默认未绑定】的按键槽，玩家可以自己在按键设置里绑一个单键。
 * 另有一个开箱即用的组合键 Ctrl+P / Alt+P（见 ClientInput）。
 */
@EventBusSubscriber(modid = "kjbankbridge", value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private static KeyMapping openPanelKey;

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        // UNKNOWN = 默认不绑定，避免和别的 mod 抢键
        openPanelKey = new KeyMapping(
                "key.kjbb.panel",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                "key.categories.kjbb");
        event.register(openPanelKey);
    }

    public static void openPanel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            mc.setScreen(new PaperScreen());
        }
    }

    /** 玩家自己绑定的单键 */
    public static void pollBoundKey() {
        if (openPanelKey == null) {
            return;
        }
        while (openPanelKey.consumeClick()) {
            openPanel();
        }
    }
}