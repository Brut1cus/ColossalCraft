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
    /** Loaded Colossi, one set per side. (Entities compare equal by id, and in single player both sides share ids.) */
    private static final Set<ColossusEntity> SERVER = ConcurrentHashMap.newKeySet(), CLIENT = ConcurrentHashMap.newKeySet();
    public static Set<ColossusEntity> all(Level level) { return level.isClientSide ? CLIENT : SERVER; }
    public static Set<ColossusEntity> all(boolean client) { return client ? CLIENT : SERVER; }

    public static List<VoxelShape> withColossi(Level level, Entity self, AABB query, List<VoxelShape> base) {
        Set<ColossusEntity> loaded = all(level);
        loaded.removeIf(Entity::isRemoved);
        if (loaded.isEmpty() || self instanceof ColossusEntity || self.getVehicle() instanceof ColossusEntity) return base;
        List<VoxelShape> out = null;
        for (ColossusEntity c : loaded) {
            if (c.level() != level || c.isRemoved() || c.model == null || !c.getBoundingBox().intersects(query.inflate(1))) continue;
            for (AABB b : c.boxesNear(query)) {
                if (out == null) out = new ArrayList<>(base);
                out.add(Shapes.create(b));
            }
        }
        return out == null ? base : out;
    }
}
