package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 单据挂卖小窗：从背包里点提单/仓单后弹出，填价格直接挂到票据市场
 */
public class TicketSellScreen extends Screen {

    private static final int PW = 260, PH = 158;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;

    private final ItemStack ticket;
    private final int slot;

    private EditBox fieldPrice;
    private int left;
    private int top;

    public TicketSellScreen(ItemStack ticket, int slot) {
        super(Component.literal("\u6302\u5356\u7968\u636e"));
        this.ticket = ticket == null ? ItemStack.EMPTY : ticket.copy();
        this.slot = slot;
    }

    @Override
    protected void init() {
        super.init();
        this.left = this.width / 2 - PW / 2;
        this.top = this.height / 2 - PH / 2;

        fieldPrice = new EditBox(this.font, left + 84, top + 62, 70, 12,
                Component.literal("\u4ef7\u683c"));
        fieldPrice.setValue("100");
        fieldPrice.setMaxLength(9);
        fieldPrice.setBordered(false);
        fieldPrice.setTextColor(INK);
        addRenderableWidget(fieldPrice);

        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));
    }

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(left - 2, top - 2, left + PW + 2, top + PH + 2, 0xFF4A3418);
        g.fill(left, top, left + PW, top + PH, 0xFFF3E2C4);
        g.fill(left, top, left + PW, top + 18, 0xFFE4CFA8);

        String title = "\u6302\u5356\u7968\u636e";
        g.drawString(this.font, title, left + (PW - this.font.width(title)) / 2, top + 5,
                INK, false);

        if (!ticket.isEmpty()) {
            g.renderItem(ticket, left + 16, top + 30);
            g.renderItemDecorations(this.font, ticket, left + 16, top + 30);
            String name = ticket.getHoverName().getString();
            g.drawString(this.font, name, left + 40, top + 34, INK, false);
        }

        g.drawString(this.font, "\u5355\u4ef7", left + 52, top + 66, INK_DIM, false);
        g.fill(left + 82, top + 61, left + 156, top + 75, 0x30FFFFFF);
        g.fill(left + 82, top + 75, left + 156, top + 76, 0xFF9A7B4C);

        drawWillingness(g);

        WoodRender.button(g, this.font, left + 60, top + PH - 30, 84, 18,
                "\u6302\u5356", mouseX, mouseY, WoodRender.TEXT);
        WoodRender.button(g, this.font, left + 150, top + PH - 30, 84, 18,
                "\u53d6\u6d88", mouseX, mouseY, WoodRender.TEXT);
    }

    /**
     * 唐兔国国民购买意愿（实时跟着单价输入变化）
     *
     *   折价 = 输入价 ÷ 票据内货物估值
     *   ≤50% 高 / ≤70% 中 / ≤85% 低 / >85% 不会买
     */
    private void drawWillingness(GuiGraphics g) {
        long worth = estimateWorth();
        int y = top + 84;

        g.drawString(this.font, "\u5510\u5154\u56fd\u6c11\u8d2d\u4e70\u610f\u613f", left + 30, y,
                INK_DIM, false);

        if (worth <= 0L) {
            g.drawString(this.font, "\u2014", left + 150, y, INK_DIM, false);
            return;
        }

        long price = 100L;
        try {
            price = Math.max(1L, Long.parseLong(fieldPrice.getValue().trim()));
        } catch (Throwable ignored) {
            // 用默认
        }
        double ratio = (double) price / (double) worth;

        String txt;
        int color;
        if (ratio <= 0.50) {
            txt = "\u9ad8";
            color = 0xFFC05000;
        } else if (ratio <= 0.70) {
            txt = "\u4e2d";
            color = 0xFF1F6FB0;
        } else if (ratio <= 0.85) {
            txt = "\u4f4e";
            color = 0xFF8A8A8A;
        } else {
            txt = "\u4f4e\uff08\u4e0d\u4f1a\u4e70\uff09";
            color = 0xFF8A8A8A;
        }
        g.drawString(this.font, txt, left + 150, y, color, false);

        String hint = "\u4f30\u503c " + worth + "\uff0c\u6536\u8d2d\u4e0a\u9650 "
                + (worth * 85L / 100L);
        g.drawString(this.font, hint, left + 30, y + 12, INK_DIM, false);
    }

    /** 票据内货物估值（仓单读记录，提单读 BillData） */
    private long estimateWorth() {
        long worth = 0L;
        try {
            String kind = com.tangtu.kjbb.TicketMarket.kindOf(ticket);
            if ("RECEIPT".equals(kind)) {
                for (var line : com.tangtu.kjbb.item.StoreReceiptItem.readRecord(ticket)) {
                    worth += unitPrice(line.itemId()) * Math.max(0, line.count());
                }
            } else if (kind != null) {
                var bd = com.tangtu.kjbb.item.KjbbItems.getBill(ticket);
                if (bd != null) {
                    worth += unitPrice(bd.itemId()) * Math.max(0, bd.count());
                }
            }
        } catch (Throwable ignored) {
            worth = 0L;
        }
        return worth;
    }

    /** 物品单价：市场价 → 基准价 → 0（无价按 64 件=1 由调用方处理） */
    private static long unitPrice(String itemId) {
        if (itemId == null) {
            return 0L;
        }
        try {
            for (var m : MarketCache.markets()) {
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
            long p = com.tangtu.kjbb.ShopPrices.price(itemId);
            if (p > 0) {
                return p;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0L;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 遮罩必须画在压入缩放之前，否则会跟着面板一起缩小
        g.fill(0, 0, this.width, this.height, 0x70000000);
        // 逻辑分辨率装不下面板时整体等比缩小；鼠标坐标同步换算，绘制与点击永远一致
        float k = UiScale.factor(this.width, this.height, PW, PH, 4);
        if (k >= 1.0f) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        UiScale.push(g, left, top, PW, PH, k);
        try {
            super.render(g, (int) UiScale.mapX(mouseX, left, PW, k),
                    (int) UiScale.mapY(mouseY, top, PH, k), partialTick);
        } finally {
            UiScale.pop(g);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 空实现：遮罩已在 render() 里按屏幕坐标画好，否则会被缩放一起缩掉
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, PW, PH, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, PW, k);
            my = UiScale.mapY(my, top, PH, k);
        }
        if (button == 0) {
            int by = top + PH - 30;
            if (mx >= left + 60 && mx < left + 144 && my >= by && my < by + 18) {
                long price = 100L;
                try {
                    price = Math.max(1L, Long.parseLong(fieldPrice.getValue().trim()));
                } catch (Throwable ignored) {
                    // 用默认
                }
                try {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.tangtu.kjbb.net.MenuNetwork.Action(
                                    "/ticket listslot " + slot + " " + price));
                } catch (Throwable ignored) {
                    // ignore
                }
                Minecraft.getInstance().setScreen(new TicketMarketScreen());
                return true;
            }
            if (mx >= left + 150 && mx < left + 234 && my >= by && my < by + 18) {
                Minecraft.getInstance().setScreen(new TicketMarketScreen());
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
