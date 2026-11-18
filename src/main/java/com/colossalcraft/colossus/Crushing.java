package com.colossalcraft.colossus;

import com.colossalcraft.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.*;

/**
 * Heavy feet and hard hits, from vanilla's own block numbers: hardness (how long it takes to mine) and blast resistance.
 * Mass comes from density (see {@link Density}); toughness comes from these, so heavy and tough stay separate.
 * - Feet crush what they step on or into if the foot is made of something tougher and pushes hard enough
 *   (a wooden foot can't break a log; an iron foot under a heavy mech can, and the whole tree comes down). Ground itself
 *   (dirt, sand, stone, terracotta) is never dug out by walking.
 * - Hard landings and crashes break the Colossus' own blocks that take the hit, weak ones first.
 */
public final class Crushing {
    private Crushing() {}

    public static float hardness(BlockState s) { try { return Math.max(0, s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)); } catch (Exception e) { return 1; } }
    /** Energy (J) a Colossus block takes before it breaks: from hardness and blast resistance. */
    public static double toughness(BlockState s) { return (3 * hardness(s) + Math.min(100, s.getBlock().getExplosionResistance())) * 8000; }
    /** Natural ground that feet press on but never dig out. */
    static boolean ground(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.TERRACOTTA)
                || s.is(net.minecraft.world.level.block.Blocks.GRAVEL) || s.is(net.minecraft.world.level.block.Blocks.BEDROCK) || s.is(BlockTags.SNOW) && s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
    /** Can a foot of this hardness, pushing this hard on one block (N), crush this world block? */
    public static boolean crushes(BlockState target, float footHard, double perBlockN) {
        if (target.isAir() || !target.getFluidState().isEmpty() && target.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return false;
        float h = hardness(target);
        if (target.getBlock().defaultDestroyTime() < 0 || ground(target)) return false;
        if (h <= 0.05f) return perBlockN > 2_000;                       // plants, flowers, snow layers: anything heavy flattens them
        return footHard > h && perBlockN > h * 60_000;                  // tougher foot, and enough weight on it
    }

    /** Feet of a walker: what each foot lands on (stance, just landed) or swings into gets crushed if it can be. */
    static void feet(ColossusEntity c, WalkerController w, boolean[] landed) {
        if (!Config.FOOT_CRUSHING.get() || !(c.level() instanceof ServerLevel sl) || c.model == null) return;
        StructureModel m = c.model;
        Matrix4d[] W = c.freshSegWorlds();
        double legShare = m.mass * StructureModel.G / Math.max(1, m.legChains.size());
        int broken = 0;
        for (int i = 0; i < m.legChains.size() && i < w.state.length; i++) {
            StructureModel.LegChain L = m.legChains.get(i);
            boolean stance = w.state[i] == WalkerController.State.STANCE;
            if (stance && !landed[i]) continue;                               // only the moment of landing, not every tick
            if (!stance && w.state[i] != WalkerController.State.SWING) continue;
            List<BlockPos> soles = L.soles(); if (soles.isEmpty()) continue;
            float footHard = 0; for (BlockPos p : soles) footHard = Math.max(footHard, hardness(m.blocks.get(p)));
            double load = stance ? Math.max(w.footN.length > i ? w.footN[i] : 0, legShare) * 1.3 : legShare * 0.5; // landing hits harder; a swinging foot only shoves
            double per = load / soles.size();
            int seg = L.footSeg(); if (seg >= W.length) continue;
            for (BlockPos p : soles) {
                Vector3d v = W[seg].transformPosition(new Vector3d(p.getX() + .5, p.getY() + .5, p.getZ() + .5));
                BlockPos in = BlockPos.containing(v.x, v.y, v.z), under = BlockPos.containing(v.x, v.y - 0.9, v.z);
                for (BlockPos t : stance ? new BlockPos[]{in, under} : new BlockPos[]{in}) {
                    BlockState s = sl.getBlockState(t);
                    if (!crushes(s, footHard, per)) continue;
                    if (s.is(BlockTags.LOGS)) broken += fellTree(sl, t); else { sl.destroyBlock(t, true); broken++; }
                    if (broken > 64) return;
                }
            }
        }
        if (broken > 0) sl.playSound(null, c.blockPosition(), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.2f, 0.6f);
    }

    /** A log was crushed: the whole tree comes down (its logs, and the leaves that hung on it), dropping as items. */
    public static int fellTree(ServerLevel sl, BlockPos start) {
        Set<BlockPos> logs = new LinkedHashSet<>(); ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(start); logs.add(start);
        while (!q.isEmpty() && logs.size() < 256) {
            BlockPos p = q.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos n = p.offset(dx, dy, dz);
                if (!logs.contains(n) && n.getY() >= start.getY() - 1 && sl.getBlockState(n).is(BlockTags.LOGS)) { logs.add(n); q.add(n); }
            }
        }
        Set<BlockPos> leaves = new LinkedHashSet<>();
        for (BlockPos l : logs) for (int dx = -4; dx <= 4 && leaves.size() < 800; dx++) for (int dy = -2; dy <= 4; dy++) for (int dz = -4; dz <= 4; dz++) {
            BlockPos n = l.offset(dx, dy, dz); BlockState s = sl.getBlockState(n);
            if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) leaves.add(n);
        }
        for (BlockPos p : logs) sl.destroyBlock(p, true);
        for (BlockPos p : leaves) sl.destroyBlock(p, true);
        return logs.size() + leaves.size();
    }

    /** A hard hit (landing, crash): the Colossus blocks that take it break if the energy on each is more than they can take. */
    static void impact(ColossusEntity c, double speed, Direction side) {
        if (!Config.IMPACT_DAMAGE.get() || c.model == null || c.level().isClientSide || speed < 6) return;
        StructureModel m = c.model;
        List<BlockPos> face = new ArrayList<>();
        for (BlockPos p : m.surface) if (!m.solid.contains(p.relative(side))) face.add(p);
        if (face.isEmpty()) return;
        // the blocks furthest that way take the hit
        double best = Double.NEGATIVE_INFINITY; for (BlockPos p : face) best = Math.max(best, side.getStepX() * p.getX() + side.getStepY() * p.getY() + side.getStepZ() * p.getZ());
        List<BlockPos> hit = new ArrayList<>();
        for (BlockPos p : face) if (side.getStepX() * p.getX() + side.getStepY() * p.getY() + side.getStepZ() * p.getZ() >= best - 0.5) hit.add(p);
        double perBlock = 0.5 * m.mass * speed * speed / hit.size();
        List<BlockPos> gone = new ArrayList<>();
        for (BlockPos p : hit) {
            BlockState s = m.blocks.get(p);
            if (s == null || s.getBlock() instanceof com.colossalcraft.block.HullCoreBlock) continue;
            if (perBlock > toughness(s)) gone.add(p);
            if (gone.size() >= 32) break;
        }
        if (gone.isEmpty()) return;
        for (BlockPos p : gone) c.dropBlock(p, null);
        c.level().playSound(null, c.blockPosition(), SoundEvents.ANVIL_BREAK, SoundSource.BLOCKS, 1.5f, 0.5f);
        c.editBlocks(Map.of(), Map.of(), gone);
    }
}
