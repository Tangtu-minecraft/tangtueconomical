package com.tangtu.kjbb.client;

/** 客户端：玩家是否处于"用空白单据下单"的模式（决定按钮显示「下单」还是「发送」） */
public final class BillModeClient {

    private static long expiresAt = 0L;
    private static boolean lastState = false;

    private BillModeClient() {
    }

    public static void mark() {
        expiresAt = System.currentTimeMillis() + 120_000L;
    }

    public static void clear() {
        expiresAt = 0L;
    }

    /** 出票模式：手里拿着空白单据，或刚用空白单据点过发报机 */
    public static boolean active() {
        boolean byTime = System.currentTimeMillis() < expiresAt;
        boolean byHand = false;
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null) {
                var blank = com.tangtu.kjbb.item.KjbbItems.BLANK_FORM.get();
                byHand = mc.player.getMainHandItem().is(blank)
                        || mc.player.getOffhandItem().is(blank);
            }
        } catch (Throwable ignored) {
            byHand = false;
        }
        boolean now = byTime || byHand;
        if (now != lastState) {
            lastState = now;
            System.out.println("[TangtuEconomical] 出票模式=" + now + "（时间=" + byTime
                    + " 手牌=" + byHand + "）");
        }
        return now;
    }
}
