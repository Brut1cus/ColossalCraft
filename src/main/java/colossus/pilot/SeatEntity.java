package colossus.pilot;

import colossus.core.ColossusConfig;
import colossus.core.ColossusManager;
import colossus.core.ColossusRegistries;
import colossus.physics.math.Vec3;
import colossus.shipyard.ColossusRecord;
import colossus.shipyard.ColossusSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The rideable part of a Pilot Seat. Invisible, never saved: the Colossus re-creates one for every Pilot Seat block
 * when it spawns, and each tick moves it to its block's live position (the block itself sits in ship space, out of
 * reach, so this entity is what players click and ride). Before assembly, sitting on a Pilot Seat block makes a
 * temporary one that the Colossus adopts when it is assembled and that vanishes when left empty.
 * <p>
 * Whoever rides it steers: the client sends {@link PilotInput} at 20 Hz; if input stops arriving the walker stops.
 */
public class SeatEntity extends Entity {
    /** Ticks without pilot input after which the machine is stopped. */
    static final int INPUT_TIMEOUT = 20;

    private @Nullable UUID colossus;
    private int segment = -1;
    private long cell;
    private @Nullable BlockPos block;
    private int ticksSinceInput;
    private boolean commanding;

    public SeatEntity(EntityType<? extends SeatEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /** A temporary seat on an unassembled Pilot Seat block. */
    public static SeatEntity onBlock(ServerLevel level, BlockPos pos) {
        SeatEntity e = new SeatEntity(ColossusRegistries.SEAT.get(), level);
        e.block = pos.immutable();
        e.setPos(pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5);
        return e;
    }

    /** Ties this seat to a Pilot Seat cell of an assembled Colossus. */
    public void link(UUID colossus, int segment, long cell) {
        this.colossus = colossus;
        this.segment = segment;
        this.cell = cell;
        this.block = null;
    }

    public @Nullable UUID colossus() { return colossus; }

    public @Nullable BlockPos block() { return block; }

    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel sl)) return;
        if (colossus != null) {
            ColossusRecord r = ColossusSavedData.get(sl).byId(colossus);
            if (r == null || segment >= r.segments.size()) { discard(); return; } // disassembled
            ColossusRecord.SegmentRecord s = r.segments.get(segment);
            Vec3 at = ColossusManager.get(sl).pose(r, segment).toWorld(s.local(BlockPos.of(cell)));
            setPos(at.x(), at.y() - 0.25, at.z());
            // stop the machine if the pilot left or went quiet
            if (++ticksSinceInput > INPUT_TIMEOUT && commanding) {
                ColossusManager.get(sl).command(colossus, 0, 0, 0);
                commanding = false;
            }
        } else if (block == null || !sl.getBlockState(block).is(ColossusRegistries.PILOT_SEAT.get()) || !isVehicle()) {
            discard(); // a temporary seat whose block is gone or that nobody sits in
        }
    }

    /** Pilot input from the rider: forward and turn in −1..1 (positive turn = left). */
    void steer(float forward, float turn) {
        ticksSinceInput = 0;
        if (colossus == null || !(level() instanceof ServerLevel sl)) return;
        double speed = ColossusConfig.WALKER_MAX_SPEED.get(), turnRate = ColossusConfig.WALKER_TURN_RATE.get();
        ColossusManager.get(sl).command(colossus, clamp(forward) * speed, 0, clamp(turn) * turnRate);
        commanding = forward != 0 || turn != 0;
    }

    private static double clamp(float v) { return Math.max(-1, Math.min(1, v)); }

    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (colossus != null && level() instanceof ServerLevel sl && !isVehicle()) {
            ColossusManager.get(sl).command(colossus, 0, 0, 0);
            commanding = false;
        }
    }

    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isSecondaryUseActive() || isVehicle()) return InteractionResult.PASS;
        if (!level().isClientSide) player.startRiding(this);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override public boolean isPickable() { return !isRemoved(); }

    @Override public boolean canBeCollidedWith() { return false; }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    // never saved (the entity type is noSave): a Colossus makes fresh seats when it spawns
    @Override protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override protected void addAdditionalSaveData(CompoundTag tag) {}
}
