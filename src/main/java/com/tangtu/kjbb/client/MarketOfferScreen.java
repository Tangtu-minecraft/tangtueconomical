package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 卖单详情页（可直接买下）
 *
 * 显示：物品、卖家、数量、单价、总价、已挂时长、剩余有效期
 * 操作：输入数量 → 【买　下】→ /billmarket buy &lt;id&gt; &lt;数量&gt;
 */
public class MarketOfferScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;

    private static final int IN_L = 44, IN_R = 276, IN_T = 45, IN_B = 246;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int SELL_COLOR = 0xFFB03030;
    private static final int OK_COLOR = 0xFF2E7D32;

    private final MarketCache.OpenOrder offer;

    private int left;
    private int top;

    /** 数量行基线（相对 top） */
    private int QTY_Y() {
        return top + 182;
    }
    private EditBox fieldCount;
    private String hint = "";

    public MarketOfferScreen(MarketCache.OpenOrder offer) {
        super(Component.literal("\u5356\u5355\u8be6\u60c5"));
        this.offer = offer;
    }

    @Override
    protected void init() {
        super.init();
        this.left = this.width / 2 - W / 2;
        this.top = this.height / 2 - H / 2;

        fieldCount = new EditBox(this.font, left + IN_L + 52, QTY_Y() - 4, 66, 12,
                Component.literal("\u6570\u91cf"));
        fieldCount.setValue(String.valueOf(offer == null ? 1 : Math.max(1, offer.count())));
        fieldCount.setMaxLength(9);
        fieldCount.setBordered(false);
        fieldCount.setTextColor(INK);
        addRenderableWidget(fieldCount);

        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));
    }

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        String title = "\u5356 \u5355 \u8be6 \u60c5";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + IN_T + 2,
                INK, false);

        if (offer == null) {
            g.drawString(this.font, "\uff08\u5356\u5355\u5df2\u4e0d\u5b58\u5728\uff09", left + IN_L,
                    top + IN_T + 24, INK_DIM, false);
            WoodRender.button(g, this.font, left + IN_L, top + IN_B - 22, 86, 16,
                    "\u2190 \u8fd4\u56de", mouseX, mouseY, WoodRender.TEXT);
            return;
        }

        int x = left + IN_L;
        int y = top + IN_T + 12;

        ItemStack st = iconOf(offer.itemId());
        if (!st.isEmpty()) {
            g.renderItem(st, x, y - 1);
        }
        g.drawString(this.font, shortName(offer.itemId(), 150), x + 22, y + 2, INK, false);
        y += 17;

        g.drawString(this.font, "\u5356\u5355\uff08\u4e70\u8d70\uff09", x, y, SELL_COLOR, false);
        y += 12;

        y = row(g, "\u7f16\u53f7", "#" + offer.id(), x, y);
        y = row(g, "\u5356\u5bb6", offer.owner() == null ? "?" : offer.owner(), x, y);
        y = row(g, "\u6570\u91cf", offer.count() + " \u4e2a", x, y);
        y = row(g, "\u5355\u4ef7", offer.unit() + " \u5510\u5154\u5e01", x, y);
        y = row(g, "\u603b\u4ef7", (offer.unit() * offer.count()) + " \u5510\u5154\u5e01", x, y);

        long ageMs = offer.created() > 0
                ? Math.max(0L, nowMs() - offer.created()) : 0L;
        long ageMin = ageMs / 60_000L;
        y = row(g, "\u5df2\u6302", ageMin + " \u5206\u949f", x, y);
        if (offer.created() > 0) {
            long remain = 30L - ageMin;
            y = row(g, "\u5269\u4f59\u6709\u6548",
                    remain > 0 ? (remain + " \u5206\u949f") : "\u5df2\u8fc7\u671f", x, y);
        }

        // ---- 数量 + 买下 区 ----
        int qy = QTY_Y();
        g.fill(x, qy - 6, x + IN_R - IN_L, qy - 5, 0x40B08A50);
        g.drawString(this.font, "\u6570\u91cf", x, qy, INK_DIM, false);
        g.fill(x + 50, qy - 5, x + 120, qy + 9, 0x30FFFFFF);
        g.fill(x + 50, qy + 9, x + 120, qy + 10, 0xFF9A7B4C);
        WoodRender.button(g, this.font, x + 128, qy - 6, 76, 18,
                "\u4e70\u3000\u4e0b", mouseX, mouseY, WoodRender.TEXT);

        String tip = hint.isEmpty()
                ? "\u4e70\u4e0b\u540e\u8d27\u7269\u4f1a\u9001\u5230\u4f60\u7684\u63d0\u8d27\u70b9"
                : hint;
        g.drawString(this.font, trim(tip, IN_R - IN_L - 6), x, qy + 18, INK_DIM, false);

        WoodRender.button(g, this.font, x, top + IN_B - 22, 86, 16,
                "\u2190 \u8fd4\u56de", mouseX, mouseY, WoodRender.TEXT);
    }

    private int row(GuiGraphics g, String k, String v, int x, int y) {
        g.drawString(this.font, k, x, y, INK_DIM, false);
        g.drawString(this.font, v, x + 58, y, INK, false);
        return y + 12;
    }

    private String trim(String s, int maxW) {
        if (s == null) {
            return "";
        }
        if (this.font.width(s) <= maxW) {
            return s;
        }
        return this.font.plainSubstrByWidth(s, maxW - 6) + "\u2026";
    }

    private static ItemStack iconOf(String id) {
        try {
            return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id)));
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
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

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, H, k);
        }
        if (button == 0 && offer != null) {
            int x = left + IN_L;
            int qy = QTY_Y();
            // 买下
            if (mx >= x + 128 && mx < x + 204 && my >= qy - 6 && my < qy + 12) {
                int want;
                try {
                    want = Math.max(1, Integer.parseInt(fieldCount.getValue().trim()));
                } catch (Throwable ignored) {
                    want = 1;
                }
                want = Math.min(want, offer.count());
                String cmd = "/billmarket buy " + offer.id() + " " + want;
                try {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.tangtu.kjbb.net.MenuNetwork.Action(cmd));
                } catch (Throwable ignored) {
                    // ignore
                }
                com.tangtu.kjbb.net.MarketNetwork.requestOpenOrders();
                Minecraft.getInstance().setScreen(new MarketOrdersScreen());
                return true;
            }
            // 返回
            if (mx >= x && mx < x + 86 && my >= top + IN_B - 22 && my < top + IN_B - 6) {
                Minecraft.getInstance().setScreen(new MarketOrdersScreen());
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
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

    /** 与服务器一致的"现在"：游戏时钟源时用游戏时间，否则用真实时间 */
    private static long nowMs() {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                String src = "game";
                try {
                    src = com.tangtu.kjbb.BotConfig.get().timeSource;
                } catch (Throwable ignored) {
                    // ignore
                }
                if (!"real".equalsIgnoreCase(src)) {
                    return mc.level.getGameTime() * 50L;
                }
            }
        } catch (Throwable ignored) {
            // 客户端类不可用时退回真实时间
        }
        return System.currentTimeMillis();
    }
}
