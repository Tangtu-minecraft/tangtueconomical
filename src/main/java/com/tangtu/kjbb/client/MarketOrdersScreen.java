package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 市场活跃订单（独立页面）
 *
 * 列布局（羊皮纸内区 left+36 ~ left+284）：
 *   [图标] 买 #220   铜瓦            ×14 @ 16
 */
public class MarketOrdersScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;

    /** 内区 */
    private static final int IN_L = 43, IN_R = 283, IN_T = 45, IN_B = 246;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int BUY_COLOR = 0xFF2E7D32;
    private static final int SELL_COLOR = 0xFFB03030;
    /** 自己的单 / 别的真人玩家的单 */
    private static final int OWN_COLOR = 0xFFC98A00;
    private static final int PLAYER_COLOR = 0xFF1E6FD9;

    private static final int ROWS = 8;
    private static final int ROW_H = 18;

    private int left;
    private int top;
    private int page = 0;
    private String hint = "";

    public MarketOrdersScreen() {
        super(Component.literal("\u5e02\u573a\u6d3b\u8dc3\u8ba2\u5355"));
    }

    @Override
    protected void init() {
        super.init();
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tangtu.kjbb.net.MenuNetwork.ReqBank());
        } catch (Throwable ignored) {
            // ignore
        }
        this.left = this.width / 2 - W / 2;
        this.top = this.height / 2 - H / 2;
        com.tangtu.kjbb.net.MarketNetwork.requestOpenOrders();
        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));
    }

    /** 列表起始 Y */
    private int listTop() {
        return top + IN_T + 12;
    }

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        String title = "\u5e02 \u573a \u6d3b \u8dc3 \u8ba2 \u5355";
        // 右上角：唐兔币余额
        try {
            int bal = com.tangtu.kjbb.client.MarketCache.total();
            String balTxt = bal < 0 ? "\u4f59\u989d\u672a\u77e5"
                    : (bal >= 10000
                    ? String.format(java.util.Locale.ROOT, "%.1fK", bal / 1000.0)
                    : String.valueOf(bal));
            String bt = balTxt + " \u5510\u5154\u5e01";
            g.drawString(this.font, bt, left + IN_R - 6 - this.font.width(bt),
                    top + IN_T + 2, INK, false);
        } catch (Throwable ignored) {
            // ignore
        }
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + IN_T + 2,
                INK, false);

        var list = MarketCache.openOrders();
        int pages = Math.max(1, (list.size() + ROWS - 1) / ROWS);
        if (page >= pages) {
            page = 0;
        }
        int from = page * ROWS;
        int y0 = listTop();

        if (list.isEmpty()) {
            g.drawString(this.font, "\uff08\u5f53\u524d\u6ca1\u6709\u6d3b\u8dc3\u8ba2\u5355\uff09",
                    left + IN_L, y0 + 6, INK_DIM, false);
        } else {
            for (int i = 0; i < ROWS && from + i < list.size(); i++) {
                var o = list.get(from + i);
                int cy = y0 + i * ROW_H;
                boolean hover = mouseX >= left + IN_L - 4 && mouseX < left + IN_R
                        && mouseY >= cy - 2 && mouseY < cy + ROW_H - 4;
                if (hover) {
                    g.fill(left + IN_L - 4, cy - 2, left + IN_R, cy + ROW_H - 4, 0x30B08A50);
                }
                ItemStack st = iconOf(o.itemId());
                if (!st.isEmpty()) {
                    g.renderItem(st, left + IN_L, cy - 2);
                }
                String kind = o.buy() ? "\u4e70" : "\u5356";
                g.drawString(this.font, kind, left + IN_L + 20, cy, o.buy() ? BUY_COLOR : SELL_COLOR,
                        false);
                // 真人玩家的单号/物品名用醒目色：自己的金色，别人的蓝色，机器人的保持灰
                int oc = ownerColor(o);
                g.drawString(this.font, "#" + o.id(), left + IN_L + 32, cy, oc, false);
                g.drawString(this.font, shortName(o.itemId(), 88), left + IN_L + 68, cy, oc,
                        false);
                String detail = "\u00d7" + o.count() + " @ " + o.unit();
                g.drawString(this.font, detail, left + IN_R - this.font.width(detail), cy, INK,
                        false);
            }
        }

        // 提示
        String tip = hint.isEmpty()
                ? "\u70b9\u4e00\u884c\u770b\u63d0\u793a\uff08\u4e70\u5355\u53ef\u4f9b\u8d27 / \u5356\u5355\u53ef\u4e70\u8d70\uff09"
                : hint;
        g.drawString(this.font, trim(tip, IN_R - IN_L - 10), left + IN_L, top + IN_B - 52,
                INK_DIM, false);

        // 页码
        String pageTxt = (page + 1) + "/" + pages + "\uff08\u5171 " + list.size() + " \u5355\uff09";
        g.drawString(this.font, pageTxt, left + IN_L, top + IN_B - 34, INK_DIM, false);
        // 颜色说明（灰字=机器人，不另标）
        int lx = left + IN_L + this.font.width(pageTxt) + 12;
        g.drawString(this.font, "\u91d1=\u6211", lx, top + IN_B - 34, OWN_COLOR, false);
        lx += this.font.width("\u91d1=\u6211") + 8;
        g.drawString(this.font, "\u84dd=\u73a9\u5bb6", lx, top + IN_B - 34, PLAYER_COLOR, false);

        // 翻页
        drawNav(g, left + IN_R - 74, top + IN_B - 36, 34, "<", mouseX, mouseY);
        drawNav(g, left + IN_R - 34, top + IN_B - 36, 34, ">", mouseX, mouseY);

        // 返回
        WoodRender.button(g, this.font, left + IN_L, top + IN_B - 20, 82, 16,
                "\u2190 \u8fd4\u56de", mouseX, mouseY, WoodRender.TEXT);
    }

    private void drawNav(GuiGraphics g, int x, int y, int w, String label, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? 0x805A3F1E : 0x40B08A50);
        int tw = this.font.width(label);
        g.drawString(this.font, label, x + (w - tw) / 2, y + 3, INK, false);
    }

    /** 订单颜色：自己的金色 / 别的玩家蓝色 / 机器人灰 */
    private static int ownerColor(MarketCache.OpenOrder o) {
        String me = "";
        try {
            var p = net.minecraft.client.Minecraft.getInstance().player;
            me = p == null ? "" : p.getScoreboardName();
        } catch (Throwable ignored) {
            me = "";
        }
        if (!me.isEmpty() && me.equals(o.owner())) {
            return OWN_COLOR;
        }
        return o.bot() ? INK_DIM : PLAYER_COLOR;
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
        if (button == 0) {
            var list = MarketCache.openOrders();
            int from = page * ROWS;
            int y0 = listTop();
            for (int i = 0; i < ROWS && from + i < list.size(); i++) {
                int cy = y0 + i * ROW_H;
                if (mx >= left + IN_L - 4 && mx < left + IN_R
                        && my >= cy - 2 && my < cy + ROW_H - 4) {
                    var o = list.get(from + i);
                    if (o.buy()) {
                        // 买单：走现有「订单详情」页
                        com.tangtu.kjbb.client.MarketCache.setOrderDetailFromOrders(true);
                        com.tangtu.kjbb.net.MarketNetwork.requestOrderDetail(o.id());
                    } else {
                        Minecraft.getInstance().setScreen(new MarketOfferScreen(o));
                    }
                    return true;
                }
            }
            // 翻页
            if (my >= top + IN_B - 36 && my < top + IN_B - 22) {
                if (mx >= left + IN_R - 74 && mx < left + IN_R - 40) {
                    page = Math.max(0, page - 1);
                    return true;
                }
                if (mx >= left + IN_R - 34 && mx < left + IN_R) {
                    int pages = Math.max(1, (list.size() + ROWS - 1) / ROWS);
                    page = Math.min(pages - 1, page + 1);
                    return true;
                }
            }
            // 返回
            if (mx >= left + IN_L && mx < left + IN_L + 82
                    && my >= top + IN_B - 20 && my < top + IN_B - 4) {
                Minecraft.getInstance().setScreen(new MarketScreen());
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
