package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.fml.loading.FMLEnvironment;

/** 把服务端回包转给当前打开的面板（仅客户端有实现） */
public final class ClientPanelBridge {

    private ClientPanelBridge() {
    }

    public static void accept(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientDispatch.run(body);
        }
    }

    /** 客户端类加载隔离：只在客户端引用 */
    private static final class ClientDispatch {
        static void run(String body) {
            com.tangtu.kjbb.client.PanelData.set(body);
        }
    }
}