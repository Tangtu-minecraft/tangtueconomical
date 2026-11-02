package com.tangtu.kjbb.client;

import com.tangtu.kjbb.net.MenuNetwork;
import com.tangtu.kjbb.net.ShippingNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 航运中心：在招 / 航行 / 历史 / 罗马国 / 包船，五个页签。
 *
 * 入口挂在「远洋提单」的船票页（底部「航运」按钮）；
 * 数据走 {@code net/ShippingNetwork}（服务端现算），动作全部复用已有指令
 * （/billvoyage contribute|invest|charter|sail|abandon、/billroma sell|want|cancel），
 * 所以这个界面不新增任何服务端业务逻辑。
 */
public class ShippingScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;

    /** 纸面可用区（form_sheet 实测 43,45 – 283,246） */
    private static final int IN_L = 43;
    private static final int IN_T = 45;
    private static final int IN_R = 283;
    private static final int IN_B = 246;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int GOOD = 0x2E5B2E;
    private static final int BAD = 0xB03030;
    private static final int BAR = 0xFF8A6A3A;

    private static final String[] TABS = {
            "\u5728\u62db", "\u822a\u884c", "\u5386\u53f2", "\u76ee\u7684\u5730", "\u5305\u8239"};
    private static final int TAB_W = 46;
    private static final int TAB_GAP = 2;
    private static final int TABS_Y = 60;

    /** 目的地：目前只有罗马开着，后面三个位置留给以后 */
    private static final String[] DESTINATIONS = {
            "\u7f57\u9a6c", "\u5f85\u5f00", "\u5f85\u5f00", "\u5f85\u5f00"};
    private static final int DEST_Y = 78;
    private static final int DEST_W = 56;
    private static final int DEST_H = 14;
    private static final int DEST_GAP = 2;

    /** 目的地页的纵向锚点（绘制和点击共用） */
    private static final int D_ORDERS_Y = 122;
    private static final int D_GOODS_Y = 158;
    private static final int D_GOOD_ROW = 170;
    private static final int D_QTY_Y = 184;
    private static final int D_HINT_Y = 202;

    /** 内容起点 / 行高 */
    private static final int BODY_Y = 80;
    private static final int ROW_H = 11;

    /** 载货清单：纯图标网格，每页 8 个，右侧翻页（数量用图标右下角的堆叠数字） */
    private static final int CARGO_PER_PAGE = 8;
    private static final int CARGO_SLOT = 30;
    private static final int CARGO_Y = 130;
    private static final int CARGO_HEAD_Y = 116;

    private int left;
    private int top;
    private int page;
    private int cargoPageOpen;
    private int cargoPageVoy;

    /** 在招页：按船号记住选中的航次（0 = 用第一条 OPEN） */
    private long selBill;
    /** 市场页选中的挂单 */
    private long selListing;
    /** 目的地页：选中的目的地（0 = 罗马，其余为预留位） */
    private int dest;
    /** 罗马页：选中的品类下标 / 订单号 */
    private int goodIndex;
    private long selOrder;

    private EditBox qtyBox;

    public ShippingScreen() {
        super(Component.literal("航运中心"));
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;

        qtyBox = new EditBox(this.font, left + IN_L + 22, top + 211, 38, 12,
                Component.literal("\u4efd\u6570"));
        qtyBox.setValue("10");
        qtyBox.setMaxLength(6);
        qtyBox.setBordered(false);
        qtyBox.setTextColor(INK);
        addRenderableWidget(qtyBox);

        ShippingNetwork.sendRequest();
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
        // 空实现：遮罩在 render() 里按屏幕坐标画，否则会被缩放一起缩掉
    }

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        // 输入框只剩包船页要用（在招的出货/出钱已各自单开一页）
        qtyBox.visible = false;   // 在招/目的地/包船都不再用内联数量框
        qtyBox.setX(left + IN_L + (page == 4 ? 48 : 22));
        qtyBox.setY(top + (page == 0 ? 211 : (page == 3 ? D_QTY_Y + 1 : 191)));

        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        String title = "\u822a \u8fd0 \u4e2d \u5fc3";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + IN_T,
                INK, false);

        drawTabs(g, mouseX, mouseY);

        if (ShippingData.empty()) {
            g.drawString(this.font, "\u6b63\u5728\u8bfb\u53d6\u2026\u2026", left + IN_L + 6,
                    top + 92, INK_DIM, false);
        } else if (!ShippingData.error().isEmpty()) {
            g.drawString(this.font, cut("\u00a7c" + ShippingData.error(), IN_R - IN_L),
                    left + IN_L + 6, top + 92, BAD, false);
        } else {
            switch (page) {
                case 0 -> drawOpen(g, mouseX, mouseY);
                case 1 -> drawVoyaging(g, mouseX, mouseY);
                case 2 -> drawHistory(g);
                case 3 -> drawRoma(g, mouseX, mouseY);
                default -> drawCharter(g, mouseX, mouseY);
            }
        }

        button(g, "\u5237\u65b0", left + IN_L, top + 232, 46, mouseX, mouseY);
        button(g, "\u2190 \u8fd4\u56de", left + IN_R - 58, top + 232, 58, mouseX, mouseY);
    }

    private void drawTabs(GuiGraphics g, int mouseX, int mouseY) {
        int total = TABS.length * TAB_W + (TABS.length - 1) * TAB_GAP;
        int x = left + (W - total) / 2;
        for (int i = 0; i < TABS.length; i++) {
            boolean on = page == i;
            boolean hover = mouseX >= x && mouseX < x + TAB_W
                    && mouseY >= top + TABS_Y && mouseY < top + TABS_Y + 14;
            // 页签也是木制按钮：选中/悬停都切到 hover 贴图
            WoodRender.plate(g, x, top + TABS_Y, TAB_W, 14, on || hover);
            g.drawString(this.font, TABS[i], x + (TAB_W - this.font.width(TABS[i])) / 2,
                    top + TABS_Y + 3, WoodRender.TEXT, false);
            x += TAB_W + TAB_GAP;
        }
    }

    // ---------------------------------------------------------------- 在招

    private void drawOpen(GuiGraphics g, int mouseX, int mouseY) {
        ShippingData.Bill b = currentOpen();
        if (b == null) {
            g.drawString(this.font, "\uff08\u6ca1\u6709\u5728\u62db\u7684\u822a\u6b21\uff09",
                    left + IN_L, top + BODY_Y, INK_DIM, false);
            g.drawString(this.font, "\u53ef\u4ee5\u53bb\u300c\u5305\u8239\u300d\u9875\u81ea\u5df1\u5f00\u4e00\u8d9f",
                    left + IN_L, top + BODY_Y + 14, INK_DIM, false);
            return;
        }

        int y = top + BODY_Y;
        g.drawString(this.font, cut("#" + b.id + " " + b.name, 170), left + IN_L, y, INK, false);
        String dl = "\u622a\u6b62 " + day(b.deadline);
        g.drawString(this.font, dl, left + IN_R - this.font.width(dl), y, INK_DIM, false);

        // 份数进度条
        y += 12;
        int barW = 150;
        g.fill(left + IN_L, y + 3, left + IN_L + barW, y + 8, 0x305A3F1E);
        if (b.total > 0) {
            int w = Math.min(barW, (int) ((long) barW * b.sold / b.total));
            g.fill(left + IN_L, y + 3, left + IN_L + w, y + 8, BAR);
        }
        String cnt = b.sold + "/" + b.total;
        g.drawString(this.font, cnt, left + IN_R - this.font.width(cnt), y, INK, false);

        y += 14;
        String info = "\u4ef7 " + b.price + " \u00b7 \u6211 " + b.mine + " \u4efd \u00b7 \u7d2f\u8ba1 "
                + fmt(b.sold * b.price);
        g.drawString(this.font, cut(info, IN_R - IN_L), left + IN_L, y, INK_DIM, false);

        // 载货清单：图标网格 + 上下页
        y += 12;
        drawCargo(g, b.id, cargoPageOpen, true, mouseX, mouseY);

        // 份额分布（一行压缩）
        int hy = top + 160;
        g.drawString(this.font, "\u4efd\u989d\u5206\u5e03", left + IN_L, hy, INK, false);
        List<ShippingData.Holder> hs = ShippingData.holdersOf(b.id);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hs.size() && i < 4; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            sb.append(shortName(hs.get(i).name)).append(' ')
                    .append(b.total > 0 ? (hs.get(i).shares * 100 / b.total) : 0).append('%');
        }
        g.drawString(this.font, hs.isEmpty() ? "\uff08\u6682\u65e0\uff09" : cut(sb.toString(), 180),
                left + IN_L + 54, hy, INK_DIM, false);

        // 出货 / 出钱：各自单开一页（出货 = 背包界面，出钱 = 填份数算总价）
        int by = top + 190;
        button(g, "\u51fa\u8d27\u6362\u4efd\u989d", left + IN_L, by, 76, mouseX, mouseY);
        button(g, "\u51fa\u94b1\u6362\u4efd\u989d", left + IN_L + 82, by, 76, mouseX, mouseY);
        g.drawString(this.font, "\u51fa\u8d27\uff1a\u6253\u5f00\u80cc\u5305\u9009\u8d27",
                left + IN_L, by + 18, INK_DIM, false);
        g.drawString(this.font, "\u51fa\u94b1\uff1a\u5355\u4ef7 " + b.price + " \u00d7 \u4efd\u6570",
                left + IN_L, by + 28, INK_DIM, false);
    }

    /** selBill 选中的 OPEN 船；没有就返回 null */
    private ShippingData.Bill pickBill(String ignored) {
        if (selBill > 0) {
            ShippingData.Bill b = ShippingData.bill(selBill);
            if (b != null && "OPEN".equals(b.status)) {
                return b;
            }
        }
        return null;
    }

    private ShippingData.Bill currentOpen() {
        ShippingData.Bill b = pickBill("");
        return b != null ? b : ShippingData.first("OPEN");
    }

    // ---------------------------------------------------------------- 航行中

    private void drawVoyaging(GuiGraphics g, int mouseX, int mouseY) {
        List<ShippingData.Bill> list = ShippingData.bills("VOYAGING");
        int y = top + BODY_Y;
        g.drawString(this.font, "\u5728\u822a\u8239\u53ea", left + IN_L, y, INK, false);
        String cnt = list.size() + " \u8258 \u00b7 \u70b9\u4e00\u884c\u770b\u8be6\u60c5";
        g.drawString(this.font, cnt, left + IN_R - this.font.width(cnt), y, INK_DIM, false);

        y += 13;
        if (list.isEmpty()) {
            g.drawString(this.font, "\uff08\u6ca1\u6709\u5728\u822a\u7684\u8239\uff09", left + IN_L + 4, y,
                    INK_DIM, false);
            return;
        }
        for (int i = 0; i < list.size() && i < 7; i++) {
            ShippingData.Bill b = list.get(i);
            boolean on = b.id == selBill;
            int ry = y + i * 13;
            if (on) {
                g.fill(left + IN_L, ry - 1, left + IN_R, ry + 11, 0x30B08A50);
            }
            g.drawString(this.font, cut("#" + b.id + " " + b.name, 150), left + IN_L, ry,
                    on ? INK : INK_DIM, false);
            String right = "\u7b2c " + b.legDone + "/" + b.legs + " \u6bb5"
                    + (b.dueDay > 0 ? (" \u00b7 \u5230\u6e2f " + day(b.dueDay)) : "");
            g.drawString(this.font, right, left + IN_R - this.font.width(right), ry, INK_DIM,
                    false);
        }

        g.drawString(this.font, "\u70b9\u8239\u540d\u8fdb\u5165\u8239\u53ea\u8be6\u60c5",
                left + IN_L, top + 210, 0x9A8A70, false);
    }

    /**
     * 载货清单：图标 + 数量，每页 5 个，右上角翻页。
     * @param progress true = 在招页，图标下显示「已收/需求」；false = 航行页，显示「×已装」
     */
    private void drawCargo(GuiGraphics g, long billId, int pageIdx, boolean progress,
                           int mouseX, int mouseY) {
        List<ShippingData.Line> lines = ShippingData.linesOf(billId);
        int pages = Math.max(1, (lines.size() + CARGO_PER_PAGE - 1) / CARGO_PER_PAGE);
        int p = Math.max(0, Math.min(pageIdx, pages - 1));

        g.drawString(this.font, progress ? "\u8f7d\u8d27\u6e05\u5355" : "\u8239\u4e0a\u8d27\u7269",
                left + IN_L, top + CARGO_HEAD_Y, INK, false);

        String pn = (p + 1) + "/" + pages;
        int arrowY = top + CARGO_HEAD_Y - 2;
        g.drawString(this.font, pn, left + IN_R - 62, top + CARGO_HEAD_Y, INK_DIM, false);
        WoodRender.button(g, this.font, left + IN_R - 26, arrowY, 12, 11, "<", mouseX, mouseY,
                WoodRender.TEXT);
        WoodRender.button(g, this.font, left + IN_R - 12, arrowY, 12, 11, ">", mouseX, mouseY,
                WoodRender.TEXT);

        int from = p * CARGO_PER_PAGE;
        for (int i = 0; i < CARGO_PER_PAGE && from + i < lines.size(); i++) {
            ShippingData.Line l = lines.get(from + i);
            int cx = left + IN_L + i * CARGO_SLOT + (CARGO_SLOT - 16) / 2;
            int cy = top + CARGO_Y;
            ItemStack icon = iconOf(l.item);
            if (!icon.isEmpty()) {
                ItemStack shown = icon.copy();
                shown.setCount(Math.max(1, progress ? l.need : l.have));
                g.renderItem(shown, cx, cy);
                g.renderItemDecorations(this.font, shown, cx, cy);
            }
        }
    }

    // ---------------------------------------------------------------- 历史

    private void drawHistory(GuiGraphics g) {
        int y = top + BODY_Y;
        g.drawString(this.font, "\u8239\u53f7", left + IN_L, y, INK_DIM, false);
        String h2 = "\u7ed3\u679c / \u8d27\u6b3e / \u5173\u7a0e";
        g.drawString(this.font, h2, left + IN_R - this.font.width(h2), y, INK_DIM, false);

        List<ShippingData.Bill> closed = ShippingData.bills("CLOSED");
        y += 12;
        if (closed.isEmpty()) {
            g.drawString(this.font, "\uff08\u8fd8\u6ca1\u6709\u7ed3\u675f\u7684\u822a\u6b21\uff09",
                    left + IN_L, y, INK_DIM, false);
            return;
        }
        int shown = 0;
        for (int i = closed.size() - 1; i >= 0 && shown < 7; i--, shown++) {
            ShippingData.Bill b = closed.get(i);
            int ry = y + shown * 12;
            String outcome = b.outcome == null || b.outcome.isEmpty() ? "\u2014" : b.outcome;
            int color = "\u5b89\u5168".equals(outcome) || "\u987a\u98ce".equals(outcome)
                    ? GOOD : ("\u6c89\u6ca1".equals(outcome) ? BAD : INK_DIM);
            g.drawString(this.font, "#" + b.id + " " + shortName(b.name), left + IN_L, ry, INK,
                    false);
            String right = outcome + "  " + fmt(b.revenue) + "  \u7a0e " + fmt(b.tariff);
            g.drawString(this.font, right, left + IN_R - this.font.width(right), ry, color, false);
        }
        int by = top + 200;
        g.drawString(this.font, "\u5173\u7a0e = \u51fa\u53e3 " + ShippingData.tariff() + "\u2030 + \u8fd4\u7a0b "
                + ShippingData.importTariff() + "\u2030", left + IN_L, by, INK_DIM, false);
    }

    // ---------------------------------------------------------------- 罗马国

    private void drawRoma(GuiGraphics g, int mouseX, int mouseY) {
        // 目的地选择条：罗马 + 三个预留位（后面开新目的地就往空位里填）
        for (int i = 0; i < DESTINATIONS.length; i++) {
            int x = left + IN_L + i * (DEST_W + DEST_GAP);
            boolean on = i == dest;
            boolean hover = mouseX >= x && mouseX < x + DEST_W
                    && mouseY >= top + DEST_Y && mouseY < top + DEST_Y + DEST_H;
            WoodRender.plate(g, x, top + DEST_Y, DEST_W, DEST_H, on || hover);
            g.drawString(this.font, DESTINATIONS[i],
                    x + (DEST_W - this.font.width(DESTINATIONS[i])) / 2, top + DEST_Y + 3,
                    i == 0 ? WoodRender.TEXT : 0xFFC9B79A, false);
        }

        int y = top + 96;
        String fx = "\u884c\u60c5 " + ShippingData.fx() + "\u2030";
        g.drawString(this.font, fx, left + IN_L, y, INK, false);
        String cash = "\u7f57\u9a6c\u56db\u5e93 " + fmt(ShippingData.romaCash());
        g.drawString(this.font, cash, left + IN_R - this.font.width(cash), y, INK_DIM, false);

        y += 12;
        if (ShippingData.activeBillId() > 0) {
            String line = "\u822a\u6b21 #" + ShippingData.activeBillId() + " "
                    + ShippingData.activeName() + " \u00b7 \u52df\u8d44\u81f3 "
                    + day(ShippingData.deadline());
            g.drawString(this.font, cut(line, IN_R - IN_L - 4), left + IN_L, y, INK_DIM, false);
        } else {
            g.drawString(this.font, "\u6ca1\u6709\u52df\u8d44\u4e2d\u7684\u822a\u6b21\uff0c"
                    + "\u6682\u65f6\u4e0d\u80fd\u4e0b\u5355", left + IN_L, y, BAD, false);
        }

        // 我的贸易单（最多 2 行，给目的地条腾位置）
        y = top + D_ORDERS_Y;
        g.drawString(this.font, "\u6211\u7684\u8d38\u6613\u5355", left + IN_L, y, INK, false);
        List<ShippingData.Order> orders = ShippingData.orders();
        String cnt = orders.size() + " \u5f20";
        g.drawString(this.font, cnt, left + IN_R - this.font.width(cnt), y, INK_DIM, false);

        y += 11;
        if (orders.isEmpty()) {
            g.drawString(this.font, "\uff08\u6ca1\u6709\u672a\u7ed3\u7b97\u7684\u5355\uff09",
                    left + IN_L + 4, y, INK_DIM, false);
        }
        for (int i = 0; i < orders.size() && i < 2; i++) {
            ShippingData.Order o = orders.get(i);
            boolean on = o.id == selOrder;
            int ry = y + i * ROW_H;
            if (on) {
                g.fill(left + IN_L, ry - 1, left + IN_R, ry + 10, 0x30B08A50);
            }
            String side = "SELL".equals(o.side) ? "\u51fa" : "\u8fdb";
            String txt = "#" + o.id + " \u8239" + o.billId + " " + side + " "
                    + nameOf(o.item) + " \u00d7" + o.qty + " @" + o.unit;
            g.drawString(this.font, cut(txt, IN_R - IN_L - 20), left + IN_L, ry,
                    on ? INK : INK_DIM, false);
            // 撤单：小号木制按钮
            boolean xHover = mouseX >= left + IN_R - 12 && mouseX < left + IN_R
                    && mouseY >= ry - 1 && mouseY < ry + 10;
            WoodRender.plate(g, left + IN_R - 12, ry - 1, 12, 11, xHover);
            g.drawString(this.font, "\u00d7", left + IN_R - 9, ry, WoodRender.TEXT, false);
        }

        // 贸易品类
        y = top + D_GOODS_Y;
        g.drawString(this.font, "\u8d38\u6613\u54c1\u7c7b", left + IN_L, y, INK, false);
        List<ShippingData.Good> goods = ShippingData.goods();
        if (goods.isEmpty()) {
            g.drawString(this.font, "\uff08\u6ca1\u6709\u53ef\u4ea4\u6613\u54c1\u7c7b\uff09",
                    left + IN_L + 4, y + 12, INK_DIM, false);
            return;
        }
        if (goodIndex >= goods.size()) {
            goodIndex = 0;
        }
        ShippingData.Good gd = goods.get(goodIndex);
        String idx = (goodIndex + 1) + "/" + goods.size();
        g.drawString(this.font, idx, left + IN_R - this.font.width(idx), y, INK_DIM, false);

        y += 12;
        g.drawString(this.font, cut(nameOf(gd.item), 110), left + IN_L, y, INK, false);
        String price = "\u5356 " + gd.sell + " / \u4e70 " + gd.buy;
        g.drawString(this.font, price, left + IN_L + 72, y, INK_DIM, false);
        String stock = "\u5e93\u5b58 " + gd.stock;
        g.drawString(this.font, stock, left + IN_R - this.font.width(stock), y, INK_DIM, false);

        // 品类翻页（只影响报价预览）
        int qy = top + D_QTY_Y;
        nav(g, left + IN_L, qy, 12, "<", mouseX, mouseY);
        nav(g, left + IN_L + 14, qy, 12, ">", mouseX, mouseY);

        // 进口 / 出口：暂未开放，按钮置灰，点了只给提示
        int dis = 0xFF8A7A60;
        WoodRender.plate(g, left + IN_L + 32, qy, 52, 14, false);
        g.drawString(this.font, "\u51fa\u53e3",
                left + IN_L + 32 + (52 - this.font.width("\u51fa\u53e3")) / 2, qy + 3, dis, false);
        WoodRender.plate(g, left + IN_L + 88, qy, 52, 14, false);
        g.drawString(this.font, "\u8fdb\u53e3",
                left + IN_L + 88 + (52 - this.font.width("\u8fdb\u53e3")) / 2, qy + 3, dis, false);
        g.drawString(this.font, "\u8d38\u6613\u6682\u672a\u5f00\u653e", left + IN_L + 146, qy + 3,
                0x9A8A70, false);

        g.drawString(this.font, "\u5f00\u653e\u540e\uff1a\u51fa\u53e3\u8d27\u968f\u8239\u8d70\uff0c"
                + "\u8fdb\u53e3\u9884\u4ed8\u5230\u6e2f\u63d0\u8d27",
                left + IN_L, top + D_HINT_Y, INK_DIM, false);
    }

    // ---------------------------------------------------------------- 包船

    private void drawCharter(GuiGraphics g, int mouseX, int mouseY) {
        int y = top + BODY_Y;
        if (!ShippingData.hasCharter()) {
            g.drawString(this.font, "\u8fd8\u6ca1\u6709\u5305\u8239\u8349\u6848", left + IN_L, y,
                    INK_DIM, false);
            g.drawString(this.font, "\u4e3b\u624b\u62ff\u8d27\u3001\u70b9\u300c\u88c5\u8d27\u300d\u5f00\u59cb",
                    left + IN_L, y + 12, INK_DIM, false);
        } else {
            g.drawString(this.font, "\u5305\u8239\u8349\u6848\uff08\u672a\u51fa\u6d77\uff09", left + IN_L,
                    y, INK, false);
            String v = "\u8d27\u503c " + fmt(ShippingData.charterValue()) + " / "
                    + fmt(ShippingData.charterMaxValue());
            g.drawString(this.font, v, left + IN_L, y + 12, INK_DIM, false);
            String f = "\u8fd0\u8d39 " + ShippingData.freight() + "\uff08"
                    + ShippingData.charterFee() + "\u2030\uff09";
            g.drawString(this.font, f, left + IN_R - this.font.width(f), y + 12, INK_DIM, false);

            y += 26;
            g.drawString(this.font, "\u8239\u4e0a\u8d27\u7269", left + IN_L, y, INK, false);
            List<ShippingData.CharterLine> lines = ShippingData.charter();
            for (int i = 0; i < lines.size() && i < 5; i++) {
                ShippingData.CharterLine cl = lines.get(i);
                int ry = y + 12 + i * ROW_H;
                g.drawString(this.font, cut(nameOf(cl.item), 110), left + IN_L + 2, ry, INK,
                        false);
                String q = "\u00d7" + cl.qty;
                g.drawString(this.font, q, left + IN_L + 116, ry, INK_DIM, false);
                String val = fmt(cl.value);
                g.drawString(this.font, val, left + IN_R - this.font.width(val), ry, GOOD, false);
            }
        }

        // 包船暂未开放：三个按钮并排置灰，下面一行提示
        int by = top + 190;
        int dis = 0xFF8A7A60;
        WoodRender.plate(g, left + IN_L + 6, by, 56, 14, false);
        g.drawString(this.font, "\u88c5\u8d27",
                left + IN_L + 6 + (56 - this.font.width("\u88c5\u8d27")) / 2, by + 3, dis, false);
        WoodRender.plate(g, left + IN_L + 68, by, 56, 14, false);
        g.drawString(this.font, "\u51fa\u6d77",
                left + IN_L + 68 + (56 - this.font.width("\u51fa\u6d77")) / 2, by + 3, dis, false);
        WoodRender.plate(g, left + IN_L + 130, by, 56, 14, false);
        g.drawString(this.font, "\u64a4\u8239",
                left + IN_L + 130 + (56 - this.font.width("\u64a4\u8239")) / 2, by + 3, dis, false);

        g.drawString(this.font, "\u5305\u8239\u6682\u672a\u5f00\u653e", left + IN_L, by + 22,
                0x9A8A70, false);
    }

    // ---------------------------------------------------------------- 小部件

    private void nav(GuiGraphics g, int x, int y, int w, String label, int mouseX, int mouseY) {
        WoodRender.button(g, this.font, x, y, w, 14, label, mouseX, mouseY, WoodRender.TEXT);
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int mouseX, int mouseY) {
        WoodRender.button(g, this.font, x, y, w, 14, label, mouseX, mouseY, WoodRender.TEXT);
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

        int total = TABS.length * TAB_W + (TABS.length - 1) * TAB_GAP;
        int tx = left + (W - total) / 2;
        if (iy >= top + TABS_Y && iy < top + TABS_Y + 14) {
            for (int i = 0; i < TABS.length; i++) {
                if (ix >= tx && ix < tx + TAB_W) {
                    page = i;
                    selOrder = 0L;
                    return true;
                }
                tx += TAB_W + TAB_GAP;
            }
        }

        if (hit(ix, iy, left + IN_L, top + 232, 46, 14)) {
            ShippingNetwork.sendRequest();
            return true;
        }
        if (hit(ix, iy, left + IN_R - 58, top + 232, 58, 14)) {
            Minecraft.getInstance().setScreen(new FormCenterScreen());
            return true;
        }

        if (ShippingData.empty()) {
            return super.mouseClicked(mx, my, button);
        }
        boolean handled = switch (page) {
            case 0 -> clickOpen(ix, iy);
            case 1 -> clickVoyaging(ix, iy);
            case 3 -> clickRoma(ix, iy);
            case 4 -> clickCharter(ix, iy);
            default -> false;
        };
        return handled || super.mouseClicked(mx, my, button);
    }

    /** 我的份额：选中 / 上架 / 折现 / 提货 */
    /** 我的股票：翻页 / 上架 / 折现 / 提货 */

    /** 份额市场：选中 / 买下 / 撤单 */

    /** 叙述文案（public 给 GUI 预览工具复用，保证预览和游戏里同一套字） */
    public static List<String> narrativeLinesOf(ShippingData.Bill b) {
        List<String> lines = new ArrayList<>();
        if (b == null) {
            return lines;
        }
        // 先写逐段遭遇（日期 + 发生了什么），这是详情页的主体
        lines.addAll(legStory(b));

        String cargo = b.total > 0 ? ("共 " + b.total + " 份，已售 " + b.sold + " 份") : "";
        switch (b.status) {
            case "OPEN" -> {
                lines.add(b.name + "正在募资，" + cargo + "。");
                lines.add("募资截止 " + day(b.deadline) + "，认购不足三成会取消并退款。");
                lines.add("可以在「在招」页出货换份或出钱换份。");
            }
            case "VOYAGING" -> {
                lines.add("预计 " + day(b.dueDay) + " 靠岸，后面还会碰上什么，谁也说不准。");
                lines.add("船越破越容易出事；真要沉了，票子就成废纸。");
            }
            case "DUE" -> {
                lines.add(b.name + "已经靠岸，结果「" + (b.outcome == null ? "" : b.outcome) + "」。");
                lines.add("每份份值 " + b.shareValue + "，可在「我的」页折现或提货。");
            }
            default -> {
                lines.add(b.name + "的航次已经结束"
                        + (b.outcome == null || b.outcome.isEmpty() ? "。" : "，结果「" + b.outcome + "」。"));
                lines.add("货物与资金都已结清，记录保留备查。");
            }
        }
        return lines;
    }

    /** 逐段航程：按「日期 + 遭遇」一条条写出来（数据来自 VoyageBill.legLog / legDays） */
    private static List<String> legStory(ShippingData.Bill b) {
        List<String> out = new ArrayList<>();
        if (b.legLog == null || b.legLog.isEmpty()) {
            return out;
        }
        String[] ev = b.legLog.split(";", -1);
        String[] dd = b.legDays == null ? new String[0] : b.legDays.split(",", -1);
        for (int i = 0; i < ev.length; i++) {
            String e = ev[i] == null ? "" : ev[i].trim();
            if (e.isEmpty()) {
                continue;
            }
            long d = -1L;
            if (i < dd.length) {
                try {
                    d = Long.parseLong(dd[i].trim());
                } catch (Throwable ignored) {
                    d = -1L;
                }
            }
            String when = d > 0 ? (day(d) + " ") : "";
            out.add(when + eventSentence(e));
        }
        return out;
    }

    /** 事件名 → 一句话（不带数值，只描述发生了什么） */
    private static String eventSentence(String e) {
        return switch (e) {
            case "\u5e73\u5b89" -> "\u672c\u8239\u4e00\u8def\u5e73\u5b89\uff0c\u6ca1\u51fa\u4ec0\u4e48\u4e8b\u3002";
            case "\u987a\u98ce" -> "\u672c\u8239\u8d76\u4e0a\u987a\u98ce\uff0c\u8dd1\u5f97\u6bd4\u9884\u60f3\u5feb\u3002";
            case "\u9006\u98ce" -> "\u672c\u8239\u9047\u4e0a\u9006\u98ce\uff0c\u803d\u6401\u4e86\u4e9b\u65e5\u5b50\u3002";
            case "\u98ce\u66b4" -> "\u672c\u8239\u906d\u9047\u4e86\u98ce\u66b4\uff0c\u8239\u8eab\u53d7\u635f\uff0c\u8d27\u4e5f\u6298\u4e86\u4e9b\u3002";
            case "\u6d77\u76d7" -> "\u672c\u8239\u906d\u9047\u4e86\u6d77\u76d7\uff0c\u88ab\u62a2\u8d70\u4e00\u90e8\u5206\u8d27\u3002";
            case "\u89e6\u7901" -> "\u672c\u8239\u89e6\u7901\uff0c\u8239\u5e95\u8fdb\u6c34\uff0c\u8d27\u4e5f\u635f\u4e86\u4e9b\u3002";
            case "\u6c89\u6ca1" -> "\u672c\u8239\u6c89\u6ca1\u4e86\uff0c\u8fd9\u8d9f\u5230\u6b64\u4e3a\u6b62\u3002";
            default -> e + "\u3002";
        };
    }

    /** 在航船只：点一行 → 船只详情（独立页面） */
    private boolean clickVoyaging(int mx, int my) {
        List<ShippingData.Bill> list = ShippingData.bills("VOYAGING");
        int y = top + BODY_Y + 13;
        for (int i = 0; i < list.size() && i < 7; i++) {
            int ry = y + i * 13;
            if (my >= ry - 1 && my < ry + 11) {
                ShippingData.Bill b = list.get(i);
                selBill = b.id;
                Minecraft.getInstance().setScreen(
                        new VoyageShipScreen(b.id, narrativeLinesOf(b)));
                return true;
            }
        }
        return false;
    }

    /** 载货清单翻页（kind：0 = 在招，1 = 航行） */
    private boolean clickCargoPage(int kind, int mx, int my) {
        ShippingData.Bill b = kind == 0 ? currentOpen() : ShippingData.first("VOYAGING");
        if (b == null) {
            return false;
        }
        int arrowY = top + CARGO_HEAD_Y - 2;
        if (my < arrowY || my >= arrowY + 11) {
            return false;
        }
        int pages = Math.max(1,
                (ShippingData.linesOf(b.id).size() + CARGO_PER_PAGE - 1) / CARGO_PER_PAGE);
        if (hit(mx, my, left + IN_R - 26, arrowY, 12, 11)) {
            if (kind == 0) {
                cargoPageOpen = Math.max(0, cargoPageOpen - 1);
            } else {
                cargoPageVoy = Math.max(0, cargoPageVoy - 1);
            }
            return true;
        }
        if (hit(mx, my, left + IN_R - 12, arrowY, 12, 11)) {
            if (kind == 0) {
                cargoPageOpen = Math.min(pages - 1, cargoPageOpen + 1);
            } else {
                cargoPageVoy = Math.min(pages - 1, cargoPageVoy + 1);
            }
            return true;
        }
        return false;
    }

    private boolean clickOpen(int mx, int my) {
        ShippingData.Bill b = currentOpen();
        if (b == null) {
            return false;
        }
        if (clickCargoPage(0, mx, my)) {
            return true;
        }
        int by = top + 190;
        if (hit(mx, my, left + IN_L, by, 76, 14)) {
            Minecraft.getInstance().setScreen(new ContributeScreen(b.id));
            return true;
        }
        if (hit(mx, my, left + IN_L + 82, by, 76, 14)) {
            Minecraft.getInstance().setScreen(new InvestScreen(b.id));
            return true;
        }
        return false;
    }

    private boolean clickRoma(int mx, int my) {
        // 目的地选择条
        if (my >= top + DEST_Y && my < top + DEST_Y + DEST_H) {
            for (int i = 0; i < DESTINATIONS.length; i++) {
                int x = left + IN_L + i * (DEST_W + DEST_GAP);
                if (mx >= x && mx < x + DEST_W) {
                    if (i == 0) {
                        dest = 0;
                    } else {
                        hint("\u00a77\u8fd9\u4e2a\u76ee\u7684\u5730\u8fd8\u6ca1\u5f00\u901a");
                    }
                    return true;
                }
            }
        }

        int oy = top + D_ORDERS_Y + 11;
        List<ShippingData.Order> orders = ShippingData.orders();
        for (int i = 0; i < orders.size() && i < 2; i++) {
            int ry = oy + i * ROW_H;
            if (my >= ry - 1 && my < ry + 10) {
                ShippingData.Order o = orders.get(i);
                if (mx >= left + IN_R - 14) {
                    action("/billroma cancel " + o.id);
                } else {
                    selOrder = o.id;
                }
                return true;
            }
        }
        List<ShippingData.Good> goods = ShippingData.goods();
        if (goods.isEmpty()) {
            return false;
        }
        int qy = top + D_QTY_Y;
        // 品类翻页
        if (hit(mx, my, left + IN_L, qy, 12, 14)) {
            goodIndex = Math.max(0, goodIndex - 1);
            return true;
        }
        if (hit(mx, my, left + IN_L + 14, qy, 12, 14)) {
            goodIndex = Math.min(goods.size() - 1, goodIndex + 1);
            return true;
        }
        // 进口 / 出口：暂未开放
        if (hit(mx, my, left + IN_L + 32, qy, 52, 14)
                || hit(mx, my, left + IN_L + 88, qy, 52, 14)) {
            hint("\u00a77\u8fdb\u53e3 / \u51fa\u53e3 \u6682\u672a\u5f00\u653e");
            return true;
        }
        return false;
    }

    private void order(ShippingData.Good gd, boolean export) {
        long bill = ShippingData.activeBillId();
        if (bill <= 0) {
            hint("\u00a77\u6ca1\u6709\u52df\u8d44\u4e2d\u7684\u822a\u6b21\uff0c\u4e0b\u4e0d\u4e86\u5355");
            return;
        }
        action((export ? "/billroma sell " : "/billroma want ") + bill + " " + gd.item + " "
                + qty());
    }

    /** 包船暂未开放：点哪儿都只给一句提示 */
    private boolean clickCharter(int mx, int my) {
        int by = top + 190;
        if (my >= by && my < by + 16 && mx >= left + IN_L && mx < left + IN_L + 186) {
            hint("\u00a77\u5305\u8239\u6682\u672a\u5f00\u653e");
            return true;
        }
        return false;
    }

    private int qty() {
        try {
            return Math.max(1, Integer.parseInt(qtyBox.getValue().trim()));
        } catch (Throwable t) {
            return 1;
        }
    }

    private static String statusText(String s) {
        return switch (s) {
            case "OPEN" -> "\u52df\u8d44";
            case "VOYAGING" -> "\u822a\u884c";
            case "DUE" -> "\u5230\u6e2f";
            case "CLOSED" -> "\u5df2\u7ed3\u675f";
            default -> s;
        };
    }

    /** 第 N 日 → 「X年Y月Z日」；服务端历法不在就用原始日数兜底 */
    static String day(long d) {
        if (d < 0) {
            return "\u672a\u5b9a";
        }
        try {
            return com.tangtu.kjbb.ServerCalendar.shortDate(d);
        } catch (Throwable t) {
            return d + " \u65e5";
        }
    }

    private static boolean hit(int mx, int my, int x, int y, int w, int h) {        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 动作全走已有指令；延迟刷新拿新数据 */
    private void action(String cmd) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new MenuNetwork.Action(cmd));
        } catch (Throwable ignored) {
            // ignore
        }
        refreshLater();
    }

    private void refreshLater() {
        new Thread(() -> {
            try {
                Thread.sleep(350L);
            } catch (Throwable ignored) {
                // ignore
            }
            Minecraft.getInstance().execute(ShippingNetwork::sendRequest);
        }, "kjbb-shipping-refresh").start();
    }

    private static void hint(String msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) {
            p.displayClientMessage(Component.literal(msg), false);
        }
    }

    // ---------------------------------------------------------------- 文本工具

    private String cut(String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (this.font.width(s) <= maxWidth) {
            return s;
        }
        return this.font.plainSubstrByWidth(s, Math.max(6, maxWidth - 6)) + "\u2026";
    }

    private static ItemStack iconOf(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                return ItemStack.EMPTY;
            }
            Item item = BuiltInRegistries.ITEM.get(rl);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static String nameOf(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                return shortId(id);
            }
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null) {
                return shortId(id);
            }
            return new ItemStack(item).getHoverName().getString();
        } catch (Throwable t) {
            return shortId(id);
        }
    }

    /** 持有人名字太长（唐兔国航运公司）——压缩到 4 个字 */
    private static String shortName(String name) {
        if (name == null) {
            return "";
        }
        if (name.startsWith("\u5510\u5154\u56fd\u822a\u8fd0\u516c\u53f8")) {
            return "\u822a\u8fd0";
        }
        if (name.startsWith("\u5510\u5154\u56fd\u56fd\u6c11")) {
            return "\u56fd\u6c11";
        }
        if (name.startsWith("\u7ecf\u6d4e\u90e8")) {
            return "\u56fd\u5e93";
        }
        if (name.length() > 4) {
            return name.substring(0, 4);
        }
        return name;
    }

    private static String shortId(String id) {
        if (id == null) {
            return "";
        }
        String s = id;
        if (s.startsWith("minecraft:")) {
            s = s.substring(10);
        } else if (s.startsWith("create:")) {
            s = "C\u00b7" + s.substring(7);
        } else if (s.startsWith("kaleidoscope_cookery:")) {
            s = s.substring(22);
        } else if (s.startsWith("kaleidoscope_tavern:")) {
            s = s.substring(21);
        }
        return s.replace('_', ' ');
    }

    private static String fmt(long v) {
        if (v >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0);
        }
        if (v >= 10_000L) {
            return String.format(Locale.ROOT, "%.1fK", v / 1000.0);
        }
        return String.valueOf(v);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
