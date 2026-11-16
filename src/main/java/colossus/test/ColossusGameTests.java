package colossus.test;

import colossus.assembly.AssemblyService;
import colossus.assembly.JointBlock;
import colossus.assembly.JointKind;
import colossus.assembly.graph.CoreTier;
import colossus.assembly.graph.StructureScanner;
import colossus.client.ClientColossi;
import colossus.core.Colossus;
import colossus.core.ColossusManager;
import colossus.core.ColossusRegistries;
import colossus.net.SegmentBlocks;
import colossus.net.StructureSync;
import colossus.shipyard.ColossusRecord;
import colossus.shipyard.ColossusSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.level.block.Block;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
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

    /** The renderer's input: every Segment's blocks reach the client intact, split into parts, and reassemble. */
    @GameTest(template = "empty16", timeoutTicks = 100)
    public static void structureSyncCarriesEverySegmentBlock(GameTestHelper h) {
        floor(h);
        BlockPos core = biped(h);
        UUID id = AssemblyService.assemble(h.getLevel(), core, null);
        h.assertTrue(id != null, "assembly failed");
        ColossusRecord rec = ColossusSavedData.get(h.getLevel()).byId(id);

        // tiny parts force the multi-part path (the torso holds 11 blocks)
        List<SegmentBlocks> payloads = StructureSync.payloads(h.getLevel(), rec, 4);
        int iron = Block.getId(Blocks.IRON_BLOCK.defaultBlockState()), cells = 0, ironCells = 0;
        ClientColossi.remove(id);
        for (SegmentBlocks p : payloads) {
            h.assertTrue(p.cells().length <= 4, "part too large");
            ByteBuf buf = Unpooled.buffer();
            SegmentBlocks.CODEC.encode(buf, p);
            SegmentBlocks back = SegmentBlocks.CODEC.decode(buf);
            h.assertTrue(java.util.Arrays.equals(back.cells(), p.cells()) && java.util.Arrays.equals(back.states(), p.states())
                    && back.comRel().equals(p.comRel()) && back.part() == p.part() && back.parts() == p.parts(), "codec round trip changed the payload");
            cells += p.cells().length;
            for (int s : p.states()) if (s == iron) ironCells++;
            ClientColossi.accept(back);
        }
        h.assertTrue(cells == 23, "expected 23 synced cells, got " + cells);
        h.assertTrue(ironCells == 16, "expected 16 iron blocks, got " + ironCells);

        ClientColossi.Structure st = ClientColossi.structure(id);
        h.assertTrue(st != null && st.segments() == rec.segments.size(), "client structure missing");
        for (int i = 0; i < st.segments(); i++) {
            ClientColossi.SegmentData d = st.segment(i);
            ColossusRecord.SegmentRecord s = rec.segments.get(i);
            h.assertTrue(d.complete() && d.size() == s.cells.length, "segment " + i + " incomplete on the client");
            for (int c = 0; c < d.size(); c++) {
                BlockPos plot = s.plotPos(d.cell(c));
                h.assertTrue(Block.stateById(d.state(c)) == h.getLevel().getBlockState(plot), "segment " + i + " cell " + c + " has the wrong block");
            }
        }
        ClientColossi.remove(id);
        h.assertTrue(AssemblyService.disassemble(h.getLevel(), id, null), "disassembly refused");
        h.succeed();
    }

    /**
     * A deck with a 2x2 Gyro Stabilizer powered by a redstone block on board and a lone unpowered one: the 2x2 merges
     * into one rotor and spins up, the lone one stays still.
     */
    @GameTest(template = "empty16", timeoutTicks = 200)
    public static void gyroMergesSquaresAndSpinsUpOnRedstone(GameTestHelper h) {
        floor(h);
        for (int x = 4; x <= 10; x++) for (int z = 6; z <= 9; z++) h.setBlock(x, 2, z, Blocks.IRON_BLOCK);
        h.setBlock(7, 3, 7, ColossusRegistries.HULL_CORES.get(CoreTier.IRON).get());
        for (int x = 4; x <= 5; x++) for (int z = 6; z <= 7; z++) h.setBlock(x, 3, z, ColossusRegistries.GYRO_STABILIZER.get());
        h.setBlock(4, 4, 6, Blocks.REDSTONE_BLOCK);
        h.setBlock(10, 3, 9, ColossusRegistries.GYRO_STABILIZER.get());

        UUID id = AssemblyService.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 3, 7)), null);
        h.assertTrue(id != null, "assembly failed");
        ColossusManager m = ColossusManager.get(h.getLevel());
        List<ColossusManager.GyroInfo> gyros = m.gyros(id);
        h.assertTrue(gyros.size() == 1, "expected gyros on one segment, got " + gyros.size());
        var rotors = gyros.get(0).rotors();
        h.assertTrue(rotors.size() == 2, "expected a 2x2 rotor and a 1x1 rotor, got " + rotors.size());
        var big = rotors.stream().filter(r -> r.footprint == 2).findFirst().orElse(null);
        var small = rotors.stream().filter(r -> r.footprint == 1).findFirst().orElse(null);
        h.assertTrue(big != null && small != null, "rotor footprints wrong");
        h.succeedWhen(() -> {
            h.assertTrue(big.throttle() == 1, "redstone block should give full throttle, got " + big.throttle());
            // physics runs in real time while the test server races through ticks, so only ask that it is turning
            h.assertTrue(big.rpm() > 5, "2x2 rotor still at " + big.rpm() + " rpm");
            h.assertTrue(small.rpm() == 0 && small.throttle() == 0, "unpowered rotor turned");
            h.assertTrue(m.gyros(id).get(0).torqueCapacity() > 0, "no torque capacity");
            h.assertTrue(AssemblyService.disassemble(h.getLevel(), id, null) || ColossusSavedData.get(h.getLevel()).byId(id) == null,
                    "disassembly refused");
        });
    }
}
