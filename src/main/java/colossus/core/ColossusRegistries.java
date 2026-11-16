package colossus.core;

import colossus.assembly.HullCoreBlock;
import colossus.assembly.HullCoreBlockEntity;
import colossus.assembly.JointBlock;
import colossus.assembly.JointKind;
import colossus.assembly.graph.CoreTier;
import colossus.gyro.GyroStabilizerBlock;
import colossus.pilot.PilotSeatBlock;
import colossus.pilot.SeatEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import colossus.gyro.GyroStabilizerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Content: Hull Cores (five tiers), Core Lattice, the seven joint blocks, the Foot Pad, the Gyro Stabilizer and the Pilot Seat. */
public final class ColossusRegistries {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Colossus.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Colossus.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Colossus.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Colossus.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, Colossus.MODID);

    private static final List<DeferredItem<?>> TAB_ITEMS = new ArrayList<>();

    private static BlockBehaviour.Properties metal() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4f, 8f).sound(SoundType.METAL).requiresCorrectToolForDrops();
    }

    private static <B extends Block> DeferredBlock<B> block(String name, Function<BlockBehaviour.Properties, B> factory, BlockBehaviour.Properties props) {
        DeferredBlock<B> b = BLOCKS.register(name, () -> factory.apply(props));
        TAB_ITEMS.add(ITEMS.registerSimpleBlockItem(name, b));
        return b;
    }

    // ---- Hull Cores ----
    public static final Map<CoreTier, DeferredBlock<HullCoreBlock>> HULL_CORES = new EnumMap<>(CoreTier.class);
    static {
        for (CoreTier t : CoreTier.values()) {
            BlockBehaviour.Properties p = t == CoreTier.WOODEN
                    ? BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD).lightLevel(s -> 4)
                    : metal().lightLevel(s -> 7);
            HULL_CORES.put(t, block(t.name().toLowerCase(Locale.ROOT) + "_hull_core", props -> new HullCoreBlock(props, t), p));
        }
    }
    public static final DeferredBlock<Block> CORE_LATTICE = block("core_lattice", Block::new, metal().lightLevel(s -> 5));

    // ---- Joints ----
    public static final Map<JointKind, DeferredBlock<JointBlock>> JOINTS = new EnumMap<>(JointKind.class);
    static {
        for (JointKind k : JointKind.values()) JOINTS.put(k, block(k.id, props -> new JointBlock(props, k), metal().noOcclusion()));
    }

    // ---- Limb parts ----
    /** Marks a limb's end effector; any Segment touching the ground at rest also counts as a foot. */
    public static final DeferredBlock<Block> FOOT_PAD = block("foot_pad", Block::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2f).sound(SoundType.WOOL).friction(0.5f));

    // ---- Stabilization ----
    /** Powered control-moment gyro; an N×N square merges into one rotor (see {@code GyroStabilizer}). Redstone sets its throttle. */
    public static final DeferredBlock<GyroStabilizerBlock> GYRO_STABILIZER = block("gyro_stabilizer", GyroStabilizerBlock::new, metal());

    // ---- Piloting ----
    /** Sit here to drive the Colossus (W/S, A/D). */
    public static final DeferredBlock<PilotSeatBlock> PILOT_SEAT = block("pilot_seat", PilotSeatBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2f).sound(SoundType.WOOD).noOcclusion());

    /** The rideable, invisible part of a Pilot Seat; follows the seat on an assembled Colossus. Never saved. */
    public static final DeferredHolder<EntityType<?>, EntityType<SeatEntity>> SEAT = ENTITY_TYPES.register("seat",
            () -> EntityType.Builder.<SeatEntity>of(SeatEntity::new, MobCategory.MISC).sized(0.9f, 0.5f)
                    .clientTrackingRange(10).updateInterval(1).noSave().noSummon().build("seat"));

    // ---- Block entities ----
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HullCoreBlockEntity>> HULL_CORE_BE = BLOCK_ENTITIES.register("hull_core",
            () -> BlockEntityType.Builder.of(HullCoreBlockEntity::new, HULL_CORES.values().stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GyroStabilizerBlockEntity>> GYRO_BE = BLOCK_ENTITIES.register("gyro_stabilizer",
            () -> BlockEntityType.Builder.of(GyroStabilizerBlockEntity::new, GYRO_STABILIZER.get()).build(null));

    // ---- Creative tab ----
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.colossus.main"))
            .icon(() -> new ItemStack(HULL_CORES.get(CoreTier.IRON).get()))
            .displayItems((params, out) -> TAB_ITEMS.forEach(i -> out.accept(i.get())))
            .build());

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
        ENTITY_TYPES.register(modBus);
    }

    private ColossusRegistries() {}
}
