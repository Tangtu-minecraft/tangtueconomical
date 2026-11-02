package com.tangtu.kjbb;

/**
 * 账本路由：同一次交易里可能一方是机器人、一方是玩家。
 *
 * 机器人记账走 BotLedger，玩家走钱包（Wallet / BankBridge）。
 * 混用会导致「玩家卖了货但钱进了不存在的机器人账户」。
 */
public final class Ledger {

    private Ledger() {
    }

    /** 是不是机器人账户 */
    public static boolean isBot(String who) {
        if (who == null || who.isEmpty()) {
            return true;
        }
        // 统一名册优先：新增机器人登记一次即可
        try {
            if (com.tangtu.kjbb.BotAccounts.isBot(who)) {
                return true;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            if (BotConfig.bot(who) != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            if (com.tangtu.kjbb.BankBridge.isBotAccount(who)) {
                return true;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            if (who.equals(com.tangtu.kjbb.VoyageBill.issuerName())) {
                return true;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            if (who.equals(com.tangtu.kjbb.RomaMarket.NAME)) {
                return true;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            if (com.tangtu.kjbb.BotLedger.allNames().contains(who)) {
                return true;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return false;
    }

    /** 查余额（谁都能查） */
    public static long money(String who) {
        try {
            return com.tangtu.kjbb.Accounts.money(who);
        } catch (Throwable ignored) {
            // 回退旧逻辑
        }
        if (who == null || who.isEmpty()) {
            return 0L;
        }
        if (isBot(who)) {
            try {
                return BotLedger.money(who);
            } catch (Throwable ignored) {
                return 0L;
            }
        }
        try {
            return com.tangtu.kjbb.Wallet.money(who);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 给钱（机器人和玩家都正确） */
    public static void credit(String who, long amount) {
        try {
            com.tangtu.kjbb.Accounts.credit(who, amount);
            return;
        } catch (Throwable ignored) {
            // 回退旧逻辑
        }
        if (who == null || who.isEmpty() || amount == 0L) {
            return;
        }
        if (isBot(who)) {
            try {
                BotLedger.addMoney(who, amount);
            } catch (Throwable ignored) {
                // ignore
            }
        } else {
            try {
                com.tangtu.kjbb.Wallet.addMoney(who, amount);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 扣钱（返回实际扣到的金额） */
    public static long take(String who, long amount) {
        try {
            return com.tangtu.kjbb.Accounts.take(who, amount);
        } catch (Throwable ignored) {
            // 回退旧逻辑
        }
        if (who == null || who.isEmpty() || amount <= 0L) {
            return 0L;
        }
        long have = money(who);
        long take = Math.min(have, amount);
        if (take <= 0L) {
            return 0L;
        }
        if (isBot(who)) {
            try {
                BotLedger.addMoney(who, -take);
            } catch (Throwable ignored) {
                return 0L;
            }
        } else {
            try {
                com.tangtu.kjbb.Wallet.takeMoney(who, take);
            } catch (Throwable ignored) {
                return 0L;
            }
        }
        return take;
    }

    /** 记货（机器人进账本，玩家进背包/掉落） */
    public static void addStock(String who, String itemId, int count) {
        if (who == null || who.isEmpty() || count <= 0) {
            return;
        }
        if (isBot(who)) {
            try {
                BotLedger.addStock(who, itemId, count);
            } catch (Throwable ignored) {
                // ignore
            }
            return;
        }
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null) {
                return;
            }
            var sp = srv.getPlayerList().getPlayerByName(who);
            if (sp == null) {
                // 玩家不在线：记到账本，等他上线时由其它流程处理
                BotLedger.addStock(who, itemId, count);
                return;
            }
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.tryParse(itemId));
            if (item == null) {
                return;
            }
            int left = count;
            int max = new net.minecraft.world.item.ItemStack(item).getMaxStackSize();
            while (left > 0) {
                int n = Math.min(left, max);
                var st = new net.minecraft.world.item.ItemStack(item, n);
                if (!sp.getInventory().add(st)) {
                    sp.drop(st, false);
                }
                left -= n;
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }
}