package com.tangtu.kjbb;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * 物品估值：会递归钻进容器看内容
 *
 * 支持：
 *   · 普通物品           → 单价 × 数量
 *   · 潜影盒 / 箱子类     → DataComponents.CONTAINER
 *   · 收纳袋（Bundle）     → DataComponents.BUNDLE_CONTENTS
 *   · 机械动力包裹 / 背包  → CUSTOM_DATA 里的 Items / Inventory 列表
 *
 * 没有市场价也没有基准价的物品，按「64 件 = 1 唐兔币」折算。
 */
public final class ItemValuation {

    private ItemValuation() {
    }

    /** 递归深度上限，防止恶意嵌套 */
    private static final int MAX_DEPTH = 4;

    /** 单件价格：市场价 → 基准价 → 0 */
    public static long unitPrice(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return 0L;
        }
        long unit = MarketPrice.price(itemId);
        if (unit <= 0L) {
            unit = ShopPrices.price(itemId);
        }
        return Math.max(0L, unit);
    }

    /** 一堆物品的估值（含容器内容） */
    public static long worth(ItemStack stack) {
        return worth(stack, 0);
    }

    private static long worth(ItemStack stack, int depth) {
        if (stack == null || stack.isEmpty() || depth > MAX_DEPTH) {
            return 0L;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int count = stack.getCount();
        // 仓单：按票面记录的货物估值（货物本身锁在容器里，不能重复计算）
        if ("kjbankbridge:store_receipt".equals(id)) {
            long face = 0L;
            try {
                for (var line : com.tangtu.kjbb.item.StoreReceiptItem.readRecord(stack)) {
                    long u = unitPrice(line.itemId());
                    face += (u > 0L ? u : 1L) * line.count();
                }
            } catch (Throwable ignored) {
                // ignore
            }
            long receiptUnit = unitPrice(id);   // 仓单本身的名义价
            return face + Math.max(0L, receiptUnit) * count;
        }
        long unit = unitPrice(id);
        long own;
        if (unit > 0L) {
            own = unit * count;
        } else {
            own = Math.max(1L, count / 64L);
        }
        long inside = contentsWorth(stack, depth);
        return own + inside;
    }

    /** 容器内容价值 */
    private static long contentsWorth(ItemStack stack, int depth) {
        long sum = 0L;
        try {
            ItemContainerContents container = stack.get(DataComponents.CONTAINER);
            if (container != null) {
                for (ItemStack inner : container.nonEmptyItems()) {
                    sum += worth(inner, depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
            if (bundle != null) {
                for (ItemStack inner : bundle.items()) {
                    sum += worth(inner, depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // 机械动力包裹 / 背包：CUSTOM_DATA 里的 Items 或 Inventory 列表
        try {
            var custom = stack.get(DataComponents.CUSTOM_DATA);
            if (custom != null) {
                var tag = custom.copyTag();
                for (String key : new String[]{"Items", "Inventory", "items", "inventory"}) {
                    if (!tag.contains(key)) {
                        continue;
                    }
                    var list = tag.getList(key, 10);
                    for (int i = 0; i < list.size(); i++) {
                        var c = list.getCompound(i);
                        String id = c.getString("id");
                        int n = c.contains("count") ? c.getInt("count")
                                : (c.contains("Count") ? c.getByte("Count") : 1);
                        if (id == null || id.isEmpty() || n <= 0) {
                            continue;
                        }
                        long unit = unitPrice(id);
                        sum += unit > 0L ? unit * n : Math.max(1L, n / 64L);
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return sum;
    }

    /** 一个容器（箱子等）里全部物品的估值 */
    public static long handlerWorth(net.neoforged.neoforge.items.IItemHandler handler) {
        if (handler == null) {
            return 0L;
        }
        long sum = 0L;
        try {
            for (int i = 0; i < handler.getSlots(); i++) {
                sum += worth(handler.getStackInSlot(i), 0);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return sum;
    }
}
