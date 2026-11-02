package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 客户端：打开票据挂卖小窗 */
public final class ClientSellBridge {

    private ClientSellBridge() {
    }

    public static void open(int slot) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.run(slot);
        }
    }

    private static final class Dispatch {
        static void run(int slot) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }
            var stack = mc.player.getMainHandItem();
            if (stack == null || stack.isEmpty()) {
                return;
            }
            mc.setScreen(new com.tangtu.kjbb.client.TicketSellScreen(stack, slot));
        }
    }
}
