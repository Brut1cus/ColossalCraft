package colossus.test;

import colossus.assembly.AssemblyService;
import colossus.assembly.JointBlock;
import colossus.assembly.JointKind;
import colossus.assembly.graph.CoreTier;
import colossus.assembly.graph.StructureScanner;
import colossus.core.Colossus;
import colossus.core.ColossusRegistries;
import colossus.shipyard.ColossusRecord;
import colossus.shipyard.ColossusSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** In-world tests. Run with {@code ./gradlew runGameTestServer}. Pure maths is covered by {@code ./gradlew test}. */
@GameTestHolder(Colossus.MODID)
@PrefixGameTestTemplate(false)
public class ColossusGameTests {

    static void floor(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    /** Biped on the floor: torso y=7..8 with the core on top, ball hips at y=6, thighs, hinge knees at y=3, shins. */
    static BlockPos biped(GameTestHelper h) {
        for (int x = 5; x <= 9; x++) { h.setBlock(x, 7, 8, Blocks.IRON_BLOCK); h.setBlock(x, 8, 8, Blocks.IRON_BLOCK); }
        h.setBlock(7, 9, 8, ColossusRegistries.HULL_CORES.get(CoreTier.IRON).get());
        for (int x : new int[]{5, 9}) {
            h.setBlock(x, 6, 8, ColossusRegistries.JOINTS.get(JointKind.BALL_BEARING).get().defaultBlockState().setValue(JointBlock.AXIS, Direction.Axis.Y));
            h.setBlock(x, 5, 8, Blocks.IRON_BLOCK);
            h.setBlock(x, 4, 8, Blocks.IRON_BLOCK);
            h.setBlock(x, 3, 8, ColossusRegistries.JOINTS.get(JointKind.THRUST_BEARING).get().defaultBlockState().setValue(JointBlock.AXIS, Direction.Axis.X));
            h.setBlock(x, 2, 8, Blocks.IRON_BLOCK);
            h.setBlock(x, 1, 8, ColossusRegistries.FOOT_PAD.get());
        }
        return h.absolutePos(new BlockPos(7, 9, 8));
    }

    @GameTest(template = "empty16")
    public static void scanFindsSegmentsAndJoints(GameTestHelper h) {
        floor(h);
        BlockPos core = biped(h);
        StructureScanner.Result r = AssemblyService.scan(h.getLevel(), core);
        h.assertTrue(r.ok(), "scan errors: " + r.errors());
        h.assertTrue(r.segments().size() == 5, "expected 5 segments, got " + r.segments().size());
        h.assertTrue(r.joints().size() == 4, "expected 4 joints, got " + r.joints().size());
        h.assertTrue(r.blockCount() == 23, "expected 23 blocks, got " + r.blockCount());
        h.assertTrue(r.warnings().isEmpty(), "unexpected warnings " + r.warnings());
        h.succeed();
    }

    @GameTest(template = "empty16")
    public static void floorIsNeverPickedUp(GameTestHelper h) {
        floor(h);
        h.setBlock(7, 1, 7, ColossusRegistries.HULL_CORES.get(CoreTier.WOODEN).get());
        h.setBlock(7, 2, 7, Blocks.OAK_PLANKS);
        StructureScanner.Result r = AssemblyService.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 1, 7)));
        // natural stone is in colossus:non_assemblable, so only the core and the plank are picked up, and the floor counts as support
        h.assertTrue(r.ok(), "scan errors: " + r.errors());
        h.assertTrue(r.blockCount() == 2, "expected 2 blocks, got " + r.blockCount());
        h.assertTrue(r.warnings().isEmpty(), "unexpected warnings " + r.warnings());
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public static void assembleKeepsInventoriesAndDisassembles(GameTestHelper h) {
        floor(h);
        // a small hovering deck (not touching the floor): core, planks, a chest with an item
        for (int x = 6; x <= 8; x++) for (int z = 6; z <= 8; z++) h.setBlock(x, 5, z, Blocks.OAK_PLANKS);
        h.setBlock(7, 6, 7, ColossusRegistries.HULL_CORES.get(CoreTier.WOODEN).get());
        h.setBlock(6, 6, 6, Blocks.CHEST);
        BlockEntity chest = h.getBlockEntity(new BlockPos(6, 6, 6));
        h.assertTrue(chest instanceof Container, "chest missing");
        ((Container) chest).setItem(0, new ItemStack(Items.DIAMOND, 7));

        BlockPos core = h.absolutePos(new BlockPos(7, 6, 7));
        UUID id = AssemblyService.assemble(h.getLevel(), core, null);
        h.assertTrue(id != null, "assembly failed");
        h.assertBlockNotPresent(Blocks.CHEST, new BlockPos(6, 6, 6));
        ColossusRecord rec = ColossusSavedData.get(h.getLevel()).byId(id);
        h.assertTrue(rec != null && rec.blockCount() == 11, "record should hold 11 blocks");

        // put it straight back (before it has fallen far) and check the chest kept its diamonds
        h.assertTrue(AssemblyService.disassemble(h.getLevel(), id, null), "disassembly refused");
        h.succeedWhen(() -> {
            boolean found = false;
            for (int y = 1; y <= 6 && !found; y++) {
                BlockEntity be = h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(6, y, 6)));
                if (be instanceof Container c && c.getItem(0).is(Items.DIAMOND) && c.getItem(0).getCount() == 7) found = true;
            }
            h.assertTrue(found, "chest with 7 diamonds not found after disassembly");
            h.assertTrue(ColossusSavedData.get(h.getLevel()).byId(id) == null, "record not removed");
        });
    }
}
