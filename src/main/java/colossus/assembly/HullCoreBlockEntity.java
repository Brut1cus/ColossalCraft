package colossus.assembly;

import colossus.core.ColossusRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Hull Core data. Once assembled, the core block (with this block entity) lives in ship space and remembers which
 * Colossus it runs. Brain state, control bindings and rigging move here in later phases.
 */
public class HullCoreBlockEntity extends BlockEntity {
    private UUID colossusId;

    public HullCoreBlockEntity(BlockPos pos, BlockState state) { super(ColossusRegistries.HULL_CORE_BE.get(), pos, state); }

    public UUID colossusId() { return colossusId; }

    public void setColossusId(UUID id) { colossusId = id; setChanged(); }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (colossusId != null) tag.putUUID("Colossus", colossusId);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        colossusId = tag.hasUUID("Colossus") ? tag.getUUID("Colossus") : null;
    }
}
