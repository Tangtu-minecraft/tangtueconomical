package com.tangtu.kjbb.net;

/**
 * 大字符串分片：Minecraft 单个字符串字段上限 32767 字符，
 * 行情 / K 线 / 挂单这类「行数随数据增长」的 payload 一律按 {@link #SIZE} 切片发送。
 *
 * <p>发送端：{@code for (int i = 0; i < parts; i++) send(new X(i, parts, Chunk.slice(body, i)));}
 * <p>接收端：每片 {@code String done = CHUNKS.feed(seq, parts, body);}，非 null 表示收齐了。
 */
public final class Chunk {

    /** 单片字符数（留足余量给 32767 的上限） */
    public static final int SIZE = 30000;

    private Chunk() {
    }

    public static int parts(String s) {
        int len = s == null ? 0 : s.length();
        return Math.max(1, (len + SIZE - 1) / SIZE);
    }

    public static String slice(String s, int i) {
        if (s == null) {
            return "";
        }
        int from = Math.min(s.length(), i * SIZE);
        int to = Math.min(s.length(), from + SIZE);
        return s.substring(from, to);
    }

    /** 客户端拼包缓冲：同一类型同一时间只有一个请求，用单例即可 */
    public static final class Inbox {
        private final StringBuilder sb = new StringBuilder();
        private int parts = 1;
        private int got;

        /** 收一片；收齐返回完整内容，否则返回 null */
        public String feed(int seq, int parts, String body) {
            if (seq <= 0) {
                sb.setLength(0);
                this.parts = Math.max(1, parts);
                this.got = 0;
            }
            sb.append(body == null ? "" : body);
            this.got++;
            if (this.got >= this.parts) {
                String all = sb.toString();
                sb.setLength(0);
                this.got = 0;
                return all;
            }
            return null;
        }
    }
}
