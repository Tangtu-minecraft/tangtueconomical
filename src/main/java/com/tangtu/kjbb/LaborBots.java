package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 假人劳务：雇佣 / 到期解雇。
 *
 * 通过反射调用 SiliconeDolls 的 FakePlayer.createFake(...)，
 * 记录 名字|到期毫秒|主人 到 config/kjbb/labor_bots.txt。
 */
public final class LaborBots {

    /** 内存缓存：避免每 5 秒读一次磁盘 */
    private static Map<String, long[]> CACHE = null;

    private LaborBots() {
    }

    /** 雇佣；成功返回 null，失败返回错误提示 */
    public static String hire(ServerLevel level, BlockPos pos, String worker,
                              ServerPlayer owner, int minutesIn) {
        var server = level.getServer();
        if (server == null) {
            return "\u00a7c\u670d\u52a1\u5668\u4e0d\u53ef\u7528";
        }
        if (server.getPlayerList().getPlayerByName(worker) != null) {
            return "\u00a7c\u8fd9\u4e2a\u540d\u5b57\u5df2\u7ecf\u5728\u7ebf\u4e86\uff0c\u6362\u4e2a\u540d\u5b57";
        }
        int used = countActive();
        if (used >= BotConfig.get().laborMaxBots) {
            return "\u00a7c\u5168\u670d\u5047\u4eba\u5df2\u6ee1\uff08" + used + "/"
                    + BotConfig.get().laborMaxBots + "\uff09";
        }
        int mine = countByOwner(owner.getScoreboardName());
        if (mine >= BotConfig.get().laborMaxPerPlayer) {
            return "\u00a7c\u4f60\u5df2\u6709 " + mine + " \u4e2a\u5047\u4eba\uff0c\u4e0a\u9650 "
                    + BotConfig.get().laborMaxPerPlayer;
        }
        try {
            Class<?> fp = Class.forName("dev.anvilcraft.rg.sd.entity.FakePlayer");
            Method create = fp.getMethod("createFake",
                    net.minecraft.server.MinecraftServer.class, String.class,
                    Vec3.class, Vec2.class, ServerLevel.class, GameType.class, boolean.class);
            Object ok = create.invoke(null, server, worker,
                    new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
                    new Vec2(0.0F, owner.getYRot()), level, GameType.SURVIVAL, false);
            if (!(ok instanceof Boolean b) || !b) {
                return "\u00a7c\u5047\u4eba\u751f\u6210\u5931\u8d25\uff08\u672a\u5b89\u88c5 SiliconeDolls\uff1f\uff09";
            }
        } catch (Throwable t) {
            return "\u00a7c\u5047\u4eba\u6a21\u7ec4\u4e0d\u53ef\u7528\uff1a" + t.getClass().getSimpleName();
        }
        int minutes = minutesIn > 0 ? minutesIn : BotConfig.get().laborMinutes;
        Map<String, long[]> all = cache();
        all.put(worker, new long[]{System.currentTimeMillis() + minutes * msPerContractMinute()});
        setOwner(worker, owner.getScoreboardName());
        save(all);
        try {
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    /** 每 100 tick 调一次 */
    /** 合同一律用真实时间：1 分钟 = 60,000 毫秒，不受游戏加速影响 */
    private static long msPerContractMinute() {
        return 60_000L;
    }

    public static void tick(net.minecraft.server.MinecraftServer server) {
        Map<String, long[]> all = cache();
        if (all.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean dirty = false;
        var it = all.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            long until = e.getValue()[0];
            if (now < until) {
                // 头顶显示剩余时间：玩家名字由计分板队伍后缀渲染
                continue;
            }
            ServerPlayer p = server.getPlayerList().getPlayerByName(e.getKey());
            if (p != null) {
                // 先走 kill：模组的 disconnect 会触发 PERFORM_RESPAWN 自动重连
                try {
                    p.kill();
                } catch (Throwable ignored) {
                    // ignore
                }
                try {
                    if (!p.isRemoved()) {
                        p.connection.disconnect(Component.literal("\u00a7e\u5408\u540c\u5230\u671f\uff0c\u5047\u4eba\u5df2\u89e3\u96c7"));
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
                // 假人会自动重连：踢下线后还要从玩家列表移除、并卸掉实体
                try {
                    server.getPlayerList().remove(p);
                } catch (Throwable ignored) {
                    // ignore
                }
                try {
                    if (p.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                        sl.removePlayerImmediately(p,
                                net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
                // 租约先留着：下次 tick 若又上线就再踢，直到彻底离线
                continue;
            }
            it.remove();
            dirty = true;
        }
        if (dirty) {
            save(all);
        }
    }

    /** 发放劳动合同（扣钱由调用方处理） */
    public static void issueItem(ServerPlayer sp, String worker, int minutes) {
        var stack = new net.minecraft.world.item.ItemStack(
                com.tangtu.kjbb.item.KjbbItems.LABOR_CONTRACT.get(), 1);
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("kjbb_worker", worker);
        tag.putLong("kjbb_minutes", minutes);
        tag.putString("kjbb_owner", sp.getScoreboardName());
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack, t -> t.merge(tag));
        if (!sp.getInventory().add(stack)) {
            sp.drop(stack, false);
        }
    }

    /**
     * 当前单价（唐兔币/小时）—— 跟随市场价浮动。
     *
     *   单价 = 篮子铁锭数 × 铁锭当前市价
     *   例：120 × 18 = 2160/小时（铁价涨到 40 就是 4800/小时）
     */
    public static long pricePerHour() {
        var cfg = BotConfig.get();
        if (cfg.laborBasketIron <= 0) {
            return Math.max(1L, cfg.laborPrice);   // 固定价模式
        }
        long iron = 0L;
        try {
            iron = MarketPrice.price("minecraft:iron_ingot");
        } catch (Throwable ignored) {
            // ignore
        }
        if (iron <= 0L) {
            try {
                iron = ShopPrices.price("minecraft:iron_ingot");
            } catch (Throwable ignored) {
                // ignore
            }
        }
        if (iron <= 0L) {
            return Math.max(1L, cfg.laborPrice);
        }
        return Math.max(1L, cfg.laborBasketIron * iron);
    }

    /** 按分钟算总价 */
    public static long priceFor(int minutes) {
        long perHour = pricePerHour();
        return Math.max(1L, (long) Math.ceil(perHour * (double) minutes / 60.0));
    }

    /** 主人表 */
    private static final java.util.Map<String, String> OWNERS = new java.util.LinkedHashMap<>();

    public static void setOwner(String worker, String owner) {
        OWNERS.put(worker, owner);
    }

    public static String ownerOf(String worker) {
        return OWNERS.getOrDefault(worker, "");
    }

    /** 未到期租约数 */
    public static int countActive() {
        long now = ServerClock.now();
        int n = 0;
        for (long[] v : cache().values()) {
            if (v[0] > now) {
                n++;
            }
        }
        return n;
    }

    /** 某主人的未到期租约数 */
    public static int countByOwner(String owner) {
        if (owner == null) {
            return 0;
        }
        long now = ServerClock.now();
        int n = 0;
        for (var e : cache().entrySet()) {
            if (e.getValue()[0] > now && owner.equals(ownerOf(e.getKey()))) {
                n++;
            }
        }
        return n;
    }

    /** 现有假人列表文本："名字 剩N分；名字 剩N分"，空则 "无" */
    public static String listText() {
        Map<String, long[]> all = cache();
        if (all.isEmpty()) {
            return "\u65e0";
        }
        long now = ServerClock.now();
        StringBuilder sb = new StringBuilder();
        for (var e : all.entrySet()) {
            long left = Math.max(0L, (e.getValue()[0] - now + 59999L) / 60000L);
            if (sb.length() > 0) {
                sb.append("\uff1b");
            }
            sb.append(e.getKey()).append(" \u5269").append(left).append("\u5206");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------- 存档

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("labor_bots.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_labor_bots.txt");
        }
    }

    /** 取缓存（首次从磁盘加载） */
    private static Map<String, long[]> cache() {
        if (CACHE == null) {
            CACHE = load();
        }
        return CACHE;
    }

    private static Map<String, long[]> load() {
        Map<String, long[]> map = new LinkedHashMap<>();
        try {
            Path f = file();
            if (!Files.exists(f)) {
                return map;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                String[] p = s.split("\\|");
                if (p.length < 2) {
                    continue;
                }
                try {
                    map.put(p[0], new long[]{Long.parseLong(p[1])});
                    if (p.length > 2) {
                        setOwner(p[0], p[2]);
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return map;
    }

    private static void save(Map<String, long[]> map) {
        try {
            StringBuilder sb = new StringBuilder("# worker|until|owner\n");
            for (var e : map.entrySet()) {
                sb.append(e.getKey()).append('|').append(e.getValue()[0])
                        .append('|').append(ownerOf(e.getKey())).append('\n');
            }
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public static int count() {
        return load().size();
    }
}
