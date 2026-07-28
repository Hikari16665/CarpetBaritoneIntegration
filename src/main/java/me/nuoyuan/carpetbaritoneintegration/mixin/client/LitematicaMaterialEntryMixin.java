package me.nuoyuan.carpetbaritoneintegration.mixin.client;

import me.nuoyuan.carpetbaritoneintegration.client.LitematicaCollectBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets =
        "fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry",
        remap = false)
abstract class LitematicaMaterialEntryMixin {
    @Inject(method = "<init>", at = @At("TAIL"), require = 0,
            remap = false)
    private void cbi$addCollectButton(CallbackInfo ci) {
        LitematicaCollectBridge.addEntryButton(this);
    }
}
