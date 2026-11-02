package com.tangtu.kjbb;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 一次性迁移：把 banksystem 里的余额与物品账户搬进自建钱包。
 *
 * 全程用反射，不产生编译期依赖 —— 没装 banksystem 时直接返回说明。
 */
public final class WalletMigration {

    private WalletMigration() {
    }

    public static String run() {
        try {
            Class<?> mgr = Class.forName("net.kroia.banksystem.banking.ServerBankManager");
            Method getAllUsers = null;
            for (Method m : mgr.getMethods()) {
                if (m.getName().equals("getUser") && m.getParameterCount() == 0) {
                    getAllUsers = m;
                    break;
                }
            }
            if (getAllUsers == null) {
                return "§c找不到 banksystem 的账户列表接口，无法迁移";
            }
            Object raw = getAllUsers.invoke(null);
            if (!(raw instanceof Map<?, ?> map)) {
                return "§c账户列表为空或类型不对";
            }
            int accounts = 0;
            long moneySum = 0;
            int itemsKinds = 0;
            for (Object v : map.values()) {
                if (v == null) {
                    continue;
                }
                String name = null;
                try {
                    Method getName = v.getClass().getMethod("getPlayerName");
                    Object n = getName.invoke(v);
                    name = n == null ? null : n.toString();
                } catch (Throwable ignored) {
                    // 忽略
                }
                if (name == null || name.isEmpty()) {
                    continue;
                }
                long money = 0L;
                try {
                    Method mb = v.getClass().getMethod("getMoneyBalance");
                    Object m0 = mb.invoke(v);
                    if (m0 instanceof Long l) {
                        money = l;
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
                if (money > 0) {
                    Wallet.addMoney(name, money);
                    moneySum += money;
                }
                try {
                    Method getMap = v.getClass().getMethod("getBankMap");
                    Object raw2 = getMap.invoke(v);
                    if (raw2 instanceof Map<?, ?> banks) {
                        for (var e : banks.entrySet()) {
                            String itemId = String.valueOf(e.getKey());
                            if (itemId == null || "money".equals(itemId)) {
                                continue;
                            }
                            long bal = 0L;
                            try {
                                Method getBal = e.getValue().getClass().getMethod("getBalance");
                                Object b0 = getBal.invoke(e.getValue());
                                if (b0 instanceof Long l2) {
                                    bal = l2;
                                }
                            } catch (Throwable ignored) {
                                // 忽略
                            }
                            if (bal > 0) {
                                Wallet.addItems(name, itemId, bal);
                                itemsKinds++;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
                accounts++;
            }
            Wallet.save();
            return "§a迁移完成：账户 " + accounts + " 个，金钱 " + moneySum
                    + " 唐兔币，物品账户 " + itemsKinds + " 条";
        } catch (ClassNotFoundException e) {
            return "§7没有安装 banksystem，无需迁移";
        } catch (Throwable t) {
            return "§c迁移失败: " + t;
        }
    }
}
