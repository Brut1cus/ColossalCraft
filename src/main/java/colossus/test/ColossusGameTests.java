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
import colossus.gyro.GyroStabilizerBlockEntity;
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

    /** A powered gyro spins up in the world, keeps that speed when assembled, and still has it after disassembly. */
    @GameTest(template = "empty16", timeoutTicks = 200)
    public static void gyroKeepsItsSpinThroughAssembly(GameTestHelper h) {
        floor(h);
        // a free-standing powered gyro spins up in the world
        h.setBlock(2, 1, 2, ColossusRegistries.GYRO_STABILIZER.get());
        h.setBlock(2, 2, 2, Blocks.REDSTONE_BLOCK);
        // a deck whose gyro starts at 80% (as if spun up long ago)
        for (int x = 6; x <= 8; x++) for (int z = 6; z <= 8; z++) h.setBlock(x, 4, z, Blocks.IRON_BLOCK);
        h.setBlock(7, 5, 7, ColossusRegistries.HULL_CORES.get(CoreTier.IRON).get());
        h.setBlock(6, 5, 6, ColossusRegistries.GYRO_STABILIZER.get());
        ((GyroStabilizerBlockEntity) h.getBlockEntity(new BlockPos(6, 5, 6))).setSpin(0.8);

        UUID id = AssemblyService.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 5, 7)), null);
        h.assertTrue(id != null, "assembly failed");
        var rotor = ColossusManager.get(h.getLevel()).gyros(id).get(0).rotors().get(0);
        h.assertTrue(Math.abs(rotor.spinFraction() - 0.8) < 0.01, "rotor should start at 80%, got " + rotor.spinFraction());
        h.assertTrue(AssemblyService.disassemble(h.getLevel(), id, null), "disassembly refused");

        h.succeedWhen(() -> {
            var free = (GyroStabilizerBlockEntity) h.getBlockEntity(new BlockPos(2, 1, 2));
            h.assertTrue(free.spin() > 0.01, "powered gyro did not spin up in the world: " + free.spin());
            // unpowered after disassembly it spins down slowly, so it is still well above zero
            boolean found = false;
            for (int y = 1; y <= 5 && !found; y++) {
                if (h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(6, y, 6))) instanceof GyroStabilizerBlockEntity be && be.spin() > 0.5) found = true;
            }
            h.assertTrue(found, "disassembled gyro lost its spin");
        });
    }

    /**
     * A quadruped on ball-bearing hips stands on held joints instead of flopping onto its belly, and keeps standing past
     * the strain meter's snap time: a plank-frame deck keeps each 1x1 hip at about 60% of its rating.
     */
    @GameTest(template = "empty16", timeoutTicks = 100_000)
    public static void quadrupedStandsOnHeldBallHips(GameTestHelper h) {
        floor(h);
        for (int x = 5; x <= 9; x++) for (int z = 5; z <= 9; z++) if (x == 5 || x == 9 || z == 5 || z == 9 || x == 7 || z == 7) h.setBlock(x, 6, z, Blocks.OAK_PLANKS);
        h.setBlock(7, 7, 7, ColossusRegistries.HULL_CORES.get(CoreTier.WOODEN).get());
        for (int[] c : new int[][]{{5, 5}, {9, 5}, {5, 9}, {9, 9}}) {
            h.setBlock(c[0], 5, c[1], ColossusRegistries.JOINTS.get(JointKind.BALL_BEARING).get().defaultBlockState().setValue(JointBlock.AXIS, Direction.Axis.Y));
            for (int y = 2; y <= 4; y++) h.setBlock(c[0], y, c[1], Blocks.OAK_PLANKS);
            h.setBlock(c[0], 1, c[1], ColossusRegistries.FOOT_PAD.get());
        }
        StructureScanner.Result scan = AssemblyService.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertTrue(scan.ok(), "scan errors: " + scan.errors());
        UUID id = AssemblyService.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null);
        h.assertTrue(id != null, "assembly failed");
        ColossusRecord rec = ColossusSavedData.get(h.getLevel()).byId(id);
        int root = rec.segments.indexOf(rec.root());
        double y0 = rec.root().pose.position().y();
        double start = ColossusManager.get(h.getLevel()).physicsTime();
        // physics runs in real time while the test server races through ticks: wait for 4 s of simulated time (past
        // the 3 s strain snap), not for a tick count
        h.succeedWhen(() -> {
            h.assertTrue(ColossusManager.get(h.getLevel()).physicsTime() - start > 4, "waiting for physics");
            var pose = ColossusManager.get(h.getLevel()).pose(rec, root);
            double drop = y0 - pose.position().y();
            double tilt = Math.toDegrees(pose.rotation().angle());
            long broken = rec.joints.stream().filter(j -> j.broken).count();
            AssemblyService.disassemble(h.getLevel(), id, null);
            if (drop > 0.5 || tilt > 5 || broken > 0) {
                // a real failure, not an assertion succeedWhen would retry
                throw new IllegalStateException(String.format(java.util.Locale.ROOT, "deck dropped %.2f m, tilted %.1f°, %d joints snapped", drop, tilt, broken));
            }
        });
    }

    /**
     * Phase 2 gate: a quadruped with 2x2 ball hips, hinge knees, universal ankles and Foot Pad feet walks 50 blocks
     * on flat ground under the walker brain and stays upright with no joint snapped. Physics runs in real time, so it
     * waits on simulated time (about two minutes), in a batch of its own on a long floor.
     */
    @GameTest(template = "empty16", timeoutTicks = 2_000_000, batch = "walker")
    public static void quadrupedWalksFiftyBlocks(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos o = h.absolutePos(new BlockPos(2, 0, 2));
        // floor: a strip 16 wide and 90 long, chunks kept loaded while the test runs
        java.util.Set<net.minecraft.world.level.ChunkPos> forced = new java.util.HashSet<>();
        for (int x = -4; x < 12; x++) for (int z = -4; z < 86; z++) {
            BlockPos p = o.offset(x, 0, z);
            if (forced.add(new net.minecraft.world.level.ChunkPos(p))) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
            level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        }
        // deck 6x6 at y=8 with the core on top; 2x2 legs at the corners
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) level.setBlock(o.offset(x, 8, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
        BlockPos core = o.offset(2, 9, 2);
        level.setBlock(core, ColossusRegistries.HULL_CORES.get(CoreTier.IRON).get().defaultBlockState(), 2);
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
            for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                BlockPos b = o.offset(c[0] + dx, 0, c[1] + dz);
                level.setBlock(b.above(7), ColossusRegistries.JOINTS.get(JointKind.BALL_BEARING).get().defaultBlockState().setValue(JointBlock.AXIS, Direction.Axis.Y), 2);
                level.setBlock(b.above(6), Blocks.WHITE_WOOL.defaultBlockState(), 2);
                level.setBlock(b.above(5), Blocks.WHITE_WOOL.defaultBlockState(), 2);
                level.setBlock(b.above(4), ColossusRegistries.JOINTS.get(JointKind.THRUST_BEARING).get().defaultBlockState().setValue(JointBlock.AXIS, Direction.Axis.X), 2);
                level.setBlock(b.above(3), Blocks.WHITE_WOOL.defaultBlockState(), 2);
                level.setBlock(b.above(2), ColossusRegistries.JOINTS.get(JointKind.UNIVERSAL_JOINT).get().defaultBlockState().setValue(JointBlock.AXIS, Direction.Axis.Y), 2);
                level.setBlock(b.above(1), ColossusRegistries.FOOT_PAD.get().defaultBlockState(), 2);
            }
        }
        StructureScanner.Result scan = AssemblyService.scan(level, core);
        h.assertTrue(scan.ok(), "scan errors: " + scan.errors());
        UUID id = AssemblyService.assemble(level, core, null);
        h.assertTrue(id != null, "assembly failed");
        ColossusManager m = ColossusManager.get(level);
        h.assertTrue(m.legCount(id) == 4, "expected 4 legs, the walker brain found " + m.legCount(id));
        ColossusRecord rec = ColossusSavedData.get(level).byId(id);
        int root = rec.segments.indexOf(rec.root());
        double z0 = rec.root().pose.position().z();
        double t0 = m.physicsTime();
        m.command(id, 0.5, 0, 0);
        double[] worst = {0};
        h.succeedWhen(() -> {
            var pose = m.pose(rec, root);
            double tilt = Math.toDegrees(colossus.brain.balance.GyroStabilizer.tiltError(pose.rotation()).length());
            worst[0] = Math.max(worst[0], tilt);
            long broken = rec.joints.stream().filter(j -> j.broken).count();
            double walked = pose.position().z() - z0;
            if (tilt > 30 || broken > 0 || m.physicsTime() - t0 > 240) {
                cleanUp(level, id, forced);
                // not h.fail(): succeedWhen retries assertion failures until the timeout, so a fall would hang the test
                throw new IllegalStateException(String.format(java.util.Locale.ROOT, "walked %.1f blocks in %.0f s, tilt %.1f° (worst %.1f°), %d joints snapped",
                        walked, m.physicsTime() - t0, tilt, worst[0], broken));
            }
            h.assertTrue(walked >= 50, String.format(java.util.Locale.ROOT, "walked %.1f of 50 blocks", walked));
            colossus.core.Colossus.LOG.info("Walker GameTest: 50 blocks in {} s of physics, worst tilt {}°", Math.round(m.physicsTime() - t0), Math.round(worst[0]));
            cleanUp(level, id, forced);
        });
    }

    private static void cleanUp(net.minecraft.server.level.ServerLevel level, UUID id, java.util.Set<net.minecraft.world.level.ChunkPos> forced) {
        ColossusManager.get(level).command(id, 0, 0, 0);
        ColossusManager.get(level).despawn(id);
        ColossusSavedData.get(level).remove(id);
        for (var c : forced) level.setChunkForced(c.x, c.z, false);
    }

    /**
     * A Pilot Seat on an assembled Colossus gets a rideable seat entity at the seat's position; its rider's input is
     * accepted, anyone else's is not; disassembly removes the seat and unseats the rider.
     */
    @GameTest(template = "empty16", timeoutTicks = 100)
    public static void pilotSeatFollowsItsColossusAndTakesInputFromItsRider(GameTestHelper h) {
        floor(h);
        for (int x = 6; x <= 8; x++) for (int z = 6; z <= 8; z++) h.setBlock(x, 4, z, Blocks.OAK_PLANKS);
        h.setBlock(7, 5, 7, ColossusRegistries.HULL_CORES.get(CoreTier.WOODEN).get());
        h.setBlock(6, 5, 6, ColossusRegistries.PILOT_SEAT.get());
        UUID id = AssemblyService.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 5, 7)), null);
        h.assertTrue(id != null, "assembly failed");
        var seats = h.getLevel().getEntitiesOfClass(colossus.pilot.SeatEntity.class, new net.minecraft.world.phys.AABB(h.absolutePos(new BlockPos(6, 5, 6))).inflate(1));
        h.assertTrue(seats.size() == 1 && id.equals(seats.get(0).colossus()), "expected one seat linked to the Colossus, found " + seats.size());
        var seat = seats.get(0);
        var pilot = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), "pilot"));
        var bystander = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), "bystander"));
        pilot.setPos(seat.getX(), seat.getY(), seat.getZ());
        h.assertTrue(pilot.startRiding(seat), "could not sit down");
        h.assertTrue(colossus.pilot.PilotInput.apply(pilot, new colossus.pilot.PilotInput(seat.getId(), 1, 0)), "rider's input refused");
        h.assertTrue(!colossus.pilot.PilotInput.apply(bystander, new colossus.pilot.PilotInput(seat.getId(), 1, 0)), "a bystander steered");
        h.assertTrue(!colossus.pilot.PilotInput.apply(pilot, new colossus.pilot.PilotInput(seat.getId(), Float.NaN, 0)), "NaN input accepted");
        h.runAfterDelay(5, () -> {
            // the seat sits where its block is on the moving body
            ColossusRecord rec = ColossusSavedData.get(h.getLevel()).byId(id);
            int si = 0;
            var s0 = rec.segments.get(si);
            long cell = new BlockPos(-1, 0, -1).asLong();
            var at = ColossusManager.get(h.getLevel()).pose(rec, si).toWorld(s0.local(BlockPos.of(cell)));
            h.assertTrue(seat.position().distanceTo(new net.minecraft.world.phys.Vec3(at.x(), at.y() - 0.25, at.z())) < 0.6,
                    "seat at " + seat.position() + ", block at " + at);
            h.assertTrue(AssemblyService.disassemble(h.getLevel(), id, null), "disassembly refused");
            h.runAfterDelay(2, () -> {
                h.assertTrue(seat.isRemoved(), "seat outlived its Colossus");
                h.assertTrue(pilot.getVehicle() == null, "pilot still seated");
                h.succeed();
            });
        });
    }
}
