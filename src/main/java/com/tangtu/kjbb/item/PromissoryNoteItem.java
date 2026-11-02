package com.tangtu.kjbb.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 本票（Promissory Note）
 *
 * 出票人自己承诺「见票即付」一定金额，不由银行担保。
 * 数据存在 CUSTOM_DATA 里：
 *     kjbb_pn_amount   金额（唐兔币）
 *     kjbb_pn_issuer   出票人（玩家名）
 *     kjbb_pn_bearer   收款人（不记名时为 空）
 *     kjbb_pn_due      到期时间（毫秒，0 = 见票即付）
 */
public class PromissoryNoteItem extends Item {

    public PromissoryNoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        var tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        long amount = tag.getLong("kjbb_pn_amount");
        String issuer = tag.getString("kjbb_pn_issuer");
        String bearer = tag.getString("kjbb_pn_bearer");

        tooltip.add(Component.literal("\u00a76\u672c\u7968\uff08\u51fa\u7968\u4eba\u627f\u5151\uff09")
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("\u00a77\u91d1\u989d\uff1a\u00a7e"
                + (amount > 0 ? amount + " \u5510\u5154\u5e01" : "\uff08\u7a7a\u767d\uff09")));
        tooltip.add(Component.literal("\u00a77\u51fa\u7968\u4eba\uff1a\u00a7f"
                + (issuer.isEmpty() ? "\uff08\u7a7a\u767d\uff09" : issuer)));
        tooltip.add(Component.literal("\u00a77\u6536\u6b3e\u4eba\uff1a\u00a7f"
                + (bearer.isEmpty() ? "\u4e0d\u8bb0\u540d\uff08\u6301\u7968\u4eba\uff09" : bearer)));
        if (flag.isAdvanced()) {
            tooltip.add(Component.literal("\u00a78\u89c1\u7968\u5373\u4ed8\uff1b\u65e0\u94f6\u884c\u62c5\u4fdd\uff0c"
                    + "\u51fa\u7968\u4eba\u8d25\u7ea6\u5373\u4e3a\u5e9f\u7eb8").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
