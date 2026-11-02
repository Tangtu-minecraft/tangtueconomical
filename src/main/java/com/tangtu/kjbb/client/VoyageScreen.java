package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 远洋提单界面（从服务中心进入）
 *
 * 三个分页：
 *   ① 船票   —— 在募资/航行的船，可认购、可出资
 *   ② 我的   —— 我持有的份额，可挂卖、提货、折现
 *   ③ 市场   —— 二级市场挂单，可买入
 */
public class VoyageScreen extends Screen {

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("kjbb", "textures/gui/form_sheet.png");
    private static final int W = 320, H = 291;
    private static final int IN_L = 43, IN_R = 283, IN_T = 45, IN_B = 246;

    private static final int INK = 0x3A2A18;
    private static final int INK_DIM = 0x7A5A32;
    private static final int SEL = 0xFFC8A870;
    private static final int GOOD = 0xFF2E7D32;
    private static final int WARN = 0xFFB06040;

    private static final String[] TABS = {"船票", "我的", "市场"};

    private int left;
    private int top;

    private int tab = 0;
    /** 自由输入份数 */
    private net.minecraft.client.gui.components.EditBox qtyBox;
    private long selId = -1L;

    private static final class Bill {
        long id;
        String name = "";
        String status = "";
        int sold;
        int total;
        long price;
        long shareValue;
        long cash;
        long targetCash;
        long deadline;
        long dueDay;
        String outcome = "";
        int mine;
        long issueDay;
        int legs;
        int legDone;
        int condition = 100;
        int mult = 100;
        String legLog = "";
        final List<String> cargoItems = new ArrayList<>();
        final List<Integer> cargoHave = new ArrayList<>();
        final List<Integer> cargoNeed = new ArrayList<>();
    }

    private static final class Listing {
        long id;
        long billId;
        int shares;
        long price;
        String seller = "";
    }

    private final List<Bill> bills = new ArrayList<>();
    private final List<Listing> listings = new ArrayList<>();

    public VoyageScreen() {
        super(Component.literal("远洋提单"));
    }

    @Override
    protected void init() {
        super.init();
        this.left = this.width / 2 - W / 2;
        this.top = this.height / 2 - H / 2;
        this.addRenderableOnly((g, mx, my, pt) -> this.drawAll(g, mx, my));
        this.qtyBox = new net.minecraft.client.gui.components.EditBox(
                this.font, left + IN_L + 146, top + 192, 36, 14,
                net.minecraft.network.chat.Component.literal("份数"));
        this.qtyBox.setMaxLength(5);
        this.qtyBox.setValue("10");
        this.qtyBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,5}"));
        this.addRenderableWidget(this.qtyBox);
        requestData();
    }

    public static void requestData() {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tangtu.kjbb.net.MenuNetwork.VoyageReq());
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ---------------- 数据 ----------------

    /** 当前输入的份数（1~999） */
    private int qty() {
        try {
            String s = qtyBox == null ? "" : qtyBox.getValue().trim();
            if (s.isEmpty()) {
                return 1;
            }
            int v = Integer.parseInt(s);
            return Math.max(1, Math.min(999, v));
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private void parse() {
        bills.clear();
        listings.clear();
        String data = MarketCache.voyage();
        if (data == null || data.isEmpty()) {
            return;
        }
        for (String line : data.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\\|", -1);
            try {
                if ("B".equals(f[0]) && f.length >= 16) {
                    Bill b = new Bill();
                    b.id = Long.parseLong(f[1]);
                    b.name = f[2];
                    b.status = f[3];
                    b.sold = Integer.parseInt(f[4]);
                    b.total = Integer.parseInt(f[5]);
                    b.price = Long.parseLong(f[6]);
                    b.shareValue = Long.parseLong(f[7]);
                    b.cash = Long.parseLong(f[8]);
                    b.targetCash = Long.parseLong(f[9]);
                    b.deadline = Long.parseLong(f[10]);
                    b.dueDay = Long.parseLong(f[11]);
                    b.outcome = f[12];
                    b.mine = Integer.parseInt(f[13]);
                    b.issueDay = Long.parseLong(f[14]);
                    parseCargo(b, f[15]);
                    if (f.length > 16) {
                        b.legs = Integer.parseInt(f[16]);
                    }
                    if (f.length > 17) {
                        b.legDone = Integer.parseInt(f[17]);
                    }
                    if (f.length > 18) {
                        b.condition = Integer.parseInt(f[18]);
                    }
                    if (f.length > 19) {
                        b.mult = Integer.parseInt(f[19]);
                    }
                    if (f.length > 20) {
                        b.legLog = f[20];
                    }
                    bills.add(b);
                } else if (("L".equals(f[0]) || "S".equals(f[0])) && f.length >= 6) {
                    Listing l = new Listing();
                    l.id = Long.parseLong(f[1]);
                    l.billId = Long.parseLong(f[2]);
                    l.shares = Integer.parseInt(f[3]);
                    l.price = Long.parseLong(f[4]);
                    l.seller = f[5];
                    listings.add(l);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        applyInventoryHoldings();
        if (selId < 0) {
            List<?> cur = currentRows();
            if (!cur.isEmpty()) {
                selId = idOf(cur.get(0));
            }
        }
    }

    /** 扫自己背包里的股票，累计每艘船的份数（玩家认购直接发票） */
    private void applyInventoryHoldings() {
        try {
            var player = Minecraft.getInstance().player;
            if (player == null) {
                return;
            }
            java.util.Map<Long, Integer> held = new java.util.HashMap<>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack st = player.getInventory().getItem(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                if (!(st.getItem() instanceof com.tangtu.kjbb.item.StockItem)) {
                    continue;
                }
                long billId = com.tangtu.kjbb.item.StockItem.billOf(st);
                int shares = com.tangtu.kjbb.item.StockItem.sharesOf(st);
                if (billId > 0 && shares > 0) {
                    held.merge(billId, shares, Integer::sum);
                }
            }
            for (Bill b : bills) {
                Integer v = held.get(b.id);
                b.mine = v == null ? b.mine : v;
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private void parseCargo(Bill b, String raw) {
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String rest = part.substring(eq + 1);
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            try {
                b.cargoItems.add(part.substring(0, eq));
                b.cargoHave.add(Integer.parseInt(rest.substring(0, slash)));
                b.cargoNeed.add(Integer.parseInt(rest.substring(slash + 1)));
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private List<?> currentRows() {
        if (tab == 2) {
            return listings;
        }
        List<Bill> out = new ArrayList<>();
        for (Bill b : bills) {
            if (tab == 0) {
                if (!"CLOSED".equals(b.status)) {
                    out.add(b);
                }
            } else if (b.mine > 0) {
                out.add(b);
            }
        }
        return out;
    }

    private static long idOf(Object row) {
        if (row instanceof Bill b) {
            return b.id;
        }
        if (row instanceof Listing l) {
            return l.id;
        }
        return -1L;
    }

    private Bill selBill() {        for (Bill b : bills) {
            if (b.id == selId) {
                return b;
            }
        }
        return null;
    }

    private Listing selListing() {
        for (Listing l : listings) {
            if (l.id == selId) {
                return l;
            }
        }
        return null;
    }

    // ---------------- 绘制 ----------------

    private static String fmt(long v) {
        if (v >= 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fM", v / 1_000_000.0);
        }
        if (v >= 10000L) {
            return String.format(java.util.Locale.ROOT, "%.1fK", v / 1000.0);
        }
        return String.valueOf(v);
    }

    private static ItemStack iconOf(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                return ItemStack.EMPTY;
            }
            var item = BuiltInRegistries.ITEM.get(rl);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
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

    private void drawAll(GuiGraphics g, int mouseX, int mouseY) {
        parse();
        g.blit(TEX, left, top, W, H, 0.0f, 0.0f, 1315, 1196, 1315, 1196);

        String title = "远 洋 提 单";
        g.drawString(this.font, title, left + (W - this.font.width(title)) / 2, top + 46, INK, false);

        // 分页标签：三个都是木制按钮（沿用 WoodRender 的统一外观）
        int tx = left + IN_L + 26;
        for (int i = 0; i < TABS.length; i++) {
            int w = 50;
            int h = 16;
            boolean on = tab == i;
            boolean hover = mouseX >= tx && mouseX < tx + w
                    && mouseY >= top + 77 && mouseY < top + 77 + h;
            WoodRender.plate(g, tx, top + 77, w, h, on || hover);
            g.drawString(this.font, TABS[i], tx + (w - this.font.width(TABS[i])) / 2,
                    top + 81, WoodRender.TEXT, false);
            tx += w + 3;
        }

        // 左列
        List<?> rows = currentRows();
        int listTop = top + 94;
        int rowH = 17;
        int listW = 106;
        if (rows.isEmpty()) {
            g.drawString(this.font, emptyHint(), left + IN_L + 8, listTop, INK_DIM, false);
        }
        for (int i = 0; i < rows.size() && i < 11; i++) {
            Object r = rows.get(i);
            long id = idOf(r);
            boolean on = id == selId;
            int y = listTop + i * rowH;
            boolean hover = mouseX >= left + IN_L + 10 && mouseX < left + IN_L + 10 + listW
                    && mouseY >= y - 2 && mouseY < y + 14;
            if (tab == 0) {
                // 船票页：纯文字（不加木按钮）
                if (on) {
                    g.fill(left + IN_L + 10, y - 1, left + IN_L + 10 + listW, y + rowH - 4, SEL);
                }
                if (r instanceof Bill b) {
                    g.drawString(this.font, "#" + b.id, left + IN_L + 14, y,
                            on ? INK : INK_DIM, false);
                    g.drawString(this.font, statusText(b.status), left + IN_L + 40, y,
                            on ? INK : INK_DIM, false);
                }
            } else {
                // 我的 / 市场页：木制按钮
                WoodRender.plate(g, left + IN_L + 10, y - 2, listW, 15, on || hover);
                if (r instanceof Bill b) {
                    String txt = "#" + b.id + "  " + b.mine + " 份";
                    g.drawString(this.font, txt, left + IN_L + 16, y + 1, WoodRender.TEXT, false);
                } else if (r instanceof Listing l) {
                    String txt = "#" + l.id + " 船" + l.billId;
                    g.drawString(this.font, txt, left + IN_L + 16, y + 1, WoodRender.TEXT, false);
                    String price = l.shares + "份 " + l.price;
                    g.drawString(this.font, price,
                            left + IN_L + 10 + listW - 4 - this.font.width(price), y + 1,
                            0xFFFFE08A, false);
                }
            }
        }

        // 右栏
        if (tab == 2) {
            drawMarketPanel(g, mouseX, mouseY);
        } else {
            drawBillPanel(g, mouseX, mouseY, tab == 1);
        }

        // 详情不再用按钮：点左列某艘船直接打开（船票页），我的页选中后也能点开
        button(g, "刷新", left + IN_L + 6, top + 232, 46, 14, mouseX, mouseY);
        // 航运中心（罗马国买卖 / 包船 / 船队）挂在船票页
        if (tab == 0) {
            button(g, "航运", left + IN_L + 110, top + 232, 46, 14, mouseX, mouseY);
        }
        button(g, "返回", left + IN_R - 52, top + 232, 46, 14, mouseX, mouseY);
    }

    private String emptyHint() {
        if (tab == 0) {
            return "（暂时没有船）";
        }
        if (tab == 1) {
            return "（还没持有份额）";
        }
        return "（没有挂单）";
    }

    private void drawBillPanel(GuiGraphics g, int mouseX, int mouseY, boolean minePage) {
        int x = left + IN_L + 124;
        Bill b = selBill();
        if (b == null) {
            g.drawString(this.font, "（请选择左边的船）", x, top + 94, INK_DIM, false);
            return;
        }
        int y = top + 94;
        g.drawString(this.font, b.name, x, y, INK, false);
        y += 12;
        g.drawString(this.font, "份 " + b.sold + "/" + b.total + "   价 " + b.price, x, y, INK_DIM, false);
        y += 12;
        g.drawString(this.font, "值 " + b.shareValue + "   池 " + fmt(b.cash) + "/" + fmt(b.targetCash),
                x, y, INK_DIM, false);
        y += 12;
        String tl = "发行 " + com.tangtu.kjbb.ServerCalendar.shortDate(b.issueDay)
                + "  截止 " + com.tangtu.kjbb.ServerCalendar.shortDate(b.deadline);
        if (b.dueDay > 0) {
            tl += "  到港 " + com.tangtu.kjbb.ServerCalendar.shortDate(b.dueDay);
        }
        g.drawString(this.font, tl, x, y, INK_DIM, false);
        y += 12;
        g.drawString(this.font, "我的 " + b.mine + " 份   " + statusText(b.status)
                + (b.outcome.isEmpty() ? "" : (" · " + b.outcome)),
                x, y, b.mine > 0 ? GOOD : INK_DIM, false);
        y += 13;
        g.drawString(this.font, "载货进度", x, y, INK, false);
        y += 11;
        for (int i = 0; i < b.cargoItems.size() && i < 3; i++) {
            String item = b.cargoItems.get(i);
            int have = b.cargoHave.get(i);
            int need = b.cargoNeed.get(i);
            double pct = need <= 0 ? 1.0 : Math.min(1.0, have / (double) need);
            ItemStack icon = iconOf(item);
            if (!icon.isEmpty()) {
                g.renderItem(icon, x, y - 3);
            }
            int barX = x + 19;
            int barW = 36;
            g.fill(barX, y, barX + barW, y + 6, 0x33FFFFFF);
            int fillW = (int) Math.round(barW * pct);
            if (fillW > 0) {
                g.fill(barX, y, barX + fillW, y + 6, pct >= 0.999 ? GOOD : WARN);
            }
            g.drawString(this.font, have + "/" + need, barX + barW + 3, y, INK_DIM, false);
            y += 12;
        }

        // 航行分段信息（出海后才有意义）
        if (b.legs > 0 && (b.legDone > 0 || "VOYAGING".equals(b.status)
                || "DUE".equals(b.status) || "CLOSED".equals(b.status))) {
            y += 2;
            g.drawString(this.font, "航段 " + b.legDone + "/" + b.legs
                    + "   船况 " + b.condition + "   倍率 " + b.mult + "%", x, y,
                    b.mult >= 100 ? GOOD : (b.mult >= 75 ? INK_DIM : WARN), false);
            y += 11;
            if (!b.legLog.isEmpty()) {
                String[] parts = b.legLog.split(";");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) {
                        sb.append("  ");
                    }
                    sb.append(i + 1).append(".");
                    sb.append(parts[i]);
                }
                String line = sb.toString();
                if (line.length() > 32) {
                    line = line.substring(0, 32) + "…";
                }
                g.drawString(this.font, line, x, y, INK_DIM, false);
            }
        }

        int by = top + 196;
        int bw = 36;
        int gap = 2;
        if (!minePage && "OPEN".equals(b.status)) {
            g.drawString(this.font, "份数", x, top + 195, INK_DIM, false);
            button(g, "认购", x + 60, top + 192, 34, 14, mouseX, mouseY);
            long cost = (long) qty() * Math.max(1L, b.price);
            button(g, "出资" + fmt(cost), x + 96, top + 192, 58, 14, mouseX, mouseY);
            button(g, "全买", x + 60, top + 210, 34, 14, mouseX, mouseY);
            button(g, "清空", x + 96, top + 210, 34, 14, mouseX, mouseY);
        }
        if (minePage && b.mine > 0) {
            button(g, "上架", x, by, bw, 14, mouseX, mouseY);
            if ("DUE".equals(b.status)) {
                button(g, "提货", x + bw + gap, by, bw, 14, mouseX, mouseY);
                button(g, "折现", x + (bw + gap) * 2, by, bw, 14, mouseX, mouseY);
            }
        }
    }

    /** 把状态写成一段话（详情页 VoyageNarrativeScreen 用） */
    private java.util.List<String> narrativeLines(Bill b) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (b == null) {
            return lines;
        }
        String cargoKind = b.cargoItems.size() + " 种";
        long cargoValue = b.shareValue * b.total;
        long pct = b.total <= 0 ? 0 : Math.min(100, b.sold * 100L / b.total);

        if ("OPEN".equals(b.status)) {
            lines.add("唐兔国航运公司的" + b.name + "正在募资，船上已装 " + cargoKind
                    + "货，货值约 " + fmt(cargoValue) + " 唐兔币。");
            lines.add("全部 " + b.total + " 份里已认购 " + b.sold + " 份（" + pct
                    + "%），资金池 " + fmt(b.cash) + " / " + fmt(b.targetCash) + "。");
            lines.add("募资到 " + com.tangtu.kjbb.ServerCalendar.shortDate(b.deadline)
                    + " 结束。到时候若是认购的人太少，这趟航次会取消，钱原路退回。");
            lines.add("你可以认购份额，也可以直接把货交给船上。");
        } else if ("VOYAGING".equals(b.status)) {
            lines.add(b.name + "已经离港，预计第 " + b.dueDay + " 日靠岸，眼下走完 "
                    + b.legDone + " / " + b.legs + " 段航程。");
            lines.add("船上载着 " + cargoKind + "货物，约值 " + fmt(cargoValue)
                    + " 唐兔币。" + conditionWord(b.condition) + "，" + lossWord(b.mult) + "。");
            if (!b.legLog.isEmpty()) {
                StringBuilder sb = new StringBuilder("航程记录：");
                String[] parts = b.legLog.split(";");
                for (int i = 0; i < parts.length; i++) {
                    sb.append("第").append(i + 1).append("段").append(parts[i]).append("；");
                }
                lines.add(sb.toString());
            }
            lines.add("船越破，后面越容易出事；真要沉了，票子就成废纸。");
        } else if ("DUE".equals(b.status)) {
            lines.add(b.name + "已经靠岸，这一趟的结果是「" + b.outcome + "」。");
            lines.add("货算下来每份 " + b.shareValue + " 唐兔币，"
                    + lossWord(b.mult) + "，实际每份约 "
                    + Math.round(b.shareValue * b.mult / 100.0) + "。");
            lines.add("你持有 " + b.mine + " 份，可以在「我的」页选择折现或提货。");
            lines.add("所有持有人都结算完，这艘船才会正式结束。");
        } else {
            lines.add(b.name + "的航次已经结束"
                    + (b.outcome.isEmpty() ? "。" : ("，结果「" + b.outcome + "」。")));
            lines.add("共发行 " + b.total + " 份，每份 " + b.price + " 唐兔币。");
            lines.add("这条船的货物与资金都已结清，记录会保留备查。");
        }

        return lines;
    }

    /** 船况 → 说法（不给具体数字） */
    private static String conditionWord(int cond) {
        if (cond >= 90) {
            return "船身结实，看着还能跑很久";
        }
        if (cond >= 70) {
            return "船身有几处修补过的痕迹";
        }
        if (cond >= 45) {
            return "船板已经松动，水手脸上都不太好看";
        }
        if (cond >= 20) {
            return "船漏得厉害，谁都不敢保证能撑到岸";
        }
        return "这船随时会散架";
    }

    /** 累计损失 → 说法（不给百分比） */
    private static String lossWord(int mult) {
        if (mult >= 100) {
            return "舱里的货看起来没少";
        }
        if (mult >= 85) {
            return "路上颠掉了一点";
        }
        if (mult >= 70) {
            return "损失了一些，但还剩大半";
        }
        if (mult >= 40) {
            return "折损相当大，只剩一部分";
        }
        if (mult > 0) {
            return "几乎没剩下什么";
        }
        return "什么都没剩下";
    }

    /** 简易中文换行 */
    private void drawMarketPanel(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + IN_L + 124;
        Listing l = selListing();
        if (l == null) {
            g.drawString(this.font, "（没有挂单）", x, top + 94, INK_DIM, false);
            return;
        }
        int y = top + 94;
        g.drawString(this.font, "挂单 #" + l.id, x, y, INK, false);
        y += 12;
        g.drawString(this.font, "船 #" + l.billId + "   " + l.shares + " 份", x, y, INK_DIM, false);
        y += 12;
        g.drawString(this.font, "单价 " + l.price + "/份", x, y, INK_DIM, false);
        y += 12;
        g.drawString(this.font, "总价 " + fmt(l.price * l.shares), x, y, INK_DIM, false);
        y += 12;
        g.drawString(this.font, "卖家 " + l.seller, x, y, INK_DIM, false);

        int by = top + 196;
        button(g, "买下", x, by, 44, 14, mouseX, mouseY);
        button(g, "撤单", x + 48, by, 44, 14, mouseX, mouseY);
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int h,
                        int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        WoodRender.plate(g, x, y, w, h, hover);
        g.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2,
                WoodRender.TEXT, false);
    }

    private static void cmd(String c) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tangtu.kjbb.net.MenuNetwork.Action(c));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ---------------- 交互 ----------------

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 空实现：遮罩在 render() 里按屏幕坐标画，否则会被缩放一起缩掉
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 遮罩必须画在压入缩放之前，否则四周会露出世界
        g.fill(0, 0, this.width, this.height, 0xB0101010);
        // 逻辑分辨率装不下 320x291 面板时，整体等比缩小
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
    public boolean mouseClicked(double mx, double my, int button) {
        // 面板被等比缩小时，鼠标坐标要换算回面板坐标系
        float k = UiScale.factor(this.width, this.height, W, H, 4);
        if (k < 1.0f) {
            mx = UiScale.mapX(mx, left, W, k);
            my = UiScale.mapY(my, top, H, k);
        }
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        // 分页
        int tx = left + IN_L + 26;
        for (int i = 0; i < TABS.length; i++) {
            int w = 50;
            if (my >= top + 77 && my < top + 93 && mx >= tx && mx < tx + w) {
                tab = i;
                selId = -1L;
                List<?> rows = currentRows();
                if (!rows.isEmpty()) {
                    selId = idOf(rows.get(0));
                }
                return true;
            }
            tx += w + 3;
        }

        // 左列
        List<?> rows = currentRows();
        int listTop = top + 94;
        int rowH = 17;
        int listX0 = left + IN_L + 10;
        if (mx >= listX0 && mx < listX0 + 112) {
            for (int i = 0; i < rows.size() && i < 11; i++) {
                int y = listTop + i * rowH;
                if (my >= y - 1 && my < y + rowH - 4) {
                    long id = idOf(rows.get(i));
                    selId = id;
                    // 点船次 = 打开详情页（叙述），不再需要底部「详情」按钮
                    Bill picked = selBill();
                    if (picked != null) {
                        Minecraft.getInstance().setScreen(
                                new VoyageShipScreen(picked.id, narrativeLines(picked)));
                    }
                    return true;
                }
            }
        }

        // 底部
        if (my >= top + 232 && my < top + 246) {
            if (tab == 0 && mx >= left + IN_L + 110 && mx < left + IN_L + 156) {
                Minecraft.getInstance().setScreen(new ShippingScreen());
                return true;
            }
            if (mx >= left + IN_L + 6 && mx < left + IN_L + 52) {
                requestData();
                return true;
            }
            if (mx >= left + IN_R - 52 && mx < left + IN_R - 6) {
                Minecraft.getInstance().setScreen(new FormCenterScreen());
                return true;
            }
        }

        Bill b = selBill();
        Listing l = selListing();
        int x = left + IN_L + 124;
        int by = top + 196;
        int bw = 36;
        int gap = 2;

        if (tab == 0 && b != null && "OPEN".equals(b.status)) {
            int n = qty();
            if (hit(mx, my, x + 60, top + 192, 34, 14)) {
                cmd("/billvoyage buy " + b.id + " " + n);
                refreshLater();
                return true;
            }
            if (hit(mx, my, x + 96, top + 192, 58, 14)) {
                cmd("/billvoyage invest " + ((long) n * Math.max(1L, b.price)));
                refreshLater();
                return true;
            }
            if (hit(mx, my, x + 60, top + 210, 34, 14)) {
                cmd("/billvoyage buy " + b.id + " " + Math.max(1, b.total - b.sold));
                refreshLater();
                return true;
            }
            if (hit(mx, my, x + 96, top + 210, 34, 14)) {
                if (qtyBox != null) {
                    qtyBox.setValue("10");
                }
                return true;
            }
        }

        if (tab == 1 && b != null && b.mine > 0) {
            if (hit(mx, my, x, by, bw, 14)) {
                cmd("/billvoyage stall auto");
                refreshLater();
                return true;
            }
            if ("DUE".equals(b.status)) {
                if (hit(mx, my, x + bw + gap, by, bw, 14)) {
                    cmd("/billvoyage settle " + b.id + " goods");
                    refreshLater();
                    return true;
                }
                if (hit(mx, my, x + (bw + gap) * 2, by, bw, 14)) {
                    cmd("/billvoyage settle " + b.id + " cash");
                    refreshLater();
                    return true;
                }
            }
        }

        if (tab == 2 && l != null) {
            if (hit(mx, my, x, by, 44, 14)) {
                cmd("/billvoyage stabuy " + l.id);
                refreshLater();
                return true;
            }
            if (hit(mx, my, x + 48, by, 44, 14)) {
                cmd("/billvoyage stacancel " + l.id);
                refreshLater();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void refreshLater() {
        new Thread(() -> {
            try {
                Thread.sleep(320L);
            } catch (Throwable ignored) {
                // ignore
            }
            Minecraft.getInstance().execute(VoyageScreen::requestData);
        }, "kjbb-voyage-refresh").start();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
