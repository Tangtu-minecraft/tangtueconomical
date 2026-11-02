package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 国民买下票据后的「上门收货」
 *
 * 流程：
 *   国民买下票据 → 货物仍留在被锁的容器里
 *        ↓
 *   等到任意玩家走进容器 8 格内（有人在场，模拟国民来收货）
 *        ↓
 *   把容器里【所有】东西取空 → 全部进国民库存 → 解除锁定
 *   （取不空就保持待收货，下一轮继续，不会提前判定作废）
 */
public final class TicketCollector {

    private TicketCollector() {
    }

    /** 待收货：容器坐标 + 票据号 + 卖家 */
    public static final class Pending {
        public BlockPos pos;
        public long ticketId;
        public String seller;
        /** 首次登记时的描述（仅用于日志） */
        public String firstItem;
        public int firstCount;
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    /** 收货触发半径（玩家距离容器多少格内） */
    public static final double NEAR = 8.0;

    public static synchronized void add(BlockPos pos, String itemId, int count, long ticketId,
                                        String seller) {
        Pending p = new Pending();
        p.pos = pos;
        p.ticketId = ticketId;
        p.seller = seller;
        p.firstItem = itemId;
        p.firstCount = count;
        PENDING.add(p);
        System.out.println("[TangtuEconomical] \u56fd\u6c11\u4e70\u4e0b\u7968\u636e #" + ticketId
                + "\uff0c\u7b49\u73a9\u5bb6\u9760\u8fd1 " + pos + " \u518d\u6536\u8d27\uff08"
                + itemId + " x" + count + " \u8d77\uff09");
    }

    public static synchronized void tick(MinecraftServer server) {
        if (PENDING.isEmpty() || server == null) {
            return;
        }
        var done = new ArrayList<Pending>();
        for (Pending p : PENDING) {
            if (p.pos == null) {
                done.add(p);
                continue;
            }
            ServerLevel level = server.overworld();
            if (level == null || !level.isLoaded(p.pos)) {
                continue;
            }
            boolean near = false;
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (sp.level() == level
                        && sp.blockPosition().distSqr(p.pos) <= NEAR * NEAR) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                continue;
            }
            // 一次取空整箱
            Map<String, Integer> got = withdrawAll(level, p.pos);
            int total = 0;
            for (var e : got.entrySet()) {
                if (e.getValue() > 0) {
                    BotLedger.addStock(citizenName(), e.getKey(), e.getValue());
                    total += e.getValue();
                }
            }
            if (total > 0) {
                BotLedger.save();
            }
            boolean empty = isEmpty(level, p.pos);
            System.out.println("[TangtuEconomical] \u56fd\u6c11\u6536\u8d27 \u7968\u636e#" + p.ticketId
                    + " \u53d6\u8d70 " + total + " \u4ef6\uff0c\u5bb9\u5668"
                    + (empty ? "\u5df2\u7a7a" : "\u4ecd\u6709\u6b8b\u7559") + " @ " + p.pos);
            if (!empty) {
                // 还有东西取不出来 → 保持待收货，下一轮再来
                continue;
            }
            try {
                StoreLocks.unlock(p.pos);
                // 货物已取空：清掉封条记录，避免之后被判定「内容不一致」
                StoreSeals.clear(p.pos);
            } catch (Throwable ignored) {
                // ignore
            }
            notifySeller(server, p, got, total);
            done.add(p);
        }
        PENDING.removeAll(done);
    }

    private static void notifySeller(MinecraftServer server, Pending p,
                                     Map<String, Integer> got, int total) {
        if (total <= 0) {
            return;
        }
        StringBuilder items = new StringBuilder();
        int n = 0;
        for (var e : got.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            if (n > 0) {
                items.append("\u00a77\u3001\u00a7f");
            }
            items.append(itemName(e.getKey())).append(" \u00d7").append(e.getValue());
            n++;
            if (n >= 4) {
                items.append("\u00a77 \u7b49");
                break;
            }
        }
        String note = "\u00a77[\u7968\u636e\u5e02\u573a] \u00a7e\u5510\u5154\u56fd\u56fd\u6c11"
                + "\u00a77\u5df2\u4e0a\u95e8\u63d0\u8d70\u4f60\u7968\u636e #" + p.ticketId
                + " \u5bf9\u5e94\u5bb9\u5668\u91cc\u7684\u5168\u90e8\u8d27\u7269\uff08\u00a7f"
                + items + "\u00a77\uff0c\u5171 " + total + " \u4ef6\uff09"
                + "\uff0c\u8fd9\u5f20\u7968\u5df2\u4f5c\u5e9f";
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (p.seller != null && !p.seller.isEmpty()
                    && p.seller.equalsIgnoreCase(sp.getScoreboardName())) {
                sp.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(note), false);
            }
        }
    }

    private static String citizenName() {
        try {
            for (String n : BotConfig.get().botOperateBots) {
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return "\u5510\u5154\u56fd\u56fd\u6c11";
    }

    /** 物品显示名 */
    private static String itemName(String itemId) {
        try {
            var it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
            if (it != null) {
                return new ItemStack(it).getHoverName().getString();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return itemId;
    }

    /** 容器是否已经空了 */
    private static boolean isEmpty(ServerLevel level, BlockPos pos) {
        try {
            var h = TickerStock.handlerAt(level, pos);
            if (h == null) {
                return true;
            }
            for (int i = 0; i < h.getSlots(); i++) {
                ItemStack st = h.getStackInSlot(i);
                if (st != null && !st.isEmpty()) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return true;
    }

    /** 把容器里所有东西一次性取空，返回 物品 → 数量 */
    private static Map<String, Integer> withdrawAll(ServerLevel level, BlockPos pos) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            var handler = TickerStock.handlerAt(level, pos);
            if (handler == null) {
                return out;
            }
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack st = handler.getStackInSlot(i);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                int want = st.getCount();
                ItemStack extracted = handler.extractItem(i, want, false);
                int got = extracted == null ? 0 : extracted.getCount();
                if (got > 0) {
                    out.merge(id, got, Integer::sum);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return out;
    }

    public static synchronized int size() {
        return PENDING.size();
    }
}
