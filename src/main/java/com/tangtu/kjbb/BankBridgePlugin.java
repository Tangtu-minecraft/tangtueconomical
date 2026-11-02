package com.tangtu.kjbb;

import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;

/**
 * 注册给 KubeJS：脚本里直接用 BankBridge.xxx(...)
 *
 * KubeJS 通过 classpath 上的 kubejs.plugins.txt 发现这个类。
 */
public class BankBridgePlugin implements KubeJSPlugin {

    @Override
    public void registerBindings(BindingRegistry registry) {
        registry.add("BankBridge", BankBridge.class);
        registry.add("MarketBridge", MarketBridge.class);
        registry.add("ShopBridge", ShopBridge.class);
    }
}
