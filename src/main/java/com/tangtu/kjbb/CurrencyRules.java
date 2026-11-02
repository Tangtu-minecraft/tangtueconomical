package com.tangtu.kjbb;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 结算货币规则。
 *
 * config/kjbb/bots.json 里的 "coinOnlyItems" 列表：
 *   其中的物品【只能用实物硬币结算】（旧币或齿轮币均可），
 *   唐兔币记账不允许直接支付 —— 用于外贸等需要硬通货的场景。
 */
public final class CurrencyRules {

    private CurrencyRules() {
    }

    public static Set<String> coinOnly() {
        return new LinkedHashSet<>(BotConfig.coinOnlyItems());
    }

    public static boolean isCoinOnly(String itemId) {
        if (itemId == null) {
            return false;
        }
        for (String s : BotConfig.coinOnlyItems()) {
            if (s.equalsIgnoreCase(itemId)) {
                return true;
            }
        }
        return false;
    }

    public static String describe() {
        var list = BotConfig.coinOnlyItems();
        return list.isEmpty() ? "（无：全部可用唐兔币结算）" : String.join(", ", list);
    }
}
