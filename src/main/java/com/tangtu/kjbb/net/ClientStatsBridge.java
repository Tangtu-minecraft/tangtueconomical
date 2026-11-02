package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 把统计回包转给客户端缓存（服务端不加载客户端类） */
public final class ClientStatsBridge {

    private ClientStatsBridge() {
    }

    public static void accept(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientDispatch.run(body);
        }
    }

    /** 客户端类加载隔离：只在客户端引用 */
    private static final class ClientDispatch {
        static void run(String body) {
            com.tangtu.kjbb.client.StatsData.set(body);
        }
    }
}
