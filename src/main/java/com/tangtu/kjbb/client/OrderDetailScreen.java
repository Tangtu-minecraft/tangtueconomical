package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 订单详情：右侧列表里点某一单，弹出这张纸看全部信息 + 接单/撤单。
 */
public class OrderDetailScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_L = 43, IN_T = 45, IN_R = 283, IN_B = 246;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;

    private int left;
    private int top;

    public OrderDetailScreen() {
        super(Component.literal("订单详情"));
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

        String title = "订 单 详 情";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + IN_T + 8,
                INK, false);

        var d = MarketCache.orderDetail();
        if (d == null) {
            g.drawString(this.font, "（订单已不存在）", left + IN_L + 10, top + IN_T + 30,
                    INK_DIM, false);
            drawButton(g, left + IN_R - 160, top + IN_B - 20, 66, 16, "返　回", mouseX, mouseY);
            drawButton(g, left + IN_R - 86, top + IN_B - 20, 70, 16, "关　闭", mouseX, mouseY);
            return;
        }

        int x = left + 50;
        int y = top + IN_T + 42;

        // 物品图标 + 名称
        ItemStack icon = iconOf(d.itemId());
        if (!icon.isEmpty()) {
            g.renderItem(icon, x, y - 2);
        }
        g.drawString(this.font, nameOf(d.itemId()), x + 20, y, INK, false);
        g.drawString(this.font, d.itemId(), x + 20, y + 11, 0xFF8A7250, false);
        y += 26;

        y = row(g, "订单编号", "#" + d.id(), x, y, INK);
        y = row(g, "数量", d.count() + " 个", x, y, INK);
        y = row(g, "单价", d.unit() + " 唐兔币/个", x, y, INK);
        y = row(g, "总价", d.total() + " 唐兔币", x, y, 0xFF2E5B2E);
        y = row(g, "状态", statusText(d.status()), x, y,
                "DONE".equals(d.status()) ? 0xFF2E7D32
                        : "PENDING".equals(d.status()) ? 0xFFB07000
                        : "SUPPLIED".equals(d.status()) ? 0xFF1F6FB0 : 0xFF8A8A8A);
        y = row(g, "买家", d.buyer(), x, y, INK);
        y = row(g, "卖家", d.seller() == null || d.seller().isEmpty() ? "（还没人接）" : d.seller(),
                x, y, INK);
        y = row(g, "提货地址", d.address() == null || d.address().isEmpty() ? "（未登记）" : d.address(),
                x, y, INK);
        String when;
        try {
            when = new SimpleDateFormat("MM-dd HH:mm").format(new Date(d.created()));
        } catch (Throwable t) {
            when = String.valueOf(d.created());
        }
        y = row(g, "下单时间", when, x, y, INK);

        // 按钮
        int by = top + IN_B - 20;
        boolean pending = "PENDING".equals(d.status());
        String me = Minecraft.getInstance().player == null ? ""
                : Minecraft.getInstance().player.getScoreboardName();
        boolean isBuyer = d.buyer() != null && d.buyer().equals(me);
        boolean mine = "ACCEPTED".equals(d.status()) && d.seller() != null
                && d.seller().equals(me);
        boolean buyerIsBot = d.buyer() != null && (d.buyer().startsWith("\u5546\u884c")
                || d.buyer().startsWith("\u5510\u5154\u56fd") || d.buyer().startsWith("\u7ecf\u6d4e\u90e8"));
        if (pending) {
            if (isBuyer) {
                drawButton(g, left + 50, by, 70, 16, "撤　单", mouseX, mouseY);
            } else if (buyerIsBot) {
                // 机器人买家：直接接单结算，不需要接受/发报机
                drawButton(g, left + 50, by, 70, 16, "接　单", mouseX, mouseY);
            } else {
                drawButton(g, left + 50, by, 70, 16, "接　受", mouseX, mouseY);
            }
        } else if (mine) {
            g.drawString(this.font, "去仓储发报机前发货（存入货物即自动寄出）",
                    left + 50, by + 4, INK_DIM, false);
        }
        drawButton(g, left + IN_R - 160, by, 66, 16, "返　回", mouseX, mouseY);
        drawButton(g, left + IN_R - 86, by, 70, 16, "关　闭", mouseX, mouseY);

        if (!pending) {
            String tip = "DONE".equals(d.status()) ? "订单已完成，款项已结清"
                    : "SUPPLIED".equals(d.status()) ? "已发货，等待包裹到达提货点"
                    : "订单已取消（超时或主动撤销）";
            g.drawString(this.font, tip, left + 50, by + 4, INK_DIM, false);
        }
    }

    private int row(GuiGraphics g, String k, String v, int x, int y, int color) {
        g.drawString(this.font, k, x, y, INK_DIM, false);
        g.drawString(this.font, v, x + 70, y, color, false);
        return y + 13;
    }

    private static String statusText(String s) {
        return switch (s) {
            case "PENDING" -> "待接受";
            case "ACCEPTED" -> "已被接受（待发货）";
            case "SUPPLIED" -> "已发货";
            case "DONE" -> "已完成";
            default -> "已取消";
        };
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label,
                            int mouseX, int mouseY) {
        WoodRender.button(g, this.font, x, y, w, h, label, mouseX, mouseY, WoodRender.TEXT);
    }

    private static void send(String cmd) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tangtu.kjbb.net.MenuNetwork.Action(cmd));
        } catch (Throwable ignored) {
            // ignore
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
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, H, k);
        }
        if (button == 0) {
            var d = MarketCache.orderDetail();
            int by = top + IN_B - 20;
            // 返回：从活跃订单进来的就回列表，否则回服务中心
            if (mx >= left + IN_R - 160 && mx < left + IN_R - 94
                    && my >= by && my < by + 16) {
                goBack();
                return true;
            }
            if (mx >= left + IN_R - 80 && mx < left + IN_R - 10
                    && my >= by && my < by + 16) {
                goBack();
                return true;
            }
            if (d != null && "PENDING".equals(d.status())) {
                if (mx >= left + 50 && mx < left + 120 && my >= by && my < by + 16) {
                    String me2 = Minecraft.getInstance().player == null ? ""
                            : Minecraft.getInstance().player.getScoreboardName();
                    if (d.buyer() != null && d.buyer().equals(me2)) {
                        // 自己的单 → 撤单
                        com.tangtu.kjbb.net.MarketNetwork.cancelOrder(d.id());
                    } else if (d.buyer() != null && (d.buyer().startsWith("\u5546\u884c")
                            || d.buyer().startsWith("\u5510\u5154\u56fd")
                            || d.buyer().startsWith("\u7ecf\u6d4e\u90e8")
                            || d.buyer().startsWith("\u5927A\u818f\u624b")
                            || d.buyer().startsWith("\u8c03\u8282\u8005"))) {
                        // 机器人的单 → 直接接单结算
                        com.tangtu.kjbb.net.MarketNetwork.takeOrder(d.id());
                    } else {
                        // 其他玩家的单 → 接受（锁单）
                        send("/billorder accept " + d.id());
                    }
                    goBack();
                    return true;
                }
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

    /** 返回上一级：从活跃订单进来 → 回列表；否则回服务中心 */
    private void goBack() {
        try {
            if (MarketCache.orderDetailFromOrders()) {
                MarketCache.setOrderDetailFromOrders(false);
                Minecraft.getInstance().setScreen(new MarketOrdersScreen());
                return;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        Minecraft.getInstance().setScreen(new FormCenterScreen());
    }

    @Override
    public void onClose() {
        goBack();
    }
}
