package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 市场 / 证券交易所 / 我的 —— 一张羊皮纸，三个页签。
 *
 * 底图 trade_sheet.png（1254×1254 → 320×320）
 * 羊皮纸内框（实测）：x 21..300，y 32..288
 *
 * 所有绘制都在 addRenderableOnly 里（会话档案第 29 条铁律）。
 */
public class MarketScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/trade_sheet.png");
    private static final int W = 320, H = 320;

    private static final int TITLE_Y = 42;
    private static final int LIST_X = 48, LIST_Y = 76, LIST_W = 100, ROW_H = 18;
    private static final int SEARCH_Y = 56, SEARCH_W = 108;
    private static final int PAGE_Y = 158, PAGE_ROWS = 4;
    private static final int CHART_X = 164, CHART_Y = 64, CHART_W = 128, CHART_H = 96;
    private static final int PANEL_X = 44, PANEL_Y = 196, PANEL_W = 112;
    /** 右列「服务器订单」：位置、行数、行高（4 行 × 12 才装得下） */
    private static final int ORD_X = 164, ORD_Y = 168, ORD_ROWS = 4, ORD_H = 12;
    private static final int BOTTOM_Y = 268;

    /** 交易所页签的大图表区 */
    private static final int BIG_X = 40, BIG_Y = 66, BIG_W = 252, BIG_H = 132;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int UP = 0xFF2E7D32;
    private static final int DOWN = 0xFFB03030;

    private int left;
    private int top;
    private EditBox fieldCount;
    private EditBox fieldSearch;
    private EditBox fieldPrice;
    private int page = 0;
    private int orderPage = 0;
    private int ticketPage = 0;
    private String selected = "";

    // ---------------- 右侧「物品表」（JEI 风格，独立一栏） ----------------
    private static final int JP_W = 150;
    private static final int JP_PAD = 6;
    private static final int JP_SLOT = 18;
    private static final int JP_COLS = 7;
    private static final int JP_ROWS = 10;
    private EditBox fieldJei;
    private String jeiQuery = "";
    private int jeiPage = 0;
    private static java.util.List<String> jeiAll;
    private java.util.List<String> jeiFiltered = new java.util.ArrayList<>();
    private int jeiLeft;
    private int jeiTop;
    private int jeiRight;
    /** 0 = 市场，1 = 我的，2 = 证券交易所，3 = 票据，4 = 投递记录 */
    private int tab = 0;
    /** 投递记录页（与交易中心共用同一套绘制） */
    private final MailPanel mailPanel = new MailPanel();

    public MarketScreen() {
        this(0);
    }

    /** @param tab 0 = 市场，1 = 我的，2 = 证券交易所 */
    public MarketScreen(int tab) {
        super(Component.literal("市场"));
        // 现在只有 0 = 市场、2 = 交易所、4 = 投递记录（票据/我的是独立界面了）
        this.tab = (tab == 2 || tab == 4) ? tab : 0;
    }

    @Override
    protected void init() {
        super.init();
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tangtu.kjbb.net.MenuNetwork.ReqBank());
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            var tm = Minecraft.getInstance().getTextureManager();
            var tex = tm.getTexture(TEX, new net.minecraft.client.renderer.texture.SimpleTexture(TEX));
            if (tex != null) {
                tex.setFilter(true, false);
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        fieldSearch = new EditBox(this.font, left + LIST_X + 18, top + SEARCH_Y + 2,
                SEARCH_W - 20, 8, Component.literal("搜索"));
        fieldSearch.setValue("");
        fieldSearch.setMaxLength(32);
        fieldSearch.setBordered(false);
        fieldSearch.setTextColor(INK);
        fieldSearch.setResponder(s -> page = 0);
        addRenderableWidget(fieldSearch);

        fieldCount = new EditBox(this.font, left + 168, top + BOTTOM_Y + 3, 36, 9,
                Component.literal("数量"));
        fieldCount.setValue("1");
        fieldCount.setMaxLength(9);
        fieldCount.setBordered(false);
        fieldCount.setTextColor(INK);
        addRenderableWidget(fieldCount);

        fieldPrice = new EditBox(this.font, left + 150, top + BIG_Y + BIG_H + 22, 44, 9,
                Component.literal("单价"));
        fieldPrice.setValue("0");
        fieldPrice.setMaxLength(9);
        fieldPrice.setBordered(false);
        fieldPrice.setTextColor(INK);
        addRenderableWidget(fieldPrice);

        // 右侧物品表：给羊皮纸留出空间，两者一起居中
        if (jeiAll == null) {
            jeiAll = new java.util.ArrayList<>();
            for (ResourceLocation rl : BuiltInRegistries.ITEM.keySet()) {
                Item it = BuiltInRegistries.ITEM.get(rl);
                if (it == null) {
                    continue;
                }
                if (new ItemStack(it).isEmpty()) {
                    continue;
                }
                jeiAll.add(rl.toString());
            }
            jeiAll.sort(String::compareTo);
        }
        refilterJei();

        int totalW = W + JP_PAD + JP_W;
        this.left = Math.max(6, this.width / 2 - totalW / 2);
        this.top = this.height / 2 - H / 2;
        this.jeiLeft = left + W + JP_PAD;
        this.jeiRight = jeiLeft + JP_W;
        this.jeiTop = top;

        // 搜索框（位置随布局重算，所以放在这里重新摆）
        if (fieldSearch != null) {
            fieldSearch.setX(left + LIST_X + 18);
            fieldSearch.setY(top + SEARCH_Y + 2);
        }
        // 数量 / 单价两个输入框是在 left/top 算出来之前 new 的，这里必须跟着重摆，
        // 否则会按初始化时的旧坐标漂到面板外（表现为天上飘着「0」和「1」两个小字）
        if (fieldCount != null) {
            fieldCount.setX(left + 168);
            fieldCount.setY(top + BOTTOM_Y + 3);
        }
        if (fieldPrice != null) {
            fieldPrice.setX(left + 150);
            fieldPrice.setY(top + BIG_Y + BIG_H + 22);
        }

        fieldJei = new EditBox(this.font, jeiLeft + JP_PAD + 4, jeiTop + 20,
                JP_W - JP_PAD * 2 - 8, 10, Component.literal("\u641c\u7269\u54c1"));
        fieldJei.setValue(jeiQuery);
        fieldJei.setMaxLength(40);
        fieldJei.setBordered(false);
        fieldJei.setTextColor(0xFFF3E2C0);
        fieldJei.setResponder(s -> {
            jeiQuery = s;
            jeiPage = 0;
            refilterJei();
        });
        addRenderableWidget(fieldJei);

        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));

        com.tangtu.kjbb.net.MarketNetwork.requestMarkets();
        com.tangtu.kjbb.net.MarketNetwork.requestOpenOrders();
        com.tangtu.kjbb.net.MarketNetwork.requestMyOrders();
        com.tangtu.kjbb.net.MarketNetwork.requestTickets();
        if (tab == 4) {
            com.tangtu.kjbb.net.TxNetwork.sendRequest();
        }
    }

    // ------------------------------------------------------------------ 绘制

    private void refilterJei() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String q = jeiQuery == null ? "" : jeiQuery.trim().toLowerCase();
        for (String id : jeiAll) {
            if (q.isEmpty()) {
                out.add(id);
                continue;
            }
            if (id.toLowerCase().contains(q)) {
                out.add(id);
                continue;
            }
            try {
                ItemStack st = new ItemStack(BuiltInRegistries.ITEM.get(
                        ResourceLocation.tryParse(id)));
                if (st.getHoverName().getString().toLowerCase().contains(q)) {
                    out.add(id);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        jeiFiltered = out;
        int perPage = JP_COLS * JP_ROWS;
        int pages = Math.max(1, (jeiFiltered.size() + perPage - 1) / perPage);
        if (jeiPage >= pages) {
            jeiPage = 0;
        }
    }

    /** 搜索框为空时：右侧列出全市场未完成订单 */
    private boolean ordersMode() {
        if (fieldJei == null) {
            return false;
        }
        String s = fieldJei.getValue();
        return s == null || s.trim().isEmpty();
    }

    private void drawOrderPanel(GuiGraphics g, int mouseX, int mouseY) {
        int x = jeiLeft;
        int y = jeiTop;
        g.fill(x - 2, y - 2, jeiRight + 2, y + H + 2, 0xFF2A1C0C);
        g.fill(x, y, jeiRight, y + H, 0xFF3B2A16);
        g.fill(x + 1, y + 1, jeiRight - 1, y + 16, 0xFF553C1E);

        String title = "\u672a\u5b8c\u6210\u8ba2\u5355";
        g.drawString(this.font, title, x + (JP_W - this.font.width(title)) / 2, y + 4,
                0xFFF3E2C0, false);

        // 搜索框底（保持位置一致）
        g.fill(x + JP_PAD, y + 19, x + JP_W - JP_PAD, y + 32, 0x30FFFFFF);
        g.fill(x + JP_PAD, y + 32, x + JP_W - JP_PAD, y + 33, 0xFF9A7B4C);

        var list = MarketCache.openOrders();
        int pages = Math.max(1, (list.size() + ORDER_ROWS - 1) / ORDER_ROWS);
        if (jeiPage >= pages) {
            jeiPage = 0;
        }
        int from = jeiPage * ORDER_ROWS;
        int rowH = 15;
        int ry = y + 38;
        if (list.isEmpty()) {
            g.drawString(this.font, "\uff08\u5f53\u524d\u6ca1\u6709\u672a\u5b8c\u6210\u7684\u8ba2\u5355\uff09",
                    x + JP_PAD, ry + 4, 0xFFC9A96A, false);
        } else {
            for (int i = 0; i < ORDER_ROWS && from + i < list.size(); i++) {
                var o = list.get(from + i);
                int cy = ry + i * rowH;
                boolean hover = mouseX >= x + JP_PAD && mouseX < jeiRight - JP_PAD
                        && mouseY >= cy - 1 && mouseY < cy + rowH - 2;
                if (hover) {
                    g.fill(x + JP_PAD - 1, cy - 1, jeiRight - JP_PAD, cy + rowH - 3, 0x40FFFFFF);
                }
                ItemStack st = iconOf(o.itemId());
                if (!st.isEmpty()) {
                    g.renderItem(st, x + JP_PAD, cy - 1);
                }
                String nm = shortName(o.itemId(), 52);
                // 真人的单换个颜色：自己金色、别的玩家蓝色、机器人原色
                g.drawString(this.font, nm, x + JP_PAD + 18, cy, orderOwnerColor(o), false);
                String line2 = (o.buy() ? "\u00a7a\u4e70 " : "\u00a7c\u5356 ")
                        + o.count() + " @ " + o.unit();
                g.drawString(this.font, line2, x + JP_PAD + 18, cy + 6, 0xFFC9A96A, false);
            }
        }

        String pageTxt = (jeiPage + 1) + "/" + pages + "  \uff08\u5171 " + list.size() + " \u5355\uff09";
        g.drawString(this.font, pageTxt, x + JP_PAD, y + H - 30, 0xFFC9A96A, false);
        int by = y + H - 16;
        drawPanelBtn(g, x + JP_PAD, by, 34, "<", mouseX, mouseY);
        drawPanelBtn(g, x + JP_PAD + 38, by, 34, ">", mouseX, mouseY);
    }

    /** 活跃订单里的名字颜色：自己金色 / 别的真人玩家蓝色 / 机器人原色 */
    private static int orderOwnerColor(MarketCache.OpenOrder o) {
        String me = "";
        try {
            var p = Minecraft.getInstance().player;
            me = p == null ? "" : p.getScoreboardName();
        } catch (Throwable ignored) {
            me = "";
        }
        if (!me.isEmpty() && me.equals(o.owner())) {
            return 0xFFFFD24A;
        }
        return o.bot() ? 0xFFF3E2C0 : 0xFF6FB6FF;
    }

    private String shortName(String id, int maxW) {
        String nm = id;
        try {
            var it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
            if (it != null) {
                nm = new ItemStack(it).getHoverName().getString();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (this.font.width(nm) <= maxW) {
            return nm;
        }
        return this.font.plainSubstrByWidth(nm, maxW - 6) + "\u2026";
    }

    /** 右侧「物品表」：JEI 风格的物品网格 */
    private void drawJeiPanel(GuiGraphics g, int mouseX, int mouseY) {
        int x = jeiLeft;
        int y = jeiTop;
        // 底：深色木框
        g.fill(x - 2, y - 2, jeiRight + 2, y + H + 2, 0xFF2A1C0C);
        g.fill(x, y, jeiRight, y + H, 0xFF3B2A16);
        g.fill(x + 1, y + 1, jeiRight - 1, y + 16, 0xFF553C1E);

        String title = "\u7269 \u54c1 \u8868";
        g.drawString(this.font, title, x + (JP_W - this.font.width(title)) / 2, y + 4,
                0xFFF3E2C0, false);

        // 搜索框底
        g.fill(x + JP_PAD, y + 19, x + JP_W - JP_PAD, y + 32, 0x30FFFFFF);
        g.fill(x + JP_PAD, y + 32, x + JP_W - JP_PAD, y + 33, 0xFF9A7B4C);

        int gx = x + (JP_W - JP_COLS * JP_SLOT) / 2;
        int gy = y + 38;
        int perPage = JP_COLS * JP_ROWS;
        int from = jeiPage * perPage;

        for (int i = 0; i < perPage; i++) {
            int idx = from + i;
            int cx = gx + (i % JP_COLS) * JP_SLOT;
            int cy = gy + (i / JP_COLS) * JP_SLOT;
            boolean hover = mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16;
            if (hover) {
                g.fill(cx - 1, cy - 1, cx + 17, cy + 17, 0x40FFFFFF);
            }
            if (idx >= jeiFiltered.size()) {
                continue;
            }
            String id = jeiFiltered.get(idx);
            try {
                ItemStack st = new ItemStack(BuiltInRegistries.ITEM.get(
                        ResourceLocation.tryParse(id)));
                if (!st.isEmpty()) {
                    g.renderItem(st, cx, cy);
                }
            } catch (Throwable ignored) {
                // ignore
            }
            if (id.equals(selected)) {
                g.fill(cx - 1, cy - 1, cx + 17, cy - 1 + 2, 0xFF2E7D32);
                g.fill(cx - 1, cy + 15, cx + 17, cy + 17, 0xFF2E7D32);
                g.fill(cx - 1, cy - 1, cx - 1 + 2, cy + 17, 0xFF2E7D32);
                g.fill(cx + 15, cy - 1, cx + 17, cy + 17, 0xFF2E7D32);
            }
        }

        int perPage2 = perPage;
        int pages = Math.max(1, (jeiFiltered.size() + perPage2 - 1) / perPage2);
        String pageTxt = (jeiPage + 1) + "/" + pages;
        g.drawString(this.font, pageTxt, x + JP_PAD, y + H - 30, 0xFFC9A96A, false);

        // 翻页按钮
        int by = y + H - 16;
        drawPanelBtn(g, x + JP_PAD, by, 34, "<", mouseX, mouseY);
        drawPanelBtn(g, x + JP_PAD + 38, by, 34, ">", mouseX, mouseY);
        drawPanelBtn(g, x + JP_W - JP_PAD - 62, by, 62, "\u52a0\u5165\u5e02\u573a", mouseX, mouseY);

        // 悬停提示
        for (int i = 0; i < perPage; i++) {
            int idx = from + i;
            int cx = gx + (i % JP_COLS) * JP_SLOT;
            int cy = gy + (i / JP_COLS) * JP_SLOT;
            if (idx < jeiFiltered.size()
                    && mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16) {
                String id = jeiFiltered.get(idx);
                g.renderTooltip(this.font, Component.literal(nameOf(id)), mouseX, mouseY);
            }
        }
    }

    private void drawPanelBtn(GuiGraphics g, int x, int y, int w, String label,
                              int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? 0xFF8A6A3A : 0xFF4A3418);
        int tw = this.font.width(label);
        g.drawString(this.font, label, x + (w - tw) / 2, y + 3, 0xFFF3E2C0, false);
    }

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1254, 1254, 1254, 1254);

        // 输入框只在用得着的页签显示，避免别的页签上飘着空框
        if (fieldSearch != null) {
            fieldSearch.setVisible(tab == 0);
        }
        if (fieldCount != null) {
            // 数量框：市场页（买入）和交易所页（挂单）都要，位置各摆各的
            fieldCount.setVisible(tab == 0 || tab == 2);
            if (tab == 2) {
                fieldCount.setX(left + 68);
                fieldCount.setY(top + BIG_Y + BIG_H + 21);
            } else {
                fieldCount.setX(left + 168);
                fieldCount.setY(top + BOTTOM_Y + 3);
            }
        }
        if (fieldPrice != null) {
            fieldPrice.setVisible(tab == 2);
            if (tab == 2) {
                fieldPrice.setX(left + 136);
                fieldPrice.setY(top + BIG_Y + BIG_H + 21);
            }
        }

        String title = switch (tab) {
            case 2 -> "证 券 交 易 所";
            case 4 -> "投 递 记 录";
            default -> "市　场";
        };
        g.drawString(this.font, title, left + LIST_X, top + TITLE_Y, INK, false);

        // 左下角：唐兔币余额
        try {
            int bal = com.tangtu.kjbb.client.MarketCache.total();
            String balTxt = bal < 0 ? "余额未知"
                    : (bal >= 10000
                    ? String.format(java.util.Locale.ROOT, "%.1fK", bal / 1000.0)
                    : String.valueOf(bal));
            g.drawString(this.font, balTxt + " 唐兔币", left + LIST_X, top + 238, INK, false);
        } catch (Throwable ignored) {
            // ignore
        }

        // 右下角：服务器日期（避开顶部标签）
        String date = com.tangtu.kjbb.client.MarketCache.serverDate();
        if (date != null && !date.isEmpty()) {
            String shown = date;
            // 去掉「第 」前缀，压缩成「2 年 9 月 10 日」
            shown = shown.replace("\u7b2c ", "").replace("\u5e74 ", "\u5e74")
                    .replace("\u6708 ", "\u6708").replace("\u65e5", "\u65e5");
            g.drawString(this.font, shown,
                    left + W - 18 - this.font.width(shown), top + 238, INK_DIM, false);
        }

        drawTabIcon(g, left + 142, top + TITLE_Y - 1, 52, "市场", tab == 0, mouseX, mouseY, "market");
        drawTabIcon(g, left + 198, top + TITLE_Y - 1, 52, "投递", tab == 4, mouseX, mouseY, "list");
        drawTabIcon(g, left + 254, top + TITLE_Y - 1, 52, "交易所", tab == 2, mouseX, mouseY, "exchange");

        switch (tab) {
            case 2 -> drawExchange(g, mouseX, mouseY);
            case 4 -> mailPanel.draw(g, this.font, left + 44, top + 80, 236, 6, 15,
                    top + 184, mouseX, mouseY);
            default -> {
                drawSearch(g);
                drawMarkets(g, mouseX, mouseY);
                drawPager(g, mouseX, mouseY);
                drawDetail(g, mouseX, mouseY);
                drawItemOrders(g, left + ORD_X, top + ORD_Y, mouseX, mouseY);
            }
        }

        // 底部操作栏：只有「市场」页需要（投递没有买入，交易所用上面的挂单）
        if (tab == 4 || tab == 2) {
            drawJeiPanel(g, mouseX, mouseY);
            return;
        }
        String selTxt = selected.isEmpty() ? "未选" : nameOf(selected);
        if (selected.isEmpty()) {
            g.drawString(this.font, "已选：未选", left + LIST_X, top + BOTTOM_Y + 4, INK_DIM, false);
        } else {
            ItemStack si = iconOf(selected);
            if (!si.isEmpty()) {
                g.renderItem(si, left + LIST_X, top + BOTTOM_Y);
            }
            g.drawString(this.font, selTxt, left + LIST_X + 18, top + BOTTOM_Y + 4, INK, false);
        }
        g.drawString(this.font, "数量", left + 148, top + BOTTOM_Y + 4, INK_DIM, false);
        g.fill(left + 166, top + BOTTOM_Y + 1, left + 206, top + BOTTOM_Y + 13, 0x28FFFFFF);
        g.fill(left + 166, top + BOTTOM_Y + 13, left + 206, top + BOTTOM_Y + 14, 0xFF9A7B4C);
        // 右列底部：查看全部活跃订单（左列底部留给余额/日期）
        WoodRender.button(g, this.font, left + ORD_X, top + ORD_Y + 78, 118, 16,
                "查看全部活跃订单", mouseX, mouseY, WoodRender.TEXT);
    
        // 右侧「物品表」
        drawJeiPanel(g, mouseX, mouseY);
}

    private void drawTabIcon(GuiGraphics g, int x, int y, int w, String label, boolean active,
                             int mouseX, int mouseY, String icon) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 12;
        int bg = active ? 0xFF5A3F1E : (hover ? 0x807A5A30 : 0);
        if (bg != 0) {
            g.fill(x, y, x + w, y + 12, bg);
        }
        drawIcon(g, icon, x + 1, y - 2);
        int color = active ? 0xFFF3E2C0 : INK_DIM;
        g.drawString(this.font, label, x + 18, y + 2, color, false);
    }

    private void drawSearch(GuiGraphics g) {
        int x = left + LIST_X - 4;
        int y = top + SEARCH_Y;
        g.fill(x, y, x + SEARCH_W + 8, y + 12, 0x30FFFFFF);
        g.fill(x, y + 12, x + SEARCH_W + 8, y + 13, 0xFF9A7B4C);
        drawIcon(g, "search", x + 2, y - 2);
        if (fieldSearch.getValue().isEmpty()) {
            g.drawString(this.font, "搜索物品", x + 22, y + 3, 0xFFA08A60, false);
        }
    }

    private void drawMarkets(GuiGraphics g, int mouseX, int mouseY) {
        String head = "卖一/买一";
        g.drawString(this.font, head, left + LIST_X + LIST_W - this.font.width(head) + 12,
                top + LIST_Y - 10, INK_DIM, false);

        List<MarketCache.Market> list = filtered();
        if (list.isEmpty()) {
            if (!selected.isEmpty()) {
                g.drawString(this.font, nameOf(selected) + "（未入市）",
                        left + LIST_X, top + LIST_Y + 6, INK, false);
                g.drawString(this.font, "点右下【加入市场】即可开价交易",
                        left + LIST_X, top + LIST_Y + 18, INK_DIM, false);
            } else {
                g.drawString(this.font, "（仓库里还没有可卖的商品）", left + LIST_X, top + LIST_Y + 6, INK_DIM, false);
                g.drawString(this.font, "把货放进发报机链接的仓库即可", left + LIST_X, top + LIST_Y + 18, INK_DIM, false);
            }
            drawChartArea(g, CHART_X, CHART_Y, CHART_W, CHART_H);
            return;
        }
        int from = page * PAGE_ROWS;
        for (int i = 0; i < PAGE_ROWS && from + i < list.size(); i++) {
            MarketCache.Market m = list.get(from + i);
            int y = top + LIST_Y + i * ROW_H;
            boolean sel = m.id().equals(selected);
            if (sel) {
                g.fill(left + LIST_X - 4, y - 1, left + LIST_X + LIST_W, y + ROW_H - 3, 0x40B08A50);
            }
            ItemStack icon = iconOf(m.id());
            if (!icon.isEmpty()) {
                g.renderItem(icon, left + LIST_X - 2, y);
            }
            String ask = m.ask() > 0 ? String.valueOf(m.ask()) : "—";
            String bid = m.bid() > 0 ? String.valueOf(m.bid()) : "—";
            String right = ask + " / " + bid;
            g.drawString(this.font, right, left + LIST_X + LIST_W - this.font.width(right), y + 4,
                    m.ask() > 0 || m.bid() > 0 ? (sel ? INK : INK_DIM) : 0xAA6060, false);
        }
        drawChartArea(g, CHART_X, CHART_Y, CHART_W, CHART_H);
    }

    private void drawChartArea(GuiGraphics g, int cx, int cy, int cw, int chh) {
        int x0 = left + cx;
        int y0 = top + cy;
        g.fill(x0 - 1, y0 - 1, x0 + cw + 1, y0, 0xFF9A7B4C);
        g.fill(x0 - 1, y0 + chh, x0 + cw + 1, y0 + chh + 1, 0xFF9A7B4C);
        g.fill(x0 - 1, y0, x0, y0 + chh, 0xFF9A7B4C);
        g.fill(x0 + cw, y0, x0 + cw + 1, y0 + chh, 0xFF9A7B4C);

        List<MarketCache.Candle> candles = MarketCache.history();
        if (candles.isEmpty()) {
            String tip = selected.isEmpty() ? "（选中商品后显示 K 线）" : "（这个标的还没有 K 线数据）";
            g.drawString(this.font, tip, x0 + 6, y0 + chh / 2 - 4, INK_DIM, false);
            return;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (MarketCache.Candle c : candles) {
            min = Math.min(min, Math.min(c.low(), c.close()));
            max = Math.max(max, Math.max(c.high(), c.close()));
        }
        if (max <= min) {
            max = min + 1;
        }
        int n = Math.min(candles.size(), Math.max(1, cw / 8));
        int from = candles.size() - n;
        // 每根间距最多 16px、最少 6px —— 数据少的时候不会拉成一整块
        int step = Math.max(6, Math.min(16, cw / Math.max(1, n)));
        int barW = Math.max(3, step - 3);
        // 右边对齐：最新一根贴着右边界
        int xAnchor = x0 + cw - 4 - n * step;
        for (int i = 0; i < n; i++) {
            MarketCache.Candle c = candles.get(from + i);
            int cx2 = xAnchor + i * step;
            int yHigh = y0 + chh - 3 - (int) ((c.high() - min) / (double) (max - min) * (chh - 6));
            int yLow = y0 + chh - 3 - (int) ((c.low() - min) / (double) (max - min) * (chh - 6));
            int yOpen = y0 + chh - 3 - (int) ((c.open() - min) / (double) (max - min) * (chh - 6));
            int yClose = y0 + chh - 3 - (int) ((c.close() - min) / (double) (max - min) * (chh - 6));
            // 无成交（补的平价蜡烛）→ 灰色细线，一眼区分
            boolean flat = c.volume() <= 0
                    && c.open() == c.high() && c.high() == c.low() && c.low() == c.close();
            int color = flat ? 0xFF9A8A6A : (c.close() >= c.open() ? UP : DOWN);
            int midX = cx2 + barW / 2;
            g.fill(midX, Math.min(yHigh, yLow), midX + 1, Math.max(yHigh, yLow) + 1, color);
            int boxTop = Math.min(yOpen, yClose);
            int boxBot = Math.max(yOpen, yClose);
            if (boxBot - boxTop < 1) {
                boxBot = boxTop + 1;
            }
            if (flat) {
                // 平价蜡烛：一根短横线，宽度只有 1/3
                g.fill(cx2 + barW / 3, boxTop, cx2 + barW - barW / 3, boxTop + 1, color);
            } else {
                g.fill(cx2, boxTop, cx2 + barW, boxBot, color);
            }
        }
        g.drawString(this.font, String.valueOf(max), x0 + 2, y0 + 1, INK_DIM, false);
        g.drawString(this.font, String.valueOf(min), x0 + 2, y0 + chh - 9, INK_DIM, false);
    }

    /** 详情面板 */
    private void drawDetail(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + PANEL_X;
        int y = top + PANEL_Y;
        int step = 11;
        if (selected.isEmpty()) {
            g.drawString(this.font, "点左边的商品", x, y, INK_DIM, false);
            g.drawString(this.font, "看价格与订单", x, y + step, INK_DIM, false);
            g.drawString(this.font, "买入后在「我的订单」等货", x, y + step * 2, INK_DIM, false);
            return;
        }
        MarketCache.Market m = null;
        for (MarketCache.Market it : MarketCache.markets()) {
            if (it.id().equals(selected)) {
                m = it;
                break;
            }
        }
        if (m == null) {
            return;
        }
        // 左列这块只放四行；右列订单区和底栏各占各的位置，互不覆盖
        g.drawString(this.font, "最近成交 " + m.last(), x, y, INK, false);
        g.drawString(this.font, "卖一 " + (m.ask() > 0 ? m.ask() : "—")
                + "　买一 " + (m.bid() > 0 ? m.bid() : "—"), x, y + step, INK, false);
        g.drawString(this.font, "在售 " + m.supply() + " 个", x, y + step * 2, INK_DIM, false);
        g.drawString(this.font, "合计 " + (buyPrice() * parseCount()) + " 唐兔币",
                x, y + step * 3, 0xFF2E5B2E, false);
    }

    private static final int ORDER_ROWS = ORD_ROWS;

    /** 该物品在服务器上的所有订单（所有人可见），右下角带上下页 */
    private void drawItemOrders(GuiGraphics g, int ax, int ay, int mouseX, int mouseY) {
        var list = MarketCache.itemOrders();
        g.drawString(this.font, "服务器订单", ax, ay, INK_DIM, false);
        if (list.isEmpty()) {
            g.drawString(this.font, "（这个物品还没有人下单）", ax, ay + 12, INK_DIM, false);
            return;
        }
        int pages = Math.max(1, (list.size() + ORDER_ROWS - 1) / ORDER_ROWS);
        if (orderPage >= pages) {
            orderPage = pages - 1;
        }
        int from = orderPage * ORDER_ROWS;
        int yy = ay + 14;
        for (int i = 0; i < ORDER_ROWS && from + i < list.size(); i++) {
            var o = list.get(from + i);
            String status;
            int color;
            switch (o.status()) {
                case "PENDING" -> {
                    status = "待接";
                    color = 0xFFB07000;
                }
                case "SUPPLIED" -> {
                    status = "已发";
                    color = 0xFF1F6FB0;
                }
                case "DONE" -> {
                    status = "完成";
                    color = 0xFF2E7D32;
                }
                default -> {
                    status = "已撤";
                    color = 0xFF8A8A8A;
                }
            }
            boolean clickable = "PENDING".equals(o.status());
            boolean hoverRow = clickable && mouseX >= ax - 2 && mouseX < ax + 126
                    && mouseY >= yy - 1 && mouseY < yy + ORD_H - 3;
            if (hoverRow) {
                g.fill(ax - 2, yy - 1, ax + 126, yy + ORD_H - 3, 0x40B08A50);
            }
            g.drawString(this.font, "#" + o.id() + " ×" + o.count(), ax, yy,
                    clickable ? INK : INK_DIM, false);
            String price = o.unit() + " 唐兔币";
            g.drawString(this.font, price, ax + 66, yy, INK_DIM, false);
            g.drawString(this.font, status, ax + 112, yy, color, false);
            yy += ORD_H;
        }
        // 上下页
        int py = ay + 14 + ORDER_ROWS * ORD_H + 2;
        boolean lh = mouseX >= ax && mouseX < ax + 16 && mouseY >= py - 3 && mouseY < py + 13;
        boolean rh = mouseX >= ax + 100 && mouseX < ax + 116 && mouseY >= py - 3 && mouseY < py + 13;
        g.fill(ax, py - 3, ax + 16, py + 13, lh ? 0x40B08A50 : 0);
        drawIcon(g, "page_l", ax, py - 3);
        String mid = (orderPage + 1) + "/" + pages;
        g.drawString(this.font, mid, ax + 58 - this.font.width(mid) / 2, py + 2, INK_DIM, false);
        g.fill(ax + 100, py - 3, ax + 116, py + 13, rh ? 0x40B08A50 : 0);
        drawIcon(g, "page_r", ax + 100, py - 3);
    }

    /** 交易所页签：大 K 线 + OHLC */
    private void drawExchange(GuiGraphics g, int mouseX, int mouseY) {
        drawIcon(g, "exchange", left + LIST_X, top + LIST_Y - 12);
        String head = selected.isEmpty() ? "（未选商品）" : nameOf(selected);
        g.drawString(this.font, head, left + LIST_X + 20, top + LIST_Y - 8, INK, false);

        drawChartArea(g, BIG_X, BIG_Y, BIG_W, BIG_H);

        List<MarketCache.Candle> candles = MarketCache.history();
        int y = top + BIG_Y + BIG_H + 2;
        if (!candles.isEmpty()) {
            MarketCache.Candle last = candles.get(candles.size() - 1);
            int prev = candles.size() > 1 ? candles.get(candles.size() - 2).close() : last.close();
            double pct = prev == 0 ? 0 : (last.close() - prev) * 100.0 / prev;
            String ohlc = "开 " + last.open() + "   高 " + last.high() + "   低 " + last.low()
                    + "   收 " + last.close();
            g.drawString(this.font, ohlc, left + LIST_X, y, INK, false);
            String pctText = (pct >= 0 ? "+" : "") + String.format("%.2f", pct) + "%";
            g.drawString(this.font, pctText, left + LIST_X + 170, y,
                    pct >= 0 ? UP : DOWN, false);
        } else {
            g.drawString(this.font, "（还没有行情数据，先有成交才有 K 线）",
                    left + LIST_X, y, INK_DIM, false);
        }
        // 挂单区：数量 + 单价 + 挂单按钮（框的位置与 fieldCount / fieldPrice 一致）
        int by = top + BIG_Y + BIG_H + 20;
        String tip = selected.isEmpty() ? "先在「市场」选一个商品" : ("按单价卖出 " + nameOf(selected));
        String tipCut = this.font.width(tip) > 150
                ? this.font.plainSubstrByWidth(tip, 146) + "…" : tip;
        g.drawString(this.font, tipCut, left + LIST_X, by - 9, INK_DIM, false);
        g.drawString(this.font, "数量", left + 44, by + 4, INK_DIM, false);
        g.fill(left + 68, by + 1, left + 106, by + 13, 0x28FFFFFF);
        g.fill(left + 68, by + 13, left + 106, by + 14, 0xFF9A7B4C);
        g.drawString(this.font, "单价", left + 112, by + 4, INK_DIM, false);
        g.fill(left + 136, by + 1, left + 174, by + 13, 0x28FFFFFF);
        g.fill(left + 136, by + 13, left + 174, by + 14, 0xFF9A7B4C);
        drawButtonIcon(g, left + 180, by, 76, 16, "挂　单", "list", mouseX, mouseY);
    }

    /** 票据页签：在售提单/仓单 */

    /** 我的页签：订单 + 票据 */

    private void drawPager(GuiGraphics g, int mouseX, int mouseY) {
        int total = filtered().size();
        int pages = Math.max(1, (total + PAGE_ROWS - 1) / PAGE_ROWS);
        if (page >= pages) {
            page = pages - 1;
        }
        int x = left + LIST_X;
        int y = top + PAGE_Y;
        boolean leftHover = mouseX >= x && mouseX < x + 16 && mouseY >= y - 3 && mouseY < y + 13;
        boolean rightHover = mouseX >= x + 72 && mouseX < x + 88 && mouseY >= y - 3 && mouseY < y + 13;
        g.fill(x, y - 3, x + 16, y + 13, leftHover ? 0x40B08A50 : 0);
        drawIcon(g, "page_l", x, y - 3);
        drawIcon(g, "page_r", x + 72, y - 3);
        String mid = (page + 1) + "/" + pages + " (" + total + ")";
        g.drawString(this.font, mid, x + 44 - this.font.width(mid) / 2, y + 2, INK_DIM, false);
    }

    private void drawButtonIcon(GuiGraphics g, int x, int y, int w, int h, String label,
                                String icon, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0xFF6B4E28 : 0xFF4A3418);
        g.fill(x, y, x + w, y + 1, 0xFFB08A50);
        drawIcon(g, icon, x + 3, y + (h - 16) / 2);
        int tw = this.font.width(label);
        g.drawString(this.font, label, x + 20 + (w - 20 - tw) / 2, y + (h - 8) / 2,
                0xFFF3E2C0, false);
    }

    private void drawMini(GuiGraphics g, int x, int y, int w, String label, int mouseX, int mouseY) {
        WoodRender.button(g, this.font, x, y, w, 12, label, mouseX, mouseY, WoodRender.TEXT);
    }

    private void drawIcon(GuiGraphics g, String name, int x, int y) {
        drawIcon(g, name, x, y, 16);
    }

    /** 用真正的 16×16 像素图 + 最近邻，不做插值（没有飞边/黑晕） */
    private void drawIcon(GuiGraphics g, String name, int x, int y, int size) {
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("kjbb",
                "textures/gui/icons/icon_" + name + "_16.png");
        try {
            var tm = Minecraft.getInstance().getTextureManager();
            var tex = tm.getTexture(rl, new net.minecraft.client.renderer.texture.SimpleTexture(rl));
            if (tex != null) {
                tex.setFilter(false, false);
            }
            g.blit(rl, x, y, size, size, 0.0f, 0.0f, 16, 16, 16, 16);
        } catch (Throwable ignored) {
            // 忽略
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

    private static final java.util.Map<String, String> NAME_CACHE = new java.util.HashMap<>();

    private static String nameOf(String id) {
        return NAME_CACHE.computeIfAbsent(id, k -> {
            try {
                Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(k));
                if (it == null) {
                    return shortName(k);
                }
                String n = new ItemStack(it).getHoverName().getString();
                return n == null || n.isEmpty() ? shortName(k) : n;
            } catch (Throwable t) {
                return shortName(k);
            }
        });
    }

    private static String shortName(String id) {
        int c = id.indexOf(':');
        String s = c >= 0 ? id.substring(c + 1) : id;
        return s.length() > 13 ? s.substring(0, 13) : s;
    }

    private int parseCount() {
        try {
            return Math.max(1, Integer.parseInt(fieldCount.getValue().trim()));
        } catch (Throwable t) {
            return 1;
        }
    }

    private List<MarketCache.Market> filtered() {
        String q = fieldSearch == null ? "" : fieldSearch.getValue().trim().toLowerCase();
        if (q.isEmpty()) {
            return MarketCache.markets();
        }
        List<MarketCache.Market> out = new java.util.ArrayList<>();
        for (MarketCache.Market m : MarketCache.markets()) {
            if (m.id().toLowerCase().contains(q) || nameOf(m.id()).toLowerCase().contains(q)) {
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, H, k);
        }
        // 查看所有市场活跃订单
        if (button == 0 && mx >= left + ORD_X && mx < left + ORD_X + 118
                && my >= top + ORD_Y + 78 && my < top + ORD_Y + 94) {
            Minecraft.getInstance().setScreen(new MarketOrdersScreen());
            return true;
        }
        // 右侧物品表：翻页 / 选中
        if (button == 0 && mx >= jeiLeft - 2 && mx < jeiRight + 2) {
            int perPage = JP_COLS * JP_ROWS;
            int pages = Math.max(1, (jeiFiltered.size() + perPage - 1) / perPage);
            int by = jeiTop + H - 16;
            if (my >= by && my < by + 14) {
                if (mx >= jeiLeft + JP_PAD && mx < jeiLeft + JP_PAD + 34) {
                    jeiPage = Math.max(0, jeiPage - 1);
                    return true;
                }
                if (mx >= jeiLeft + JP_PAD + 38 && mx < jeiLeft + JP_PAD + 72) {
                    jeiPage = Math.min(pages - 1, jeiPage + 1);
                    return true;
                }
                if (mx >= jeiRight - JP_PAD - 62 && mx < jeiRight - JP_PAD) {
                    if (!selected.isEmpty()) {
                        final String id = selected;
                        // 先不登记市场：等买单真正下成功，由服务端把它加进市场
                        Minecraft.getInstance().setScreen(new MarketItemScreen(id));
                    }
                    return true;
                }
            }
            int gx = jeiLeft + (JP_W - JP_COLS * JP_SLOT) / 2;
            int gy = jeiTop + 38;
            int from = jeiPage * perPage;
            for (int i = 0; i < perPage; i++) {
                int idx2 = from + i;
                int cx = gx + (i % JP_COLS) * JP_SLOT;
                int cy = gy + (i / JP_COLS) * JP_SLOT;
                if (idx2 < jeiFiltered.size()
                        && mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) {
                    String clickId = jeiFiltered.get(idx2);
                    if (clickId.equals(selected)) {
                        Minecraft.getInstance().setScreen(new MarketItemScreen(clickId));
                        return true;
                    }
                    selected = clickId;
                    MarketCache.setHistory("");
                    MarketCache.setItemOrders("");
                    // 让左边列表立刻跳到这个物品
                    if (fieldSearch != null) {
                        fieldSearch.setValue(nameOf(selected));
                    }
                    page = 0;
                    com.tangtu.kjbb.net.MarketNetwork.requestMarkets();
                    com.tangtu.kjbb.net.MarketNetwork.requestHistory(selected);
                    com.tangtu.kjbb.net.MarketNetwork.requestItemOrders(selected);
                    return true;
                }
            }
        }

        if (button == 0) {
            // 页签：市场 / 投递 / 交易所
            if (my >= top + TITLE_Y - 2 && my < top + TITLE_Y + 10) {
                if (mx >= left + 142 && mx < left + 198) {
                    tab = 0;
                    com.tangtu.kjbb.net.MarketNetwork.requestMarkets();
                    return true;
                }
                if (mx >= left + 198 && mx < left + 254) {
                    tab = 4;
                    com.tangtu.kjbb.net.TxNetwork.sendRequest();
                    return true;
                }
                if (mx >= left + 254 && mx < left + 306) {
                    tab = 2;
                    if (!selected.isEmpty()) {
                        com.tangtu.kjbb.net.MarketNetwork.requestHistory(selected);
                    }
                    return true;
                }
            }
            // 投递记录页：选行 / 翻页
            if (tab == 4) {
                if (mailPanel.click(mx, my, left + 44, top + 80, 236, 6, 15, this.font)) {
                    return true;
                }
            }
            // 买入
            if (mx >= left + 216 && mx < left + 278 && my >= top + BOTTOM_Y - 1
                    && my < top + BOTTOM_Y + 15) {
                buy();
                return true;
            }
            // 交易所页签的「挂单」
            if (tab == 2) {
                int by = top + BIG_Y + BIG_H + 20;
                if (mx >= left + 202 && mx < left + 278 && my >= by && my < by + 16) {
                    placeOffer();
                    return true;
                }
                return true;
            }
            // 服务器订单：点可接的单 → 接单
            int oax = left + 164;
            int oay = top + CHART_Y + CHART_H + 8;
            var itemOrders = MarketCache.itemOrders();
            if (my >= oay + 14 && my < oay + 14 + ORDER_ROWS * ORD_H
                    && mx >= oax - 2 && mx < oax + 126) {
                int idx = (int) ((my - (oay + 14)) / ORD_H);
                int real = orderPage * ORDER_ROWS + idx;
                if (real >= 0 && real < itemOrders.size()) {
                    var o = itemOrders.get(real);
                    com.tangtu.kjbb.net.MarketNetwork.requestOrderDetail(o.id());
                    return true;
                }
            }
            // 订单翻页（右列）
            int opy = oay + 14 + ORDER_ROWS * ORD_H + 2;
            if (my >= opy - 3 && my < opy + 13) {
                int total0 = MarketCache.itemOrders().size();
                int pages0 = Math.max(1, (total0 + ORDER_ROWS - 1) / ORDER_ROWS);
                if (mx >= oax && mx < oax + 16) {
                    if (orderPage > 0) {
                        orderPage--;
                    }
                    return true;
                }
                if (mx >= oax + 100 && mx < oax + 116) {
                    if (orderPage < pages0 - 1) {
                        orderPage++;
                    }
                    return true;
                }
            }
            // 分页
            int px = left + LIST_X;
            int py = top + PAGE_Y;
            if (my >= py - 3 && my < py + 13) {
                if (mx >= px && mx < px + 16) {
                    if (page > 0) {
                        page--;
                    }
                    return true;
                }
                if (mx >= px + 72 && mx < px + 88) {
                    int pages = Math.max(1, (filtered().size() + PAGE_ROWS - 1) / PAGE_ROWS);
                    if (page < pages - 1) {
                        page++;
                    }
                    return true;
                }
            }
            // 商品列表
            List<MarketCache.Market> list = filtered();
            for (int i = 0; i < PAGE_ROWS && page * PAGE_ROWS + i < list.size(); i++) {
                int y = top + LIST_Y + i * ROW_H;
                if (mx >= left + LIST_X - 4 && mx < left + LIST_X + LIST_W
                        && my >= y - 1 && my < y + ROW_H - 2) {
                    String clicked = list.get(page * PAGE_ROWS + i).id();
                    if (clicked.equals(selected)) {
                        // 再点一次同一物品 → 打开下单页
                        Minecraft.getInstance().setScreen(new MarketItemScreen(clicked));
                        return true;
                    }
                    selected = clicked;
                    page = 0;
                    MarketCache.setHistory("");
                    MarketCache.setItemOrders("");
                    com.tangtu.kjbb.net.MarketNetwork.requestHistory(selected);
                    com.tangtu.kjbb.net.MarketNetwork.requestItemOrders(selected);
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private long parsePrice() {
        try {
            return Math.max(1L, Long.parseLong(fieldPrice.getValue().trim()));
        } catch (Throwable t) {
            return 1L;
        }
    }

    private void placeOffer() {
        if (selected.isEmpty()) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("§c先在「市场」页签选一个商品"), false);
            }
            return;
        }
        com.tangtu.kjbb.net.MarketNetwork.placeOffer(selected, parseCount(), parsePrice());
        this.onClose();
    }

    /** 下单用的价格：卖一优先，没有就用最近成交价，再没有用 1 */
    private long buyPrice() {
        for (var m : MarketCache.markets()) {
            if (m.id().equals(selected)) {
                if (m.ask() > 0) {
                    return m.ask();
                }
                if (m.last() > 0) {
                    return m.last();
                }
            }
        }
        return 1L;
    }

    /** 添加物品：一律打开「选择要交易的物品」面板 */
    private void addHeldItem() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.setScreen(new ItemPickerScreen());
    }

    private void buy() {
        if (selected.isEmpty()) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("§c先选一个商品"), false);
            }
            return;
        }
        com.tangtu.kjbb.net.MarketNetwork.buy(selected, parseCount());
        this.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 逻辑分辨率装不下面板时整体等比缩小；鼠标坐标同步换算，绘制与点击永远一致
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k >= 1.0f) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        g.fill(0, 0, this.width, this.height, 0x70000000);
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
        // 空实现：1:1 时 render() 里显式调 super，缩放时改用屏幕坐标的遮罩
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
