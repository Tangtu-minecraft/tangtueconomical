package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 唐兔国国民统计报表界面
 *
 * 遵守 §29 GUI 铁律：
 *   ① 所有绘制放在 addRenderableOnly
 *   ② render() 只画半透明底 + super.render()
 *   ③ renderBackground 不调用
 */
public class CitizenReportScreen extends Screen {

    /** 与 BankScreen 同一张纸（命名空间 kjbb） */
    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");

    private static final int W = 340;
    private static final int H = 300;
    /** 纸面内侧留白（参考 BankScreen：左右 52，顶部 38） */
    private static final int IN_X = 52;
    private static final int IN_Y = 40;
    private static final int LINE = 12;

    private static List<String> data = new ArrayList<>();
    private static String iconData = "";

    private int left;
    private int top;

    public CitizenReportScreen() {
        super(Component.literal("唐兔国国民 统计报表"));
    }

    /** 由网络包设置数据后打开界面 */
    public static void setData(String text, String items) {
        List<String> lines = new ArrayList<>();
        if (text != null) {
            for (String s : text.split("\\n")) {
                if (!s.isEmpty()) {
                    lines.add(s);
                }
            }
        }
        data = lines;
        iconData = items == null ? "" : items;
        Minecraft.getInstance().setScreen(new CitizenReportScreen());
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;

        int by = this.top + H + 6;
        if (by + 20 > this.height) {
            by = this.top + H - 26;
        }
        this.addRenderableWidget(new WoodButton(this.left + W / 2 - 66, by, 128, 20,
                Component.literal("\u2190 \u8fd4\u56de"), b -> {
                    Minecraft.getInstance().setScreen(new FormCenterScreen());
                }));

        this.addRenderableOnly((g, mouseX, mouseY, partialTick) -> {
            Font f = Minecraft.getInstance().font;

            // 背景（先注册，压在最底层）
            g.blit(TEX, this.left, this.top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

            // 标题
            g.drawString(f, "唐兔国国民",
                    this.left + IN_X, this.top + 22, 0x5A3F1E, false);

            int maxW = W - IN_X * 2 + 24;
            int y = this.top + IN_Y + 18;
            for (String raw : data) {
                if (y > this.top + H - 34) {
                    g.drawString(f, "…", this.left + IN_X, y, 0x777777, false);
                    break;
                }
                String line = raw.replace('=', '-');
                int color = 0x2F2F2F;
                if (line.startsWith("唐兔国国民") || line.startsWith("--")) {
                    color = 0x7A4A12;
                } else if (line.contains("+")) {
                    color = 0x2F6B2F;
                } else if (line.contains("支")) {
                    color = 0xB0202D;
                }
                g.drawString(f, trim(f, line, maxW), this.left + IN_X, y, color, false);
                y += LINE;
            }

            // 物品图标：画在文字下方，单独一行
            if (!iconData.isEmpty()) {
                int iconTop = this.top + H - 62;
                String[] parts = iconData.split(",");
                int ix = this.left + IN_X;
                int shown = 0;
                for (String part : parts) {
                    if (shown >= 8) {
                        break;
                    }
                    String[] kv = part.split("\\|");
                    if (kv.length < 2) {
                        continue;
                    }
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .get(net.minecraft.resources.ResourceLocation.tryParse(kv[0]));
                    if (item == null) {
                        continue;
                    }
                    var stack = new net.minecraft.world.item.ItemStack(item);
                    g.renderItem(stack, ix, iconTop);
                    g.renderItemDecorations(f, stack, ix, iconTop);
                    ix += 19;
                    shown++;
                }
            }
        });
    }

    /** 按像素宽度截断，末尾加省略号 */
    private static String trim(Font f, String s, int maxWidth) {
        if (f.width(s) <= maxWidth) {
            return s;
        }
        String ell = "…";
        int ellW = f.width(ell);
        StringBuilder sb = new StringBuilder();
        int acc = 0;
        for (int i = 0; i < s.length(); i++) {
            String c = String.valueOf(s.charAt(i));
            int w = f.width(c);
            if (acc + w + ellW > maxWidth) {
                break;
            }
            sb.append(c);
            acc += w;
        }
        return sb + ell;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 遮罩必须画在压入缩放之前，否则会跟着面板一起缩小
        g.fill(0, 0, this.width, this.height, 0x70000000);
        // 逻辑分辨率装不下面板时整体等比缩小；鼠标坐标同步换算，绘制与点击永远一致
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k >= 1.0f) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
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
        // 空实现：遮罩已在 render() 里按屏幕坐标画好，否则会被缩放一起缩掉
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系，否则点不中
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, H, k);
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
