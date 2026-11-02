package com.tangtu.kjbb.client;

import com.tangtu.kjbb.net.MenuNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 出货换份额：把玩家自己的背包铺成界面（9×3 主背包 + 快捷栏），点一格选中，
 * 再点「出货」把这一格（或输入的数量）交给当前在招的航次。
 *
 * 指令仍然是 {@code /billvoyage contribute <item> <qty>}，和以前一样，只是把
 * 「从主手拿货」换成了「从背包里挑」。
 */
public class ContributeScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_L = 43;
    private static final int IN_R = 283;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int SLOT = 18;
    private static final int GRID_COLS = 9;
    private static final int GRID_X = IN_L + 39;   // 43 + (240-162)/2
    private static final int GRID_Y = 96;

    private final long billId;
    private int left;
    private int top;
    private int selected = -1;
    private EditBox qtyBox;

    public ContributeScreen(long billId) {
        super(Component.literal("出货换份额"));
        this.billId = billId;
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        qtyBox = new EditBox(this.font, left + IN_L + 96, top + 214, 40, 12,
                Component.literal("\u6570\u91cf"));
        qtyBox.setValue("1");
        qtyBox.setMaxLength(6);
        qtyBox.setBordered(false);
        qtyBox.setTextColor(INK);
        addRenderableWidget(qtyBox);
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

        String title = "\u51fa \u8d27 \u6362 \u4efd \u989d";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + 46, INK,
                false);

        ShippingData.Bill b = ShippingData.bill(billId);
        String sub = b == null ? ("#" + billId)
                : ("#" + b.id + " " + b.name + " \u00b7 \u4ef7 " + b.price);
        g.drawString(this.font, cut(sub, IN_R - IN_L), left + IN_L, top + 64, INK_DIM, false);

        Inventory inv = inventory();
        // 主背包 9×3（读不到背包时只画空格子，预览和异常情况下也能看出布局）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                drawSlot(g, inv, 9 + row * GRID_COLS + col,
                        left + GRID_X + col * SLOT, top + GRID_Y + row * SLOT, mouseX, mouseY);
            }
        }
        // 快捷栏
        int hotY = top + GRID_Y + 3 * SLOT + 8;
        for (int col = 0; col < GRID_COLS; col++) {
            drawSlot(g, inv, col, left + GRID_X + col * SLOT, hotY, mouseX, mouseY);
        }

        // 选中信息
        ItemStack sel = selectedStack(inv);
        int by = top + 210;
        if (sel.isEmpty()) {
            g.drawString(this.font, "\u70b9\u80cc\u5305\u91cc\u4e00\u683c\u9009\u8d27", left + IN_L,
                    by + 3, INK_DIM, false);
        } else {
            String name = sel.getHoverName().getString();
            g.drawString(this.font, cut(name, 90), left + IN_L, by + 3, INK, false);
        }
        g.drawString(this.font, "\u6570\u91cf", left + IN_L + 94, by + 3, INK_DIM, false);
        g.fill(left + IN_L + 94, by + 12, left + IN_L + 138, by + 13, 0xFF9A7B4C);
        button(g, "\u51fa\u8d27", left + IN_L + 144, by, 44, mouseX, mouseY);
        button(g, "\u2190 \u8fd4\u56de", left + IN_R - 58, top + 232, 58, mouseX, mouseY);

        if (sel.isEmpty()) {
            qtyBox.setVisible(false);
        } else {
            qtyBox.setVisible(true);
            qtyBox.setX(left + IN_L + 96);
            qtyBox.setY(by + 1);
        }
    }

    private void drawSlot(GuiGraphics g, Inventory inv, int idx, int x, int y,
                          int mouseX, int mouseY) {
        boolean on = idx == selected;
        g.fill(x, y, x + 16, y + 16, on ? 0xFFB08A50 : 0xFFDCC59E);
        ItemStack st = inv == null ? ItemStack.EMPTY : inv.getItem(idx);
        if (!st.isEmpty()) {
            g.renderItem(st, x, y);
            g.renderItemDecorations(this.font, st, x, y);
        }
    }

    private ItemStack selectedStack(Inventory inv) {
        if (inv == null || selected < 0 || selected >= 36) {
            return ItemStack.EMPTY;
        }
        return inv.getItem(selected);
    }

    private Inventory inventory() {
        var p = Minecraft.getInstance().player;
        return p == null ? null : p.getInventory();
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int mouseX, int mouseY) {
        WoodRender.button(g, this.font, x, y, w, 14, label, mouseX, mouseY, WoodRender.TEXT);
    }

    private String cut(String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (this.font.width(s) <= maxWidth) {
            return s;
        }
        return this.font.plainSubstrByWidth(s, Math.max(6, maxWidth - 6)) + "\u2026";
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
        Inventory inv = inventory();
        if (inv == null) {
            return super.mouseClicked(mx, my, button);
        }

        // 背包格子
        int gx0 = left + GRID_X;
        int gy0 = top + GRID_Y;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int x = gx0 + col * SLOT;
                int y = gy0 + row * SLOT;
                if (ix >= x && ix < x + 16 && iy >= y && iy < y + 16) {
                    pick(9 + row * GRID_COLS + col, inv);
                    return true;
                }
            }
        }
        int hotY = gy0 + 3 * SLOT + 8;
        for (int col = 0; col < GRID_COLS; col++) {
            int x = gx0 + col * SLOT;
            if (ix >= x && ix < x + 16 && iy >= hotY && iy < hotY + 16) {
                pick(col, inv);
                return true;
            }
        }

        // 按钮
        int by = top + 210;
        if (hit(ix, iy, left + IN_L + 144, by, 44, 14)) {
            contribute(inv);
            return true;
        }
        if (hit(ix, iy, left + IN_R - 58, top + 232, 58, 14)) {
            Minecraft.getInstance().setScreen(new ShippingScreen());
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void pick(int idx, Inventory inv) {
        selected = idx;
        ItemStack st = inv.getItem(idx);
        if (!st.isEmpty()) {
            qtyBox.setValue(String.valueOf(st.getCount()));
        }
    }

    private void contribute(Inventory inv) {
        ItemStack st = selectedStack(inv);
        if (st.isEmpty()) {
            hint("\u00a77\u5148\u70b9\u4e00\u683c\u9009\u8d27");
            return;
        }
        int qty;
        try {
            qty = Math.max(1, Integer.parseInt(qtyBox.getValue().trim()));
        } catch (Throwable t) {
            qty = 1;
        }
        qty = Math.min(qty, st.getCount());
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(st.getItem());
        try {
            // 注意：指令是 /billvoyage cargo <物品> <数量>（不是 contribute）
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new MenuNetwork.Action("/billvoyage cargo " + rl + " " + qty));
        } catch (Throwable ignored) {
            // ignore
        }
        Minecraft.getInstance().setScreen(new ShippingScreen());
    }

    private static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void hint(String msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) {
            p.displayClientMessage(Component.literal(msg), false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
