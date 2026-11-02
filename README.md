# 唐兔经济系统 · TangtuEconomical

> Minecraft NeoForge 1.21.1 的服务器经济模组
> 一个「钱不会凭空产生」的封闭经济：市场定价、票据金融、远洋贸易、国民经济闭环

![版本](https://img.shields.io/badge/version-6.9.329-blue)
![MC](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.252-orange)

---

## 这是什么

唐兔经济系统试图回答一个问题：**如果服务器里的钱不能印，经济会长成什么样。**

它包含四个互相咬合的部分：

| 部分 | 作用 |
|---|---|
| **市场** | 玩家挂单、买单、成交通知、离线投递 |
| **金融** | 仓单、票据市场、K 线、股票、估值 |
| **远洋** | 航次募资、份额交易、七种风险结局、包船 |
| **国民经济** | 机器人生产消费、国库常平仓、罗马外部市场 |

**核心设计：价格 = 基准价 × 库存因子 × 预算系数**

货多就便宜、买家穷就压价，没有固定价格表。

---

## 主要特性

### 会呼吸的价格

同一件货，市场库存 256 时收 20，库存 1,024 时只收 7。

珍宝类（钻石等）波动是普通物品的两倍——**要么很贵，要么砸手里**。

### 有风险的远洋

每趟航次会经历若干段随机结局：

| 结果 | 概率 | 收益率 |
|---|---|---|
| 顺风 | 22% | +30% |
| 平安 | 23% | +27% |
| 逆风 | 17% | +16% |
| 风暴 | 18% | +4% |
| 海盗 | 13% | -6% |
| 触礁 | 7% | -17% |
| **沉没** | **8%** | **-100%** |

**长期期望 +5%，单趟可能是 +30% 也可能是归零。**

### 会干活的机器人

| 机器人 | 角色 |
|---|---|
| 唐兔国国民 | 生产、吃饭、采购、零售 |
| 经济部 | 国库：高买粮食、按量放货、危机收购 |
| 大A膏手 | 投机客：只做金融 |
| 唐兔国航运公司 | 船东：逐利装货、出海 |
| 罗马国 | 外部市场：收港货、生产、纳税 |

---

## 安装

### 服务端 / 客户端

1. 安装 **NeoForge 21.1.252**（Minecraft 1.21.1）
2. 把 `TangtuEconomical-x.x.x.jar` 放进 `mods/`
3. 启动即可（单人游戏也能用）

### 依赖

- Minecraft 1.21.1
- NeoForge 21.1.252+
- Java 21

**可选联动：** Create、Farmer's Delight、Twilight Forest 等（自动识别，缺失不影响启动）

---

## 指令

### 玩家

```
/billmenu                    打开经济界面
/billmoney                   查看余额
/billmarket                  市场列表
/billprices <物品>           查看物价
/billoffer place <价> <量>   挂单卖
/billoffer cancel <编号>     撤单
/billorder <价> <量>         挂单买
/billorders                  我的买单
/billreceipt                 我的仓单
/billreceiptvalue            仓单估值
/billkline <物品>            价格走势
/billvoyage list             所有航次
/billvoyage my               我的份额
/billvoyage buy <号> <份>    买份额
/billvoyage contribute <号>  用货物换份额
/billmail send <玩家> <量>   邮寄
/billecon                    经济总览
/billdate  /billtime         时间
```

### 管理员

```
/billbot reload              重载配置
/billpurge <物品>            清理违规物品
/billmint <玩家> <金额>      发放货币
/billaccounts sync           账户对账
```

---

## 文档

项目根目录下有 18 份设计文档：

| 文档 | 内容 |
|---|---|
| [档案-国民经济最终形态.md](档案-国民经济最终形态.md) | **规则总纲**（16 章，全部机制） |
| [档案-玩家手册-帕秋莉.md](档案-玩家手册-帕秋莉.md) | 面向玩家的手册稿（可直接转 Patchouli） |
| [档案-全功能清单.md](档案-全功能清单.md) | 功能总表 |
| [档案-指令与数据包接口.md](档案-指令与数据包接口.md) | 指令与数据包格式 |
| [档案-远洋航行系统.md](档案-远洋航行系统.md) | 航次系统细节 |
| [档案-国民经济调优.md](档案-国民经济调优.md) | 调参过程记录 |
| [交接档案-GUI制作流程.md](交接档案-GUI制作流程.md) | GUI 实现交接 |
| [设计-买卖流程思索.md](设计-买卖流程思索.md) | 交易流程设计推导 |
| [剧本-玩家买卖Ponder.md](剧本-玩家买卖Ponder.md) | Ponder 教学剧本 |

---

## 构建

```powershell
# 编译并打包
.\build.ps1

# 产物
dist\TangtuEconomical-x.x.x.jar
```

**或使用 Gradle：**

```bash
./gradlew build
```

---

## 配置

首次启动后在 `config/kjbb/` 生成：

| 文件 | 内容 |
|---|---|
| `bots.json` | **主配置**（120+ 参数） |
| `shop_prices.properties` | 基准价表 |
| `accounts.txt` | 统一账户 |
| `offers.txt` / `orders.txt` | 挂单与买单 |
| `kline.txt` | K 线（每 10 笔一根） |
| `voyage_bills.txt` | 航次记录 |
| `ticket_market.txt` | 票据市场 |

**所有配置都可以热重载：** `/billbot reload`

---

## 开发

```
src/main/java/com/tangtu/kjbb/
├── MarketBots.java        国民经济主循环
├── ShopPricing.java       价格因子
├── OfferBook.java         挂单
├── OrderBook.java         买单
├── VoyageBill.java        航次
├── RomaMarket.java        罗马市场
├── RegulatorGranary.java  国库常平仓
├── TicketMarket.java      票据
├── KlineAggregator.java   K 线聚合
└── ...
```

**统计：** 210 个源文件 → 375 个 class

---

## 许可

见 [LICENSE](LICENSE)。

---

## 致谢

- NeoForge 团队
- Create 模组（远洋贸易的灵感来源）
- 所有在测试服上跑过经济数据的玩家
