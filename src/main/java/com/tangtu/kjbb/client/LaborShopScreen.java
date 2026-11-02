package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 假人劳动合同 · 购买界面（木牌按钮 + 小时/天切换） */
public class LaborShopScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;

    private static LaborShopScreen OPEN;

    private final int pricePerHour;
    private final int minMinutes;
    private final int maxMinutes;
    private final String defaultName = "bot" + (System.currentTimeMillis() % 10000);

    private int minutes;
    /** 1 = 按小时调，2 = 按天调 */
    private int unit = 2;
    private EditBox nameBox;

    private int left;
    private int top;

    private final int usedGlobal, maxGlobal, usedMine, maxMine;

    public LaborShopScreen(int pricePerHour, int minMinutes, int maxMinutes, int defaultMinutes,
                           int usedGlobal, int maxGlobal, int usedMine, int maxMine) {
        super(Component.literal("劳动合同"));
        this.usedGlobal = usedGlobal;
        this.maxGlobal = maxGlobal;
        this.usedMine = usedMine;
        this.maxMine = maxMine;
        this.pricePerHour = Math.max(1, pricePerHour);
        this.minMinutes = Math.max(1, minMinutes);
        this.maxMinutes = Math.max(this.minMinutes, maxMinutes);
        this.minutes = Math.max(this.minMinutes, Math.min(this.maxMinutes, defaultMinutes));
        OPEN = this;
    }

    private long price() {
        return Math.max(1L, (long) Math.ceil(pricePerHour * (double) minutes / 60.0));
    }

    private int step() {
        return unit == 2 ? 1440 : 60;
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

        // 底图先注册（在下层）
        this.addRenderableOnly((g, mx, my, pt) -> this.drawContent(g));

        int cx = this.width / 2;
        int y = top + 96;

        nameBox = new EditBox(this.font, cx - 100, top + 62, 200, 18, Component.literal("假人名称"));
        nameBox.setMaxLength(16);
        nameBox.setValue(defaultName);
        addRenderableWidget(nameBox);

        // 单位切换
        addRenderableWidget(new WoodButton(cx - 100, y, 95, 20,
                Component.literal("\u00a7e" + (unit == 2 ? "\u5355\u4f4d\uff1a\u5929" : "\u5355\u4f4d\uff1a\u5c0f\u65f6")),
                b -> {
                    unit = unit == 2 ? 1 : 2;
                    rebuild();
                }));
        // 减少
        addRenderableWidget(new WoodButton(cx + 5, y, 45, 20,
                Component.literal("\u00a7c-"), b -> {
                    minutes = Math.max(minMinutes, minutes - step());
                    rebuild();
                }));
        // 增加
        addRenderableWidget(new WoodButton(cx + 55, y, 45, 20,
                Component.literal("\u00a7a+"), b -> {
                    minutes = Math.min(maxMinutes, minutes + step());
                    rebuild();
                }));

        boolean full = usedGlobal >= maxGlobal || usedMine >= maxMine;
        var buyBtn = new WoodButton(cx - 100, top + 200, 95, 20,
                Component.literal(full ? "\u00a77\u5df2\u6ee1" : "\u00a7a\u7b7e\u7ea6\u8d2d\u4e70"), b -> {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.tangtu.kjbb.net.LaborNetwork.Buy(nameBox.getValue(), minutes));
                    onClose();
                });
        buyBtn.active = !full;
        addRenderableWidget(buyBtn);
        addRenderableWidget(new WoodButton(cx + 5, top + 200, 95, 20,
                Component.literal("\u00a77\u53d6\u6d88"), b -> onClose()));

        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.tangtu.kjbb.net.LaborNetwork.ReqList());
    }

    /** 只重建文字，不重建输入框内容 */
    private void rebuild() {
        String keep = nameBox == null ? defaultName : nameBox.getValue();
        this.clearWidgets();
        this.init(this.minecraft, this.width, this.height);
        if (nameBox != null) {
            nameBox.setValue(keep);
        }
    }

    public static void refreshIfOpen() {
        // 列表数据更新时只重绘，不重建（避免名字乱跳）
    }

    private void drawContent(GuiGraphics g) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        int cx = this.width / 2;
        g.drawString(this.font, "\u5047\u4eba\u52b3\u52a8\u5408\u540c", left + 52, top + 46, 0x5A3F1E, false);

        g.drawString(this.font, "\u5047\u4eba\u540d\u79f0", cx - 100, top + 52, 0x6B5A44, false);

        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        String dur;
        if (days > 0 && hours > 0) {
            dur = days + " \u5929 " + hours + " \u5c0f\u65f6";
        } else if (days > 0) {
            dur = days + " \u5929";
        } else {
            dur = hours + " \u5c0f\u65f6";
        }
        g.drawString(this.font, "\u5de5\u671f\uff1a" + dur, cx - 100, top + 124, 0x3A2A18, false);
        g.drawString(this.font, "\u5355\u4ef7\uff1a" + pricePerHour + " / \u5c0f\u65f6", cx - 100, top + 138, 0x3A2A18, false);
        g.drawString(this.font, "\u603b\u4ef7\uff1a", cx - 100, top + 156, 0x5A3F1E, false);
        g.drawString(this.font, price() + " \u5510\u5154\u5e01", cx - 60, top + 156, 0xB0202D, false);

        g.drawString(this.font, "\u5047\u4eba\uff1a\u00a7f" + usedGlobal + "/" + maxGlobal
                + " \u00a77\uff08\u4f60 " + usedMine + "/" + maxMine + "\uff09",
                left + 52, top + 176, 0x6B5A44, false);

        String list = MarketCache.laborList();
        g.drawString(this.font, "\u73b0\u6709\uff1a" + (list.isEmpty() ? "\u67e5\u8be2\u4e2d" : list),
                left + 52, top + 232, 0x6B5A44, false);
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
    public void removed() {
        if (OPEN == this) {
            OPEN = null;
        }
        super.removed();
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
