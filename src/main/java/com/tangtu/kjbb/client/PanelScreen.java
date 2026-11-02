package com.tangtu.kjbb.client;

import com.tangtu.kjbb.net.PanelNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 交易面板：上半区「我的挂牌」，下半区提示，底部自绘按钮条。
 *
 * 全部用同一次渲染画完（不叠加原版控件），所以视觉风格统一。
 * 坐标对应 1x 贴图 176×155。
 */
public class PanelScreen extends Screen {

    private static final ResourceLocation PANEL =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/panel.png");

    /** 4x 贴图：按 GUI 缩放 1:1 绘制，避免插值糊 */
    private static final ResourceLocation PANEL_4X =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/panel4x.png");

    private static final int TEX_W = 176;
    private static final int TEX_H = 155;

    private static final int TITLE_Y = 11;
    private static final int TOP_X = 12, TOP_Y = 24;
    private static final int BOT_X = 12, BOT_Y = 84;

    private static final int ROWS_PER_PAGE = 4;

    // 底部按钮（1x 坐标，相对面板左上角）
    private static final int BTN_Y = 140, BTN_H = 12;
    private static final int BTN_PREV_X = 12, BTN_PREV_W = 16;
    private static final int BTN_NEXT_X = 30, BTN_NEXT_W = 16;
    private static final int BTN_REFRESH_X = 50, BTN_REFRESH_W = 34;
    private static final int BTN_CLOSE_X = 136, BTN_CLOSE_W = 28;

    private int left;
    private int top;
    private int page;

    public PanelScreen() {
        super(Component.literal("交易面板"));
    }

    @Override
    protected void init() {
        super.init();
        this.left = this.width / 2 - TEX_W / 2;
        this.top = this.height / 2 - TEX_H / 2;
        if (PanelData.lines().isEmpty()) {
            PanelData.set("读取中……");
            PanelNetwork.sendRequest();
        }
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);

        // ① 面板底图：用 4x 贴图 1:1 映射到屏幕像素，避免被线性插值
        drawPanel(g);

        // ② 标题
        String title = "我的挂牌";
        g.drawString(this.font, title, this.left + (TEX_W - this.font.width(title)) / 2,
                this.top + TITLE_Y, 0x2B2B2B, false);

        // ③ 上半区：待放款 + 列表
        List<String> lines = PanelData.lines();
        g.drawString(this.font, "待放款 " + pendingOf(lines) + " 单", this.left + TOP_X,
                this.top + TOP_Y - 2, 0x2C5D8F, false);

        int drawn = 0;
        int idx = 0;
        for (String line : lines) {
            if (line.startsWith("待放款")) {
                continue;
            }
            if (idx++ < this.page * ROWS_PER_PAGE) {
                continue;
            }
            if (drawn >= ROWS_PER_PAGE) {
                break;
            }
            g.drawString(this.font, "· " + PanelData.pretty(line), this.left + TOP_X,
                    this.top + TOP_Y + 12 + drawn * 9, 0x333333, false);
            drawn++;
        }
        if (drawn == 0) {
            g.drawString(this.font, lines.isEmpty() ? "读取中……" : "（没有挂牌）",
                    this.left + TOP_X, this.top + TOP_Y + 12, 0x6A6A6A, false);
        }

        // ④ 下半区：提示
        g.drawString(this.font, "提示", this.left + BOT_X, this.top + BOT_Y, 0x3A3A3A, false);
        g.drawString(this.font, "拿货找商店管理员挂牌", this.left + BOT_X, this.top + BOT_Y + 12, 0x555555, false);
        g.drawString(this.font, "卖出后钱自动进余额", this.left + BOT_X, this.top + BOT_Y + 22, 0x555555, false);
        g.drawString(this.font, "第 " + (this.page + 1) + " 页", this.left + BOT_X, this.top + BOT_Y + 34, 0x777777, false);

        // ⑤ 底部自绘按钮（同一图层）
        drawButton(g, BTN_PREV_X, BTN_PREV_W, "<", mouseX, mouseY);
        drawButton(g, BTN_NEXT_X, BTN_NEXT_W, ">", mouseX, mouseY);
        drawButton(g, BTN_REFRESH_X, BTN_REFRESH_W, "刷新", mouseX, mouseY);
        drawButton(g, BTN_CLOSE_X, BTN_CLOSE_W, "关闭", mouseX, mouseY);
    }

    /** 底图：4x 贴图 + 位姿缩放，实现设备像素 1:1（GUI 缩放 4 时完全不插值） */
    private void drawPanel(GuiGraphics g) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        int scale = 1;
        try {
            scale = Math.max(1, (int) Math.round(mc.getWindow().getGuiScale()));
        } catch (Throwable ignored) {
            scale = 1;
        }

        // 只在 scale 能被 4 整除时用 4x（4 的约数：1/2/4）
        boolean use4x = (scale == 1 || scale == 2 || scale == 4);
        var pose = g.pose();
        if (use4x) {
            pose.pushPose();
            pose.scale(1.0f / scale, 1.0f / scale, 1.0f);
            g.blit(PANEL_4X,
                    this.left * scale, this.top * scale,
                    0, 0,
                    TEX_W * 4, TEX_H * 4,
                    TEX_W * 4, TEX_H * 4);
            pose.popPose();
        } else {
            g.blit(PANEL, this.left, this.top, 0, 0, TEX_W, TEX_H, TEX_W, TEX_H);
        }
    }

    /** 自绘按钮：与面板同一套像素风格（黑边 + 高光 + 灰底） */
    private void drawButton(GuiGraphics g, int relX, int relW, String label, int mouseX, int mouseY) {
        int x = this.left + relX;
        int y = this.top + BTN_Y;
        WoodRender.button(g, this.font, x, y, relW, BTN_H, label, mouseX, mouseY, WoodRender.TEXT);
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (hit(mx, my, BTN_PREV_X, BTN_PREV_W)) {
                if (this.page > 0) {
                    this.page--;
                }
                return true;
            }
            if (hit(mx, my, BTN_NEXT_X, BTN_NEXT_W)) {
                this.page++;
                return true;
            }
            if (hit(mx, my, BTN_REFRESH_X, BTN_REFRESH_W)) {
                PanelData.set("读取中……");
                PanelNetwork.sendRequest();
                return true;
            }
            if (hit(mx, my, BTN_CLOSE_X, BTN_CLOSE_W)) {
                this.onClose();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean hit(double mx, double my, int relX, int relW) {
        int x = this.left + relX;
        int y = this.top + BTN_Y;
        return mx >= x && mx < x + relW && my >= y && my < y + BTN_H;
    }

    private static String pendingOf(List<String> lines) {
        for (String s : lines) {
            if (s.startsWith("待放款")) {
                return s.replace("待放款", "").replace("单", "").trim();
            }
        }
        return "?";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}