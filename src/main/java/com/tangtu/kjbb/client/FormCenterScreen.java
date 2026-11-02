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
 * 服务中心（原「票据中心」）
 *
 * 第 1 页：现成功能（市场 / 我的订单 / 国民状况 / 绑定提货 / 管理员报表）
 * 第 2 页：预留位（股票、期货、期权、银行、拍卖行…）
 */
public class FormCenterScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_L = 43, IN_T = 45, IN_R = 283, IN_B = 246;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int LOCK = 0xFF9A8A70;
    private static final int TAG = 0xFFB06040;

    /** 每页最多 5 行 */
    private static final int ROW_H = 30;
    private static final int ROW_GAP = 32;
    private static final int ROW_TOP = 26;

    /** 名称、说明、图标、动作（"wait" = 预留） */
    private static final String[][] PAGE1 = {
            {"市　场", "看行情、挂单、下单、我的订单", "minecraft:emerald", "market"},
            {"我的订单", "我的挂单 · 托管订单 · 投递记录", "minecraft:paper", "mine"},
            {"国民状况", "人口、城镇规模、饱食情况", "minecraft:gold_ingot", "status"},
            {"票据市场", "买卖提单 / 仓单", "kjbankbridge:bill_of_lading", "tickets"},
            {"绑定提货", "把附近的邮箱设为我的提货点", "kjbankbridge:address_tag", "bind"},
            {"国民经济（管理）", "资金池、库存、收支、单价报表", "minecraft:written_book", "report"},
    };

    private static final String[][] PAGE2 = {
            {"航运中心", "航次 · 份额 · 目的地贸易 · 包船", "minecraft:oak_boat", "voyage"},
            {"交易中心", "我的挂单 · 托管订单 · 投递记录", "minecraft:chest", "tx"},
            {"期　货", "约定到期交割的合同", "minecraft:clock", "wait"},
            {"期　权", "付权利金买/卖的权利", "minecraft:gold_ingot", "wait"},
            {"拍卖行", "竞价成交、限时拍卖", "minecraft:gavel", "wait"},
    };

    /** 一页最多显示 5 行；第 1 页按权限过滤 */
    private java.util.List<String[]> rowsOf(int page) {
        var out = new java.util.ArrayList<String[]>();
        boolean admin = false;
        try {
            var p = Minecraft.getInstance().player;
            admin = p != null && p.hasPermissions(2);
        } catch (Throwable ignored) {
            // ignore
        }
        if (page == 0) {
            for (String[] it : PAGE1) {
                if ("report".equals(it[3]) && !admin) {
                    continue;
                }
                out.add(it);
            }
        } else {
            for (String[] it : PAGE2) {
                out.add(it);
            }
        }
        return out;
    }

    private int page = 0;
    private int left;
    private int top;

    public FormCenterScreen() {
        this(0);
    }

    /** 直接打开第 page 页（0 起）；主要给预览工具用，游戏内仍用无参构造 */
    public FormCenterScreen(int page) {
        super(Component.literal("服务中心"));
        this.page = Math.max(0, Math.min(1, page));
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
        // 打开界面时向服务端要一次余额
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tangtu.kjbb.net.MenuNetwork.ReqBank());
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        String title = "服 务 中 心";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2,
                top + IN_T + 2, INK, false);
        String sub = page == 0 ? "挑一个要用的功能" : "后续功能（预留）";
        g.drawString(this.font, sub, left + IN_L + 6, top + IN_T + 8, INK_DIM, false);

        // 右上角：唐兔币余额
        try {
            int bal = com.tangtu.kjbb.client.MarketCache.total();
            String balance;
            if (bal < 0) {
                balance = "余额未知";
            } else if (bal >= 10000) {
                balance = String.format(java.util.Locale.ROOT, "%.1fK", bal / 1000.0);
            } else {
                balance = String.valueOf(bal);
            }
            String plain = balance + " 唐兔币";
            g.drawString(this.font, plain,
                    left + IN_R - 6 - this.font.width(plain), top + IN_T + 8, INK, false);
        } catch (Throwable ignored) {
            // ignore
        }

        var rows = rowsOf(page);
        int rowY = top + IN_T + ROW_TOP;
        for (int i = 0; i < rows.size() && i < 5; i++) {
            int y = rowY + i * ROW_GAP;
            drawRow(g, rows.get(i), left + IN_L + 6, y, IN_R - IN_L - 12, ROW_H - 2,
                    mouseX, mouseY);
        }

        // 页码 + 翻页按钮
        String pageTxt = (page + 1) + "/2";
        g.drawString(this.font, pageTxt, left + IN_L + 6, top + IN_B - 12, INK_DIM, false);
        drawNav(g, left + IN_R - 76, top + IN_B - 16, 32, "<", mouseX, mouseY);
        drawNav(g, left + IN_R - 40, top + IN_B - 16, 32, ">", mouseX, mouseY);

    }

    private void drawNav(GuiGraphics g, int x, int y, int w, String label, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? 0x805A3F1E : 0x40B08A50);
        int tw = this.font.width(label);
        g.drawString(this.font, label, x + (w - tw) / 2, y + 3, INK, false);
    }

    private void drawRow(GuiGraphics g, String[] it, int x, int y, int w, int h,
                         int mouseX, int mouseY) {
        boolean open = !"wait".equals(it[3]);
        boolean hover = open && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0x50B08A50 : 0x28FFFFFF);
        g.fill(x, y + h, x + w, y + h + 1, 0xFFB49A6A);

        ItemStack icon = iconOf(it[2]);
        if (!icon.isEmpty()) {
            g.renderItem(icon, x + 6, y + 6);
        }
        g.drawString(this.font, it[0], x + 30, y + 5, open ? INK : LOCK, false);
        g.drawString(this.font, it[1], x + 30, y + 16, open ? INK_DIM : LOCK, false);
        if (!open) {
            String tag = "预留";
            g.drawString(this.font, tag, x + w - this.font.width(tag) - 6, y + 9, TAG, false);
        }
    }

    private static ItemStack iconOf(String id) {
        try {
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
            return it == null ? ItemStack.EMPTY : new ItemStack(it);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
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
        // 翻页
        if (my >= top + IN_B - 16 && my < top + IN_B - 2) {
            if (mx >= left + IN_R - 76 && mx < left + IN_R - 44) {
                page = Math.max(0, page - 1);
                return true;
            }
            if (mx >= left + IN_R - 40 && mx < left + IN_R - 8) {
                page = Math.min(1, page + 1);
                return true;
            }
        }
        var rows = rowsOf(page);
        int rowY = top + IN_T + ROW_TOP;
        for (int i = 0; i < rows.size() && i < 5; i++) {
            int y = rowY + i * ROW_GAP;
            if (mx >= left + IN_L + 6 && mx < left + IN_R - 6
                    && my >= y && my < y + ROW_H - 2) {
                choose(rows.get(i));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void choose(String[] it) {
        var mc = Minecraft.getInstance();
        String action = it[3];
        if ("wait".equals(action)) {
            hint("§e" + it[0].replace("　", "") + " 还没做，先留个位置");
            return;
        }
        switch (action) {
            case "market" -> mc.setScreen(new MarketScreen(0));
            case "mine" -> mc.setScreen(new TxCenterScreen(1));
            case "tickets" -> mc.setScreen(new TicketMarketScreen());
            case "voyage" -> mc.setScreen(new ShippingScreen());
            case "stats" -> mc.setScreen(new StatsScreen());
            case "tx" -> mc.setScreen(new TxCenterScreen());
            case "status" -> {
                send("/billcitizen public");
                this.onClose();
            }
            case "report" -> {
                send("/billcitizen gui");
                this.onClose();
            }
            case "bind" -> {
                send("/billkey bindhere");
                hint("§7正在绑定附近的邮箱…");
                this.onClose();
            }
            default -> {
                // 无动作
            }
        }
    }

    private static void send(String cmd) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tangtu.kjbb.net.MenuNetwork.Action(cmd));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void hint(String msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) {
            p.displayClientMessage(Component.literal(msg), false);
        }
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
