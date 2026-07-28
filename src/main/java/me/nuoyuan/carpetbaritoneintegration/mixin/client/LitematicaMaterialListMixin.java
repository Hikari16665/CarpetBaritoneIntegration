package me.nuoyuan.carpetbaritoneintegration.mixin.client;

import me.nuoyuan.carpetbaritoneintegration.client.LitematicaCollectBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.gui.GuiMaterialList",
        remap = false)
abstract class LitematicaMaterialListMixin {
    @Inject(method = "initGui", at = @At("TAIL"), require = 0,
            remap = false)
    private void cbi$addCollectAll(CallbackInfo ci) {
        LitematicaCollectBridge.addCollectAllButton(this);
    }
}
