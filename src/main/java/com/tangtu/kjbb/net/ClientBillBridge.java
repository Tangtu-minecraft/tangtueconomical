package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 提单列表回包 → 客户端缓存 */
public final class ClientBillBridge {

    private ClientBillBridge() {
    }

    public static void accept(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientDispatch.run(body);
        }
    }

    public static void openBill(String itemId, int count, String issuer,
                                long serial, long expiresAt, String status, String address) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            BillOpenDispatch.run(itemId, count, issuer, serial, expiresAt, status, address);
        }
    }

    public static void openPaper() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            OpenDispatch.run();
        }
    }

    private static final class ClientDispatch {
        static void run(String body) {
            com.tangtu.kjbb.client.BillListCache.set(body);
        }
    }

    private static final class BillOpenDispatch {
        static void run(String itemId, int count, String issuer,
                        long serial, long expiresAt, String status, String address) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.PaperScreen(true, itemId, count,
                    issuer, serial, expiresAt, status, address));
        }
    }

    private static final class OpenDispatch {
        static void run() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen == null) {
                mc.setScreen(new com.tangtu.kjbb.client.PaperScreen());
            }
        }
    }
}
