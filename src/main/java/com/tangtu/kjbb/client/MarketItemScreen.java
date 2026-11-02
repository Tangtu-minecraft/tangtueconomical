package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 单个物品的下单页（和市场经济同款羊皮纸底图）
 *
 * 显示：物品图标 / 名称 / 近期买卖价
 * 操作：下单价格（输入条 + 加减）、下单数量（输入条 + 加减）、确认下单
 */
public class MarketItemScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/trade_sheet.png");
    private static final int W = 320, H = 320;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int UP = 0xFF2E7D32;
    private static final int DOWN = 0xFFB03030;

    private final String itemId;

    private EditBox fieldPrice;
    private EditBox fieldCount;

    private int left;
    private int top;

    public MarketItemScreen(String itemId) {
        super(Component.literal("\u4e0b\u5355"));
        this.itemId = itemId == null ? "" : itemId;
    }

    @Override
    protected void init() {
        super.init();
        this.left = this.width / 2 - W / 2;
        this.top = this.height / 2 - H / 2;

        fieldPrice = new EditBox(this.font, left + 118, top + 150, 84, 12,
                Component.literal("\u4ef7\u683c"));
        fieldPrice.setValue(String.valueOf(Math.max(1L, suggestPrice())));
        fieldPrice.setMaxLength(9);
        fieldPrice.setBordered(false);
        fieldPrice.setTextColor(INK);
        addRenderableWidget(fieldPrice);

        fieldCount = new EditBox(this.font, left + 118, top + 180, 84, 12,
                Component.literal("\u6570\u91cf"));
        fieldCount.setValue("1");
        fieldCount.setMaxLength(9);
        fieldCount.setBordered(false);
        fieldCount.setTextColor(INK);
        addRenderableWidget(fieldCount);

        com.tangtu.kjbb.net.MarketNetwork.requestWillingness(itemId);
        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));
    }

    /** 建议价：卖一 → 最近成交 → 1 */
    private long suggestPrice() {
        try {
            for (MarketCache.Market m : MarketCache.markets()) {
                if (m.id().equals(itemId)) {
                    if (m.ask() > 0) {
                        return m.ask();
                    }
                    if (m.last() > 0) {
                        return m.last();
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            List<MarketCache.Candle> cs = MarketCache.history();
            if (!cs.isEmpty()) {
                return Math.max(1L, cs.get(cs.size() - 1).close());
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 1L;
    }

    private void drawAll(GuiGraphics g, int mx, int my) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1254, 1254, 1254, 1254);

        // 图标 + 名称
        ItemStack st = ItemStack.EMPTY;
        try {
            st = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId)));
        } catch (Throwable ignored) {
            // ignore
        }
        String name = st.isEmpty() ? itemId : st.getHoverName().getString();
        if (!st.isEmpty()) {
            g.renderItem(st, left + 44, top + 46);
            g.renderItemDecorations(this.font, st, left + 44, top + 46);
        }
        g.drawString(this.font, name, left + 68, top + 50, INK, false);
        g.drawString(this.font, itemId, left + 68, top + 62, INK_DIM, false);

        // 近期买卖价
        long sell = 0L, buy = 0L, last = 0L;
        try {
            for (MarketCache.Market m : MarketCache.markets()) {
                if (m.id().equals(itemId)) {
                    sell = m.ask();
                    buy = m.bid();
                    last = m.last();
                    break;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            List<MarketCache.Candle> cs = MarketCache.history();
            if (!cs.isEmpty()) {
                last = cs.get(cs.size() - 1).close();
            }
        } catch (Throwable ignored) {
            // ignore
        }

        g.drawString(this.font, "\u8fd1\u671f\u4e70\u5356", left + 44, top + 88, INK_DIM, false);
        String priceLine = "\u5356\u4e00 " + (sell > 0 ? sell : "\u2014")
                + "    \u4e70\u4e00 " + (buy > 0 ? buy : "\u2014")
                + "    \u6700\u8fd1 " + (last > 0 ? last : "\u2014");
        g.drawString(this.font, priceLine, left + 44, top + 102, INK, false);

        // 唐兔国国民购买意愿（随价格与数量实时变化）
        int[] willInfo = willingnessNow();
        int will = willInfo[0];
        String willTxt;
        int willColor;
        if (will < 0) {
            willTxt = "\u2026";
            willColor = INK_DIM;
        } else if (will == 2) {
            willTxt = "\u9ad8";
            willColor = 0xFFC05000;
        } else if (will == 1) {
            willTxt = "\u4e2d";
            willColor = 0xFF1F6FB0;
        } else {
            willTxt = "\u4f4e";
            willColor = 0xFF8A8A8A;
        }
        g.drawString(this.font, "\u5510\u5154\u56fd\u6c11\u8d2d\u4e70\u610f\u613f", left + 44, top + 122, INK, false);
        g.drawString(this.font, willTxt, left + 150, top + 122, willColor, false);


        // 下单价格
        g.drawString(this.font, "\u4e0b\u5355\u4ef7\u683c", left + 44, top + 140, INK, false);
        drawStep(g, left + 96, top + 148, "-", mx, my);
        g.fill(left + 114, top + 149, left + 206, top + 163, 0x28FFFFFF);
        g.fill(left + 114, top + 163, left + 206, top + 164, 0xFF9A7B4C);
        drawStep(g, left + 210, top + 148, "+", mx, my);

        // 下单数量
        g.drawString(this.font, "\u4e0b\u5355\u6570\u91cf", left + 44, top + 170, INK, false);
        drawStep(g, left + 96, top + 178, "-", mx, my);
        g.fill(left + 114, top + 179, left + 206, top + 193, 0x28FFFFFF);
        g.fill(left + 114, top + 193, left + 206, top + 194, 0xFF9A7B4C);
        drawStep(g, left + 210, top + 178, "+", mx, my);

        // 合计
        long count = parse(fieldCount, 1);
        long unit = parse(fieldPrice, 1);
        long total = count * unit;
        boolean afford = total <= 0 || true;
        String sum = "\u5408\u8ba1 " + total + " \u5510\u5154\u5e01";
        g.drawString(this.font, sum, left + 44, top + 204, afford ? INK : DOWN, false);


        // 按钮
        WoodRender.button(g, this.font, left + 44, top + 240, 96, 18,
                "\u786e\u8ba4\u4e0b\u5355", mx, my, WoodRender.TEXT);
        WoodRender.button(g, this.font, left + 150, top + 240, 96, 18,
                "\u2190 \u8fd4\u56de", mx, my, WoodRender.TEXT);

        g.drawString(this.font, "\u4e0b\u5355\u540e\u94b1\u88ab\u6258\u7ba1\uff0c\u5305\u88f9\u5230\u8fbe\u624d\u4ed8\u7ed9\u5356\u5bb6",
                left + 44, top + 268, INK_DIM, false);
    }


    private void drawStep(GuiGraphics g, int x, int y, String label, int mx, int my) {
        boolean hover = mx >= x && mx < x + 16 && my >= y && my < y + 16;
        g.fill(x, y, x + 16, y + 16, hover ? 0xFF8A6A3A : 0xFF4A3418);
        g.drawString(this.font, label, x + 5, y + 4, 0xFFF3E2C0, false);
    }

    /** 参考价：卖一 → 最近成交 → 0 */
    private long refPrice() {
        try {
            for (MarketCache.Market m : MarketCache.markets()) {
                if (m.id().equals(itemId)) {
                    if (m.ask() > 0) {
                        return m.ask();
                    }
                    if (m.last() > 0) {
                        return m.last();
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0L;
    }

    /**
     * 当前意愿：基础意愿（服务端给的物品属性）+ 出价修正 + 数量修正
     *
     * @return -1 未知，0 低，1 中，2 高；第二项是修正说明
     */
    private int[] willingnessNow() {
        int p0 = MarketCache.fillProb(itemId);
        if (p0 < 0) {
            return new int[]{-1, 0};
        }
        long unit = parse(fieldPrice, 1);
        long count = parse(fieldCount, 1);
        long ref = refPrice();

        // 从「参考价下的接单概率」出发，按玩家出价滑动
        double p = p0 / 100.0;
        if (ref > 0) {
            double ratio = (double) unit / (double) ref;
            double base01 = Math.max(0.01, Math.min(0.99, p));
            double logit = Math.log(base01 / (1.0 - base01)) + 3.0 * (ratio - 1.0);
            p = 1.0 / (1.0 + Math.exp(-logit));
        }
        // 数量越多越难一次吃下
        if (count > 256) {
            p *= 0.55;
        } else if (count > 64) {
            p *= 0.80;
        }
        p = Math.max(0.0, Math.min(0.95, p));

        int level = p >= 0.60 ? 2 : (p >= 0.30 ? 1 : 0);
        return new int[]{level, 0};
    }

    private long parse(EditBox box, long fallback) {
        try {
            return Math.max(1L, Long.parseLong(box.getValue().trim()));
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void bump(EditBox box, long delta) {
        long v = parse(box, 1) + delta;
        box.setValue(String.valueOf(Math.max(1L, v)));
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
            // 价格加减
            if (mx >= left + 96 && mx < left + 112 && my >= top + 148 && my < top + 164) {
                bump(fieldPrice, -1);
                return true;
            }
            if (mx >= left + 210 && mx < left + 226 && my >= top + 148 && my < top + 164) {
                bump(fieldPrice, +1);
                return true;
            }
            // 数量加减
            if (mx >= left + 96 && mx < left + 112 && my >= top + 178 && my < top + 194) {
                bump(fieldCount, -1);
                return true;
            }
            if (mx >= left + 210 && mx < left + 226 && my >= top + 178 && my < top + 194) {
                bump(fieldCount, +1);
                return true;
            }
            // 确认下单
            if (mx >= left + 44 && mx < left + 140 && my >= top + 240 && my < top + 258) {
                long unit = parse(fieldPrice, 1);
                long count = parse(fieldCount, 1);
                com.tangtu.kjbb.net.MarketNetwork.buyOrder(itemId, (int) count, unit);
                Minecraft.getInstance().setScreen(new MarketScreen());
                return true;
            }
            // 返回市场
            if (mx >= left + 150 && mx < left + 246 && my >= top + 240 && my < top + 258) {
                Minecraft.getInstance().setScreen(new FormCenterScreen());
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
}
