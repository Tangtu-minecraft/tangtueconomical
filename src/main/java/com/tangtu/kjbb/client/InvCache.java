package com.tangtu.kjbb.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 背包物品清单缓存（客户端） */
public final class InvCache {

    public record Entry(String id, int count) {
    }

    private static List<Entry> entries = new ArrayList<>();

    private InvCache() {
    }

    public static void set(String body) {
        List<Entry> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                int bar = line.lastIndexOf('|');
                if (bar <= 0) {
                    continue;
                }
                String id = line.substring(0, bar).trim();
                int cnt;
                try {
                    cnt = Integer.parseInt(line.substring(bar + 1).trim());
                } catch (Throwable t) {
                    cnt = 0;
                }
                if (!id.isEmpty()) {
                    out.add(new Entry(id, cnt));
                }
            }
        }
        entries = out;
    }

    public static List<Entry> entries() {
        return entries;
    }

    /** 用 id 造一个展示用 ItemStack */
    public static ItemStack stackOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }
}