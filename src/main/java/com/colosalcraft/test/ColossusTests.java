package com.colosalcraft.test;

import com.colosalcraft.ColosalCraft;
import com.colosalcraft.ModRegistry;
import com.colosalcraft.colossus.ColossusEntity;
import com.colosalcraft.colossus.StructureModel;
import com.colosalcraft.colossus.StructureScan;
import com.colosalcraft.tier.MaterialTier;
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
@GameTestHolder(ColosalCraft.MODID)
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
            ColosalCraft.LOG.info("[test] hover y0={} y={} energy={} vel={}", y0, c.getY(), c.energy, c.vel);
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
            ColosalCraft.LOG.info("[test] fall y={} ground={} vel={}", c.getY(), c.onGroundC, c.vel);
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
        ColosalCraft.LOG.info("[test] joint sub={} ratio={} parent={} children={}", j.subMass, j.staticRatio, j.parentSeg, j.childSegs);
        h.assertTrue(j.parentSeg == 0 && !j.childSegs.isEmpty(), "joint tree wrong");
        h.assertTrue(j.staticRatio > 1, "wood joint should be overloaded: " + j.staticRatio);
        // same joint in diamond, 1x1, should hold
        double diamondRatio = j.subMass * 9.81 / MaterialTier.forceCap(MaterialTier.DIAMOND, 1);
        h.assertTrue(diamondRatio < 1, "diamond should hold");
        int before = r.blocks.size();
        h.assertTrue(ColossusEntity.assemble(h.getLevel(), heart, null), "assemble failed");
        h.runAfterDelay(80, () -> {
            ColossusEntity c = find(h);
            ColosalCraft.LOG.info("[test] blocks before={} after={}", before, c.blocks.size());
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
            h.assertBlockProperty(new BlockPos(6, 2, 5), com.colosalcraft.block.ControllerBlock.FORMED, true);
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
        if (!com.colosalcraft.compat.Compat.CREATE) { h.succeed(); return; }
        floor(h);
        var motor = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:creative_motor"));
        h.setBlock(5, 1, 5, motor.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST));
        h.setBlock(6, 1, 5, com.colosalcraft.compat.create.CreateCompat.KINETIC_DYNAMO.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        h.runAfterDelay(60, () -> {
            var be = (com.colosalcraft.compat.create.CreateCompat.DynamoBE) h.getBlockEntity(new BlockPos(6, 1, 5));
            ColosalCraft.LOG.info("[test] dynamo speed={} energy={}", be.getSpeed(), be.energy);
            h.assertTrue(be.energy > 0, "dynamo made no energy (speed " + be.getSpeed() + ")");
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 220)
    public static void createMachinesWorkAfterLanding(GameTestHelper h) {
        if (!com.colosalcraft.compat.Compat.CREATE) { h.succeed(); return; }
        floor(h); ship(h, 0);
        var reg = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        h.setBlock(5, 7, 5, reg.get(net.minecraft.resources.ResourceLocation.parse("create:creative_motor")).defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST));
        h.setBlock(6, 7, 5, reg.get(net.minecraft.resources.ResourceLocation.parse("create:shaft")).defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        h.setBlock(7, 7, 5, com.colosalcraft.compat.create.CreateCompat.KINETIC_DYNAMO.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        h.runAfterDelay(20, () -> {
            h.assertTrue(ColossusEntity.assemble(h.getLevel(), h.absolutePos(new BlockPos(7, 7, 7)), null), "assemble failed");
            h.runAfterDelay(60, () -> {
                h.assertTrue(find(h).disassemble(null), "disassemble failed");
                var be = (com.colosalcraft.compat.create.CreateCompat.DynamoBE) h.getBlockEntity(new BlockPos(7, 2, 5));
                long e0 = be.energy;
                h.runAfterDelay(40, () -> {
                    var be2 = (com.colosalcraft.compat.create.CreateCompat.DynamoBE) h.getBlockEntity(new BlockPos(7, 2, 5));
                    ColosalCraft.LOG.info("[test] after landing speed={} energy {} -> {}", be2.getSpeed(), e0, be2.energy);
                    h.assertTrue(be2.getSpeed() != 0 && be2.energy > e0, "Create network did not re-link after landing");
                    h.succeed();
                });
            });
        });
    }
}
