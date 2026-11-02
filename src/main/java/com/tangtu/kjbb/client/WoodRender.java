package com.tangtu.kjbb.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 统一的木制按钮绘制
 *
 * 所有界面里的「手绘按钮」都走这里，保证外观一致：
 *   普通：kjbb:textures/gui/btn_wood.png
 *   悬停：kjbb:textures/gui/btn_wood_hover.png
 *   文字：奶白色，居中
 */
public final class WoodRender {

    public static final ResourceLocation WOOD =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/btn_wood.png");
    public static final ResourceLocation WOOD_HOVER =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/btn_wood_hover.png");

    /** 原图尺寸 */
    private static final int TW = 440;
    private static final int TH = 80;

    private WoodRender() {
    }

    /** 画一个木制按钮（不含文字） */
    public static void plate(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        g.blit(hover ? WOOD_HOVER : WOOD, x, y, w, h, 0.0f, 0.0f, TW, TH, TW, TH);
    }

    /** 画木制按钮 + 居中文字 */
    public static void button(GuiGraphics g, net.minecraft.client.gui.Font font,
                              int x, int y, int w, int h, String label,
                              int mouseX, int mouseY, int textColor) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        plate(g, x, y, w, h, hover);
        int tw = font.width(label);
        font.getClass();   // 保持签名简单
        g.drawString(font, label, x + (w - tw) / 2, y + (h - 8) / 2, textColor, false);
    }

    /** 默认文字色 */
    public static final int TEXT = 0xFFF3E2C0;
}
