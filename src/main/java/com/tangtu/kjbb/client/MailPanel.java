package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 「投递记录」列表 + 详情（交易中心第三页与市场界面共用）。
 *
 * <p>数据来自 {@link TxData}（由 {@code net/TxNetwork} 下发）。
 * 画法：表头右侧带页码与翻页箭头，点一行在下方展开详情。
 */
public final class MailPanel {

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int GOOD = 0x2E5B2E;
    private static final int WARN = 0xFF9A5A00;

    /** 选中的行号（-1 = 没选）与页码 */
    private int sel = -1;
    private int page;

    public void reset() {
        sel = -1;
        page = 0;
    }

    /** 预览工具用：直接选中某一行，好把详情画出来 */
    public void select(int idx) {
        this.sel = idx;
    }

    /**
     * @param x      内容左界
     * @param y      列表首行
     * @param w      内容宽度
     * @param rows   一页几行
     * @param rowH   行高
     * @param detailTop 详情区顶端（<=0 表示不画详情）
     */
    public void draw(GuiGraphics g, Font font, int x, int y, int w, int rows, int rowH,
                     int detailTop, int mouseX, int mouseY) {
        List<TxData.Mail> list = TxData.mails();
        int pages = Math.max(1, (list.size() + rows - 1) / rows);
        if (page >= pages) {
            page = 0;
        }

        // 表头：左标题，右侧页码 + 翻页
        String head = "\u6700\u8fd1\u6295\u9012";
        g.drawString(font, head, x, y - 12, INK, false);
        String pageTxt = (page + 1) + "/" + pages + "  \u5171 " + list.size() + " \u6761";
        int pw = font.width(pageTxt);
        g.drawString(font, pageTxt, x + w - pw, y - 12, 0x9A8A70, false);
        nav(g, font, x + w - pw - 34, y - 14, "<", mouseX, mouseY);
        nav(g, font, x + w - pw - 16, y - 14, ">", mouseX, mouseY);

        if (list.isEmpty()) {
            g.drawString(font, "\uff08\u8fd8\u6ca1\u6709\u6295\u9012\u8bb0\u5f55\uff09", x + 4, y,
                    INK_DIM, false);
            return;
        }

        int from = page * rows;
        for (int i = 0; i < rows && from + i < list.size(); i++) {
            int idx = from + i;
            TxData.Mail m = list.get(idx);
            int ry = y + i * rowH;
            if (idx == sel) {
                g.fill(x - 2, ry - 1, x + w, ry + rowH - 2, 0x30B08A50);
            }
            String time = m.time == null ? "" : m.time;
            if (time.length() > 10) {
                time = time.substring(5);   // 去掉年份，省地方
            }
            g.drawString(font, cut(font, time, w * 29 / 100), x, ry, INK_DIM, false);
            int tx = x + w * 31 / 100;
            g.drawString(font, cut(font, m.seller + " \u2192 " + m.buyer, w * 36 / 100), tx, ry,
                    INK, false);
            int ix = x + w * 68 / 100;
            ItemStack ic = iconOf(m.item);
            if (!ic.isEmpty()) {
                g.renderItem(ic, ix, ry - 1);
            }
            boolean chain = m.mode != null && (m.mode.contains("\u9501") || m.mode.contains("\u7269\u6d41"));
            String mode = chain ? "\u7269\u6d41\u4e2d" : "\u6392\u961f";
            g.drawString(font, mode, ix + 20, ry, chain ? GOOD : WARN, false);
            String qty = "\u00d7" + m.count;
            g.drawString(font, qty, x + w - font.width(qty), ry, INK_DIM, false);
        }

        if (detailTop <= 0) {
            return;
        }
        TxData.Mail selMail = sel >= 0 && sel < list.size() ? list.get(sel) : null;
        if (selMail == null) {
            g.drawString(font, "\u70b9\u4e00\u884c\u770b\u8be6\u60c5", x, detailTop, INK_DIM, false);
            return;
        }
        drawDetail(g, font, selMail, x, x + w, detailTop);
    }

    private void drawDetail(GuiGraphics g, Font font, TxData.Mail m, int x, int right, int y) {
        g.fill(x - 2, y - 6, right, y + 44, 0x1FB08A50);
        g.fill(x - 2, y - 6, right, y - 5, 0x669A7B4C);

        String me = me();
        boolean iAmBuyer = me.equals(m.buyer);
        boolean iAmSeller = me.equals(m.seller);
        String tag = iAmBuyer ? "\uff08\u5bc4\u7ed9\u6211\uff09"
                : (iAmSeller ? "\uff08\u6211\u5356\u51fa\uff09" : "");
        g.drawString(font, "\u65f6\u95f4 " + (m.time == null ? "" : m.time) + "  " + tag, x, y,
                INK, false);

        g.drawString(font, "\u5356\u5bb6 " + m.seller, x, y + 12, INK_DIM, false);
        g.drawString(font, "\u2192", x + 104, y + 12, INK_DIM, false);
        g.drawString(font, "\u4e70\u5bb6 " + m.buyer, x + 116, y + 12, INK_DIM, false);

        ItemStack ic = iconOf(m.item);
        if (!ic.isEmpty()) {
            g.renderItem(ic, x, y + 24);
        }
        String itemLine = nameOf(m.item) + " \u00d7" + m.count;
        g.drawString(font, cut(font, itemLine, 120), x + 20, y + 24, INK, false);
        g.drawString(font, cut(font, m.item, 96), x + 146, y + 24, 0x9A8A70, false);

        boolean chain = m.mode != null && (m.mode.contains("\u9501") || m.mode.contains("\u7269\u6d41"));
        String modeLine = chain
                ? "\u7269\u6d41\u4e2d\uff1a\u5df2\u4ea4\u7ed9\u7269\u6d41\u7cfb\u7edf\uff0c\u8fd8\u5728\u8fd0\u8f93"
                : "\u6392\u961f\uff1a\u63d0\u8d27\u70b9\u5f53\u65f6\u672a\u52a0\u8f7d\uff0c\u52a0\u8f7d\u540e\u81ea\u52a8\u6295\u9012";
        g.drawString(font, cut(font, modeLine, Math.max(40, right - x - 6)), x, y + 36,
                chain ? GOOD : WARN, false);
    }

    /** 点了哪个位置；返回 true 表示这次点击属于本面板（含翻页） */
    public boolean click(double mx, double my, int x, int y, int w, int rows, int rowH, Font font) {
        List<TxData.Mail> list = TxData.mails();
        int from = page * rows;
        for (int i = 0; i < rows && from + i < list.size(); i++) {
            int ry = y + i * rowH;
            if (my >= ry - 1 && my < ry + rowH - 2) {
                sel = (sel == from + i) ? -1 : (from + i);
                return true;
            }
        }
        int pages = Math.max(1, (list.size() + rows - 1) / rows);
        String pageTxt = (page + 1) + "/" + pages + "  \u5171 " + list.size() + " \u6761";
        int nx = x + w - font.width(pageTxt) - 34;
        if (my >= y - 14 && my < y - 2) {
            if (mx >= nx && mx < nx + 14) {
                page = Math.max(0, page - 1);
                sel = -1;
                return true;
            }
            if (mx >= nx + 18 && mx < nx + 32) {
                page = Math.min(pages - 1, page + 1);
                sel = -1;
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 小工具

    private void nav(GuiGraphics g, Font font, int x, int y, String label, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + 14 && mouseY >= y && mouseY < y + 12;
        g.fill(x, y, x + 14, y + 12, hover ? 0x805A3F1E : 0x40B08A50);
        g.drawString(font, label, x + (14 - font.width(label)) / 2, y + 2, INK, false);
    }

    private static String cut(Font font, String s, int maxW) {
        if (s == null) {
            return "";
        }
        if (font.width(s) <= maxW) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(6, maxW - 6)) + "\u2026";
    }

    private static String me() {
        try {
            var p = Minecraft.getInstance().player;
            return p == null ? "" : p.getScoreboardName();
        } catch (Throwable t) {
            return "";
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
}
