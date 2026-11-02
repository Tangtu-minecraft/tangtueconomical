package com.tangtu.kjbb.client;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
/**
 * 股票详情界面（右键股票打开）
 *
 * 显示：航次、份数、发行价、当前份值、到港日、载货进度
 * 按钮：折现 / 提货 / 上架 / 返回
 */
public class StockScreen extends Screen {
    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_L = 43, IN_R = 283, IN_T = 45, IN_B = 246;
    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int GOOD = 0xFF2E7D32;
    private int left;
    private int top;
    private final long billId;
    private long sold;
    private int total;
    private long price;
    private long shareValue;
    private long dueDay;
    private String status = "";
    private String outcome = "";
    private String shipName = "";
    private int mine;
    private boolean loaded = false;
    public StockScreen(long billId) {
        super(Component.literal("股票"));
        this.billId = billId;
    }
    @Override
    protected void init() {
        super.init();
        this.left = this.width / 2 - W / 2;
        this.top = this.height / 2 - H / 2;
        this.addRenderableOnly((g, mx, my, pt) -> this.draw(g, mx, my));
        VoyageScreen.requestData();
    }
    private void parse() {
        String data = MarketCache.voyage();
        if (data == null || data.isEmpty()) {
            return;
        }
        for (String line : data.split("\n")) {
            String[] f = line.split("\\|", -1);
            if (!"B".equals(f[0]) || f.length < 16) {
                continue;
            }
            try {
                if (Long.parseLong(f[1]) != billId) {
                    continue;
                }
                shipName = f[2];
                status = f[3];
                sold = Long.parseLong(f[4]);
                total = Integer.parseInt(f[5]);
                price = Long.parseLong(f[6]);
                shareValue = Long.parseLong(f[7]);
                dueDay = Long.parseLong(f[11]);
                outcome = f[12];
                mine = Integer.parseInt(f[13]);
                loaded = true;
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
    private static String statusText(String s) {
        return switch (s) {
            case "OPEN" -> "募资中";
            case "VOYAGING" -> "航行中";
            case "DUE" -> "已到港";
            case "CLOSED" -> "已结束";
            default -> s;
        };
    }
    private void draw(GuiGraphics g, int mouseX, int mouseY) {
        parse();
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);
        String title = "股 票";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + 46, INK, false);
        int x = left + IN_L + 12;
        int y = top + 66;
        if (!loaded) {
            g.drawString(this.font, "（正在读取票面…）", x, y, INK_DIM, false);
        } else {
            g.drawString(this.font, shipName + "  #" + billId, x, y, INK, false);
            y += 14;
            g.drawString(this.font, "状态 " + statusText(status)
                    + (outcome.isEmpty() ? "" : (" · " + outcome)), x, y, INK_DIM, false);
            y += 14;
            g.drawString(this.font, "船份数 " + sold + "/" + total, x, y, INK_DIM, false);
            y += 14;
            g.drawString(this.font, "发行价 " + price + "　当前份值 " + shareValue, x, y,
                    shareValue >= price ? GOOD : INK_DIM, false);
            y += 14;
            if (dueDay > 0) {
                g.drawString(this.font, "到港日 "
                        + com.tangtu.kjbb.ServerCalendar.shortDate(dueDay), x, y, INK_DIM, false);
            } else {
                g.drawString(this.font, "到港日（未出海）", x, y, INK_DIM, false);
            }
            y += 16;
        }
        int by = top + 196;
        int bw = 44;
        int gap = 3;
        boolean due = "DUE".equals(status)
                || ("CLOSED".equals(status) && "取消".equals(outcome));
        // 上架：价格 / 份数（留空 = 当前份值 / 整张票）
        g.drawString(this.font, "上架价", x, top + 171, INK_DIM, false);
        g.drawString(this.font, "份数", x + 108, top + 171, INK_DIM, false);
        g.drawString(this.font, "\u7559\u7a7a = \u5f53\u524d\u4efd\u503c / \u6574\u5f20\u7968",
                x, top + 185, 0x9A8A70, false);
        // 折现 / 提货一直可点：没到港时服务端会回「这艘船还没到港」，比灰着更好懂
        button(g, "折现", x, by, bw, mouseX, mouseY, true);
        button(g, "提货", x + bw + gap, by, bw, mouseX, mouseY, true);
        button(g, "售出股票", x + (bw + gap) * 2, by, 60, mouseX, mouseY, !due);
        button(g, "返回", x + (bw + gap) * 2 + 63, by, bw, mouseX, mouseY, true);
    }
    /** 上架指令：价格留空 = auto（当前份值）；份数留空 = 整张票 */

    /** 手上这张票能卖几份（读不到就用「我持有」兜底） */
    private int heldShares() {
        try {
            var p = Minecraft.getInstance().player;
            if (p != null) {
                int sh = com.tangtu.kjbb.item.StockItem.sharesOf(p.getMainHandItem());
                if (sh > 0) {
                    return sh;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return Math.max(1, mine);
    }

    private void button(GuiGraphics g, String label, int x, int y, int w,
                        int mouseX, int mouseY, boolean enabled) {
        boolean hover = enabled && mouseX >= x && mouseX < x + w
                && mouseY >= y && mouseY < y + 14;
        if (enabled) {
            WoodRender.plate(g, x, y, w, 14, hover);
        } else {
            g.fill(x, y, x + w, y + 14, 0x60806040);
        }
        g.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + 3,
                enabled ? WoodRender.TEXT : 0xFF9A8A70, false);
    }

    private static void cmd(String c) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tangtu.kjbb.net.MenuNetwork.Action(c));
        } catch (Throwable ignored) {
            // ignore
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
        if (button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        int x = left + IN_L + 12;
        int by = top + 196;
        int bw = 44;
        int gap = 3;
        boolean due = "DUE".equals(status)
                || ("CLOSED".equals(status) && "取消".equals(outcome));

        if (hit(mx, my, x, by, bw)) {
            cmd("/billvoyage redeem cash");
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        if (hit(mx, my, x + bw + gap, by, bw)) {
            cmd("/billvoyage redeem coin");
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        if (hit(mx, my, x + (bw + gap) * 2, by, 60)) {
            // 售出股票：单独一页调份数与单价
            Minecraft.getInstance().setScreen(
                    new StockListScreen(billId, shipName, heldShares(), shareValue));
            return true;
        }
        if (hit(mx, my, x + (bw + gap) * 2 + 63, by, bw)) {
            Minecraft.getInstance().setScreen(new FormCenterScreen());
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private static boolean hit(double mx, double my, int x, int y, int w) {
        return mx >= x && mx < x + w && my >= y && my < y + 14;
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
