package com.colosalcraft.mixin;

import com.colosalcraft.colossus.ColossusCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** Adds Colossus blocks as solid shapes when entities move, so you can walk on and inside them. */
@Mixin(Entity.class)
public abstract class EntityCollideMixin {
    @Redirect(method = "collide", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> colosalcraft$collisions(Level level, Entity self, AABB box) {
        return ColossusCollision.withColossi(level, self, box, level.getEntityCollisions(self, box));
    }
}
