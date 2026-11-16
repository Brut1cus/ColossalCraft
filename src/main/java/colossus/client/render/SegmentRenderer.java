package colossus.client.render;

import colossus.client.ClientColossi;
import colossus.net.PoseInterpolation;
import colossus.physics.math.Pose;
import colossus.physics.math.Vec3;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.*;

/**
 * Vanilla Segment renderer: each Segment's blocks are compiled once into per-layer vertex buffers (like a chunk
 * section) and drawn every frame with the vanilla chunk shaders at the pose interpolated between the last two
 * {@code PoseSync} packets. Draws happen in the matching {@link RenderLevelStageEvent} stage of each chunk layer, so
 * solid, cutout and translucent geometry sort with the terrain. The Sodium and Iris paths replace this from
 * {@code colossus.compat} later; until then this path also runs alongside them.
 * <p>
 * Not covered yet: block entity renderers (chests, signs), model data from block entities, live light (light is
 * sampled at the centre of mass and the mesh is rebuilt at most once a second when it changes), translucent sorting.
 */
public final class SegmentRenderer {
    private SegmentRenderer() {}

    private record Key(UUID colossus, int segment) {}

    private record Draw(SegmentMesh mesh, SegmentTransform transform) {}

    private static final Map<Key, SegmentMesh> MESHES = new HashMap<>();
    private static final List<Draw> FRAME = new ArrayList<>();
    private static final long RELIGHT_NANOS = 1_000_000_000L;
    private static final int MAX_COMPILES_PER_FRAME = 16;

    public static void onRenderStage(RenderLevelStageEvent e) {
        RenderLevelStageEvent.Stage stage = e.getStage();
        RenderType type;
        if (stage == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            prepareFrame(e);
            type = RenderType.solid();
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS) type = RenderType.cutoutMipped();
        else if (stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) type = RenderType.cutout();
        else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) type = RenderType.translucent();
        else if (stage == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) type = RenderType.tripwire();
        else return;
        drawLayer(type, e.getModelViewMatrix(), e.getProjectionMatrix());
    }

    /** Once per frame: rebuild changed meshes, drop stale ones, work out each visible Segment's transform. */
    private static void prepareFrame(RenderLevelStageEvent e) {
        FRAME.clear();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        net.minecraft.world.phys.Vec3 cam = e.getCamera().getPosition();
        Vec3 camera = new Vec3(cam.x, cam.y, cam.z);
        long now = System.nanoTime();
        int compiles = 0;
        Set<Key> seen = new HashSet<>();

        for (Map.Entry<UUID, ClientColossi.Structure> entry : ClientColossi.structures().entrySet()) {
            ClientColossi.Poses poses = ClientColossi.get(entry.getKey());
            if (poses == null) continue;
            ClientColossi.Structure structure = entry.getValue();
            int n = Math.min(structure.segments(), Math.min(poses.previous().segments(), poses.latest().segments()));
            double alpha = PoseInterpolation.alpha(now, poses.receivedAtNanos(), PoseInterpolation.INTERVAL_NANOS);
            for (int i = 0; i < n; i++) {
                ClientColossi.SegmentData data = structure.segment(i);
                if (!data.complete() || data.size() == 0) continue;
                Key key = new Key(entry.getKey(), i);
                seen.add(key);
                Pose pose = PoseInterpolation.interpolate(poses.previous().pose(i), poses.latest().pose(i), alpha);
                BlockPos sample = BlockPos.containing(pose.position().x(), pose.position().y(), pose.position().z());

                SegmentMesh mesh = MESHES.get(key);
                boolean stale = mesh == null || mesh.version != data.version();
                boolean relight = !stale && now - mesh.compiledAtNanos > RELIGHT_NANOS && packedLight(level, sample) != mesh.light;
                if ((stale || relight) && compiles < MAX_COMPILES_PER_FRAME) {
                    compiles++;
                    SegmentMesh fresh = SegmentMesh.compile(data, level, sample);
                    if (mesh != null) mesh.close();
                    MESHES.put(key, fresh);
                    mesh = fresh;
                }
                if (mesh == null || mesh.isEmpty()) continue;
                Vec3 p = pose.position();
                double r = mesh.radius;
                if (!e.getFrustum().isVisible(new AABB(p.x() - r, p.y() - r, p.z() - r, p.x() + r, p.y() + r, p.z() + r))) continue;
                FRAME.add(new Draw(mesh, SegmentTransform.of(pose, camera)));
            }
        }
        MESHES.entrySet().removeIf(m -> {
            if (seen.contains(m.getKey())) return false;
            m.getValue().close();
            return true;
        });
    }

    private static int packedLight(ClientLevel level, BlockPos p) {
        return level.getBrightness(LightLayer.SKY, p) << 4 | level.getBrightness(LightLayer.BLOCK, p);
    }

    private static void drawLayer(RenderType type, Matrix4f modelView, Matrix4f projection) {
        if (FRAME.isEmpty()) return;
        boolean any = false;
        for (Draw d : FRAME) if (d.mesh.buffer(type) != null) { any = true; break; }
        if (!any) return;

        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            Matrix4f mv = new Matrix4f();
            Matrix4f model = new Matrix4f();
            for (Draw d : FRAME) {
                VertexBuffer vb = d.mesh.buffer(type);
                if (vb == null) continue;
                model.set(d.transform.modelFloats());
                modelView.mul(model, mv);
                shader.setDefaultUniforms(VertexFormat.Mode.QUADS, mv, projection, Minecraft.getInstance().getWindow());
                if (shader.CHUNK_OFFSET != null) {
                    Vec3 o = d.transform.chunkOffset();
                    shader.CHUNK_OFFSET.set((float) o.x(), (float) o.y(), (float) o.z());
                }
                shader.apply();
                vb.bind();
                vb.draw();
            }
            if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0f, 0f, 0f);
            shader.clear();
            VertexBuffer.unbind();
        }
        type.clearRenderState();
    }

    /** Frees every mesh (logout, respawn, dimension change). Render thread only. */
    public static void clear() {
        for (SegmentMesh m : MESHES.values()) m.close();
        MESHES.clear();
        FRAME.clear();
    }
}
