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
        m.ikT = pt; // smooth IK angles between ticks
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
        double[] angles = new double[m.joints.size()];
        double wp = Mth.lerp(pt, e.prevWalkPhase, e.walkPhase), wb = Mth.lerp(pt, e.prevWalkBlend, e.walkBlend), spn = Mth.lerp(pt, e.prevSpin, e.spin);
        for (StructureModel.Joint j : m.joints) angles[j.id] = m.angle(j, wp, wb, spn, e.tickCount + pt);
        m.ikT = 1;
        Matrix4d[] segM = new Matrix4d[m.segs.size()];
        if (e.bake != null) baseM.mul(e.bake); // a wreck keeps the pose it broke off in
        for (int i = 0; i < segM.length; i++) segM[i] = new Matrix4d(baseM).mul(art[i]);
        for (StructureModel.Seg s : m.segs) {
            Matrix4d local = segM[s.id];
            ps.pushPose();
            ps.mulPose(new Matrix4f(local));
            for (BlockPos p : s.blocks) {
                Integer jid = m.jointOf.get(p);
                double[] sp = m.spinParts.get(p);
                if (jid != null && !m.joints.get(jid).childSegs.isEmpty()) drawBlock(e, m, p, local, ps, buf, pt, light, ex, ey, ez, level, lp, Part.STATIC, null, 0);
                else if (m.blocks.get(p).getBlock() instanceof com.colossalcraft.block.SimpleParts.GearboxBlock)
                    drawBlock(e, m, p, local, ps, buf, pt, light, ex, ey, ez, level, lp, Part.ALL_SPIN_TOO, m.blocks.get(p).getValue(net.minecraft.world.level.block.DirectionalBlock.FACING).getAxis(),
                            sp == null ? 0 : angles[(int) sp[0]] * sp[1], sp == null ? 1 : sp[2]);
                else if (sp != null || isClusterMember(m, p)) drawBlock(e, m, p, local, ps, buf, pt, light, ex, ey, ez, level, lp, Part.STATIC, null, 0);
                else drawBlock(e, m, p, local, ps, buf, pt, light, ex, ey, ez, level, lp, Part.ALL, null, 0);
            }
            ps.popPose();
        }
        // bearings turn with the part they move
        for (StructureModel.Joint j : m.joints) {
            if (j.childSegs.isEmpty()) continue;
            Matrix4d local = segM[j.childSegs.get(0)];
            ps.pushPose();
            ps.mulPose(new Matrix4f(local));
            for (BlockPos p : j.blocks) drawBlock(e, m, p, local, ps, buf, pt, light, ex, ey, ez, level, lp, Part.SPIN, null, 0);
            ps.popPose();
        }
        // motor groups: one shaft per group, as thick as the group, turning with its joint (times its gear ratio)
        for (StructureModel.MotorCluster mc : m.motorClusters) {
            BlockPos rep0 = mc.blocks().get(0);
            Matrix4d local = segM[m.segOf.getOrDefault(rep0, 0)];
            double ang = mc.joint() >= 0 ? angles[mc.joint()] * mc.ratio() : e.walkPhase * 2;
            ps.pushPose();
            ps.mulPose(new Matrix4f(local));
            ps.translate(mc.center().x, mc.center().y, mc.center().z);
            var ax = StructureModel.axisVec(mc.axis());
            ps.mulPose(new org.joml.Quaternionf().rotateAxis((float) ang, (float) ax.x, (float) ax.y, (float) ax.z));
            float n = mc.n();
            ps.scale(mc.axis() == net.minecraft.core.Direction.Axis.X ? 1 : n, mc.axis() == net.minecraft.core.Direction.Axis.Y ? 1 : n, mc.axis() == net.minecraft.core.Direction.Axis.Z ? 1 : n);
            ps.translate(-0.5, -0.5, -0.5);
            Vector3d w = local.transformPosition(new Vector3d(mc.center().x, mc.center().y, mc.center().z));
            lp.set(ex + w.x, ey + w.y, ez + w.z);
            drawQuads(m.blocks.get(rep0), ps, buf, level == null ? light : LevelRenderer.getLightColor(level, lp), Part.SPIN);
            ps.popPose();
        }
        // gantry hoist chain
        BlockPos hoist = e.heldBy();
        if (hoist != null && level != null) {
            net.minecraft.world.level.block.state.BlockState chain = net.minecraft.world.level.block.Blocks.CHAIN.defaultBlockState();
            for (int y = hoist.getY() - 1; y > hoist.getY() - 33; y--) {
                net.minecraft.world.phys.Vec3 a = new net.minecraft.world.phys.Vec3(hoist.getX() + .5, y + 1, hoist.getZ() + .5);
                if (e.raycastBlock(a, a.subtract(0, 1, 0)) != null) break;
                ps.pushPose();
                ps.translate(hoist.getX() - ex, y - ey, hoist.getZ() - ez);
                lp.set(hoist.getX(), y, hoist.getZ());
                try { blocks.renderSingleBlock(chain, ps, buf, LevelRenderer.getLightColor(level, lp), OverlayTexture.NO_OVERLAY); } catch (Exception ignored) {}
                ps.popPose();
            }
        }
        super.render(e, yawIgnored, pt, ps, buf, light);
    }

    enum Part { ALL, STATIC, SPIN, SPIN2, ALL_SPIN_TOO }

    /** (u, v) pixels across the axis + middle along it -> block coordinates (0..1). Same layout as the model generator. */
    private static org.joml.Vector3f uvToBlock(net.minecraft.core.Direction.Axis axis, double u, double v) {
        return switch (axis) {
            case X -> new org.joml.Vector3f(0.5f, (float) (u / 16), (float) (v / 16));
            case Y -> new org.joml.Vector3f((float) (u / 16), 0.5f, (float) (v / 16));
            default -> new org.joml.Vector3f((float) (u / 16), (float) (v / 16), 0.5f);
        };
    }
    private static org.joml.Vector3f uAxis(net.minecraft.core.Direction.Axis axis) {
        return axis == net.minecraft.core.Direction.Axis.X ? new org.joml.Vector3f(0, 1, 0) : new org.joml.Vector3f(1, 0, 0);
    }
    private void spinGroup(BlockState st, PoseStack ps, MultiBufferSource buf, int li, Part group, org.joml.Vector3f center, org.joml.Vector3f ax, double angle) {
        ps.pushPose();
        ps.translate(center.x, center.y, center.z);
        ps.mulPose(new org.joml.Quaternionf().rotateAxis((float) angle, ax.x, ax.y, ax.z));
        ps.translate(-center.x, -center.y, -center.z);
        drawQuads(st, ps, buf, li, group);
        ps.popPose();
    }

    private static boolean isClusterMember(StructureModel m, BlockPos p) { return m.blocks.get(p).getBlock() instanceof com.colossalcraft.block.SimpleParts.MotorBlock; }

    /** Draws only the model faces of one kind: STATIC = housing, SPIN = faces marked tintindex 9 (shafts, axles, gears). */
    private void drawQuads(BlockState st, PoseStack ps, MultiBufferSource buf, int light, Part part) {
        var model = blocks.getBlockModel(st);
        var rnd = net.minecraft.util.RandomSource.create(42);
        for (var rt : model.getRenderTypes(st, rnd, net.neoforged.neoforge.client.model.data.ModelData.EMPTY)) {
            var vc = buf.getBuffer(net.neoforged.neoforge.client.RenderTypeHelper.getEntityRenderType(rt, false));
            for (int d = -1; d < 6; d++) {
                rnd.setSeed(42);
                for (var q : model.getQuads(st, d < 0 ? null : net.minecraft.core.Direction.from3DDataValue(d), rnd, net.neoforged.neoforge.client.model.data.ModelData.EMPTY, rt)) {
                    int ti = q.getTintIndex();
                    boolean keep = switch (part) { case STATIC -> ti < 9; case SPIN -> ti == 9; case SPIN2 -> ti == 10; default -> true; };
                    if (!keep) continue;
                    vc.putBulkData(ps.last(), q, 1f, 1f, 1f, 1f, light, OverlayTexture.NO_OVERLAY);
                }
            }
        }
    }

    private void drawBlock(ColossusEntity e, StructureModel m, BlockPos p, Matrix4d local, PoseStack ps, MultiBufferSource buf, float pt, int light,
                           double ex, double ey, double ez, net.minecraft.client.multiplayer.ClientLevel level, BlockPos.MutableBlockPos lp,
                           Part part, net.minecraft.core.Direction.Axis spinAxis, double spinAngle) {
        drawBlock(e, m, p, local, ps, buf, pt, light, ex, ey, ez, level, lp, part, spinAxis, spinAngle, 1);
    }
    private void drawBlock(ColossusEntity e, StructureModel m, BlockPos p, Matrix4d local, PoseStack ps, MultiBufferSource buf, float pt, int light,
                           double ex, double ey, double ez, net.minecraft.client.multiplayer.ClientLevel level, BlockPos.MutableBlockPos lp,
                           Part part, net.minecraft.core.Direction.Axis spinAxis, double spinAngle, double gearRatio) {
        if (part != Part.ALL) {
            BlockState st = m.blocks.get(p); if (st == null) return;
            Vector3d w = local.transformPosition(new Vector3d(p.getX() + .5, p.getY() + .5, p.getZ() + .5));
            lp.set(ex + w.x, ey + w.y, ez + w.z);
            int li = level == null ? light : LevelRenderer.getLightColor(level, lp);
            ps.pushPose();
            ps.translate(p.getX(), p.getY(), p.getZ());
            if (part == Part.ALL_SPIN_TOO && st.getBlock() instanceof com.colossalcraft.block.SimpleParts.GearboxBlock) {
                // housing stays; the input shaft turns with the motor side, the output shaft 1/ratio as fast
                drawQuads(st, ps, buf, li, Part.STATIC);
                var a = StructureModel.axisVec(spinAxis);
                org.joml.Vector3f ax = new org.joml.Vector3f((float) a.x, (float) a.y, (float) a.z), mid = new org.joml.Vector3f(.5f, .5f, .5f);
                spinGroup(st, ps, buf, li, Part.SPIN, mid, ax, spinAngle);
                spinGroup(st, ps, buf, li, Part.SPIN2, mid, ax, spinAngle / gearRatio);
            } else drawQuads(st, ps, buf, li, part);
            ps.popPose();
            return;
        }
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
