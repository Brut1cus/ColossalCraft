package com.colossalcraft.test;

import com.colossalcraft.colossus.WalkerController;

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
        h.setBlock(7, 7, 6, ModRegistry.LOCOMOTION_CONTROLLER.get());
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
        h.setBlock(8, 5, 7, ModRegistry.GEARBOXES.get(2).get().defaultBlockState().setValue(DirectionalBlock.FACING, Direction.WEST)); // planetary 5:1, output towards the joint
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

    @GameTest(template = "empty16", timeoutTicks = 160)
    public static void walkerWalksFromItsFeet(GameTestHelper h) {
        walker(h, false, true);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(20, () -> {
            ColossusEntity c = find(h); double z0 = c.getZ();
            c.autopilot = true; c.setInput(1, 0, false, false);
            h.runAfterDelay(80, () -> {
                ColossalCraft.LOG.info("[test] walk dz={} phase={} tilt={} vel={}", c.getZ() - z0, c.walkPhase, c.tilt, c.vel);
                h.assertTrue(c.getZ() - z0 < -1.0, "should walk north (forward), dz=" + (c.getZ() - z0));
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 160)
    public static void rotorLiftsItOff(GameTestHelper h) {
        floor(h);
        for (int x = 6; x <= 8; x++) for (int z = 6; z <= 8; z++) h.setBlock(x, 6, z, Blocks.OAK_PLANKS);
        h.setBlock(7, 7, 7, ModRegistry.HULL_CORE.get());
        h.setBlock(6, 7, 7, ModRegistry.EMBER_BOILER.get());
        CompoundTag t = new CompoundTag(); t.putLong("Energy", 900_000);
        h.getBlockEntity(new BlockPos(6, 7, 7)).loadWithComponents(t, h.getLevel().registryAccess());
        h.setBlock(7, 8, 7, ModRegistry.JOINTS.get(MaterialTier.DIAMOND).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
        h.setBlock(8, 8, 7, ModRegistry.GEARBOXES.get(0).get().defaultBlockState().setValue(DirectionalBlock.FACING, Direction.WEST)); // overdrive: twice as fast
        h.setBlock(8, 7, 7, ModRegistry.MOTORS.get(MaterialTier.DIAMOND).get());
        h.setBlock(7, 9, 7, Blocks.OAK_PLANKS); h.setBlock(7, 10, 7, Blocks.OAK_PLANKS);
        for (int r = 1; r <= 3; r++) {
            h.setBlock(7 + r, 10, 7, ModRegistry.ROTOR_BLADE.get()); h.setBlock(7 - r, 10, 7, ModRegistry.ROTOR_BLADE.get());
            h.setBlock(7, 10, 7 + r, ModRegistry.ROTOR_BLADE.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
            h.setBlock(7, 10, 7 - r, ModRegistry.ROTOR_BLADE.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
        }
        StructureScan.Result r0 = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        var hc = (com.colossalcraft.block.HullCoreBlockEntity) h.getBlockEntity(new BlockPos(7, 7, 7));
        CompoundTag jc = new CompoundTag(); jc.putByte("Mode", (byte) StructureModel.SPIN); hc.joints.put(StructureModel.jointKey(r0.model.joints.get(0).key), jc); hc.setChanged();
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        StructureModel.Joint j = r.model.joints.get(0);
        ColossalCraft.LOG.info("[test] rotor blades={} A2={} torque={} maxSpeed={} mass={}", j.blades, j.bladeA2, j.motorTorque, j.maxSpeed, r.model.mass);
        h.assertTrue(j.blades == 12 && j.active(), "rotor not set up: blades " + j.blades);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(10, () -> {
            ColossusEntity c = find(h); double y0 = c.getY(); c.autopilot = true; c.setInput(0, 0, true, false);
            h.runAfterDelay(120, () -> {
                ColossalCraft.LOG.info("[test] rotor dy={} spinRate={}", c.getY() - y0, c.spinRate);
                h.assertTrue(c.getY() - y0 > 0.5, "rotor should lift it, dy=" + (c.getY() - y0));
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16")
    public static void motorGridAddsUp(GameTestHelper h) {
        floor(h); ship(h, 0);
        h.setBlock(7, 5, 7, ModRegistry.JOINTS.get(MaterialTier.IRON).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int y = 2; y <= 4; y++) h.setBlock(7, y, 7, Blocks.STONE_BRICKS);
        var motor = ModRegistry.MOTORS.get(MaterialTier.IRON).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        h.setBlock(8, 5, 7, motor); h.setBlock(8, 5, 8, motor); h.setBlock(8, 4, 7, motor); h.setBlock(8, 4, 8, motor); // 2x2 grid, one touches the joint
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        StructureModel.Joint j = r.model.joints.get(0);
        h.assertTrue(Math.abs(j.motorTorque - 4 * 150_000 * 1.1) < 1, "2x2 grid torque " + j.motorTorque);
        h.assertTrue(r.model.motorClusters.stream().anyMatch(mc -> mc.k() == 4 && mc.n() == 2), "no 2x2 cluster");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200)
    public static void hoistLiftsAndParksClear(GameTestHelper h) {
        floor(h); ship(h, 0);
        BlockPos hoist = new BlockPos(7, 12, 7);
        h.setBlock(hoist, ModRegistry.GANTRY_HOIST.get());
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(60, () -> {
            ColossusEntity c = find(h);
            h.assertTrue(c.onGroundC, "should have landed first");
            BlockPos ap = h.absolutePos(hoist);
            h.getLevel().setBlock(ap, h.getLevel().getBlockState(ap).setValue(com.colossalcraft.block.SimpleParts.GantryHoistBlock.ENGAGED, true), 3);
            ColossusEntity b = ColossusEntity.below(h.getLevel(), ap);
            h.assertTrue(b == c, "hoist doesn't see the Colossus");
            b.holdBy(ap);
            h.runAfterDelay(90, () -> {
                h.assertTrue(c.heldBy() != null && !c.onGroundC, "not held");
                double top = c.getY() + (c.model.localBounds.maxY - c.model.com.y);
                ColossalCraft.LOG.info("[test] hoist top={} hoist={}", top, ap.getY());
                h.assertTrue(top > ap.getY() - 0.3 && top <= ap.getY() + 1e-3, "should be winched right up to the hoist, top=" + top + " hoist=" + ap.getY());
                h.assertTrue(c.disassemble(null), "park failed");
                h.assertBlockPresent(Blocks.AIR, new BlockPos(7, 2, 7));      // the deck moved up, a big gap under it
                h.assertBlockNotPresent(Blocks.AIR, new BlockPos(7, 11, 7)); // the top sits right under the hoist
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16")
    public static void fuelCanBeTakenBackOut(GameTestHelper h) {
        h.setBlock(5, 1, 5, ModRegistry.FISSION_REACTOR.get()); // not formed: fuel stays in the slot
        var be = (com.colossalcraft.block.PowerBlockEntity) h.getBlockEntity(new BlockPos(5, 1, 5));
        be.fuel.setStackInSlot(0, new net.minecraft.world.item.ItemStack(ModRegistry.ENRICHED_FUEL_CELL.get(), 5));
        be.energy = com.colossalcraft.tier.EngineTier.FISSION.capacity; // full, so nothing burns
        h.assertTrue(be.fuel.extractItem(0, 64, false).getCount() == 5, "screen slot should give fuel back");
        be.fuel.setStackInSlot(0, new net.minecraft.world.item.ItemStack(ModRegistry.ENRICHED_FUEL_CELL.get(), 5));
        h.assertTrue(be.fuelPipe.extractItem(0, 64, false).isEmpty(), "pipes must not pull fuel out");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 160)
    public static void plantedFeetDontMove(GameTestHelper h) {
        walker(h, false, true);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        double[] maxSlide = {0}; int[] stanceTicks = {0};
        net.minecraft.world.phys.Vec3[][] last = {null};
        h.runAfterDelay(20, () -> { ColossusEntity c = find(h); c.autopilot = true; c.setInput(1, 0, false, false); last[0] = new net.minecraft.world.phys.Vec3[c.walker.foot.length]; });
        for (int t = 21; t <= 80; t++) h.runAfterDelay(t, () -> {
            ColossusEntity c = find(h);
            for (int i = 0; i < c.walker.foot.length; i++) {
                if (c.walker.state[i] == WalkerControllerAccess.STANCE && last[0][i] != null) { maxSlide[0] = Math.max(maxSlide[0], c.walker.foot[i].distanceTo(last[0][i])); stanceTicks[0]++; }
                last[0][i] = c.walker.state[i] == WalkerControllerAccess.STANCE ? c.walker.foot[i] : null;
            }
        });
        h.runAfterDelay(82, () -> {
            ColossalCraft.LOG.info("[test] planted feet: max slide {} over {} stance ticks, status {}", maxSlide[0], stanceTicks[0], find(h).walker.status);
            h.assertTrue(stanceTicks[0] > 20 && maxSlide[0] < 1e-6, "planted feet moved: " + maxSlide[0]);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200)
    public static void walkerSlipsOnIce(GameTestHelper h) {
        walker(h, false, true);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 0, z, Blocks.BLUE_ICE);
        double[] maxAcc = {0}, z0 = {0}; boolean[] slipped = {false};
        net.minecraft.world.phys.Vec3[] lastV = {null};
        h.runAfterDelay(20, () -> { ColossusEntity c = find(h); z0[0] = c.getZ(); c.autopilot = true; c.setInput(1, 0, false, false); lastV[0] = c.vel; });
        for (int t = 21; t <= 100; t++) h.runAfterDelay(t, () -> {
            ColossusEntity c = find(h);
            net.minecraft.world.phys.Vec3 dv = c.vel.subtract(lastV[0]); lastV[0] = c.vel;
            maxAcc[0] = Math.max(maxAcc[0], Math.sqrt(dv.x * dv.x + dv.z * dv.z) * 20);
            slipped[0] |= c.walker.status.contains("SLIP");
        });
        h.runAfterDelay(102, () -> {
            ColossusEntity c = find(h);
            double muG = StructureModel.grip(Blocks.BLUE_ICE.getFriction()) * 9.81;
            ColossalCraft.LOG.info("[test] ice dz={} maxAcc={} (grip limit {}) slipped={}", c.getZ() - z0[0], maxAcc[0], muG, slipped[0]);
            h.assertTrue(slipped[0], "feet should slip on ice");
            h.assertTrue(maxAcc[0] <= muG * 1.5 + 0.05, "accelerated faster than ice grip allows: " + maxAcc[0]);
            h.succeed();
        });
    }

    /** Like walker(), plus a knee on each leg (knee motor hangs off the upper leg). */
    static void kneeWalker(GameTestHelper h) {
        walker(h, false, true);
        BlockState knee = ModRegistry.JOINTS.get(MaterialTier.IRON).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        var mot = ModRegistry.MOTORS.get(MaterialTier.IRON).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        for (int x : new int[]{6, 8}) {
            h.setBlock(x, 2, 7, knee);
            h.setBlock(x, 3, 8, Blocks.STONE_BRICKS); // bracket on the upper leg
            h.setBlock(x, 2, 8, mot);                  // knee motor, held by the bracket
        }
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        var hc = (com.colossalcraft.block.HullCoreBlockEntity) h.getBlockEntity(new BlockPos(7, 7, 7));
        for (StructureModel.Joint j : r.model.joints) { CompoundTag c = new CompoundTag(); c.putByte("Mode", (byte) StructureModel.LEG); hc.joints.put(StructureModel.jointKey(j.key), c); }
        hc.setChanged();
    }

    @GameTest(template = "empty16", timeoutTicks = 200)
    public static void droppedWalkerLandsOnItsFeetAndWalks(GameTestHelper h) {
        walker(h, false, true);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 0, z, Blocks.AIR); // drop it onto the world's ground below
        double groundTop = h.absolutePos(new BlockPos(0, 0, 0)).getY();
        double[] z0 = {0}; boolean[] stepped = {false};
        h.runAfterDelay(40, () -> {
            ColossusEntity c = find(h);
            for (int i = 0; i < c.walker.foot.length; i++) {
                h.assertTrue(c.walker.state[i] == WalkerControllerAccess.STANCE, "foot " + i + " not planted: " + c.walker.status);
                h.assertTrue(Math.abs(c.walker.foot[i].y - groundTop) < 0.05, "foot " + i + " not on the surface: " + c.walker.foot[i].y + " vs " + groundTop);
            }
            z0[0] = c.getZ(); c.autopilot = true; c.setInput(1, 0, false, false);
        });
        for (int t = 41; t <= 140; t++) h.runAfterDelay(t, () -> stepped[0] |= find(h).walker.status.contains("STEP"));
        h.runAfterDelay(142, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] dropped walker dz={} stepped={} y={}", c.getZ() - z0[0], stepped[0], c.getY());
            h.assertTrue(stepped[0] && c.getZ() - z0[0] < -1.0, "should step forward, dz=" + (c.getZ() - z0[0]));
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200)
    public static void kneeWalkerWalks(GameTestHelper h) {
        kneeWalker(h);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertTrue(r.model.legChains.size() == 2 && r.model.legChains.get(0).joints().size() == 2, "expected 2 legs with hip+knee, got " + r.model.legChains.size());
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        double[] z0 = {0};
        h.runAfterDelay(20, () -> { ColossusEntity c = find(h); z0[0] = c.getZ(); c.autopilot = true; c.setInput(1, 0, false, false); });
        h.runAfterDelay(120, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] knee walker dz={} tilt={} status={}", c.getZ() - z0[0], c.tilt, c.walker.status);
            h.assertTrue(c.getZ() - z0[0] < -1.0 && c.tilt == 0, "knee walker should walk, dz=" + (c.getZ() - z0[0]));
            h.succeed();
        });
    }

    @GameTest(template = "empty16")
    public static void backwardGearboxInverts(GameTestHelper h) {
        floor(h); ship(h, 0);
        h.setBlock(7, 5, 7, ModRegistry.JOINTS.get(MaterialTier.IRON).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int y = 2; y <= 4; y++) h.setBlock(7, y, 7, Blocks.STONE_BRICKS);
        h.setBlock(8, 5, 7, ModRegistry.GEARBOXES.get(1).get().defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST)); // spur 2:1, but output points away
        h.setBlock(9, 5, 7, ModRegistry.MOTORS.get(MaterialTier.IRON).get());
        StructureModel.Joint j = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model.joints.get(0);
        h.assertTrue(Math.abs(j.maxSpeed - 60 * 6 * 2) < 0.01, "backwards spur should double speed, got " + j.maxSpeed);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200)
    public static void kneeWalkerHoldsItsHeight(GameTestHelper h) {
        kneeWalker(h);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        double[] y0 = {0};
        h.runAfterDelay(30, () -> y0[0] = find(h).getY());
        h.runAfterDelay(90, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] knee height y0={} y={} status={}", y0[0], c.getY(), c.walker.status);
            h.assertTrue(Math.abs(c.getY() - y0[0]) < 0.3, "knee walker sagged: " + (c.getY() - y0[0]));
            h.succeed();
        });
    }

    @GameTest(template = "empty16")
    public static void ballJointKeepsFootLevel(GameTestHelper h) {
        kneeWalker(h);
        // ball joint above each foot block
        var ball = ModRegistry.JOINT_SHAPES.get(MaterialTier.IRON).get(com.colossalcraft.block.SimpleParts.JointShape.BALL).get();
        for (int x : new int[]{6, 8}) { h.setBlock(x, 1, 7, ball); h.setBlock(x, 0, 7, ModRegistry.GANTRY_SCAFFOLD.get()); }
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        StructureModel m = r.model;
        h.assertTrue(m.joints.stream().anyMatch(j -> j.ball), "no ball joint found");
        // turn the knees by 30 degrees: the part under a ball joint must stay level
        for (StructureModel.Joint j : m.joints) if (!j.ball && j.mode == StructureModel.LEG) { m.ikAngles[j.id] = Math.toRadians(30); }
        var tf = m.transforms(0, 0, 0, 0);
        for (StructureModel.Joint j : m.joints) if (j.ball && !j.childSegs.isEmpty()) {
            var q = tf[j.childSegs.get(0)].getNormalizedRotation(new org.joml.Quaterniond());
            double ang = Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(q.w))));
            h.assertTrue(ang < 0.5, "foot under ball joint tilted by " + ang + " degrees");
        }
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200)
    public static void jumpDriveLaunches(GameTestHelper h) {
        walker(h, false, true);
        h.setBlock(6, 7, 6, ModRegistry.JUMP_DRIVE.get());
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        double[] y0 = {0}, peak = {-1e9};
        h.runAfterDelay(20, () -> { ColossusEntity c = find(h); c.jumpCharge = com.colossalcraft.block.SimpleParts.JumpDriveBlock.CAPACITY; y0[0] = c.getY(); c.autopilot = true; c.setInput(0, 0, false, false, false, true); });
        for (int t = 21; t <= 60; t++) h.runAfterDelay(t, () -> peak[0] = Math.max(peak[0], find(h).getY()));
        h.runAfterDelay(62, () -> {
            ColossalCraft.LOG.info("[test] jump peak +{}", peak[0] - y0[0]);
            h.assertTrue(peak[0] - y0[0] > 1.0, "jump drive should launch it, peak +" + (peak[0] - y0[0]));
            h.succeed();
        });
    }

    /** A mech like the player's: wide torso, legs outside it with hip, thigh, knee, shin, ball ankle and a 3-long foot. */
    static void bigMech(GameTestHelper h, boolean ankles) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 0, z, ModRegistry.GANTRY_SCAFFOLD.get());
        for (int x = 5; x <= 9; x++) for (int z = 6; z <= 8; z++) for (int y = 8; y <= 9; y++) h.setBlock(x, y, z, Blocks.STONE_BRICKS);
        h.setBlock(7, 10, 7, ModRegistry.HULL_CORE.get());
        h.setBlock(7, 10, 8, ModRegistry.PILOT_SEAT.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        h.setBlock(6, 10, 7, ModRegistry.FLUX_ACCUMULATOR.get()); // 2000 CJ/t: enough for four diamond motors
        CompoundTag t = new CompoundTag(); t.putLong("Energy", 4_000_000);
        h.getBlockEntity(new BlockPos(6, 10, 7)).loadWithComponents(t, h.getLevel().registryAccess());
        h.setBlock(8, 10, 7, ModRegistry.SMALL_GYRO.get());
        h.setBlock(7, 10, 6, ModRegistry.LOCOMOTION_CONTROLLER.get());
        BlockState hipJ = ModRegistry.JOINTS.get(MaterialTier.DIAMOND).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState kneeJ = ModRegistry.JOINTS.get(MaterialTier.DIAMOND).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        var mot = ModRegistry.MOTORS.get(MaterialTier.DIAMOND).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        var ball = ModRegistry.JOINT_SHAPES.get(MaterialTier.DIAMOND).get(com.colossalcraft.block.SimpleParts.JointShape.BALL).get().defaultBlockState();
        for (int x : new int[]{4, 10}) {
            h.setBlock(x, 8, 7, hipJ); h.setBlock(x, 9, 7, mot);                 // hip on the torso side, motor above it on the torso
            h.setBlock(x, 7, 7, Blocks.STONE_BRICKS); h.setBlock(x, 6, 7, Blocks.STONE_BRICKS); // thigh
            h.setBlock(x, 5, 7, kneeJ); h.setBlock(x, 6, 6, Blocks.STONE_BRICKS); h.setBlock(x, 5, 6, mot); // knee + motor on a bracket
            h.setBlock(x, 4, 7, Blocks.STONE_BRICKS); h.setBlock(x, 3, 7, Blocks.STONE_BRICKS); // shin
            h.setBlock(x, 2, 7, ankles ? ball : Blocks.STONE_BRICKS.defaultBlockState());
            for (int z = 6; z <= 8; z++) h.setBlock(x, 1, z, Blocks.STONE_BRICKS);    // foot
        }
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 10, 7)));
        var hc = (com.colossalcraft.block.HullCoreBlockEntity) h.getBlockEntity(new BlockPos(7, 10, 7));
        for (StructureModel.Joint j : r.model.joints) if (!j.ball) { CompoundTag c = new CompoundTag(); c.putByte("Mode", (byte) StructureModel.LEG); hc.joints.put(StructureModel.jointKey(j.key), c); }
        hc.setChanged();
    }

    @GameTest(template = "empty16")
    public static void standingLoadsFromTheFeetUp(GameTestHelper h) {
        bigMech(h, true);
        StructureModel m = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 10, 7))).model;
        java.util.Map<Integer, java.util.List<StructureModel.Joint>> byY = new java.util.TreeMap<>();
        for (StructureModel.Joint j : m.joints) {
            ColossalCraft.LOG.info("[test] J{} y={} ball={} share={} stand={} standTq={} hang={} swingTq={}", j.id, j.pivot.y, j.ball, j.footShare, j.standMass, j.standTorque, j.subMass, j.reqTorque);
            byY.computeIfAbsent((int) Math.floor(j.pivot.y), k -> new java.util.ArrayList<>()).add(j);
        }
        double prev = -1;
        for (var e : byY.entrySet()) { // top to bottom order is reversed: lower y first
            var l = e.getValue();
            h.assertTrue(l.size() == 2, "expected a pair of joints at y=" + e.getKey());
            h.assertTrue(Math.abs(l.get(0).standMass - l.get(1).standMass) < 1e-6, "legs should carry the same: " + l.get(0).standMass + " vs " + l.get(1).standMass);
            h.assertTrue(Math.abs(l.get(0).footShare - 0.5) < 1e-6, "each foot should carry half, got " + l.get(0).footShare);
            if (prev >= 0) h.assertTrue(l.get(0).standMass < prev, "upper joint should carry less than the one below");
            prev = l.get(0).standMass;
        }
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 260)
    public static void bigMechWalksSmoothly(GameTestHelper h) {
        bigMech(h, true);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 10, 7)));
        ColossalCraft.LOG.info("[test] big mech: mass {} legs {} warnings {} errors {}", r.model.mass, r.model.legChains.size(), r.warnings, r.errors);
        h.assertTrue(r.model.legChains.size() == 2, "expected 2 legs, got " + r.model.legChains.size());
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 10, 7)), null), "assemble failed");
        double[] start = new double[3]; double[] jitter = {0}; net.minecraft.world.phys.Vec3[] lastV = {null};
        double[] slide = {0}; int[] bothUp = {0}; double[] maxTurn = {0}; int[] flips = {0}; double[][] lastA = {null}; net.minecraft.world.phys.Vec3[][] lastF = {null}; WalkerController.State[][] lastS = {null};
        h.runAfterDelay(30, () -> { ColossusEntity c = find(h); start[0] = c.getZ(); start[1] = c.getY(); c.autopilot = true; c.setInput(1, 0, false, false); lastV[0] = c.vel; });
        for (int t = 31; t <= 200; t++) { int tt = t; h.runAfterDelay(t, () -> {
            ColossusEntity c = find(h);
            if (c.getZ() - start[0] < -4.5) c.setInput(0, 0, false, false); // stop before the edge of the test area, then stand
            net.minecraft.world.phys.Vec3 dv = c.vel.subtract(lastV[0]); lastV[0] = c.vel;
            jitter[0] += dv.length();
            var w = c.walker;
            if (lastF[0] != null) for (int i = 0; i < w.foot.length; i++)
                if (w.state[i] == WalkerController.State.STANCE && lastS[0][i] == WalkerController.State.STANCE) slide[0] = Math.max(slide[0], w.foot[i].subtract(lastF[0][i]).length());
            int up = 0; for (var st : w.state) if (st != WalkerController.State.STANCE) up++;
            if (up == w.state.length) bothUp[0]++;
            lastF[0] = w.foot.clone(); lastS[0] = w.state.clone();
            double[] A = c.model.ikAngles.clone();
            if (lastA[0] != null) for (StructureModel.LegChain L : c.model.legChains) for (int k = 0; k < L.joints().size(); k++) {
                int j = L.joints().get(k); double a0 = lastA[0][j], a1 = A[j];
                if (Double.isNaN(a0) || Double.isNaN(a1)) continue;
                maxTurn[0] = Math.max(maxTurn[0], Math.abs(a1 - a0));
                if (k > 0 && Math.abs(a0) > 0.3 && Math.abs(a1) > 0.3 && Math.signum(a0) != Math.signum(a1)) flips[0]++; // knee crossed over
            }
            lastA[0] = A;
            if (tt % 10 == 0) ColossalCraft.LOG.info("[test] big mech t={} pos={} vel={} tilt={} feet={}", tt, c.position(), c.vel, c.tilt, c.walker.status);
        }); }
        h.runAfterDelay(202, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] big mech dz={} dy={} jitter(sum |dv|)={} tilt={} maxStanceSlide={} bothUpTicks={}", c.getZ() - start[0], c.getY() - start[1], jitter[0], c.tilt, slide[0], bothUp[0]);
            h.assertTrue(slide[0] < 0.02, "a planted foot slid " + slide[0]);
            ColossalCraft.LOG.info("[test] big mech IK maxTurn/tick={} kneeFlips={}", maxTurn[0], flips[0]);
            h.assertTrue(maxTurn[0] <= 0.6001, "a joint jumped " + maxTurn[0] + " rad in one tick");
            h.assertTrue(flips[0] == 0, "a knee snapped to the other side " + flips[0] + " times");
            h.assertTrue(bothUp[0] == 0, "both feet were off the ground for " + bothUp[0] + " ticks");
            h.assertTrue(c.getZ() - start[0] < -2.0, "big mech should walk forward, dz=" + (c.getZ() - start[0]));
            h.assertTrue(c.tilt == 0, "big mech fell over");
            h.assertTrue(Math.abs(c.getY() - start[1]) < 1.0, "big mech sank or rose: " + (c.getY() - start[1]));
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 260)
    public static void bigMechClimbsAStep(GameTestHelper h) {
        bigMech(h, true);
        for (int x = 0; x < 16; x++) for (int z = 0; z <= 4; z++) h.setBlock(x, 1, z, Blocks.STONE); // a 1-block step ahead
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 10, 7)), null), "assemble failed");
        double[] y0 = {0}, pen = {0}, z0s = {0};
        h.runAfterDelay(30, () -> { ColossusEntity c = find(h); y0[0] = c.getY(); z0s[0] = c.getZ(); c.autopilot = true; c.setInput(1, 0, false, false); });
        for (int t = 31; t <= 230; t++) { int tt = t; h.runAfterDelay(t, () -> {
            ColossusEntity c = find(h);
            if (c.getZ() - z0s[0] < -5.0) c.setInput(0, 0, false, false); // on the step: stop before the edge
            double p = WalkerController.maxLegPenetration(c);
            if (p > pen[0]) { pen[0] = p; ColossalCraft.LOG.info("[test] step t={} penetration {} feet {}", tt, p, c.walker.status); }
        }); }
        h.runAfterDelay(232, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] step climb dy={} z={} tilt={} maxPen={}", c.getY() - y0[0], c.getZ(), c.tilt, pen[0]);
            h.assertTrue(pen[0] < 0.15, "a leg sank into the ground by " + pen[0]);
            h.assertTrue(c.tilt == 0, "fell over on the step");
            h.assertTrue(c.getY() - y0[0] > 0.6, "should have climbed the step, dy=" + (c.getY() - y0[0]));
            h.succeed();
        });
    }

    // ---------------------------------------------------------------- live blocks on a built Colossus
    /** Create sends its own packets to every joining player, which test (mock) players can't take: these tests run without Create. */
    static boolean skipWithCreate(GameTestHelper h) { if (net.neoforged.fml.ModList.get().isLoaded("create")) { h.succeed(); return true; } return false; }
    static net.minecraft.server.level.ServerPlayer playerAt(GameTestHelper h, ColossusEntity c, BlockPos local) {
        var sp = h.makeMockServerPlayerInLevel();
        net.minecraft.world.phys.Vec3 w = c.blockWorldCenter(local);
        sp.teleportTo(w.x + 1.5, w.y, w.z + 0.5);
        return sp;
    }
    static void act(net.minecraft.server.level.ServerPlayer sp, ColossusEntity c, BlockPos local, Direction face, int action) {
        com.colossalcraft.colossus.ColossusInteract.handle(sp, new com.colossalcraft.network.Net.ColossusBlock(c.getId(), local.asLong(), face.get3DDataValue(), action));
    }

    @GameTest(template = "empty16", timeoutTicks = 120)
    public static void chestOpensWhileBuilt(GameTestHelper h) {
        if (skipWithCreate(h)) return;
        floor(h); ship(h, 0);
        h.setBlock(8, 7, 7, Blocks.CHEST);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(40, () -> {
            ColossusEntity c = find(h);
            var sp = playerAt(h, c, new BlockPos(1, 0, 0));
            act(sp, c, new BlockPos(1, 0, 0), Direction.UP, 0);
            h.assertTrue(sp.containerMenu instanceof net.minecraft.world.inventory.ChestMenu, "chest didn't open: " + sp.containerMenu);
            sp.containerMenu.getSlot(3).set(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 5));
            sp.closeContainer();
            h.assertTrue(c.disassemble(null), "park failed");
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getBlockEntity(new BlockPos(8, 2, 7));
            h.assertTrue(chest.getItem(3).getCount() == 5, "item put in on the move was lost: " + chest.getItem(3));
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public static void doorOpensWhileBuilt(GameTestHelper h) {
        if (skipWithCreate(h)) return;
        floor(h); ship(h, 0);
        h.setBlock(9, 7, 7, Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
        h.setBlock(9, 8, 7, Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(40, () -> {
            ColossusEntity c = find(h);
            var sp = playerAt(h, c, new BlockPos(2, 0, 0));
            act(sp, c, new BlockPos(2, 1, 0), Direction.EAST, 0); // click the top half
            var open = net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN;
            h.assertTrue(c.blocks.get(new BlockPos(2, 0, 0)).getValue(open) && c.blocks.get(new BlockPos(2, 1, 0)).getValue(open), "door didn't open");
            act(sp, c, new BlockPos(2, 0, 0), Direction.EAST, 0);
            h.assertTrue(!c.blocks.get(new BlockPos(2, 0, 0)).getValue(open), "door didn't close");
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 260)
    public static void minedBlockLetsAPieceFallOff(GameTestHelper h) {
        if (skipWithCreate(h)) return;
        floor(h); ship(h, 0);
        h.setBlock(10, 6, 7, Blocks.STONE_BRICKS); h.setBlock(11, 6, 7, Blocks.STONE_BRICKS); h.setBlock(12, 6, 7, Blocks.STONE_BRICKS); // an arm off the deck
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(40, () -> {
            ColossusEntity c = find(h);
            int n0 = c.blocks.size();
            var sp = playerAt(h, c, new BlockPos(3, -1, 0));
            sp.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            act(sp, c, new BlockPos(3, -1, 0), Direction.UP, 1);
            h.assertTrue(!c.blocks.containsKey(new BlockPos(3, -1, 0)), "block wasn't mined");
            h.assertTrue(c.blocks.size() == n0 - 3, "the cut-off end should have broken away: " + (n0 - c.blocks.size()) + " gone");
            long wrecks = h.getLevel().getEntitiesOfClass(ColossusEntity.class, h.getBounds().inflate(8)).stream().filter(e -> e.wreck).count();
            h.assertTrue(wrecks == 1, "expected a falling wreck, found " + wrecks);
        });
        h.runAfterDelay(200, () -> {
            long wrecks = h.getLevel().getEntitiesOfClass(ColossusEntity.class, h.getBounds().inflate(8)).stream().filter(e -> e.wreck).count();
            h.assertTrue(wrecks == 0, "the wreck should have settled into blocks");
            int bricks = 0;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y < 4; y++) if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.STONE_BRICKS)) bricks++;
            h.assertTrue(bricks == 2, "the broken-off piece should lie on the ground as 2 blocks, found " + bricks);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public static void blockPlacedWhileBuilt(GameTestHelper h) {
        if (skipWithCreate(h)) return;
        floor(h); ship(h, 0);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(40, () -> {
            ColossusEntity c = find(h);
            var sp = playerAt(h, c, new BlockPos(2, -1, 2));
            sp.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FURNACE, 3));
            act(sp, c, new BlockPos(2, -1, 2), Direction.UP, 0);
            h.assertTrue(c.blocks.containsKey(new BlockPos(2, 0, 2)) && c.blocks.get(new BlockPos(2, 0, 2)).is(Blocks.FURNACE), "block not placed on the deck");
            h.assertTrue(c.bes.containsKey(new BlockPos(2, 0, 2)), "placed furnace has no block entity data");
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 260)
    public static void explosionBreaksALegOff(GameTestHelper h) {
        walker(h, false, true);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        int[] j0 = {0};
        h.runAfterDelay(30, () -> {
            ColossusEntity c = find(h);
            j0[0] = c.model.joints.size();
            StructureModel.Joint j = c.model.joints.get(0);
            c.explode(c.blockWorldCenter(j.blocks.get(0)), 4f); // TNT right at a hip
            h.assertTrue(c.isRemoved() || c.model.joints.size() < j0[0], "the hip joint should have broken");
            long wrecks = h.getLevel().getEntitiesOfClass(ColossusEntity.class, h.getBounds().inflate(8)).stream().filter(e -> e.wreck).count();
            h.assertTrue(wrecks >= 1, "the leg should fall off as a wreck");
        });
        h.runAfterDelay(240, () -> {
            long wrecks = h.getLevel().getEntitiesOfClass(ColossusEntity.class, h.getBounds().inflate(8)).stream().filter(e -> e.wreck).count();
            h.assertTrue(wrecks == 0, "the leg should have settled into blocks");
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public static void engineBurnsFuelWhileBuilt(GameTestHelper h) {
        floor(h); ship(h, 0);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        long[] e0 = {0};
        h.runAfterDelay(20, () -> {
            ColossusEntity c = find(h);
            // the engine screen's fuel slot is this handler (opening the screen itself needs a real client)
            var pe = (com.colossalcraft.block.PowerBlockEntity) c.liveBe(new BlockPos(-1, 0, 0));
            pe.fuel.setStackInSlot(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, 2));
            e0[0] = c.energy;
        });
        h.runAfterDelay(30, () -> {
            ColossusEntity c = find(h);
            h.assertTrue(c.energy > e0[0] + 1000, "coal put in on the move should add power: " + e0[0] + " -> " + c.energy);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 220)
    public static void stepControllerAvoidsDangerousGround(GameTestHelper h) {
        walker(h, false, true);
        h.setBlock(8, 7, 8, ModRegistry.STEP_CONTROLLER.get());
        // magma right where the feet would normally land, with safe ground beside it
        for (int z = 2; z <= 5; z++) { h.setBlock(6, 0, z, Blocks.MAGMA_BLOCK); h.setBlock(8, 0, z, Blocks.MAGMA_BLOCK); }
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        double[] z0 = {0}; boolean[] burned = {false}; boolean[] planned = {false};
        h.runAfterDelay(20, () -> { ColossusEntity c = find(h); z0[0] = c.getZ(); c.autopilot = true; c.setInput(1, 0, false, false); });
        for (int t = 21; t <= 200; t++) h.runAfterDelay(t, () -> {
            ColossusEntity c = find(h);
            if (c.getZ() - z0[0] < -6) c.setInput(0, 0, false, false);
            planned[0] |= c.walker.planner;
            for (int i = 0; i < c.walker.foot.length; i++) if (c.walker.state[i] == WalkerController.State.STANCE) {
                var f = c.walker.foot[i];
                if (h.getLevel().getBlockState(BlockPos.containing(f.x, f.y - 0.05, f.z)).is(Blocks.MAGMA_BLOCK)) burned[0] = true;
            }
        });
        h.runAfterDelay(205, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] step planner dz={} burned={} planned={} status={}", c.getZ() - z0[0], burned[0], planned[0], c.walker.status);
            h.assertTrue(planned[0], "the Step Controller should be planning (powered)");
            h.assertTrue(!burned[0], "a foot was planted on magma");
            h.assertTrue(c.getZ() - z0[0] < -3, "should still walk past the magma, dz=" + (c.getZ() - z0[0]));
            h.succeed();
        });
    }
}
