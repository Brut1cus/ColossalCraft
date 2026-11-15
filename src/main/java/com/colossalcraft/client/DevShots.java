package com.colossalcraft.client;

import com.colossalcraft.ColossalCraft;
import com.colossalcraft.ModRegistry;
import com.colossalcraft.block.HullCoreBlockEntity;
import com.colossalcraft.block.PowerBlockEntity;
import com.colossalcraft.colossus.ColossusEntity;
import com.colossalcraft.colossus.StructureModel;
import com.colossalcraft.colossus.StructureScan;
import com.colossalcraft.network.Net;
import com.colossalcraft.tier.MaterialTier;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Dev only: started with -Dcolossalcraft.devshots=true. Makes a world, builds a test walker, takes screenshots, quits. */
@EventBusSubscriber(modid = ColossalCraft.MODID, value = Dist.CLIENT)
public final class DevShots {
    static final boolean ON = Boolean.getBoolean("colossalcraft.devshots");
    private static boolean started; private static int t = -1; private static BlockPos base;

    @SubscribeEvent public static void title(ScreenEvent.Init.Post e) {
        if (!ON || started || !(e.getScreen() instanceof TitleScreen || e.getScreen() instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)) return;
        started = true;
        Minecraft mc = Minecraft.getInstance();
        LevelSettings ls = new LevelSettings("devshots" + System.currentTimeMillis(), GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(ls.levelName(), ls, new WorldOptions(1L, false, false),
                ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), e.getScreen());
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), c -> ColossalCraft.LOG.info("[devshots] {}", c.getString()));
    }
    private static void server(java.util.function.Consumer<ServerPlayer> r) {
        Minecraft mc = Minecraft.getInstance();
        var srv = mc.getSingleplayerServer(); if (srv == null || mc.player == null) return;
        var uuid = mc.player.getUUID();
        srv.execute(() -> { ServerPlayer sp = srv.getPlayerList().getPlayer(uuid); if (sp != null) r.accept(sp); });
    }
    private static void look(ServerPlayer sp, BlockPos from, BlockPos at) {
        net.minecraft.world.phys.Vec3 eye = net.minecraft.world.phys.Vec3.atCenterOf(from), d = net.minecraft.world.phys.Vec3.atCenterOf(at).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        sp.getAbilities().flying = true; sp.onUpdateAbilities();
        sp.connection.teleport(eye.x, eye.y - 1.62, eye.z, yaw, pitch);
    }
    private static ColossusEntity colossus() { return ColossusCollisionAccess.first(); }

    @SubscribeEvent public static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (!ON || mc.player == null || mc.level == null) return;
        t++;
        switch (t) {
            case 40 -> server(sp -> {
                ServerLevel lv = sp.serverLevel();
                sp.getServer().getCommands().performPrefixedCommand(sp.createCommandSourceStack().withPermission(4), "time set noon");
                sp.getServer().getCommands().performPrefixedCommand(sp.createCommandSourceStack().withPermission(4), "weather clear");
                base = sp.blockPosition().offset(0, 0, 10);
                build(lv, base);
            });
            case 44 -> server(sp -> look(sp, base.offset(1, 9, -1), base.offset(0, 8, 1)));
            case 54 -> shot("0_closeup_chair");
            case 55 -> server(sp -> look(sp, base.offset(4, 10, -4), base.offset(1, 8, -1)));
            case 68 -> shot("0_closeup_joints");
            case 70 -> server(sp -> { BlockPos h = base.offset(0, 8, 0); sp.connection.teleport(h.getX() + 0.5, h.getY(), h.getZ() - 2.5, 0f, 30f); });
            case 80 -> server(sp -> Net.sendScan(sp, base.offset(0, 8, 0), StructureScan.scan(sp.serverLevel(), base.offset(0, 8, 0))));
            case 100 -> shot("1_overview");
            case 105 -> { if (mc.screen instanceof HullCoreScreen s) s.setTab(1); }
            case 120 -> shot("2_joints");
            case 125 -> { if (mc.screen instanceof HullCoreScreen s) s.setTab(2); }
            case 135 -> shot("3_power");
            case 138 -> { if (mc.screen instanceof HullCoreScreen s) s.setTab(3); }
            case 143 -> shot("3b_help");
            case 145 -> { mc.setScreen(null); server(sp -> ColossusEntity.assemble(sp.serverLevel(), base.offset(0, 8, 0), sp)); }
            case 150 -> server(sp -> { sp.getAbilities().flying = true; sp.onUpdateAbilities(); sp.connection.teleport(base.getX() + 5.5, base.getY() + 10, base.getZ() - 4.5, 45f, 38f); });
            case 175 -> shot("4_assembled");
            case 180 -> server(sp -> {
                ColossusEntity c = colossus(); if (c == null) return;
                net.minecraft.world.phys.Vec3 hc = c.blockWorldCenter(BlockPos.ZERO);
                net.minecraft.world.phys.Vec3 eye = hc.add(-2.6, 1.4, -2.6);
                net.minecraft.world.phys.Vec3 d = hc.subtract(eye);
                float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                sp.connection.teleport(eye.x, eye.y - 1.62, eye.z, yaw, pitch);
            });
            case 195 -> {
                var p = mc.player; var eye = p.getEyePosition(); var to = eye.add(p.getViewVector(1f).scale(p.blockInteractionRange()));
                for (var c : com.colossalcraft.colossus.ColossusCollision.all(true)) if (c.level() == p.level())
                    ColossalCraft.LOG.info("[devshots] eye={} look={} heart={} bbox={} hit={}", eye, p.getViewVector(1f), c.blockWorldCenter(BlockPos.ZERO), c.getBoundingBox().inflate(6).contains(eye), c.raycastBlock(eye, to));
                ColossalCraft.LOG.info("[devshots] loaded={} clientEntities={}", com.colossalcraft.colossus.ColossusCollision.all(true).stream().map(c -> c.level().isClientSide + ":" + c.getId()).toList(), java.util.stream.StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false).filter(en -> en instanceof ColossusEntity).map(en -> en.getId() + "@" + en.position() + " model=" + (((ColossusEntity) en).model != null)).toList());
                ColossalCraft.LOG.info("[devshots] click hull core handled={}", ClientHooks.tryClickColossus());
            }
            case 210 -> shot("5_built_screen");
            case 212 -> { if (mc.screen instanceof HullCoreScreen s) s.setTab(1); }
            case 218 -> shot("6_built_joints");
            case 220 -> { mc.setScreen(null); server(sp -> { ColossusEntity c = colossus(); if (c != null) sp.startRiding(c, true); }); }
            case 225 -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            case 290 -> shot("7_walking_back");
            case 292 -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            case 305 -> shot("8_walking_front");
            case 307 -> mc.options.setCameraType(CameraType.FIRST_PERSON);
            case 315 -> shot("9_cockpit");
            case 340 -> mc.stop();
            default -> {}
        }
        if (t > 225 && t < 330) server(sp -> { ColossusEntity c = colossus(); if (c != null) c.setInput(1f, t > 260 && t < 280 ? 0.6f : 0f, false, false); if (c != null && t % 20 == 0) ColossalCraft.LOG.info("[devshots] walk t={} pos={} ground={} phase={}", t, c.position(), c.onGroundC, c.walkPhase); });
    }

    /** Two-legged test walker. Legs hang from iron bearing joints (X axis) driven by iron motors. */
    private static void build(ServerLevel lv, BlockPos ground) {
        for (int side : new int[]{-1, 1}) { lv.setBlockAndUpdate(ground.offset(side, 0, 0), ModRegistry.GANTRY_SCAFFOLD.get().defaultBlockState()); lv.setBlockAndUpdate(ground.offset(side, 0, -1), ModRegistry.GANTRY_SCAFFOLD.get().defaultBlockState()); }
        BlockPos b = ground.above();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 5; y <= 6; y++) lv.setBlockAndUpdate(b.offset(x, y, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
        BlockState joint = ModRegistry.JOINTS.get(MaterialTier.IRON).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        for (int side : new int[]{-1, 1}) {
            lv.setBlockAndUpdate(b.offset(side, 4, 0), joint);
            lv.setBlockAndUpdate(b.offset(side * 2, 4, 0), ModRegistry.MOTORS.get(MaterialTier.IRON).get().defaultBlockState());
            for (int y = 0; y <= 3; y++) lv.setBlockAndUpdate(b.offset(side, y, 0), Blocks.STONE_BRICKS.defaultBlockState());
            lv.setBlockAndUpdate(b.offset(side, 0, -1), Blocks.STONE_BRICK_SLAB.defaultBlockState());
        }
        lv.setBlockAndUpdate(b.offset(0, 7, 0), ModRegistry.HULL_CORE.get().defaultBlockState());
        lv.setBlockAndUpdate(b.offset(0, 7, 1), ModRegistry.PILOT_SEAT.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        lv.setBlockAndUpdate(b.offset(-1, 7, 0), ModRegistry.EMBER_BOILER.get().defaultBlockState());
        lv.setBlockAndUpdate(b.offset(1, 7, 0), ModRegistry.SMALL_GYRO.get().defaultBlockState());
        lv.setBlockAndUpdate(b.offset(-2, 7, -2), ModRegistry.STEAM_VENT.get().defaultBlockState());
        lv.setBlockAndUpdate(b.offset(2, 7, -2), ModRegistry.BLAZE_JET.get().defaultBlockState());
        lv.setBlockAndUpdate(b.offset(2, 7, 2), Blocks.CHEST.defaultBlockState());
        lv.setBlockAndUpdate(b.offset(-2, 7, 2), ModRegistry.FLUX_ACCUMULATOR.get().defaultBlockState());
        if (com.colossalcraft.compat.Compat.CREATE) {
            var reg = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
            
            
            lv.setBlockAndUpdate(b.offset(-1, 7, -2), com.colossalcraft.compat.create.CreateCompat.KINETIC_DYNAMO.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            
        }
        if (lv.getBlockEntity(b.offset(-1, 7, 0)) instanceof PowerBlockEntity pe) { pe.energy = 800_000; pe.setChanged(); }
        var js = ModRegistry.JOINT_SHAPES.get(MaterialTier.GOLD);
        lv.setBlockAndUpdate(b.offset(-2, 7, -1), js.get(com.colossalcraft.block.SimpleParts.JointShape.SLAB).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        lv.setBlockAndUpdate(b.offset(2, 7, -1), js.get(com.colossalcraft.block.SimpleParts.JointShape.PANEL).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        lv.setBlockAndUpdate(b.offset(2, 7, 1), js.get(com.colossalcraft.block.SimpleParts.JointShape.PIN).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
        lv.setBlockAndUpdate(b.offset(0, 7, -2), ModRegistry.SKY_SAIL.get().defaultBlockState());
        StructureScan.Result r = StructureScan.scan(lv, b.offset(0, 7, 0));
        if (lv.getBlockEntity(b.offset(0, 7, 0)) instanceof HullCoreBlockEntity hc) {
            for (StructureModel.Joint j : r.model.joints) {
                CompoundTag c = new CompoundTag(); c.putByte("Mode", (byte) (j.pivot.x < 0 ? StructureModel.WALK_A : StructureModel.WALK_B)); c.putInt("Amp", 30);
                hc.joints.put(StructureModel.jointKey(j.key), c);
            }
            hc.hover = false; hc.setChanged();
        }
    }
}
