package com.tangtu.kjbb;

import net.minecraft.server.level.ServerPlayer;

/**
 * 统一付款：钱包 → 背包硬币 → 保险柜，依次扣到够为止。
 *
 * 这样玩家不用手动 /deposit，身上有硬币也能直接买东西。
 */
public final class Pay {

    private Pay() {
    }

    /** 玩家当前可用于支付的总资产 */
    public static long liquid(ServerPlayer sp) {
        return Wallet.money(sp.getScoreboardName())
                + Coins.inventoryValue(sp)
                + Coins.safeBalance(sp);
    }

    /**
     * 尝试付款。
     *
     * @return true = 已扣够；false = 钱不够（不会扣任何钱）
     */
    public static boolean pay(ServerPlayer sp, long amount) {
        if (amount <= 0) {
            return true;
        }
        String name = sp.getScoreboardName();
        if (liquid(sp) < amount) {
            return false;
        }
        // ① 先扣钱包
        long need = amount;
        long have = Wallet.money(name);
        if (have > 0) {
            long use = Math.min(have, need);
            if (Wallet.takeMoney(name, use) >= 0) {
                need -= use;
            }
        }
        // ② 再扣背包硬币
        if (need > 0) {
            need -= Coins.withdraw(sp, need);
        }
        // ③ 最后扣保险柜
        if (need > 0) {
            need -= Coins.takeFromSafe(sp, need);
        }
        return need <= 0;
    }

    /**
     * 只用实物硬币付款（旧币/齿轮币都行，钱包不参与）。
     *
     * @return true = 硬币够且已扣
     */
    public static boolean payWithCoinsOnly(ServerPlayer sp, long amount) {
        if (amount <= 0) {
            return true;
        }
        if (Coins.inventoryValue(sp) < amount) {
            return false;
        }
        long covered = Coins.withdraw(sp, amount);
        return covered >= amount;
    }

    /**
     * 统一入账：机器人入 bot_ledger，玩家入 wallet。
     * 这样机器人只有一本账，不会出现「账本 26040 / 钱包 14174」两套数。
     */
    public static void credit(String name, long amount) {
        if (amount <= 0 || name == null || name.isEmpty()) {
            return;
        }
        try {
            if (BotLedger.PRINCIPAL.containsKey(name)) {
                BotLedger.addMoney(name, amount);
                return;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        Wallet.addMoney(name, amount);
    }

    /** 退款（一律退到钱包） */
    public static void refund(ServerPlayer sp, long amount) {
        if (amount > 0) {
            Wallet.addMoney(sp.getScoreboardName(), amount);
        }
    }
}
