package com.colossalcraft;

import com.colossalcraft.block.*;
import com.colossalcraft.block.SimpleParts.*;
import com.colossalcraft.colossus.ColossusEntity;
import com.colossalcraft.multiblock.MultiblockDef;
import com.colossalcraft.tier.EngineTier;
import com.colossalcraft.tier.MaterialTier;
import com.colossalcraft.tier.ThrusterTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.*;
import java.util.function.Function;

public final class ModRegistry {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ColossalCraft.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ColossalCraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ColossalCraft.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, ColossalCraft.MODID);
    public static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ColossalCraft.MODID);
    public static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<EngineMenu>> ENGINE_MENU =
            MENUS.register("engine", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(EngineMenu::new));
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ColossalCraft.MODID);

    public static final List<DeferredBlock<?>> ALL_BLOCKS = new ArrayList<>();
    public static final List<DeferredItem<?>> ALL_ITEMS = new ArrayList<>();
    /** Creative tabs. */
    public enum Cat { COMMAND, JOINTS, POWER, PROPULSION, MATERIALS }
    public static final Map<Cat, List<DeferredItem<?>>> BY_CAT = new EnumMap<>(Cat.class);
    static { for (Cat c : Cat.values()) BY_CAT.put(c, new ArrayList<>()); }
    private static Cat cat = Cat.COMMAND;
    public static void addToTab(Cat c, DeferredItem<?> i) { BY_CAT.get(c).add(i); }

    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4f, 8f).sound(SoundType.METAL).requiresCorrectToolForDrops();
    }
    private static <B extends Block> DeferredBlock<B> block(String name, Function<BlockBehaviour.Properties, B> f, BlockBehaviour.Properties p) {
        DeferredBlock<B> b = BLOCKS.register(name, () -> f.apply(p));
        DeferredItem<?> it = ITEMS.registerSimpleBlockItem(name, b); ALL_ITEMS.add(it); BY_CAT.get(cat).add(it);
        ALL_BLOCKS.add(b);
        return b;
    }
    private static DeferredItem<Item> item(String name) {
        DeferredItem<Item> i = ITEMS.registerSimpleItem(name); ALL_ITEMS.add(i); BY_CAT.get(cat).add(i); return i;
    }

    // ---- control ----
    static { cat = Cat.COMMAND; }
    public static final DeferredBlock<HullCoreBlock> HULL_CORE = block("hull_core", HullCoreBlock::new, metal().lightLevel(s -> 7));
    public static final DeferredBlock<PilotSeatBlock> PILOT_SEAT = block("pilot_seat", PilotSeatBlock::new, metal().noOcclusion());
    public static final DeferredBlock<ScaffoldBlock> GANTRY_SCAFFOLD = block("gantry_scaffold", ScaffoldBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).instabreak().sound(SoundType.SCAFFOLDING).noOcclusion());

    // ---- joints & motors (5 material levels) ----
    /** Full hollow bearing joints (one per material). */
    public static final Map<MaterialTier, DeferredBlock<JointBlock>> JOINTS = new EnumMap<>(MaterialTier.class);
    /** Every joint: material -> shape -> block. */
    public static final Map<MaterialTier, Map<JointShape, DeferredBlock<JointBlock>>> JOINT_SHAPES = new EnumMap<>(MaterialTier.class);
    public static final Map<MaterialTier, DeferredBlock<MotorBlock>> MOTORS = new EnumMap<>(MaterialTier.class);
    public static final List<DeferredBlock<GearboxBlock>> GEARBOXES = new ArrayList<>();
    static {
        cat = Cat.JOINTS;
        for (MaterialTier t : MaterialTier.values()) {
            Map<JointShape, DeferredBlock<JointBlock>> m = new EnumMap<>(JointShape.class);
            for (JointShape sh : JointShape.values()) m.put(sh, block(t.id + "_" + sh.suffix, p -> sh == JointShape.SLAB ? new SlabJointBlock(t, p) : sh == JointShape.PANEL ? new PanelJointBlock(t, p) : new JointBlock(t, sh, p), metal().noOcclusion()));
            JOINT_SHAPES.put(t, m); JOINTS.put(t, m.get(JointShape.FULL));
        }
        for (MaterialTier t : MaterialTier.values()) MOTORS.put(t, block(t.id + "_motor", p -> new MotorBlock(t, p), metal()));
        GEARBOXES.add(block("overdrive_gearbox", p -> new GearboxBlock(0.5, 0.95, "1:2 speed-up", p), metal()));
        GEARBOXES.add(block("spur_gearbox", p -> new GearboxBlock(2, 0.95, "2:1", p), metal()));
        GEARBOXES.add(block("planetary_gearbox", p -> new GearboxBlock(5, 0.9, "5:1", p), metal()));
        GEARBOXES.add(block("worm_drive", p -> new GearboxBlock(20, 0.6, "20:1", p), metal()));
        cat = Cat.POWER;
    }

    // ---- single block engines ----
    public static final DeferredBlock<PowerBlock> EMBER_BOILER = block("ember_boiler", p -> new PowerBlock(EngineTier.EMBER, p), metal().lightLevel(s -> 6));
    public static final DeferredBlock<PowerBlock> BLAZE_COMBUSTOR = block("blaze_combustor", p -> new PowerBlock(EngineTier.BLAZE, p), metal().lightLevel(s -> 9));
    public static final DeferredBlock<PowerBlock> REDSTONE_DYNAMO = block("redstone_dynamo", p -> new PowerBlock(EngineTier.REDSTONE, p), metal().lightLevel(s -> 5));

    // ---- multiblock parts ----
    public static final DeferredBlock<Block> VOID_CASING = block("void_casing", Block::new, metal());
    public static final DeferredBlock<Block> ENDER_COIL = block("ender_coil", Block::new, metal().lightLevel(s -> 8));
    public static final DeferredBlock<ControllerBlock> ION_DRIVE = block("ion_drive", p -> new ControllerBlock(MultiblockDef.ION_DRIVE, EngineTier.ION, 0, p), metal());
    public static final DeferredBlock<Block> LEADLINED_CASING = block("leadlined_casing", Block::new, metal());
    public static final DeferredBlock<Block> FUEL_ROD_ASSEMBLY = block("fuel_rod_assembly", Block::new, metal().lightLevel(s -> 10));
    public static final DeferredBlock<ControllerBlock> FISSION_REACTOR = block("fission_reactor", p -> new ControllerBlock(MultiblockDef.FISSION, EngineTier.FISSION, 0, p), metal());
    public static final DeferredBlock<Block> CONTAINMENT_CASING = block("containment_casing", Block::new, metal());
    public static final DeferredBlock<Block> PLASMA_COIL = block("plasma_coil", Block::new, metal().lightLevel(s -> 12));
    public static final DeferredBlock<ControllerBlock> FUSION_TORUS = block("fusion_torus", p -> new ControllerBlock(MultiblockDef.FUSION, EngineTier.FUSION, 0, p), metal());
    public static final DeferredBlock<Block> SINGULARITY_CASING = block("singularity_casing", Block::new, metal().strength(8f, 1200f));
    public static final DeferredBlock<Block> VACUUM_LATTICE = block("vacuum_lattice", Block::new, metal().noOcclusion().lightLevel(s -> 6));
    public static final DeferredBlock<Block> NULL_SINGULARITY = block("null_singularity", Block::new, metal().lightLevel(s -> 15));
    public static final DeferredBlock<ControllerBlock> ZERO_POINT_CORE = block("zero_point_core", p -> new ControllerBlock(MultiblockDef.ZERO_POINT, EngineTier.ZERO_POINT, 0, p), metal().strength(8f, 1200f));

    // ---- gyros ----
    static { cat = Cat.PROPULSION; }
    public static final DeferredBlock<GyroBlock> SMALL_GYRO = block("small_gyro", p -> new GyroBlock(20_000, p), metal());
    public static final DeferredBlock<Block> GYRO_CASING = block("gyro_casing", Block::new, metal());
    public static final DeferredBlock<Block> GYRO_ROTOR = block("gyro_rotor", Block::new, metal());
    public static final DeferredBlock<ControllerBlock> GYRO_ARRAY = block("gyro_array", p -> new ControllerBlock(MultiblockDef.GYRO_ARRAY, null, 600_000, p), metal());
    public static final DeferredBlock<ControllerBlock> GRAND_GYROSCOPE = block("grand_gyroscope", p -> new ControllerBlock(MultiblockDef.GRAND_GYRO, null, 6_000_000, p), metal());

    // ---- thrusters & aero ----
    public static final DeferredBlock<ThrusterBlock> STEAM_VENT = block("steam_vent", p -> new ThrusterBlock(ThrusterTier.STEAM, p), metal());
    public static final DeferredBlock<ThrusterBlock> BLAZE_JET = block("blaze_jet", p -> new ThrusterBlock(ThrusterTier.BLAZE, p), metal().lightLevel(s -> 6));
    public static final DeferredBlock<ThrusterBlock> ARC_THRUSTER = block("arc_thruster", p -> new ThrusterBlock(ThrusterTier.ARC, p), metal().lightLevel(s -> 6));
    public static final DeferredBlock<ThrusterBlock> ION_LANCE = block("ion_lance", p -> new ThrusterBlock(ThrusterTier.ION, p), metal().lightLevel(s -> 8));
    public static final DeferredBlock<ThrusterBlock> GRAVITON_LENS = block("graviton_lens", p -> new ThrusterBlock(ThrusterTier.GRAVITON, p), metal().lightLevel(s -> 10));
    static { cat = Cat.POWER; }
    public static final DeferredBlock<FluxAccumulatorBlock> FLUX_ACCUMULATOR = block("flux_accumulator", FluxAccumulatorBlock::new, metal());
    static { cat = Cat.PROPULSION; }
    public static final DeferredBlock<SailBlock> SKY_SAIL = block("sky_sail", ThinSailBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOL).strength(0.8f).sound(SoundType.WOOL).noOcclusion());

    // ---- items ----
    static { cat = Cat.MATERIALS; }
    public static final DeferredItem<Item> COLOSSTEEL_BLEND = item("colossteel_blend");
    public static final DeferredItem<Item> COLOSSTEEL_INGOT = item("colossteel_ingot");
    public static final DeferredItem<Item> REACTOR_ALLOY = item("reactor_alloy");
    public static final DeferredItem<Item> NEUTRONIUM_INGOT = item("neutronium_ingot");
    static { cat = Cat.POWER; }
    public static final DeferredItem<Item> ENRICHED_FUEL_CELL = item("enriched_fuel_cell");
    public static final DeferredItem<Item> DEUTERIUM_CELL = item("deuterium_cell");

    // ---- block entities / entities ----
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PowerBlockEntity>> POWER_BE = BES.register("power", () ->
            BlockEntityType.Builder.of(PowerBlockEntity::new, EMBER_BOILER.get(), BLAZE_COMBUSTOR.get(), REDSTONE_DYNAMO.get(), ION_DRIVE.get(),
                    FISSION_REACTOR.get(), FUSION_TORUS.get(), ZERO_POINT_CORE.get(), GYRO_ARRAY.get(), GRAND_GYROSCOPE.get()).build(null));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HullCoreBlockEntity>> HULL_CORE_BE = BES.register("hull_core", () ->
            BlockEntityType.Builder.of(HullCoreBlockEntity::new, HULL_CORE.get()).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FluxAccumulatorBlock.BE>> FLUX_BE = BES.register("flux_accumulator", () ->
            BlockEntityType.Builder.of(FluxAccumulatorBlock.BE::new, FLUX_ACCUMULATOR.get()).build(null));

    public static final DeferredHolder<EntityType<?>, EntityType<ColossusEntity>> COLOSSUS = ENTITIES.register("colossus", () ->
            EntityType.Builder.<ColossusEntity>of(ColossusEntity::new, MobCategory.MISC).sized(1f, 1f)
                    .clientTrackingRange(16).updateInterval(1).fireImmune().build("colossus"));

    public static final DeferredHolder<EntityType<?>, EntityType<SeatEntity>> SEAT = ENTITIES.register("seat", () ->
            EntityType.Builder.<SeatEntity>of(SeatEntity::new, MobCategory.MISC).sized(0.01f, 0.01f).noSummon().clientTrackingRange(8).build("seat"));

    private static DeferredHolder<CreativeModeTab, CreativeModeTab> tab(Cat c, String id, java.util.function.Supplier<ItemStack> icon, String before) {
        return TABS.register(id, () -> {
            CreativeModeTab.Builder b = CreativeModeTab.builder().title(Component.translatable("itemGroup.colossalcraft." + id)).icon(icon)
                    .displayItems((params, out) -> BY_CAT.get(c).forEach(i -> out.accept(i.get())));
            if (before != null) b.withTabsBefore(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ColossalCraft.MODID, before));
            return b.build();
        });
    }
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB_COMMAND = tab(Cat.COMMAND, "command", () -> new ItemStack(HULL_CORE.get()), null);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB_JOINTS = tab(Cat.JOINTS, "joints", () -> new ItemStack(JOINTS.get(MaterialTier.IRON).get()), "command");
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB_POWER = tab(Cat.POWER, "power", () -> new ItemStack(BLAZE_COMBUSTOR.get()), "joints");
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB_PROPULSION = tab(Cat.PROPULSION, "propulsion", () -> new ItemStack(BLAZE_JET.get()), "power");
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB_MATERIALS = tab(Cat.MATERIALS, "materials", () -> new ItemStack(COLOSSTEEL_INGOT.get()), "propulsion");

    public static void register(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); BES.register(bus); ENTITIES.register(bus); TABS.register(bus); MENUS.register(bus);
    }
}
