package com.tangtu.kjbb;

/** 游戏时间流速倍率（1 = 原速）。每 tick 额外推进 (speed-1) 刻。 */
public final class TimeSpeed {

    public static volatile int SPEED = 1;

    private TimeSpeed() {
    }

    public static void set(int s) {
        SPEED = Math.max(1, Math.min(1000, s));
    }

    public static int get() {
        return SPEED;
    }
}
