package com.tangtu.kjbb.net;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 发报机票据界面：服务端回包 → 客户端 */
public final class ClientTickerBridge {

    private ClientTickerBridge() {
    }

    public static void open(BlockPos pos) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            OpenDispatch.run(pos);
        }
    }

    public static void accept(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            DataDispatch.run(body);
        }
    }

    private static final class OpenDispatch {
        static void run(BlockPos pos) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.PaperScreen(pos));
        }
    }

    private static final class DataDispatch {
        static void run(String body) {
            com.tangtu.kjbb.client.StoreCache.set(body);
        }
    }

    public static void acceptShipments(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.tangtu.kjbb.client.StoreCache.setShipments(body);
        }
    }

    /** 客户端请求：从发报机发货某张订单 */
    public static void ship(net.minecraft.core.BlockPos pos, long orderId) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new TickerBillNetwork.ShipOrder(pos.getX(), pos.getY(), pos.getZ(), orderId));
    }
}
