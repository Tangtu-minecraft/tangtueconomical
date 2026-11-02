package com.tangtu.kjbb.client;

import com.tangtu.kjbb.net.StatsNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 统计局面板：货币 / 价格 / 交易 / 国库一页看完。
 *
 * 数据由服务端 {@code EconReport.stats()} 现算（跟 econ_report.txt 同源），
 * 客户端只负责排版；面板沿用 form_sheet 羊皮纸 + UiScale 自适应缩放。
 */
public class StatsScreen extends Screen {

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
    private static final int HEAD = 0x8A4B12;

    private static final int HEAD_H = 16;
    private static final int ROW_H = 12;

    private int left;
    private int top;
    private int page;

    public StatsScreen() {
        super(Component.literal("唐兔国统计"));
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        StatsNetwork.sendRequest();
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (UiScale.factor(this.width, this.height, W, H, 4) >= 1.0f) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            drawAll(g, mouseX, mouseY);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        g.fill(0, 0, this.width, this.height, 0x70000000);
        float k = UiScale.factor(this.width, this.height, W, H, 4);
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
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        String title = "\u5510\u5154\u56fd\u7edf\u8ba1";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + IN_T,
                INK, false);

        String[] meta = StatsData.meta();
        if (meta != null) {
            g.drawString(this.font, meta[1], left + IN_L + 2, top + IN_T + 13, INK_DIM, false);
            g.drawString(this.font, meta[2], left + IN_R - this.font.width(meta[2]),
                    top + IN_T + 13, INK_DIM, false);
        }

        List<String[]> body = StatsData.body();
        if (body.isEmpty()) {
            g.drawString(this.font, "\u6b63\u5728\u8bfb\u53d6\u2026\u2026", left + IN_L + 8,
                    top + IN_T + 34, INK_DIM, false);
        } else {
            List<int[]> ranges = pageRanges(body);
            int[] range = ranges.get(Math.min(page, ranges.size() - 1));
            int y = top + IN_T + 30;
            int limit = top + IN_B - 24;
            for (int i = range[0]; i < range[1]; i++) {
                String[] line = body.get(i);
                boolean head = "h".equals(line[0]);
                int h = head ? HEAD_H : ROW_H;
                if (y + h > limit) {
                    break;
                }
                if (head) {
                    g.fill(left + IN_L, y + h - 4, left + IN_R, y + h - 3, 0x60B08A50);
                    g.drawString(this.font, line[1], left + IN_L, y + 2, HEAD, false);
                } else {
                    g.drawString(this.font, line[1], left + IN_L + 8, y + 3, INK_DIM, false);
                    String v = line[2] == null ? "" : line[2];
                    g.drawString(this.font, v, left + IN_R - this.font.width(v), y + 3, INK,
                            false);
                }
                y += h;
            }
        }

        // 数据出处（脚注）
        String foot = "\u6570\u636e\u73b0\u7b97 \u00b7 \u4e0e econ_report.txt \u540c\u6e90";
        g.drawString(this.font, foot, left + (W - this.font.width(foot)) / 2,
                top + IN_B - 30, 0x9A8A70, false);

        // 底部：刷新 / 返回 / 页码 / 翻页
        int by = top + IN_B - 16;
        drawBtn(g, left + IN_L, by, 48, "\u5237\u65b0", mouseX, mouseY);
        drawBtn(g, left + IN_L + 56, by, 48, "\u2190 \u8fd4\u56de", mouseX, mouseY);
        String pageTxt = (page + 1) + "/" + pageRanges(body).size();
        g.drawString(this.font, pageTxt,
                left + IN_R - 104 - this.font.width(pageTxt) / 2, by + 3, INK_DIM, false);
        drawBtn(g, left + IN_R - 72, by, 32, "<", mouseX, mouseY);
        drawBtn(g, left + IN_R - 36, by, 32, ">", mouseX, mouseY);
    }

    /**
     * 按「整节」分页：一节 = 一个小标题 + 它的若干行。整节放不下就换页，
     * 单节比一页还高才按行切 —— 这样不会出现标题落在页尾、或某节被切成两页。
     * 返回 [start, end) 列表。
     */
    private static List<int[]> pageRanges(List<String[]> body) {
        List<int[]> out = new ArrayList<>();
        if (body.isEmpty()) {
            out.add(new int[]{0, 0});
            return out;
        }
        final int avail = (IN_B - 24) - (IN_T + 30);
        int start = 0;
        int used = 0;
        int i = 0;
        while (i < body.size()) {
            int j = i + 1;
            while (j < body.size() && !"h".equals(body.get(j)[0])) {
                j++;
            }
            int sectionH = 0;
            for (int k = i; k < j; k++) {
                sectionH += "h".equals(body.get(k)[0]) ? HEAD_H : ROW_H;
            }
            if (used > 0 && used + sectionH > avail) {
                out.add(new int[]{start, i});
                start = i;
                used = 0;
            }
            if (sectionH > avail) {
                for (int k = i; k < j; k++) {
                    int h = "h".equals(body.get(k)[0]) ? HEAD_H : ROW_H;
                    if (used > 0 && used + h > avail) {
                        out.add(new int[]{start, k});
                        start = k;
                        used = 0;
                    }
                    used += h;
                }
            } else {
                used += sectionH;
            }
            i = j;
        }
        if (start < body.size() || out.isEmpty()) {
            out.add(new int[]{start, body.size()});
        }
        return out;
    }

    private void drawBtn(GuiGraphics g, int x, int y, int w, String label,
                         int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? 0x805A3F1E : 0x40B08A50);
        g.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + 3,
                hover ? 0xFFF3E2C0 : INK, false);
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, H, k);
        }
        if (button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        int by = top + IN_B - 16;
        if (my >= by && my < by + 14) {
            if (mx >= left + IN_L && mx < left + IN_L + 48) {
                StatsData.set("");
                StatsNetwork.sendRequest();
                return true;
            }
            if (mx >= left + IN_L + 56 && mx < left + IN_L + 104) {
                net.minecraft.client.Minecraft.getInstance().setScreen(new FormCenterScreen());
                return true;
            }
            int pages = pageRanges(StatsData.body()).size();
            if (mx >= left + IN_R - 72 && mx < left + IN_R - 40) {
                page = Math.max(0, page - 1);
                return true;
            }
            if (mx >= left + IN_R - 36 && mx < left + IN_R - 4) {
                page = Math.min(pages - 1, page + 1);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 供外部（测试/调试）拿到正文行 */
    public static List<String[]> bodyLines() {
        return new ArrayList<>(StatsData.body());
    }
}
