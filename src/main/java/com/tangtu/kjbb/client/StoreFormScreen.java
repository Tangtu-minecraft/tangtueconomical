package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓单界面：拿空白单据右键箱子/保险库 → 列出容器内容 → 选物品开仓单。
 *
 * 排版基于 form_sheet.png（320×291，羊皮纸内框 30..290 / 40..248）。
 */
public class StoreFormScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_L = 43, IN_T = 45, IN_R = 283, IN_B = 246;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;

    private static final int ROW_H = 18;
    private static final int PAGE_ROWS = 7;
    private static final int LIST_X = IN_L + 10;
    private static final int LIST_Y = IN_T + 32;

    public record Entry(String itemId, int count) {
    }

    private static List<Entry> ENTRIES = new ArrayList<>();
    private static BlockPos POS = null;
    private static String DIAG = "";
    private static final Map<String, String> NAME_CACHE = new HashMap<>();

    private int left;
    private int top;
    private int page = 0;
    private String selected = "";
    private EditBox fieldCount;

    public StoreFormScreen() {
        super(Component.literal("仓单"));
    }

    /** 服务器回包时调用 */
    public static void setData(int x, int y, int z, String body, String diag) {
        POS = new BlockPos(x, y, z);
        DIAG = diag == null ? "" : diag;
        List<Entry> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                String[] f = line.split("\\|", -1);
                if (f.length < 2) {
                    continue;
                }
                try {
                    out.add(new Entry(f[0].trim(), Integer.parseInt(f[1].trim())));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        }
        ENTRIES = out;
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static BlockPos pos() {
        return POS;
    }

    @Override
    protected void init() {
        super.init();
        this.left = this.width / 2 - W / 2;
        this.top = this.height / 2 - H / 2;
        try {
            var tm = Minecraft.getInstance().getTextureManager();
            var tex = tm.getTexture(TEX, new net.minecraft.client.renderer.texture.SimpleTexture(TEX));
            if (tex != null) {
                tex.setFilter(true, false);
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        fieldCount = new EditBox(this.font, left + 130, top + IN_B - 9, 34, 9,
                Component.literal("数量"));
        fieldCount.setValue("1");
        fieldCount.setMaxLength(9);
        fieldCount.setBordered(false);
        fieldCount.setTextColor(INK);
        fieldCount.setVisible(false);
        addRenderableWidget(fieldCount);

        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));
    }

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        String title = "仓　单　记　录";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + IN_T + 2, INK, false);
        String sub = POS == null ? "（容器）" : ("容器 " + POS.getX() + ", " + POS.getY() + ", "
                + POS.getZ());
        g.drawString(this.font, sub, left + (W - this.font.width(sub)) / 2, top + IN_T + 14, INK_DIM, false);

        List<Entry> list = filtered();
        if (list.isEmpty()) {
            g.drawString(this.font, "（容器里没有可开单的物品）",
                    left + LIST_X, top + LIST_Y + 6, INK_DIM, false);
        }
        if (!DIAG.isEmpty()) {
            String d0 = DIAG;
            if (this.font.width(d0) > 200) {
                d0 = this.font.plainSubstrByWidth(d0, 190) + "…";
            }
            g.drawString(this.font, d0, left + LIST_X + 2, top + LIST_Y - 4, 0xFF9A7B4C, false);
        }
        int from = page * PAGE_ROWS;
        for (int i = 0; i < PAGE_ROWS && from + i < list.size(); i++) {
            Entry e = list.get(from + i);
            int y = top + LIST_Y + 14 + i * ROW_H;
            boolean sel = e.itemId().equals(selected);
            if (sel) {
                g.fill(left + LIST_X - 3, y - 1, left + IN_R - 8, y + ROW_H - 3, 0x40B08A50);
            }
            ItemStack icon = iconOf(e.itemId());
            if (!icon.isEmpty()) {
                g.renderItem(icon, left + LIST_X - 1, y);
            }
            g.drawString(this.font, nameOf(e.itemId()), left + LIST_X + 20, y + 1, sel ? INK : INK_DIM, false);
            String right = "×" + e.count();
            g.drawString(this.font, right, left + IN_R - 12 - this.font.width(right), y + 1,
                    sel ? INK : INK_DIM, false);
        }

        // 分页
        int py = top + LIST_Y + 14 + PAGE_ROWS * ROW_H + 2;
        int pages = Math.max(1, (list.size() + PAGE_ROWS - 1) / PAGE_ROWS);
        int px = left + LIST_X;
        boolean lh = mouseX >= px && mouseX < px + 12 && mouseY >= py && mouseY < py + 10;
        boolean rh = mouseX >= px + 56 && mouseX < px + 68 && mouseY >= py && mouseY < py + 10;
        g.fill(px, py, px + 12, py + 10, lh ? 0xFF6B4E28 : 0x405A3F1E);
        g.drawString(this.font, "<", px + 4, py + 1, 0xFFF3E2C0, false);
        String mid = (page + 1) + "/" + pages;
        g.drawString(this.font, mid, px + 34 - this.font.width(mid) / 2, py + 1, INK_DIM, false);
        g.fill(px + 56, py, px + 68, py + 10, rh ? 0xFF6B4E28 : 0x405A3F1E);
        g.drawString(this.font, ">", px + 60, py + 1, 0xFFF3E2C0, false);

        // 底部：已选 + 数量 + 开仓单
        int barY = top + IN_B - 14;
        g.drawString(this.font, "整箱开单：票面 = 容器全部内容", left + LIST_X, barY + 4, INK_DIM, false);
        drawButton(g, left + 190, barY, 50, 14, "记名单", mouseX, mouseY);
        drawButton(g, left + 223, barY, 56, 14, "不记名单", mouseX, mouseY);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label,
                            int mouseX, int mouseY) {
        WoodRender.button(g, this.font, x, y, w, h, label, mouseX, mouseY, WoodRender.TEXT);
    }

    private List<Entry> filtered() {
        return ENTRIES;
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
        return s.length() > 14 ? s.substring(0, 14) : s;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, H, k);
        }
        if (button == 0) {
            // 分页
            int py = top + LIST_Y + 14 + PAGE_ROWS * ROW_H + 2;
            int px = left + LIST_X;
            if (my >= py && my < py + 10) {
                if (mx >= px && mx < px + 12) {
                    if (page > 0) {
                        page--;
                    }
                    return true;
                }
                if (mx >= px + 56 && mx < px + 68) {
                    int pages = Math.max(1, (filtered().size() + PAGE_ROWS - 1) / PAGE_ROWS);
                    if (page < pages - 1) {
                        page++;
                    }
                    return true;
                }
            }
            // 开单
            int barY2 = top + IN_B - 14;
            if (my >= barY2 && my < barY2 + 14) {
                if (mx >= left + 190 && mx < left + 240) {
                    issue(false);
                    return true;
                }
                if (mx >= left + 244 && mx < left + 300) {
                    issue(true);
                    return true;
                }
            }

        }
        return super.mouseClicked(mx, my, button);
    }

    private void issue(boolean bearer) {
        if (POS == null) {
            return;
        }
        String itemId = "";
        int cnt = 0;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.tangtu.kjbb.net.StoreFormNetwork.IssueReceipt(
                        POS.getX(), POS.getY(), POS.getZ(), bearer, itemId, cnt));
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
