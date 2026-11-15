package com.colossalcraft.client;

import com.colossalcraft.colossus.ColossusEntity;
import com.colossalcraft.colossus.StructureModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

public class ColossusRenderer extends EntityRenderer<ColossusEntity> {
    private final BlockRenderDispatcher blocks;
    private final BlockEntityRenderDispatcher beRenderers;

    public ColossusRenderer(EntityRendererProvider.Context ctx) { super(ctx); blocks = ctx.getBlockRenderDispatcher(); beRenderers = Minecraft.getInstance().getBlockEntityRenderDispatcher(); shadowRadius = 0; }

    /**
     * Mods whose moving parts are drawn by their own instanced renderer (e.g. Create with Flywheel), which skips
     * blocks riding on an entity. For these we draw the block's baked model instead, so shafts and cogs stay visible.
     */
    private static final java.util.Set<String> STATIC_MODEL_MODS = java.util.Set.of("create", "createaddition", "create_new_age", "createdieselgenerators", "flywheel");

    private void renderBaked(BlockState st, PoseStack ps, MultiBufferSource buf, int light) {
        try {
            var model = blocks.getBlockModel(st);
            for (var rt : model.getRenderTypes(st, net.minecraft.util.RandomSource.create(42), net.neoforged.neoforge.client.model.data.ModelData.EMPTY))
                blocks.getModelRenderer().renderModel(ps.last(), buf.getBuffer(net.neoforged.neoforge.client.RenderTypeHelper.getEntityRenderType(rt, false)),
                        st, model, 1f, 1f, 1f, light, OverlayTexture.NO_OVERLAY, net.neoforged.neoforge.client.model.data.ModelData.EMPTY, rt);
        } catch (Exception ignored) {}
    }

    @Override public ResourceLocation getTextureLocation(ColossusEntity e) { return InventoryMenu.BLOCK_ATLAS; }

    @Override public boolean shouldRender(ColossusEntity e, Frustum f, double x, double y, double z) {
        return e.model != null && f.isVisible(e.getBoundingBox().inflate(2));
    }

    @Override public void render(ColossusEntity e, float yawIgnored, float pt, PoseStack ps, MultiBufferSource buf, int light) {
        StructureModel m = e.model; if (m == null) return;
        double yaw = Mth.lerp(pt, e.prevYaw, e.yaw);
        Matrix4d[] art = m.transforms(Mth.lerp(pt, e.prevWalkPhase, e.walkPhase), Mth.lerp(pt, e.prevWalkBlend, e.walkBlend), Mth.lerp(pt, e.prevSpin, e.spin), e.tickCount + pt);
        double ex = Mth.lerp(pt, e.xo, e.getX()), ey = Mth.lerp(pt, e.yo, e.getY()), ez = Mth.lerp(pt, e.zo, e.getZ());
        var level = Minecraft.getInstance().level;
        if (e.clientBes == null) {
            e.clientBes = new java.util.HashMap<>();
            if (level != null) for (var en : e.bes.entrySet()) {
                BlockState st = m.blocks.get(en.getKey()); if (st == null) continue;
                try {
                    BlockEntity be = BlockEntity.loadStatic(en.getKey(), st, en.getValue(), level.registryAccess());
                    if (be != null) { be.setLevel(level); e.clientBes.put(en.getKey(), be); }
                } catch (Throwable ignored) {}
            }
        }
        BlockPos.MutableBlockPos lp = new BlockPos.MutableBlockPos();
        double tl = Mth.lerp(pt, e.prevTilt, e.tilt);
        var axis = e.tiltAxis();
        Matrix4d baseM = new Matrix4d().rotateY(-yaw);
        if (tl != 0) baseM.rotate(tl, axis.x, axis.y, axis.z);
        baseM.translate(-m.com.x, -m.com.y, -m.com.z);
        Matrix4d[] segM = new Matrix4d[m.segs.size()];
        for (int i = 0; i < segM.length; i++) segM[i] = new Matrix4d(baseM).mul(art[i]);
        for (StructureModel.Seg s : m.segs) {
            Matrix4d local = segM[s.id];
            ps.pushPose();
            ps.mulPose(new Matrix4f(local));
            for (BlockPos p : s.blocks) {
                Integer jid = m.jointOf.get(p);
                if (jid != null && !m.joints.get(jid).childSegs.isEmpty()) continue; // bearings are drawn with their moving side below
                drawBlock(e, m, p, local, ps, buf, pt, light, ex, ey, ez, level, lp);
            }
            ps.popPose();
        }
        // bearings turn with the part they move
        for (StructureModel.Joint j : m.joints) {
            if (j.childSegs.isEmpty()) continue;
            Matrix4d local = segM[j.childSegs.get(0)];
            ps.pushPose();
            ps.mulPose(new Matrix4f(local));
            for (BlockPos p : j.blocks) drawBlock(e, m, p, local, ps, buf, pt, light, ex, ey, ez, level, lp);
            ps.popPose();
        }
        super.render(e, yawIgnored, pt, ps, buf, light);
    }

    private void drawBlock(ColossusEntity e, StructureModel m, BlockPos p, Matrix4d local, PoseStack ps, MultiBufferSource buf, float pt, int light,
                           double ex, double ey, double ez, net.minecraft.client.multiplayer.ClientLevel level, BlockPos.MutableBlockPos lp) {
        if (!m.visible.contains(p)) return;
        BlockState st = m.blocks.get(p);
        if (st == null) return;
        BlockEntity be = e.clientBes.get(p);
        BlockEntityRenderer<BlockEntity> ber = be == null ? null : beRenderers.getRenderer(be);
        if (st.getRenderShape() == RenderShape.INVISIBLE && ber == null) return;
        Vector3d w = local.transformPosition(new Vector3d(p.getX() + .5, p.getY() + .5, p.getZ() + .5));
        lp.set(ex + w.x, ey + w.y, ez + w.z);
        int li = level == null ? light : LevelRenderer.getLightColor(level, lp);
        ps.pushPose();
        ps.translate(p.getX(), p.getY(), p.getZ());
        boolean animated = st.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED;
        boolean staticPart = animated && STATIC_MODEL_MODS.contains(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace());
        if (staticPart) { renderBaked(st, ps, buf, li); ber = null; }
        else if (st.getRenderShape() == RenderShape.MODEL || ber == null)
            try { blocks.renderSingleBlock(st, ps, buf, li, OverlayTexture.NO_OVERLAY); } catch (Exception ignored) {}
        if (ber != null) try { ber.render(be, pt, ps, buf, li, OverlayTexture.NO_OVERLAY); } catch (Throwable bex) { e.clientBes.remove(p); }
        ps.popPose();
    }
}
