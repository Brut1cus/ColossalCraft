package com.colossalcraft.test;

import com.colossalcraft.ColossalCraft;
import com.colossalcraft.ModRegistry;
import com.colossalcraft.colossus.ColossusEntity;
import com.colossalcraft.colossus.StructureModel;
import com.colossalcraft.colossus.StructureScan;
import com.colossalcraft.tier.MaterialTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** In-world tests. Run with: gradle runGameTestServer */
@GameTestHolder(ColossalCraft.MODID)
@PrefixGameTestTemplate(false)
public class ColossusTests {
    static void floor(GameTestHelper h) { for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 0, z, Blocks.STONE); }

    /** 5x5 plank deck at y=6, Hull Core at (7,7,7), seat, fuelled boiler, steam vents underneath. */
    static void ship(GameTestHelper h, int vents) {
        for (int x = 5; x <= 9; x++) for (int z = 5; z <= 9; z++) h.setBlock(x, 6, z, Blocks.OAK_PLANKS);
        h.setBlock(7, 7, 7, ModRegistry.HULL_CORE.get());
        h.setBlock(7, 7, 8, ModRegistry.PILOT_SEAT.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        h.setBlock(6, 7, 7, ModRegistry.EMBER_BOILER.get());
        CompoundTag t = new CompoundTag(); t.putLong("Energy", 900_000);
        h.getBlockEntity(new BlockPos(6, 7, 7)).loadWithComponents(t, h.getLevel().registryAccess());
        int n = 0;
        for (int x = 5; x <= 9 && n < vents; x++) for (int z = 5; z <= 9 && n < vents; z++, n++)
            h.setBlock(x, 5, z, ModRegistry.STEAM_VENT.get().defaultBlockState().setValue(DirectionalBlock.FACING, Direction.DOWN));
    }

    static ColossusEntity find(GameTestHelper h) {
        List<ColossusEntity> l = h.getLevel().getEntitiesOfClass(ColossusEntity.class, h.getBounds());
        return l.isEmpty() ? null : l.get(0);
    }

    @GameTest(template = "empty16", timeoutTicks = 120)
    public static void hovers(GameTestHelper h) {
        floor(h); ship(h, 4);
        BlockPos heart = h.absolutePos(new BlockPos(7, 7, 7));
        StructureScan.Result r = StructureScan.scan(h.getLevel(), heart);
        h.assertTrue(r.ok(), "scan errors: " + r.errors);
        h.assertTrue(r.model.thrustCap[Direction.UP.ordinal()] > r.model.mass * 9.81, "not enough lift: " + r.model.thrustCap[1] + " vs " + r.model.mass * 9.81);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), heart, null), "assemble failed");
        h.assertBlockNotPresent(ModRegistry.HULL_CORE.get(), new BlockPos(7, 7, 7));
        double y0 = find(h).getY();
        h.runAfterDelay(80, () -> {
            ColossusEntity c = find(h);
            h.assertTrue(c != null, "colossus gone");
            ColossalCraft.LOG.info("[test] hover y0={} y={} energy={} vel={}", y0, c.getY(), c.energy, c.vel);
            h.assertTrue(Math.abs(c.getY() - y0) < 1.5, "did not hover: dy=" + (c.getY() - y0));
            h.assertTrue(c.energy < 900_000, "no fuel used");
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 160)
    public static void fallsLandsAndDisassembles(GameTestHelper h) {
        floor(h); ship(h, 0);
        BlockPos heart = h.absolutePos(new BlockPos(7, 7, 7));
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), heart, null), "assemble failed");
        h.runAfterDelay(80, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] fall y={} ground={} vel={}", c.getY(), c.onGroundC, c.vel);
            h.assertTrue(c.onGroundC, "not on ground");
            h.assertTrue(c.disassemble(null), "disassemble failed");
            // deck lands on the floor (y=1)
            h.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(7, 1, 7));
            h.assertBlockPresent(ModRegistry.HULL_CORE.get(), new BlockPos(7, 2, 7));
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 140)
    public static void weakJointSnaps(GameTestHelper h) {
        floor(h); ship(h, 25);
        // wooden joint hanging under the deck edge with an 8-block stone column (~5 t) below it
        h.setBlock(5, 5, 5, Blocks.AIR);
        h.setBlock(5, 5, 5, ModRegistry.JOINTS.get(MaterialTier.WOOD).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int y = 1; y <= 4; y++) for (int z = 4; z <= 5; z++) h.setBlock(5, y, z == 5 ? 5 : 4, Blocks.STONE_BRICKS);
        h.setBlock(5, 0, 5, Blocks.AIR); h.setBlock(5, 0, 4, Blocks.AIR);
        BlockPos heart = h.absolutePos(new BlockPos(7, 7, 7));
        StructureScan.Result r = StructureScan.scan(h.getLevel(), heart);
        h.assertTrue(r.model.joints.size() == 1, "expected 1 joint, got " + r.model.joints.size());
        StructureModel.Joint j = r.model.joints.get(0);
        ColossalCraft.LOG.info("[test] joint sub={} ratio={} parent={} children={}", j.subMass, j.staticRatio, j.parentSeg, j.childSegs);
        h.assertTrue(j.parentSeg == 0 && !j.childSegs.isEmpty(), "joint tree wrong");
        h.assertTrue(j.staticRatio > 1, "wood joint should be overloaded: " + j.staticRatio);
        // same joint in diamond, 1x1, should hold
        double diamondRatio = j.subMass * 9.81 / MaterialTier.forceCap(MaterialTier.DIAMOND, 1);
        h.assertTrue(diamondRatio < 1, "diamond should hold");
        int before = r.blocks.size();
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), heart, null), "assemble failed");
        h.runAfterDelay(80, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] blocks before={} after={}", before, c.blocks.size());
            h.assertTrue(c.blocks.size() < before - 8, "joint did not snap");
            h.succeed();
        });
    }

    @GameTest(template = "empty16")
    public static void oneHullCoreOnly(GameTestHelper h) {
        floor(h); ship(h, 0);
        h.setBlock(9, 7, 9, ModRegistry.HULL_CORE.get());
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertFalse(r.ok(), "two hull cores must be refused");
        h.succeed();
    }

    @GameTest(template = "empty16")
    public static void multiblockForms(GameTestHelper h) {
        // Ender Ion Drive: 3x3x3 Void Casing shell, Ender Coil centre, controller in the middle of the north face
        for (int x = 0; x < 3; x++) for (int y = 1; y < 4; y++) for (int z = 0; z < 3; z++)
            h.setBlock(5 + x, y, 5 + z, (x == 1 && y == 2 && z == 1) ? ModRegistry.ENDER_COIL.get().defaultBlockState() : ModRegistry.VOID_CASING.get().defaultBlockState());
        BlockState ctl = ModRegistry.ION_DRIVE.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
        h.setBlock(6, 2, 5, ctl);
        h.runAfterDelay(25, () -> {
            h.assertBlockProperty(new BlockPos(6, 2, 5), com.colossalcraft.block.ControllerBlock.FORMED, true);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 160)
    public static void chestItemsSurviveFlight(GameTestHelper h) {
        floor(h); ship(h, 0);
        h.setBlock(8, 7, 7, Blocks.CHEST);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getBlockEntity(new BlockPos(8, 7, 7));
        chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(60, () -> {
            h.assertTrue(find(h).disassemble(null), "disassemble failed");
            var c2 = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getBlockEntity(new BlockPos(8, 2, 7));
            h.assertTrue(c2.getItem(0).getCount() == 7, "chest lost items");
            h.succeed();
        });
    }

    @GameTest(template = "empty16")
    public static void fluxAccumulatorTakesFE(GameTestHelper h) {
        floor(h); ship(h, 0);
        h.setBlock(8, 7, 7, ModRegistry.FLUX_ACCUMULATOR.get());
        var fe = h.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, h.absolutePos(new BlockPos(8, 7, 7)), null);
        h.assertTrue(fe != null && fe.receiveEnergy(50_000, false) == 50_000, "FE not accepted");
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertTrue(r.model.energy == 900_000 + 50_000, "battery energy not counted: " + r.model.energy);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public static void createDynamoCharges(GameTestHelper h) {
        if (!com.colossalcraft.compat.Compat.CREATE) { h.succeed(); return; }
        floor(h);
        var motor = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:creative_motor"));
        h.setBlock(5, 1, 5, motor.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST));
        h.setBlock(6, 1, 5, com.colossalcraft.compat.create.CreateCompat.KINETIC_DYNAMO.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        h.runAfterDelay(60, () -> {
            var be = (com.colossalcraft.compat.create.CreateCompat.DynamoBE) h.getBlockEntity(new BlockPos(6, 1, 5));
            ColossalCraft.LOG.info("[test] dynamo speed={} energy={}", be.getSpeed(), be.energy);
            h.assertTrue(be.energy > 0, "dynamo made no energy (speed " + be.getSpeed() + ")");
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 220)
    public static void createMachinesWorkAfterLanding(GameTestHelper h) {
        if (!com.colossalcraft.compat.Compat.CREATE) { h.succeed(); return; }
        floor(h); ship(h, 0);
        var reg = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        h.setBlock(5, 7, 5, reg.get(net.minecraft.resources.ResourceLocation.parse("create:creative_motor")).defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST));
        h.setBlock(6, 7, 5, reg.get(net.minecraft.resources.ResourceLocation.parse("create:shaft")).defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        h.setBlock(7, 7, 5, com.colossalcraft.compat.create.CreateCompat.KINETIC_DYNAMO.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        h.runAfterDelay(20, () -> {
            h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
            h.runAfterDelay(60, () -> {
                h.assertTrue(find(h).disassemble(null), "disassemble failed");
                var be = (com.colossalcraft.compat.create.CreateCompat.DynamoBE) h.getBlockEntity(new BlockPos(7, 2, 5));
                long e0 = be.energy;
                h.runAfterDelay(40, () -> {
                    var be2 = (com.colossalcraft.compat.create.CreateCompat.DynamoBE) h.getBlockEntity(new BlockPos(7, 2, 5));
                    ColossalCraft.LOG.info("[test] after landing speed={} energy {} -> {}", be2.getSpeed(), e0, be2.energy);
                    h.assertTrue(be2.getSpeed() != 0 && be2.energy > e0, "Create network did not re-link after landing");
                    h.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public static void scaffoldStaysBehind(GameTestHelper h) {
        // ship stands on dirt pillars? no: on Gantry Scaffold. Dirt on the ship IS picked up now.
        ship(h, 0);
        for (int y = 0; y <= 5; y++) h.setBlock(7, y, 6, ModRegistry.GANTRY_SCAFFOLD.get());
        h.setBlock(9, 7, 9, Blocks.DIRT);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertTrue(r.ok(), "scan errors: " + r.errors);
        h.assertTrue(r.blocks.values().stream().noneMatch(st -> st.is(ModRegistry.GANTRY_SCAFFOLD.get())), "scaffold was picked up");
        h.assertTrue(r.blocks.values().stream().anyMatch(st -> st.is(Blocks.DIRT)), "dirt should be picked up now");
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.assertBlockPresent(ModRegistry.GANTRY_SCAFFOLD.get(), new BlockPos(7, 3, 6));
        h.succeed();
    }

    @GameTest(template = "empty16")
    public static void mixedJointShapesFormOneJoint(GameTestHelper h) {
        floor(h); ship(h, 0);
        var shapes = ModRegistry.JOINT_SHAPES.get(MaterialTier.IRON);
        // full + slab + pin in a row along X, same axis -> one joint, strength 1 + 0.5 + 0.25
        h.setBlock(6, 5, 7, shapes.get(com.colossalcraft.block.SimpleParts.JointShape.FULL).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        h.setBlock(7, 5, 7, shapes.get(com.colossalcraft.block.SimpleParts.JointShape.SLAB).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        h.setBlock(8, 5, 7, shapes.get(com.colossalcraft.block.SimpleParts.JointShape.PIN).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int x = 6; x <= 8; x++) h.setBlock(x, 4, 7, Blocks.STONE_BRICKS);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertTrue(r.model.joints.size() == 1, "expected 1 joint, got " + r.model.joints.size());
        h.assertTrue(Math.abs(r.model.joints.get(0).area - 1.75) < 1e-6, "strength should be 1.75, got " + r.model.joints.get(0).area);
        h.succeed();
    }

    @GameTest(template = "empty16")
    public static void segmentLimitIsChecked(GameTestHelper h) {
        floor(h); ship(h, 0);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertTrue(r.ok(), "default limit should allow this: " + r.errors);
        int old = com.colossalcraft.Config.MAX_SEGMENT_BLOCKS.get();
        com.colossalcraft.Config.MAX_SEGMENT_BLOCKS.set(10);
        StructureScan.Result r2 = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        com.colossalcraft.Config.MAX_SEGMENT_BLOCKS.set(old);
        h.assertFalse(r2.ok(), "limit 10 should refuse a 30+ block part");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 120)
    public static void parkFromBuiltScreenAction(GameTestHelper h) {
        floor(h); ship(h, 0);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(60, () -> {
            ColossusEntity c = find(h);
            StructureScan.Result r = StructureScan.fromEntity(c);
            h.assertTrue(r.built && r.entityId == c.getId(), "built report wrong");
            h.assertTrue(c.disassemble(null), "park failed");
            h.assertBlockPresent(ModRegistry.HULL_CORE.get(), new BlockPos(7, 2, 7));
            h.succeed();
        });
    }

    /** Two-legged walker standing on Gantry Scaffold. footGyros: put a Pocket Gyro at each foot; coreGyro: put one on the deck. */
    static void walker(GameTestHelper h, boolean footGyros, boolean coreGyro) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 0, z, ModRegistry.GANTRY_SCAFFOLD.get());
        for (int x = 5; x <= 9; x++) for (int z = 5; z <= 9; z++) h.setBlock(x, 6, z, Blocks.OAK_PLANKS);
        h.setBlock(7, 7, 7, ModRegistry.HULL_CORE.get());
        h.setBlock(7, 7, 8, ModRegistry.PILOT_SEAT.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        h.setBlock(6, 7, 7, ModRegistry.EMBER_BOILER.get());
        CompoundTag t = new CompoundTag(); t.putLong("Energy", 900_000);
        h.getBlockEntity(new BlockPos(6, 7, 7)).loadWithComponents(t, h.getLevel().registryAccess());
        if (coreGyro) h.setBlock(8, 7, 7, ModRegistry.SMALL_GYRO.get());
        BlockState joint = ModRegistry.JOINTS.get(MaterialTier.IRON).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        for (int x : new int[]{6, 8}) {
            h.setBlock(x, 5, 7, joint);
            h.setBlock(x == 6 ? 5 : 9, 5, 7, ModRegistry.MOTORS.get(MaterialTier.IRON).get());
            for (int y = 2; y <= 4; y++) h.setBlock(x, y, 7, Blocks.STONE_BRICKS);
            h.setBlock(x, 1, 7, footGyros ? ModRegistry.SMALL_GYRO.get().defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
        }
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        var hc = (com.colossalcraft.block.HullCoreBlockEntity) h.getBlockEntity(new BlockPos(7, 7, 7));
        for (StructureModel.Joint j : r.model.joints) {
            CompoundTag c = new CompoundTag(); c.putByte("Mode", (byte) (j.pivot.x < 7.5 ? StructureModel.WALK_A : StructureModel.WALK_B));
            hc.joints.put(StructureModel.jointKey(j.key), c);
        }
        hc.setChanged();
    }

    @GameTest(template = "empty16", timeoutTicks = 140)
    public static void walkerWithoutGyroFalls(GameTestHelper h) {
        walker(h, false, false);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertTrue(r.model.legged && !r.model.footingOk(), "should be legged without footing");
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(80, () -> { ColossusEntity c = find(h); ColossalCraft.LOG.info("[test] no gyro tilt={}", c.tilt); h.assertTrue(c.tilt > 1.0, "did not fall, tilt " + c.tilt); h.succeed(); });
    }

    @GameTest(template = "empty16", timeoutTicks = 140)
    public static void walkerWithCoreGyroStands(GameTestHelper h) {
        walker(h, false, true);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertTrue(r.model.footingOk() && r.model.balanced(), "core gyro walker should be stable: " + r.warnings);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(80, () -> { ColossusEntity c = find(h); h.assertTrue(c.tilt == 0 && c.onGroundC, "should stand, tilt " + c.tilt); h.succeed(); });
    }

    @GameTest(template = "empty16", timeoutTicks = 140)
    public static void walkerWithFootGyrosOnlyFalls(GameTestHelper h) {
        walker(h, true, false);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertTrue(r.model.legged && !r.model.footingOk(), "foot gyros alone should not count now");
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(80, () -> { ColossusEntity c = find(h); ColosalCraftLog(c); h.assertTrue(c.tilt > 1.0, "should fall, tilt " + c.tilt); h.succeed(); });
    }

    @GameTest(template = "empty16", timeoutTicks = 140)
    public static void walkerWithHeavyOverhangTips(GameTestHelper h) {
        walker(h, false, true);
        for (int x = 5; x <= 9; x++) for (int z = 2; z <= 4; z++) h.setBlock(x, 6, z, Blocks.IRON_BLOCK);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        ColossalCraft.LOG.info("[test] overhang com={} support={} offset={}", r.model.com, r.model.support, r.model.balanceOffset);
        h.assertTrue(r.model.footingOk() && !r.model.balanced(), "should be unbalanced");
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(80, () -> { ColossusEntity c = find(h); h.assertTrue(c.tilt > 1.0 && Math.sin(c.tiltDir) < -0.9, "should tip towards the overhang (-Z): tilt " + c.tilt + " dir " + c.tiltDir); h.succeed(); });
    }

    @GameTest(template = "empty16")
    public static void gearboxMultipliesTorque(GameTestHelper h) {
        floor(h); ship(h, 0);
        h.setBlock(7, 5, 7, ModRegistry.JOINTS.get(MaterialTier.IRON).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int y = 2; y <= 4; y++) h.setBlock(7, y, 7, Blocks.STONE_BRICKS);
        h.setBlock(8, 5, 7, ModRegistry.GEARBOXES.get(2).get()); // planetary 5:1, touches the joint
        h.setBlock(9, 5, 7, ModRegistry.MOTORS.get(MaterialTier.IRON).get()); // motor touches the gearbox only
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        StructureModel.Joint j = r.model.joints.get(0);
        h.assertTrue(Math.abs(j.motorTorque - 150_000 * 5 * 0.9) < 1, "torque " + j.motorTorque);
        h.assertTrue(Math.abs(j.maxSpeed - 60 * 6 / 5.0) < 0.01, "speed " + j.maxSpeed);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40)
    public static void lavaBucketGivesBucketBack(GameTestHelper h) {
        h.setBlock(5, 1, 5, ModRegistry.EMBER_BOILER.get());
        var be = (com.colossalcraft.block.PowerBlockEntity) h.getBlockEntity(new BlockPos(5, 1, 5));
        be.fuel.insertItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET), false);
        h.runAfterDelay(5, () -> {
            h.assertTrue(be.fuel.getStackInSlot(0).is(net.minecraft.world.item.Items.BUCKET), "bucket not returned: " + be.fuel.getStackInSlot(0));
            h.assertTrue(be.energy == 20000L * 40L, "energy " + be.energy);
            h.succeed();
        });
    }

    @GameTest(template = "empty16")
    public static void groundContactIsReported(GameTestHelper h) {
        ship(h, 0);
        h.setBlock(5, 5, 5, Blocks.STONE_BRICKS);
        for (int y = 0; y <= 4; y++) h.setBlock(5, y, 5, Blocks.DIRT); // a dirt pillar to the floor
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        ColossalCraft.LOG.info("[test] contact blocks={} hasDirt={} dirtTagged={} contacts={}", r.blocks.size(), r.blocks.values().stream().anyMatch(st -> st.is(Blocks.DIRT)),
                Blocks.DIRT.defaultBlockState().is(StructureScan.NATURAL), r.contacts.size());
        boolean told = r.warnings.stream().anyMatch(w -> w.contains("natural ground")) || r.errors.stream().anyMatch(e -> e.contains("Touches the ground"));
        h.assertTrue(!r.contacts.isEmpty() && told, "contact not reported: " + r.errors + r.warnings);
        h.succeed();
    }
    @GameTest(template = "empty16")
    public static void lockedLegsDontTip(GameTestHelper h) {
        walker(h, false, false);
        ((com.colossalcraft.block.HullCoreBlockEntity) h.getBlockEntity(new BlockPos(7, 7, 7))).joints = new CompoundTag(); // all Locked: a statue, not a walker
        h.assertTrue(!StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model.legged, "locked legs shouldn't count as a walker");
        h.succeed();
    }

    private static void ColosalCraftLog(ColossusEntity c) { ColossalCraft.LOG.info("[test] foot gyro tilt={} ground={} energy={}", c.tilt, c.onGroundC, c.energy); }

    @GameTest(template = "empty16", timeoutTicks = 40)
    public static void engineFuelSlotBurns(GameTestHelper h) {
        h.setBlock(5, 1, 5, ModRegistry.EMBER_BOILER.get());
        var cap = h.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, h.absolutePos(new BlockPos(5, 1, 5)), null);
        h.assertTrue(cap != null, "no item handler");
        h.assertTrue(cap.insertItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, 3), false).isEmpty(), "coal refused");
        h.assertTrue(!cap.insertItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT), true).isEmpty(), "dirt accepted");
        h.runAfterDelay(10, () -> {
            var be = (com.colossalcraft.block.PowerBlockEntity) h.getBlockEntity(new BlockPos(5, 1, 5));
            h.assertTrue(be.energy == 3 * 1600L * 40L && be.fuel.getStackInSlot(0).isEmpty(), "energy " + be.energy);
            h.succeed();
        });
    }

    @GameTest(template = "empty16")
    public static void panelJointSitsAgainstSide(GameTestHelper h) {
        var panel = ModRegistry.JOINT_SHAPES.get(MaterialTier.IRON).get(com.colossalcraft.block.SimpleParts.JointShape.PANEL).get();
        h.setBlock(5, 1, 5, panel.defaultBlockState().setValue(com.colossalcraft.block.SimpleParts.PanelJointBlock.SIDE, Direction.EAST));
        var shape = h.getLevel().getBlockState(h.absolutePos(new BlockPos(5, 1, 5))).getShape(h.getLevel(), h.absolutePos(new BlockPos(5, 1, 5))).bounds();
        h.assertTrue(shape.minX == 0.5 && shape.maxX == 1.0, "panel should hug the east side: " + shape);
        h.succeed();
    }
}
