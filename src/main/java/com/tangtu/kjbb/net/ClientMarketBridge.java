package com.tangtu.kjbb.net;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** 行情界面的服务端回包 → 客户端缓存 */
public final class ClientMarketBridge {

    private ClientMarketBridge() {
    }

    public static void markets(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.markets(body);
        }
    }

    public static void history(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.history(body);
        }
    }

    public static void myOrders(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.myOrders(body);
        }
    }

    public static void itemOrders(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.itemOrders(body);
        }
    }

    public static void orderDetail(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.orderDetail(body);
        }
    }

    public static void tickets(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.tickets(body);
        }
    }

    public static void ticketDetail(String body) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Dispatch.ticketDetail(body);
        }
    }

    private static final class Dispatch {
        static void ticketDetail(String body) {
            com.tangtu.kjbb.client.MarketCache.setTicketDetail(body);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.TicketDetailScreen());
        }

        static void tickets(String body) {
            com.tangtu.kjbb.client.MarketCache.setTickets(body);
        }

        static void orderDetail(String body) {
            com.tangtu.kjbb.client.MarketCache.setOrderDetail(body);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new com.tangtu.kjbb.client.OrderDetailScreen());
        }

        static void itemOrders(String body) {
            com.tangtu.kjbb.client.MarketCache.setItemOrders(body);
        }

        static void myOrders(String body) {
            com.tangtu.kjbb.client.MarketCache.setMyOrders(body);
        }

        static void markets(String body) {
            com.tangtu.kjbb.client.MarketCache.setMarkets(body);
        }

        static void history(String body) {
            com.tangtu.kjbb.client.MarketCache.setHistory(body);
        }
    }
}