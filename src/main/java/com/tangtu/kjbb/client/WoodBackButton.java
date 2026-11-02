package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 木制「← 返回」按钮（统一的返回键样式）
 */
public class WoodBackButton extends AbstractWidget {

    private final OnPress onPress;

    public interface OnPress {
        void press();
    }

    public WoodBackButton(int x, int y, int w, int h, Component label, OnPress onPress) {
        super(x, y, w, h, label);
        this.onPress = onPress;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        WoodRender.button(g, Minecraft.getInstance().font, getX(), getY(),
                getWidth(), getHeight(), getMessage().getString(),
                mouseX, mouseY, WoodRender.TEXT);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (onPress != null) {
            onPress.press();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        // 不需要朗读
    }
}
