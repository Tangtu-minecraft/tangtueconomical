package com.tangtu.kjbb.net;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

public final class ClientOrderBridge {

    private ClientOrderBridge() {
    }

    public static void open(BlockPos pos) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.run(pos);
        }
    }

    private static final class Dispatch {
        static void run(BlockPos pos) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.BillOrderScreen(pos));
        }
    }
}