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
            case 42 -> server(sp -> { for (int i = 0; i < 4; i++) sp.serverLevel().setBlockAndUpdate(base.offset(-6 + i * 2, 1, -6), ModRegistry.GEARBOXES.get(i).get().defaultBlockState().setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.WEST));
                sp.serverLevel().setBlockAndUpdate(base.offset(-6, 2, -6), ModRegistry.JOINT_SHAPES.get(MaterialTier.GOLD).get(com.colossalcraft.block.SimpleParts.JointShape.BALL).get().defaultBlockState());
                sp.serverLevel().setBlockAndUpdate(base.offset(-2, 2, -6), ModRegistry.LOCOMOTION_CONTROLLER.get().defaultBlockState());
                sp.serverLevel().setBlockAndUpdate(base.offset(0, 2, -6), ModRegistry.JUMP_DRIVE.get().defaultBlockState()); });
            case 43 -> server(sp -> look(sp, base.offset(-1, 3, -10), base.offset(-3, 1, -6)));
            case 50 -> shot("0a_gearboxes");
            case 51 -> server(sp -> { for (int i = 0; i < 4; i++) { sp.serverLevel().removeBlock(base.offset(-6 + i * 2, 1, -6), false); sp.serverLevel().removeBlock(base.offset(-6 + i * 2, 2, -6), false); } });
            case 52 -> server(sp -> look(sp, base.offset(1, 9, -1), base.offset(0, 8, 1)));
            case 58 -> shot("0_closeup_chair");
            case 59 -> server(sp -> look(sp, base.offset(4, 5, -2), base.offset(2, 5, 0)));
            case 68 -> shot("0_closeup_joints");
            case 70 -> server(sp -> { if (sp.serverLevel().getBlockEntity(base.offset(-1, 8, 0)) instanceof PowerBlockEntity pe) sp.openMenu(pe, buf -> com.colossalcraft.block.EngineMenu.writeOpen(buf, pe.getBlockPos(), null)); });
            case 85 -> shot("0b_engine_gui");
            case 86 -> mc.setScreen(null);
            case 90 -> server(sp -> { BlockPos h = base.offset(0, 8, 0); sp.connection.teleport(h.getX() + 0.5, h.getY(), h.getZ() - 2.5, 0f, 30f); });
            case 100 -> server(sp -> Net.openHullParked(sp, base.offset(0, 8, 0)));
            case 112 -> shot("1_overview");
            case 113 -> server(sp -> Net.openLocoParked(sp, base.offset(1, 8, 1)));
            case 115 -> { if (mc.screen instanceof HullCoreScreen s) s.setTab(0); }
            case 117 -> { if (mc.screen instanceof HullCoreScreen s) { s.setTab(0); s.selectLegJoint(); } }
            case 130 -> shot("2_joints");
            case 131 -> { if (mc.screen instanceof HullCoreScreen s) s.turnView(-70, 20, 1.4); }
            case 132 -> shot("2b_joints_rotated");
            case 133 -> { if (mc.screen instanceof HullCoreScreen s) s.setTab(1); }
            case 135 -> shot("2c_gait");
            case 136 -> { if (mc.screen instanceof HullCoreScreen s) s.setTab(2); }
            case 138 -> shot("2d_keys");
            case 139 -> mc.setScreen(null);
            case 140 -> server(sp -> { sp.getAbilities().flying = false; sp.onUpdateAbilities(); BlockPos p = base.offset(1, 8, 1); sp.connection.teleport(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 135f, 20f); });
            case 145 -> { ColossalCraft.LOG.info("[devshots] before build player y={}", mc.player.getY()); server(sp -> ColossusEntity.assemble(sp.serverLevel(), base.offset(0, 8, 0), sp)); }
            case 150, 155, 159 -> ColossalCraft.LOG.info("[devshots] after build t={} player y={} onGround={}", t, mc.player.getY(), mc.player.onGround());
            case 160 -> server(sp -> { BlockPos hp = base.offset(0, 14, 0); sp.serverLevel().setBlockAndUpdate(hp, ModRegistry.GANTRY_HOIST.get().defaultBlockState().setValue(com.colossalcraft.block.SimpleParts.GantryHoistBlock.ENGAGED, true));
                ColossusEntity c = colossus(); if (c != null) c.holdBy(hp); sp.getAbilities().flying = true; sp.onUpdateAbilities(); sp.connection.teleport(base.getX() + 9.5, base.getY() + 7, base.getZ() - 8.5, 45f, 10f); });
            case 178 -> shot("3_hoist_holding");
            case 179 -> server(sp -> { ColossusEntity c = colossus(); if (c != null) c.holdBy(null); sp.serverLevel().removeBlock(base.offset(0, 14, 0), false); });
            case 182 -> server(sp -> {
                ColossusEntity c = colossus(); if (c == null) return;
                net.minecraft.world.phys.Vec3 hc = c.blockWorldCenter(BlockPos.ZERO);
                net.minecraft.world.phys.Vec3 eye = hc.add(-2.6, 1.4, -2.6), d = hc.subtract(eye);
                sp.getAbilities().flying = true; sp.onUpdateAbilities();
                sp.connection.teleport(eye.x, eye.y - 1.62, eye.z, (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
            });
            case 192 -> ColossalCraft.LOG.info("[devshots] click hull core handled={}", ClientHooks.tryClickColossus());
            case 200 -> { if (mc.screen instanceof HullCoreScreen s) s.setTab(1); }
            case 205 -> shot("4_built_joints");
            case 207 -> { mc.setScreen(null); server(sp -> { ColossusEntity c = colossus(); if (c != null) sp.startRiding(c, true); }); }
            case 212 -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            case 240 -> shot("5a_walking_step"); case 250 -> shot("5b_walking_step"); case 262 -> shot("5_walking_back");
            case 264 -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            case 275 -> shot("6_walking_front");
            case 277 -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            case 280 -> { ColossusEntity c = colossus(); if (c != null) net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.colossalcraft.network.Net.StepRequest(c.getId(), 0)); }
            case 292 -> shot("8_step_planner");
            case 294 -> mc.setScreen(null);
            case 295 -> server(sp -> { ColossusEntity c = colossus(); if (c == null) return; c.allowDismount = true; sp.stopRiding(); c.allowDismount = false;
                net.minecraft.world.phys.Vec3 a = c.blockWorldCenter(new BlockPos(-1, -6, 0)), eye = a.add(-2.2, 0.6, -1.6), d = a.subtract(eye);
                sp.getAbilities().flying = true; sp.onUpdateAbilities();
                sp.connection.teleport(eye.x, eye.y - 1.62, eye.z, (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)))); });
            case 299 -> { mc.options.setCameraType(CameraType.FIRST_PERSON); shot("9_ankle"); }
            case 300 -> server(sp -> { ColossusEntity c = colossus(); if (c != null) { c.energy = 0; ColossalCraft.LOG.info("[devshots] power cut"); } });
            case 330, 345 -> { ColossusEntity c = colossus(); ColossalCraft.LOG.info("[devshots] t={} tilt={}", t, c == null ? -1 : c.tilt); }
            case 346 -> shot("7_fallen");
            case 352 -> mc.stop();
            default -> {}
        }
        if (t > 212 && t < 300) server(sp -> { ColossusEntity c = colossus(); if (c != null) c.setInput(1f, 0f, false, false); if (c != null && t % 10 == 0) ColossalCraft.LOG.info("[devshots] feet {} pos {} tilt {}", c.walker.status, c.position(), c.tilt); if (c != null && t % 20 == 0) ColossalCraft.LOG.info("[devshots] walk t={} pos={} ground={} phase={}", t, c.position(), c.onGroundC, c.walkPhase); });
    }

    /** Two-legged test walker. Legs hang from iron bearing joints (X axis) driven by iron motors. */
    private static void build(ServerLevel lv, BlockPos ground) {
        for (int side : new int[]{-1, 1}) { lv.setBlockAndUpdate(ground.offset(side, 0, 0), ModRegistry.GANTRY_SCAFFOLD.get().defaultBlockState()); lv.setBlockAndUpdate(ground.offset(side, 0, -1), ModRegistry.GANTRY_SCAFFOLD.get().defaultBlockState()); }
        BlockPos b = ground.above();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 5; y <= 6; y++) lv.setBlockAndUpdate(b.offset(x, y, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
        BlockState joint = ModRegistry.JOINTS.get(MaterialTier.DIAMOND).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        for (int side : new int[]{-1, 1}) {
            lv.setBlockAndUpdate(b.offset(side, 4, 0), joint);
            var mot = ModRegistry.MOTORS.get(MaterialTier.IRON).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
            if (side < 0) lv.setBlockAndUpdate(b.offset(side * 2, 4, 0), mot);
            else {
                lv.setBlockAndUpdate(b.offset(2, 4, 0), ModRegistry.GEARBOXES.get(1).get().defaultBlockState().setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.WEST));
                for (int[] o : new int[][]{{3, 4, 0}, {3, 4, 1}, {3, 3, 0}, {3, 3, 1}}) lv.setBlockAndUpdate(b.offset(o[0], o[1], o[2]), mot); // 2x2 motor grid
            }
            for (int y = 2; y <= 3; y++) lv.setBlockAndUpdate(b.offset(side, y, 0), Blocks.STONE_BRICKS.defaultBlockState());
            lv.setBlockAndUpdate(b.offset(side, 1, 0), ModRegistry.JOINT_SHAPES.get(MaterialTier.NETHERITE).get(com.colossalcraft.block.SimpleParts.JointShape.BALL).get().defaultBlockState()); // ball ankle
            lv.setBlockAndUpdate(b.offset(side, 0, 0), Blocks.STONE_BRICKS.defaultBlockState());
            lv.setBlockAndUpdate(b.offset(side, 0, -1), Blocks.STONE_BRICK_SLAB.defaultBlockState());
        }
        lv.setBlockAndUpdate(b.offset(0, 7, 0), ModRegistry.HULL_CORE.get().defaultBlockState());
        lv.setBlockAndUpdate(b.offset(0, 7, 1), ModRegistry.PILOT_SEAT.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        lv.setBlockAndUpdate(b.offset(-1, 7, 0), ModRegistry.EMBER_BOILER.get().defaultBlockState());
        lv.setBlockAndUpdate(b.offset(1, 7, 0), ModRegistry.SMALL_GYRO.get().defaultBlockState());
        lv.setBlockAndUpdate(b.offset(1, 7, 1), ModRegistry.LOCOMOTION_CONTROLLER.get().defaultBlockState());
        lv.setBlockAndUpdate(b.offset(-1, 7, 1), ModRegistry.STEP_CONTROLLER.get().defaultBlockState());
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
        lv.setBlockAndUpdate(b.offset(-2, 7, -1), js.get(com.colossalcraft.block.SimpleParts.JointShape.SLAB).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X).setValue(com.colossalcraft.block.SimpleParts.SlabJointBlock.TOP, true));
        lv.setBlockAndUpdate(b.offset(2, 7, -1), js.get(com.colossalcraft.block.SimpleParts.JointShape.PANEL).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X).setValue(com.colossalcraft.block.SimpleParts.PanelJointBlock.SIDE, Direction.EAST));
        lv.setBlockAndUpdate(b.offset(2, 7, 1), js.get(com.colossalcraft.block.SimpleParts.JointShape.PIN).get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
        lv.setBlockAndUpdate(b.offset(0, 7, -2), ModRegistry.SKY_SAIL.get().defaultBlockState());
        StructureScan.Result r = StructureScan.scan(lv, b.offset(0, 7, 0));
        if (lv.getBlockEntity(b.offset(0, 7, 0)) instanceof HullCoreBlockEntity hc) {
            for (StructureModel.Joint j : r.model.joints) {
                CompoundTag c = new CompoundTag(); c.putByte("Mode", (byte) StructureModel.LEG); c.putInt("Amp", 25);
                hc.joints.put(StructureModel.jointKey(j.key), c);
            }
            hc.hover = false; hc.setChanged();
        }
    }
}
