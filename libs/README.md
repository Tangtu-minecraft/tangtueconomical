# libs —— 编译期依赖（不纳入版本库）

本目录存放编译需要的第三方 mod jar。

**这些文件不进 Git**（体积大、第三方版权），但**编译前必须放进来**。

## 需要的文件

| 文件名 | 用途 | 获取方式 |
|---|---|---|
| `SC-jei-1.21.1-neoforge-*.jar` | 配方/物品查询联动 | CurseForge 搜 "JEI 1.21.1" |
| `waystones-neoforge-1.21.1-*.jar` | 传送石联动（禁售判定） | CurseForge 搜 "Waystones" |
| `ponder-neoforge-1.0.82+mc1.21.1.jar` | Create Ponder 教学 | CurseForge 搜 "Ponder" |
| `balm-neoforge-1.21.1-*.jar` | Ponder 的前置库 | CurseForge 搜 "Balm" |
| `modutilities-neoforge-*.jar` | 工具库 | CurseForge 搜 "Mod Utilities" |

## 版本对应

**Minecraft 1.21.1 / NeoForge 21.1.252**

版本号不需要完全一致，只要 API 兼容即可（大版本相同）。

## 缺失会怎样

编译时会出现 `package xxx does not exist` 或 `cannot find symbol`。

**但这几个依赖都是可选的：** 拿到 jar 之后，代码里用反射或 try-catch 包住，缺失的 mod 不会导致运行时崩溃，只是对应联动功能关闭。

## 为什么不用 Gradle 依赖

这些 mod 没有稳定的 Maven 仓库，只能用本地 jar。

如果以后找到可用的 Maven 坐标，可以改成 `implementation` 依赖，这个目录就可以删掉。
