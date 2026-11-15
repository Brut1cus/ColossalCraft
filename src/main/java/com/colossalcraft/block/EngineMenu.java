package com.colossalcraft.block;

import com.colossalcraft.ModRegistry;
import com.colossalcraft.tier.EngineTier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;

/** Furnace-like engine screen: one fuel slot, stored energy, output. */
public class EngineMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    public final EngineTier tier;
    public final boolean multiblock;
    private final ContainerData data;
    private final PowerBlockEntity be;

    public EngineMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) { this(id, inv, (PowerBlockEntity) inv.player.level().getBlockEntity(buf.readBlockPos())); }

    public EngineMenu(int id, Inventory inv, PowerBlockEntity be) {
        super(ModRegistry.ENGINE_MENU.get(), id);
        this.be = be; this.pos = be.getBlockPos(); this.tier = be.tier();
        this.multiblock = be.getBlockState().getBlock() instanceof ControllerBlock;
        this.data = inv.player.level().isClientSide ? new SimpleContainerData(6) : new ContainerData() {
            public int get(int i) {
                return switch (i) { case 0, 1, 2, 3 -> (int) ((be.energy >>> (16 * i)) & 0xFFFF); case 4 -> be.formed ? 1 : 0; case 5 -> be.primed ? 1 : 0; default -> 0; };
            }
            public void set(int i, int v) {}
            public int getCount() { return 6; }
        };
        addSlot(new SlotItemHandler(be.fuel, 0, 26, 36));
        for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 84 + r * 18));
        for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c, 8 + c * 18, 142));
        addDataSlots(data);
    }

    public long energy() { long e = 0; for (int i = 0; i < 4; i++) e |= ((long) (data.get(i) & 0xFFFF)) << (16 * i); return e; }
    public boolean formed() { return data.get(4) == 1; }
    public boolean primed() { return data.get(5) == 1; }

    @Override public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack st = slot.getItem(), copy = st.copy();
        if (index == 0) { if (!moveItemStackTo(st, 1, 37, true)) return ItemStack.EMPTY; }
        else if (!moveItemStackTo(st, 0, 1, false)) return ItemStack.EMPTY;
        if (st.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    @Override public boolean stillValid(Player p) { return !be.isRemoved() && p.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) < 64; }
}
