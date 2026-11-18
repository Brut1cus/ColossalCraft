package com.colossalcraft.mixin;

import com.colossalcraft.colossus.ColossusEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A pilot only picks up items near their own body, not everything under the whole Colossus they ride. */
@Mixin(Player.class)
public abstract class PilotPickupMixin {
    @Redirect(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;minmax(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/AABB;"))
    private AABB colossalcraft$pilotReach(AABB own, AABB vehicle) {
        return ((Player) (Object) this).getVehicle() instanceof ColossusEntity ? own : own.minmax(vehicle);
    }
}
