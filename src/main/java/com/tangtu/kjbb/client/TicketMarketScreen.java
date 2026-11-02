package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 票据市场：提单 / 仓单 的买卖界面
 *
 * 上：在售列表（点行 → 打开票据详情，可买可撤）
 * 下：挂卖手持票据（填单价 → 挂卖）
 */
public class TicketMarketScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/trade_sheet.png");
    private static final int W = 320, H = 320;
    /** 纸张 + 下方背包/按钮的整块高度：缩放要按整块算，否则纸面缩进去了、页脚还在外面 */
    private static final int BLOCK_H = H + 8 + 3 * 18 + 6;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int UP = 0xFF2E7D32;

    private int left;
    private int top;
    private int page = 0;
    /** 0 = 票据，1 = 股票（份额挂单） */
    private int tab = 0;
    /** 股票页选中的挂单号 */
    private long selListing;
    private EditBox fieldPrice;

    private static final int ROWS = 5;
    /** 纸面可用区（trade_sheet 实测 29,38 – 293,284）：内容左/上原点、右界、列表首行 */
    private static final int IN_L = 44;
    private static final int IN_T = 44;
    private static final int IN_R = 288;
    private static final int LIST_Y = 70;
    private static final int ROW_H = 22;

    public TicketMarketScreen() {
        super(Component.literal("\u7968\u636e\u5e02\u573a"));
    }

    @Override
    protected void init() {
        super.init();
        this.left = this.width / 2 - W / 2;
        // 纸张上方留白，下方给背包。这里不夹到 4：屏幕不够高时由 UiScale 整体缩小并居中
        this.top = (this.height - BLOCK_H) / 2;
        try {
            var tm = Minecraft.getInstance().getTextureManager();
            var tex = tm.getTexture(TEX, new net.minecraft.client.renderer.texture.SimpleTexture(TEX));
            if (tex != null) {
                tex.setFilter(true, false);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        fieldPrice = new EditBox(this.font, left + IN_L + 78, top + 236, 70, 12,
                Component.literal("\u4e0b\u5355\u5355\u4ef7"));
        fieldPrice.setValue("100");
        fieldPrice.setMaxLength(9);
        fieldPrice.setBordered(false);
        fieldPrice.setTextColor(INK);
        addRenderableWidget(fieldPrice);

        com.tangtu.kjbb.net.MarketNetwork.requestTickets();
        // 股票页要用航运中心的份额挂单数据
        com.tangtu.kjbb.net.ShippingNetwork.sendRequest();
        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));
    }

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1254, 1254, 1254, 1254);

        String title = "\u7968 \u636e \u5e02 \u573a";
        g.drawString(this.font, title, left + IN_L, top + IN_T, INK, false);

        List<MarketCache.Ticket> list = MarketCache.tickets();
        int y = top + LIST_Y;

        // 页签：票据 / 股票
        tabButton(g, 0, "\u7968\u636e", mouseX, mouseY);
        tabButton(g, 1, "\u80a1\u7968", mouseX, mouseY);
        if (tab == 1) {
            fieldPrice.setVisible(false);
            drawStockTab(g, mouseX, mouseY);
            drawInventory(g, mouseX, mouseY);
            return;
        }
        fieldPrice.setVisible(true);

        g.drawString(this.font, "\u5728\u552e\uff08\u70b9\u4e00\u884c\u770b\u8be6\u60c5\uff09",
                left + IN_L, top + IN_T + 16, INK_DIM, false);

        int from = page * ROWS;
        if (list.isEmpty()) {
            g.drawString(this.font, "\uff08\u6ca1\u6709\u5728\u552e\u7968\u636e\uff09", left + IN_L, y,
                    INK_DIM, false);
        } else {
            for (int i = 0; i < ROWS && from + i < list.size(); i++) {
                var tk = list.get(from + i);
                int ry = y + i * ROW_H;
                boolean hover = mouseX >= left + IN_L - 4 && mouseX < left + IN_R + 4
                        && mouseY >= ry - 2 && mouseY < ry + 18;
                if (hover) {
                    g.fill(left + IN_L - 4, ry - 2, left + IN_R + 4, ry + 18, 0x30B08A50);
                }
                ItemStack icon = iconOf("BILL".equals(tk.kind())
                        ? "kjbankbridge:bill_of_lading" : "kjbankbridge:store_receipt");
                if (!icon.isEmpty()) {
                    g.renderItem(icon, left + IN_L, ry);
                }
                String kind = "BILL".equals(tk.kind()) ? "\u63d0\u5355" : "\u4ed3\u5355";
                g.drawString(this.font, "#" + tk.id() + "  " + kind, left + IN_L + 22, ry, INK,
                        false);
                String desc = tk.desc() == null ? "" : tk.desc();
                g.drawString(this.font, cut(desc, 120), left + IN_L + 22, ry + 9, INK_DIM, false);
                String price = tk.price() + " \u5510\u5154\u5e01";
                g.drawString(this.font, price, left + IN_R - this.font.width(price), ry, UP,
                        false);
            }
        }

        // 页码
        g.drawString(this.font, (page + 1) + "/" + Math.max(1, (list.size() + ROWS - 1) / ROWS),
                left + IN_L, top + 186, INK_DIM, false);
        drawNav(g, left + IN_L + 42, top + 182, 28, "<", mouseX, mouseY);
        drawNav(g, left + IN_L + 74, top + 182, 28, ">", mouseX, mouseY);

        // 挂卖区
        g.drawString(this.font, "\u6302\u5356\u624b\u6301\u7968\u636e", left + IN_L, top + 208,
                INK, false);
        g.drawString(this.font, "\u5355\u4ef7", left + IN_L + 42, top + 238, INK_DIM, false);
        g.fill(left + IN_L + 76, top + 235, left + IN_L + 150, top + 249, 0x30FFFFFF);
        g.fill(left + IN_L + 76, top + 249, left + IN_L + 150, top + 250, 0xFF9A7B4C);
        WoodRender.button(g, this.font, left + 198, top + 233, 85, 18,
                "\u6302\u5356", mouseX, mouseY, WoodRender.TEXT);
        g.drawString(this.font, "\u4e3b\u624b\u62ff\u7740\u7968\u636e\u65f6\u624d\u80fd\u6302\u5356",
                left + IN_L, top + 258, INK_DIM, false);

        // 返回（挪进纸面右下：原来在 top+282，整块压在木框上）
        WoodRender.button(g, this.font, left + 198, top + 258, 85, 18,
                "\u2190 \u8fd4\u56de", mouseX, mouseY, WoodRender.TEXT);

        drawInventory(g, mouseX, mouseY);
    }

    /** 页签按钮（标题右侧两个小木牌，避免压住标题） */
    private void tabButton(GuiGraphics g, int idx, String label, int mx, int my) {
        int x = left + IN_R - 94 + idx * 48;
        boolean on = tab == idx;
        WoodRender.button(g, this.font, x, top + 40, 46, 14, label, mx, my,
                on ? 0xFFFFE8B0 : WoodRender.TEXT);
    }

    /** 股票页：份额挂单列表 + 买下 / 撤单（原航运中心「市场」页搬过来） */
    private void drawStockTab(GuiGraphics g, int mouseX, int mouseY) {
        List<com.tangtu.kjbb.client.ShippingData.Listing> ls = ShippingData.listings();
        g.drawString(this.font, "\u4efd\u989d\u6302\u5355\uff08\u70b9\u4e00\u884c\u9009\u4e2d\uff09",
                left + IN_L, top + IN_T + 16, INK_DIM, false);

        String cnt = ls.size() + " \u5355";
        g.drawString(this.font, cnt, left + IN_R - this.font.width(cnt), top + IN_T + 16,
                INK_DIM, false);

        int y = top + LIST_Y;
        if (ls.isEmpty()) {
            g.drawString(this.font, "\uff08\u6ca1\u6709\u6302\u5355\uff09", left + IN_L, y, INK_DIM,
                    false);
        } else {
            if (selListing <= 0) {
                selListing = ls.get(0).id;
            }
            for (int i = 0; i < ls.size() && i < 5; i++) {
                var l = ls.get(i);
                int ry = y + i * ROW_H;
                boolean on = l.id == selListing;
                if (on) {
                    g.fill(left + IN_L - 4, ry - 2, left + IN_R + 4, ry + 18, 0x30B08A50);
                }
                String txt = "#" + l.id + "  \u8239" + l.billId + "  " + l.shares + " \u4efd";
                g.drawString(this.font, txt, left + IN_L, ry, INK, false);
                String price = "@" + l.price;
                g.drawString(this.font, price, left + IN_L + 150, ry, UP, false);
                String seller = cut(l.seller == null ? "" : l.seller, 90);
                g.drawString(this.font, seller, left + IN_R - this.font.width(seller), ry + 9,
                        INK_DIM, false);
            }
        }

        // 买下 / 撤单
        WoodRender.button(g, this.font, left + IN_L, top + 208, 64, 16, "\u4e70\u4e0b", mouseX,
                mouseY, WoodRender.TEXT);
        WoodRender.button(g, this.font, left + IN_L + 70, top + 208, 64, 16, "\u64a4\u5355",
                mouseX, mouseY, WoodRender.TEXT);
        g.drawString(this.font, "\u64a4\u5355\u53ea\u5bf9\u81ea\u5df1\u7684\u5355\u6709\u6548",
                left + IN_L + 140, top + 212, INK_DIM, false);
        WoodRender.button(g, this.font, left + 198, top + 258, 85, 18,
                "\u2190 \u8fd4\u56de", mouseX, mouseY, WoodRender.TEXT);
    }

    /** 羊皮纸下方的玩家背包（9×3 主背包 + 9 快捷栏）；票点一下就能挂卖 */
    private void drawInventory(GuiGraphics g, int mouseX, int mouseY) {
        var p = Minecraft.getInstance().player;
        if (p == null) {
            return;
        }
        int cell = 18;
        int gx = left + (W - cell * 9) / 2;
        int gy = top + H + 8;
        // 背景
        g.fill(gx - 3, gy - 3, gx + cell * 9 + 3, gy + cell * 3 + cell + 3, 0x66000000);

        var inv = p.getInventory();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int idx = 9 + row * 9 + col;          // 主背包 9..35
                int x = gx + col * cell;
                int y = gy + row * cell;
                g.fill(x, y, x + 16, y + 16, 0x30FFFFFF);
                ItemStack st = inv.getItem(idx);
                if (!st.isEmpty()) {
                    g.renderItem(st, x, y);
                    g.renderItemDecorations(this.font, st, x, y);
                }
            }
        }
        // 快捷栏
        int hy = gy + cell * 3 + 2;
        for (int col = 0; col < 9; col++) {
            int x = gx + col * cell;
            g.fill(x, hy, x + 16, hy + 16, 0x30FFFFFF);
            ItemStack st = inv.getItem(col);
            if (!st.isEmpty()) {
                g.renderItem(st, x, hy);
                g.renderItemDecorations(this.font, st, x, hy);
            }
            if (inv.selected == col) {
                g.fill(x - 1, hy - 1, x + 17, hy - 1 + 1, 0xFFB08A50);
                g.fill(x - 1, hy + 16, x + 17, hy + 17, 0xFFB08A50);
                g.fill(x - 1, hy - 1, x - 1 + 1, hy + 17, 0xFFB08A50);
                g.fill(x + 16, hy - 1, x + 17, hy + 17, 0xFFB08A50);
            }
        }
    }

    private void drawNav(GuiGraphics g, int x, int y, int w, String label, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? 0x805A3F1E : 0x40B08A50);
        int tw = this.font.width(label);
        g.drawString(this.font, label, x + (w - tw) / 2, y + 3, INK, false);
    }

    private String cut(String s, int maxW) {
        if (s == null) {
            return "";
        }
        if (this.font.width(s) <= maxW) {
            return s;
        }
        return this.font.plainSubstrByWidth(s, maxW - 6) + "\u2026";
    }

    private static ItemStack iconOf(String id) {
        try {
            var it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
            return it == null ? ItemStack.EMPTY : new ItemStack(it);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, W, BLOCK_H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, BLOCK_H, k);
        }
        if (button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        // 页签
        for (int i = 0; i < 2; i++) {
            int tx = left + IN_R - 94 + i * 48;
            if (mx >= tx && mx < tx + 46 && my >= top + 40 && my < top + 54) {
                tab = i;
                return true;
            }
        }

        if (tab == 1) {
            // 选行
            List<ShippingData.Listing> ls = ShippingData.listings();
            for (int i = 0; i < ls.size() && i < 5; i++) {
                int ry = top + LIST_Y + i * ROW_H;
                if (mx >= left + IN_L - 4 && mx < left + IN_R + 4 && my >= ry - 2 && my < ry + 18) {
                    selListing = ls.get(i).id;
                    return true;
                }
            }
            boolean bought = false;
            if (mx >= left + IN_L && mx < left + IN_L + 64 && my >= top + 208
                    && my < top + 224) {
                if (selListing > 0) {
                    send("/billvoyage stabuy " + selListing);
                }
                bought = true;
            }
            if (mx >= left + IN_L + 70 && mx < left + IN_L + 134 && my >= top + 208
                    && my < top + 224) {
                if (selListing > 0) {
                    send("/billvoyage stacancel " + selListing);
                }
                bought = true;
            }
            if (bought) {
                return true;
            }
            if (mx >= left + 198 && mx < left + 283 && my >= top + 258 && my < top + 276) {
                Minecraft.getInstance().setScreen(new FormCenterScreen());
                return true;
            }
            return super.mouseClicked(mx, my, button);
        }

        List<MarketCache.Ticket> list = MarketCache.tickets();
        int from = page * ROWS;
        for (int i = 0; i < ROWS && from + i < list.size(); i++) {
            int ry = top + LIST_Y + i * ROW_H;
            if (mx >= left + IN_L - 4 && mx < left + IN_R + 4 && my >= ry - 2 && my < ry + 18) {
                com.tangtu.kjbb.net.MarketNetwork.requestTicketDetail(list.get(from + i).id());
                return true;
            }
        }
        // 翻页
        if (my >= top + 182 && my < top + 196) {
            if (mx >= left + IN_L + 42 && mx < left + IN_L + 70) {
                page = Math.max(0, page - 1);
                return true;
            }
            if (mx >= left + IN_L + 74 && mx < left + IN_L + 102) {
                int pages = Math.max(1, (list.size() + ROWS - 1) / ROWS);
                page = Math.min(pages - 1, page + 1);
                return true;
            }
        }
        // 挂卖
        if (mx >= left + 198 && mx < left + 283 && my >= top + 233 && my < top + 251) {
            long price = 100L;
            try {
                price = Math.max(1L, Long.parseLong(fieldPrice.getValue().trim()));
            } catch (Throwable ignored) {
                // 用默认
            }
            send("/ticket list " + price);
            return true;
        }
        // 背包：点提单/仓单
        var p = Minecraft.getInstance().player;
        if (p != null) {
            int cell = 18;
            int gx = left + (W - cell * 9) / 2;
            int gy = top + H + 8;
            var inv = p.getInventory();
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    int idx = 9 + row * 9 + col;
                    if (hit(mx, my, gx + col * cell, gy + row * cell)) {
                        tryOpenSell(inv.getItem(idx), idx);
                        return true;
                    }
                }
            }
            int hy = gy + cell * 3 + 2;
            for (int col = 0; col < 9; col++) {
                if (hit(mx, my, gx + col * cell, hy)) {
                    tryOpenSell(inv.getItem(col), col);
                    return true;
                }
            }
        }
        // 返回
        if (mx >= left + 198 && mx < left + 283 && my >= top + 258 && my < top + 276) {
            Minecraft.getInstance().setScreen(new FormCenterScreen());
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private static boolean hit(double mx, double my, int x, int y) {
        return mx >= x && mx < x + 16 && my >= y && my < y + 16;
    }

    private void tryOpenSell(ItemStack st, int slot) {
        if (st == null || st.isEmpty()) {
            return;
        }
        String kind = com.tangtu.kjbb.TicketMarket.kindOf(st);
        if (kind == null) {
            hint("\u00a77\u8fd9\u4e0d\u662f\u7968\u636e");
            return;
        }

        Minecraft.getInstance().setScreen(new TicketSellScreen(st, slot));
    }

    private static void hint(String msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) {
            p.displayClientMessage(Component.literal(msg), false);
        }
    }

    private static void send(String cmd) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tangtu.kjbb.net.MenuNetwork.Action(cmd));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 整块（纸张 + 页脚）等比缩小；鼠标坐标同步换算，绘制与点击永远一致
        float k = UiScale.factor(this.width, this.height, W, BLOCK_H, 4);
        if (k >= 1.0f) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        g.fill(0, 0, this.width, this.height, 0x70000000);
        UiScale.push(g, left, top, W, BLOCK_H, k);
        try {
            super.render(g, (int) UiScale.mapX(mouseX, left, W, k),
                    (int) UiScale.mapY(mouseY, top, BLOCK_H, k), partialTick);
        } finally {
            UiScale.pop(g);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 空实现：1:1 时 render() 里显式调 super，缩放时改用屏幕坐标的遮罩
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
