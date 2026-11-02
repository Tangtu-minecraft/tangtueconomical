package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
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
 * 仓单查看：只读的仓库记录（位置 / 开具人 / 物品清单）。
 */
public class ReceiptScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_L = 43, IN_T = 45, IN_R = 283, IN_B = 246;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int ROW_H = 18;
    private static final int PAGE_ROWS = 6;

    public record Line(String itemId, int count) {
    }

    private static List<Line> LINES = new ArrayList<>();
    private static String POS = "";
    private static long RID = 0L;
    private static String OWNER = "";
    private static boolean LOCKED = false;
    private static boolean IS_BEARER = false;
    private static final Map<String, String> NAME_CACHE = new HashMap<>();

    private int left;
    private int top;
    private int page = 0;

    public ReceiptScreen() {
        super(Component.literal("仓单"));
    }

    public static void setLocked(boolean locked) {
        LOCKED = locked;
    }

    public static void setData(String pos, String owner, String body, long rid) {
        String raw = pos == null ? "" : pos;
        IS_BEARER = raw.endsWith("#B");
        POS = raw.replace("#B", "").replace("#R", "");
        OWNER = owner == null ? "" : owner;
        List<Line> out = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            for (String line : body.split("\n")) {
                String[] f = line.split("\\|", -1);
                if (f.length < 2) {
                    continue;
                }
                try {
                    out.add(new Line(f[0].trim(), Integer.parseInt(f[1].trim())));
                } catch (Throwable ignored) {
                    // 跳过
                }
            }
        }
        LINES = out;
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

        String title = "仓　单";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + IN_T + 2, INK, false);
        String sub = (POS.isEmpty() ? "" : "容器 " + POS + "　") + (OWNER.isEmpty() ? "" : "开具人 " + OWNER)
                + (IS_BEARER ? "　不记名" : "　记名");
        g.drawString(this.font, sub, left + (W - this.font.width(sub)) / 2, top + IN_T + 14, INK_DIM, false);

        int listY = IN_T + 34;
        int total = 0;
        for (Line l : LINES) {
            total += l.count();
        }
        g.drawString(this.font, "共 " + LINES.size() + " 种 / " + total + " 件",
                left + IN_L + 8, top + listY, INK_DIM, false);

        int from = page * PAGE_ROWS;
        for (int i = 0; i < PAGE_ROWS && from + i < LINES.size(); i++) {
            Line l = LINES.get(from + i);
            int y = top + listY + 16 + i * ROW_H;
            ItemStack icon = iconOf(l.itemId());
            if (!icon.isEmpty()) {
                g.renderItem(icon, left + IN_L + 8, y);
            }
            g.drawString(this.font, nameOf(l.itemId()), left + IN_L + 28, y + 4, INK, false);
            String right = "×" + l.count();
            g.drawString(this.font, right, left + IN_R - 14 - this.font.width(right), y + 4, INK, false);
        }

        // 分页
        int pages = Math.max(1, (LINES.size() + PAGE_ROWS - 1) / PAGE_ROWS);
        int px = left + IN_L + 8;
        int py = top + IN_B - 34;
        boolean lh = mouseX >= px && mouseX < px + 12 && mouseY >= py && mouseY < py + 10;
        boolean rh = mouseX >= px + 56 && mouseX < px + 68 && mouseY >= py && mouseY < py + 10;
        g.fill(px, py, px + 12, py + 10, lh ? 0xFF6B4E28 : 0x405A3F1E);
        g.drawString(this.font, "<", px + 4, py + 1, 0xFFF3E2C0, false);
        String mid = (page + 1) + "/" + pages;
        g.drawString(this.font, mid, px + 34 - this.font.width(mid) / 2, py + 1, INK_DIM, false);
        g.fill(px + 56, py, px + 68, py + 10, rh ? 0xFF6B4E28 : 0x405A3F1E);
        g.drawString(this.font, ">", px + 60, py + 1, 0xFFF3E2C0, false);

        // 锁定状态 + 上锁/解锁按钮
        String state = LOCKED
                ? "仓库隔离中：仅仓单持有人可开启，漏斗与物流无法存取"
                : "仓库未隔离：任何人、任何物流都能存取";
        g.drawString(this.font, state, left + IN_L + 8, top + IN_B - 13,
                LOCKED ? 0xFF2E5B2E : INK_DIM, false);
        drawButton(g, left + 86, top + IN_B - 15, 60, 15, "挂　卖", mouseX, mouseY);
        drawButton(g, left + 150, top + IN_B - 15, 60, 15, "兑　付", mouseX, mouseY);
        drawButton(g, left + 214, top + IN_B - 15, 60, 15,
                LOCKED ? "解　锁" : "上　锁", mouseX, mouseY);
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
                    return k;
                }
                String n = new ItemStack(it).getHoverName().getString();
                return n == null || n.isEmpty() ? k : n;
            } catch (Throwable t) {
                return k;
            }
        });
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label,
                            int mouseX, int mouseY) {
        WoodRender.button(g, this.font, x, y, w, h, label, mouseX, mouseY, WoodRender.TEXT);
    }

    /** 解析 "x,y,z" */
    private static net.minecraft.core.BlockPos parsePos() {
        try {
            String[] f = POS.split(",");
            return new net.minecraft.core.BlockPos(Integer.parseInt(f[0].trim()),
                    Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()));
        } catch (Throwable t) {
            return null;
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
            // 挂卖：打开挂卖小窗
            if (mx >= left + 86 && mx < left + 146 && my >= top + IN_B - 15
                    && my < top + IN_B) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player != null) {
                    var inv = mc.player.getInventory();
                    int found = -1;
                    ItemStack held = mc.player.getMainHandItem();
                    if (com.tangtu.kjbb.TicketMarket.kindOf(held) != null) {
                        found = inv.selected;
                    } else {
                        for (int i = 0; i < inv.getContainerSize(); i++) {
                            if (com.tangtu.kjbb.TicketMarket.kindOf(inv.getItem(i)) != null) {
                                found = i;
                                break;
                            }
                        }
                    }
                    if (found < 0) {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "\u00a7c\u80cc\u5305\u91cc\u6ca1\u627e\u5230\u7968\u636e"), false);
                    } else {
                        mc.setScreen(new TicketSellScreen(inv.getItem(found), found));
                    }
                }
                return true;
            }
            // 兑付：凭这张仓单把货取出来
            if (mx >= left + 150 && mx < left + 210 && my >= top + IN_B - 15
                    && my < top + IN_B) {
                var pos1 = parsePos();
                if (pos1 != null) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.tangtu.kjbb.net.StoreFormNetwork.RedeemReceipt(
                                    pos1.getX(), pos1.getY(), pos1.getZ(), RID));
                }
                return true;
            }
            // 上锁 / 解锁
            if (mx >= left + 214 && mx < left + 274 && my >= top + IN_B - 15
                    && my < top + IN_B) {
                var pos = parsePos();
                if (pos != null) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.tangtu.kjbb.net.StoreFormNetwork.ToggleLock(
                                    pos.getX(), pos.getY(), pos.getZ(), !LOCKED));
                }
                return true;
            }
            int px = left + IN_L + 8;
            int py = top + IN_B - 34;
            if (my >= py && my < py + 10) {
                if (mx >= px && mx < px + 12) {
                    if (page > 0) {
                        page--;
                    }
                    return true;
                }
                if (mx >= px + 56 && mx < px + 68) {
                    int pages = Math.max(1, (LINES.size() + PAGE_ROWS - 1) / PAGE_ROWS);
                    if (page < pages - 1) {
                        page++;
                    }
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
}
