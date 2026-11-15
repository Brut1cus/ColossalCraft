package com.colossalcraft.colossus;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Lets normal entities stand on, walk inside and bump into Colossi (hooked into Entity.collide by a mixin). */
public final class ColossusCollision {
    public static final Set<ColossusEntity> LOADED = ConcurrentHashMap.newKeySet();

    public static List<VoxelShape> withColossi(Level level, Entity self, AABB query, List<VoxelShape> base) {
        if (LOADED.isEmpty() || self instanceof ColossusEntity || self.getVehicle() instanceof ColossusEntity) return base;
        List<VoxelShape> out = null;
        for (ColossusEntity c : LOADED) {
            if (c.level() != level || c.isRemoved() || c.model == null || !c.getBoundingBox().intersects(query.inflate(1))) continue;
            for (AABB b : c.boxesNear(query)) {
                if (out == null) out = new ArrayList<>(base);
                out.add(Shapes.create(b));
            }
        }
        return out == null ? base : out;
    }
}
