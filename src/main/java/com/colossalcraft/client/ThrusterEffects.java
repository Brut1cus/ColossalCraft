package com.colossalcraft.client;

import com.colossalcraft.colossus.ColossusEntity;
import com.colossalcraft.colossus.StructureModel;
import com.colossalcraft.tier.ThrusterTier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

/** Client: exhaust particles out of every firing thruster's nozzle, and one engine sound per Colossus that follows the throttle. */
public final class ThrusterEffects {
    private ThrusterEffects() {}
    private static final Map<Integer, Loop> SOUNDS = new HashMap<>();

    public static void tick(ColossusEntity c) {
        if (c.model == null || c.model.thrusterList.isEmpty()) return;
        float[] lv = c.thrustLevels();
        var level = c.level();
        double total = 0; ThrusterTier loud = null; double loudF = 0;
        for (int i = 0; i < lv.length; i++) {
            if (lv[i] <= 0) continue;
            StructureModel.Thruster t = c.model.thrusterList.get(i);
            ThrusterTier tier = ((com.colossalcraft.block.SimpleParts.ThrusterBlock) c.model.blocks.get(t.pos()).getBlock()).tier;
            total += lv[i] * Math.sqrt(t.force() / 40_000.0);
            if (t.force() * lv[i] > loudF) { loudF = t.force() * lv[i]; loud = tier; }
            // exhaust leaves through the nozzle: opposite to the push, turned with the part
            Vec3 pl = Vec3.atLowerCornerOf(t.push().getNormal());
            Integer seg = c.model.segOf.get(t.pos());
            Vector3d d = c.segWorld(seg == null ? 0 : seg).transformDirection(new Vector3d(-pl.x, -pl.y, -pl.z)).normalize();
            Vec3 at = c.blockWorldCenter(t.pos()).add(d.x * 0.6, d.y * 0.6, d.z * 0.6);
            int n = 1 + Math.round(lv[i] * 2);
            double sp = 0.25 + 0.35 * lv[i];
            for (int k = 0; k < n; k++) {
                double jx = (level.random.nextDouble() - .5) * 0.25, jy = (level.random.nextDouble() - .5) * 0.25, jz = (level.random.nextDouble() - .5) * 0.25;
                level.addParticle(particle(tier, k), at.x + jx, at.y + jy, at.z + jz, d.x * sp + jx * 0.2 + c.vel.x * 0.05, d.y * sp + jy * 0.2 + c.vel.y * 0.05, d.z * sp + jz * 0.2 + c.vel.z * 0.05);
            }
        }
        Loop l = SOUNDS.get(c.getId());
        float vol = (float) Math.min(1.0, total * 0.35);
        if (vol > 0.02 && (l == null || l.isStopped())) {
            l = new Loop(c, loud == null ? ThrusterTier.STEAM : loud);
            SOUNDS.put(c.getId(), l);
            Minecraft.getInstance().getSoundManager().play(l);
        }
        if (l != null) l.target = vol;
    }

    private static ParticleOptions particle(ThrusterTier t, int k) {
        return switch (t) {
            case STEAM -> ParticleTypes.CLOUD;
            case BLAZE -> k == 0 ? ParticleTypes.SMOKE : ParticleTypes.FLAME;
            case ARC -> ParticleTypes.ELECTRIC_SPARK;
            case ION -> ParticleTypes.SOUL_FIRE_FLAME;
            case GRAVITON -> ParticleTypes.REVERSE_PORTAL;
        };
    }

    /** A looping engine sound at the Colossus; it fades with the throttle and stops when idle or gone. */
    static final class Loop extends AbstractTickableSoundInstance {
        private final ColossusEntity c; float target; private int quiet;
        Loop(ColossusEntity c, ThrusterTier t) {
            super(SoundEvents.ELYTRA_FLYING, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.c = c; looping = true; delay = 0; volume = 0.01f;
            pitch = switch (t) { case STEAM -> 0.7f; case BLAZE -> 0.85f; case ARC -> 1.15f; case ION -> 1.4f; case GRAVITON -> 0.5f; };
            x = c.getX(); y = c.getY(); z = c.getZ();
        }
        @Override public void tick() {
            if (c.isRemoved()) { stop(); return; }
            x = c.getX(); y = c.getY(); z = c.getZ();
            volume += (target - volume) * 0.2f;
            target *= 0.8f; // fades unless the next tick sets it again
            if (volume < 0.02f) { if (++quiet > 20) stop(); } else quiet = 0;
        }
    }
}
