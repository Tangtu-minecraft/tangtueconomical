package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 船只详情：把原来的「船只情况」和「航次详情（叙述）」合成一页。
 *
 * <p>从上到下：#id 船名 · 状态 · 到港日期 → 第 n/m 段进度 → 航次情况（叙述，按纸面宽度折行、
 * 超过 4 行可翻页）→ 船上货物（图标网格，超过 8 个可翻页）→ 持有人（前 2 位）+ 我的份额。
 *
 * <p>数据全读客户端缓存 {@link ShippingData}；倍率 / 船况不显示（数据包里也不下发）。
 */
public class VoyageShipScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_L = 43;
    private static final int IN_R = 283;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int GOOD = 0x2E5B2E;

    private static final int NARR_LINE_H = 10;
    private static final int NARR_LINES = 10;
    private static final int NARR_TOP = 88;
    private static final int WRAP_W = 232;

    private final long billId;
    private final List<String> narrative;
    private final List<String> wrapped = new ArrayList<>();
    private int left;
    private int top;
    private int narrPage;

    public VoyageShipScreen(long billId, List<String> narrative) {
        super(Component.literal("船只详情"));
        this.billId = billId;
        this.narrative = narrative == null ? List.of() : narrative;
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        if (wrapped.isEmpty()) {
            for (String p : narrative) {
                wrap(p);
                wrapped.add("");
            }
            if (!wrapped.isEmpty()) {
                wrapped.remove(wrapped.size() - 1);
            }
        }
    }

    /** 按像素宽度折行 */
    private void wrap(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (this.font.width(cur.toString()) >= WRAP_W) {
                cur.setLength(cur.length() - 1);
                if (cur.length() > 0) {
                    wrapped.add(cur.toString());
                }
                cur.setLength(0);
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            wrapped.add(cur.toString());
        }
    }

    private int narrPages() {
        return Math.max(1, (wrapped.size() + NARR_LINES - 1) / NARR_LINES);
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

        String title = "\u8239 \u53ea \u8be6 \u60c5";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + 46, INK,
                false);

        ShippingData.Bill b = ShippingData.bill(billId);
        if (b == null) {
            g.drawString(this.font, "\uff08\u8bfb\u4e0d\u5230\u8fd9\u8258\u8239\uff09", left + IN_L,
                    top + 70, INK_DIM, false);
            button(g, "\u2190 \u8fd4\u56de", left + IN_L, top + 232, 56, mouseX, mouseY);
            return;
        }

        String sub = "#" + b.id + " " + b.name + " \u00b7 " + statusText(b.status);
        g.drawString(this.font, cut(sub, IN_R - IN_L - 70), left + IN_L, top + 60, INK_DIM, false);
        String due = b.dueDay > 0 ? ("\u5230\u6e2f " + ShippingScreen.day(b.dueDay)) : "";
        g.drawString(this.font, due, left + IN_R - this.font.width(due), top + 60, INK_DIM, false);

        // 航次情况（叙述）：这页只讲这件事，段数 / 船上货物都不写
        g.drawString(this.font, "\u822a\u6b21\u60c5\u51b5", left + IN_L, top + 78, INK, false);
        int np = narrPages();
        int npi = Math.max(0, Math.min(narrPage, np - 1));
        if (np > 1) {
            String pn = (npi + 1) + "/" + np;
            g.drawString(this.font, pn, left + IN_R - 62, top + 78, INK_DIM, false);
            WoodRender.button(g, this.font, left + IN_R - 26, top + 76, 12, 11, "<", mouseX, mouseY,
                    WoodRender.TEXT);
            WoodRender.button(g, this.font, left + IN_R - 12, top + 76, 12, 11, ">", mouseX, mouseY,
                    WoodRender.TEXT);
        }
        int from = npi * NARR_LINES;
        for (int i = 0; i < NARR_LINES && from + i < wrapped.size(); i++) {
            String line = wrapped.get(from + i);
            if (!line.isEmpty()) {
                g.drawString(this.font, line, left + IN_L, top + NARR_TOP + i * NARR_LINE_H,
                        INK_DIM, false);
            }
        }
        if (wrapped.isEmpty()) {
            g.drawString(this.font, "\uff08\u6682\u65e0\u5e8f\u8ff0\uff09", left + IN_L,
                    top + NARR_TOP, INK_DIM, false);
        }

        // 持有人（两行）+ 我的份额
        g.drawString(this.font, "\u6301\u6709\u4eba", left + IN_L, top + 194, INK, false);
        if (b.mine > 0) {
            String mine = "\u6211\u6301\u6709 " + b.mine + " \u4efd";
            g.drawString(this.font, mine, left + IN_R - this.font.width(mine), top + 194, GOOD,
                    false);
        }
        List<ShippingData.Holder> hs = ShippingData.holdersOf(b.id);
        for (int i = 0; i < hs.size() && i < 2; i++) {
            ShippingData.Holder h = hs.get(i);
            int ry = top + 206 + i * 12;
            g.drawString(this.font, cut(h.name, 130), left + IN_L + 2, ry, INK, false);
            String s = h.shares + " \u4efd\uff08"
                    + (b.total > 0 ? h.shares * 100 / b.total : 0) + "%\uff09";
            g.drawString(this.font, s, left + IN_R - this.font.width(s), ry, INK_DIM, false);
        }
        if (hs.isEmpty()) {
            g.drawString(this.font, "\uff08\u6682\u65e0\uff09", left + IN_L + 2, top + 206, INK_DIM,
                    false);
        }

        button(g, "\u2190 \u8fd4\u56de", left + IN_L, top + 232, 56, mouseX, mouseY);
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


    private static String statusText(String s) {
        return switch (s) {
            case "OPEN" -> "\u52df\u8d44\u4e2d";
            case "VOYAGING" -> "\u822a\u884c\u4e2d";
            case "DUE" -> "\u5df2\u5230\u6e2f";
            case "CLOSED" -> "\u5df2\u7ed3\u675f";
            default -> s;
        };
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
        if (iy >= top + 232 && iy < top + 246) {
            if (ix >= left + IN_L && ix < left + IN_L + 56) {
                Minecraft.getInstance().setScreen(new ShippingScreen());
                return true;
            }
        }
        // 叙述翻页
        if (iy >= top + 76 && iy < top + 88) {
            if (ix >= left + IN_R - 26 && ix < left + IN_R - 14) {
                narrPage = Math.max(0, narrPage - 1);
                return true;
            }
            if (ix >= left + IN_R - 12 && ix < left + IN_R) {
                narrPage = Math.min(narrPages() - 1, narrPage + 1);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
