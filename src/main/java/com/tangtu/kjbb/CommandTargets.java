package com.tangtu.kjbb;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 命令目标解析
 *
 * NPC（Easy NPC 等）触发命令时，命令源往往不是玩家本身，
 * 直接 getPlayer() 会得到 null，界面就打不开。
 *
 * 这里做兜底：命令源不是玩家时，取执行位置附近最近的真人玩家。
 */
public final class CommandTargets {

    private CommandTargets() {
    }

    /** 附近多远处算「最近的玩家」 */
    private static final double RADIUS = 12.0;

    /**
     * 解析出「这条命令应该服务哪个玩家」
     *
     * @return 找不到返回 null
     */
    public static ServerPlayer player(CommandSourceStack source) {
        if (source == null) {
            return null;
        }
        // ① 命令源本身就是玩家
        ServerPlayer direct = source.getPlayer();
        if (direct != null && direct.connection != null) {
            return direct;
        }
        // ② 退而求其次：执行位置附近最近的玩家
        try {
            Vec3 pos = source.getPosition();
            var server = source.getServer();
            if (server == null) {
                return null;
            }
            ServerPlayer best = null;
            double bestDist = Double.MAX_VALUE;
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (sp == null || sp.connection == null || sp.isSpectator()) {
                    continue;
                }
                double d = sp.position().distanceToSqr(pos);
                if (d < bestDist && d <= RADIUS * RADIUS) {
                    bestDist = d;
                    best = sp;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
