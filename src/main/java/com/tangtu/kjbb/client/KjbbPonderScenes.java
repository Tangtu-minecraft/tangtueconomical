package com.tangtu.kjbb.client;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** 三个票据教程场景 */
public class KjbbPonderScenes {

    /** 空白单据 → 仓单 */
    public static void blankForm(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("blank_form", "\u7528\u7a7a\u767d\u5355\u636e\u7ed9\u7bb1\u5b50\u5f00\u4ed3\u5355");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos chest = util.grid().at(2, 1, 2);
        scene.world().showSection(util.select().position(chest), Direction.DOWN);
        scene.idle(20);

        scene.overlay().showText(70)
                .text("\u624b\u6301\u7a7a\u767d\u5355\u636e\uff0c\u53f3\u952e\u7bb1\u5b50")
                .pointAt(util.vector().blockSurface(chest, Direction.UP))
                .placeNearTarget();
        scene.idle(80);

        scene.overlay().showText(80)
                .text("\u7cfb\u7edf\u8bb0\u5f55\u7bb1\u5b50\u91cc\u7684\u6bcf\u4e00\u79cd\u7269\u54c1\u4e0e\u6570\u91cf")
                .pointAt(util.vector().centerOf(chest))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(90)
                .text("\u540c\u65f6\u7bb1\u5b50\u88ab\u4e0a\u9501\uff1a\u53ea\u6709\u6301\u7968\u4eba\u80fd\u6253\u5f00")
                .pointAt(util.vector().centerOf(chest))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(80)
                .text("\u53f3\u952e\u4ed3\u5355\u53ef\u4ee5\u9886\u53d6\uff0c\u6216\u62ff\u53bb\u5e02\u573a\u6302\u5356")
                .independent(0)
                .placeNearTarget();
        scene.idle(90);

        scene.markAsFinished();
    }

    /** 提单：空白单据的旅程（卖家 → 燃烧室 → 变成提单 → 飞回卖家） */
    public static void billOfLading(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("bill_of_lading", "\u63d0\u5355\uff1a\u7a7a\u767d\u5355\u636e\u7684\u65c5\u7a0b");
        scene.configureBasePlate(0, 0, 7);
        scene.showBasePlate();
        scene.idle(10);

        scene.world().showSection(util.select().fromTo(0, 0, 0, 6, 3, 6), Direction.DOWN);
        scene.idle(25);

        final BlockPos chest = util.grid().at(2, 1, 3);
        final BlockPos hopper = util.grid().at(2, 2, 3);
        final BlockPos packager = util.grid().at(2, 1, 4);
        final BlockPos link = util.grid().at(2, 2, 4);
        final BlockPos ticker = util.grid().at(4, 1, 4);
        final BlockPos burner = util.grid().at(5, 1, 4);
        final BlockPos sellerPos = util.grid().at(1, 1, 1);
        final BlockPos buyerPos = util.grid().at(5, 1, 2);

        // 卖家 / 买家
        scene.world().createEntity(level -> {
            var p = net.minecraft.world.entity.EntityType.PARROT.create(level);
            if (p != null) {
                p.setVariant(net.minecraft.world.entity.animal.Parrot.Variant.RED_BLUE);
                p.setPos(sellerPos.getX() + 0.5, sellerPos.getY(), sellerPos.getZ() + 0.5);
                p.setCustomName(net.minecraft.network.chat.Component.literal("\u5356\u5bb6"));
                p.setCustomNameVisible(true);
            }
            return p;
        });
        scene.world().createEntity(level -> {
            var p = net.minecraft.world.entity.EntityType.PARROT.create(level);
            if (p != null) {
                p.setVariant(net.minecraft.world.entity.animal.Parrot.Variant.GREEN);
                p.setPos(buyerPos.getX() + 0.5, buyerPos.getY(), buyerPos.getZ() + 0.5);
                p.setCustomName(net.minecraft.network.chat.Component.literal("\u4e70\u5bb6"));
                p.setCustomNameVisible(true);
            }
            return p;
        });
        scene.idle(25);

        // ① 铁锭经漏斗入库
        scene.world().createItemEntity(
                util.vector().centerOf(hopper.getX(), hopper.getY() + 3, hopper.getZ()),
                util.vector().of(0.0, -0.06, 0.0),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 16));
        scene.idle(95);

        // ② 漏斗退场（纯演示，不配旁白）
        scene.world().hideSection(util.select().position(hopper), Direction.UP);
        scene.idle(60);

        // ③ 空白单据从卖家处出发
        final net.minecraft.world.phys.Vec3 sellerFront =
                util.vector().centerOf(sellerPos.getX(), sellerPos.getY() + 1, sellerPos.getZ());
        var form = scene.world().createItemEntity(sellerFront, util.vector().of(0.0, 0.0, 0.0),
                new net.minecraft.world.item.ItemStack(com.tangtu.kjbb.item.KjbbItems.BLANK_FORM.get()));
        scene.world().modifyEntity(form, e -> e.setNoGravity(true));
        scene.overlay().showText(70)
                .text("\u5356\u5bb6\u624b\u91cc\u62ff\u7740\u4e00\u5f20\u7a7a\u767d\u5355\u636e")
                .pointAt(sellerFront)
                .placeNearTarget();
        scene.idle(80);

        // ④ 单据一路挪到烈焰人燃烧室前
        final net.minecraft.world.phys.Vec3 burnerFront =
                util.vector().centerOf(burner.getX(), burner.getY() + 1, burner.getZ());
        for (int i = 1; i <= 14; i++) {
            final double t2 = i / 14.0;
            scene.world().modifyEntity(form, e -> {
                var pos = sellerFront.add(burnerFront.subtract(sellerFront).scale(t2));
                e.setPos(pos.x, pos.y + Math.sin(t2 * Math.PI) * 0.6, pos.z);
            });
            scene.idle(2);
        }
        scene.overlay().showText(85)
                .text("\u628a\u5b83\u9001\u5230\u70c8\u7130\u4eba\u71c3\u70e7\u5ba4\u524d")
                .pointAt(burnerFront)
                .placeNearTarget();
        scene.idle(95);

        // ⑤ 变成提单：单据沉进燃烧室
        for (int i = 1; i <= 6; i++) {
            final double t3 = i / 6.0;
            scene.world().modifyEntity(form, e ->
                    e.setPos(burnerFront.x, burnerFront.y - t3 * 1.2, burnerFront.z));
            scene.idle(2);
        }

        // ⑥ 提单出现，并飞回卖家面前
        final net.minecraft.world.phys.Vec3 sellerFront2 =
                util.vector().centerOf(sellerPos.getX(), sellerPos.getY() + 1, sellerPos.getZ()).add(0.0, 0.2, 0.0);
        var bill = scene.world().createItemEntity(burnerFront,
                util.vector().of(0.0, 0.0, 0.0),
                new net.minecraft.world.item.ItemStack(com.tangtu.kjbb.item.KjbbItems.BILL.get()));
        scene.world().modifyEntity(bill, e -> e.setNoGravity(true));
        for (int i = 1; i <= 14; i++) {
            final double t4 = i / 14.0;
            scene.world().modifyEntity(bill, e -> {
                var pos = burnerFront.add(sellerFront2.subtract(burnerFront).scale(t4));
                e.setPos(pos.x, pos.y + Math.sin(t4 * Math.PI) * 0.8, pos.z);
            });
            scene.idle(2);
        }

        // ⑦ 同一时刻：打包机打包、发报机发报
        scene.effects().indicateSuccess(packager);
        scene.effects().indicateSuccess(link);
        var pkg = scene.world().createItemEntity(
                util.vector().centerOf(ticker.getX(), ticker.getY() + 2, ticker.getZ()),
                util.vector().of(0.0, 0.05, 0.0),
                new net.minecraft.world.item.ItemStack(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                net.minecraft.resources.ResourceLocation.tryParse(
                                        "create:cardboard_package_12x10"))));
        scene.world().modifyEntity(pkg, e -> e.setNoGravity(true));
        scene.effects().indicateSuccess(ticker);
        scene.overlay().showText(100)
                .text("\u5c31\u5728\u8fd9\u4e00\u523b\uff1a\u6253\u5305\u673a\u6253\u5305\u3001\u53d1\u62a5\u673a\u53d1\u62a5")
                .pointAt(util.vector().centerOf(ticker))
                .placeNearTarget();
        scene.idle(110);

        // ⑧ 提单落在卖家面前
        scene.overlay().showText(90)
                .text("\u7a7a\u767d\u5355\u636e\u53d8\u6210\u63d0\u5355\uff0c\u98de\u56de\u5356\u5bb6\u9762\u524d")
                .pointAt(sellerFront2)
                .placeNearTarget();
        scene.idle(100);

        scene.markAsFinished();
    }

    /** 市场：下单与成交 */
    public static void market(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("market", "在市场里下单与成交");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        scene.overlay().showText(90)
                .text("空白单据潜行右键，或输入 /billmarket，打开市场界面")
                .independent(0)
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("市场页签显示「卖一 / 买一」：别人挂的最低价与最高出价")
                .independent(0)
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("点「买入」按卖一价下单，钱先冻结，等卖家接单")
                .independent(0)
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("卖家在「我的」或 /billorder take 接单，包裹寄到你的提货点")
                .independent(0)
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(90)
                .text("货到提货点后自动放款；30 分钟无人接单自动退款")
                .independent(0)
                .placeNearTarget();
        scene.idle(100);

        scene.markAsFinished();
    }

    /** 绑定钥匙：右键邮箱绑定提货点 */
    public static void addressKey(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("address_key", "\u7ed1\u5b9a\u94a5\u5319\uff1a\u7ed9\u81ea\u5df1\u7ed1\u4e00\u4e2a\u63d0\u8d27\u70b9");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos postbox = util.grid().at(2, 1, 2);
        BlockPos chest = util.grid().at(3, 1, 2);
        scene.world().showSection(util.select().position(postbox), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(chest), Direction.DOWN);
        scene.idle(20);

        scene.overlay().showText(70)
                .text("\u7ed1\u5b9a\u94a5\u5319\u53ea\u80fd\u7ed1\u5230\u300c\u86d9\u6e2f / \u5305\u88f9\u6e2f / \u90ae\u7bb1\u300d")
                .pointAt(util.vector().centerOf(postbox))
                .placeNearTarget();
        scene.idle(80);

        scene.overlay().showText(80)
                .text("\u6f5c\u884c + \u53f3\u952e\u90ae\u7bb1\uff0c\u5373\u53ef\u7ed1\u5b9a\u4e3a\u81ea\u5df1\u7684\u63d0\u8d27\u70b9")
                .pointAt(util.vector().blockSurface(postbox, Direction.UP))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(80)
                .text("\u7ed1\u5b9a\u540e\u90ae\u7bb1\u4f1a\u81ea\u52a8\u6539\u6210\u4f60\u7684\u540d\u5b57")
                .pointAt(util.vector().centerOf(postbox))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(80)
                .text("\u4e4b\u540e\u63a5\u5355\u3001\u5151\u4ed8\u4ed3\u5355\uff0c\u8d27\u7269\u90fd\u4f1a\u5bc4\u5230\u8fd9\u91cc")
                .pointAt(util.vector().centerOf(postbox))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(70)
                .text("\u60f3\u89e3\u9664\uff1a/billkey unbind")
                .independent(0)
                .placeNearTarget();
        scene.idle(80);

        scene.markAsFinished();
    }

    /** 劳动合同：右键地面雇佣假人 */
    public static void laborContract(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("labor_contract", "\u52b3\u52a8\u5408\u540c\uff1a\u96c7\u4e00\u4e2a\u5047\u4eba\u5e2e\u4f60\u5e72\u6d3b");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos work = util.grid().at(2, 1, 2);
        scene.world().showSection(util.select().position(work), Direction.DOWN);
        scene.idle(20);

        scene.overlay().showText(80)
                .text("\u624b\u6301\u52b3\u52a8\u5408\u540c\uff0c\u53f3\u952e\u5730\u9762\u53ef\u4ee5\u53ec\u5524\u5047\u4eba")
                .pointAt(util.vector().blockSurface(work, Direction.UP))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(90)
                .text("\u5047\u4eba\u662f\u771f\u6b63\u7684\u73a9\u5bb6\u5b9e\u4f53\uff1a\u80fd\u6302\u673a\u3001\u80fd\u88ab\u5f15\u5bfc\u3001\u4f1a\u81ea\u52a8\u6316\u77ff\u79cd\u7530")
                .pointAt(util.vector().centerOf(work))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(80)
                .text("\u79df\u91d1\u4ece\u94b1\u5305\u91cc\u6263\uff0c\u5230\u671f\u81ea\u52a8\u89e3\u96c7")
                .pointAt(util.vector().centerOf(work))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(70)
                .text("\u5168\u670d\u4e0a\u9650 16 \u4e2a\uff0c\u6bcf\u4eba 3 \u4e2a")
                .independent(0)
                .placeNearTarget();
        scene.idle(80);

        scene.markAsFinished();
    }

    /** 本票 / 支票：见票即付 */
    public static void promissoryNote(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("promissory_note", "\u672c\u7968\u4e0e\u652f\u7968\uff1a\u89c1\u7968\u5373\u4ed8");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos payer = util.grid().at(1, 1, 2);
        BlockPos payee = util.grid().at(3, 1, 2);
        scene.world().showSection(util.select().position(payer), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(payee), Direction.DOWN);
        scene.idle(20);

        scene.overlay().showText(80)
                .text("\u672c\u7968\uff1a\u51fa\u7968\u4eba\u81ea\u5df1\u627f\u8bfa\u4ed8\u6b3e\uff0c\u4e0d\u9760\u94f6\u884c\u62c5\u4fdd")
                .pointAt(util.vector().centerOf(payer))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(90)
                .text("\u652f\u7968\uff1a\u51fa\u7968\u4eba\u6307\u4ee4\u4ed8\u6b3e\uff0c\u89c1\u7968\u5373\u4ed8")
                .pointAt(util.vector().centerOf(payer))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showText(80)
                .text("\u4e24\u8005\u90fd\u53ef\u4ee5\u5728\u7968\u636e\u5e02\u573a\u8f6c\u8ba9")
                .pointAt(util.vector().centerOf(payee))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(70)
                .text("\u5151\u4ed8\u65f6\u8d27\u7269\u4f1a\u5bc4\u5230\u4f60\u7ed1\u5b9a\u7684\u63d0\u8d27\u70b9")
                .independent(0)
                .placeNearTarget();
        scene.idle(80);

        scene.markAsFinished();
    }
    /** 面对面交易：两人站在一起，钱货两清 */
    public static void tradeFaceToFace(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("trade_face_to_face", "两人站在一起，钱货两清");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos chest = util.grid().at(2, 1, 2);
        BlockPos seller = util.grid().at(1, 1, 2);
        BlockPos buyer = util.grid().at(3, 1, 2);

        scene.world().showSection(util.select().position(chest), Direction.DOWN);
        scene.idle(20);

        scene.overlay().showText(80)
                .text("\u4e70\u5356\u53cc\u65b9\u7ad9\u5728 8 \u683c\u5185")
                .pointAt(util.vector().centerOf(seller))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(80)
                .text("\u53ef\u4ee5\u76f4\u63a5\u5bf9\u9762\u4ea4\u6613\uff1a\u94b1\u4e00\u6263\uff0c\u8d27\u4e00\u7ed9")
                .pointAt(util.vector().centerOf(chest))
                .placeNearTarget();
        scene.idle(90);

        scene.overlay().showText(70)
                .text("\u4e0d\u9700\u8981\u7ed1\u5b9a\u63d0\u8d27\u70b9\uff0c\u4e5f\u4e0d\u7528\u7b49\u7269\u6d41")
                .independent(0)
                .placeNearTarget();
        scene.idle(80);

        scene.overlay().showText(70)
                .text("\u8ddd\u79bb\u8d85\u8fc7 8 \u683c\u5c31\u81ea\u52a8\u6539\u8d70\u7269\u6d41")
                .pointAt(util.vector().centerOf(buyer))
                .placeNearTarget();
        scene.idle(80);

        scene.markAsFinished();
    }

    /** 托管交易：挂单 → 锁单 → 发货 → 确认 */
    public static void tradeEscrow(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("trade_escrow", "托管交易：钱先押着，货到再付");
        scene.configureBasePlate(0, 0, 7);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos seller = util.grid().at(1, 1, 2);
        BlockPos chest = util.grid().at(3, 1, 2);
        BlockPos buyer = util.grid().at(5, 1, 2);
        BlockPos port = util.grid().at(5, 1, 1);

        scene.world().showSection(util.select().fromTo(1, 0, 1, 5, 2, 3), Direction.DOWN);
        scene.idle(25);

        scene.overlay().showText(80)
                .text("\u5356\u5bb6\u6302\u5355\uff1a\u5199\u660e\u7269\u54c1\u3001\u6570\u91cf\u3001\u5355\u4ef7")
                .pointAt(util.vector().centerOf(seller))
                .placeNearTarget();
        scene.idle(60);
        scene.overlay().showText(60)
                .text("\u8d27\u4ecd\u7136\u7559\u5728\u5356\u5bb6\u624b\u4e0a")
                .pointAt(util.vector().centerOf(chest))
                .placeNearTarget();
        scene.idle(65);

        scene.overlay().showText(80)
                .text("\u4e70\u5bb6\u9501\u5355\uff1a\u94b1\u4ece\u94b1\u5305\u6263\u8d70\uff0c\u8fdb\u6258\u7ba1")
                .pointAt(util.vector().centerOf(buyer))
                .placeNearTarget();
        scene.idle(60);
        scene.overlay().showText(60)
                .text("\u7cfb\u7edf\u751f\u6210\u4e00\u5f20\u5f85\u53d1\u8d27\u8ba2\u5355")
                .independent(0)
                .placeNearTarget();
        scene.idle(65);

        scene.overlay().showText(80)
                .text("\u5356\u5bb6\u53d1\u8d27\uff1a\u8d27\u6253\u5305\u6210\u5305\u88f9")
                .pointAt(util.vector().centerOf(chest))
                .placeNearTarget();
        scene.idle(60);
        scene.overlay().showText(80)
                .text("\u901a\u8fc7\u673a\u68b0\u52a8\u529b\u7269\u6d41\u9001\u5230\u4e70\u5bb6\u63d0\u8d27\u70b9")
                .pointAt(util.vector().centerOf(port))
                .placeNearTarget();
        scene.idle(65);

        scene.overlay().showText(80)
                .text("\u4e70\u5bb6\u53d6\u5230\u8d27\u540e\u786e\u8ba4\u6536\u8d27")
                .pointAt(util.vector().centerOf(buyer))
                .placeNearTarget();
        scene.idle(60);
        scene.overlay().showText(70)
                .text("\u6258\u7ba1\u6b3e\u624d\u4ed8\u7ed9\u5356\u5bb6")
                .independent(0)
                .placeNearTarget();
        scene.idle(80);

        scene.overlay().showText(90)
                .text("\u5356\u5bb6 30 \u5206\u949f\u4e0d\u53d1\u8d27\uff0c\u7cfb\u7edf\u81ea\u52a8\u53d6\u6d88\u5e76\u9000\u6b3e")
                .independent(0)
                .placeNearTarget();
        scene.idle(90);

        scene.markAsFinished();
    }

    /** 包裹走锁链：从打包机到蛙港 */
    public static void tradePackageRoute(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("trade_package_route", "\u5305\u88f9\u600e\u4e48\u4ece\u5356\u5bb6\u8d70\u5230\u86d9\u6e2f");
        scene.configureBasePlate(0, 0, 9);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos packager = util.grid().at(1, 1, 2);
        BlockPos port = util.grid().at(7, 1, 2);
        BlockPos mid = util.grid().at(4, 2, 2);

        scene.world().showSection(util.select().fromTo(1, 0, 1, 7, 3, 3), Direction.DOWN);
        scene.idle(30);

        scene.overlay().showText(70)
                .text("\u8d27\u5148\u88ab\u6253\u5305\u6210 Create \u5305\u88f9")
                .pointAt(util.vector().centerOf(packager))
                .placeNearTarget();
        scene.idle(55);

        scene.overlay().showText(60)
                .text("\u5305\u88f9\u4e0a\u5199\u7740\u6536\u4ef6\u5730\u5740")
                .pointAt(util.vector().centerOf(packager))
                .placeNearTarget();
        scene.idle(55);

        scene.overlay().showText(70)
                .text("\u5305\u88f9\u4ea4\u7ed9\u7269\u6d41\u76ee\u6807\uff0c\u6cbf\u9501\u94fe\u8fd0\u8f93")
                .pointAt(util.vector().centerOf(mid))
                .placeNearTarget();
        scene.idle(60);

        scene.overlay().showText(70)
                .text("\u5230\u8fbe\u86d9\u6e2f\u540e\uff0c\u5305\u88f9\u8fdb\u86d9\u6e2f\u7684\u5e93\u5b58")
                .pointAt(util.vector().centerOf(port))
                .placeNearTarget();
        scene.idle(55);

        scene.overlay().showText(60)
                .text("\u6536\u4ef6\u4eba\u6253\u5f00\u86d9\u6e2f\u5c31\u80fd\u53d6\u8d70")
                .pointAt(util.vector().centerOf(port))
                .placeNearTarget();
        scene.idle(60);

        scene.overlay().showText(80)
                .text("\u63d0\u8d27\u70b9\u6240\u5728\u533a\u5757\u6ca1\u52a0\u8f7d\uff0c\u5305\u88f9\u4f1a\u6392\u961f\u7b49")
                .independent(0)
                .placeNearTarget();
        scene.idle(85);

        scene.markAsFinished();
    }
}
