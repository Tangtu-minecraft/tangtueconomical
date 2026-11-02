package com.tangtu.kjbb;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 记录玩家"下一次发报机下单要出票"的状态（60 秒有效） */
public final class BillMode {

    private static final Map<String, Long> PENDING = new ConcurrentHashMap<>();
    private static final long WINDOW_MS = 60_000L;

    /** 玩家签发的提单默认是否不记名 */
    private static final java.util.Map<java.util.UUID, Boolean> BEARER_PREF =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void setBearer(net.minecraft.server.level.ServerPlayer sp, boolean bearer) {
        BEARER_PREF.put(sp.getUUID(), bearer);
    }

    public static boolean bearerPref(net.minecraft.server.level.ServerPlayer sp) {
        return BEARER_PREF.getOrDefault(sp.getUUID(), false);
    }

    private BillMode() {
    }

    public static void mark(ServerPlayer sp) {
        PENDING.put(sp.getScoreboardName(), ServerClock.now());
    }

    /** 取用一次（取完即清） */
    public static boolean consume(ServerPlayer sp) {
        String name = sp.getScoreboardName();
        Long ts = PENDING.remove(name);
        return ts != null && (ServerClock.now() - ts) <= WINDOW_MS;
    }

    public static boolean peek(ServerPlayer sp) {
        Long ts = PENDING.get(sp.getScoreboardName());
        return ts != null && (ServerClock.now() - ts) <= WINDOW_MS;
    }
}