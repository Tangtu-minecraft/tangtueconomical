package com.tangtu.kjbb.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.component.DataComponentType;

/** 物品与数据组件注册 */
public final class KjbbItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems("kjbankbridge");

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, "kjbankbridge");

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "kjbankbridge");

    /** 票面数据 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BillData>> BILL_DATA =
            COMPONENTS.register("bill_data", () -> DataComponentType.<BillData>builder()
                    .persistent(BillData.CODEC)
                    .networkSynchronized(BillData.STREAM_CODEC)
                    .build());

    /** 空白单据 */
    public static final DeferredItem<Item> BLANK_FORM = ITEMS.register(
            "blank_form", () -> new BlankFormItem(new Item.Properties().stacksTo(16)));

    /** 提单 */
    /** 仓单：只记录容器里有什么 */
    public static final DeferredItem<Item> STORE_RECEIPT = ITEMS.register(
            "store_receipt", () -> new com.tangtu.kjbb.item.StoreReceiptItem(
                    new Item.Properties().stacksTo(1)));
    
    public static final DeferredItem<Item> BILL = ITEMS.register(
            "bill_of_lading", () -> new BillItem(new Item.Properties().stacksTo(1)));

    /** 假人劳动合同：右键地面雇佣一个假人 */
    public static final DeferredItem<Item> LABOR_CONTRACT = ITEMS.register(
            "labor_contract", () -> new com.tangtu.kjbb.item.LaborContractItem(
                    new Item.Properties().stacksTo(16)));

    /** 本票：出票人自己承诺付款（无银行担保） */
    public static final DeferredItem<Item> PROMISSORY_NOTE = ITEMS.register(
            "promissory_note", () -> new com.tangtu.kjbb.item.PromissoryNoteItem(
                    new Item.Properties().stacksTo(1)));

    /** 支票：出票人指令付款（见票即付） */
    public static final DeferredItem<Item> CHECK = ITEMS.register(
            "check", () -> new com.tangtu.kjbb.item.CheckItem(
                    new Item.Properties().stacksTo(1)));

    /** 股票：远洋提单份额凭证（图标与提单/仓单一致） */
    public static final DeferredItem<Item> STOCK = ITEMS.register(
            "stock", () -> new com.tangtu.kjbb.item.StockItem(
                    new Item.Properties().stacksTo(16)));

    /** 地址铭牌：右键方块绑定提货点 */
    public static final DeferredItem<Item> ADDRESS_TAG = ITEMS.register(
            "address_tag", () -> new com.tangtu.kjbb.item.AddressTagItem(
                    new Item.Properties().stacksTo(1)));

    /** 创造模式物品栏 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.kjbankbridge"))
                    .icon(() -> new ItemStack(BLANK_FORM.get()))
                    .displayItems((params, output) -> {
                        output.accept(BLANK_FORM.get());
                        output.accept(BILL.get());
                        output.accept(STORE_RECEIPT.get());
                        output.accept(STOCK.get());
                        output.accept(PROMISSORY_NOTE.get());
                        output.accept(CHECK.get());
                        output.accept(LABOR_CONTRACT.get());
                        output.accept(ADDRESS_TAG.get());
                    })
                    .build());

    private KjbbItems() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        COMPONENTS.register(bus);
        TABS.register(bus);
    }

    public static BillData getBill(ItemStack stack) {
        return stack.get(BILL_DATA.get());
    }

    public static void setBill(ItemStack stack, BillData data) {
        stack.set(BILL_DATA.get(), data);
    }
}