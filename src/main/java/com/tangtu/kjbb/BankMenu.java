package com.tangtu.kjbb;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/** 银行柜台菜单（命令 /billbank 与 NPC 右键共用） */
public final class BankMenu {

    private BankMenu() {
    }

    public static void send(ServerPlayer sp) {
        line(sp, Component.literal("\u00a76\u2500\u2500 \u5510\u5154\u94f6\u884c \u2500\u2500"));
        line(sp, Component.literal(Coins.describe(sp)));
        line(sp, button("\u00a7a[\u5b58\u6b3e]", "/billcoins deposit")
                .append(Component.literal("  "))
                .append(button("\u00a7e[\u53d6\u73b0 512]", "/billcoins cash 512"))
                .append(Component.literal("  "))
                .append(button("\u00a7e[\u53d6\u73b0 2048]", "/billcoins cash 2048")));
        line(sp, button("\u00a7b[\u6362\u6210\u9f7f\u8f6e\u5e01]", "/billcoins togear")
                .append(Component.literal("  "))
                .append(button("\u00a7b[\u6362\u6210\u65e7\u5e01]", "/billcoins toold"))
                .append(Component.literal("  "))
                .append(button("\u00a7b[\u4fdd\u9669\u67dc\u53d6\u73b0]", "/billcoins safe")));
        line(sp, button("\u00a7d[\u5f00\u672c\u7968]", "/billnote issue")
                .append(Component.literal("  "))
                .append(button("\u00a7d[\u5f00\u652f\u7968]", "/billcheck issue"))
                .append(Component.literal("  "))
                .append(button("\u00a77[\u5237\u65b0]", "/billbank")));
        line(sp, button("\u00a76[\u8d2d\u4e70\u52b3\u52a8\u5408\u540c]", "/billcontract")
                .append(Component.literal("  "))
                .append(button("\u00a77[\u5047\u4eba]", "/billcontract")));
    }

    private static void line(ServerPlayer sp, Component c) {
        sp.displayClientMessage(c, false);
    }

    public static MutableComponent button(String label, String command) {
        return Component.literal(label).withStyle(st -> st
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("\u00a77" + command))));
    }
}
