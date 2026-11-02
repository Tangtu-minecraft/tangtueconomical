package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 仓单界面的客户端分发 */
public final class ClientStoreBridge {

    private ClientStoreBridge() {
    }

    public static void open(int x, int y, int z, String body, String diag) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.open(x, y, z, body, diag);
        }
    }

    public static void view(String pos, String owner, String body, long rid) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.view(pos, owner, body, rid);
        }
    }

    public static void lockState(boolean locked) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.lockState(locked);
        }
    }

    private static final class Dispatch {
        static void lockState(boolean locked) {
            com.tangtu.kjbb.client.ReceiptScreen.setLocked(locked);
        }

        static void view(String pos, String owner, String body, long rid) {
            com.tangtu.kjbb.client.ReceiptScreen.setData(pos, owner, body, rid);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.ReceiptScreen());
        }

        static void open(int x, int y, int z, String body, String diag) {
            com.tangtu.kjbb.client.StoreFormScreen.setData(x, y, z, body, diag);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.StoreFormScreen());
        }
    }
}