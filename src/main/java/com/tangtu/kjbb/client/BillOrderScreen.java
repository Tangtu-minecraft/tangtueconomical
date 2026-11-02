package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「下单」界面 —— 完全按机械动力「仓储请求」界面的三段式贴图拼出来：
 *
 *   头部 HEADER  stock_keeper.png (0,  0) 256×36
 *   身体 BODY    stock_keeper.png (0, 48) 256×20  （纵向平铺）
 *   底部 FOOTER  stock_keeper.png (0, 80) 256×80  （含已选条 + 地址条 + 确认按钮）
 *
 * 唯一改动：底部右侧那个灰色确认按钮上的文字换成「下　单」。
 */
public class BillOrderScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("create", "textures/gui/stock_keeper.png");

    private static final int W = 256;
    private static final int HDR_H = 36;
    private static final int FTR_H = 80;
    private static final int BODY_UNIT = 20;

    private static final int COLS = 9;
    private static final int ROWS = 5;
    private static final int CELL = 18;

    private static final int WINDOW_H = HDR_H + ROWS * CELL + 6 + FTR_H;

    private final BlockPos tickerPos;
    private final Map<String, Integer> order = new LinkedHashMap<>();

    private int left;
    private int top;
    private EditBox fieldAddr;

    public BillOrderScreen(BlockPos pos) {
        super(Component.literal("下单"));
        this.tickerPos = pos;
    }

    @Override
    protected void init() {
        super.init();
        this.left = this.width / 2 - W / 2;
        this.top = this.height / 2 - WINDOW_H / 2;

        try {
            var tm = Minecraft.getInstance().getTextureManager();
            var tex = tm.getTexture(TEX,
                    new net.minecraft.client.renderer.texture.SimpleTexture(TEX));
            if (tex != null) {
                tex.setFilter(false, false);
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        // 底部 FOOTER 里的米色地址条
        fieldAddr = new EditBox(this.font, left + 20, top + WINDOW_H - FTR_H + 36, 130, 10,
                Component.literal("地址"));
        fieldAddr.setValue("");
        fieldAddr.setMaxLength(32);
        fieldAddr.setBordered(false);
        fieldAddr.setTextColor(0x3A2A18);
        addRenderableWidget(fieldAddr);

        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));

        try {
            com.tangtu.kjbb.net.TickerBillNetwork.requestStore(tickerPos);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 绘制

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        drawFrame(g);

        // 标题
        g.drawString(this.font, "仓储下单", left + 14, top + 12, 0x3A2A18, false);

        // 货物网格（用原版的 18×18 槽位贴图）
        List<StoreCache.Entry> list = StoreCache.entries();
        int gx = left + 16;
        int gy = top + HDR_H + 2;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = row * COLS + col;
                int x = gx + col * CELL;
                int y = gy + row * CELL;
                g.blit(TEX, x, y, 0, 32, 200, CELL, CELL, 256, 256);
                if (idx >= list.size()) {
                    continue;
                }
                StoreCache.Entry e = list.get(idx);
                ItemStack st = InvCache.stackOf(e.id());
                if (!st.isEmpty()) {
                    g.renderItem(st, x + 1, y + 1);
                    g.renderItemDecorations(this.font, st, x + 1, y + 1,
                            e.count() > 1 ? String.valueOf(Math.min(e.count(), 64)) : "");
                }
                if (order.containsKey(e.id())) {
                    g.fill(x, y, x + CELL - 1, y + CELL - 1, 0x60FFD060);
                }
            }
        }

        // 已选汇总（写在网格下方）
        long total = 0L;
        StringBuilder sb = new StringBuilder();
        for (var e : order.entrySet()) {
            total += unitPriceOf(e.getKey()) * e.getValue();
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(shortName(e.getKey())).append('×').append(e.getValue());
        }
        String info = order.isEmpty() ? "左键选货 +1 ／ 右键 +10 ／ Shift 左键 -1"
                : fit(sb.toString(), 40);
        g.drawString(this.font, info, gx, gy + ROWS * CELL + 3, 0x6A6058, false);

        // 底部：合计（地址框由 EditBox 画）
        g.drawString(this.font, total + " 唐兔币", left + 156, top + WINDOW_H - FTR_H + 37,
                0x8A5A18, false);

        // 确认按钮上的「下单」（按钮底图在 FOOTER 里已经有了）
        String label = "下　单";
        g.drawString(this.font, label, left + W - 62, top + WINDOW_H - FTR_H + 37,
                0x3A2A18, false);
    }

    private void drawFrame(GuiGraphics g) {
        // 头部
        g.blit(TEX, left, top, 0, 0, 0, W, HDR_H, 256, 256);
        // 身体：纵向平铺
        int bodyTop = top + HDR_H;
        int bodyBottom = top + WINDOW_H - FTR_H;
        for (int y = bodyTop; y < bodyBottom; y += BODY_UNIT) {
            int h = Math.min(BODY_UNIT, bodyBottom - y);
            g.blit(TEX, left, y, 0, 0, 48, W, h, 256, 256);
        }
        // 底部
        g.blit(TEX, left, top + WINDOW_H - FTR_H, 0, 0, 80, W, FTR_H, 256, 256);
    }

    private static String shortName(String id) {
        int c = id.indexOf(':');
        String s = c >= 0 ? id.substring(c + 1) : id;
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static String fit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private long unitPriceOf(String id) {
        for (StoreCache.Entry e : StoreCache.entries()) {
            if (e.id().equals(id)) {
                return e.unit();
            }
        }
        return 0L;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, W, WINDOW_H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, WINDOW_H, k);
        }
        // 确认按钮：FOOTER 右侧灰色条
        int bx = left + 160;
        int by = top + WINDOW_H - FTR_H + 30;
        if (button == 0 && mx >= bx && mx < bx + 88 && my >= by && my < by + 22) {
            if (!order.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (var e : order.entrySet()) {
                    if (sb.length() > 0) {
                        sb.append(';');
                    }
                    sb.append(e.getKey()).append(':').append(e.getValue());
                }
                com.tangtu.kjbb.net.OrderNetwork.submit(tickerPos, sb.toString());
                this.onClose();
            }
            return true;
        }

        int gx = left + 16;
        int gy = top + HDR_H + 2;
        int col = ((int) mx - gx) / CELL;
        int row = ((int) my - gy) / CELL;
        if (mx >= gx && my >= gy && col >= 0 && col < COLS && row >= 0 && row < ROWS) {
            int idx = row * COLS + col;
            List<StoreCache.Entry> list = StoreCache.entries();
            if (idx < list.size()) {
                StoreCache.Entry e = list.get(idx);
                if (button == 0) {
                    int add = Minecraft.getInstance().options.keyShift.isDown() ? -1 : 1;
                    int cur = order.getOrDefault(e.id(), 0) + add;
                    if (cur <= 0) {
                        order.remove(e.id());
                    } else {
                        order.put(e.id(), Math.min(cur, e.count()));
                    }
                } else if (button == 1) {
                    order.put(e.id(), Math.min(order.getOrDefault(e.id(), 0) + 10, e.count()));
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 逻辑分辨率装不下面板时整体等比缩小；鼠标坐标同步换算，绘制与点击永远一致
        float k = UiScale.factor(this.width, this.height, W, WINDOW_H, 4);
        if (k >= 1.0f) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        g.fill(0, 0, this.width, this.height, 0x70000000);
        UiScale.push(g, left, top, W, WINDOW_H, k);
        try {
            super.render(g, (int) UiScale.mapX(mouseX, left, W, k),
                    (int) UiScale.mapY(mouseY, top, WINDOW_H, k), partialTick);
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
