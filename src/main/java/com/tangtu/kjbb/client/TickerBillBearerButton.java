package com.tangtu.kjbb.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 在机械动力的「仓储发报机」界面左上角加一个按钮：
 * 切换之后签发的提单是【记名】还是【不记名】。
 */
@EventBusSubscriber(modid = "kjbankbridge", value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.GAME)
public final class TickerBillBearerButton {

    private static Button button;

    private TickerBillBearerButton() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        String name = screen.getClass().getName();
        if (!name.contains("StockKeeperRequestScreen")) {
            return;
        }
        int w = screen.width;
        int h = screen.height;
        // 按发报机界面实测：下单按钮在 GUI 中心 右+53 / 下+265，宽 96 高 26
        int x = w / 2 + 53;
        int y = h / 2 + 293;
        // 诊断：把所有控件位置打进日志，方便定位发送按钮
        try {
            System.out.println("[TangtuEconomical] 发报机界面控件清单 screen=" + name
                    + " size=" + w + "x" + h);
            for (var listener : event.getListenersList()) {
                if (listener instanceof net.minecraft.client.gui.components.AbstractWidget aw) {
                    System.out.println("[TangtuEconomical]   控件 " + aw.getClass().getSimpleName()
                            + " text=\"" + (aw.getMessage() == null ? "" : aw.getMessage().getString())
                            + "\" x=" + aw.getX() + " y=" + aw.getY()
                            + " w=" + aw.getWidth() + " h=" + aw.getHeight()
                            + " visible=" + aw.visible);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        // 优先贴在「发送 / 下单」按钮的下面
        var sendBounds = findSendButton(event);
        if (sendBounds != null) {
            x = sendBounds[0];
            y = sendBounds[1] + sendBounds[3] + 2;
        }
        if (y + 16 > h - 4) {
            y = h - 24;
        }
        if (x + 96 > w - 4) {
            x = w - 100;
        }
        button = Button.builder(label(), b -> {
            com.tangtu.kjbb.net.ClientBillBearer.toggle();
            b.setMessage(label());
        }).bounds(x, y, 96, 16).build();
        event.addListener(button);

        // 第二个按钮：发货（把锁定给你的订单从这台发报机发出去）
        int shipX = Math.max(4, x - 100);
        int shipY = y;
        final String shipCmd;
        var bp = tickerPosOf(screen);
        if (bp != null) {
            shipCmd = "/billorder shipnext " + bp.getX() + " " + bp.getY() + " " + bp.getZ();
        } else {
            shipCmd = "/billorder shipnext";
        }
        Button shipBtn = Button.builder(Component.literal("\u53d1\u8d27"), b -> {
            try {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.tangtu.kjbb.net.MenuNetwork.Action(shipCmd));
            } catch (Throwable ignored) {
                // ignore
            }
        }).bounds(shipX, shipY, 96, 16).build();
        event.addListener(shipBtn);
    }

    /** 在界面里找「发送/下单/Send」按钮，返回 {x, y, w, h} */
    private static int[] findSendButton(ScreenEvent.Init.Post event) {
        try {
            for (var listener : event.getListenersList()) {
                if (!(listener instanceof net.minecraft.client.gui.components.AbstractWidget widget)) {
                    continue;
                }
                String text = widget.getMessage() == null ? ""
                        : widget.getMessage().getString();
                if (text.contains("发送") || text.contains("下单")
                        || text.toLowerCase().contains("send")) {
                    return new int[]{widget.getX(), widget.getY(), widget.getWidth(),
                            widget.getHeight()};
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 反射取出界面绑定的仓储发报机坐标（StockKeeperRequestScreen.blockEntity） */
    private static net.minecraft.core.BlockPos tickerPosOf(Screen screen) {
        try {
            Class<?> c = screen.getClass();
            while (c != null) {
                for (var f : c.getDeclaredFields()) {
                    if (!net.minecraft.world.level.block.entity.BlockEntity.class
                            .isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object be = f.get(screen);
                    if (be instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
                        return blockEntity.getBlockPos();
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    private static Component label() {
        return Component.literal(com.tangtu.kjbb.net.ClientBillBearer.isBearer()
                ? "提单：不记名"
                : "提单：记名");
    }

    /** 供语言覆盖逻辑复用（判断是否在出票模式） */
    public static boolean bound() {
        return Minecraft.getInstance().screen != null
                && Minecraft.getInstance().screen.getClass().getName()
                        .contains("StockKeeperRequestScreen");
    }
}
