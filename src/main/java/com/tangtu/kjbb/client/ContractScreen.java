package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 假人劳动合同 · 详情（沿用 form_sheet 底图 + 木制按钮） */
public class ContractScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;

    private final String worker;
    private final int minutes;
    private final int price;
    private final String owner;

    private int left;
    private int top;

    public ContractScreen(String worker, int minutes, int price, String owner) {
        super(Component.literal("劳动合同"));
        this.worker = worker == null ? "" : worker;
        this.minutes = minutes;
        this.price = price;
        this.owner = owner == null ? "" : owner;
    }

    @Override
    protected void init() {
        left = (this.width - W) / 2;
        top = (this.height - H) / 2;
        try {
            var tm = Minecraft.getInstance().getTextureManager();
            var tex = tm.getTexture(TEX, new SimpleTexture(TEX));
            if (tex != null) {
                tex.setFilter(false, false);
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // 底图先画，按钮后画
        this.addRenderableOnly((g, mx, my, pt) -> this.drawContent(g));

        addRenderableWidget(new WoodButton(left + 105, top + H - 65, 110, 20,
                Component.literal("\u00a77\u5173\u95ed"), b -> onClose()));
    }

    private void drawContent(GuiGraphics g) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        int x = left + 52;
        int y = top + 46;
        g.drawString(this.font, "\u5047\u4eba\u52b3\u52a8\u5408\u540c", x, y, 0x5A3F1E, false);
        y += 22;

        y = row(g, "\u5de5\u4eba", worker, x, y);
        y = row(g, "\u5de5\u671f", minutes + " \u5206\u949f", x, y);
        y = row(g, "\u5de5\u4ef7", price + " \u5510\u5154\u5e01", x, y);
        y = row(g, "\u96c7\u4e3b", owner, x, y);
        y += 8;

        g.drawString(this.font, "\u4f7f\u7528\u65b9\u5f0f", x, y, 0x5A3F1E, false);
        y += 14;
        g.drawString(this.font, "\u53f3\u952e\u5730\u9762\uff1a\u96c7\u4f63\u8be5\u5047\u4eba", x, y, 0x6B5A44, false);
        y += 12;
        g.drawString(this.font, "\u5230\u671f\u81ea\u52a8\u89e3\u96c7\uff0c\u53ef\u968f\u65f6\u91cd\u65b0\u7b7e\u7ea6", x, y, 0x6B5A44, false);
        y += 18;
        g.drawString(this.font, "\u6f5c\u884c\u53f3\u952e\uff1a\u67e5\u770b\u672c\u9875\u8be6\u60c5", x, y, 0x6B5A44, false);
    }

    private int row(GuiGraphics g, String label, String value, int x, int y) {
        g.drawString(this.font, label, x, y, 0x5A3F1E, false);
        g.drawString(this.font, value, x + 44, y, 0x2F6B2F, false);
        return y + 16;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 遮罩必须画在压入缩放之前，否则会跟着面板一起缩小
        g.fill(0, 0, this.width, this.height, 0x70000000);
        // 逻辑分辨率装不下面板时整体等比缩小；鼠标坐标同步换算，绘制与点击永远一致
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k >= 1.0f) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        UiScale.push(g, left, top, W, H, k);
        try {
            super.render(g, (int) UiScale.mapX(mouseX, left, W, k),
                    (int) UiScale.mapY(mouseY, top, H, k), partialTick);
        } finally {
            UiScale.pop(g);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 空实现：遮罩已在 render() 里按屏幕坐标画好，否则会被缩放一起缩掉
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, H, k);
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
