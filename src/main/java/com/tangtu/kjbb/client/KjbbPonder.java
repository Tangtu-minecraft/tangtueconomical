package com.tangtu.kjbb.client;

import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** 客户端初始化时注册 Ponder 插件 */
@EventBusSubscriber(modid = "kjbankbridge", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class KjbbPonder {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                PonderIndex.addPlugin(new KjbbPonderPlugin());
                System.out.println("[TangtuEconomical] Ponder 教程已注册");
            } catch (Throwable t) {
                System.out.println("[TangtuEconomical] Ponder 注册失败: " + t);
            }
        });
    }
}
