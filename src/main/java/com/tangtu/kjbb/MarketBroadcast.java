package com.tangtu.kjbb;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/** 全服广播：买家挂了买单 */
public final class MarketBroadcast {

    private MarketBroadcast() {
    }

    public static void buyOrder(MinecraftServer server, String buyer, String itemId,
                                int count, long unit, long total) {
        if (server == null || buyer == null || itemId == null) {
            return;
        }
        String name = itemId;
        try {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
            if (item != null) {
                String n = new ItemStack(item).getHoverName().getString();
                if (n != null && !n.isEmpty()) {
                    name = n;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        Component msg = Component.literal("\u00a76[\u6c42\u8d2d] \u00a7e" + buyer
                + " \u00a77\u6c42\u8d2d \u00a7f" + name + " \u00a77\u00d7" + count
                + " \u00a77\uff0c\u5355\u4ef7 \u00a7a" + unit
                + " \u00a77\uff08\u5171 " + total + " \u5510\u5154\u5e01\uff09"
                + " \u00a78\u2014\u2014 \u53bb\u5e02\u573a\u63a5\u5355");
        try {
            server.getPlayerList().broadcastSystemMessage(msg, false);
        } catch (Throwable ignored) {
            // ignore
        }
        System.out.println("[TangtuEconomical] \u5e7f\u64ad\u6c42\u8d2d " + buyer + " " + itemId
                + " x" + count + " unit=" + unit);
    }
}
