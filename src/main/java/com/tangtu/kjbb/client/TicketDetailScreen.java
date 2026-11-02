package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 票据详情：提单显示物品/数量/收货地址；仓单显示容器坐标 + 物品清单。
 */
public class TicketDetailScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_T = 45, IN_R = 283, IN_B = 246;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;

    private int left;
    private int top;

    public TicketDetailScreen() {
        super(Component.literal("票据详情"));
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
        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));
    }

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        var d = MarketCache.ticketDetail();
        boolean bill = d != null && "BILL".equals(d.kind());
        String title = bill ? "提 单 详 情" : "仓 单 详 情";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + IN_T + 8,
                INK, false);

        if (d == null) {
            g.drawString(this.font, "（票据已不存在）", left + 50, top + IN_T + 34, INK_DIM, false);
            drawButton(g, left + IN_R - 86, top + IN_B - 20, 70, 16, "关　闭", mouseX, mouseY);
            return;
        }

        int x = left + 50;
        int y = top + IN_T + 32;

        g.drawString(this.font, bill ? "提单" : "仓单", x, y, INK, false);
        g.drawString(this.font, d.bearer() ? "不记名" : "记名",
                x + 40, y, d.bearer() ? 0xFF1F6FB0 : 0xFFB07000, false);
        y += 14;
        y = row(g, "挂卖编号", "#" + d.id(), x, y, INK);
        y = row(g, "价格", d.price() + " 唐兔币", x, y, 0xFF2E5B2E);
        y = row(g, "卖家", d.seller(), x, y, INK);
        y = row(g, "状态", "ON_SALE".equals(d.status()) ? "在售" : d.status(), x, y, INK);
        y += 4;

        if (bill) {
            y = row(g, "货物", niceName(d.itemId()) + " ×" + d.count(), x, y, INK);
            y = row(g, "收货地址", d.pos() == null || d.pos().isEmpty() ? "（未登记）" : d.pos(),
                    x, y, INK);
            long unit = d.count() > 0 ? Math.max(1L, d.price() / d.count()) : d.price();
            y = row(g, "单件折算", unit + " 唐兔币/个", x, y, INK_DIM);
            drawWillingness(g, d, x, y + 4);
            y += 14;
            g.drawString(this.font, "成交后单件价格会记入该物品的 K 线", x, y + 4, INK_DIM, false);
        } else {
            y = row(g, "容器坐标", d.pos().isEmpty() ? "?" : d.pos(), x, y, INK);
            g.drawString(this.font, "容器内容", x, y, INK_DIM, false);
            y += 13;
            int i = 0;
            for (String part : d.items().split(";")) {
                int eq = part.lastIndexOf('=');
                if (eq <= 0 || i >= 6) {
                    continue;
                }
                String id = part.substring(0, eq);
                String cnt = part.substring(eq + 1);
                ItemStack icon = iconOf(id);
                if (!icon.isEmpty()) {
                    g.renderItem(icon, x, y - 2);
                }
                g.drawString(this.font, niceName(id), x + 20, y, INK, false);
                g.drawString(this.font, "×" + cnt, x + 150, y, INK_DIM, false);
                y += 14;
                i++;
            }
            if (d.items().isEmpty()) {
                g.drawString(this.font, "（无记录）", x + 20, y, INK_DIM, false);
                y += 14;
            }
            // 唐兔国民购买意愿（按挂牌价 / 货物估值）
            drawWillingness(g, d, x, y + 4);
            y += 14;
            g.drawString(this.font, "成交后容器锁会转给买家", x, y + 4, INK_DIM, false);
        }

        int by = top + IN_B - 20;
        boolean mine = Minecraft.getInstance().player != null
                && d.seller().equalsIgnoreCase(Minecraft.getInstance().player.getScoreboardName());
        if ("ON_SALE".equals(d.status())) {
            if (mine) {
                drawButton(g, left + 50, by, 70, 16, "撤　单", mouseX, mouseY);
            } else {
                drawButton(g, left + 50, by, 70, 16, "购　买", mouseX, mouseY);
            }
        }
        drawButton(g, left + IN_R - 86, by, 70, 16, "关　闭", mouseX, mouseY);
    }

    /**
     * 唐兔国民对这张票据的购买意愿
     *
     * 国民只在「挂牌价 ≤ 货物估值 × 85%」时买，
     * 所以这里用折价比例分档：
     *   ≤50% 高 / ≤70% 中 / 其它 低
     */
    private void drawWillingness(GuiGraphics g, MarketCache.TicketDetail d, int x, int y) {
        g.drawString(this.font, "唐兔国民购买意愿", x, y, INK_DIM, false);

        long worth = 0L;
        try {
            if ("BILL".equals(d.kind())) {
                worth = estimateWorth(d.itemId(), Math.max(1, d.count()));
            } else {
                for (String part : d.items().split(";")) {
                    int eq = part.lastIndexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String id = part.substring(0, eq);
                    int cnt;
                    try {
                        cnt = Integer.parseInt(part.substring(eq + 1).trim());
                    } catch (Throwable tt) {
                        continue;
                    }
                    worth += estimateWorth(id, cnt);
                }
            }
        } catch (Throwable ignored) {
            worth = 0L;
        }

        String txt;
        int color;
        if (worth <= 0L) {
            txt = "—";
            color = INK_DIM;
        } else {
            double ratio = (double) d.price() / (double) worth;
            if (ratio <= 0.50) {
                txt = "高";
                color = 0xFFC05000;
            } else if (ratio <= 0.70) {
                txt = "中";
                color = 0xFF1F6FB0;
            } else if (ratio <= 0.85) {
                txt = "低";
                color = 0xFF8A8A8A;
            } else {
                txt = "低（不会买）";
                color = 0xFF8A8A8A;
            }
        }
        g.drawString(this.font, txt, x + 110, y, color, false);
    }

    /**
     * 一批物品的估值
     *   有市场价/基准价 → 单价 × 数量
     *   都没有         → 按最低价 64 件 = 1 唐兔币
     */
    private static long estimateWorth(String itemId, int count) {
        if (itemId == null || count <= 0) {
            return 0L;
        }
        long unit = unitPriceOf(itemId);
        if (unit > 0L) {
            return unit * count;
        }
        return Math.max(1L, count / 64L);
    }

    /** 物品单价：市场价 → 基准价 → 0（表示没有价） */
    private static long unitPriceOf(String itemId) {
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

    private int row(GuiGraphics g, String k, String v, int x, int y, int color) {
        g.drawString(this.font, k, x, y, INK_DIM, false);
        g.drawString(this.font, v, x + 62, y, color, false);
        return y + 13;
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label,
                            int mouseX, int mouseY) {
        WoodRender.button(g, this.font, x, y, w, h, label, mouseX, mouseY, WoodRender.TEXT);
    }

    private static ItemStack iconOf(String id) {
        try {
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
            return it == null ? ItemStack.EMPTY : new ItemStack(it);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static String niceName(String id) {
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
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, H, k);
        }
        if (button == 0) {
            var d = MarketCache.ticketDetail();
            int by = top + IN_B - 20;
            if (mx >= left + IN_R - 86 && mx < left + IN_R - 16 && my >= by && my < by + 16) {
                this.onClose();
                return true;
            }
            if (d != null && "ON_SALE".equals(d.status())
                    && mx >= left + 50 && mx < left + 120 && my >= by && my < by + 16) {
                boolean mine = Minecraft.getInstance().player != null
                        && d.seller().equalsIgnoreCase(
                                Minecraft.getInstance().player.getScoreboardName());
                if (mine) {
                    com.tangtu.kjbb.net.MarketNetwork.ticketCancel(d.id());
                } else {
                    com.tangtu.kjbb.net.MarketNetwork.ticketBuy(d.id());
                }
                this.onClose();
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
