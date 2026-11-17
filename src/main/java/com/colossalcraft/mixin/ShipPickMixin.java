package com.colossalcraft.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After the game finds what the crosshair points at: a nearer block on a Colossus wins (as its real dock block). */
@Mixin(GameRenderer.class)
public abstract class ShipPickMixin {
    @Inject(method = "pick(F)V", at = @At("TAIL"))
    private void colossalcraft$pickShip(float pt, CallbackInfo ci) { com.colossalcraft.client.ColossusLook.pickOverride(); }
}
