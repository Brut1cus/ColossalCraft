package com.colossalcraft.compat.create;

import com.colossalcraft.ColossalCraft;
import com.colossalcraft.ModRegistry;
import com.colossalcraft.block.ColossalBattery;
import com.colossalcraft.colossus.StructureScan;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/** Create support: Kinetic Dynamo (rotation -> Colossus power) and safe moving of Create machines. */
public final class CreateCompat {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ColossalCraft.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ColossalCraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ColossalCraft.MODID);

    public static final DeferredBlock<DynamoBlock> KINETIC_DYNAMO = BLOCKS.register("kinetic_dynamo", () -> new DynamoBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredItem<BlockItem> KINETIC_DYNAMO_ITEM = ITEMS.registerSimpleBlockItem("kinetic_dynamo", KINETIC_DYNAMO);
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DynamoBE>> DYNAMO_BE = BES.register("kinetic_dynamo",
            () -> BlockEntityType.Builder.of(DynamoBE::new, KINETIC_DYNAMO.get()).build(null));

    /** Stress units the dynamo uses per RPM. */
    public static final double IMPACT = 8.0;

    public static void init(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); BES.register(bus);
        ModRegistry.ALL_ITEMS.add(KINETIC_DYNAMO_ITEM);
        bus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> BlockStressValues.IMPACTS.register(KINETIC_DYNAMO.get(), () -> IMPACT)));
        ColossalCraft.LOG.info("ColossalCraft: Create support on");
    }

    /** Strip live network data so the machine re-joins a network cleanly after landing. */
    public static void onLift(BlockEntity be, CompoundTag tag) {
        if (be instanceof KineticBlockEntity) { tag.remove("Speed"); tag.remove("Network"); tag.remove("Source"); tag.remove("NeedsSpeedUpdate"); }
    }

    public static void onLand(Level level, BlockPos pos, BlockEntity be) {
        if (be instanceof KineticBlockEntity kbe && !level.isClientSide) {
            try { kbe.clearKineticInformation(); RotationPropagator.handleAdded(level, pos, kbe); } catch (Exception e) { ColossalCraft.LOG.warn("Create re-link failed at {}", pos, e); }
        }
    }

    // ------------------------------------------------------------------ Kinetic Dynamo
    public static class DynamoBlock extends RotatedPillarKineticBlock implements IBE<DynamoBE>, ColossalBattery {
        public DynamoBlock(Properties p) { super(p); }
        @Override public Direction.Axis getRotationAxis(BlockState s) { return s.getValue(AXIS); }
        @Override public boolean hasShaftTowards(LevelReader l, BlockPos p, BlockState s, Direction f) { return f.getAxis() == s.getValue(AXIS); }
        @Override public Class<DynamoBE> getBlockEntityClass() { return DynamoBE.class; }
        @Override public BlockEntityType<? extends DynamoBE> getBlockEntityType() { return DYNAMO_BE.get(); }
        @Override public long capacity() { return DynamoBE.CAPACITY; }
        @Override public int output() { return 800; }
        @Override public String title() { return "Kinetic Dynamo"; }
        @Override protected InteractionResult useWithoutItem(BlockState s, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof DynamoBE be)
                player.displayClientMessage(Component.literal(String.format("Kinetic Dynamo: %s / %s CJ, %.0f RPM -> %.0f CJ/t",
                        StructureScan.kilo(be.energy), StructureScan.kilo(DynamoBE.CAPACITY), Math.abs(be.getSpeed()), DynamoBE.rate(be.getSpeed()))), true);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    public static class DynamoBE extends KineticBlockEntity {
        public static final long CAPACITY = 8_000_000;
        public long energy;
        public DynamoBE(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
        public DynamoBE(BlockPos pos, BlockState state) { this(DYNAMO_BE.get(), pos, state); }
        /** CJ per tick from rotation: 0.5 CJ/t per RPM (256 RPM = 128 CJ/t). */
        public static double rate(float speed) { return Math.abs(speed) * 0.5; }

        @Override public void tick() {
            super.tick();
            if (level == null || level.isClientSide || isOverStressed()) return;
            long add = (long) rate(getSpeed());
            if (add > 0 && energy < CAPACITY) { energy = Math.min(CAPACITY, energy + add); setChanged(); }
        }
        @Override protected void write(CompoundTag t, HolderLookup.Provider r, boolean client) { super.write(t, r, client); t.putLong("Energy", energy); }
        @Override protected void read(CompoundTag t, HolderLookup.Provider r, boolean client) { super.read(t, r, client); energy = t.getLong("Energy"); }
        @Override public boolean addToGoggleTooltip(List<Component> tip, boolean sneaking) {
            super.addToGoggleTooltip(tip, sneaking);
            tip.add(Component.literal("    Colossus power: " + StructureScan.kilo(energy) + " CJ"));
            return true;
        }
    }
}
