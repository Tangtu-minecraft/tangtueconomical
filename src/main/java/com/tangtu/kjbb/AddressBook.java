package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收货地址簿：config/kjbb/addresses.txt
 * 格式：名称|x|y|z
 *
 * 提单兑付时按票面地址找到对应容器，把货物打成 Create 包裹塞进去。
 */
public final class AddressBook {

    /** 最近一次投递的来源上下文（供包裹标记用） */
    public static String lastOrigin = "";
    public static long lastOrderId = 0L;


    private static final Map<String, int[]> MAP = new LinkedHashMap<>();
    private static boolean loaded = false;

    private AddressBook() {
    }

    private static Path file() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("kjbb");
            Files.createDirectories(dir);
            return dir.resolve("addresses.txt");
        } catch (Throwable t) {
            return Path.of("kjbb_addresses.txt");
        }
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        MAP.clear();
        Path p = file();
        if (!Files.exists(p)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line == null || line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length < 4) {
                    continue;
                }
                MAP.put(f[0].trim(), new int[]{
                        Integer.parseInt(f[1].trim()),
                        Integer.parseInt(f[2].trim()),
                        Integer.parseInt(f[3].trim())});
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 读地址簿失败: " + t);
        }
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder("# 名称|x|y|z\n");
        for (var e : MAP.entrySet()) {
            int[] v = e.getValue();
            sb.append(e.getKey()).append('|').append(v[0]).append('|')
                    .append(v[1]).append('|').append(v[2]).append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 写地址簿失败: " + t);
        }
    }

    public static synchronized boolean set(String name, int x, int y, int z) {
        load();
        MAP.put(name, new int[]{x, y, z});
        save();
        return true;
    }

    /** 解除绑定（真正删掉） */
    public static synchronized boolean remove(String name) {
        load();
        boolean had = MAP.remove(name) != null;
        if (had) {
            save();
        }
        return had;
    }

    public static synchronized BlockPos get(String name) {
        load();
        int[] v = MAP.get(name);
        return v == null ? null : new BlockPos(v[0], v[1], v[2]);
    }

    /** 玩家默认地址：注册过的用自己的名字，否则用玩家名 */
    public static String addressOf(ServerPlayer sp) {
        return sp.getScoreboardName();
    }

    /** 读蛙港/邮箱的地址过滤串 */
    private static String filterOf(Object be) {
        try {
            var f = be.getClass().getField("addressFilter");
            Object v = f.get(be);
            return v instanceof String s ? s : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 把包裹放进蛙港/邮箱的库存（反射 SmartInventory.insertItem） */
    private static boolean putIntoPort(Object be, net.minecraft.world.item.ItemStack pkg) {
        try {
            // ① 先走机械动力的物流目标（锁链/列车）：包裹真的走一遍运货流程
            try {
                java.lang.reflect.Field tf = be.getClass().getField("target");
                Object portTarget = tf.get(be);
                if (portTarget != null) {
                    java.lang.reflect.Field af = be.getClass().getField("acceptsPackages");
                    boolean accepts = af.getBoolean(be);
                    if (accepts) {
                        java.lang.reflect.Method export = portTarget.getClass().getMethod("export",
                                net.minecraft.world.level.LevelAccessor.class,
                                net.minecraft.core.BlockPos.class,
                                net.minecraft.world.item.ItemStack.class,
                                boolean.class);
                        Object lvl = be.getClass().getMethod("getLevel").invoke(be);
                        Object bpos = be.getClass().getMethod("getBlockPos").invoke(be);
                        Object ok = export.invoke(portTarget, lvl, bpos, pkg, false);
                        if (ok instanceof Boolean sent && sent) {
                            System.out.println("[TangtuEconomical] 包裹已交给物流目标（锁链）");
                            return true;
                        }
                    } else {
                        System.out.println("[TangtuEconomical] 对方蛙港/邮箱没有开启收件");
                    }
                } else {
                    System.out.println("[TangtuEconomical] 对方不在机械动力物流系统中（target 为空）");
                }
            } catch (Throwable t) {
                System.out.println("[TangtuEconomical] 交给物流目标失败，退回直接塞入: " + t);
            }
            // ② 兜底：直接塞进库存
            var f = be.getClass().getField("inventory");
            Object inv = f.get(be);
            if (inv == null) {
                return false;
            }
            var slots = inv.getClass().getMethod("getSlots").invoke(inv);
            int n = slots instanceof Number num ? num.intValue() : 0;
            var insert = inv.getClass().getMethod("insertItem", int.class,
                    net.minecraft.world.item.ItemStack.class, boolean.class);
            for (int i = 0; i < n; i++) {
                Object rest = insert.invoke(inv, i, pkg, false);
                if (rest == null) {
                    return true;
                }
                if (rest instanceof net.minecraft.world.item.ItemStack st && st.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 放入蛙港/邮箱失败: " + t);
        }
        return false;
    }

    /** 蛙港 / 邮箱的位置 + 地址 */
    public record PortInfo(net.minecraft.server.level.ServerLevel level,
                           net.minecraft.core.BlockPos pos, String address) {
    }

    /**
     * 找「白色邮箱 / 蛙港」：
     * ① 地址过滤串包含 preferred（玩家名或 /billaddr 绑定的地址）→ 优先
     * ② 否则取最近的
     */
    public static PortInfo findPort(net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.core.BlockPos center, String preferred) {
        Object best = null;
        Object nearest = null;
        String bestAddr = "";
        String nearAddr = "";
        double bestDist = Double.MAX_VALUE;
        double nearDist = Double.MAX_VALUE;
        int r = 16;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    var p = center.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    var be = level.getBlockEntity(p);
                    if (be == null) {
                        continue;
                    }
                    String cls = be.getClass().getName().toLowerCase();
                    if (!cls.contains("packageport") && !cls.contains("frogport")
                            && !cls.contains("postbox")) {
                        continue;
                    }
                    double d = dx * dx + dy * dy + dz * dz;
                    String f = filterOf(be);
                    if (preferred != null && !preferred.isEmpty() && f != null
                            && f.toLowerCase().contains(preferred.toLowerCase())) {
                        if (d < bestDist) {
                            bestDist = d;
                            best = be;
                            bestAddr = f;
                        }
                    }
                    if (d < nearDist) {
                        nearDist = d;
                        nearest = be;
                        nearAddr = f;
                    }
                }
            }
        }
        Object pick = best != null ? best : nearest;
        String addr = best != null ? bestAddr : nearAddr;
        if (pick == null) {
            return null;
        }
        net.minecraft.core.BlockPos bp = com.tangtu.kjbb.TickerStock.posOfBlockEntity(pick);
        return new PortInfo(level, bp, addr == null ? "" : addr);
    }

    /** 把货物打成包裹（写这个蛙港/邮箱的地址）并直接塞进它 */
    public static boolean deliverTo(PortInfo port, java.util.List<net.minecraft.world.item.ItemStack> goods) {
        if (port == null) {
            return false;
        }
        try {
            var level = port.level();
            if (level == null) {
                return false;
            }
            var be = level.getBlockEntity(port.pos());
            if (be == null) {
            // 走到这里说明区块没加载或方块实体拿不到 → 排队，等加载后补投
            try {
                for (var st : goods) {
                    if (st == null || st.isEmpty()) {
                        continue;
                    }
                    var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(st.getItem());
                    if (rl != null) {
                        MailQueue.add(port.address(), port.address(), rl.toString(), st.getCount());
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
                return false;
            }
            net.minecraft.world.item.ItemStack pkg =
                    com.simibubi.create.content.logistics.box.PackageItem.containing(goods);
            // 包裹标记：写入发件人与订单号，鼠标悬停可见
            try {
                var cd = pkg.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                var tag = cd == null ? new net.minecraft.nbt.CompoundTag() : cd.copyTag();
                tag.putString("kjbb_origin", com.tangtu.kjbb.AddressBook.lastOrigin);
                tag.putLong("kjbb_order", com.tangtu.kjbb.AddressBook.lastOrderId);
                pkg.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(tag));
            } catch (Throwable ignored) {
                // ignore
            }
            com.simibubi.create.content.logistics.box.PackageItem.addAddress(pkg,
                    port.address() == null ? "" : port.address());
            return putIntoPort(be, pkg);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 投递到蛙港/邮箱失败: " + t);
            return false;
        }
    }

    public static synchronized String dump() {
        load();
        StringBuilder sb = new StringBuilder();
        for (var e : MAP.entrySet()) {
            int[] v = e.getValue();
            sb.append(e.getKey()).append(" → ").append(v[0]).append(", ")
                    .append(v[1]).append(", ").append(v[2]).append('\n');
        }
        return sb.length() == 0 ? "（地址簿为空）" : sb.toString();
    }

    /**
     * 把货物打成 Create 包裹并投递给「蛙港 / 邮箱」（PackagePortBlockEntity）。
     *
     * 规则：
     *   ① 优先找 addressFilter 与地址相同的蛙港/邮箱 → 直接放进它的库存
     *   ② 否则找最近的蛙港/邮箱 → 放进它的库存（由 Create 物流按地址转发）
     *   ③ 都没有 → 退回 /billaddr 绑定的普通容器
     *   ④ 仍然没有 → 返回 false
     */
    /**
     * 快速解析：如果地址已绑定且该位置仍是合法提货点 → 直接用，跳过全域扫描。
     */
    public static BlockPos fastResolve(ServerLevel level, String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }
        BlockPos bound = get(address);
        if (bound == null) {
            return null;
        }
        if (!level.isLoaded(bound)) {
            return bound;      // 未加载也先返回，让调用方决定
        }
        return isValidPort(level, bound) ? bound : null;
    }


    /** 邮寄队列专用：把一件货重新尝试投递到指定地址 */
    public static boolean resendQueued(net.minecraft.server.level.ServerLevel level,
            String address, String receiver, String itemId, int count) {
        if (level == null || itemId == null || count <= 0) {
            return false;
        }
        net.minecraft.core.BlockPos pos = fastResolve(level, address);
        if (pos == null || !level.isLoaded(pos)) {
            return false;
        }
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.tryParse(itemId));
        if (item == null) {
            return false;
        }
        java.util.List<net.minecraft.world.item.ItemStack> goods = new java.util.ArrayList<>();
        goods.add(new net.minecraft.world.item.ItemStack(item, count));
        return deliver(level, pos, address, goods);
    }

    public static boolean deliver(net.minecraft.server.level.ServerLevel level,
                                  net.minecraft.core.BlockPos center,
                                  String address,
                                  java.util.List<net.minecraft.world.item.ItemStack> goods) {
        BlockPos fast = fastResolve(level, address);
        if (fast != null) {
            try {
                var pkg0 = com.simibubi.create.content.logistics.box.PackageItem.containing(goods);
                com.simibubi.create.content.logistics.box.PackageItem.addAddress(pkg0,
                        address == null ? "" : address);
                java.util.List<net.minecraft.world.item.ItemStack> one =
                        new java.util.ArrayList<>();
                one.add(pkg0);
                if (deliverTo(new PortInfo(level, fast, address), one)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 退回全量扫描
            }
        }

        net.minecraft.world.item.ItemStack pkg;
        try {
            pkg = com.simibubi.create.content.logistics.box.PackageItem.containing(goods);
            com.simibubi.create.content.logistics.box.PackageItem.addAddress(pkg,
                    address == null ? "" : address);
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] 生成包裹失败: " + t);
            return false;
        }

        // ① / ② 蛙港或邮箱（用反射，避免引用 Create 的虚拟方块实体父类）
        Object bestMatch = null;
        Object nearest = null;
        double bestDist = Double.MAX_VALUE;
        double nearDist = Double.MAX_VALUE;
        int r = 16;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    var p = center.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    var be = level.getBlockEntity(p);
                    if (be == null) {
                        continue;
                    }
                    String cls = be.getClass().getName().toLowerCase();
                    if (!cls.contains("packageport") && !cls.contains("frogport")
                            && !cls.contains("postbox")) {
                        continue;
                    }
                    double d = dx * dx + dy * dy + dz * dz;
                    String filter = filterOf(be);
                    if (address != null && !address.isEmpty() && filter != null
                            && filter.toLowerCase().contains(address.toLowerCase())) {
                        if (d < bestDist) {
                            bestDist = d;
                            bestMatch = be;
                        }
                    }
                    if (d < nearDist) {
                        nearDist = d;
                        nearest = be;
                    }
                }
            }
        }
        Object target = bestMatch != null ? bestMatch : nearest;
        if (target != null && putIntoPort(target, pkg)) {
            return true;
        }

        // ③ /billaddr 绑定的普通容器
        load();
        net.minecraft.core.BlockPos pos = get(address);
        if (pos != null && level.isLoaded(pos)) {
            int left = TickerStock.insert(level, pos, pkg);
            if (left == 0) {
                return true;
            }
        }
        return false;
    }


    /**
     * 这个方块能不能当提货点？
     * 只允许 Create 物流的三种：蛙港(frogport) / 包裹港(packageport) / 邮箱(postbox)
     */
    public static boolean isValidPort(net.minecraft.server.level.ServerLevel level,
                                       BlockPos pos) {
        try {
            var be = level.getBlockEntity(pos);
            if (be == null) {
                return false;
            }
            String cls = be.getClass().getName().toLowerCase();
            return cls.contains("frogport") || cls.contains("packageport")
                    || cls.contains("postbox");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 列出附近所有可当提货点的方块（蛙港/包裹港/邮箱） */
    public static java.util.List<BlockPos> portsNear(net.minecraft.server.level.ServerLevel level,
                                                     BlockPos center, int radius) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    if (isValidPort(level, p)) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }
}
