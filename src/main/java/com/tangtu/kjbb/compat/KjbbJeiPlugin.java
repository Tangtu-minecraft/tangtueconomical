package com.tangtu.kjbb.compat;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 联动（只在装了 JEI 的实例生效；没装 JEI 时本类不会被加载）
 *
 * 能力：
 *   ① 在 JEI 里「拖着」某个物品，丢到市场界面 / 物品选择器上 →
 *      直接把它加进市场（不用再去点搜索框）
 *
 * 说明：JEI 的「点一下物品」是查配方，不能当作选择；所以采用 JEI 官方的
 *       ghost ingredient（幽灵拖拽）机制，按住左键从 JEI 面板拖到我们界面上。
 */
@JeiPlugin
public final class KjbbJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("kjbankbridge", "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(
                com.tangtu.kjbb.client.MarketScreen.class, new MarketGhost<>());
        registration.addGhostIngredientHandler(
                com.tangtu.kjbb.client.ItemPickerScreen.class, new MarketGhost<>());
    }

    /** 把 JEI 拖过来的物品加进市场 */
    private static final class MarketGhost<T extends net.minecraft.client.gui.screens.Screen>
            implements IGhostIngredientHandler<T> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(T gui,
                                                   ITypedIngredient<I> ingredient,
                                                   boolean doStart) {
            List<Target<I>> out = new ArrayList<>();
            // 整个界面都是投放区
            out.add(new Target<>() {
                @Override
                public Rect2i getArea() {
                    return new Rect2i(0, 0, gui.width, gui.height);
                }

                @Override
                public void accept(I value) {
                    try {
                        ItemStack stack = null;
                        if (value instanceof ItemStack st) {
                            stack = st;
                        } else if (value instanceof net.minecraft.world.item.Item it) {
                            stack = new ItemStack(it);
                        }
                        if (stack == null || stack.isEmpty()) {
                            return;
                        }
                        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
                        if (rl == null) {
                            return;
                        }
                        com.tangtu.kjbb.net.MarketNetwork.addItem(rl.toString());
                        var p = Minecraft.getInstance().player;
                        if (p != null) {
                            p.displayClientMessage(Component.literal(
                                    "\u00a77[JEI] \u6b63\u5728\u628a "
                                            + stack.getHoverName().getString()
                                            + " \u52a0\u5165\u5e02\u573a\u2026"), true);
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            });
            return out;
        }

        @Override
        public void onComplete() {
            // 拖拽完成，无需处理
        }
    }
}
