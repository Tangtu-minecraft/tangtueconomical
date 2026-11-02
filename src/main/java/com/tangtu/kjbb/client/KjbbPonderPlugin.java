package com.tangtu.kjbb.client;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/** 票据系统的思考（Ponder）教程 */
public class KjbbPonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return "kjbankbridge";
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        // 暂时关闭所有思索场景（代码保留在 KjbbPonderScenes 里，需要时取消注释即可）
        //
        // helper.addStoryBoard(id("blank_form"), "blank_form", KjbbPonderScenes::blankForm, id("tickets"));
        // helper.addStoryBoard(id("bill_of_lading"), "bill", KjbbPonderScenes::billOfLading, id("tickets"));
        // helper.addStoryBoard(id("store_receipt"), "market", KjbbPonderScenes::market, id("tickets"));
        // helper.addStoryBoard(id("address_tag"), "address_key", KjbbPonderScenes::addressKey, id("tickets"));
        // helper.addStoryBoard(id("labor_contract"), "labor_contract", KjbbPonderScenes::laborContract, id("tickets"));
        // helper.addStoryBoard(id("promissory_note"), "promissory_note", KjbbPonderScenes::promissoryNote, id("tickets"));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kjbankbridge", path);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(ResourceLocation.fromNamespaceAndPath("kjbankbridge", "tickets"))
                .addToIndex()
                .title("票据与市场")
                .description("仓单、提单、订单该怎么用")
                .register();
    }
}
