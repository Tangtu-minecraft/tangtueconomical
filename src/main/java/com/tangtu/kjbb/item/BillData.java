package com.tangtu.kjbb.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 提单票面数据（存在物品的数据组件里）。
 *
 * storeX/Y/Z = 提货仓库坐标（全 0 = 不绑定仓库）
 */
public record BillData(String itemId, int count, String issuer, long serial, String status,
                       long expiresAt, int storeX, int storeY, int storeZ, String address) {

    public static final String STATUS_ISSUED = "ISSUED";
    public static final String STATUS_REDEEMED = "REDEEMED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    /** 出票时已经把货物打成包裹寄出 */
    public static final String STATUS_SHIPPED = "SHIPPED";

    /** 有效期：7 个游戏日（1 日 = 24000 tick） */
    public static final long LIFETIME_TICKS = 7L * 24000L;

    public static final Codec<BillData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("item").forGetter(BillData::itemId),
            Codec.INT.fieldOf("count").forGetter(BillData::count),
            Codec.STRING.fieldOf("issuer").forGetter(BillData::issuer),
            Codec.LONG.fieldOf("serial").forGetter(BillData::serial),
            Codec.STRING.fieldOf("status").forGetter(BillData::status),
            Codec.LONG.fieldOf("expires").forGetter(BillData::expiresAt),
            Codec.INT.optionalFieldOf("sx", 0).forGetter(BillData::storeX),
            Codec.INT.optionalFieldOf("sy", 0).forGetter(BillData::storeY),
            Codec.INT.optionalFieldOf("sz", 0).forGetter(BillData::storeZ),
            Codec.STRING.optionalFieldOf("addr", "").forGetter(BillData::address)
    ).apply(i, BillData::new));

    /** 手写流编码（StreamCodec.composite 最多 6 个字段，这里字段更多） */
    public static final StreamCodec<RegistryFriendlyByteBuf, BillData> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BillData decode(RegistryFriendlyByteBuf buf) {
                    String itemId = ByteBufCodecs.STRING_UTF8.decode(buf);
                    int count = ByteBufCodecs.VAR_INT.decode(buf);
                    String issuer = ByteBufCodecs.STRING_UTF8.decode(buf);
                    long serial = ByteBufCodecs.VAR_LONG.decode(buf);
                    String status = ByteBufCodecs.STRING_UTF8.decode(buf);
                    long expiresAt = ByteBufCodecs.VAR_LONG.decode(buf);
                    int sx = ByteBufCodecs.VAR_INT.decode(buf);
                    int sy = ByteBufCodecs.VAR_INT.decode(buf);
                    int sz = ByteBufCodecs.VAR_INT.decode(buf);
                    String address = ByteBufCodecs.STRING_UTF8.decode(buf);
                    return new BillData(itemId, count, issuer, serial, status, expiresAt,
                            sx, sy, sz, address);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, BillData v) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.itemId());
                    ByteBufCodecs.VAR_INT.encode(buf, v.count());
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.issuer());
                    ByteBufCodecs.VAR_LONG.encode(buf, v.serial());
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.status());
                    ByteBufCodecs.VAR_LONG.encode(buf, v.expiresAt());
                    ByteBufCodecs.VAR_INT.encode(buf, v.storeX());
                    ByteBufCodecs.VAR_INT.encode(buf, v.storeY());
                    ByteBufCodecs.VAR_INT.encode(buf, v.storeZ());
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.address() == null ? "" : v.address());
                }
            };

    public BillData withStatus(String newStatus) {
        return new BillData(itemId, count, issuer, serial, newStatus, expiresAt,
                storeX, storeY, storeZ, address);
    }

    /** 是否绑定到某个仓库（发报机） */
    public boolean hasStore() {
        return storeX != 0 || storeY != 0 || storeZ != 0;
    }
}
