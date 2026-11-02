package com.tangtu.kjbb.mixin;

import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 只改发报机界面的按钮文案：出票模式 → 「下单」，否则保持原版「发送」 */
@Mixin(Language.class)
public class LanguageMixin {

    @Inject(method = "getOrDefault(Ljava/lang/String;)Ljava/lang/String;",
            at = @At("HEAD"), cancellable = true)
    private void kjbb$billButtonLabel(String key, CallbackInfoReturnable<String> cir) {
        if ("create.gui.stock_keeper.send".equals(key)
                && com.tangtu.kjbb.client.BillModeClient.active()) {
            cir.setReturnValue("下单");
        }
    }
}
