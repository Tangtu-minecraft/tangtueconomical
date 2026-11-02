package com.tangtu.kjbb.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 唐兔银行（沿用 form_sheet 底图） */
public class BankScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;

    private static BankScreen OPEN;

    private int left;
    private int top;

    public BankScreen() {
        super(Component.literal("唐兔银行"));
    }

    @Override
    protected void init() {
        OPEN = this;
        left = (this.width - W) / 2;
        top = (this.height - H) / 2;

        // 铁律 ②：先注册底图（顺序决定层级：先注册=先画=在下面）
        this.addRenderableOnly((g, mx, my, pt) -> this.drawContent(g));

        int x1 = left + 44;
        int x2 = left + 170;
        int y = top + 108;
        int w = 110, h = 20, gap = 4;

        addRenderableWidget(new WoodButton(x1, y, w, h,
                Component.literal("\u00a7a\u5b58\u6b3e"), b -> act("billcoins deposit")));
        addRenderableWidget(new WoodButton(x2, y, w, h,
                Component.literal("\u00a7e\u53d6\u73b0 512"), b -> act("billcoins cash 512")));
        addRenderableWidget(new WoodButton(x1, y + (h + gap), w, h,
                Component.literal("\u00a7e\u53d6\u73b0 2048"), b -> act("billcoins cash 2048")));
        addRenderableWidget(new WoodButton(x2, y + (h + gap), w, h,
                Component.literal("\u00a7b\u9f7f\u8f6e\u5e01"), b -> act("billcoins togear")));
        addRenderableWidget(new WoodButton(x1, y + (h + gap) * 2, w, h,
                Component.literal("\u00a7b\u65e7\u5e01"), b -> act("billcoins toold")));
        addRenderableWidget(new WoodButton(x2, y + (h + gap) * 2, w, h,
                Component.literal("\u00a7b\u4fdd\u9669\u67dc"), b -> act("billcoins safe")));
        addRenderableWidget(new WoodButton(x1, y + (h + gap) * 3, w, h,
                Component.literal("\u00a76\u52b3\u52a8\u5408\u540c"), b -> act("billcontract")));
        addRenderableWidget(new WoodButton(x2, y + (h + gap) * 3, w, h,
                Component.literal("\u00a77\u5173\u95ed"), b -> onClose()));

        // 铁律 ①：贴图设为最近邻 + 无 mipmap
        try {
            var tm = net.minecraft.client.Minecraft.getInstance().getTextureManager();
            var tex = tm.getTexture(TEX, new net.minecraft.client.renderer.texture.SimpleTexture(TEX));
            if (tex != null) {
                tex.setFilter(false, false);
            }
        } catch (Throwable ignored) {
            // ignore
        }


        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.tangtu.kjbb.net.MenuNetwork.ReqBank());
    }

    /** 全部绘制都在这里（在 super.render 阶段以 1:1 画） */
    private void drawContent(GuiGraphics g) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        int x = left + 52;
        g.drawString(this.font, "\u5510\u5154\u94f6\u884c", x, top + 46, 0x5A3F1E, false);

        kv(g, "\u94b1\u5305", MarketCache.wallet(), x, top + 58);
        kv(g, "\u786c\u5e01", MarketCache.coins(), x, top + 72);
        kv(g, "\u4fdd\u9669\u67dc", MarketCache.safe(), x + 118, top + 58);

        g.drawString(this.font, "\u5408\u8ba1", x, top + 86, 0x5A3F1E, false);
        g.drawString(this.font, String.valueOf(MarketCache.total()),
                x + this.font.width("\u5408\u8ba1") + 6, top + 86, 0xB0202D, false);
    }

    /** 画「标签 + 数字」，y 由调用方明确给出 */
    private void kv(GuiGraphics g, String label, int value, int x, int y) {
        g.drawString(this.font, label, x, y, 0x5A3F1E, false);
        g.drawString(this.font, String.valueOf(value), x + this.font.width(label) + 6, y,
                0x2F6B2F, false);
    }

    /** 以玩家身份执行一条命令 */
    private void act(String cmd) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.tangtu.kjbb.net.MenuNetwork.Action(cmd));
    }


    public static void refreshIfOpen() {
        if (OPEN != null && net.minecraft.client.Minecraft.getInstance().screen == OPEN) {
            OPEN.clearWidgets();
            OPEN.init(OPEN.minecraft, OPEN.width, OPEN.height);
        }
    }

    @Override
    public void removed() {
        if (OPEN == this) {
            OPEN = null;
        }
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 铁律 ③：不调用 renderBackground（会触发菜单模糊），只自己压暗
        // 遮罩必须在压入缩放之前画，否则会跟着面板一起缩小
        g.fill(0, 0, this.width, this.height, 0x70000000);
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
        // 空实现：遮罩已在 render() 里按屏幕坐标画好，这里再画会被缩放一起缩掉
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
