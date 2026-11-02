package com.tangtu.kjbb;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 安全落盘：先写临时文件，再原子改名覆盖。
 *
 * 目的：直接 Files.writeString 覆盖目标文件时，如果进程在写入中途被杀（关服、崩溃、OOM），
 * 目标文件会变成半截内容，整个钱包/订单/账本全部报废。改成
 * 「写 .tmp → ATOMIC_MOVE 覆盖」之后，要么是新内容、要么是旧内容，不会出现半截文件。
 */
public final class SafeFile {

    private SafeFile() {
    }

    /** 原子写入；失败时退回普通写入，并打印一次日志 */
    public static void write(Path target, String content) {
        if (target == null) {
            return;
        }
        String data = content == null ? "" : content;
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
        } catch (Throwable ignored) {
            // 目录已存在或无权限，交给后续写入报错
        }
        try {
            Files.writeString(tmp, data, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicFail) {
                // 某些文件系统（网络盘、部分 Windows 配置）不支持原子改名
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                Files.writeString(target, data, StandardCharsets.UTF_8);
            } catch (Throwable t2) {
                System.out.println("[TangtuEconomical] 写文件失败 " + target + ": " + t2);
            }
        }
    }
}
