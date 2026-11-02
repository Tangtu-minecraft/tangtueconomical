package com.tangtu.kjbb;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 硬币兼容层：让 create_currency_shops / numismatics 的硬币直接当钱用。
 *
 * 能力：
 *   ① 背包里的硬币按面值折算成唐兔币（付款时自动兑换）
 *   ② Coin Safe（保险柜）余额也能用（反射调用，未装 mod 时自动禁用）
 *   ③ /billcoins 查看各来源金额
 */
public final class Coins {

    /** 面值表（两套硬币都认） */
    private static final Map<String, Long> FACE = new LinkedHashMap<>();

    static {
        // numismatics（新币）
        FACE.put("numismatics:spur", 1L);
        FACE.put("numismatics:bevel", 8L);
        FACE.put("numismatics:sprocket", 16L);
        FACE.put("numismatics:cog", 64L);
        FACE.put("numismatics:crown", 512L);
        FACE.put("numismatics:sun", 4096L);
        // create_currency_shops（旧币）
        FACE.put("create_currency_shops:copper_coin", 1L);
        FACE.put("create_currency_shops:iron_coin", 5L);
        FACE.put("create_currency_shops:zinc_coin", 10L);
        FACE.put("create_currency_shops:brass_coin", 50L);
        FACE.put("create_currency_shops:gold_coin", 100L);
        FACE.put("create_currency_shops:netherite_coin", 500L);
    }

    /** 旧币 → 齿轮币的同档位对应关系（tierMap 开启时使用） */
    private static final String[][] TIER_PAIRS = {
            {"create_currency_shops:copper_coin", "numismatics:spur", "1"},
            {"create_currency_shops:iron_coin", "numismatics:bevel", "8"},
            {"create_currency_shops:zinc_coin", "numismatics:sprocket", "16"},
            {"create_currency_shops:brass_coin", "numismatics:cog", "64"},
            {"create_currency_shops:gold_coin", "numismatics:crown", "512"},
            {"create_currency_shops:netherite_coin", "numismatics:sun", "4096"},
    };

    private Coins() {
    }

    /** 旧币是否按齿轮币档位计价 */
    public static boolean tierMap() {
        try {
            return BotConfig.get().oldCoinTierMap;
        } catch (Throwable t) {
            return true;
        }
    }

    public static long faceValue(String itemId) {
        if (itemId == null) {
            return 0L;
        }
        if (tierMap() && itemId.startsWith("create_currency_shops:")) {
            for (String[] pair : TIER_PAIRS) {
                if (pair[0].equals(itemId)) {
                    return Long.parseLong(pair[2]);
                }
            }
        }
        Long v = FACE.get(itemId);
        return v == null ? 0L : v;
    }

    public static boolean isCoin(String itemId) {
        return FACE.containsKey(itemId);
    }

    /** 背包里所有硬币折合多少唐兔币 */
    public static long inventoryValue(ServerPlayer sp) {
        long sum = 0;
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
            if (rl == null) {
                continue;
            }
            long face = faceValue(rl.toString());
            if (face > 0) {
                sum += face * st.getCount();
            }
        }
        return sum;
    }

    /** 把背包硬币全部兑换成唐兔币，返回兑换金额 */
    public static long depositAll(ServerPlayer sp) {
        long total = 0;
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
            if (rl == null) {
                continue;
            }
            long face = faceValue(rl.toString());
            if (face <= 0) {
                continue;
            }
            total += face * st.getCount();
            st.setCount(0);
        }
        if (total > 0) {
            Wallet.addMoney(sp.getScoreboardName(), total);
        }
        return total;
    }

    /**
     * 从背包硬币里扣出指定金额（尽量凑够），剩余退回钱包。
     * 返回实际从硬币里出账的金额；0 表示硬币不够/没硬币。
     */
    public static long withdraw(ServerPlayer sp, long want) {
        if (want <= 0) {
            return 0L;
        }
        long have = inventoryValue(sp);
        if (have <= 0) {
            return 0L;
        }
        long take = Math.min(have, want);
        // 全部换成钱，再从钱包扣回多余部分（简单可靠）
        long all = depositAll(sp);
        if (all <= take) {
            return all;
        }
        // 多余部分退回背包太麻烦 → 留在钱包，等于「多存了」
        long change = all - take;
        Wallet.takeMoney(sp.getScoreboardName(), change);
        giveChangeCoins(sp, change);
        return take;
    }

    /** 把零钱以最小面额硬币形式退回 */
    private static void giveChangeCoins(ServerPlayer sp, long amount) {
        long left = amount;
        // 用 1 面值的硬币找零
        var spur = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.tryParse(
                        left > 0 ? "numismatics:spur" : "numismatics:spur"));
        if (spur == null) {
            return;
        }
        while (left > 0) {
            int n = (int) Math.min(64, left);
            var st = new ItemStack(spur, n);
            if (!sp.getInventory().add(st)) {
                sp.drop(st, false);
            }
            left -= n;
        }
    }

    /** 齿轮币面额（从大到小），用于找零 */
    private static final String[][] GEAR_TIERS = {
            {"numismatics:sun", "4096"},
            {"numismatics:crown", "512"},
            {"numismatics:cog", "64"},
            {"numismatics:sprocket", "16"},
            {"numismatics:bevel", "8"},
            {"numismatics:spur", "1"},
    };

    /** 把一批面值换成齿轮币（贪心大面额，余数用 spur） */
    private static long giveGear(net.minecraft.server.level.ServerPlayer sp, long value) {
        long left = value;
        for (String[] tier : GEAR_TIERS) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.tryParse(tier[0]));
            if (item == null) {
                continue;
            }
            long face = Long.parseLong(tier[1]);
            while (left >= face) {
                int n = (int) Math.min(64, left / face);
                var st = new ItemStack(item, n);
                if (!sp.getInventory().add(st)) {
                    sp.drop(st, false);
                }
                left -= (long) n * face;
            }
        }
        return value - left;
    }

    /**
     * 旧币 → 齿轮币（按面值等值兑换）。
     *
     * @return 兑换掉的面值总额
     */
    /** 旧币 1:1 换成同档齿轮币（tierMap 开启时用这个，不产生找零碎币） */
    private static long convertTierMap(ServerPlayer sp) {
        long value = 0;
        var inv = sp.getInventory();
        for (String[] pair : TIER_PAIRS) {
            var oldItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.tryParse(pair[0]));
            var newItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.tryParse(pair[1]));
            if (oldItem == null || newItem == null) {
                continue;
            }
            long face = Long.parseLong(pair[2]);
            int total = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st == null || st.isEmpty() || st.getItem() != oldItem) {
                    continue;
                }
                total += st.getCount();
                st.setCount(0);
            }
            int left = total;
            while (left > 0) {
                int n = Math.min(left, 64);
                var give = new ItemStack(newItem, n);
                if (!sp.getInventory().add(give)) {
                    sp.drop(give, false);
                }
                left -= n;
            }
            value += face * total;
        }
        return value;
    }

    public static long convertOldToGear(ServerPlayer sp) {
        if (tierMap()) {
            return convertTierMap(sp);
        }
        long value = 0;
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
            if (rl == null || !rl.toString().startsWith("create_currency_shops:")) {
                continue;
            }
            long face = faceValue(rl.toString());
            if (face <= 0) {
                continue;
            }
            value += face * st.getCount();
            st.setCount(0);
        }
        if (value > 0) {
            giveGear(sp, value);
            Wallet.save();
        }
        return value;
    }

    /** 旧币面额（从大到小），用于发放 */
    private static final String[][] OLD_TIERS = {
            {"create_currency_shops:netherite_coin", "500"},
            {"create_currency_shops:gold_coin", "100"},
            {"create_currency_shops:brass_coin", "50"},
            {"create_currency_shops:zinc_coin", "10"},
            {"create_currency_shops:iron_coin", "5"},
            {"create_currency_shops:copper_coin", "1"},
    };

    private static long giveOld(net.minecraft.server.level.ServerPlayer sp, long value) {
        long left = value;
        for (String[] tier : OLD_TIERS) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.tryParse(tier[0]));
            if (item == null) {
                continue;
            }
            long face = Long.parseLong(tier[1]);
            while (left >= face) {
                int n = (int) Math.min(64, left / face);
                var st = new ItemStack(item, n);
                if (!sp.getInventory().add(st)) {
                    sp.drop(st, false);
                }
                left -= (long) n * face;
            }
        }
        return value - left;
    }

    /**
     * 齿轮币 → 旧币（按面值等值兑换）。
     * 让两套硬币真正「共同使用」：随便换成哪一套都行。
     *
     * @return 兑换掉的面值总额
     */
    public static long convertGearToOld(ServerPlayer sp) {
        long value = 0;
        var inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
            if (rl == null || !rl.toString().startsWith("numismatics:")) {
                continue;
            }
            long face = faceValue(rl.toString());
            if (face <= 0) {
                continue;
            }
            value += face * st.getCount();
            st.setCount(0);
        }
        if (value > 0) {
            giveOld(sp, value);
        }
        return value;
    }

    /** 唐兔币 → 齿轮币（从钱包取钱换硬币），返回换出的面值 */
    public static long withdrawWalletToCoins(ServerPlayer sp, long want) {
        if (want <= 0) {
            return 0L;
        }
        String name = sp.getScoreboardName();
        long have = Wallet.money(name);
        long take = Math.min(have, want);
        if (take <= 0) {
            return 0L;
        }
        if (Wallet.takeMoney(name, take) < 0) {
            return 0L;
        }
        giveGear(sp, take);
        return take;
    }

    // ---------------------------------------------------------------- 保险柜

    private static java.lang.reflect.Method SAFE_GET;
    private static java.lang.reflect.Method SAFE_ADD;
    private static java.lang.reflect.Method SAFE_REMOVE;
    private static boolean safeTried = false;

    private static void initSafe() {
        if (safeTried) {
            return;
        }
        safeTried = true;
        try {
            Class<?> c = Class.forName(
                    "net.flaulox.create_currency_shops.util.CoinSafeBalanceManager");
            SAFE_GET = c.getMethod("getBalance", ServerLevel.class, java.util.UUID.class);
            SAFE_ADD = c.getMethod("addBalance", ServerLevel.class, java.util.UUID.class, int.class);
            SAFE_REMOVE = c.getMethod("removeBalance", ServerLevel.class,
                    java.util.UUID.class, int.class);
            System.out.println("[TangtuEconomical] 已接入 Coin Safe 余额");
        } catch (Throwable t) {
            SAFE_GET = null;
            SAFE_ADD = null;
            SAFE_REMOVE = null;
        }
    }

    /** 保险柜余额（没有 mod / 没余额 → 0） */
    public static long safeBalance(ServerPlayer sp) {
        initSafe();
        if (SAFE_GET == null || !(sp.level() instanceof ServerLevel level)) {
            return 0L;
        }
        try {
            Object v = SAFE_GET.invoke(null, level, sp.getUUID());
            return v instanceof Integer i ? i : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 从保险柜取出指定金额（转入钱包） */
    public static long takeFromSafe(ServerPlayer sp, long want) {
        initSafe();
        if (SAFE_REMOVE == null || want <= 0 || !(sp.level() instanceof ServerLevel level)) {
            return 0L;
        }
        long bal = safeBalance(sp);
        long take = Math.min(bal, want);
        if (take <= 0) {
            return 0L;
        }
        try {
            SAFE_REMOVE.invoke(null, level, sp.getUUID(), (int) take);
            Wallet.addMoney(sp.getScoreboardName(), take);
            return take;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 把金额拆成齿轮币最优组合，例如 600 -> crown×1 cog×1 sprocket×1 bevel×1 */
    public static String combo(long amount) {
        if (amount <= 0) {
            return "§7金额要大于 0";
        }
        StringBuilder sb = new StringBuilder();
        long left = amount;
        String[][] gear = {
                {"sun", "4096"}, {"crown", "512"}, {"cog", "64"},
                {"sprocket", "16"}, {"bevel", "8"}, {"spur", "1"},
        };
        for (String[] g : gear) {
            long face = Long.parseLong(g[1]);
            long n = left / face;
            if (n > 0) {
                if (sb.length() > 0) {
                    sb.append(" + ");
                }
                sb.append(g[0]).append("×").append(n);
                left -= n * face;
            }
        }
        if (left > 0) {
            sb.append(" (余 ").append(left).append(")");
        }
        return sb.toString();
    }

    public static String describe(ServerPlayer sp) {
        long wallet = Wallet.money(sp.getScoreboardName());
        long inv = inventoryValue(sp);
        long safe = safeBalance(sp);
        String mode = tierMap() ? "§7（旧币按同级换算：铜=spur 铁=bevel 锌=sprocket 黄铜=cog 金=crown 下界=sun）"
                : "§7（旧币按原面值 1/5/10/50/100/500）";
        return mode + "\n" + String.format("§e钱包 %d　背包硬币 %d　保险柜 %d　§a合计 %d 唐兔币",
                wallet, inv, safe, wallet + inv + safe);
    }
}
