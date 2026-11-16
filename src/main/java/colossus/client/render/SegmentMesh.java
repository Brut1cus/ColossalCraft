package colossus.client.render;

import colossus.client.ClientColossi;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * GPU buffers for one Segment, one per chunk render layer, with vertices in the body frame (relative to the centre of
 * mass). Compiled on the render thread like a vanilla chunk section, then drawn with {@link SegmentTransform}.
 */
final class SegmentMesh implements AutoCloseable {
    final int version;
    /** Packed (sky << 4 | block) light baked into the mesh. */
    final int light;
    final long compiledAtNanos;
    /** Largest distance from the centre of mass to any block corner, for frustum culling. */
    final double radius;
    private final Map<RenderType, VertexBuffer> buffers = new IdentityHashMap<>();

    private SegmentMesh(int version, int light, double radius) {
        this.version = version;
        this.light = light;
        this.radius = radius;
        this.compiledAtNanos = System.nanoTime();
    }

    static SegmentMesh compile(ClientColossi.SegmentData data, ClientLevel level, BlockPos worldSample) {
        int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, worldSample);
        int blk = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, worldSample);
        double cx = data.comRel().x(), cy = data.comRel().y(), cz = data.comRel().z();

        SegmentBlockGetter getter = new SegmentBlockGetter(data, level, worldSample, sky, blk);
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();
        PoseStack ps = new PoseStack();
        Map<RenderType, ByteBufferBuilder> memory = new HashMap<>();
        Map<RenderType, BufferBuilder> builders = new HashMap<>();
        double r2 = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        try {
            for (int i = 0; i < data.size(); i++) {
                pos.set(BlockPos.of(data.cell(i)));
                BlockState state = getter.getBlockState(pos);
                double dx = Math.max(Math.abs(pos.getX() - cx), Math.abs(pos.getX() + 1 - cx));
                double dy = Math.max(Math.abs(pos.getY() - cy), Math.abs(pos.getY() + 1 - cy));
                double dz = Math.max(Math.abs(pos.getZ() - cz), Math.abs(pos.getZ() + 1 - cz));
                r2 = Math.max(r2, dx * dx + dy * dy + dz * dz);

                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    RenderType type = ItemBlockRenderTypes.getRenderLayer(fluid);
                    // renderLiquid writes section-local positions (pos & 15) and ignores the pose stack
                    VertexConsumer out = new OffsetConsumer(builder(type, memory, builders),
                            (float) ((pos.getX() & ~15) - cx), (float) ((pos.getY() & ~15) - cy), (float) ((pos.getZ() & ~15) - cz));
                    dispatcher.renderLiquid(pos, getter, out, state, fluid);
                }
                if (state.getRenderShape() != RenderShape.MODEL) continue;
                BakedModel model = dispatcher.getBlockModel(state);
                random.setSeed(state.getSeed(pos));
                for (RenderType type : model.getRenderTypes(state, random, ModelData.EMPTY)) {
                    ps.pushPose();
                    ps.translate(pos.getX() - cx, pos.getY() - cy, pos.getZ() - cz);
                    dispatcher.renderBatched(state, pos, getter, ps, builder(type, memory, builders), true, random, ModelData.EMPTY, type);
                    ps.popPose();
                }
            }
            SegmentMesh mesh = new SegmentMesh(data.version(), sky << 4 | blk, Math.sqrt(r2));
            for (Map.Entry<RenderType, BufferBuilder> e : builders.entrySet()) {
                MeshData md = e.getValue().build();
                if (md == null) continue;
                VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                vb.bind();
                vb.upload(md); // closes md
                VertexBuffer.unbind();
                mesh.buffers.put(e.getKey(), vb);
            }
            return mesh;
        } finally {
            for (ByteBufferBuilder b : memory.values()) b.close();
        }
    }

    private static BufferBuilder builder(RenderType type, Map<RenderType, ByteBufferBuilder> memory, Map<RenderType, BufferBuilder> builders) {
        return builders.computeIfAbsent(type, t -> {
            ByteBufferBuilder bytes = new ByteBufferBuilder(t.bufferSize());
            memory.put(t, bytes);
            return new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        });
    }

    VertexBuffer buffer(RenderType type) { return buffers.get(type); }

    boolean isEmpty() { return buffers.isEmpty(); }

    @Override public void close() {
        for (VertexBuffer vb : buffers.values()) vb.close();
        buffers.clear();
    }

    /** Shifts vertex positions; everything else passes straight through. */
    private record OffsetConsumer(VertexConsumer delegate, float dx, float dy, float dz) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { delegate.addVertex(x + dx, y + dy, z + dz); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
    }
}
