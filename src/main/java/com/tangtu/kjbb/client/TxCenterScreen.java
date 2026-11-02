package com.tangtu.kjbb.client;

import com.tangtu.kjbb.net.MenuNetwork;
import com.tangtu.kjbb.net.TxNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 交易中心：三个页签——
 * <ol>
 *   <li><b>我的挂单</b>：我自己还挂着的单，可撤单（货退背包）</li>
 *   <li><b>托管订单</b>：买卖双方都能看；卖家「发货」、买家「确认收货」；PENDING 显示自动退款倒计时</li>
 *   <li><b>投递记录</b>：最近 15 条「谁寄给谁、什么货、走物流还是排队」</li>
 * </ol>
 *
 * 数据走 {@code net/TxNetwork}；按钮全部复用已有指令，不新增业务逻辑。
 */
public class TxCenterScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_L = 43;
    private static final int IN_T = 45;
    private static final int IN_R = 283;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int GOOD = 0x2E5B2E;
    private static final int WARN = 0xFF9A5A00;
    private static final int SHIP_COLOR = 0xFF1E6FD9;
    private static final int BAD = 0x9B2C2C;

    private static final String[] TABS = {"\u6211\u7684\u6302\u5355", "\u6258\u7ba1\u8ba2\u5355", "\u6295\u9012\u8bb0\u5f55"};
    private static final int TAB_W = 66;
    private static final int TAB_GAP = 2;
    private static final int TABS_Y = 62;
    private static final int LIST_Y = 100;
    private static final int ROW_H = 14;
    /** 投递记录一页几行（下面要留详情区） */
    private static final int MAIL_ROWS = 5;

    /** 卖家发货期限，与 TradeOrder.SHIP_DEADLINE_MS 一致 */
    private static final long SHIP_DEADLINE_MS = 30L * 60_000L;

    private final int page;
    private int left;
    private int top;
    private long selOffer;
    private long selOrder;
    private final MailPanel mailPanel = new MailPanel();

    public TxCenterScreen() {
        this(0);
    }

    public TxCenterScreen(int page) {
        super(Component.literal("\u4ea4\u6613\u4e2d\u5fc3"));
        this.page = Math.max(0, Math.min(2, page));
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        TxNetwork.sendRequest();
    }

    private static String me() {
        try {
            var p = Minecraft.getInstance().player;
            return p == null ? "" : p.getScoreboardName();
        } catch (Throwable t) {
            return "";
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

        String title = "\u4ea4 \u6613 \u4e2d \u5fc3";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + IN_T,
                INK, false);

        // 提货点状态：放在页签下面一行右侧，避免压住标题和红绸带
        String addr = TxData.address();
        String bind = addr == null || addr.isEmpty()
                ? "\u63d0\u8d27\u70b9\uff1a\u672a\u7ed1\u5b9a"
                : "\u63d0\u8d27\u70b9\uff1a" + addr;
        g.drawString(this.font, bind, left + IN_R - this.font.width(bind), top + TABS_Y + 16,
                addr == null || addr.isEmpty() ? BAD : INK_DIM, false);

        // 页签
        int total = TABS.length * TAB_W + (TABS.length - 1) * TAB_GAP;
        int x0 = left + (W - total) / 2;
        for (int i = 0; i < TABS.length; i++) {
            int x = x0 + i * (TAB_W + TAB_GAP);
            boolean on = i == page;
            WoodRender.plate(g, x, top + TABS_Y, TAB_W, 14, on);
            g.drawString(this.font, TABS[i],
                    x + (TAB_W - this.font.width(TABS[i])) / 2, top + TABS_Y + 3,
                    on ? WoodRender.TEXT : 0xFFC9B79A, false);
        }

        switch (page) {
            case 0 -> drawOffers(g, mouseX, mouseY);
            case 1 -> drawOrders(g, mouseX, mouseY);
            default -> mailPanel.draw(g, this.font, left + IN_L, top + LIST_Y, IN_R - IN_L,
                    MAIL_ROWS, ROW_H, top + 180, mouseX, mouseY);
        }

        button(g, "\u5237\u65b0", left + IN_L, top + 232, 46, mouseX, mouseY);
        button(g, "\u2190 \u8fd4\u56de", left + IN_R - 58, top + 232, 58, mouseX, mouseY);
    }

    // ---------------------------------------------------------------- 我的挂单

    private void drawOffers(GuiGraphics g, int mouseX, int mouseY) {
        List<TxData.Offer> list = TxData.offers();
        g.drawString(this.font, "\u6211\u7684\u6302\u5355", left + IN_L, top + LIST_Y - 12, INK,
                false);
        String cnt = list.size() + " \u5355";
        g.drawString(this.font, cnt, left + IN_R - this.font.width(cnt), top + LIST_Y - 12,
                INK_DIM, false);

        if (list.isEmpty()) {
            g.drawString(this.font, "\uff08\u6ca1\u6709\u5728\u552e\u7684\u6302\u5355\uff09",
                    left + IN_L + 4, top + LIST_Y, INK_DIM, false);
        } else {
            for (int i = 0; i < list.size() && i < 7; i++) {
                TxData.Offer o = list.get(i);
                int ry = top + LIST_Y + i * ROW_H;
                boolean on = o.id == selOffer;
                if (on) {
                    g.fill(left + IN_L, ry - 1, left + IN_R, ry + 12, 0x30B08A50);
                }
                icon(g, o.item, left + IN_L, ry - 1);
                g.drawString(this.font, "#" + o.id, left + IN_L + 20, ry, on ? INK : INK_DIM,
                        false);
                g.drawString(this.font, cut(nameOf(o.item), 96), left + IN_L + 58, ry, INK,
                        false);
                String right = "\u00d7" + o.count + " @" + o.price;
                g.drawString(this.font, right, left + IN_R - this.font.width(right), ry, INK_DIM,
                        false);
            }
        }

        String total = "\u5408\u8ba1\u5e02\u503c " + fmt(TxData.offersValue()) + " \u5510\u5154\u5e01";
        g.drawString(this.font, total, left + IN_L, top + 202, INK, false);

        // 撤单（下方一行：按钮 + 说明，说明缩到 8 个字以内，避免越界）
        boolean canCancel = selOffer > 0;
        WoodRender.plate(g, left + IN_L, top + 214, 56, 14, false);
        g.drawString(this.font, "\u64a4\u5355",
                left + IN_L + (56 - this.font.width("\u64a4\u5355")) / 2, top + 217,
                canCancel ? WoodRender.TEXT : 0xFF8A7A60, false);
        g.drawString(this.font, "\u64a4\u5355\u540e\u8d27\u9000\u56de\u80cc\u5305",
                left + IN_L + 64, top + 217, 0x9A8A70, false);
    }

    // ---------------------------------------------------------------- 托管订单

    private void drawOrders(GuiGraphics g, int mouseX, int mouseY) {
        List<TxData.Order> list = TxData.orders();
        g.drawString(this.font, "\u6258\u7ba1\u8ba2\u5355", left + IN_L, top + LIST_Y - 12, INK,
                false);
        String cnt = list.size() + " \u5355";
        g.drawString(this.font, cnt, left + IN_R - this.font.width(cnt), top + LIST_Y - 12,
                INK_DIM, false);

        if (list.isEmpty()) {
            g.drawString(this.font, "\uff08\u6ca1\u6709\u6258\u7ba1\u4e2d\u7684\u8ba2\u5355\uff09",
                    left + IN_L + 4, top + LIST_Y, INK_DIM, false);
            return;
        }
        for (int i = 0; i < list.size() && i < 6; i++) {
            TxData.Order t = list.get(i);
            int ry = top + LIST_Y + i * ROW_H;
            boolean on = t.id == selOrder;
            if (on) {
                g.fill(left + IN_L, ry - 1, left + IN_R, ry + 12, 0x30B08A50);
            }
            g.drawString(this.font, "#" + t.id, left + IN_L, ry, on ? INK : INK_DIM, false);
            g.drawString(this.font, cut(nameOf(t.item), 66), left + IN_L + 36, ry, INK, false);
            g.drawString(this.font, "\u00d7" + t.count + " @" + t.unit, left + IN_L + 108, ry,
                    INK_DIM, false);
            g.drawString(this.font, cut(t.other, 58), left + IN_L + 170, ry, INK_DIM, false);
            String st = statusText(t.status);
            g.drawString(this.font, st, left + IN_R - this.font.width(st), ry,
                    statusColor(t.status), false);
        }

        // 选中单的操作
        TxData.Order sel = findOrder(selOrder);
        int by = top + 206;
        if (sel == null) {
            g.drawString(this.font, "\u70b9\u4e00\u884c\u9009\u4e2d\u8ba2\u5355", left + IN_L,
                    by + 3, INK_DIM, false);
            return;
        }
        String mine = me();
        boolean isSeller = mine.equalsIgnoreCase(sel.other) ? false : true;
        // 对手方是别人：seller 判定要看托管单本身，这里按状态与指令回执兜底
        boolean canShip = "PENDING".equals(sel.status);
        boolean canConfirm = "SHIPPED".equals(sel.status);

        WoodRender.plate(g, left + IN_L, by, 58, 14, false);
        g.drawString(this.font, "\u53d1\u8d27", left + IN_L + (58 - this.font.width("\u53d1\u8d27")) / 2,
                by + 3, canShip ? WoodRender.TEXT : 0xFF8A7A60, false);
        WoodRender.plate(g, left + IN_L + 64, by, 74, 14, false);
        g.drawString(this.font, "\u786e\u8ba4\u6536\u8d27",
                left + IN_L + 64 + (74 - this.font.width("\u786e\u8ba4\u6536\u8d27")) / 2, by + 3,
                canConfirm ? WoodRender.TEXT : 0xFF8A7A60, false);

        if ("PENDING".equals(sel.status)) {
            long now = TxData.now();
            long leftMs = now <= 0L ? -1L : (sel.created + SHIP_DEADLINE_MS - now);
            String tip = leftMs < 0L ? "\u7b49\u5f85\u670d\u52a1\u5668\u65f6\u949f\u2026"
                    : ("\u5269\u4f59 " + mmss(leftMs) + " \u81ea\u52a8\u9000\u6b3e");
            g.drawString(this.font, tip, left + IN_L + 146, by + 3, WARN, false);
        } else if ("SHIPPED".equals(sel.status)) {
            g.drawString(this.font, "\u5df2\u53d1\u8d27\uff0c\u7b49\u4e70\u5bb6\u786e\u8ba4",
                    left + IN_L + 146, by + 3, SHIP_COLOR, false);
        }
    }

    // ---------------------------------------------------------------- 投递记录


    /** 投递详情：整条记录 + 这条货对我意味着什么 */

    // ---------------------------------------------------------------- 小工具

    private void button(GuiGraphics g, String label, int x, int y, int w, int mouseX, int mouseY) {
        WoodRender.button(g, this.font, x, y, w, 14, label, mouseX, mouseY, WoodRender.TEXT);
    }

    /** 小翻页牌 */
    private void icon(GuiGraphics g, String id, int x, int y) {
        ItemStack st = iconOf(id);
        if (!st.isEmpty()) {
            g.renderItem(st, x, y);
            g.renderItemDecorations(this.font, st, x, y);
        }
    }

    private static ItemStack iconOf(String id) {
        try {
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
            return it == null ? ItemStack.EMPTY : new ItemStack(it);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static String nameOf(String id) {
        try {
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
            if (it != null) {
                return new ItemStack(it).getHoverName().getString();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return id == null ? "" : id;
    }

    private String cut(String s, int maxW) {
        if (s == null) {
            return "";
        }
        if (this.font.width(s) <= maxW) {
            return s;
        }
        return this.font.plainSubstrByWidth(s, Math.max(6, maxW - 6)) + "\u2026";
    }

    private static String statusText(String st) {
        return switch (st) {
            case "PENDING" -> "\u5f85\u53d1\u8d27";
            case "SHIPPED" -> "\u5df2\u53d1\u8d27";
            case "DONE" -> "\u5df2\u5b8c\u6210";
            case "CANCELLED" -> "\u5df2\u53d6\u6d88";
            default -> st == null ? "" : st;
        };
    }

    private static int statusColor(String st) {
        return switch (st) {
            case "PENDING" -> WARN;
            case "SHIPPED" -> SHIP_COLOR;
            case "DONE" -> GOOD;
            default -> INK_DIM;
        };
    }

    private static String mmss(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return (s / 60) + ":" + String.format("%02d", s % 60);
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

    private TxData.Order findOrder(long id) {
        for (TxData.Order o : TxData.orders()) {
            if (o.id == id) {
                return o;
            }
        }
        return null;
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

        // 页签
        int total = TABS.length * TAB_W + (TABS.length - 1) * TAB_GAP;
        int x0 = left + (W - total) / 2;
        for (int i = 0; i < TABS.length; i++) {
            int x = x0 + i * (TAB_W + TAB_GAP);
            if (ix >= x && ix < x + TAB_W && iy >= top + TABS_Y && iy < top + TABS_Y + 14) {
                Minecraft.getInstance().setScreen(new TxCenterScreen(i));
                return true;
            }
        }

        if (page == 0) {
            List<TxData.Offer> list = TxData.offers();
            for (int i = 0; i < list.size() && i < 7; i++) {
                int ry = top + LIST_Y + i * ROW_H;
                if (iy >= ry - 1 && iy < ry + 12) {
                    selOffer = list.get(i).id;
                    return true;
                }
            }
            if (ix >= left + IN_L && ix < left + IN_L + 56 && iy >= top + 214
                    && iy < top + 228) {
                if (selOffer > 0) {
                    send("/billoffer cancel " + selOffer);
                    selOffer = 0;
                }
                return true;
            }
        } else if (page == 1) {
            List<TxData.Order> list = TxData.orders();
            for (int i = 0; i < list.size() && i < 6; i++) {
                int ry = top + LIST_Y + i * ROW_H;
                if (iy >= ry - 1 && iy < ry + 12) {
                    selOrder = list.get(i).id;
                    return true;
                }
            }
            TxData.Order sel = findOrder(selOrder);
            if (sel != null) {
                if (ix >= left + IN_L && ix < left + IN_L + 58 && iy >= top + 206
                        && iy < top + 220) {
                    if ("PENDING".equals(sel.status)) {
                        send("/billtrade ship " + sel.id);
                    }
                    return true;
                }
                if (ix >= left + IN_L + 64 && ix < left + IN_L + 138 && iy >= top + 206
                        && iy < top + 220) {
                    if ("SHIPPED".equals(sel.status)) {
                        send("/billtrade confirm " + sel.id);
                    }
                    return true;
                }
            }
        } else {
            // 投递记录：选行看详情 + 翻页（共用 MailPanel）
            if (mailPanel.click(mx, my, left + IN_L, top + LIST_Y, IN_R - IN_L,
                    MAIL_ROWS, ROW_H, this.font)) {
                return true;
            }
        }

        if (hit(ix, iy, left + IN_L, top + 232, 46, 14)) {
            TxNetwork.sendRequest();
            return true;
        }
        if (hit(ix, iy, left + IN_R - 58, top + 232, 58, 14)) {
            Minecraft.getInstance().setScreen(new FormCenterScreen());
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void send(String cmd) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new MenuNetwork.Action(cmd));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
