package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 客户端：打开票据中心 */
public final class ClientFormCenter {

    private ClientFormCenter() {
    }

    public static void open() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.run();
        }
    }

    private static final class Dispatch {
        static void run() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.FormCenterScreen());
        }
    }
}
