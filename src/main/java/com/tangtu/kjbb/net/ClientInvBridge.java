package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 背包清单回包 → 客户端缓存 */
public final class ClientInvBridge {

    private ClientInvBridge() {
    }

    public static void accept(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.run(body);
        }
    }

    private static final class Dispatch {
        static void run(String body) {
            com.tangtu.kjbb.client.InvCache.set(body);
        }
    }
}