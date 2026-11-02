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

import java.util.ArrayList;
import java.util.List;

/**
 * 物品选择器（市场「＋添加」用）
 *
 * 打开后列出所有物品，可以搜索；点一个物品选中，再点【添　加】把它加入市场。
 * 行为上等价于 JEI 里挑物品，但不依赖 JEI，单人档 / 服务器都能用。
 */
public class ItemPickerScreen extends Screen {

    private static final int COLS = 9;
    private static final int ROWS = 6;
    private static final int SLOT = 20;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int PAPER = 0xFFF3E2C4;
    private static final int PAPER_DARK = 0xFFE4CFA8;

    private EditBox search;
    private String query = "";
    private int page = 0;

    private final List<ResourceLocation> all = new ArrayList<>();
    private List<ResourceLocation> filtered = new ArrayList<>();

    private String selected = "";

    private int left;
    private int top;
    private int panelW = 300;
    private int panelH = 226;

    public ItemPickerScreen() {
        super(Component.literal("\u9009\u62e9\u7269\u54c1"));
        for (ResourceLocation rl : BuiltInRegistries.ITEM.keySet()) {
            Item it = BuiltInRegistries.ITEM.get(rl);
            if (it == null) {
                continue;
            }
            if (new ItemStack(it).isEmpty()) {
                continue;
            }
            all.add(rl);
        }
        all.sort((a, b) -> a.toString().compareTo(b.toString()));
        refilter();
    }

    private void refilter() {
        List<ResourceLocation> out = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase();
        for (ResourceLocation rl : all) {
            if (q.isEmpty()) {
                out.add(rl);
                continue;
            }
            String id = rl.toString().toLowerCase();
            String name = "";
            try {
                name = new ItemStack(BuiltInRegistries.ITEM.get(rl))
                        .getHoverName().getString().toLowerCase();
            } catch (Throwable ignored) {
                // ignore
            }
            if (id.contains(q) || name.contains(q)) {
                out.add(rl);
            }
        }
        filtered = out;
        if (page * (COLS * ROWS) >= filtered.size()) {
            page = 0;
        }
    }

    @Override
    protected void init() {
        super.init();
        this.left = this.width / 2 - panelW / 2;
        this.top = this.height / 2 - panelH / 2;

        this.search = new EditBox(this.font, left + 12, top + 26, panelW - 24, 14,
                Component.literal("\u641c\u7d22"));
        this.search.setMaxLength(40);
        this.search.setValue(query);
        this.search.setResponder(t -> {
            query = t;
            page = 0;
            refilter();
        });
        this.addRenderableWidget(this.search);

        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));
    }

    private int gridX() {
        return left + (panelW - COLS * SLOT) / 2;
    }

    private int gridY() {
        return top + 48;
    }

    private void drawAll(GuiGraphics g, int mx, int my) {
        // 纸面板
        g.fill(left - 2, top - 2, left + panelW + 2, top + panelH + 2, 0xFF4A3418);
        g.fill(left, top, left + panelW, top + panelH, PAPER);
        g.fill(left, top, left + panelW, top + 20, PAPER_DARK);

        String title = "\u9009\u62e9\u8981\u4ea4\u6613\u7684\u7269\u54c1";
        g.drawString(this.font, title, left + (panelW - this.font.width(title)) / 2,
                top + 6, INK, false);

        // 搜索框边框
        g.fill(left + 11, top + 25, left + panelW - 11, top + 41, 0xFF9A7B4C);

        int gx = gridX();
        int gy = gridY();
        int perPage = COLS * ROWS;
        int from = page * perPage;

        for (int i = 0; i < perPage; i++) {
            int idx = from + i;
            int cx = gx + (i % COLS) * SLOT;
            int cy = gy + (i / COLS) * SLOT;
            boolean hover = mx >= cx && mx < cx + 18 && my >= cy && my < cy + 18;
            if (hover) {
                g.fill(cx - 1, cy - 1, cx + 19, cy + 19, 0x40FFFFFF);
            }
            if (idx >= filtered.size()) {
                continue;
            }
            ResourceLocation rl = filtered.get(idx);
            ItemStack st = new ItemStack(BuiltInRegistries.ITEM.get(rl));
            if (st.isEmpty()) {
                continue;
            }
            g.renderItem(st, cx, cy);
            if (rl.toString().equals(selected)) {
                g.fill(cx - 1, cy - 1, cx + 19, cy - 1 + 2, 0xFF2E7D32);
                g.fill(cx - 1, cy + 17, cx + 19, cy + 19, 0xFF2E7D32);
                g.fill(cx - 1, cy - 1, cx - 1 + 2, cy + 19, 0xFF2E7D32);
                g.fill(cx + 17, cy - 1, cx + 19, cy + 19, 0xFF2E7D32);
            }
        }

        // 选中信息 + 分页
        String info = selected.isEmpty() ? "\uff08\u70b9\u4e00\u4e2a\u7269\u54c1\u9009\u4e2d\uff09"
                : nameOf(selected) + "  " + selected;
        g.drawString(this.font, cut(info, panelW - 20), left + 12, top + panelH - 44,
                selected.isEmpty() ? INK_DIM : INK, false);

        int pages = Math.max(1, (filtered.size() + perPage - 1) / perPage);
        String pageTxt = (page + 1) + "/" + pages + "  \u5171 " + filtered.size() + " \u9879";
        g.drawString(this.font, pageTxt, left + 12, top + panelH - 30, INK_DIM, false);

        // 按钮
        drawBtn(g, left + 12, top + panelH - 20, 46, "\u4e0a\u4e00\u9875", mx, my);
        drawBtn(g, left + 62, top + panelH - 20, 46, "\u4e0b\u4e00\u9875", mx, my);
        drawBtn(g, left + panelW - 186, top + panelH - 20, 50, "\u624b\u6301", mx, my);
        drawBtn(g, left + panelW - 130, top + panelH - 20, 56, "\u6dfb\u52a0", mx, my);
        drawBtn(g, left + panelW - 70, top + panelH - 20, 56, "\u53d6\u6d88", mx, my);

        // 悬停提示
        for (int i = 0; i < perPage; i++) {
            int idx = from + i;
            int cx = gx + (i % COLS) * SLOT;
            int cy = gy + (i / COLS) * SLOT;
            if (mx >= cx && mx < cx + 18 && my >= cy && my < cy + 18 && idx < filtered.size()) {
                String tip = nameOf(filtered.get(idx).toString());
                g.renderTooltip(this.font, Component.literal(tip), mx, my);
            }
        }
    }

    private void drawBtn(GuiGraphics g, int x, int y, int w, String label, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? 0xFF8A6A3A : 0xFF4A3418);
        int tw = this.font.width(label);
        g.drawString(this.font, label, x + (w - tw) / 2, y + 3, 0xFFF3E2C0, false);
    }

    private String cut(String s, int maxW) {
        if (this.font.width(s) <= maxW) {
            return s;
        }
        return this.font.plainSubstrByWidth(s, maxW - 6) + "\u2026";
    }

    private static String nameOf(String id) {
        try {
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
            if (it == null) {
                return id;
            }
            String n = new ItemStack(it).getHoverName().getString();
            return n == null || n.isEmpty() ? id : n;
        } catch (Throwable t) {
            return id;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int gx = gridX();
            int gy = gridY();
            int perPage = COLS * ROWS;
            int from = page * perPage;
            for (int i = 0; i < perPage; i++) {
                int idx = from + i;
                int cx = gx + (i % COLS) * SLOT;
                int cy = gy + (i / COLS) * SLOT;
                if (mx >= cx && mx < cx + 18 && my >= cy && my < cy + 18 && idx < filtered.size()) {
                    selected = filtered.get(idx).toString();
                    return true;
                }
            }
            int by = top + panelH - 20;
            if (mx >= left + 12 && mx < left + 58 && my >= by && my < by + 14) {
                page = Math.max(0, page - 1);
                return true;
            }
            if (mx >= left + 62 && mx < left + 108 && my >= by && my < by + 14) {
                int pages = Math.max(1, (filtered.size() + perPage - 1) / perPage);
                page = Math.min(pages - 1, page + 1);
                return true;
            }
            // 手持物品：直接把主手那件加进市场
            if (mx >= left + panelW - 186 && mx < left + panelW - 136
                    && my >= by && my < by + 14) {
                var p = Minecraft.getInstance().player;
                if (p != null) {
                    ItemStack held = p.getMainHandItem();
                    if (held != null && !held.isEmpty()) {
                        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(held.getItem());
                        if (rl != null) {
                            com.tangtu.kjbb.net.MarketNetwork.addItem(rl.toString());
                            Minecraft.getInstance().setScreen(new MarketScreen());
                            return true;
                        }
                    }
                    p.displayClientMessage(Component.literal("\u00a7c\u624b\u4e0a\u6ca1\u6709\u7269\u54c1"), false);
                }
                return true;
            }
            if (mx >= left + panelW - 130 && mx < left + panelW - 74 && my >= by && my < by + 14) {
                if (!selected.isEmpty()) {
                    com.tangtu.kjbb.net.MarketNetwork.addItem(selected);
                    Minecraft.getInstance().setScreen(new MarketScreen());
                }
                return true;
            }
            if (mx >= left + panelW - 70 && mx < left + panelW - 14 && my >= by && my < by + 14) {
                Minecraft.getInstance().setScreen(new MarketScreen());
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
