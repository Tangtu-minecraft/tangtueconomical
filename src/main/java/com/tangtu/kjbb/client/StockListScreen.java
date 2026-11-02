package com.tangtu.kjbb.client;

import com.tangtu.kjbb.net.MenuNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 上架股票：单独一页调「卖几份」和「单股价格」。
 *
 * 入口：右键股票 → 股票界面 → 上架（票必须拿在主手）。
 * 确认后发 {@code /billvoyage stall <单价> <份数>}，服务端按份数拆票并把票冻结成挂单。
 */
public class StockListScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_L = 43;
    private static final int IN_R = 283;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int GOOD = 0x2E5B2E;
    private static final int BAD = 0x9B2C2C;

    private final long billId;
    private final String shipName;
    private final int maxShares;
    private final long defaultPrice;

    private int left;
    private int top;
    private EditBox sharesBox;
    private EditBox priceBox;

    public StockListScreen(long billId, String shipName, int maxShares, long defaultPrice) {
        super(Component.literal("上架股票"));
        this.billId = billId;
        this.shipName = shipName == null ? "" : shipName;
        this.maxShares = Math.max(1, maxShares);
        this.defaultPrice = Math.max(1L, defaultPrice);
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;

        sharesBox = new EditBox(this.font, left + IN_L + 56, top + 102, 52, 14,
                Component.literal("份数"));
        sharesBox.setValue(String.valueOf(maxShares));
        sharesBox.setMaxLength(6);
        sharesBox.setTextColor(0xFFFFFF);
        addRenderableWidget(sharesBox);

        priceBox = new EditBox(this.font, left + IN_L + 56, top + 128, 52, 14,
                Component.literal("单价"));
        priceBox.setValue(String.valueOf(defaultPrice));
        priceBox.setMaxLength(9);
        priceBox.setTextColor(0xFFFFFF);
        addRenderableWidget(priceBox);
    }

    private int shares() {
        int v;
        try {
            v = Integer.parseInt(sharesBox.getValue().trim());
        } catch (Throwable t) {
            v = maxShares;
        }
        return Math.max(1, Math.min(maxShares, v));
    }

    private long price() {
        long v;
        try {
            v = Long.parseLong(priceBox.getValue().trim());
        } catch (Throwable t) {
            v = defaultPrice;
        }
        return Math.max(1L, Math.min(1_000_000_000L, v));
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k >= 1.0f) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            drawAll(g, mouseX, mouseY);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        g.fill(0, 0, this.width, this.height, 0x70000000);
        UiScale.push(g, left, top, W, H, k);
        try {
            int mx = (int) UiScale.mapX(mouseX, left, W, k);
            int my = (int) UiScale.mapY(mouseY, top, H, k);
            drawAll(g, mx, my);
            super.render(g, mx, my, partialTick);
        } finally {
            UiScale.pop(g);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 空实现：遮罩在 render() 里按屏幕坐标画
    }

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        String title = "\u4e0a \u67b6 \u80a1 \u7968";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + 46, INK,
                false);

        String sub = cut("#" + billId + " " + shipName, IN_R - IN_L);
        g.drawString(this.font, sub, left + IN_L, top + 62, INK_DIM, false);

        String have = "\u53ef\u5356 " + maxShares + " \u4efd\u3000\u5f53\u524d\u4efd\u503c "
                + defaultPrice;
        g.drawString(this.font, have, left + IN_L, top + 78, INK_DIM, false);

        // 份数
        int y = top + 104;
        g.drawString(this.font, "\u4efd\u6570", left + IN_L, y, INK, false);
        WoodRender.button(g, this.font, left + IN_L + 32, y - 2, 16, 14, "-", mouseX, mouseY,
                WoodRender.TEXT);
        WoodRender.button(g, this.font, left + IN_L + 112, y - 2, 16, 14, "+", mouseX, mouseY,
                WoodRender.TEXT);
        String maxHint = "\u6700\u591a " + maxShares;
        g.drawString(this.font, maxHint, left + IN_L + 134, y, INK_DIM, false);

        // 单价
        y = top + 130;
        g.drawString(this.font, "\u5355\u4ef7", left + IN_L, y, INK, false);
        WoodRender.button(g, this.font, left + IN_L + 32, y - 2, 16, 14, "-", mouseX, mouseY,
                WoodRender.TEXT);
        WoodRender.button(g, this.font, left + IN_L + 112, y - 2, 16, 14, "+", mouseX, mouseY,
                WoodRender.TEXT);
        String step = "\u6bcf\u6b21 \u00b110";
        g.drawString(this.font, step, left + IN_L + 134, y, INK_DIM, false);

        // 合计
        long total = (long) shares() * price();
        g.drawString(this.font, "\u5408\u8ba1 " + fmt(total) + " \u5510\u5154\u5e01",
                left + IN_L, top + 158, total > 0 ? GOOD : INK_DIM, false);
        String note = shares() > maxShares ? "\u8d85\u8fc7\u7968\u9762\u4efd\u6570" : "";
        if (!note.isEmpty()) {
            g.drawString(this.font, note, left + IN_L, top + 172, BAD, false);
        }

        button(g, "\u4e0a\u67b6", left + IN_L, top + 214, 64, mouseX, mouseY);
        button(g, "\u2190 \u8fd4\u56de", left + IN_R - 64, top + 232, 64, mouseX, mouseY);
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int mouseX, int mouseY) {
        WoodRender.button(g, this.font, x, y, w, 14, label, mouseX, mouseY, WoodRender.TEXT);
    }

    private String cut(String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (this.font.width(s) <= maxWidth) {
            return s;
        }
        return this.font.plainSubstrByWidth(s, Math.max(6, maxWidth - 6)) + "\u2026";
    }

    private static String fmt(long v) {
        if (v >= 1_000_000L) {
            return String.format("%.1fM", v / 1_000_000.0);
        }
        if (v >= 10_000L) {
            return String.format("%.1fK", v / 1000.0);
        }
        return String.valueOf(v);
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, H, k);
        }
        if (button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        int ix = (int) mx;
        int iy = (int) my;

        int ry = top + 102;
        if (hit(ix, iy, left + IN_L + 32, ry, 16, 14)) {
            sharesBox.setValue(String.valueOf(Math.max(1, shares() - 1)));
            return true;
        }
        if (hit(ix, iy, left + IN_L + 112, ry, 16, 14)) {
            sharesBox.setValue(String.valueOf(Math.min(maxShares, shares() + 1)));
            return true;
        }
        int py = top + 128;
        if (hit(ix, iy, left + IN_L + 32, py, 16, 14)) {
            priceBox.setValue(String.valueOf(Math.max(1L, price() - 10L)));
            return true;
        }
        if (hit(ix, iy, left + IN_L + 112, py, 16, 14)) {
            priceBox.setValue(String.valueOf(price() + 10L));
            return true;
        }
        if (hit(ix, iy, left + IN_L, top + 214, 64, 14)) {
            stall();
            return true;
        }
        if (hit(ix, iy, left + IN_R - 64, top + 232, 64, 14)) {
            Minecraft.getInstance().setScreen(new StockScreen(billId));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void stall() {
        int sh = shares();
        long p = price();
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new MenuNetwork.Action("/billvoyage stall " + p + " " + sh));
        } catch (Throwable ignored) {
            // ignore
        }
        Minecraft.getInstance().setScreen(null);
    }

    private static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
