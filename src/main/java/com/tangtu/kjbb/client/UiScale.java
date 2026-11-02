package com.tangtu.kjbb.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 面板自适应缩放
 *
 * 界面里的羊皮纸面板是固定尺寸（320x291 / 320x320），逻辑分辨率一变小
 * （窗口小 + GUI 缩放 2、或 1280x720 开缩放 3）就会上下被切掉。
 *
 * 做法：面板放不下时，围绕面板中心整体等比缩小，同时把鼠标坐标按同一
 * 比例反算回去。这样绘制和点击判定永远一致，界面代码一行都不用改。
 *
 * 用法（界面里）：
 * <pre>
 *   float k = UiScale.factor(this.height, H, 4);
 *   if (k < 1.0f) {
 *       UiScale.push(g, left, top, W, H, k);
 *       ...正常绘制（鼠标坐标用 UiScale.mapX/mapY 换算）...
 *       UiScale.pop(g);
 *   } else {
 *       ...正常绘制...
 *   }
 * </pre>
 */
public final class UiScale {

    /** 再怎么小也不缩到 55% 以下，否则字就糊了 */
    private static final float MIN = 0.55f;

    private UiScale() {
    }

    /** 面板放得下返回 1.0，放不下返回等比缩放系数（只看高度） */
    public static float factor(int screenHeight, int panelHeight, int margin) {
        if (panelHeight <= 0) {
            return 1.0f;
        }
        int avail = screenHeight - margin * 2;
        if (avail >= panelHeight) {
            return 1.0f;
        }
        float k = avail / (float) panelHeight;
        return Math.max(MIN, k);
    }

    /**
     * 宽高一起看：面板 320 宽，但逻辑宽度也会不够（854 窗口 + 缩放 3 = 284，
     * 1280 窗口 + 缩放 4 = 320），只按高度算依然会左右出框。
     */
    public static float factor(int screenWidth, int screenHeight,
                               int panelWidth, int panelHeight, int margin) {
        if (panelWidth <= 0 || panelHeight <= 0) {
            return 1.0f;
        }
        float kw = (screenWidth - margin * 2) / (float) panelWidth;
        float kh = (screenHeight - margin * 2) / (float) panelHeight;
        float k = Math.min(kw, kh);
        if (k >= 1.0f) {
            return 1.0f;
        }
        return Math.max(MIN, k);
    }

    /** 围绕面板中心压入缩放变换 */
    public static void push(GuiGraphics g, int left, int top, int w, int h, float k) {
        float cx = left + w / 2.0f;
        float cy = top + h / 2.0f;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0f);
        g.pose().scale(k, k, 1.0f);
        g.pose().translate(-cx, -cy, 0.0f);
    }

    public static void pop(GuiGraphics g) {
        g.pose().popPose();
    }

    /** 屏幕坐标 -> 面板坐标（x） */
    public static double mapX(double mx, int left, int w, float k) {
        float cx = left + w / 2.0f;
        return cx + (mx - cx) / k;
    }

    /** 屏幕坐标 -> 面板坐标（y） */
    public static double mapY(double my, int top, int h, float k) {
        float cy = top + h / 2.0f;
        return cy + (my - cy) / k;
    }
}
