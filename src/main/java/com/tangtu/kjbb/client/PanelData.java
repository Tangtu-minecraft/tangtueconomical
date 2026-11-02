package com.tangtu.kjbb.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 面板数据缓存（客户端） */
public final class PanelData {

    private static String raw = "";
    private static List<String> lines = new ArrayList<>();

    private PanelData() {
    }

    public static void set(String body) {
        raw = body == null ? "" : body;
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\n")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        lines = out;
    }

    public static List<String> lines() {
        return lines;
    }

    /** "minecraft:iron_ingot=34@33" → "铁锭 ×34   @33" */
    public static String pretty(String entry) {
        try {
            int eq = entry.indexOf('=');
            if (eq < 0) {
                return entry;
            }
            String id = entry.substring(0, eq);
            String rest = entry.substring(eq + 1);
            String count = rest;
            String price = "";
            int at = rest.indexOf('@');
            if (at >= 0) {
                count = rest.substring(0, at);
                price = rest.substring(at + 1);
            }
            String name = displayName(id);
            String s = name + " ×" + count;
            if (!price.isEmpty() && !"0".equals(price)) {
                s = s + "   @" + price;
            }
            return s;
        } catch (Throwable t) {
            return entry;
        }
    }

    /** 物品 id → 本地化名字（客户端有语言表） */
    private static String displayName(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                return shortId(id);
            }
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null) {
                return shortId(id);
            }
            ItemStack stack = new ItemStack(item);
            return stack.getHoverName().getString();
        } catch (Throwable t) {
            return shortId(id);
        }
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}