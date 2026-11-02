package com.tangtu.kjbb.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 唐兔经济系统 · 主菜单 */
public class MainMenuScreen extends Screen {

    private static final ResourceLocation BG =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");

    private boolean hasBg = false;

    public MainMenuScreen() {
        super(Component.literal("唐兔经济系统"));
    }

    @Override
    protected void init() {
        try {
            hasBg = this.minecraft != null && this.minecraft.getResourceManager()
                    .getResource(BG).isPresent();
        } catch (Throwable ignored) {
            hasBg = false;
        }
        this.addRenderableOnly((g, mx, my, pt) -> this.drawContent(g));
        int cx = this.width / 2;
        int cy = this.height / 2;
        int w = 160;
        int h = 22;
        int gap = 6;
        int x1 = cx - w - 10;
        int x2 = cx + 10;
        int y0 = cy - 40;

        // 左列
        addRenderableWidget(Button.builder(Component.literal("\u00a7e\u5e02\u573a"), b -> {
            this.minecraft.setScreen(new MarketScreen());
        }).bounds(x1, y0, w, h).build());

        addRenderableWidget(Button.builder(Component.literal("\u00a7e\u94f6\u884c\u4e1a\u52a1"), b -> {
            action("billbank");
        }).bounds(x1, y0 + (h + gap), w, h).build());

        addRenderableWidget(Button.builder(Component.literal("\u00a7e\u7968\u636e\u7bb1"), b -> {
            action("ticket list");
        }).bounds(x1, y0 + (h + gap) * 2, w, h).build());

        // 右列
        addRenderableWidget(Button.builder(Component.literal("\u00a7e\u52b3\u52a8\u5408\u540c"), b -> {
            action("billcontract");
        }).bounds(x2, y0, w, h).build());

        addRenderableWidget(Button.builder(Component.literal("\u00a7e\u6211\u7684\u8ba2\u5355"), b -> {
            action("billorder");
        }).bounds(x2, y0 + (h + gap), w, h).build());

        addRenderableWidget(Button.builder(Component.literal("\u00a77\u5173\u95ed"), b -> {
            onClose();
        }).bounds(x2, y0 + (h + gap) * 2, w, h).build());
    }

    private void action(String cmd) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.tangtu.kjbb.net.MenuNetwork.Action(cmd));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x70000000);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawContent(GuiGraphics g) {
        if (hasBg) {
            g.fill(0, 0, this.width, this.height, 0xF0201A12);   // 纸面底色（不再引用缺失贴图）
        }
        int cx = this.width / 2;
        g.drawCenteredString(this.font, "\u00a76\u5510\u5154\u7ecf\u6d4e\u7cfb\u7edf",
                cx, this.height / 2 - 70, 0xFFFFFF);
        g.drawCenteredString(this.font, "\u00a77\u94f6\u884c \u00b7 \u5e02\u573a \u00b7 \u7968\u636e \u00b7 \u52b3\u52a8",
                cx, this.height / 2 + 55, 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
