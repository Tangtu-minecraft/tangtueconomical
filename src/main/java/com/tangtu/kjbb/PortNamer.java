package com.tangtu.kjbb;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 给 Create 的蛙港 / 包裹港 / 邮箱「改名」
 *
 * 它们不支持 setCustomName，但 PackagePortBlockEntity 有一个
 * addressFilter 字段（界面上填的地址），这就是它们的名字：
 *
 *     port.addressFilter = owner;
 *     port.filterChanged();
 *
 * 用反射实现，避免编译期依赖 Create 的类。
 */
public final class PortNamer {

    private PortNamer() {
    }

    /** @return 是否改名成功 */
    public static boolean rename(ServerLevel level, BlockPos pos, String owner) {
        if (level == null || pos == null || owner == null || owner.isEmpty()) {
            return false;
        }
        try {
            var be = level.getBlockEntity(pos);
            if (be == null) {
                System.out.println("[TangtuEconomical] 改名失败："
                        + pos + " 方块实体为 null（区块未加载？）");
                return false;
            }
            Field f = findField(be.getClass(), "addressFilter");
            if (f == null) {
                System.out.println("[TangtuEconomical] 改名失败："
                        + be.getClass().getName() + " 里找不到 addressFilter");
                return false;
            }
            f.setAccessible(true);
            f.set(be, owner);
            Method m = findMethod(be.getClass(), "filterChanged");
            if (m != null) {
                m.setAccessible(true);
                m.invoke(be);
            }
            try {
                be.setChanged();
            } catch (Throwable ignored) {
                // ignore
            }
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            System.out.println("[TangtuEconomical] \u90ae\u7bb1\u6539\u540d " + pos + " \u2192 " + owner);
            return true;
        } catch (Throwable t) {
            System.out.println("[TangtuEconomical] \u90ae\u7bb1\u6539\u540d\u5931\u8d25: " + t);
            return false;
        }
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return f;
                }
            }
            k = k.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
            k = k.getSuperclass();
        }
        return null;
    }
}
