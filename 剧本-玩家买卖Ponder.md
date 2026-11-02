# Ponder 剧本调度 · 玩家间买卖

> 2026-11-01　对应版本 6.9.248
> 供 Ponder 动画工具使用。三个场景，每个都给出：底板、方块布局、分镜节拍、旁白文案。

---

## 通用约定

**API 风格**（沿用 `KjbbPonderScenes` 现有写法）

```java
scene.title("id", "标题");
scene.configureBasePlate(0, 0, 5);        // 底板尺寸
scene.showBasePlate();
scene.world().showSection(util.select().position(pos), Direction.DOWN);
scene.overlay().showText(ticks).text("...").pointAt(vec).placeNearTarget();
scene.idle(ticks);
scene.markAsFinished();
```

**节拍单位**：1 节拍 = 1 tick = 1/20 秒
**常用节奏**：`idle(10)` 短停顿、`idle(20)` 一句旁白、`idle(80)` 讲完一段

**三个场景共用的角色**

| 代号 | 含义 | 表现方式 |
|---|---|---|
| 卖家 | 站着的假人/玩家 | 用 `util.grid().at(x,1,z)` 定位 |
| 买家 | 另一个位置的假人 | 同上 |
| 蛙港 | Create 蛙港方块 | `select().position(pos)` |
| 锁链 | 装饰用锁链方块 | 只需显示，不做逻辑 |

---

# 场景一：面对面交易

**id**：`trade_face_to_face`
**标题**：两人站在一起，钱货两清
**底板**：5×5
**时长**：约 8 秒（160 tick）

## 方块布局

```
(2,1,2)  箱子（表示双方的货）
(1,1,2)  卖家站位
(3,1,2)  买家站位
```

## 分镜

| # | tick | 动作 | 旁白 |
|---|---|---|---|
| 1 | 0-10 | `title` + `showBasePlate` | （标题卡） |
| 2 | 10-30 | 显示箱子 | — |
| 3 | 30-110 | 文本指向箱子 | **「买卖双方站在 8 格内」** |
| 4 | 110-170 | 文本 | **「可以直接面对面交易：钱一扣，货一给」** |
| 5 | 170-230 | 文本 | **「不需要绑定提货点，也不用等物流」** |
| 6 | 230-280 | 文本 | **「距离超过 8 格就自动改走物流」** |
| 7 | 280 | `markAsFinished` | — |

## 文案定稿（直接可用）

```
「买卖双方站在 8 格内」
「可以直接面对面交易：钱一扣，货一给」
「不需要绑定提货点，也不用等物流」
「距离超过 8 格就自动改走物流」
```

## 实现骨架

```java
public static void tradeFaceToFace(SceneBuilder scene, SceneBuildingUtil util) {
    scene.title("trade_face_to_face", "两人站在一起，钱货两清");
    scene.configureBasePlate(0, 0, 5);
    scene.showBasePlate();
    scene.idle(10);

    BlockPos chest = util.grid().at(2, 1, 2);
    BlockPos seller = util.grid().at(1, 1, 2);
    BlockPos buyer  = util.grid().at(3, 1, 2);

    scene.world().showSection(util.select().position(chest), Direction.DOWN);
    scene.idle(20);

    scene.overlay().showText(80)
            .text("买卖双方站在 8 格内")
            .pointAt(util.vector().centerOf(seller))
            .placeNearTarget();
    scene.idle(90);

    scene.overlay().showText(80)
            .text("可以直接面对面交易：钱一扣，货一给")
            .pointAt(util.vector().centerOf(chest))
            .placeNearTarget();
    scene.idle(90);

    scene.overlay().showText(70)
            .text("不需要绑定提货点，也不用等物流")
            .independent(0)
            .placeNearTarget();
    scene.idle(80);

    scene.overlay().showText(70)
            .text("距离超过 8 格就自动改走物流")
            .pointAt(util.vector().centerOf(buyer))
            .placeNearTarget();
    scene.idle(80);

    scene.markAsFinished();
}
```

---

# 场景二：挂单 → 锁单 → 发货 → 确认

**id**：`trade_escrow`
**标题**：托管交易：钱先押着，货到再付
**底板**：7×5（横向流程，从左到右）
**时长**：约 12 秒（240 tick）

## 方块布局

```
(1,1,2)  卖家
(3,1,2)  箱子（货物暂存，表示"货还在卖家手上"）
(5,1,2)  买家
(5,1,1)  蛙港（买家提货点）
```

## 四个阶段（每阶段 60 tick）

### 阶段 1：挂单（只会展示，不扣货）

| tick | 动作 | 旁白 |
|---|---|---|
| 0-60 | 文本指向箱子 | **「卖家挂单：写明物品、数量、单价」** |
| | | **「货仍然留在卖家手上」** |

### 阶段 2：买家锁单（钱进托管）

| tick | 动作 | 旁白 |
|---|---|---|
| 60-120 | 文本指向买家 | **「买家锁单：钱从钱包扣走，进托管」** |
| | | **「系统生成一张待发货订单」** |

### 阶段 3：卖家发货（走物流）

| tick | 动作 | 旁白 |
|---|---|---|
| 120-180 | 箭头从箱子指向蛙港 | **「卖家发货：货打包成包裹」** |
| | | **「通过机械动力物流送到买家提货点」** |

### 阶段 4：买家确认（钱给卖家）

| tick | 动作 | 旁白 |
|---|---|---|
| 180-240 | 文本指向买家 | **「买家取到货后确认收货」** |
| | | **「托管款才付给卖家」** |

## 补充分支（可选，插在 240 之后）

**超时分支**

```
「卖家 30 分钟不发货」
「系统自动取消，钱退回买家」
```

## 文案定稿

```
「卖家挂单：写明物品、数量、单价」
「货仍然留在卖家手上」
「买家锁单：钱从钱包扣走，进托管」
「系统生成一张待发货订单」
「卖家发货：货打包成包裹」
「通过机械动力物流送到买家提货点」
「买家取到货后确认收货」
「托管款才付给卖家」
（分支）
「卖家 30 分钟不发货」
「系统自动取消，钱退回买家」
```

## 实现骨架

```java
public static void tradeEscrow(SceneBuilder scene, SceneBuildingUtil util) {
    scene.title("trade_escrow", "托管交易：钱先押着，货到再付");
    scene.configureBasePlate(0, 0, 7);
    scene.showBasePlate();
    scene.idle(10);

    BlockPos seller = util.grid().at(1, 1, 2);
    BlockPos chest  = util.grid().at(3, 1, 2);
    BlockPos buyer  = util.grid().at(5, 1, 2);
    BlockPos port   = util.grid().at(5, 1, 1);

    scene.world().showSection(util.select().fromTo(1, 0, 1, 5, 2, 3), Direction.DOWN);
    scene.idle(20);

    // ① 挂单
    scene.overlay().showText(80)
            .text("卖家挂单：写明物品、数量、单价")
            .pointAt(util.vector().centerOf(seller))
            .placeNearTarget();
    scene.idle(60);
    scene.overlay().showText(60)
            .text("货仍然留在卖家手上")
            .pointAt(util.vector().centerOf(chest))
            .placeNearTarget();
    scene.idle(60);

    // ② 锁单
    scene.overlay().showText(80)
            .text("买家锁单：钱从钱包扣走，进托管")
            .pointAt(util.vector().centerOf(buyer))
            .placeNearTarget();
    scene.idle(60);
    scene.overlay().showText(60)
            .text("系统生成一张待发货订单")
            .independent(0).placeNearTarget();
    scene.idle(60);

    // ③ 发货
    scene.overlay().showText(80)
            .text("卖家发货：货打包成包裹")
            .pointAt(util.vector().centerOf(chest))
            .placeNearTarget();
    scene.idle(60);
    scene.overlay().showText(80)
            .text("通过机械动力物流送到买家提货点")
            .pointAt(util.vector().centerOf(port))
            .placeNearTarget();
    scene.idle(60);

    // ④ 确认
    scene.overlay().showText(80)
            .text("买家取到货后确认收货")
            .pointAt(util.vector().centerOf(buyer))
            .placeNearTarget();
    scene.idle(60);
    scene.overlay().showText(70)
            .text("托管款才付给卖家")
            .independent(0).placeNearTarget();
    scene.idle(80);

    scene.markAsFinished();
}
```

---

# 场景三：包裹走锁链

**id**：`trade_package_route`
**标题**：包裹怎么从卖家走到蛙港
**底板**：9×5
**时长**：约 10 秒（200 tick）

## 方块布局

```
(1,1,2)  打包机
(2,2,2)  包裹（动画中沿锁链移动）
(3..6,2,2) 锁链（装饰）
(7,1,2)  蛙港
```

## 分镜

| # | tick | 动作 | 旁白 |
|---|---|---|---|
| 1 | 0-30 | 显示打包机 | **「货先被打包成 Create 包裹」** |
| 2 | 30-80 | 文本指向打包机 | **「包裹上写着收件地址」** |
| 3 | 80-140 | 显示锁链 + 包裹移动 | **「包裹交给物流目标，沿锁链运输」** |
| 4 | 140-190 | 文本指向蛙港 | **「到达蛙港后，包裹进蛙港的库存」** |
| 5 | 190-240 | 文本 | **「收件人打开蛙港就能取走」** |
| 6 | 240-290 | 文本（分支） | **「如果提货点所在区块没加载，包裹会排队等」** |

## 文案定稿

```
「货先被打包成 Create 包裹」
「包裹上写着收件地址」
「包裹交给物流目标，沿锁链运输」
「到达蛙港后，包裹进蛙港的库存」
「收件人打开蛙港就能取走」
（分支）
「如果提货点所在区块没加载，包裹会排队等」
```

## 实现骨架

```java
public static void tradePackageRoute(SceneBuilder scene, SceneBuildingUtil util) {
    scene.title("trade_package_route", "包裹怎么从卖家走到蛙港");
    scene.configureBasePlate(0, 0, 9);
    scene.showBasePlate();
    scene.idle(10);

    BlockPos packager = util.grid().at(1, 1, 2);
    BlockPos port     = util.grid().at(7, 1, 2);

    scene.world().showSection(util.select().fromTo(1, 0, 1, 7, 3, 3), Direction.DOWN);
    scene.idle(30);

    scene.overlay().showText(70)
            .text("货先被打包成 Create 包裹")
            .pointAt(util.vector().centerOf(packager))
            .placeNearTarget();
    scene.idle(50);
    scene.overlay().showText(60)
            .text("包裹上写着收件地址")
            .pointAt(util.vector().centerOf(packager))
            .placeNearTarget();
    scene.idle(50);

    scene.overlay().showText(70)
            .text("包裹交给物流目标，沿锁链运输")
            .pointAt(util.vector().centerOf(util.grid().at(4, 2, 2)))
            .placeNearTarget();
    scene.idle(60);

    scene.overlay().showText(70)
            .text("到达蛙港后，包裹进蛙港的库存")
            .pointAt(util.vector().centerOf(port))
            .placeNearTarget();
    scene.idle(50);
    scene.overlay().showText(60)
            .text("收件人打开蛙港就能取走")
            .pointAt(util.vector().centerOf(port))
            .placeNearTarget();
    scene.idle(60);

    scene.overlay().showText(80)
            .text("如果提货点所在区块没加载，包裹会排队等")
            .independent(0).placeNearTarget();
    scene.idle(80);

    scene.markAsFinished();
}
```

---

## 场景注册（待接）

三个场景要挂到 Ponder 索引里。现有的三个场景（`blank_form` / `bill_of_lading` / 劳动合同）应该已经有注册入口，
新增三个 id：

```
trade_face_to_face
trade_escrow
trade_package_route
```

**注册位置**：找现有 `PonderSceneRegistrationHelper` 或 `KjbbPonderScenes` 旁边的注册类。

---

## 分镜总表（可直接给美术/动画用）

| 场景 | 时长 | 旁白条数 | 关键动作 |
|---|---|---|---|
| trade_face_to_face | 8 秒 | 4 | 两人靠近、钱货交换 |
| trade_escrow | 12 秒 | 8（+2 分支） | 挂单→锁单→发货→确认 |
| trade_package_route | 10 秒 | 5（+1 分支） | 打包→锁链→蛙港 |

---

## 文案用词约定

写 Ponder 旁白时遵守的三条：

1. **不超过 20 字** —— 太长显示不全
2. **动词开头** —— "卖家挂单"、"买家锁单"，主语在前
3. **不写实现细节** —— 说"委托物流"，不说"调用 export"

---

## 备注：为什么用这三个场景

**场景一**是最短路径 —— 让玩家知道**可以不折腾物流**。

**场景二是完整流程** —— 让玩家理解**钱为什么先压着**。

**场景三解释"货去哪了"** —— 这是玩家最容易困惑的地方（挂单后看不见货）。

**三个场景递进：直接交易 → 托管交易 → 物流原理。**
