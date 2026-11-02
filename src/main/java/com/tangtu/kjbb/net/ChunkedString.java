package com.tangtu.kjbb.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 会自动分片的字符串编解码器。
 *
 * <p>Minecraft 单个字符串字段上限 32767 字符，行情 / 挂单 / 库存这类「行数随数据增长」的内容
 * 很容易超。用它替换 {@code ByteBufCodecs.STRING_UTF8} 即可：写的时候按 30000 字符切片
 * （先写片数，再逐片写），读的时候拼回来，payload 记录本身不用改。
 */
public final class ChunkedString {

    private static final int SIZE = 30000;

    private ChunkedString() {
    }

    public static final StreamCodec<FriendlyByteBuf, String> CODEC = new StreamCodec<>() {

        @Override
        public String decode(FriendlyByteBuf buf) {
            int parts = buf.readVarInt();
            if (parts <= 1) {
                return buf.readUtf();
            }
            StringBuilder sb = new StringBuilder(parts * 1024);
            for (int i = 0; i < parts; i++) {
                sb.append(buf.readUtf());
            }
            return sb.toString();
        }

        @Override
        public void encode(FriendlyByteBuf buf, String value) {
            String s = value == null ? "" : value;
            int parts = Math.max(1, (s.length() + SIZE - 1) / SIZE);
            buf.writeVarInt(parts);
            for (int i = 0; i < parts; i++) {
                int from = i * SIZE;
                buf.writeUtf(s.substring(from, Math.min(s.length(), from + SIZE)));
            }
        }

        @Override
        public String toString() {
            return "ChunkedString[" + SIZE + "]";
        }
    };
}
