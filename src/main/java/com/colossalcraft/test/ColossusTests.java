package com.colossalcraft.test;

import com.colossalcraft.colossus.WalkerController;

import com.colossalcraft.ColossalCraft;
import com.colossalcraft.ModRegistry;
import com.colossalcraft.colossus.ColossusEntity;
import com.colossalcraft.colossus.StructureModel;
import com.colossalcraft.colossus.StructureScan;
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
        // a small pin joint (a quarter of a bearing) hanging under the deck edge with an 8-block stone column (~5 t) below it
        h.setBlock(5, 5, 5, Blocks.AIR);
        h.setBlock(5, 5, 5, ModRegistry.PIN_JOINT.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int y = 1; y <= 4; y++) for (int z = 4; z <= 5; z++) h.setBlock(5, y, z == 5 ? 5 : 4, Blocks.STONE_BRICKS);
        h.setBlock(5, 0, 5, Blocks.AIR); h.setBlock(5, 0, 4, Blocks.AIR);
        BlockPos heart = h.absolutePos(new BlockPos(7, 7, 7));
        StructureScan.Result r = StructureScan.scan(h.getLevel(), heart);
        h.assertTrue(r.model.joints.size() == 1, "expected 1 joint, got " + r.model.joints.size());
        StructureModel.Joint j = r.model.joints.get(0);
        ColossalCraft.LOG.info("[test] joint sub={} ratio={} parent={} children={}", j.subMass, j.staticRatio, j.parentSeg, j.childSegs);
        h.assertTrue(j.parentSeg == 0 && !j.childSegs.isEmpty(), "joint tree wrong");
        h.assertTrue(j.staticRatio > 1, "pin joint should be overloaded: " + j.staticRatio);
        // a 2x2 bearing assembly (4x a 1x1 bearing, 16x a pin) holds it easily
        double bigRatio = j.subMass * 9.81 / (com.colossalcraft.tier.Parts.BEARING_FORCE * com.colossalcraft.tier.Parts.scale(2));
        h.assertTrue(bigRatio < 1, "a 2x2 bearing should hold");
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
        // panel + slab + pin in a row along X, same axis -> one joint, strength 0.5 + 0.5 + 0.25 (small joints join; bearings join only in squares)
        h.setBlock(6, 5, 7, ModRegistry.PANEL_JOINT.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        h.setBlock(7, 5, 7, ModRegistry.SLAB_JOINT.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        h.setBlock(8, 5, 7, ModRegistry.PIN_JOINT.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int x = 6; x <= 8; x++) h.setBlock(x, 4, 7, Blocks.STONE_BRICKS);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        h.assertTrue(r.model.joints.size() == 1, "expected 1 joint, got " + r.model.joints.size());
        h.assertTrue(Math.abs(r.model.joints.get(0).area - 1.25) < 1e-6, "strength should be 1.25, got " + r.model.joints.get(0).area);
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
        h.setBlock(6, 7, 7, ModRegistry.FLUX_ACCUMULATOR.get()); // enough power for full-speed steps and smart footing
        CompoundTag t = new CompoundTag(); t.putLong("Energy", 4_000_000);
        h.getBlockEntity(new BlockPos(6, 7, 7)).loadWithComponents(t, h.getLevel().registryAccess());
        if (coreGyro) h.setBlock(8, 7, 7, ModRegistry.SMALL_GYRO.get());
        h.setBlock(7, 7, 6, ModRegistry.LOCOMOTION_CONTROLLER.get());
        BlockState joint = ModRegistry.BEARING.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        for (int x : new int[]{6, 8}) {
            h.setBlock(x, 5, 7, joint);
            h.setBlock(x == 6 ? 5 : 9, 5, 7, ModRegistry.MOTOR.get());
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
    public static void walkerWithHeavyOverhangBalances(GameTestHelper h) {
        walker(h, false, true);
        for (int x = 5; x <= 9; x++) for (int z = 2; z <= 4; z++) h.setBlock(x, 6, z, Blocks.IRON_BLOCK);
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        ColossalCraft.LOG.info("[test] overhang com={} support={} offset={}", r.model.com, r.model.support, r.model.balanceOffset);
        h.assertTrue(r.model.footingOk() && !r.model.balanced(), "should be unbalanced");
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(80, () -> { ColossusEntity c = find(h); ColossalCraft.LOG.info("[test] overhang walker tilt={} feet={}", c.tilt, c.walker.status); h.assertTrue(c.tilt == 0, "a walker should balance itself (lean, step under the weight): tilt " + c.tilt); h.succeed(); });
    }

    @GameTest(template = "empty16", timeoutTicks = 120)
    public static void statueWithHeavyOverhangTips(GameTestHelper h) {
        walker(h, false, true);
        h.setBlock(7, 7, 6, Blocks.AIR); // no Locomotion Controller: nothing balances it
        for (int x = 5; x <= 9; x++) for (int z = 2; z <= 4; z++) h.setBlock(x, 6, z, Blocks.IRON_BLOCK);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(80, () -> { ColossusEntity c = find(h); h.assertTrue(c.tilt > 1.0 && Math.sin(c.tiltDir) < -0.9, "should tip towards the overhang (-Z): tilt " + c.tilt + " dir " + c.tiltDir); h.succeed(); });
    }

    @GameTest(template = "empty16")
    public static void gearboxMultipliesTorque(GameTestHelper h) {
        floor(h); ship(h, 0);
        h.setBlock(7, 5, 7, ModRegistry.BEARING.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int y = 2; y <= 4; y++) h.setBlock(7, y, 7, Blocks.STONE_BRICKS);
        h.setBlock(8, 5, 7, ModRegistry.REDUCTION_GEARBOX.get().defaultBlockState().setValue(DirectionalBlock.FACING, Direction.WEST)); // 5:1, output towards the joint
        h.setBlock(9, 5, 7, ModRegistry.MOTOR.get()); // motor touches the gearbox only
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
        var panel = ModRegistry.PANEL_JOINT.get();
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
        h.setBlock(7, 8, 7, ModRegistry.BEARING.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
        h.setBlock(8, 8, 7, ModRegistry.REDUCTION_GEARBOX.get().defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST)); // 5:1 placed backwards: 5x as fast
        for (int x = 8; x <= 10; x++) for (int z = 6; z <= 8; z++) h.setBlock(x, 7, z, ModRegistry.MOTOR.get()); // a 3x3 motor: 3^3 = 27x the torque
        h.setBlock(7, 9, 7, Blocks.OAK_PLANKS); h.setBlock(7, 10, 7, Blocks.OAK_PLANKS);
        for (int r = 1; r <= 4; r++) {
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
        h.assertTrue(j.blades == 16 && j.active(), "rotor not set up: blades " + j.blades);
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
        h.setBlock(7, 5, 7, ModRegistry.BEARING.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int y = 2; y <= 4; y++) h.setBlock(7, y, 7, Blocks.STONE_BRICKS);
        var motor = ModRegistry.MOTOR.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        h.setBlock(8, 5, 7, motor); h.setBlock(8, 5, 8, motor); h.setBlock(8, 4, 7, motor); h.setBlock(8, 4, 8, motor); // 2x2 grid, one touches the joint
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        StructureModel.Joint j = r.model.joints.get(0);
        h.assertTrue(Math.abs(j.motorTorque - 4 * 150_000) < 1, "a 2x2 motor should give 2^2 = 4x the torque: " + j.motorTorque);
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
        BlockState knee = ModRegistry.BEARING.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        var mot = ModRegistry.MOTOR.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
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
        h.setBlock(7, 5, 7, ModRegistry.BEARING.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int y = 2; y <= 4; y++) h.setBlock(7, y, 7, Blocks.STONE_BRICKS);
        h.setBlock(8, 5, 7, ModRegistry.GEARBOX.get().defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST)); // 2:1, but output points away
        h.setBlock(9, 5, 7, ModRegistry.MOTOR.get());
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
        var ball = ModRegistry.BALL_JOINT.get();
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
        for (int x = 5; x <= 9; x++) for (int z = 6; z <= 8; z++) h.setBlock(x, 8, z, Blocks.OAK_PLANKS); // light torso: a plank deck
        for (int x = 5; x <= 9; x++) h.setBlock(x, 9, 7, Blocks.OAK_PLANKS);
        h.setBlock(7, 9, 6, Blocks.OAK_PLANKS); h.setBlock(7, 9, 8, Blocks.OAK_PLANKS);
        h.setBlock(7, 10, 7, ModRegistry.HULL_CORE.get());
        h.setBlock(7, 10, 8, ModRegistry.PILOT_SEAT.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        h.setBlock(6, 10, 7, ModRegistry.FLUX_ACCUMULATOR.get()); // 2000 CJ/t: enough for four diamond motors
        CompoundTag t = new CompoundTag(); t.putLong("Energy", 4_000_000);
        h.getBlockEntity(new BlockPos(6, 10, 7)).loadWithComponents(t, h.getLevel().registryAccess());
        h.setBlock(8, 10, 7, ModRegistry.SMALL_GYRO.get());
        h.setBlock(7, 10, 6, ModRegistry.LOCOMOTION_CONTROLLER.get());
        BlockState hipJ = ModRegistry.BEARING.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState kneeJ = ModRegistry.BEARING.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        var mot = ModRegistry.MOTOR.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        var ball = ModRegistry.BALL_JOINT.get().defaultBlockState();
        for (int x : new int[]{4, 10}) {
            h.setBlock(x, 8, 7, hipJ);
            for (int y = 9; y <= 10; y++) for (int z = 7; z <= 8; z++) h.setBlock(x, y, z, mot); // a 2x2 hip motor (4x torque) above the hip
            h.setBlock(x, 7, 7, Blocks.OAK_LOG); h.setBlock(x, 6, 7, Blocks.OAK_LOG); // thigh
            h.setBlock(x, 5, 7, kneeJ);
            for (int y = 5; y <= 6; y++) for (int z = 5; z <= 6; z++) h.setBlock(x, y, z, mot); // a 2x2 knee motor on the thigh (4x torque: holds it up straight)
            h.setBlock(x, 4, 7, Blocks.OAK_LOG); h.setBlock(x, 3, 7, Blocks.OAK_LOG); // shin
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
        double[] slide = {0}; int[] bothUp = {0}; double[] maxAcc = {0}; int[] waits = {0}; double[] maxTurn = {0}; int[] flips = {0}; double[][] lastA = {null}; net.minecraft.world.phys.Vec3[][] lastF = {null}; WalkerController.State[][] lastS = {null};
        h.runAfterDelay(30, () -> { ColossusEntity c = find(h); start[0] = c.getZ(); start[1] = c.getY(); c.autopilot = true; c.setInput(1, 0, false, false); lastV[0] = c.vel; });
        for (int t = 31; t <= 200; t++) { int tt = t; h.runAfterDelay(t, () -> {
            ColossusEntity c = find(h);
            if (c.getZ() - start[0] < -1.5) c.setInput(0, 0, false, false); // stop before the edge of the test area (its walls stop legs now), then stand
            net.minecraft.world.phys.Vec3 dv = c.vel.subtract(lastV[0]); lastV[0] = c.vel;
            jitter[0] += dv.length();
            maxAcc[0] = Math.max(maxAcc[0], Math.sqrt(dv.x * dv.x + dv.z * dv.z) * 20);
            if (c.walker.status.contains("shifting")) waits[0]++;
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
            ColossalCraft.LOG.info("[test] big mech smoothness: max horizontal accel {} m/s2, ticks spent waiting for balance {}", maxAcc[0], waits[0]);
            h.assertTrue(maxAcc[0] <= 2.6, "the body lurched: " + maxAcc[0] + " m/s2");
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
        double[] y0 = {0}, pen = {0}, z0s = {0}; int[] deepTicks = {0};
        h.runAfterDelay(30, () -> { ColossusEntity c = find(h); y0[0] = c.getY(); z0s[0] = c.getZ(); c.autopilot = true; c.setInput(1, 0, false, false); });
        for (int t = 31; t <= 230; t++) { int tt = t; h.runAfterDelay(t, () -> {
            ColossusEntity c = find(h);
            if (c.getZ() - z0s[0] < -3.2) c.setInput(0, 0, false, false); // on the step: stop before the edge
            double p = WalkerController.maxLegPenetration(c);
            if (p > 0.15) deepTicks[0]++;
            if (p > pen[0]) { pen[0] = p; ColossalCraft.LOG.info("[test] step t={} penetration {} feet {}", tt, p, c.walker.status); }
        }); }
        h.runAfterDelay(232, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] step climb dy={} z={} tilt={} maxPen={}", c.getY() - y0[0], c.getZ(), c.tilt, pen[0]);
            h.assertTrue(deepTicks[0] <= 3 && pen[0] < 0.6, "a leg stayed in the ground: " + deepTicks[0] + " ticks deeper than 0.15 (deepest " + pen[0] + ")"); // a one-tick scrape as a foot lifts is allowed
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

    @GameTest(template = "empty16", timeoutTicks = 120)
    public static void redstoneWorksWhileBuilt(GameTestHelper h) {
        if (skipWithCreate(h)) return;
        floor(h); ship(h, 0);
        h.setBlock(8, 7, 6, Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        h.setBlock(9, 7, 6, Blocks.REDSTONE_LAMP);
        h.setBlock(9, 7, 7, Blocks.OAK_TRAPDOOR);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(30, () -> {
            ColossusEntity c = find(h);
            var sp = playerAt(h, c, new BlockPos(1, 0, -1));
            act(sp, c, new BlockPos(1, 0, -1), Direction.UP, 0); // flip the lever: the real lever in the dock powers the real lamp
            act(sp, c, new BlockPos(2, 0, 0), Direction.UP, 0);  // and open the trapdoor
        });
        h.runAfterDelay(40, () -> {
            ColossusEntity c = find(h);
            var lit = net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT;
            boolean dock = com.colossalcraft.colossus.ColossusDock.level(h.getLevel().getServer()) != null; // the test server has no extra dimensions
            ColossalCraft.LOG.info("[test] dock present: {}", dock);
            h.assertTrue(c.blocks.get(new BlockPos(1, 0, -1)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED), "lever didn't flip");
            h.assertTrue(!dock || c.blocks.get(new BlockPos(2, 0, -1)).getValue(lit), "the lamp on the Colossus should light from the lever");
            h.assertTrue(c.blocks.get(new BlockPos(2, 0, 0)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN), "trapdoor didn't open");
            h.assertTrue(c.disassemble(null), "park failed");
        });
        h.runAfterDelay(45, () -> {
            h.assertTrue(h.getBlockState(new BlockPos(9, 2, 6)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT) || h.getBlockState(new BlockPos(9, 2, 6)).is(Blocks.REDSTONE_LAMP), "lamp should be parked");
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200)
    public static void wheelsDriveWithSpinKeys(GameTestHelper h) {
        floor(h); ship(h, 0);
        var bearing = ModRegistry.BEARING.get().defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.X).setValue(com.colossalcraft.block.SimpleParts.LONE, true);
        var motor = ModRegistry.MOTOR.get().defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.X).setValue(com.colossalcraft.block.SimpleParts.LONE, true);
        for (int side : new int[]{-1, 1}) for (int z0 : new int[]{5, 9}) {
            int bx = side < 0 ? 4 : 10, wx = side < 0 ? 3 : 11;
            h.setBlock(bx, 6, z0, bearing);
            h.setBlock(bx, 6, z0 == 5 ? 6 : 8, motor);
            int ax = side < 0 ? 3 : 11; wx = side < 0 ? 2 : 12;
            h.setBlock(ax, 6, z0, Blocks.OAK_LOG); // axle, then a small "+" wheel
            for (int y = 5; y <= 7; y++) h.setBlock(wx, y, z0, Blocks.OAK_LOG);
            h.setBlock(wx, 6, z0 - 1, Blocks.OAK_LOG); h.setBlock(wx, 6, z0 + 1, Blocks.OAK_LOG);
        }
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        var hc = (com.colossalcraft.block.HullCoreBlockEntity) h.getBlockEntity(new BlockPos(7, 7, 7));
        hc.joints = new CompoundTag();
        for (StructureModel.Joint j : r.model.joints) { CompoundTag c = new CompoundTag(); c.putByte("Mode", (byte) StructureModel.SPIN); hc.joints.put(StructureModel.jointKey(j.key), c); }
        hc.setChanged();
        StructureModel m = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model;
        h.assertTrue(m.wheels == 4 && m.joints.stream().allMatch(j -> j.wheelGround), "four wheels on the ground expected, got " + m.wheels);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        double[] z0 = {0};
        h.runAfterDelay(30, () -> { ColossusEntity c = find(h); z0[0] = c.getZ(); c.autopilot = true; c.setSpinInput(true, false, false); });
        h.runAfterDelay(110, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] wheels dz={} rate={} ground={}", c.getZ() - z0[0], c.wheelRate, c.onGroundC);
            h.assertTrue(z0[0] - c.getZ() > 1.5, "the wheels should drive it forward (north), dz=" + (c.getZ() - z0[0]));
            c.setSpinInput(false, false, true);
        });
        h.runAfterDelay(150, () -> {
            ColossusEntity c = find(h);
            h.assertTrue(Math.abs(c.wheelRate) < 0.01 && c.vel.horizontalDistance() < 0.3, "brake should stop it: rate " + c.wheelRate + " v " + c.vel.horizontalDistance());
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
    public static void smartFootingAvoidsDangerousGround(GameTestHelper h) {
        walker(h, false, true);
        // smart footing is on by default (Locomotion Controller)
        h.setBlock(6, 7, 7, ModRegistry.FLUX_ACCUMULATOR.get()); // enough power for the controller and full-speed steps
        CompoundTag fe = new CompoundTag(); fe.putLong("Energy", 4_000_000);
        h.getBlockEntity(new BlockPos(6, 7, 7)).loadWithComponents(fe, h.getLevel().registryAccess());
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
            h.assertTrue(planned[0], "smart footing should be planning (powered)");
            h.assertTrue(!burned[0], "a foot was planted on magma");
            h.assertTrue(c.getZ() - z0[0] < -3, "should still walk past the magma, dz=" + (c.getZ() - z0[0]));
            h.succeed();
        });
    }

    @GameTest(template = "empty16")
    public static void bearingsJoinIntoSquares(GameTestHelper h) {
        floor(h); ship(h, 0);
        var b = ModRegistry.BEARING.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        // a 3x3 of bearings under the deck (axis Y: the square spreads in X and Z), a part hanging below it
        for (int x = 6; x <= 8; x++) for (int z = 6; z <= 8; z++) { h.setBlock(x, 5, z, b); h.setBlock(x, 4, z, Blocks.STONE_BRICKS); }
        var s0 = h.getBlockState(new BlockPos(6, 5, 6));
        h.assertTrue(s0.getValue(com.colossalcraft.block.SimpleParts.SIZE) == 3, "3x3 bearings should form a 3x3 assembly, size " + s0.getValue(com.colossalcraft.block.SimpleParts.SIZE));
        h.assertTrue(h.getBlockState(new BlockPos(8, 5, 8)).getValue(com.colossalcraft.block.SimpleParts.GU) == 2, "corner should know its place");
        StructureModel m = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model;
        h.assertTrue(m.joints.size() == 1, "one 3x3 joint expected, got " + m.joints.size());
        h.assertTrue(Math.abs(m.joints.get(0).forceCap - com.colossalcraft.tier.Parts.BEARING_FORCE * 27) < 1, "3x3 should hold 3^3 = 27x a 1x1: " + m.joints.get(0).forceCap);
        // take one out: it falls back to the biggest squares that still fit (a 2x2 and 1x1s)
        h.setBlock(8, 5, 8, Blocks.AIR);
        h.assertTrue(h.getBlockState(new BlockPos(6, 5, 6)).getValue(com.colossalcraft.block.SimpleParts.SIZE) == 2, "should drop to a 2x2");
        // a bearing placed while sneaking stays on its own
        h.setBlock(8, 5, 8, b.setValue(com.colossalcraft.block.SimpleParts.LONE, true));
        h.assertTrue(h.getBlockState(new BlockPos(8, 5, 8)).getValue(com.colossalcraft.block.SimpleParts.SIZE) == 1, "a sneak-placed bearing stays 1x1");
        h.succeed();
    }

    @GameTest(template = "empty16")
    public static void legsFoldUntilTheyHitTheBody(GameTestHelper h) {
        walker(h, false, true);
        var hc = (com.colossalcraft.block.HullCoreBlockEntity) h.getBlockEntity(new BlockPos(7, 7, 7));
        StructureModel m0 = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model;
        for (StructureModel.Joint j : m0.joints) { CompoundTag c = hc.joints.getCompound(StructureModel.jointKey(j.key)).copy(); c.putInt("Amp", 90); hc.joints.put(StructureModel.jointKey(j.key), c); }
        hc.setChanged();
        StructureModel m = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model;
        StructureModel.LegChain L = m.legChains.get(0);
        StructureModel.Joint hip = m.joints.get(L.joints().get(0));
        ColossalCraft.LOG.info("[test] hip amp {} fold {} deg, limit {} deg", hip.amp, Math.toDegrees(hip.fold), Math.toDegrees(L.limits()[0]));
        h.assertTrue(hip.amp == 90, "range should go to +-180, amp " + hip.amp);
        h.assertTrue(hip.fold < Math.PI - 1e-6 && hip.fold > Math.toRadians(20), "a hip under the deck should stop before folding into it, fold " + Math.toDegrees(hip.fold));
        h.assertTrue(Math.abs(L.limits()[0] - hip.fold) < 1e-9, "the leg limit should be the fold limit when the range is wider");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100)
    public static void settingsSurviveParkingTurned(GameTestHelper h) {
        walker(h, false, true);
        var hc = (com.colossalcraft.block.HullCoreBlockEntity) h.getBlockEntity(new BlockPos(7, 7, 7));
        StructureModel m0 = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model;
        for (StructureModel.Joint j : m0.joints) { CompoundTag c = hc.joints.getCompound(StructureModel.jointKey(j.key)).copy(); c.putInt("Amp", 33); hc.joints.put(StructureModel.jointKey(j.key), c); }
        hc.setChanged();
        int legs0 = m0.legChains.size();
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        h.runAfterDelay(20, () -> {
            ColossusEntity c = find(h);
            c.yaw = c.prevYaw = Math.PI / 2; // it turned a quarter round before parking
            h.assertTrue(c.disassemble(null), "park failed");
            BlockPos heart = null;
            for (int x = 0; x < 16 && heart == null; x++) for (int y = 0; y < 16 && heart == null; y++) for (int z = 0; z < 16; z++) if (h.getBlockState(new BlockPos(x, y, z)).is(ModRegistry.HULL_CORE.get())) { heart = new BlockPos(x, y, z); break; }
            h.assertTrue(heart != null, "no Hull Core after parking");
            StructureModel m = StructureScan.scan(h.getLevel(), h.absolutePos(heart)).model;
            long kept = m.joints.stream().filter(j -> j.amp == 33).count();
            ColossalCraft.LOG.info("[test] parked turned: {} of {} joints kept their settings, legs {}", kept, m.joints.size(), m.legChains.size());
            h.assertTrue(kept == m.joints.size() && m.legChains.size() == legs0, "joint settings were lost when parked facing another way: " + kept + "/" + m.joints.size());
            h.succeed();
        });
    }

    @GameTest(template = "empty16")
    public static void ballJointsFormBoxes(GameTestHelper h) {
        floor(h); ship(h, 0);
        var ball = ModRegistry.BALL_JOINT.get().defaultBlockState();
        // under the deck: a 2x2x2 cube of ball joints, then a foot
        for (int x = 6; x <= 7; x++) for (int z = 6; z <= 7; z++) { h.setBlock(x, 5, z, ball); h.setBlock(x, 4, z, ball); h.setBlock(x, 3, z, Blocks.STONE_BRICKS); }
        StructureModel m = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model;
        h.assertTrue(m.joints.size() == 1 && m.joints.get(0).ball && m.joints.get(0).size == 2 && m.joints.get(0).height == 2, "a 2x2x2 ball joint expected: " + m.joints.size() + " joints");
        h.assertTrue(m.joints.get(0).k == 8 && !m.joints.get(0).childSegs.isEmpty() && m.joints.get(0).romCap == 90, "one joint of 8 blocks, +-90 range, rom " + m.joints.get(0).romCap);
        // take the lower layer away: a flat 2x1x2 turns only +-45
        for (int x = 6; x <= 7; x++) for (int z = 6; z <= 7; z++) h.setBlock(x, 4, z, Blocks.STONE_BRICKS);
        m = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model;
        h.assertTrue(m.joints.size() == 1 && m.joints.get(0).k == 4 && m.joints.get(0).romCap == 45 && m.joints.get(0).amp <= 22, "a flat 2x1x2 ball should be +-45, got " + m.joints.get(0).romCap);
        h.assertTrue(h.getBlockState(new BlockPos(6, 5, 6)).getValue(com.colossalcraft.block.SimpleParts.HT) == 1, "layer count back to 1");
        h.succeed();
    }

    @GameTest(template = "empty16")
    public static void stepSpeedIsCappedByPower(GameTestHelper h) {
        walker(h, false, true);
        h.setBlock(6, 7, 7, ModRegistry.EMBER_BOILER.get()); // an Ember Boiler: little power
        CompoundTag eb = new CompoundTag(); eb.putLong("Energy", 900_000);
        h.getBlockEntity(new BlockPos(6, 7, 7)).loadWithComponents(eb, h.getLevel().registryAccess());
        StructureModel weak = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model;
        h.setBlock(6, 7, 7, ModRegistry.FLUX_ACCUMULATOR.get());
        CompoundTag fe = new CompoundTag(); fe.putLong("Energy", 4_000_000);
        h.getBlockEntity(new BlockPos(6, 7, 7)).loadWithComponents(fe, h.getLevel().registryAccess());
        StructureModel strong = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model;
        ColossalCraft.LOG.info("[test] power cap weak={} strong={} legs={} CJ/t", weak.powerSpeedCap(), strong.powerSpeedCap(), weak.legMotorPower());
        h.assertTrue(weak.powerSpeedCap() < strong.powerSpeedCap(), "more engine output should allow faster steps");
        h.assertTrue(weak.stepSpeed(false) <= weak.powerSpeedCap() + 1e-9, "step rate must not go past the power cap");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 160)
    public static void poweredBallHipsWalkAndRoll(GameTestHelper h) {
        walker(h, false, true);
        for (int x : new int[]{6, 8}) h.setBlock(x, 5, 7, ModRegistry.POWERED_BALL_JOINT.get()); // powered ball hips instead of bearings
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        var hc = (com.colossalcraft.block.HullCoreBlockEntity) h.getBlockEntity(new BlockPos(7, 7, 7));
        hc.joints = new CompoundTag();
        for (StructureModel.Joint j : r.model.joints) { CompoundTag c = new CompoundTag(); c.putByte("Mode", (byte) StructureModel.LEG); hc.joints.put(StructureModel.jointKey(j.key), c); }
        hc.setChanged();
        StructureModel m = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7))).model;
        h.assertTrue(m.legChains.size() == 2 && m.joints.stream().allMatch(j -> j.universal), "powered ball hips set to Leg should be two-way leg joints: legs " + m.legChains.size());
        // a foot wanted one block out to the side: the powered hip tilts the whole leg sideways to reach it
        StructureModel.LegChain L = m.legChains.get(0);
        var f = net.minecraft.world.phys.Vec3.atLowerCornerOf(m.forward.getNormal());
        var right = new net.minecraft.world.phys.Vec3(-f.z, 0, f.x);
        WalkerController.hipRoll(m, L, L.foot().add(right), 10);
        double hipRoll = m.ikRoll[L.joints().get(0)], hipH = m.joints.get(L.joints().get(0)).pivot.y - L.foot().y;
        ColossalCraft.LOG.info("[test] powered hip roll {} rad for 1 block sideways (leg {} high)", hipRoll, hipH);
        h.assertTrue(Math.abs(Math.abs(hipRoll) - Math.atan2(1, hipH)) < 0.05, "the hip should roll about atan(1/height), got " + hipRoll);
        m.ikRoll[L.joints().get(0)] = 0;
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        double[] z0 = {0}, roll = {0};
        h.runAfterDelay(20, () -> { ColossusEntity c = find(h); z0[0] = c.getZ(); c.autopilot = true; c.setInput(1, 0, false, false); });
        for (int t = 21; t <= 140; t++) { int tt = t; h.runAfterDelay(t, () -> { ColossusEntity c = find(h); if (tt == 90) c.setInput(1, 1, false, false); /* walk on, then turn: feet land off to the side */ for (double v : c.model.ikRoll) roll[0] = Math.max(roll[0], Math.abs(v)); }); }
        h.runAfterDelay(142, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] powered hips dz={} maxRoll={} tilt={}", c.getZ() - z0[0], roll[0], c.tilt);
            h.assertTrue(c.tilt == 0 && Math.abs(c.getZ() - z0[0]) + Math.abs(c.getX() - h.absolutePos(new BlockPos(7, 7, 7)).getX()) > 1, "should walk on powered ball hips, dz=" + (c.getZ() - z0[0]));
            h.succeed();
        });
    }

    /** How far each planted foot really is from where the walker planted it (0 = sole on its spot). */
    static double footGap(ColossusEntity c) {
        var W = c.freshSegWorlds(); double worst = 0;
        for (int i = 0; i < c.model.legChains.size() && i < c.walker.state.length; i++) {
            if (c.walker.state[i] != WalkerController.State.STANCE) continue;
            var L = c.model.legChains.get(i);
            var v = W[L.footSeg()].transformPosition(new org.joml.Vector3d(L.foot().x, L.foot().y, L.foot().z));
            worst = Math.max(worst, Math.abs(v.y - c.walker.foot[i].y));
        }
        return worst;
    }

    @GameTest(template = "empty16", timeoutTicks = 260)
    public static void unevenGroundKeepsBothFeetDown(GameTestHelper h) {
        bigMech(h, true);
        for (int x = 9; x <= 12; x++) for (int z = 0; z <= 4; z++) h.setBlock(x, 1, z, Blocks.STONE); // the right side of the path is a block higher (a gap so it is not part of the mech)
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 10, 7)), null), "assemble failed");
        double[] z0 = {0}, gap = {0}, roll = {0}; int[] lifted = {0};
        h.runAfterDelay(30, () -> { ColossusEntity c = find(h); z0[0] = c.getZ(); c.autopilot = true; c.setInput(1, 0, false, false);
            for (var L : c.model.legChains) ColossalCraft.LOG.info("[test] uneven leg limits {} folds {}", java.util.Arrays.toString(java.util.Arrays.stream(L.limits()).map(Math::toDegrees).toArray()), L.joints().stream().map(j -> Math.toDegrees(c.model.joints.get(j).fold)).toList()); });
        for (int t = 31; t <= 230; t++) h.runAfterDelay(t, () -> {
            ColossusEntity c = find(h);
            if (c.getZ() - z0[0] < -3.0) c.setInput(0, 0, false, false);
            double g = footGap(c); gap[0] = Math.max(gap[0], g); if (g > 0.3) lifted[0]++;
            roll[0] = Math.max(roll[0], Math.abs(c.bodyRoll));
        });
        h.runAfterDelay(232, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] uneven ground: worst foot gap {} (ticks over 0.3: {}), max body roll {} rad, feet {} y {}", gap[0], lifted[0], roll[0], c.walker.foot[0].y + "/" + c.walker.foot[1].y, c.getY());
            h.assertTrue(c.tilt == 0, "fell over on uneven ground");
            h.assertTrue(Math.abs(c.walker.foot[0].y - c.walker.foot[1].y) > 0.9, "one foot should be up on the higher side");
            h.assertTrue(lifted[0] <= 3, "a planted foot hung off its ground for " + lifted[0] + " ticks (worst " + gap[0] + ")");
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 160)
    public static void walkerTurnsSmoothly(GameTestHelper h) {
        walker(h, false, true);
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        double[] y0 = {0}, lastRate = {0}, jerk = {0}, over = {0}; int[] s0 = {0};
        h.runAfterDelay(20, () -> { ColossusEntity c = find(h); y0[0] = c.yaw; s0[0] = c.walker.steps; c.autopilot = true; c.setInput(0, 1, false, false); });
        for (int t = 21; t <= 140; t++) h.runAfterDelay(t, () -> { ColossusEntity c = find(h); jerk[0] = Math.max(jerk[0], Math.abs(c.yawRate - lastRate[0])); lastRate[0] = c.yawRate; over[0] = Math.max(over[0], c.walker.maxTwist(c)); });
        h.runAfterDelay(142, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] turning: turned {} rad, max yaw-rate change {} per tick, tilt {}, steps {}, past twist limit {}", c.yaw - y0[0], jerk[0], c.tilt, c.walker.steps - s0[0], over[0]);
            h.assertTrue(c.walker.steps - s0[0] >= 3, "turning should be done by stepping round, steps " + (c.walker.steps - s0[0]));
            h.assertTrue(over[0] < 0.05, "the body must not turn further over a planted foot than the leg allows: " + over[0]);
            h.assertTrue(Math.abs(c.yaw - y0[0]) > 0.3, "should turn in place, turned " + (c.yaw - y0[0]));
            h.assertTrue(jerk[0] <= 1.2 * 0.05 + 1e-6, "the turn should ease in, not jerk: " + jerk[0]);
            h.assertTrue(c.tilt == 0, "fell over while turning");
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 160)
    public static void poweredHipsTwistWhenTurning(GameTestHelper h) {
        walker(h, false, true);
        for (int x : new int[]{6, 8}) h.setBlock(x, 5, 7, ModRegistry.POWERED_BALL_JOINT.get());
        StructureScan.Result r = StructureScan.scan(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)));
        var hc = (com.colossalcraft.block.HullCoreBlockEntity) h.getBlockEntity(new BlockPos(7, 7, 7));
        hc.joints = new CompoundTag();
        for (StructureModel.Joint j : r.model.joints) { CompoundTag c = new CompoundTag(); c.putByte("Mode", (byte) StructureModel.LEG); hc.joints.put(StructureModel.jointKey(j.key), c); }
        hc.setChanged();
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
        double[] tw = {0};
        h.runAfterDelay(20, () -> { ColossusEntity c = find(h); c.autopilot = true; c.setInput(0, 1, false, false); });
        for (int t = 21; t <= 140; t++) h.runAfterDelay(t, () -> { ColossusEntity c = find(h); for (double v : c.model.ikYaw) tw[0] = Math.max(tw[0], Math.abs(v)); });
        h.runAfterDelay(142, () -> {
            ColossusEntity c = find(h);
            ColossalCraft.LOG.info("[test] powered hips twist up to {} rad while turning", tw[0]);
            h.assertTrue(tw[0] > 0.03, "powered ball hips should twist to keep planted feet pointing where they landed");
            h.assertTrue(c.tilt == 0, "fell over");
            h.succeed();
        });
    }
}
