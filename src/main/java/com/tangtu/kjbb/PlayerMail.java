package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 玩家邮件：把货物打包寄到玩家绑定的提货点（蛙港 / 包裹港 / 邮箱）
 *
 * 找不到地址时返回 false，调用方自行退回背包。
 */
public final class PlayerMail {

    private PlayerMail() {
    }

    /** 寄到玩家绑定的地址 */
    public static boolean sendToPlayer(ServerLevel level, ServerPlayer sp, List<ItemStack> goods) {
        if (level == null || sp == null || goods == null || goods.isEmpty()) {
            return false;
        }
        String addr = AddressBook.addressOf(sp);
        if (addr == null || addr.isEmpty()) {
            return false;
        }
        BlockPos p = AddressBook.get(addr);
        if (p == null) {
            p = sp.blockPosition();
        }
        try {
            return AddressBook.deliver(level, p, addr, goods);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u90ae\u5bc4\u5931\u8d25: " + t);
            return false;
        }
    }
}
