package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 纸质票据界面（提单签发）。
 *
 * ★ 渲染策略（关键）：
 *   直接用【原生 1290×1219】贴图，按 1 贴图像素 = 1 屏幕像素绘制。
 *   逻辑尺寸 = 1290 / GUI缩放，随缩放自适应 → 永远不插值、永远清晰。
 *
 *   布局以「基准尺寸 256×241」编写，运行时按 K = 逻辑宽/256 等比缩放。
 */
public class PaperScreen extends Screen {

    /** 像素画 1x（320×302） */
    private static final ResourceLocation PAPER_PIXEL =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/paper_pixel.png");
    /** 整数倍最近邻放大版：key = 放大倍数 */
    private static final java.util.Map<Integer, ResourceLocation> PAPER_SCALED = new java.util.HashMap<>();

    static {
        for (int k = 4; k <= 6; k++) {
            PAPER_SCALED.put(k, ResourceLocation.fromNamespaceAndPath("kjbb",
                    "textures/gui/paper_pixel" + k + "x.png"));
        }
    }

    private static final int TEX_W = 320, TEX_H = 302;

    // ---- 基准布局（256×241）----
    private static final int BASE_W = 320, BASE_H = 302;
    private static final int B_TOP_X = 45, B_TOP_Y = 37;
    private static final int B_BOT_X = 45, B_BOT_Y = 176, B_BOT_W = 243;
    private static final int B_FOOT_Y = 246, B_FOOT_H = 16;

    private static final int INK = 0x3A2A18;
    private static final int INK_LIGHT = 0x7A5A32;

    // ---- 运行期（按 K 缩放）----
    private int left;
    private int top;
    private int W, H;
    private float K;
    private int topX, topY;
    private int botX, botY, botW;
    private int footY, footH;
    private int gs = 4;
    private boolean nativeMode = true;

    private EditBox fieldItem;
    /** 下半区模式：true=物品选择，false=提单列表 */
    private boolean showItems = true;
    /** 仓库模式（右键发报机打开）：null = 普通模式 */
    private final net.minecraft.core.BlockPos tickerPos;
    /** 仓库模式下 true = 从仓库买，false = 存我的货 */
    private boolean storeBuyMode = true;
    /** 票面模式（右键提单打开） */
    private final boolean billMode;
    private final String billItemId;
    private final int billCount;
    private final String billIssuer;
    private final long billSerial;
    private final long billExpires;
    private final String billStatus;
    private final String billAddress;
    /** 选中的物品 id（可视化选择用） */
    private String selectedId = "";
    /** 网格参数 */
    private static final int GRID_COLS = 13;
    private static final int GRID_ROWS = 4;
    private static final int SLOT = 18;
    private EditBox fieldCount;

    public PaperScreen() {
        super(Component.literal("票据"));
        this.billMode = false;
        this.billItemId = "";
        this.billCount = 0;
        this.billIssuer = "";
        this.billSerial = 0L;
        this.billExpires = 0L;
        this.billStatus = "";
        this.billAddress = "";
        this.tickerPos = null;
    }

    public PaperScreen(boolean billMode, String itemId, int count, String issuer,
                       long serial, long expiresAt, String status) {
        this(billMode, itemId, count, issuer, serial, expiresAt, status, "");
    }

    public PaperScreen(boolean billMode, String itemId, int count, String issuer,
                       long serial, long expiresAt, String status, String address) {
        super(Component.literal("提单"));
        this.billMode = billMode;
        this.billItemId = itemId == null ? "" : itemId;
        this.billCount = count;
        this.billIssuer = issuer == null ? "" : issuer;
        this.billSerial = serial;
        this.billExpires = expiresAt;
        this.billStatus = status == null ? "" : status;
        this.billAddress = address == null ? "" : address;
        this.tickerPos = null;
    }

    /** 仓库模式：右键发报机打开 */
    public PaperScreen(net.minecraft.core.BlockPos tickerPos) {
        super(Component.literal("仓库票据"));
        this.billMode = false;
        this.billItemId = "";
        this.billCount = 0;
        this.billIssuer = "";
        this.billSerial = 0L;
        this.billExpires = 0L;
        this.billStatus = "";
        this.billAddress = "";
        this.tickerPos = tickerPos;
    }

    /** 最近邻 + 关闭 mipmap（GUI 像素风贴图必须这样，否则会糊） */
    private static void applyNativeFilter(ResourceLocation loc) {
        var tm = Minecraft.getInstance().getTextureManager();
        var tex = tm.getTexture(loc,
                new net.minecraft.client.renderer.texture.SimpleTexture(loc));
        if (tex != null) {
            tex.setFilter(false, false);
        }
    }

    private int s(int base) {
        return Math.round(base * K);
    }

    @Override
    protected void init() {
        super.init();

        try {
            gs = Math.max(1, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
        } catch (Throwable ignored) {
            gs = 4;
        }

        nativeMode = true;
        W = BASE_W;
        H = BASE_H;
        K = 1.0f;

        if (W > this.width - 20) {
            K = K * ((this.width - 20f) / W);
            W = this.width - 20;
            H = Math.round(BASE_H * K);
        }

        this.left = this.width / 2 - W / 2;
        this.top = this.height / 2 - H / 2;

        topX = left + s(B_TOP_X);
        topY = top + s(B_TOP_Y);
        botX = left + s(B_BOT_X);
        botY = top + s(B_BOT_Y);
        botW = s(B_BOT_W);
        footY = top + s(B_FOOT_Y);
        footH = Math.max(12, s(B_FOOT_H));

        fieldItem = new EditBox(this.font, topX + s(18) + 34, topY + s(27), s(140), 10,
                Component.literal("物品"));
        String handId = "";
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                var st = mc.player.getMainHandItem();
                if (st != null && !st.isEmpty()) {
                    handId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(st.getItem()).toString();
                }
            }
        } catch (Throwable ignored) {
            handId = "";
        }
        fieldItem.setValue(billMode ? billItemId : handId);
        fieldItem.setMaxLength(64);
        fieldItem.setBordered(false);
        fieldItem.setTextColor(INK);
        addRenderableWidget(fieldItem);

        fieldCount = new EditBox(this.font, topX + s(18) + 34, topY + s(45), s(60), 10,
                Component.literal("数量"));
        fieldCount.setValue(billMode ? String.valueOf(billCount) : "64");
        if (billMode) {
            fieldItem.setEditable(false);
            fieldCount.setEditable(false);
        }
        fieldCount.setMaxLength(9);
        fieldCount.setBordered(false);
        fieldCount.setTextColor(INK);
        addRenderableWidget(fieldCount);

        // ★ 关键：把贴图设为「最近邻 + 不生成 mipmap」，否则 GUI 默认的双线性+多级纹理
        //   会把 1290px 的图采样成 1/4 分辨率再用，看起来就是糊的
        try {
            applyNativeFilter(PAPER_PIXEL);
            for (ResourceLocation rl : PAPER_SCALED.values()) {
                applyNativeFilter(rl);
            }
        } catch (Throwable ex) {
            System.out.println("[TangtuEconomical] 设置贴图过滤失败: " + ex);
        }

        // 所有文字走 Renderable（与控件同一条渲染路径）
        this.addRenderableOnly((gg, mx, my, pt) -> this.drawAllText(gg, mx, my));

        if (!Logged.once) {
            Logged.once = true;
            System.out.println("[TangtuEconomical] 纸张界面 GUI缩放=" + gs
                    + " 模式=" + (nativeMode ? "原生1:1" : "小图")
                    + " 逻辑=" + W + "x" + H + " K=" + String.format("%.2f", K)
                    + " 贴图=" + (nativeMode ? TEX_W + "x" + TEX_H : BASE_W + "x" + BASE_H));
        }

        if (!billMode) {
            try {
                com.tangtu.kjbb.net.BillNetwork.sendMyBills();
                com.tangtu.kjbb.net.InvNetwork.request();
                if (tickerPos != null) {
                    com.tangtu.kjbb.net.TickerBillNetwork.requestStore(tickerPos);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 只压暗；贴图与所有文字都在 Renderable 里画（实测这条路径才清晰）
        g.fill(0, 0, this.width, this.height, 0x70000000);
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** 所有文字：作为 Renderable 在 super.render 阶段绘制（与控件同路径，才清晰） */
    private void drawAllText(GuiGraphics g, int mouseX, int mouseY) {
        drawPaper(g);
        drawButtonRects(g, mouseX, mouseY);
        // 票面
        String title = "提　单";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, topY + s(7), INK, false);

        g.drawString(this.font, "物品：", topX + s(18), topY + s(28), INK_LIGHT, false);
        g.drawString(this.font, "数量：", topX + s(18), topY + s(46), INK_LIGHT, false);
        g.fill(topX + s(18) + 34, topY + s(37), topX + s(18) + 34 + s(140), topY + s(37) + 1, 0xFF9A7B4C);
        g.fill(topX + s(18) + 34, topY + s(55), topX + s(18) + 34 + s(60), topY + s(55) + 1, 0xFF9A7B4C);

        String hint;
        if (billMode) {
            hint = "状态：" + cnStatus(billStatus);
        } else if (tickerPos != null) {
            var ships = StoreCache.shipments();
            if (!ships.isEmpty()) {
                hint = "\u5f85\u53d1\u8d27 " + ships.size() + " \u5f20\uff1a#" + ships.get(0).id()
                        + " x" + ships.get(0).count() + " \uff08\u70b9\u6b64\u53d1\u8d27\uff09";
                // 额外画一行，避免被网格挡住
                g.drawString(this.font, hint, topX + s(18), topY + s(74), 0xC05000, false);
            } else {
                hint = storeBuyMode ? "从仓库买下 → 生成提单（扣余额）" : "把背包货物存入仓库 → 生成提单";
            }
        } else {
            hint = K < 0.85f ? "有效期 7 游戏日" : "有效期 7 游戏日 · 货物由票据系统代管";
        }
        g.drawString(this.font, hint, topX + s(18), topY + s(64), 0x8A6F4A, false);

        // 下半：票面信息 / 物品选择 / 提单列表
        if (billMode) {
            drawBillInfo(g);
        } else if (tickerPos != null) {
            if (storeBuyMode) {
                drawStoreGrid(g);
            } else {
                drawItemGrid(g);
            }
        } else if (showItems) {
            drawItemGrid(g);
        } else {
            g.drawString(this.font, "我已签发的提单", botX + s(14), botY + s(8), INK_LIGHT, false);
            java.util.List<String> bills = BillListCache.lines();
            if (bills.isEmpty()) {
                g.drawString(this.font, "（还没有签发的提单）", botX + s(14), botY + s(22),
                        INK_LIGHT, false);
            } else {
                int drawn = 0;
                for (String line : bills) {
                    if (drawn >= 5) {
                        break;
                    }
                    String show = line;
                    int maxLen = Math.max(18, Math.round(botW / 6f));
                    if (show.length() > maxLen) {
                        show = show.substring(0, maxLen) + "…";
                    }
                    g.drawString(this.font, "· " + show, botX + s(14), botY + s(22) + drawn * 11,
                            INK_LIGHT, false);
                    drawn++;
                }
            }
        }

        // 按钮文字
        if (billMode) {
            // 提单只作证明，不再提供「领取」按钮
            drawButtonLabel(g, botX + botW - s(56), s(56), "关闭");
        } else if (tickerPos != null) {
            drawButtonLabel(g, botX, s(56), storeBuyMode ? "买下出票" : "存入出票");
            drawButtonLabel(g, botX + s(66), s(52), storeBuyMode ? "改存入" : "改购买");
            drawButtonLabel(g, botX + botW - s(52), s(52), "关闭");
        } else {
            drawButtonLabel(g, botX, s(46), "签发");
            drawButtonLabel(g, botX + s(54), s(46), showItems ? "看票据" : "选物品");
            drawButtonLabel(g, botX + s(114), s(46), "作废");
            drawButtonLabel(g, botX + botW - s(52), s(52), "关闭");
        }
    }

    /** 仓库模式：显示仓库里的货 + 单价 */
    private void drawStoreGrid(GuiGraphics g) {
        java.util.List<StoreCache.Entry> list = StoreCache.entries();
        int gx = botX + s(12);
        int gy = botY + s(8);
        if (list.isEmpty()) {
            g.drawString(this.font, "（仓库里没有可交易货物）", gx, gy + s(20), INK_LIGHT, false);
            return;
        }
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = row * GRID_COLS + col;
                int x = gx + col * SLOT;
                int y = gy + row * SLOT;
                boolean has = idx < list.size();
                boolean sel = has && list.get(idx).id().equals(selectedId);
                g.fill(x, y, x + SLOT - 1, y + SLOT - 1, sel ? 0xFFB08A50 : 0x33904E20);
                g.fill(x, y, x + SLOT - 1, y + 1, 0x66FFFFFF);
                if (!has) {
                    continue;
                }
                StoreCache.Entry e = list.get(idx);
                net.minecraft.world.item.ItemStack stack = InvCache.stackOf(e.id());
                if (!stack.isEmpty()) {
                    g.renderItem(stack, x + 1, y + 1);
                    g.renderItemDecorations(this.font, stack, x + 1, y + 1,
                            e.count() > 1 ? String.valueOf(Math.min(e.count(), 64)) : "");
                }
            }
        }
        if (!selectedId.isEmpty()) {
            long unit = 0L;
            int have = 0;
            for (StoreCache.Entry e : list) {
                if (e.id().equals(selectedId)) {
                    unit = e.unit();
                    have = e.count();
                    break;
                }
            }
            net.minecraft.world.item.ItemStack stack = InvCache.stackOf(selectedId);
            String name = stack.isEmpty() ? selectedId : stack.getHoverName().getString();
            g.drawString(this.font, "已选：" + name + "  单价 " + unit + " 唐兔币  库存 " + have,
                    gx, gy + GRID_ROWS * SLOT + s(4), INK, false);
        }
    }

    /** 票面模式：显示这张提单的完整信息 */
    private void drawBillInfo(GuiGraphics g) {
        int x = botX + s(14);
        int y = botY + s(8);
        g.drawString(this.font, "票面信息", x, y, INK, false);

        net.minecraft.world.item.ItemStack stack = InvCache.stackOf(billItemId);
        String itemName = stack.isEmpty() ? billItemId : stack.getHoverName().getString();
        if (!stack.isEmpty()) {
            g.renderItem(stack, x, y + s(14));
        }
        g.drawString(this.font, itemName + " ×" + billCount, x + s(20), y + s(18), INK_LIGHT, false);
        g.drawString(this.font, "编号：No." + billSerial, x, y + s(34), INK_LIGHT, false);
        g.drawString(this.font, "签发：" + billIssuer, x, y + s(46), INK_LIGHT, false);

        String st = cnStatus(billStatus);
        g.drawString(this.font, "状态：" + st, x, y + s(58), INK_LIGHT, false);
        if (billAddress != null && !billAddress.isEmpty()) {
            g.drawString(this.font, "发放地址：" + billAddress, x, y + s(70), 0x1A5C6B, false);
        }
    }

    private static String cnStatus(String s) {
        if ("REDEEMED".equals(s)) {
            return "已兑付";
        }
        if ("EXPIRED".equals(s)) {
            return "已作废";
        }
        return "待兑付";
    }

    /** 下半区：背包物品网格（点击选中） */
    private void drawItemGrid(GuiGraphics g) {
        java.util.List<InvCache.Entry> list = InvCache.entries();
        int gx = botX + s(12);
        int gy = botY + s(8);
        if (list.isEmpty()) {
            g.drawString(this.font, "（背包是空的）", gx, gy + s(20), INK_LIGHT, false);
            return;
        }
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = row * GRID_COLS + col;
                int x = gx + col * SLOT;
                int y = gy + row * SLOT;
                boolean has = idx < list.size();
                boolean sel = has && list.get(idx).id().equals(selectedId);
                g.fill(x, y, x + SLOT - 1, y + SLOT - 1, sel ? 0xFFB08A50 : 0x33904E20);
                g.fill(x, y, x + SLOT - 1, y + 1, 0x66FFFFFF);
                if (!has) {
                    continue;
                }
                InvCache.Entry e = list.get(idx);
                net.minecraft.world.item.ItemStack stack = InvCache.stackOf(e.id());
                if (!stack.isEmpty()) {
                    g.renderItem(stack, x + 1, y + 1);
                    g.renderItemDecorations(this.font, stack, x + 1, y + 1,
                            e.count() > 1 ? String.valueOf(Math.min(e.count(), 64)) : "");
                }
            }
        }
        if (!selectedId.isEmpty()) {
            net.minecraft.world.item.ItemStack stack = InvCache.stackOf(selectedId);
            String name = stack.isEmpty() ? selectedId : stack.getHoverName().getString();
            g.drawString(this.font, "已选：" + name, gx, gy + GRID_ROWS * SLOT + s(4),
                    INK, false);
        }
    }

    /**
     * 贴图绘制：优先用「与 GUI 缩放同倍数的最近邻放大版」，
     * 这样 1 贴图像素 = 1 屏幕像素，无论采样器用最近邻还是双线性都不会糊。
     */
    private void drawPaper(GuiGraphics g) {
        ResourceLocation tex = PAPER_SCALED.get(gs);
        if (tex != null) {
            int tw = TEX_W * gs;
            int th = TEX_H * gs;
            g.blit(tex, this.left, this.top, W, H, 0.0f, 0.0f, tw, th, tw, th);
        } else {
            // 其它缩放：用 1x 像素画，让 MC 自己放大
            g.blit(PAPER_PIXEL, this.left, this.top, W, H, 0.0f, 0.0f, TEX_W, TEX_H, TEX_W, TEX_H);
        }
    }

    private void drawButtonRects(GuiGraphics g, int mouseX, int mouseY) {
        if (billMode) {
            buttonRect(g, botX, s(56), mouseX, mouseY);
            buttonRect(g, botX + botW - s(56), s(56), mouseX, mouseY);
            return;
        }
        if (tickerPos != null) {
            buttonRect(g, botX, s(56), mouseX, mouseY);
            buttonRect(g, botX + s(66), s(52), mouseX, mouseY);
            buttonRect(g, botX + botW - s(52), s(52), mouseX, mouseY);
            return;
        }
        buttonRect(g, botX, s(46), mouseX, mouseY);
        buttonRect(g, botX + s(54), s(46), mouseX, mouseY);
        buttonRect(g, botX + s(114), s(46), mouseX, mouseY);
        buttonRect(g, botX + botW - s(52), s(52), mouseX, mouseY);
    }

    private void buttonRect(GuiGraphics g, int x, int w, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= footY && mouseY < footY + footH;
        WoodRender.plate(g, x, footY, w, footH, hover);
    }

    private void drawButtonLabel(GuiGraphics g, int x, int w, String label) {
        int tw = this.font.width(label);
        g.drawString(this.font, label, x + (w - tw) / 2, footY + (footH - 8) / 2,
                0xFFF3E2C0, false);
    }

    // ---------------------------------------------------------------- 交互

    /** 待发货提示行的位置（点击发货） */
    private boolean hitShipmentHint(double mx, double my) {
        if (tickerPos == null || billMode) {
            return false;
        }
        if (StoreCache.shipments().isEmpty()) {
            return false;
        }
        int hx = topX + s(18);
        int hy = topY + s(64);
        return mx >= hx - 4 && mx < hx + s(200) && my >= hy - 3 && my < hy + 9;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hitShipmentHint(mx, my)) {
            var ships = StoreCache.shipments();
            if (!ships.isEmpty()) {
                com.tangtu.kjbb.net.ClientTickerBridge.ship(tickerPos, ships.get(0).id());
                return true;
            }
        }

        if (button == 0) {
            if (tickerPos != null && !billMode) {
                // 买下/存入
                if (hit(mx, my, botX, s(56))) {
                    String id = fieldItem.getValue() == null ? "" : fieldItem.getValue().trim();
                    int count = 1;
                    try {
                        count = Integer.parseInt(fieldCount.getValue().trim());
                    } catch (Throwable ignored) {
                        count = 1;
                    }
                    if (!id.isEmpty()) {
                        if (storeBuyMode) {
                            com.tangtu.kjbb.net.TickerBillNetwork.buy(tickerPos, id, Math.max(1, count));
                        } else {
                            com.tangtu.kjbb.net.TickerBillNetwork.deposit(tickerPos, id, Math.max(1, count));
                        }
                    }
                    this.onClose();
                    return true;
                }
                // 切换 买 / 存
                if (hit(mx, my, botX + s(66), s(52))) {
                    this.storeBuyMode = !this.storeBuyMode;
                    this.selectedId = "";
                    try {
                        if (storeBuyMode) {
                            com.tangtu.kjbb.net.TickerBillNetwork.requestStore(tickerPos);
                        } else {
                            com.tangtu.kjbb.net.InvNetwork.request();
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    return true;
                }
                // 关闭
                if (hit(mx, my, botX + botW - s(52), s(52))) {
                    this.onClose();
                    return true;
                }
                // 网格点选
                int gx = botX + s(12);
                int gy = botY + s(8);
                int col = ((int) mx - gx) / SLOT;
                int row = ((int) my - gy) / SLOT;
                if (mx >= gx && my >= gy && col >= 0 && col < GRID_COLS && row >= 0 && row < GRID_ROWS) {
                    int idx = row * GRID_COLS + col;
                    if (storeBuyMode) {
                        java.util.List<StoreCache.Entry> sl = StoreCache.entries();
                        if (idx < sl.size()) {
                            this.selectedId = sl.get(idx).id();
                            this.fieldItem.setValue(this.selectedId);
                            this.fieldCount.setValue(String.valueOf(Math.min(64, sl.get(idx).count())));
                        }
                    } else {
                        java.util.List<InvCache.Entry> il = InvCache.entries();
                        if (idx < il.size()) {
                            this.selectedId = il.get(idx).id();
                            this.fieldItem.setValue(this.selectedId);
                            this.fieldCount.setValue(String.valueOf(Math.min(64, il.get(idx).count())));
                        }
                    }
                    return true;
                }
                return super.mouseClicked(mx, my, button);
            }
            if (billMode) {
                if (hit(mx, my, botX + botW - s(56), s(56))) {
                    this.onClose();
                    return true;
                }
                return super.mouseClicked(mx, my, button);
            }
            int w1 = s(46);

            // 签发
            if (hit(mx, my, botX, w1)) {
                String id = fieldItem.getValue() == null ? "" : fieldItem.getValue().trim();
                int count = 1;
                try {
                    count = Integer.parseInt(fieldCount.getValue().trim());
                } catch (Throwable ignored) {
                    count = 1;
                }
                if (!id.isEmpty()) {
                    com.tangtu.kjbb.net.BillNetwork.sendIssue(id, Math.max(1, count));
                }
                this.onClose();
                return true;
            }

            // 切换 物品 / 票据
            if (hit(mx, my, botX + s(54), w1)) {
                this.showItems = !this.showItems;
                if (this.showItems) {
                    try {
                        com.tangtu.kjbb.net.InvNetwork.request();
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                return true;
            }

            // 作废（暂等同关闭，下一步接 /bill void）
            if (hit(mx, my, botX + s(114), w1)) {
                this.onClose();
                return true;
            }

            // 关闭
            if (hit(mx, my, botX + botW - s(52), s(52))) {
                this.onClose();
                return true;
            }

            // 物品网格：点选
            if (this.showItems) {
                int gx = botX + s(12);
                int gy = botY + s(8);
                int col = ((int) mx - gx) / SLOT;
                int row = ((int) my - gy) / SLOT;
                if (mx >= gx && my >= gy && col >= 0 && col < GRID_COLS && row >= 0 && row < GRID_ROWS) {
                    int idx = row * GRID_COLS + col;
                    java.util.List<InvCache.Entry> list = InvCache.entries();
                    if (idx < list.size()) {
                        this.selectedId = list.get(idx).id();
                        this.fieldItem.setValue(this.selectedId);
                        this.fieldCount.setValue(String.valueOf(Math.min(64, list.get(idx).count())));
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean hit(double mx, double my, int x, int w) {
        return mx >= x && mx < x + w && my >= footY && my < footY + footH;
    }

    private static final class Logged {
        static boolean once = false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
