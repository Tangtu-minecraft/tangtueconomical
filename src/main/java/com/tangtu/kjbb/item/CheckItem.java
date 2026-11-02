package com.tangtu.kjbb.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 支票（Check）
 *
 * 出票人指令「见票即付」指定金额，通常先冻结资金再开出。
 * 数据存在 CUSTOM_DATA 里：
 *     kjbb_ck_amount   金额（唐兔币）
 *     kjbb_ck_drawer   出票人
 *     kjbb_ck_bearer   收款人（空 = 不记名）
 *     kjbb_ck_issued   开票时间（毫秒）
 *     kjbb_ck_expire   失效时间（毫秒，0 = 不过期）
 */
public class CheckItem extends Item {

    public CheckItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        var tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        long amount = tag.getLong("kjbb_ck_amount");
        String drawer = tag.getString("kjbb_ck_drawer");
        String bearer = tag.getString("kjbb_ck_bearer");

        tooltip.add(Component.literal("\u00a7b\u652f\u7968\uff08\u89c1\u7968\u5373\u4ed8\uff09")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("\u00a77\u91d1\u989d\uff1a\u00a7e"
                + (amount > 0 ? amount + " \u5510\u5154\u5e01" : "\uff08\u7a7a\u767d\uff09")));
        tooltip.add(Component.literal("\u00a77\u51fa\u7968\u4eba\uff1a\u00a7f"
                + (drawer.isEmpty() ? "\uff08\u7a7a\u767d\uff09" : drawer)));
        tooltip.add(Component.literal("\u00a77\u6536\u6b3e\u4eba\uff1a\u00a7f"
                + (bearer.isEmpty() ? "\u4e0d\u8bb0\u540d\uff08\u6301\u7968\u4eba\uff09" : bearer)));
        if (flag.isAdvanced()) {
            tooltip.add(Component.literal("\u00a78\u5f00\u7968\u65f6\u91d1\u989d\u5df2\u4ece\u51fa\u7968\u4eba\u8d26\u6237\u6263\u51fa"
                    + "\uff0c\u5151\u4ed8\u540e\u7968\u636e\u9500\u6bc1").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
