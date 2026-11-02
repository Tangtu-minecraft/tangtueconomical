package com.tangtu.kjbb.client;

import com.tangtu.kjbb.net.MenuNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 出资换份额：给「出钱」单独开一页——填份数，实时算出要付多少钱，
 * 再点「出资」；不想填就点「全买」把剩下的份一次吃下。
 *
 * 指令：{@code /billvoyage invest <金额>} / {@code /billvoyage buy <航次> <份数>}。
 */
public class InvestScreen extends Screen {

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
    private int left;
    private int top;
    private EditBox qtyBox;

    public InvestScreen(long billId) {
        super(Component.literal("出资换份额"));
        this.billId = billId;
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;

        ShippingData.Bill b = ShippingData.bill(billId);
        int remain = b == null ? 1 : Math.max(0, b.total - b.sold);
        int def = Math.max(1, Math.min(10, remain));

        qtyBox = new EditBox(this.font, left + IN_L + 46, top + 132, 60, 14,
                Component.literal("\u4efd\u6570"));
        qtyBox.setValue(String.valueOf(def));
        qtyBox.setMaxLength(8);
        qtyBox.setBordered(true);
        // 输入框自带深色底，字要用白色才看得清
        qtyBox.setTextColor(0xFFFFFF);
        addRenderableWidget(qtyBox);
    }

    private int shares() {
        try {
            return Math.max(1, Integer.parseInt(qtyBox.getValue().trim()));
        } catch (Throwable t) {
            return 1;
        }
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

        String title = "\u51fa \u8d44 \u6362 \u4efd \u989d";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + 52, INK,
                false);

        ShippingData.Bill b = ShippingData.bill(billId);
        if (b == null) {
            g.drawString(this.font, "\uff08\u8bfb\u4e0d\u5230\u8fd9\u8258\u8239\uff09", left + IN_L,
                    top + 80, INK_DIM, false);
            button(g, "\u2190 \u8fd4\u56de", left + IN_L, top + 232, 56, mouseX, mouseY);
            return;
        }

        int y = top + 76;
        g.drawString(this.font, cut("#" + b.id + " " + b.name, IN_R - IN_L), left + IN_L, y, INK,
                false);

        y += 16;
        String price = "\u5355\u4ef7 " + b.price + " \u00b7 \u5df2\u552e " + b.sold + "/" + b.total;
        g.drawString(this.font, price, left + IN_L, y, INK_DIM, false);

        y += 14;
        String pool = "\u622a\u6b62 " + ShippingScreen.day(b.deadline)
                + (b.dueDay > 0 ? (" \u00b7 \u5230\u6e2f " + ShippingScreen.day(b.dueDay)) : "");
        g.drawString(this.font, pool, left + IN_L, y, INK_DIM, false);

        y += 14;
        String mine = "\u6211\u6301\u6709 " + b.mine + " \u4efd";
        g.drawString(this.font, mine, left + IN_L, y, b.mine > 0 ? GOOD : INK_DIM, false);

        // 份数输入
        y += 22;
        g.drawString(this.font, "\u4efd\u6570", left + IN_L, y, INK, false);
        qtyBox.setX(left + IN_L + 46);
        qtyBox.setY(y - 3);

        // 需付
        int sh = shares();
        long cost = (long) sh * Math.max(1L, b.price);
        y += 26;
        g.drawString(this.font, "\u9700\u4ed8 " + fmt(cost) + " \u5510\u5154\u5e01", left + IN_L, y,
                INK, false);

        int left2 = Math.max(0, b.total - b.sold);
        y += 14;
        g.drawString(this.font, "\u5269\u4f59 " + left2 + " \u4efd\u53ef\u4e70", left + IN_L, y,
                left2 <= 0 ? BAD : INK_DIM, false);

        // 按钮
        int by = top + 200;
        button(g, "\u51fa\u8d44", left + IN_L, by, 54, mouseX, mouseY);
        button(g, "\u5168\u4e70", left + IN_L + 60, by, 54, mouseX, mouseY);
        button(g, "\u2190 \u8fd4\u56de", left + IN_R - 58, top + 232, 58, mouseX, mouseY);
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
        if (v >= 1000000L) {
            return String.format("%.1fM", v / 1000000.0);
        }
        if (v >= 10000L) {
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
        int by = top + 200;
        if (hit(ix, iy, left + IN_L, by, 54, 14)) {
            invest();
            return true;
        }
        if (hit(ix, iy, left + IN_L + 60, by, 54, 14)) {
            buyAll();
            return true;
        }
        if (hit(ix, iy, left + IN_R - 58, top + 232, 58, 14)) {
            Minecraft.getInstance().setScreen(new ShippingScreen());
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void invest() {
        ShippingData.Bill b = ShippingData.bill(billId);
        if (b == null) {
            return;
        }
        long cost = (long) shares() * Math.max(1L, b.price);
        action("/billvoyage invest " + cost);
    }

    private void buyAll() {
        ShippingData.Bill b = ShippingData.bill(billId);
        if (b == null) {
            return;
        }
        int left2 = Math.max(0, b.total - b.sold);
        if (left2 <= 0) {
            hint("\u00a77\u5df2\u7ecf\u5356\u5149\u4e86");
            return;
        }
        action("/billvoyage buy " + b.id + " " + left2);
    }

    private void action(String cmd) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new MenuNetwork.Action(cmd));
        } catch (Throwable ignored) {
            // ignore
        }
        Minecraft.getInstance().setScreen(new ShippingScreen());
    }

    private static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void hint(String msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) {
            p.displayClientMessage(Component.literal(msg), false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
