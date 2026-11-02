package com.tangtu.kjbb;

import com.tangtu.kjbb.item.BillData;
import com.tangtu.kjbb.item.BillItem;
import com.tangtu.kjbb.item.BillRegistry;
import com.tangtu.kjbb.item.KjbbItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * 卖单成交后的「提单式投递」
 *
 *   买下卖单 → 直接给买家一张提单（写着物品、数量、提货地址）
 *        ↓
 *   买家拿着提单，右键自己的蛙港 / 包裹港 / 邮筒
 *        ↓
 *   物品打包成 Create 包裹塞进邮箱 → 提单消失
 *
 * 好处：
 *   · 玩家不在邮箱旁边也能买东西（提单在手上）
 *   · 邮件系统只收到「包裹」，符合蛙港规则
 *   · 提单有地址，可以转卖给别人代取（记名可改）
 */
public final class BillDelivery {

    private BillDelivery() {
    }

    /** 提单有效期：7 天 */
    private static final long EXPIRE_MS = 7L * 24L * 3600_000L;

    /**
     * 给玩家一张「取货提单」
     *
     * @return 是否成功
     */
    public static boolean giveBill(ServerPlayer sp, String itemId, int count, String address) {
        try {
            long serial = BillRegistry.register(itemId, count, sp.getScoreboardName(),
                    ServerClock.now() + EXPIRE_MS, 0, 0, 0,
                    address == null ? "" : address);
            var data = BillRegistry.dataOf(serial);
            if (data == null) {
                return false;
            }
            ItemStack bill = new ItemStack(KjbbItems.BILL.get());
            KjbbItems.setBill(bill, data);
            BillItem.setBearer(bill, false);          // 记名：只有买家能取
            if (!sp.getInventory().add(bill)) {
                sp.drop(bill, false);
            }
            sp.displayClientMessage(Component.literal(
                    "\u00a7a[\u7968\u636e] \u4f60\u6210\u4ea4\u4e86\u4e00\u5f20\u53d6\u8d27\u63d0\u5355\u00a77\uff08"
                            + BillRegistry.dataOf(serial).itemId() + " \u00d7" + count
                            + "\uff09\uff0c\u62ff\u7740\u5b83\u53f3\u952e\u4f60\u7684\u1996\u6e2f/\u90ae\u7b52\u5373\u53ef\u53d6\u8d27"),
                    false);
            System.out.println("[TangtuEconomical] \u751f\u6210\u53d6\u8d27\u63d0\u5355 #" + serial
                    + " \u4e70\u5bb6=" + sp.getScoreboardName() + " " + itemId + " x" + count
                    + " \u5730\u5740=" + address);
            return true;
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u751f\u6210\u63d0\u5355\u5931\u8d25: " + t);
            return false;
        }
    }

    /**
     * 右键蛙港 / 包裹港 / 邮筒：若手上提单地址匹配，就把货塞进邮箱并消耗提单
     */
    @SubscribeEvent
    public static void onUsePort(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (!AddressBook.isValidPort(level, pos)) {
            return;
        }
        // 主手 / 副手找提单
        for (var hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack stack = sp.getItemInHand(hand);
            String kind = TicketMarket.kindOf(stack);
            if (kind == null || !TicketMarket.KIND_BILL.equals(kind)) {
                continue;
            }
            var data = KjbbItems.getBill(stack);
            if (data == null) {
                continue;
            }
            if (!BillData.STATUS_ISSUED.equals(data.status())) {
                continue;
            }
            // 找这个邮箱（地址过滤串要匹配提单上的地址）
            var port = AddressBook.findPort(level, pos, data.address());
            if (port == null) {
                sp.displayClientMessage(Component.literal(
                        "\u00a7c[\u7968\u636e] \u8fd9\u4e2a\u90ae\u7bb1\u7684\u5730\u5740\u4e0e\u63d0\u5355\u4e0d\u5339\u914d"),
                        false);
                return;
            }
            // 装机
            List<ItemStack> goods = new java.util.ArrayList<>();
            String itemId = data.itemId();
            int left = data.count();
            var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.tryParse(itemId));
            int maxStack = it == null ? 64 : Math.max(1, new ItemStack(it).getMaxStackSize());
            while (it != null && left > 0) {
                int n = Math.min(left, maxStack);
                goods.add(new ItemStack(it, n));
                left -= n;
            }
            if (goods.isEmpty()) {
                return;
            }
            boolean ok = AddressBook.deliverTo(port, goods);
            if (!ok) {
                sp.displayClientMessage(Component.literal(
                        "\u00a7c[\u7968\u636e] \u90ae\u7bb1\u6ca1\u7a7a\u95f4\u6216\u4e0d\u53ef\u7528"), false);
                return;
            }
            stack.shrink(1);
            BillRegistry.setStatus(data.serial(), BillData.STATUS_REDEEMED);
            sp.displayClientMessage(Component.literal(
                    "\u00a7a[\u7968\u636e] \u8d27\u7269\u5df2\u9001\u5165\u90ae\u7bb1\uff0c\u63d0\u5355\u5df2\u6d88\u5931"),
                    false);
            System.out.println("[TangtuEconomical] \u7968\u636e\u63d0\u8d27 #" + data.serial()
                    + " \u2192 \u90ae\u7bb1 " + pos + " " + itemId + " x" + data.count());
            return;
        }
    }
}
