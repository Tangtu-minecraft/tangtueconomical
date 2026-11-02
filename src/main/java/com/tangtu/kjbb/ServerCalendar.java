package com.tangtu.kjbb;

/**
 * 服务器历法
 *
 *   1 月 = 30 日
 *   1 年 = 12 月 = 360 日
 *
 * 游戏内 1 日 = 24,000 tick（20 分钟现实时间）
 * 所以 1 年 = 360 × 20 分钟 = 120 小时现实时间 = 5 天现实时间
 */
public final class ServerCalendar {

    private ServerCalendar() {
    }

    /** 每月天数 */
    public static final int DAYS_PER_MONTH = 30;
    /** 每年月数 */
    public static final int MONTHS_PER_YEAR = 12;
    /** 每年天数 */
    public static final int DAYS_PER_YEAR = DAYS_PER_MONTH * MONTHS_PER_YEAR;

    /** 当前游戏日（从 0 开始） */
    public static long currentDay() {
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv != null && srv.overworld() != null) {
                return srv.overworld().getDayTime() / 24000L;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0L;
    }

    /** 年（从 1 开始） */
    public static long year(long day) {
        return day / DAYS_PER_YEAR + 1L;
    }

    /** 月（1~12） */
    public static long month(long day) {
        return (day % DAYS_PER_YEAR) / DAYS_PER_MONTH + 1L;
    }

    /** 日（1~30） */
    public static long dayOfMonth(long day) {
        return day % DAYS_PER_MONTH + 1L;
    }

    /** 格式化：第 3 年 5 月 12 日 */
    public static String format(long day) {
        return "\u7b2c " + year(day) + " \u5e74 " + month(day) + " \u6708 "
                + dayOfMonth(day) + " \u65e5";
    }

    /** 紧凑日期：3年5月12日（界面/提示用；day < 0 返回「未定」） */
    public static String shortDate(long day) {
        if (day < 0L) {
            return "未定";
        }
        return year(day) + "年" + month(day) + "月" + dayOfMonth(day) + "日";
    }

    /** 当前日期 */
    public static String now() {
        return format(currentDay());
    }

    /** 简短格式：3 年 5 月 12 日 */
    public static String shortNow() {
        long day = currentDay();
        return year(day) + " \u5e74 " + month(day) + " \u6708 " + dayOfMonth(day) + " \u65e5";
    }

    /** 农历风格的月份名（正月、二月……腊月） */
    public static String monthName(long month) {
        String[] names = {"\u6b63\u6708", "\u4e8c\u6708", "\u4e09\u6708", "\u56db\u6708",
                "\u4e94\u6708", "\u516d\u6708", "\u4e03\u6708", "\u516b\u6708",
                "\u4e5d\u6708", "\u5341\u6708", "\u51ac\u6708", "\u814a\u6708"};
        int i = (int) Math.max(0, Math.min(11, month - 1));
        return names[i];
    }

    /** 完整中文日期：第 3 年 五月初五（这里日为数字） */
    public static String prettyNow() {
        long day = currentDay();
        return "\u7b2c " + year(day) + " \u5e74 " + monthName(month(day))
                + "\u7b2c " + dayOfMonth(day) + " \u65e5";
    }
}
