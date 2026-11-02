package com.tangtu.kjbb.client;

import java.util.ArrayList;
import java.util.List;

/** 统计局面板的数据缓存（客户端） */
public final class StatsData {

    /** 每行 {类型, 左, 右}；类型 m=页眉 h=小标题 r=数值行 */
    private static List<String[]> rows = new ArrayList<>();
    private static long stamp = 0L;

    private StatsData() {
    }

    public static void set(String body) {
        List<String[]> out = new ArrayList<>();
        if (body != null) {
            for (String line : body.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f.length < 3) {
                    out.add(new String[]{"r", line, ""});
                } else {
                    out.add(new String[]{f[0], f[1], f[2]});
                }
            }
        }
        rows = out;
        stamp = System.currentTimeMillis();
    }

    public static List<String[]> rows() {
        return rows;
    }

    public static boolean empty() {
        return rows.isEmpty();
    }

    public static boolean stale() {
        return rows.isEmpty() || System.currentTimeMillis() - stamp > 60_000L;
    }

    /** 页眉行（m）——目前只有一行：唐兔国日期 + 生成时间 */
    public static String[] meta() {
        for (String[] r : rows) {
            if ("m".equals(r[0])) {
                return r;
            }
        }
        return null;
    }

    /** 去掉页眉后的正文行 */
    public static List<String[]> body() {
        List<String[]> out = new ArrayList<>();
        for (String[] r : rows) {
            if (!"m".equals(r[0])) {
                out.add(r);
            }
        }
        return out;
    }
}
