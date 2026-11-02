package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 客户端：打开劳动合同购买界面 */
public final class ClientLaborOpen {

    private ClientLaborOpen() {
    }

    public static void open(int pricePerHour, int minMinutes, int maxMinutes, int defaultMinutes,
                            int usedGlobal, int maxGlobal, int usedMine, int maxMine) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.run(pricePerHour, minMinutes, maxMinutes, defaultMinutes,
                    usedGlobal, maxGlobal, usedMine, maxMine);
        }
    }

    public static void openDetail(String worker, int minutes, int price, String owner) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            DetailDispatch.run(worker, minutes, price, owner);
        }
    }

    private static final class DetailDispatch {
        static void run(String worker, int minutes, int price, String owner) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.ContractScreen(worker, minutes, price, owner));
        }
    }

    private static final class Dispatch {
        static void run(int perHour, int minMin, int maxMin, int defMin,
                        int usedGlobal, int maxGlobal, int usedMine, int maxMine) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.LaborShopScreen(perHour, minMin, maxMin, defMin,
                    usedGlobal, maxGlobal, usedMine, maxMine));
        }
    }
}
