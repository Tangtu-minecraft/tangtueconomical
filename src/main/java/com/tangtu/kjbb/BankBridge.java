package com.tangtu.kjbb;

/**
 * 钱包封装 —— 供 KubeJS / 内部代码调用（已改为自建存储，不再依赖 banksystem）。
 *
 * 约定（与旧版一致）：
 *   余额不足 / 扣款失败 → 返回 -1
 *   playerId 用玩家名（计分板名）
 */
public final class BankBridge {

    private BankBridge() {
    }

    private static final java.util.Set<String> ALLOWED_ITEMS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean account(String playerId) {
        return playerId != null && !playerId.isEmpty();
    }

    public static boolean hasAccount(String playerId) {
        return Wallet.names().contains(playerId);
    }

    public static String accountName(String playerId) {
        return playerId == null ? "" : playerId;
    }

    // ---------------------------------------------------------------- 钱

    /** 是不是机器人账户（机器人余额统一记在 BotLedger，不进玩家钱包） */
    public static boolean isBotAccount(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        try {
            if ("唐兔国航运公司".equals(id)) {
                return true;
            }
            for (BotConfig.Bot b : BotConfig.get().bots) {
                if (b != null && id.equals(b.name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return false;
    }

    public static long money(String playerId) {
        if (playerId == null || playerId.isEmpty()) {
            return -1L;
        }
        if (isBotAccount(playerId)) {
            return BotLedger.money(playerId);
        }
        return Wallet.money(playerId);
    }

    public static long addMoney(String playerId, long amount) {
        if (playerId == null || playerId.isEmpty() || amount <= 0L) {
            return -1L;
        }
        if (isBotAccount(playerId)) {
            BotLedger.addMoney(playerId, amount);
            return BotLedger.money(playerId);
        }
        return Wallet.addMoney(playerId, amount);
    }

    public static long takeMoney(String playerId, long amount) {
        if (playerId == null || playerId.isEmpty() || amount <= 0L) {
            return -1L;
        }
        if (isBotAccount(playerId)) {
            if (BotLedger.money(playerId) < amount) {
                return -1L;
            }
            BotLedger.addMoney(playerId, -amount);
            return BotLedger.money(playerId);
        }
        return Wallet.takeMoney(playerId, amount);
    }

    // ---------------------------------------------------------------- 物品账户

    public static long items(String playerId, String itemId) {
        if (playerId == null || itemId == null) {
            return -1L;
        }
        return Wallet.items(playerId, itemId);
    }

    public static long addItems(String playerId, String itemId, long count) {
        if (playerId == null || itemId == null || count <= 0L) {
            return -1L;
        }
        return Wallet.addItems(playerId, itemId, count);
    }

    public static long takeItems(String playerId, String itemId, long count) {
        if (playerId == null || itemId == null || count <= 0L) {
            return -1L;
        }
        return Wallet.takeItems(playerId, itemId, count);
    }

    public static String accounts(String playerId) {
        return playerId == null ? "" : Wallet.accounts(playerId);
    }

    // ---------------------------------------------------------------- 白名单 / 流通量

    public static long circulation() {
        return Wallet.circulation();
    }

    public static boolean isItemAllowed(String itemId) {
        return itemId != null && ALLOWED_ITEMS.contains(itemId);
    }

    public static boolean allowItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        ALLOWED_ITEMS.add(itemId);
        return true;
    }

    public static boolean disallowItem(String itemId) {
        if (itemId == null) {
            return false;
        }
        ALLOWED_ITEMS.remove(itemId);
        return true;
    }

    public static int allowedCount() {
        return ALLOWED_ITEMS.size();
    }

    // ---------------------------------------------------------------- 诊断

    public static String selftest(String playerId) {
        return "playerId=" + playerId + "\n"
                + "money=" + money(playerId) + "\n"
                + "items=" + accounts(playerId) + "\n"
                + "circulation=" + circulation() + "\n"
                + "allowedItems=" + allowedCount() + "\n";
    }

    public static String version() {
        return "2.0.0-selfhost";
    }
}
