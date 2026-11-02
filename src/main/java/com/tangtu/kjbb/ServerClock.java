package com.tangtu.kjbb;

/**
 * 统一时钟：所有「现在几点」都走这里。
 *
 * timeSource = "game"（默认）：用游戏总刻数换算成毫秒，/time 推进会立刻生效，方便测试。
 * timeSource = "real"          ：用系统真实时间，适合正式服（避免玩家刷时间绕过超时）。
 *
 * 阈值仍然按毫秒写（如 30 分钟 = 1_800_000），换算时 1 tick = 50ms。
 */
public final class ServerClock {

    private ServerClock() {
    }

    /** 当前时间（毫秒口径） */
    public static long now() {
        try {
            if (!"real".equalsIgnoreCase(BotConfig.get().timeSource)) {
                var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (srv != null && srv.overworld() != null) {
                    return srv.overworld().getGameTime() * 50L;
                }
            }
        } catch (Throwable ignored) {
            // 回退到真实时间
        }
        return System.currentTimeMillis();
    }
}
