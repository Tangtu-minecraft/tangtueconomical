# 唐兔 GUI 制作流程 交接档案

> 用途：接手「**不进游戏改 GUI**」这套流程的唯一入口文档。
> 目标读者：下一个会话的 AI / 自己。
> 最后更新：2026-10-31　对应 mod 版本：**6.9.183**

---

## 一、一页速览

| 项目 | 内容 |
|---|---|
| 要解决的问题 | 改一个界面坐标就要开游戏看效果，迭代太慢 |
| 解决办法 | 无头跑 `Screen.init()/render()` 录制绘制调用 → 用真实贴图重画 → 自动审计是否出框 |
| 工具位置 | `U:\tangtu经济系统\tools\gui-view\` |
| 入口 | 双击 `GUI预览.bat`，或跑 `build_and_dump.ps1` |
| 产出 | `out\index.html`（所有界面预览 + 问题清单）、`out\<Screen>.png` |
| 审计脚本 | `audit_layout.py`（文字/控件是否出纸面）、`paper_box.py`（从贴图量内容框） |
| 不需要 | 启动 Minecraft、OpenGL、联网 |
| 需要 | JDK 21、游戏 `libraries`、本机 Python（带 Pillow） |

**一分钟上手：**

```powershell
cd U:\tangtu经济系统\tools\gui-view
.\build_and_dump.ps1 -Only VoyageScreen          # 只录一个界面，几秒钟
.\build_and_dump.ps1 -Width 854 -Height 480 -GuiScale 2   # 指定窗口与缩放
```

---

## 二、工具链

```
U:\tangtu经济系统\tools\gui-view\
├─ GUI预览.bat                   双击入口（纯 ASCII，cmd 按 ANSI 读）
├─ build_and_dump.ps1            组 classpath → 编译 → 录制 → 渲染 → 打开索引
├─ src\GuiDump.java              无头录制器（核心，约 600 行）
├─ shim\                         预览专用桩，绝不能打进 mod 的 jar
│   ├─ net\neoforged\neoforge\network\PacketDistributor.java
│   ├─ net\minecraft\client\renderer\PanoramaRenderer.java
│   └─ net\minecraft\client\renderer\GameRenderer.java
├─ render_gui.py                  录制 JSON → PNG + index.html
├─ audit_layout.py                越界审计（文字/控件 vs 纸面）
├─ paper_box.py                   从面板贴图量出纸面可用区
├─ compare_sizes.py               多分辨率对比表
├─ dump-<宽>\                     各分辨率的录制结果
└─ out-<宽>\                      PNG 与索引页
```

`build_and_dump.ps1` 参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `-Width` / `-Height` | 854 / 480 | 游戏窗口像素 |
| `-GuiScale` | 2 | GUI 缩放，逻辑分辨率 = 窗口 ÷ 缩放 |
| `-PaintScale` | 3 | 出图放大倍数 |
| `-Only` | 全部 | 只处理指定界面类名 |
| `-NoOpen` | — | 不自动打开浏览器 |

---

## 三、核心方法（三个关键点）

### 1. 无头录制：真跑代码，不解析源码

不猜坐标，而是把 `Screen` 的 `init()/render()` 在无 GL 环境里跑一遍，把每次
`fill / blit / drawString / renderItem / 控件添加` 的参数记下来。实现要点
（全在 `GuiDump.java`）：

| 障碍 | 做法 |
|---|---|
| `GuiGraphics` 构造要 Minecraft 实例 | `Unsafe.allocateInstance` 绕过构造，只覆写绘制方法 |
| `Screen` 要 `width/height/font` | 反射塞进去；`font` 用假 `Font` 子类（ASCII 6px、中文 9px） |
| `FMLEnvironment.dist` 是 static final | `Unsafe.putObject(staticFieldBase...)` 写 `Dist.CLIENT` |
| 物品注册表未初始化 | `SharedConstants.setVersion(DetectedVersion.BUILT_IN)` + `Bootstrap.bootStrap()` |
| `init()` 里的发包直接 NPE | classpath 最前面放空 `PacketDistributor` 桩 |
| `super.render()` → 全景 → 背景模糊一路 NPE | `PanoramaRenderer` / `GameRenderer` 也顶掉；`Screen.minecraft` 塞空壳 |
| 控件真渲染会碰 `RenderSystem` | 控件只取 `getX/getY/getWidth/getHeight` + 标题，不渲染 |

> 注意：`-cp` 里必须把 `shim` 和 `GuiDump` 目录放在**最前面**，否则顶不掉原版类。

### 2. 纸面内容框：从贴图量，不信任常量

羊皮纸纹理四周有木框、卷轴、铆钉，代码里写的内容框往往比纸面还宽，文字就压到边框上。
`paper_box.py` 直接扫贴图，取浅米色纸面的包围盒：

| 贴图 | 面板尺寸 | 纸面可用区（面板内坐标） |
|---|---|---|
| `form_sheet.png` | 320×291 | **43, 45 – 283, 246** |
| `trade_sheet.png` | 320×320 | **29, 38 – 293, 284** |
| `paper_pixel4x.png` | 320×302 | **41, 35 – 287, 263** |

代码里原来的常量是 `IN_L=30, IN_T=40, IN_R=290, IN_B=248`——**左边就比纸面宽 13px**，
这就是「所有文字都压框」的根因。已按实测值改过 10 个界面。

### 3. 审计 + 预览：闭环

```powershell
python audit_layout.py --dump dump-427        # 谁出框、出多少 px（427×240 那一档）
python audit_layout.py --dump dump-960        # 大字档（960×540，k=1）
python render_gui.py --dump dump-427 --out out-427 --scale 3   # 看图
```

> `build_and_dump.ps1` 每次都写到 `dump\` 与 `out\`；要留档就自己复制成 `dump-<宽>\`、`out-<宽>\`
> （仓库里已有 320 / 427 / 640 / 960 四档）。`index.html` 只报面板级问题，文字级越界看 `audit_layout.py`。

`audit_layout.py` 的判定：文字宽度按「ASCII 6 / 中文 9」估算；越界超过 1px 就报；
面板用最大的那块 `blit` 认定；内容框优先用纸面实测值，量不出来才退回源码常量。

---

## 四、改一个界面位置的完整流程

1. **看现状**：`.\build_and_dump.ps1 -Only XxxScreen -NoOpen`，打开 `out\XxxScreen.png`；
2. **定位代码**：`grep` 该界面里的坐标表达式，注意三类陷阱：
   - 同一个 y 常出现在**绘制、悬浮判定、点击判定**三处，改一处会错位；
   - 有的界面 `IN_L` 只有左右没有上下，改动前后要确认兜底值；
   - 底部按钮常是 `top + IN_B - 8` + 高度 15，**底边会越过 IN_B 7px**；
3. **改源码**：优先改常量（`IN_*`、`top + N`），少改单点表达式；
4. **重新编译**：`powershell -NoProfile -ExecutionPolicy Bypass -File
   C:\Users\Administrator\Documents\deepseek-harness\minecraft-mod\kjbankbridge\build\verify-build.ps1`，
   然后把 `build\verify-classes\*` 覆盖到 `build\classes`（预览读的是后者）；
5. **复看**：重跑第 1 步，对照 `audit_layout.py` 的数字，0 越界再交付。

> 预览图上的坐标就是 `dump-<宽>\*.json` 里的值，可以直接用 grep/ConvertFrom-Json 查。

---

## 五、自适应缩放（UiScale）

固定 320×291 / 320×320 的面板，逻辑分辨率一小就会被上下切掉（854×480 窗口 + GUI 缩放 2 =
427×240；640×480 + 缩放 2 = 320×240）。`com.tangtu.kjbb.client.UiScale` 负责等比缩放：

```java
// 宽高一起算：320 宽的面板在窄屏上也会溢出，只按高度算不够
float k = UiScale.factor(this.width, this.height, W, H, 4);   // 放得下返回 1.0；下限 0.55
if (k >= 1.0f) {
    super.renderBackground(g, mouseX, mouseY, partialTick);   // 1:1 时保留原版背景
    super.render(g, mouseX, mouseY, partialTick);
    return;
}
g.fill(0, 0, this.width, this.height, 0x70000000);           // 遮罩必须画在缩放之前
UiScale.push(g, left, top, W, H, k);                          // 围绕面板中心压入
try {
    super.render(g, (int) UiScale.mapX(mouseX, left, W, k),
                    (int) UiScale.mapY(mouseY, top, H, k), partialTick);
} finally { UiScale.pop(g); }
```

**四个要点：**

1. **遮罩要画在压缩放之前**（屏幕坐标系），否则那层 0x70000000 会跟着面板一起缩，四周露出世界；
2. `renderBackground()` 覆写成空实现 —— `Screen.render()` 内部会**再调一次** `renderBackground`，
   不覆写就会在缩放坐标系里又画一层背景（铺开时踩过）；
3. **输入方法入口必须换算**：`mouseClicked` 入口用 `UiScale.mapX/mapY` 把鼠标换回面板坐标系，否则
   缩放后点不中。以后若加 `mouseDragged / mouseReleased / mouseScrolled`，同一入口也要换
   （目前全 client 目录没有这些覆写，`EditBox` 拖动选区暂无影响）；
4. **面板之外还有内容时，缩放要按整块算**：`TicketMarketScreen` 纸张下面还挂背包和「挂卖」按钮，
   所以它用 `BLOCK_H = H + 8 + 3*18 + 6` 参与 `factor/push/mapY`，并把 `top` 从
   `Math.max(4, (height-totalH)/2)` 改成 `(height - BLOCK_H) / 2` —— 否则纸面缩进去了，页脚还吊在屏幕外。

**已铺开（18 个）**：`Bank / Contract / FormCenter / LaborShop / MarketItem / MarketOffer / MarketOrders /
MarketScreen / OrderDetail / Receipt / Stock / StoreForm / TicketDetail / TicketMarket / TicketSell /
CitizenReport / Voyage / VoyageDetail`。
`ItemPicker / MainMenu / PanelScreen / PaperScreen` 没有固定面板（`PanelScreen`、`PaperScreen` 自带
`s()` / `gs` 缩放），不需要铺。

**验证过**：854×480（427×240）下 18 个界面全部面板完整居中、**0 面板级越界**（其中 5 个界面的面板是
`fill` 画的、审计量不到纸面，另说）；960×540 与 640×360（k=1，走原路径）同样 0；320×240 只剩
MarketScreen 的 JEI 侧栏出右边界（结构问题，见第六节）。

---

## 六、进度清单

### 已完成（6.9.198）

- 无头录制 + 渲染 + 审计工具链全套可用，**25 个界面类 / 29 张图**（航运中心 5 个页签各出一张）都能出图；
- 内容框按纸面实测值修正：`FormCenter / OrderDetail / Receipt / StoreForm / MarketOffer /
  MarketOrders / TicketDetail / Stock / Voyage / VoyageDetail`（6.9.114）；
- 标题、底栏、按钮横向收进纸面：`Bank / LaborShop / Contract / Stock / VoyageDetail /
  Voyage / Receipt / StoreForm`（6.9.114）；
- 修掉一个真 bug：`ReceiptScreen` 有一行漏了 `top +`，文字被画到面板上方 61px（6.9.114）；
- **`UiScale` 铺到 16 个固定面板界面**（连 Voyage 系共 18 个，6.9.115）：427×240 下全部面板完整
  居中、**0 面板级越界**；960×540 / 640×360（k=1）同样 0；
- 预览工具修了 3 个会让结论失真的 bug（见第七节 9–11）；
- **`TicketMarketScreen` 7 处清零**（6.9.116）：新增 `IN_L/IN_T/IN_R/LIST_Y/ROW_H` 常量，标题下移到
  纸面内（top+44）、左列从 `left+18` 挪到 `left+44`（原来压在红卷轴上）、返回键从 `top+282`（压木框）
  挪到右下 `left+198, top+258`、每页 6 行改 5 行给页码腾位置；绘制与点击用同一组常量，不再各写一份坐标；
- **新增 `StatsScreen` 统计局面板**（6.9.117）：320×291 羊皮纸 + UiScale，按「整节」分页（2 页），
  数据走 `net/StatsNetwork` 从 `EconReport.stats()` 现算；入口在服务中心第 2 页「经济统计」。
  录制时由 `GuiDump.injectStatsSample()` 塞样例行，否则无头环境只有「正在读取……」；
- **新增 `ShippingScreen` 航运中心**（6.9.167–174）：服务中心第 2 页「航运中心」（原「远洋提单」）
  直接进这个界面，**5 个页签**——**在招**（份数进度条 / 载货清单图标网格 / 份额分布 / 出货·出钱）、
  **航行**（**在航船只列表**：船号 / 船名 / 第 n/m 段 / 到港日；点一行进 `VoyageShipScreen`）、
  **历史**（船号 / 结果 / 货款 / 关税）、
  **目的地**（页面顶部目的地选择：`罗马 / 待开 / 待开 / 待开`；下面是罗马行情 / 我的贸易单 / 品类报价；
  **进口 / 出口按钮置灰、「贸易暂未开放」**，数量输入框在这一页不显示，点了只弹提示——要开的时候把
  `clickRoma` 里那段灰按钮换回 `order(...)` 即可）、
  **包船**（草案 / 装货 / 出海 / 撤船）。底部只剩 `刷新 / ← 返回`（6.9.184 删掉了「详情」按钮——
  看船一律走「航行」页点行）。
  **倍率、船况不向玩家开放**：界面不显示，两个数据包里也只发 `0` 占位（`net/ShippingNetwork` 的 B 行、
  `net/MenuNetwork.buildVoyageInfo`），改客户端也读不到真值；真值只留在服务端
  `voyage_bills.txt` / `economy_debug.txt` 里给管理员看。
  数据走 `net/ShippingNetwork`（`W/B/L/P/S/O/G/C/H` 九类行）；动作全走已有指令
  （`/billvoyage contribute|invest|stall|settle|stabuy|stacancel|charter|sail|abandon`、
  `/billroma sell|want|cancel`），**不新增服务端业务逻辑**。
  原来的 `VoyageScreen`（船票 / 我的 / 市场）**已被它取代**，界面上已无入口。
  载货清单改成**纯图标网格**（每页 8 个，图标下方不再带文字；数量只体现在图标右下角的堆叠数字上），
  右上角 `n/m` + 木制 `< >` 翻页；在招页和航行页各记一份页码（`cargoPageOpen` / `cargoPageVoy`）。
- **出货 / 出钱 各自单开一页**（6.9.179）：
  `ContributeScreen`（出货换份额）= 把玩家自己的背包铺成界面（9×3 主背包 + 快捷栏），点一格选中、
  填数量后「出货」，发 `/billvoyage contribute <item> <qty>`；
  `InvestScreen`（出资换份额）= 填份数、实时算「需付 = 份数 × 单价」，「出资」发
  `/billvoyage invest <金额>`、「全买」发 `/billvoyage buy <航次> <剩余份数>`。
  在招页原来的「份数输入 + 出货/出钱」换成了这两个入口按钮，数量框只剩包船页用。
- **股票上架单独一页**（6.9.197）：`StockListScreen` —— 右键股票 → 股票界面 → 「上架」进入；
  份数（`-`/`+` 每次 1，上限=票面份数）、单价（`-`/`+` 每次 10，默认当前份值）、实时「合计」；
  确认发 `/billvoyage stall <单价> <份数>`（份数 < 票面 = 拆票，剩下的份数写回手里那张票）。
  股票界面原来的内联输入框已移除，页面回到「船名/状态/份数/发行价·份值/到港日 + 四个按钮」。- **日期一律显示成「X年Y月Z日」**（6.9.180）：`ShippingScreen.day(long)` 包一层
  `ServerCalendar.shortDate`（取不到就退回 `N 日`），在招截止、航行列表到港、船只详情、
  出资页、目的地募资截止、叙述文案全部改用它——界面上不再出现裸日数。
- **航次情况改成「按段讲事」**（6.9.186）：不再是一句笼统的「已经离港、预计 X 靠岸」，而是
  逐条写 `日期 + 本船遭遇了什么`——
  `1年6月1日 本船一路平安，没出什么事。` / `1年7月1日 本船遭遇了风暴，船身受损，货也折了些。` /
  `1年8月1日 本船遭遇了海盗，被抢走一部分货。`，最后补一句「预计 X 靠岸…」和风险提醒。
  数据来源：`VoyageBill.legLog`（每段事件名：平安 / 顺风 / 逆风 / 风暴 / 海盗 / 触礁 / 沉没）+
  服务端按 `departDay + k×航期/段数` 算出的每段判定日（B 行新增第 19、20 个字段 `legLog`、`legDays`）。
  事件→句子的映射在 `ShippingScreen.eventSentence()`，改文案只动这一个 switch。
  `ShippingScreen.narrativeLinesOf` 已改成 **public**，预览工具直接调它，保证录出来的字和游戏里一致。
- **`VoyageShipScreen` 合并页的形态**（6.9.181 起，6.9.185 精简）：只讲「航次情况」——抬头
  `#id 船名 · 状态 / 到港日期` → 叙述正文（按纸面宽度折行、10 行一页，右上 `n/m` + `< >` 翻）→
  持有人（前两位）+ 我的份额；底部只有 `← 返回`（6.9.187 又把 `数值` 按钮和它打开的
  `VoyageDetailScreen` 一起删了——**认购/出资改走航运中心「在招」页的「出货换份额 / 出钱换份额」**）。
  **段数、进度条、船上货物都不在这一页**，`VoyageNarrativeScreen`、`VoyageDetailScreen` 均已删除。

### 未完成（按 427×240 的 `audit_layout.py` 文字级统计，共 82 处；index.html 只报 1 条）

| 界面 | 处数 | 说明 |
|---|---|---|
| `MarketScreen` | 79 | 物品格子 5 列铺到纸面右侧 128–169px（x=373，纸面右 263）；320×240 时 JEI 侧栏还会出右边界。结构问题，先定方案：`enableScissor` 裁剪 / 改 4 列 / 重排 |
| `CitizenReportScreen` | 3 | 标题在纸面上方 19px、`← 返回` 落在纸面下方（本来就在木框区）——要么挪进纸面，要么认作设计 |
| 无面板的 5 个 | — | `BillOrder / ItemPicker / MainMenu / PanelScreen / TicketSell` 面板是 `fill` 画的、没有 `blit`，审计认不出纸面，得人工看图 |
| VoyageScreen 列表行数 | 1 处 | 列表区只有 138px，`rowH=17` 放 8 行，循环上限还是 `i < 11`，船多了压到底部按钮 |

---

## 六点五、正式服实装（更新包）

**当前可用：`dist\更新包2026110110.zip`**
（4,445,448 B，SHA256-16 `97192f68c1424929`）

| 包内 | 内容 |
|---|---|
| `mods\TangtuEconomical-6.9.272.jar` | 4,489,843 B / SHA256-16 `34783c28e36976ad`（无 BOM、TOML 解析通过） |
| `datapacks\tangtu\` | `pack.mcmeta` + `data/tangtu/kjbb/` 5 个 json（demand / elasticity / prices / tuning / voyage-cargo） |
| `config\kjbb\bots.json` | 仅首次部署时使用（已存在不覆盖） |
| `部署.ps1` | `-DryRun` 先看计划；正式执行会先把 `config\kjbb`、`world\datapacks\tangtu` 备份到 `<服务端根>\_backup_<时间戳>\` |
| `移除清单.txt` / `SHASUMS.txt` / `部署说明.md` | 删哪些旧包、逐文件校验、RDP 操作步骤 |

**要点**
- 客户端（整合包 `大唐盛兔1008`）必须换成同一个 6.9.272：6.9.183 起字符串载荷分片、6.9.2xx 订单多一列 `bot`，
  混版本会连不上或数据错乱；
- 回滚必须 **world + config + jar 三件一起回**（新版本写过 `stock_market.txt` / `stock_pending.txt` / `voyage_bills.txt` 新字段）；
- 已在假服务端目录上完整跑过一遍 `部署.ps1`（备份 → 删旧 jar → 装 jar → 装数据包 → 保留 bots.json），输出符合预期。

---
## 七、踩过的坑（新增，前面的交接档案里没有）

1. **PS 5.1 的 `$ErrorActionPreference='Stop'` 会把 java 的 stderr 当致命错误**，
   编译/运行 javac、java 前后必须改成 `Continue`，否则第一行日志就把脚本掐了。
2. **`.ps1` 里有中文就必须存成 UTF-8 带 BOM**，否则 PS 5.1 按 ANSI 读，路径变乱码。
   用 write 工具写完记得补 BOM（脚本里已这么处理）。
3. **`.bat` 里不能写中文**，`cmd.exe` 按 ANSI 读会把注释行当命令执行。`.bat` 保持纯
   ASCII，中文交给 PowerShell/Python 输出（Python 侧记得 `sys.stdout.reconfigure`）。
4. **别用 `R` 当函数名**，PowerShell 里 `r` 是 `Invoke-History` 的别名，会静默吃掉调用。
5. **中文路径不能进 classpath**：`U:\PCL 正式版 2.9.3\...` 会把 java/javac 的参数解析
   搞乱，而且长度超 32k。做法是 `mklink /J C:\dsh_mc_libs <libraries>` 建 ASCII 连接点。
6. **360 个依赖 jar 里有多个版本**（log4j、joml 等），必须按 artifact 目录去重取最高版本，
   否则 `NoSuchMethodError: LoggerContextFactory.isClassLoaderDependent`。
   注意：去重逻辑按「父目录的父目录」分组，**会把散放在一个目录里的 mod jar 合并成一个**，
   所以 mods 目录的 jar 不能走同一套去重（当前实现只保留了一个，属已知缺陷，不影响预览）。
7. **`Unsafe.allocateInstance` 会触发类初始化**：`Minecraft` 的静态初始化在无头环境会失败，
   所以只能给它装空壳 + 一个 `GameRenderer` 桩。
8. **预览的字体宽度是估算的**（ASCII 6 / 中文 9），文字排版 1~2px 的差别别当真；
   物品图标是彩色占位方块；原版背景一概不画。
9. **`Minecraft` 空壳里的连接字段是 `pendingConnection`**（1.21.1 没有 `connection`）。旧代码反射
   `connection` 抛 `NoSuchFieldException`，连带 `instance` 也没设上，结果 `Minecraft.getInstance()`
   一直是 null —— 所有 `WoodButton` 的标题都画不出来，而且被 `renderBackground` 的 NPE 挡在后面，
   界面上看不出来。现在空壳里把 `font` 和 `pendingConnection` 都塞好了。
10. **`renderablesPass` 会在 `render()` 成功后再把非控件的 renderable 画一遍**（原本是 render 失败
   时的兜底）。界面一旦套上 `UiScale`，就会在原始坐标上再录一遍贴图 —— 预览里出现「一大一小两个
   面板叠在一起」，审计也跟着翻倍。现在只有 `render()` 抛异常时才补画。
11. **控件矩形也要按 pose 换算**（`Rec.addWithLastTransform`）：控件记录的是 `getX/getY` 原始值，
   不换算的话缩放后的界面会被审计误报成「控件出框」。
12. **多页签界面现在会自动多录**：`build_and_dump.ps1` 顶部 `$pageScreens = @('ShippingScreen')`，
    对列出的界面依次传 `-Ddsh.page=1..4` 再录一遍；`GuiDump` 会把名字改成
    `航运中心_在招 / _航行 / _历史 / _目的地 / _包船`，于是索引页和 `out\` 里五页齐全。
    以后哪个界面加内部页签，把类名加进这个数组即可。
13. **隐藏控件不能画进预览**：`renderablesPass` 会把另一个页签的 `EditBox` 也记下来，
   `render_gui.py` 现在跳过 `note` 里带 `hidden` 的控件，否则预览里会多出一个白框。
14. **审计的文字宽度要用录制值**：`audit_layout.py` 以前拿字符串重新按「ASCII 6 / 全角 9」估宽，
   右对齐的混排串（`罗马四库 48.6K`）会被高估 10~17px 误报出框；改成优先用 `op["w"]`（录制时
   StubFont 真量出来的），误报从 21 处降到 0。

---

## 八、环境依赖与恢复

| 依赖 | 路径 | 说明 |
|---|---|---|
| JDK 21 | `C:\Program Files\Java\jdk-21.0.12.1` | javac / java |
| 游戏 libraries | `U:\PCL 正式版 2.9.3\.minecraft\libraries` | 经 `C:\dsh_mc_libs` 连接点访问 |
| 测试服 mods | `...\versions\test经济系统\mods` | 经 `C:\dsh_mc_mods` 连接点访问 |
| 编译产物 | `C:\dsh_guidump`（录制器）、`C:\dsh_guidump_shim`（桩） | 由脚本自动重建 |
| Python | `C:\Users\Administrator\.dsh\dsh-runtimes\...\python\python.exe` | 带 Pillow |
| 字体 | `C:\Windows\Fonts\msyh.ttc` | 渲染中文 |

**连接点失效**（重装 PCL、盘符变化）：删掉 `C:\dsh_mc_libs`、`C:\dsh_mc_mods`，
重跑 `build_and_dump.ps1`，脚本会自动重建。

---

## 九、下一步建议（按优先级）

1. `MarketScreen` 的 80 处——先定「裁剪 / 改 4 列 / 重排」，这是唯一挡住 100% 清零的大项；
2. `CitizenReportScreen` 的 3 处——把标题（`top+22`）挪到纸面里、返回键从木框区挪回纸面；
3. 无面板的 5 个界面（`BillOrder / ItemPicker / MainMenu / PanelScreen / TicketSell`）——审计认不出
   纸面，先人工看图，再决定要不要给 `audit_layout.py` 加一种「按 fill 矩形认面板」的兜底；
4. `VoyageScreen` 列表行数上限（`i < 11` → 8，或加 `enableScissor`）；
5. 把「标题 / 页签 / 内容起点」这类魔数抽成常量（现在散在多处，改一次要动 7 个地方；
   `TicketMarketScreen` 已经这么做了，可以照抄）。

---

## 十、交付记录

| 版本 | jar | 字节 | SHA256-16 | 更新包 |
|---|---|---|---|---|
| 6.9.113 | `dist\TangtuEconomical-6.9.113.jar` | 4,251,189 | `a018b4d215dbb43f` | `更新包2026103001.zip` |
| 6.9.114 | `dist\TangtuEconomical-6.9.114.jar` | 4,252,449 | `cb0c107c07487e00` | `更新包2026103002.zip`（4,201,626 B / `0fd9d96538bb8ecf`） |
| 6.9.115 | `dist\TangtuEconomical-6.9.115.jar` | 4,258,874 | `f47e3601c6e28b56` | —（被 6.9.116 取代） |
| **6.9.116** | `dist\TangtuEconomical-6.9.116.jar` | **4,258,936** | **`bd504e39aa85d77b`** | 未出（GUI 改动，等 6.9.114 上线后合并发） |
| 6.9.117 | `dist\TangtuEconomical-6.9.117.jar` | 4,279,988 | `ee0455a10ac63762` | **本地构建、未部署**：含统计局面板 + 另一会话进行中的罗马商行改动（`RomaMarket` / `VoyageBill.revenue`），等罗马那套验收完再一起发 |
| 6.9.167 | `dist\TangtuEconomical-6.9.167.jar` | 4,400,692 | `a3d27615a0cf96dc` | 航运中心初版（平涂按钮），被 6.9.168 取代 |
| 6.9.168 | `dist\TangtuEconomical-6.9.168.jar` | 4,400,630 | `5f8e7e20d485797f` | 航运中心 5 页签的按钮全部换成木制（`WoodRender`） |
| **6.9.169** | `dist\TangtuEconomical-6.9.169.jar` | 4,401,168 | `7386bc9e1361db96` | 载货清单改图标网格 + 上下页 |
| 6.9.170 | `dist\TangtuEconomical-6.9.170.jar` | 4,401,104 | `56b0863ec2dcc78f` | 图标旁去掉数字，每页 8 个 |
| **6.9.171** | `dist\TangtuEconomical-6.9.171.jar` | 4,401,611 | `b3621b79c023dd5f` | 页签「罗马」→「目的地」，页内加目的地选择条（罗马 + 3 个预留位） |
| **6.9.172** | `dist\TangtuEconomical-6.9.172.jar` | 4,405,409 | `065f17de50e5fb42` | 远洋提单「详情」改成单开一页（`VoyageNarrativeScreen`） |
| **6.9.173** | `dist\TangtuEconomical-6.9.173.jar` | 4,405,305 | `7d4cf9c546350b31` | 去掉「详情」按钮，改成**点船次直接开详情页**；页内加「数值」入口 |
| **6.9.174** | `dist\TangtuEconomical-6.9.174.jar` | 4,409,294 | `3eb95a448e001d50` | 航运中心扩到 **7 个页签**（加 我的 / 市场），并**取代远洋提单**成为服务中心入口 |
| **6.9.175** | `dist\TangtuEconomical-6.9.175.jar` | 4,409,194 | `8a43395ad5c041ae` | 航行页去掉倍率 / 船况数字（叙述页同步） |
| **6.9.176** | `dist\TangtuEconomical-6.9.176.jar` | 4,409,170 | `1353009c8d1d8b38` | 倍率 / 船况**不下发**：两个数据包只发 0 占位；单船详情「返回」改回航运中心 |
| **6.9.177** | `dist\TangtuEconomical-6.9.177.jar` | 4,409,170 | `ae15fd1a2014fbc7` | 目的地页的**进口 / 出口置灰**（罗马贸易暂未开放） |
| **6.9.178** | `dist\TangtuEconomical-6.9.178.jar` | 4,413,821 | `4118f01798ffc4fb` | 航行页只留**船只列表**，具体信息拆成新界面 `VoyageShipScreen`（点船进入） |
| **6.9.179** | `dist\TangtuEconomical-6.9.179.jar` | 4,423,287 | `2f35f3a147a2e426` | **出货 / 出钱各自单开一页**：`ContributeScreen`（背包选货）/ `InvestScreen`（填份数算总价） |
| **6.9.180** | `dist\TangtuEconomical-6.9.180.jar` | 4,423,433 | `c60044260e4b6910` | 所有「第 N 日」改成 **X年Y月Z日**（截止 / 到港 / 募资） |
| **6.9.181** | `dist\TangtuEconomical-6.9.181.jar` | 4,420,409 | `bfd56ef72625aa6c` | **船只情况 + 航次详情合并**（`VoyageNarrativeScreen` 删除） |
| **6.9.182** | `dist\TangtuEconomical-6.9.182.jar` | 4,421,071 | `7f57bea2b5bcb850` | **修 打开市场就掉线**：`markets_reply` 超 32767 字符上限 → 改成分片发送 |
| **6.9.183** | `dist\TangtuEconomical-6.9.183.jar` | 4,425,188 | `534a43547f441f54` | 新增 `net/ChunkedString` 编解码器，**所有「body 可能很长」的包**统一自动分片 |
| **6.9.184** | `dist\TangtuEconomical-6.9.184.jar` | 4,425,009 | `1b0be1992bb3a704` | 删掉航运中心底部的**「详情」按钮**（看船走航行页点行） |
| **6.9.185** | `dist\TangtuEconomical-6.9.185.jar` | 4,423,905 | `3ec4f37b72f096ba` | 船只详情页**只留航次情况**：去掉段数、进度条、船上货物 |
| **6.9.186** | `dist\TangtuEconomical-6.9.186.jar` | 4,425,317 | `ea8c96b0dd6fd9cb` | 航次情况**按段写**：日期 + 逐段遭遇（风暴/海盗/触礁…） |
| **6.9.187** | `dist\TangtuEconomical-6.9.187.jar` | 4,417,498 | `372093ab31329f47` | 删掉船只详情的 `数值` 按钮 + `VoyageDetailScreen` 类 |
| **6.9.188** | `dist\TangtuEconomical-6.9.188.jar` | 4,418,134 | `26f53871c01aebf4` | 「我的」页改成股票票面（船名/状态/份数/发行价·份值/到港日/载货进度 + 上架·折现·提货，`n/m` 翻船） |
| **6.9.189** | `dist\TangtuEconomical-6.9.189.jar` | 4,416,761 | `98830d41d8b156da` | **删掉「我的」页签**（7→6，`TAB_W` 32→38）；持仓改由右键股票看 |
| **6.9.190** | `dist\TangtuEconomical-6.9.190.jar` | 4,415,568 | `84815faa3dc88d01` | 股票界面**删掉「载货进度」**（4 条带进度条的货）；样例注入只给 `StockScreen` |
| **6.9.191** | `dist\TangtuEconomical-6.9.191.jar` | 4,416,071 | `4a0cfdf71598450c` | **参与份额强制发股票**：出资 / 出货也当场发票（原来只有认购发），不再走 holders 记账 |
| **6.9.192** | `dist\TangtuEconomical-6.9.192.jar` | 4,416,539 | `153e5dbc1332078e` | 股票界面 `折现/提货` **常亮**；**提货改成开「提货提单」**（地址=玩家名，右键蛙港/邮箱取货） |
| **6.9.193** | `dist\TangtuEconomical-6.9.193.jar` | 4,416,608 | `74013f1457cba27e` | 修**出货换份额发错指令**（界面发的是 `contribute`，指令实际叫 `cargo`）；并加 `contribute` 别名 |
| **6.9.194** | `dist\TangtuEconomical-6.9.194.jar` | 4,416,613 | `586741409765c286` | 出资页「份数」输入框文字改**白色**（原来深棕字压深色底看不见） |
| **6.9.195** | `dist\TangtuEconomical-6.9.195.jar` | 4,419,381 | `301ffd0abf240749` | 股票：**自定价 + 拆票挂单**（界面两个输入框）；**航次结束自动撤单退票**（离线进服补发） |
| **6.9.196** | `dist\TangtuEconomical-6.9.196.jar` | 4,419,378 | `6856280300b8ea90` | **修 6.9.195 打不开**：`neoforge.mods.toml` 被写进 UTF-8 BOM → `Invalid bare key: '﻿modLoader'`；去掉 BOM 重打 |
| **6.9.197** | `dist\TangtuEconomical-6.9.197.jar` | 4,423,341 | `5d1c02d01092a285` | 新增 `StockListScreen`「上架股票」：单独一页调份数（±1）与单价（±10）、实时合计；股票界面的内联输入框移除 |
| **6.9.198** | `dist\TangtuEconomical-6.9.198.jar` | 4,423,949 | `f2671c9f35712cbd` | 股票按钮「上架」→**售出股票**；航运中心「市场」页**并入票据市场**（票据/股票两个页签），航运中心缩到 5 个页签 |
| **6.9.203** | `dist\TangtuEconomical-6.9.203.jar` | **4,425,362** | **`c2a52f78a96ea61c`** | **包船页「暂未开放」**：装货/出海/撤船三个按钮置灰、点了只提示；数量框移除。⚠ 该版本号与另一会话的构建共用（同一棵源码树，构建内容一致）；**已部署测试服** |
| **6.9.205** | `dist\TangtuEconomical-6.9.205.jar` | **4,429,350** | **`4100035fcf55c19e`** | 服务中心**去掉「经济统计」入口**（页面剩下 航运中心/期货/期权/拍卖行）；`StatsScreen` 类保留但已无入口；**已部署测试服** |
| **6.9.236** | `dist\TangtuEconomical-6.9.236.jar` | **4,439,589** | **`273c20355ed6ed77`** | 活跃订单**真人的单换色**（金色=我 / 蓝色=玩家 / 灰色=机器人；服务端 `open_orders_reply` 每行第 8 列带 `bot` 标记）；`build\package.ps1` 打包并部署测试服。**正式服更新包：`dist\更新包2026110110.zip`（4,398,944 B / `8aaba9f53da2cbab`）** |
| **6.9.249** | `dist\TangtuEconomical-6.9.249.jar` | **4,469,337** | **`65f77ecbee05b1b7`** | **交易中心**（服务中心第 2 页）：三个页签「我的挂单 / 托管订单 / 投递记录」，撤单→`/billoffer cancel`、发货/确认→`/billtrade`、倒计时用服务器时钟；新增 `net/TxNetwork` + `client/TxData` + `client/TxCenterScreen`。**正式服更新包：`dist\更新包2026110110.zip`（4,427,756 B / `497b5c1eca70d234`）** |
| **6.9.250** | `dist\TangtuEconomical-6.9.250.jar` | **4,470,596** | **`44c4961fe72d81c3`** | 交易中心**投递记录可点行看详情**：整条记录（完整时间 / 卖家→买家 / 物品名+ID×数量 / 交付方式解释），并标「寄给我 / 我卖出」；列表改 5 行 + 翻页，详情占下半区；**正式服更新包：`dist\更新包2026110110.zip`（4,428,995 B / `6639f46ab687c5e1`）** |
| **6.9.251** | `dist\TangtuEconomical-6.9.251.jar` | **4,470,643** | **`a0041630a1e777df`** | **修：市场界面有两个输入框漂到面板外**（画面上表现为左边飘着「0」「1」两个小字）——`fieldCount`/`fieldPrice` 在 `left/top` 算出之前就 new 了，只有搜索框事后重摆；现在三个框都在布局算完后统一 `setX/setY`。**正式服更新包：`dist\更新包2026110110.zip`（4,429,055 B / `93f2481a949fc47b`）** |
| **6.9.258** | `dist\TangtuEconomical-6.9.258.jar` | **4,478,409** | **`4d8b5f34e3b3c48d`** | **投递记录搬进市场**：市场页签新增「投递」（市场 / 投递 / 交易所 / 票据 / 我的），列表+翻页+点行看详情与交易中心一致；抽出共用 `client/MailPanel`（交易中心第三页也改用它）；投递页隐藏买入栏与搜索/数量/单价输入框。**正式服更新包：`dist\更新包2026110110.zip`（4,436,360 B / `7dae8db61e9b7035`）** |
| **6.9.259** | `dist\TangtuEconomical-6.9.259.jar` | **4,475,612** | **`e016ccf1ac3b1cf4`** | **市场界面重做**：页签缩到三个——**市场 / 投递 / 交易所**（删掉「票据」「我的」，票据走服务中心的「票据市场」、我的走「我的订单」→ 交易中心托管订单页）；页签右对齐等宽、搜索框下移、交易所页隐藏买入栏并修掉行情行与挂单提示撞行；服务中心「我的订单」改为打开 `TxCenterScreen(1)`。**正式服更新包：`dist\更新包2026110110.zip`（4,433,468 B / `1e2fc1fbbfc4b7f2`）** |
| **6.9.260** | `dist\TangtuEconomical-6.9.260.jar` | **4,475,678** | **`ed44031d9a759723`** | **投递记录的「锁链」改叫「物流中」**（列表标签 + 详情解释），服务端 `MailLog` 以后直接写「物流中」，老记录里的「锁链」仍识别并显示为物流中。**正式服更新包：`dist\更新包2026110110.zip`（4,433,522 B / `cd31937e9fd540ce`）** |
| **6.9.261** | `dist\TangtuEconomical-6.9.261.jar` | **4,475,660** | **`66985a998c8914a1`** | 投递详情去掉「（不等于已到货）」，物流中那行只留「已交给物流系统，还在运输」。**正式服更新包：`dist\更新包2026110110.zip`（4,433,504 B / `4beeb14ab17c484e`）** |
| **6.9.262** | `dist\TangtuEconomical-6.9.262.jar` | **4,475,566** | **`3a536c0b71c9701e`** | **市场页「选中商品」后文字重排**（原来详情 7 行压在余额/日期/订单上）：左列 = 列表 + 分页 + 四行详情（最近成交/卖一买一/在售/合计）+ 余额行；右列 = K 线 + 服务器订单（4 行 × 12，原来 14 行会画出纸面）+ 分页 + 「查看全部活跃订单」；底栏只留已选/数量/买入。**正式服更新包：`dist\更新包2026110110.zip`（4,433,409 B / `23c823763253ddab`）** |
| **6.9.272** | `dist\TangtuEconomical-6.9.272.jar` | **4,489,843** | **`34783c28e36976ad`** | **交易所页补「数量」输入**：挂单原来只有单价框，数量取自市场页的框（且在交易所页被隐藏），等于没法在界面里挂单；现在数量 / 单价 / 挂单按钮一排摆开（68/136/180），点「挂单」走 `MarketNetwork.placeOffer` 从发报机仓库取货上架。**正式服更新包：`dist\更新包2026110110.zip`（4,445,448 B / `97192f68c1424929`）** |

6.9.197 已部署到测试服 `mods\`。正式服仍是 6.9.35，升级需用户本人 RDP 操作；
6.9.114 的 `更新包2026103002.zip` 仍是给正式服准备的那一版（`apply-patch.ps1 -DryRun` 校验通过）。
**注意：6.9.183 起字符串载荷分片，客户端与服务端必须同版本。**
**注意：改 `neoforge.mods.toml` 千万别用 `Set-Content -Encoding UTF8`（会加 BOM，整包加载失败）。**

> **6.9.182 的崩溃原因（日志实据）**：`2026-10-31-7.log.gz` 末尾
> `Failed encoding custom payload kjbankbridge:markets_reply: String too big (was 118661 characters, max 32767)`
> ——行情行数随物品数增长，单包塞不下就编码失败、直接踢人。
> 6.9.182 先给 `markets_reply` / `history_reply` / `my_orders_reply` / `open_report` 加了 `seq/parts` 分片；
> 6.9.183 再抽了个通用编解码器 `net/ChunkedString`（写：先写片数、再逐片 `writeUtf`；读：拼回来），
> 把 **`MyBillsReply` / `InvReply` / `ItemOrdersReply` / `TicketDetailReply` / `OpenOrdersReply` /
> `TicketsReply` / `OrderDetailReply` / `MyListingsReply` / `ShippingInfo` / `StatsReply` /
> `OpenStoreForm` / `ReceiptView` / `StoreReply` / `ShipmentsReply` / `SubmitOrder`** 的 `body` 字段
> 全部换成 `ChunkedString.CODEC`——以后这些列表再长也不会超出字符串上限。


---

# 附：航运系统 GUI 需求（新增）

> **状态：6.9.167 已实现**（`client/ShippingScreen` + `net/ShippingNetwork`），
> 入口在远洋提单 → 船票页底部「航运」。实现时把需求拆成 5 个页签：
> 在招 / 航行 / 历史（对应下面「建议的界面结构」三块）+ 罗马 / 包船（把只能敲指令的两块也搬上界面）。

## 一、需要展示的数据

| 页面 | 字段 |
|---|---|
| **航次列表** | 船号、状态（OPEN/VOYAGING/CLOSED）、份数 x/143、到港日、结果 |
| **航次详情** | 载货清单（10 种）、holders 及份额占比、当前倍率、船况 |
| **份额分布** | 各持有人百分比（饼图/条形图） |
| **关税记录** | 每趟出口、返程、关税金额 |

## 二、玩家可操作的入口

| 操作 | 对应指令/API |
|---|---|
| 查看在招航次 | `VoyageBill.openBill()` |
| 出货换份额 | `VoyageBill.contribute(who, item, qty)` |
| 出钱换份额 | `VoyageBill.invest(who, amount)` |
| 查看自己份额 | `Bill.heldBy(who)` |
| 份额二级市场 | `VoyageShareMarket.open()` |
| 买份额 | `StockMarket.botBuy` / 玩家版 `buy()` |

## 三、数据来源

| 文件 | 用途 |
|---|---|
| `config/kjbb/voyage_bills.txt` | 航次主数据（29 列，holders 是 `名字=份数;...`） |
| `config/kjbb/stock_market.txt` | 份额挂单 |
| `config/kjbb/bot_trades.txt` | 出货/出资/关税流水 |

**holders 字段解析**：

```
唐兔国航运公司=86;经济部=14;唐兔国国民=33;大A膏手=10
→ split(";") → split("=") → 名字 + 份数
→ 占比 = 份数 / b.total
```

## 四、三个必须注意的展示细节

1. **份额在结算后清空**——想展示分布必须在 OPEN/VOYAGING 阶段抓取
2. **罗马国不应出现在 holders 里**（6.9.163 之后）
3. **经济部份额恒为 10%**，可作为「限额」提示

## 五、建议的界面结构

```
航运页
├── 在招航次（大卡片）
│   ├── 份数进度条 99/143
│   ├── 载货清单（图标 + 名称）
│   ├── [出货换份额] [出钱换份额]
│   └── 份额分布（当前 4 方）
├── 航行中（时间轴）
│   └── 第 3/5 段 · 倍率 91% · 船况 85
└── 历史航次（表格）
    └── 船号 / 结果 / 关税 / 我的收益
```


---

# 附二：交易与物流 GUI 需求（6.9.248）

## 一、三个新页面

### 1. 我的挂单页

| 元素 | 数据来源 |
|---|---|
| 挂单列表 | `OfferBook.of(玩家)` 过滤 OPEN |
| 列：挂单号 / 物品 / 剩余数量 / 单价 / 总价 | 同上 |
| 合计市值 | 累加 price × count |
| 按钮：撤单 | `OfferBook.setStatus(id, CANCELLED)` + 退货 |

**对应指令**：`/billoffer mine`

### 2. 托管订单页

| 元素 | 数据来源 |
|---|---|
| 订单列表 | `TradeOrder.mine(玩家)` |
| 列：订单号 / 物品 / 数量 / 单价 / 状态 / 对手方 | Row 字段 |
| 状态标签 | PENDING 黄 / SHIPPED 蓝 / DONE 绿 / CANCELLED 灰 |
| 按钮：发货 | 卖家侧，`TradeOrder.ship(id)` |
| 按钮：确认收货 | 买家侧，`TradeOrder.confirm(id)` |
| 倒计时 | PENDING 状态下显示「还剩 X 分钟自动退款」 |

**对应指令**：`/billtrade`、`ship`、`confirm`

### 3. 投递记录页

| 元素 | 数据来源 |
|---|---|
| 最近记录 | `MailLog.recent(15)` |
| 列：时间 / 卖家 → 买家 / 物品 × 数量 / 方式 | 解析 `time|seller|buyer|item|count|mode` |
| 方式标签 | 锁链（绿）/ 排队（黄） |

**对应指令**：`/billmail`

---

## 二、买卖流程的界面引导

### 购买前

买家点「购买」时，系统要判断交付方式并提示：

```
两人 <= 8 格 → 「面对面交付，立即到货」
否则         → 「物流配送，需要绑定提货点」
```

**如果买家没绑提货点且不在卖家附近**：

```
弹窗：请先站到蛙港/邮箱旁用 /billaddr 绑定提货点
```

### 购买后

| 情况 | 提示 |
|---|---|
| 面对面 | 「交易完成，物品已入背包」 |
| 走锁链 | 「货已发往你的提货点 <地址>」 |
| 进队列 | 「提货点暂未加载，货已排队，加载后自动投递」 |

### 托管流程的界面提示

```
锁单成功 → 「已托管 X 唐兔币，等对方发货（30 分钟内）」
发货     → 「已标记发货，请把货投进物流系统」
确认收货 → 「交易完成，X 唐兔币已付给对方」
超时取消 → 「对方 30 分钟未发货，X 唐兔币已退回」
```

---

## 三、需要展示的四个状态

| 数据 | 来源 | 展示位置 |
|---|---|---|
| 挂单状态（OPEN/FILLED） | `OfferBook` | 挂单列表 |
| 托管订单状态（PENDING/SHIPPED/DONE/CANCELLED） | `TradeOrder` | 订单页 |
| 投递方式（锁链/排队） | `MailLog` | 投递记录 |
| 提货点是否绑定 | `AddressBook.get(玩家)` | 购买前检查 |

---

## 四、三个必须注意的展示细节

1. **未绑提货点时不能买异地挂单**
   → 购买按钮置灰，hover 提示「先去绑定提货点」

2. **PENDING 订单要显示倒计时**
   → 用 `created + 30min - now` 计算剩余时间

3. **投递记录里的「锁链」不代表已到货**
   → 只是交给了物流；建议加一行说明「等待运输」

---

## 五、建议的页面结构

```
交易中心
├── 市场（买卖挂单）
│   ├── 浏览挂单（现有）
│   ├── 我的挂单（新增）
│   └── [买] 按钮 → 判断交付方式
├── 我的订单（托管）
│   ├── 待发货（卖家视角）
│   ├── 待收货（买家视角）
│   └── 已完成 / 已取消
└── 物流
    ├── 投递记录（新增）
    └── 离线队列（管理员可见）
```

---

## 六、接口对照表

| 界面动作 | 调用的后端方法 |
|---|---|
| 加载我的挂单 | `OfferBook.of(name)` |
| 撤单 | `OfferBook.setStatus(id, "CANCELLED")` |
| 购买 | `OfferBook.buy(name, id, count)` |
| 锁单 | `TradeOrder.lock(name, offerId, count)` |
| 发货 | `TradeOrder.ship(name, orderId)` |
| 确认收货 | `TradeOrder.confirm(name, orderId)` |
| 我的订单 | `TradeOrder.mine(name)` |
| 投递记录 | `MailLog.recent(15)` |
| 检查提货点 | `AddressBook.get(name)` |
