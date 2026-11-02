package com.tangtu.kjbb.client;

import java.util.ArrayList;
import java.util.List;

/** 我的提单列表缓存（客户端） */
public final class BillListCache {

    private static List<String> lines = new ArrayList<>();

    private BillListCache() {
    }

    public static void set(String body) {
        List<String> out = new ArrayList<>();
        if (body != null) {
            for (String s : body.split("\n")) {
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        lines = out;
    }

    public static List<String> lines() {
        return lines;
    }
}
