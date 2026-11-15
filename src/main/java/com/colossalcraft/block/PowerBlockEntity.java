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
public class PowerBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider {
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

    /** Fuel slot (GUI, hoppers, pipes). Each tick one item is burned into stored energy when it fits. */
    public final net.neoforged.neoforge.items.ItemStackHandler fuel = new net.neoforged.neoforge.items.ItemStackHandler(1) {
        @Override public boolean isItemValid(int slot, ItemStack stack) { EngineTier t = tier(); return t != null && t.fuelValue(stack) != 0; }
        @Override protected void onContentsChanged(int slot) { setChanged(); }
        /** Pipes/hoppers may only pull out leftovers (empty buckets), never fuel. */
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return isItemValid(slot, getStackInSlot(slot)) ? ItemStack.EMPTY : super.extractItem(slot, amount, simulate); }
    };

    public static void serverTick(Level level, BlockPos pos, BlockState state, PowerBlockEntity be) {
        ItemStack st = be.fuel.getStackInSlot(0);
        if (!st.isEmpty() && be.addFuel(st, true)) {
            be.addFuel(st, false);
            ItemStack leftover = st.getCraftingRemainingItem(); // lava bucket -> empty bucket
            ItemStack now = st.copy(); now.shrink(1);
            be.fuel.setStackInSlot(0, now.isEmpty() ? leftover.copy() : now);
        }
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
        tag.putLong("Energy", energy); tag.putBoolean("Primed", primed); tag.putBoolean("Formed", formed); tag.put("Fuel", fuel.serializeNBT(reg));
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.loadAdditional(tag, reg);
        energy = tag.getLong("Energy"); primed = tag.getBoolean("Primed"); formed = tag.getBoolean("Formed");
        if (tag.contains("Fuel")) fuel.deserializeNBT(reg, tag.getCompound("Fuel"));
    }

    @Override public net.minecraft.network.chat.Component getDisplayName() { return getBlockState().getBlock().getName(); }
    @Override public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inv, Player p) { return new EngineMenu(id, inv, this); }
}
