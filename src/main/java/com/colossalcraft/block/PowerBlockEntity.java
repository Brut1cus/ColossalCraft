package com.colossalcraft.block;

import com.colossalcraft.ModRegistry;
import com.colossalcraft.tier.EngineTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/** Shared block entity for engines and multiblock controllers (engines and gyros). */
public class PowerBlockEntity extends BlockEntity {
    public long energy;
    public boolean primed;   // zero-point
    public boolean formed;
    public String status = "";

    public PowerBlockEntity(BlockPos pos, BlockState state) { super(ModRegistry.POWER_BE.get(), pos, state); }

    @Nullable public EngineTier tier() {
        if (getBlockState().getBlock() instanceof PowerBlock pb) return pb.tier;
        if (getBlockState().getBlock() instanceof ControllerBlock cb) return cb.engine;
        return null;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, PowerBlockEntity be) {
        if (!(state.getBlock() instanceof ControllerBlock) || level.getGameTime() % 20 != 0) return;
        ControllerBlock c = (ControllerBlock) state.getBlock();
        String msg = c.def.validate(level, pos, state.getValue(ControllerBlock.FACING));
        boolean f = msg == null;
        be.status = f ? "Formed" : msg;
        if (f != be.formed || state.getValue(ControllerBlock.FORMED) != f) {
            be.formed = f;
            level.setBlock(pos, state.setValue(ControllerBlock.FORMED, f), 3);
            be.setChanged();
        }
    }

    /** Try to add one fuel item. Returns true if used. */
    public boolean addFuel(ItemStack stack, boolean simulate) {
        EngineTier t = tier();
        if (t == null || stack.isEmpty()) return false;
        long v = t.fuelValue(stack);
        if (v == -1) { if (primed) return false; if (!simulate) { primed = true; setChanged(); } return true; }
        if (v <= 0 || energy + v > t.capacity) return false;
        if (!simulate) { energy += v; setChanged(); }
        return true;
    }

    public void describe(Player p) {
        EngineTier t = tier();
        StringBuilder sb = new StringBuilder();
        if (t != null) {
            sb.append(t.title).append(": ");
            if (t == EngineTier.ZERO_POINT) sb.append(primed ? "PRIMED (infinite)" : "not primed");
            else sb.append(String.format("%,d / %,d CJ", energy, t.capacity));
            sb.append(" | ").append(t.output).append(" CJ/t | fuel: ").append(t.fuelHint());
        }
        if (getBlockState().getBlock() instanceof ControllerBlock) sb.append(t != null ? " | " : "").append(formed ? "Structure OK" : status.isEmpty() ? "Checking..." : status);
        p.displayClientMessage(Component.literal(sb.toString()), false);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.saveAdditional(tag, reg);
        tag.putLong("Energy", energy); tag.putBoolean("Primed", primed); tag.putBoolean("Formed", formed);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.loadAdditional(tag, reg);
        energy = tag.getLong("Energy"); primed = tag.getBoolean("Primed"); formed = tag.getBoolean("Formed");
    }

    /** Hopper/pipe input: accepts valid fuel only, burns it straight into the buffer. */
    public final IItemHandler fuelHandler = new IItemHandler() {
        public int getSlots() { return 1; }
        public ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || !addFuel(stack, true)) return stack;
            if (!simulate) addFuel(stack, false);
            ItemStack r = stack.copy(); r.shrink(1); return r;
        }
        public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        public int getSlotLimit(int slot) { return 64; }
        public boolean isItemValid(int slot, ItemStack stack) { return addFuel(stack, true); }
    };
}
