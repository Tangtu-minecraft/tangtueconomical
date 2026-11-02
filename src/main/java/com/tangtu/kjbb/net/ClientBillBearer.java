package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 客户端：提单记名/不记名状态 */
public final class ClientBillBearer {

    private static boolean bearer = false;

    private ClientBillBearer() {
    }

    public static void setState(boolean value) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            bearer = value;
        }
    }

    public static boolean isBearer() {
        return bearer;
    }

    public static void toggle() {
        bearer = !bearer;
        BillBearerNetwork.send(bearer);
    }
}