package com.colossalcraft.mixin;

import com.colossalcraft.colossus.ColossusDock;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A block entity in the dock changed: the Colossus copies its data (for the screen, for clients, for saving). */
@Mixin(BlockEntity.class)
public abstract class DockBlockEntityMixin {
    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void colossalcraft$dockBe(CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        if (ColossusDock.isDock(be.getLevel())) ColossusDock.beChanged(be.getBlockPos());
    }
}
