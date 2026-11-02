package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 已售仓单的箱子清理器
 *
 * 国民买下仓单时，货已记入它的账本（虚拟入库），但实物还锁在箱子里。
 * 这里记录这些坐标，等【有玩家靠近】时再把箱子清空并解锁：
 *
 *     · 避免"货既在箱子里又在国民账本里"的重复计量
 *     · 也避免箱子永远锁死
 *
 * 状态文件：config/kjbb/receipt_clear_pending.txt（每行 x,y,z）
 */
@EventBusSubscriber(modid = "kjbankbridge")
public final class ReceiptClearWatcher {

    /** 玩家进入这个距离（方块）就触发清理 */
    private static final double NEAR = 24.0;

    private static final Set<String> PENDING = new LinkedHashSet<>();
    private static boolean loaded = false;
    private static int tickCounter = 0;

    private ReceiptClearWatcher() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("receipt_clear_pending.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_receipt_clear_pending.txt");
        }
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path p = file();
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (!s.isEmpty() && !s.startsWith("#")) {
                        PENDING.add(s);
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static synchronized void save() {
        try {
            StringBuilder sb = new StringBuilder("# x,y,z\n");
            for (String s : PENDING) {
                sb.append(s).append('\n');
            }
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 登记一个待清理的箱子 */
    public static synchronized void add(BlockPos pos) {
        load();
        PENDING.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
        save();
    }

    /** 这个箱子是否在待清理清单里（国民已买下它的仓单） */
    public static synchronized boolean isPending(BlockPos pos) {
        load();
        return PENDING.contains(pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    /** 移除待清理标记（货已被处理时调用） */
    public static synchronized void removePending(BlockPos pos) {
        load();
        if (PENDING.remove(pos.getX() + "," + pos.getY() + "," + pos.getZ())) {
            save();
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        load();
        if (PENDING.isEmpty()) {
            return;
        }
        if (++tickCounter < 40) {          // 每 2 秒检查一次
            return;
        }
        tickCounter = 0;
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        var it = PENDING.iterator();
        boolean dirty = false;
        while (it.hasNext()) {
            String key = it.next();
            BlockPos pos = parse(key);
            if (pos == null) {
                it.remove();
                dirty = true;
                continue;
            }
            // 没人靠近就等下次
            ServerPlayer near = null;
            for (ServerPlayer sp : players) {
                if (sp.level() != level && sp.level().dimension() != level.dimension()) {
                    continue;
                }
                if (sp.blockPosition().distSqr(pos) <= NEAR * NEAR) {
                    near = sp;
                    break;
                }
            }
            if (near == null) {
                continue;
            }
            int cleared = clearAt(level, pos);
            StoreLocks.unlock(pos);
            it.remove();
            dirty = true;
            if (cleared > 0) {
                near.displayClientMessage(Component.literal(
                        "\u00a76[\u4ed3\u5355] \u8fd9\u4e2a\u5bb9\u5668\u7684\u8d27\u5df2\u88ab\u5510\u5154\u56fd\u56fd\u6c11\u53d6\u8d70\uff0c"
                                + "\u5df2\u6e05\u7a7a " + cleared + " \u4ef6\u5e76\u89e3\u9501"), false);
                System.out.println("[TangtuEconomical] \u6e05\u7406\u5df2\u552e\u4ed3\u5355\u5bb9\u5668 " + key
                        + "\uff0c\u6e05\u7a7a " + cleared + " \u4ef6");
            } else {
                System.out.println("[TangtuEconomical] \u5df2\u89e3\u9501\u7a7a\u5bb9\u5668 " + key);
            }
        }
        if (dirty) {
            save();
        }
    }

    private static BlockPos parse(String key) {
        String[] p = key.split(",");
        if (p.length < 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(p[0].trim()),
                    Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 清空该位置的容器，返回清掉的件数 */
    private static int clearAt(ServerLevel level, BlockPos pos) {
        try {
            var be = level.getBlockEntity(pos);
            if (be instanceof Container c) {
                int total = 0;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack st = c.getItem(i);
                    if (st != null && !st.isEmpty()) {
                        total += st.getCount();
                        c.setItem(i, ItemStack.EMPTY);
                    }
                }
                c.setChanged();
                return total;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0;
    }
}
