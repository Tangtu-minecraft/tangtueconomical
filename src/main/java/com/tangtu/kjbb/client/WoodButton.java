package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 木制铭牌按钮（贴图 440×80，按控件尺寸缩放） */
public class WoodButton extends Button {

    private static final ResourceLocation TEX_NORMAL =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/btn_wood.png");
    private static final ResourceLocation TEX_HOVER =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/btn_wood_hover.png");

    public WoodButton(int x, int y, int w, int h, Component label, OnPress onPress) {
        super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
        try {
            var tm = Minecraft.getInstance().getTextureManager();
            var t1 = tm.getTexture(TEX_NORMAL, new SimpleTexture(TEX_NORMAL));
            if (t1 != null) {
                t1.setFilter(false, false);
            }
            var t2 = tm.getTexture(TEX_HOVER, new SimpleTexture(TEX_HOVER));
            if (t2 != null) {
                t2.setFilter(false, false);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ResourceLocation tex = isHoveredOrFocused() && this.active ? TEX_HOVER : TEX_NORMAL;
        g.blit(tex, getX(), getY(), getWidth(), getHeight(), 0.0f, 0.0f, 440, 80, 440, 80);
        int color = this.active ? 0xFFF3E2C0 : 0xFF8A7A60;
        g.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
    }
}
