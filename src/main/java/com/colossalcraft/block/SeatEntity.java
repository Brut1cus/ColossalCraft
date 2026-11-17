package com.colossalcraft.block;

import com.colossalcraft.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Invisible seat so players can sit in a Pilot Throne that is not part of a Colossus yet. */
public class SeatEntity extends Entity {
    public SeatEntity(EntityType<?> t, Level l) { super(t, l); noPhysics = true; }

    public static void sit(Level level, BlockPos pos, Player p) {
        if (!level.getEntitiesOfClass(SeatEntity.class, new net.minecraft.world.phys.AABB(pos)).isEmpty()) return;
        SeatEntity s = new SeatEntity(ModRegistry.SEAT.get(), level);
        s.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5); // inside the chair block, so it knows its chair

        level.addFreshEntity(s);
        p.startRiding(s, true);
    }
    /** Feet height of a seated rider above the chair block's bottom. */
    public static final double SEAT_HEIGHT = -0.1;

    @Override public void tick() {
        super.tick();
        if (!level().isClientSide && (getPassengers().isEmpty() || !(level().getBlockState(blockPosition()).getBlock() instanceof SimpleParts.PilotSeatBlock))) discard();
    }
    @Override protected void positionRider(Entity p, MoveFunction f) { if (hasPassenger(p)) f.accept(p, getX(), Math.floor(getY()) + SEAT_HEIGHT, getZ()); }
    /** Getting up: stand on top of the chair (never inside the block under it). */
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity p) { return new Vec3(getX(), Math.floor(getY()) + 1.05, getZ()); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {}
    @Override protected void readAdditionalSaveData(CompoundTag t) {}
    @Override protected void addAdditionalSaveData(CompoundTag t) {}
    @Override public boolean shouldBeSaved() { return false; }
}
