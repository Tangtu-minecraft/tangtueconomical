package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 客户端：打开经济系统主菜单 */
public final class ClientMenuOpen {

    private ClientMenuOpen() {
    }

    public static void open() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.run();
        }
    }

    public static void openBank() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            BankDispatch.run();
        }
    }

    private static final class BankDispatch {
        static void run() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.BankScreen());
        }
    }

    private static final class Dispatch {
        static void run() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.MainMenuScreen());
        }
    }

    /** 打开国民统计报表界面 */
    public static void openReport(String text, String items) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> com.tangtu.kjbb.client.CitizenReportScreen.setData(text, items));
    }

    /** 打开股票详情界面 */
    public static void openStock(long billId) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new com.tangtu.kjbb.client.StockScreen(billId)));
    }
}
