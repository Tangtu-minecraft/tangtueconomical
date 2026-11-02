# 唐兔经济系统 · 指令与 GUI 对照档案

> 对应版本：6.9.272
> 用途：查「某个功能有没有界面 / 某个按钮发的是哪条指令 / 哪些功能只能敲指令」。
> 写作帕秋莉手册请用同目录的 `帕秋莉手册-写作稿.md`（那是给玩家看的成稿）。

---

## 一、玩家从哪里进界面

| 入口 | 怎么触发 | 打开什么 |
|---|---|---|
| **服务中心** | `/billmenu`，或空表单/菜单类物品右键 | `FormCenterScreen`（两页，共 11 个入口） |
| **市场** | 服务中心 → 市场 | `MarketScreen`（三个页签） |
| **我的订单** | 服务中心 → 我的订单 | `TxCenterScreen` 的「托管订单」页 |
| **票据市场** | 服务中心 → 票据市场 | `TicketMarketScreen`（票据 / 股票两页签） |
| **航运中心** | 服务中心 → 航运中心 | `ShippingScreen`（五个页签） |
| **交易中心** | 服务中心 → 交易中心 | `TxCenterScreen`（三页签，默认「我的挂单」） |
| **唐兔银行** | `/billbank` | `BankScreen` |
| **劳动合同** | `/billcontract` | `LaborShopScreen` |
| **市场行情** | `/billmarket` | `MarketScreen`（等价于服务中心 → 市场） |
| **股票票面** | 主手/背包里**右键股票**（`kjbankbridge:stock`） | `StockScreen` |
| **提单详情** | 右键提单 | `PaperScreen` |
| **仓单详情** | 右键仓单 | `ReceiptScreen` / `StoreFormScreen` |
| **仓储发报机** | 右键方块 | `TickerBillNetwork` 打开的提单界面 |

> 服务中心第 1 页：市场 / 我的订单 / 国民状况 / 票据市场 / 绑定提货 / 国民经济（管理）
> 服务中心第 2 页：航运中心 / 交易中心 / 期货（预留）/ 期权（预留）/ 拍卖行（预留）

---

## 二、GUI 界面清单（28 个界面）

| 界面类 | 标题 | 打开方式 | 主要内容 |
|---|---|---|---|
| `FormCenterScreen` | 服务中心 | `/billmenu` | 功能索引，两页 |
| `MainMenuScreen` | 唐兔经济系统 | 旧入口（保留） | 兼容用主菜单 |
| `MarketScreen` | 市场 | 服务中心 → 市场 / `/billmarket` | 三页签：市场 / 投递 / 交易所 |
| `MarketOrdersScreen` | 市场活跃订单 | 市场页 →「查看全部活跃订单」 | 全场买卖挂单，点行看详情 |
| `MarketOfferScreen` | 卖单详情 | 活跃订单里点**卖**单 | 填数量 →「买下」 |
| `OrderDetailScreen` | 订单详情 | 活跃订单里点**买**单 | 接单/查看需求 |
| `MarketItemScreen` / `BillOrderScreen` | 下单 | 表格区块交互 | 挂买单 |
| `TxCenterScreen` | 交易中心 | 服务中心 → 交易中心 | 我的挂单 / 托管订单 / 投递记录 |
| `TicketMarketScreen` | 票据市场 | 服务中心 → 票据市场 | 票据（在售 + 挂卖）/ 股票（份额挂单） |
| `TicketDetailScreen` | 票据详情 | 票据市场点一行 | 票面与取货信息 |
| `TicketSellScreen` | 挂卖票据 | 背包里点票据 / 主手挂卖 | 填单价挂卖 |
| `PaperScreen` | 票据 | 右键提单 | 提单/股票票面 |
| `ReceiptScreen` / `StoreFormScreen` | 仓单 | 右键仓单 / 容器 | 仓单条目与绑定 |
| `ShippingScreen` | 航运中心 | 服务中心 → 航运中心 | 在招 / 航行 / 历史 / 目的地 / 包船 |
| `VoyageShipScreen` | 船只详情 | 航运中心「航行」点一行 | 航次情况叙述、持有人 |
| `ContributeScreen` | 出货换份额 | 航运中心「在招」→ 出货 | 背包选货换份额 |
| `InvestScreen` | 出资换份额 | 航运中心「在招」→ 出钱 | 填份数算出资金额 |
| `StockScreen` | 股票 | 右键股票 | 折现 / 提货 / 售出股票 |
| `StockListScreen` | 上架股票 | 股票界面 →「售出股票」 | 份数 + 单价 + 合计 |
| `BankScreen` | 唐兔银行 | `/billbank` | 存取、硬币、保险柜 |
| `LaborShopScreen` | 劳动合同 | `/billcontract` | 雇假人，按时长计价 |
| `ContractScreen` | 劳动合同 | 点合同 | 单个合同详情 |
| `CitizenReportScreen` | 唐兔国国民 统计报表 | `/billcitizen report` | 人口/城镇 |
| `StatsScreen` | 唐兔国统计 | 已从服务中心移除 | 旧统计页 |
| `PanelScreen` | 交易面板 | 方块交互 | 面板基类 |
| `ItemPickerScreen` | 选择物品 | 内部调用 | 物品筛选 |

---

## 三、指令 → 界面 对照（按业务）

### 1. 市场与挂单

| 指令 | 等价界面操作 | 后端 |
|---|---|---|
| `/billmarket` | 服务中心 → 市场 | 打开 `MarketScreen` |
| `/billmarket buy <挂单号> <数量>` | 市场 →「查看全部活跃订单」→ 点卖单 →「买下」 | `OfferBook.buy()` |
| `/billoffer mine` | 交易中心 →「我的挂单」 | `OfferBook.of()` 过滤 OPEN |
| `/billoffer sell <数量> <单价>` | 市场 → 交易所 → 数量/单价 →「挂单」（**GUI 走仓库，指令走主手背包**） | `OfferBook.add()` |
| `/billoffer list <物品> <数量> <单价>` | 同上（指令可指定任意物品） | `OfferBook.add()` |
| `/billoffer cancel <挂单号>` | 交易中心 →「我的挂单」→「撤单」 | `OfferBook.setStatus(CANCELLED)` + 退货 |
| `/billoffer buy <挂单号> <数量>` | 同 `/billmarket buy` | `OfferBook.buy()` |
| （GUI 按钮）市场 → 数量 →「买　入」 | **不是买挂单**，是按卖一价**挂买单** | `OrderBook.add()` |

### 2. 托管订单（锁单交易）

| 指令 | 等价界面操作 | 说明 |
|---|---|---|
| `/billtrade` | 交易中心 →「托管订单」 | 列我的托管单 |
| `/billtrade lock <挂单号> <数量>` | **仅指令** | 锁单，钱先进托管 |
| `/billtrade ship <订单号>` | 托管订单 → 选中 →「发货」 | 卖家标记发货 |
| `/billtrade confirm <订单号>` | 托管订单 → 选中 →「确认收货」 | 买家确认，钱付给卖家 |
| `/billmail` | 市场 →「投递」/ 交易中心 →「投递记录」 | 最近 15 条投递记录 |

### 3. 票据

| 指令 | 等价界面操作 |
|---|---|
| `/ticket list <单价>` | 票据市场 → 票据页 → 填单价 →「挂卖」（货=主手票据） |
| `/ticket mine` | 票据市场 → 票据页（我的在售） |
| `/ticket cancel <票据号>` | 票据市场 → 票据页 →「撤单」 |
| `/ticket buy <票据号>` | 票据市场 → 票据页 → 点一行 → 购买 |
| `/ticket split <票据号> <数量>` | **仅指令**（拆票） |
| `/ticket listslot` | 背包里点票据 → 挂卖（GUI 版入口） |

### 4. 航运与股票

| 指令 | 等价界面操作 |
|---|---|
| `/billvoyage list` | 航运中心 →「在招」 |
| `/billvoyage invest <份数>` | 航运中心「在招」→「出钱换份额」→ 填份数 |
| `/billvoyage cargo <物品> <数量>` | 航运中心「在招」→「出货换份额」→ 背包选货 |
| `/billvoyage my` | 航运中心 →「航行」（我的持仓在船只详情里） |
| `/billvoyage stock <航次号>` | 航运中心「航行」点一行 → 船只详情 |
| `/billvoyage redeem cash` | 股票 →「折现」 |
| `/billvoyage redeem coin` | 股票 →「提货」 |
| `/billvoyage stall <单价> <份数>` | 股票 →「售出股票」→ 填份数/单价 →「上架」 |
| `/billvoyage stabuy <挂单号>` | 票据市场 →「股票」页签 →「买下」 |
| `/billvoyage stacancel <挂单号>` | 票据市场 →「股票」页签 →「撤单」 |
| `/billvoyage stalist` | 票据市场 →「股票」页签（列表） |
| `/billvoyage issue` / `depart` / `sail` / `force` / `settle` | **仅指令**（发行人/管理） |
| `/billvoyage charter` / `unlist` / `redeem` / `take` / `prod` / `roll` | 部分仅指令（包船页目前置灰） |
| `/billroma quote` / `sell` / `want` / `orders` / `accept` | 航运中心「目的地」页（罗马订单可撤单） |
| `/billroma book` / `fx` / `stock` / `cancel` | **仅指令** |

### 5. 银行、货币、提货点

| 指令 | 等价界面操作 |
|---|---|
| `/billbank` | 直接开银行界面 |
| `/billcoins deposit` / `cash` / `safe` / `togear` / `toold` / `combo` | 银行界面按钮 |
| `/billkey bindhere` | 服务中心 →「绑定提货」 |
| `/billkey info` / `unbind` | **仅指令**（查/解绑钥匙） |
| `/billport bind <玩家> <地址>` | **仅指令**（OP） |
| `/billport mine` / `list` / `claim` | **仅指令** |
| `/billaddr` | **不存在**（文档里别写） |

### 6. 国民与管理

| 指令 | 等价界面操作 | 权限 |
|---|---|---|
| `/billcitizen status` | 服务中心 →「国民状况」 | 玩家 |
| `/billcitizen public` | 服务中心 →「国民状况」（公开数据） | 玩家 |
| `/billcitizen report` | `CitizenReportScreen` | 玩家 |
| `/billcitizen gui` | 服务中心按钮内部用 | — |
| `/billcitizen feed` / `setmoney` / `debug` / `retail` | **仅指令** | OP |
| （服务中心 → 国民经济（管理）） | 发 `/billcitizen report` 之类看报表 | OP |
| `/billbot config` / `pnl` / `reload` / `reset` / `setmoney` | **仅指令** | OP |
| `/billecon baseline` / `save` | **仅指令** | OP |
| `/billprices reload` / `/billbearer on|off` / `/billmint coin|money` | **仅指令** | OP |
| `/billpurge` / `/billmigrate` / `/billflush` / `/billperf reset` / `/billgoods` / `/billtime` / `/billdate` | **仅指令** | OP |
| `/billkline export` | **仅指令** | OP |
| `/billorders` / `/billorder place|accept|ship|shipnext|take|pending|all` / `/billsupply <id>` | 部分有界面（订单详情「接单」= `/billorder accept`） | 玩家/OP |
| `/billvault ship|shipnext|take|accept|pending` | **仅指令** | 玩家 |
| `/billreceipt buy|list|locks|shop|unlock|unlockall|void` | 仓单界面 + 指令混合 | 玩家 |

---

## 四、反向表：界面按钮发的是哪条指令

| 界面 | 按钮 | 发出的指令 |
|---|---|---|
| 交易中心 → 我的挂单 | 撤单 | `/billoffer cancel <id>` |
| 交易中心 → 托管订单 | 发货 | `/billtrade ship <id>` |
| 交易中心 → 托管订单 | 确认收货 | `/billtrade confirm <id>` |
| 票据市场 → 票据 | 挂卖 | `/ticket list <单价>` |
| 票据市场 → 股票 | 买下 | `/billvoyage stabuy <id>` |
| 票据市场 → 股票 | 撤单 | `/billvoyage stacancel <id>` |
| 航运中心 → 在招 | 出货换份额 | `ContributeScreen` → `/billvoyage cargo <物品> <数量>` |
| 航运中心 → 在招 | 出钱换份额 | `InvestScreen` → `/billvoyage invest <份数>`（或 `/billvoyage buy` 全买） |
| 航运中心 → 目的地 | 罗马订单撤单 | `/billroma cancel <id>` |
| 股票 | 折现 | `/billvoyage redeem cash` |
| 股票 | 提货 | `/billvoyage redeem coin` |
| 股票 → 售出股票 | 上架 | `/billvoyage stall <单价> <份数>` |
| 服务中心 → 绑定提货 | 绑定 | `/billkey bindhere` |
| 服务中心 → 国民状况 | 刷新 | `/billcitizen public` |
| 市场 → 买入 | 买入（挂买单） | 网络包 `BuyMarket`（非指令） |
| 市场 → 交易所 | 挂单 | 网络包 `PlaceOffer`（非指令） |
| 卖单详情 | 买下 | `/billmarket buy <挂单号> <数量>` |
| 订单详情 | 接单 | `/billorder accept <订单号>` |

---

## 五、只有指令、没有界面的功能

写手册时要单独说明「这条要敲指令」，别让玩家在界面里找。

1. **托管下单**（`/billtrade lock`）：锁单入口只有指令；
2. **拆票**（`/ticket split`）；
3. **航次发行与结算**：`/billvoyage issue`、`depart`、`sail`、`force`、`settle`、`roll`；
4. **包船**：`/billvoyage charter`、`sail`、`abandon`（界面已置灰「暂未开放」）；
5. **罗马批发**：`/billroma book`、`fx`、`stock`、`quote`（部分仅指令）；
6. **提货点管理**：`/billport bind|default|mine|list|claim|unbind|sync`，`/billkey info|unbind`；
7. **保险库**：`/billvault ...`；
8. **仓单高级操作**：`/billreceipt locks|unlockall|void|shop`；
9. **所有管理/调试**：`/billbot`、`/billecon`、`/billprices`、`/billbearer`、`/billmint`、`/billpurge`、`/billmigrate`、`/billflush`、`/billperf`、`/billgoods`、`/billtime`、`/billdate`、`/billkline export`。

---

## 六、几个容易写错的点

1. **市场页的「买　入」不是买别人的挂单**：它按卖一价挂一张买单（PENDING），等别人供货；买别人挂单要走「活跃订单 → 点卖单 → 买下」或 `/billmarket buy`。
2. **「提货」按钮发的是 `/billvoyage redeem coin`**，不是 `redeem goods`。
3. **挂单取货来源不同**：GUI 只能从「附近 32 格的仓储发报机仓库」取；指令 `sell`/`list` 先背包后仓库（64 格）。
4. **投递方式只有两种文案**：`物流中`（已交给物流系统，还在运输）/ `排队`（提货点未加载，加载后自动投递）。
5. **提货点绑定不存在 `/billaddr`**，只有 `/billkey bindhere` 和（OP）`/billport bind`。
6. **服务中心第 2 页的期货 / 期权 / 拍卖行是预留位**，点进去只会提示未开放。
