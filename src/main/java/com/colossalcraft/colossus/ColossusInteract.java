package com.colossalcraft.colossus;

import com.colossalcraft.block.EngineMenu;
import com.colossalcraft.block.HullCoreBlock;
import com.colossalcraft.block.PowerBlockEntity;
import com.colossalcraft.network.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Nameable;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.context.UseOnContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Using and editing blocks on a built Colossus, while it moves.
 * Right-click goes to the real block in the dock (see {@link ColossusDock}), so every block works as it does on the ground.
 * Without a dock (fallback): doors, trapdoors, fence gates and levers toggle; chests, barrels, hoppers, dispensers, crafting tables and engines open;
 * with a block in hand (or sneaking) a block is placed against the clicked face. Left-click mines the block (instant in creative).
 * Containers and engines work on a live copy of their block entity that is saved back into the Colossus.
 */
public final class ColossusInteract {
    private ColossusInteract() {}

    public static void handle(ServerPlayer sp, Net.ColossusBlock a) {
        if (!(sp.level().getEntity(a.entity()) instanceof ColossusEntity c) || c.model == null || c.isRemoved()) return;
        BlockPos p = BlockPos.of(a.pos());
        BlockState st = c.blocks.get(p);
        if (st == null) return;
        if (sp.getEyePosition().distanceTo(c.blockWorldCenter(p)) > sp.blockInteractionRange() + 2) return;
        Direction face = Direction.from3DDataValue(a.face() & 7);
        Vec3 at = (a.face() & 8) != 0 ? new Vec3(((a.face() >> 4) & 31) / 16.0, ((a.face() >> 9) & 31) / 16.0, ((a.face() >> 14) & 31) / 16.0)
                : new Vec3(.5 + face.getStepX() * .5, .5 + face.getStepY() * .5, .5 + face.getStepZ() * .5);
        if (a.action() == 1) hit(sp, c, p, st);
        else if (c.dockSlot >= 0 && ColossusDock.level(sp.server) != null) useInDock(sp, c, p, st, face, at);
        else use(sp, c, p, st, face);
    }

    // ---------------------------------------------------------------- right-click through the dock: the real block does what it does
    private static void useInDock(ServerPlayer sp, ColossusEntity c, BlockPos p, BlockState st, Direction face, Vec3 at) {
        ServerLevel d = ColossusDock.level(sp.server);
        BlockPos dp = ColossusDock.toDock(c, p);
        if (d.getBlockState(dp) != st) { ColossusDock.attach(c); } // out of step: write it again
        ItemStack held = sp.getMainHandItem();
        // engines keep their own screen (the Colossus runs them)
        if ((!sp.isSecondaryUseActive() || held.isEmpty()) && c.liveBe(p) instanceof PowerBlockEntity pe && pe.tier() != null) {
            sp.openMenu(new SimpleMenuProvider((id, inv, pl) -> { EngineMenu m = new EngineMenu(id, inv, pe); m.valid = q -> stillThere(c, p, pe, q); return m; }, st.getBlock().getName()),
                    buf -> EngineMenu.writeOpen(buf, p, st));
            return;
        }
        if (held.getItem() instanceof BlockItem bi) {
            if (bi.getBlock() instanceof HullCoreBlock) { sp.displayClientMessage(Component.literal("A Colossus has only one Hull Core."), true); return; }
            BlockPos q = st.canBeReplaced() ? p : p.relative(face);
            BlockPos wp = BlockPos.containing(c.blockWorldCenter(p).add(c.toWorld(Vec3.atCenterOf(q), Vec3.ZERO, c.yaw).subtract(c.toWorld(Vec3.atCenterOf(p), Vec3.ZERO, c.yaw))));
            if (!c.blocks.containsKey(q) && !sp.level().getBlockState(wp).getCollisionShape(sp.level(), wp).isEmpty()) { sp.displayClientMessage(Component.literal("Something in the world is in the way."), true); return; }
        }
        BlockHitResult hr = new BlockHitResult(Vec3.atLowerCornerOf(dp).add(at), face, dp, false);
        float yRot = sp.getYRot(), yHead = sp.getYHeadRot();
        sp.setYRot(yRot - (float) Math.toDegrees(c.yaw)); sp.setYHeadRot(sp.getYRot()); // look the way the player looks, in the Colossus' own frame
        InteractionResult r;
        try { r = useOn(sp, d, held, hr); }
        finally { sp.setYRot(yRot); sp.setYHeadRot(yHead); }
        if (!r.consumesAction()) r = sp.gameMode.useItem(sp, sp.level(), held, InteractionHand.MAIN_HAND);
        if (r.consumesAction()) sp.swing(InteractionHand.MAIN_HAND, true);
        ColossusDock.flush(c, true);
    }
    /** Like using an item on a block in the world, but in the dock (the vanilla path would use the player's own world). */
    private static InteractionResult useOn(ServerPlayer sp, ServerLevel d, ItemStack stack, BlockHitResult hr) {
        BlockPos pos = hr.getBlockPos(); BlockState bs = d.getBlockState(pos);
        UseOnContext ctx = new UseOnContext(d, sp, InteractionHand.MAIN_HAND, stack, hr) {};
        InteractionResult first = stack.onItemUseFirst(ctx);
        if (first != InteractionResult.PASS) return first;
        boolean anyHeld = !sp.getMainHandItem().isEmpty() || !sp.getOffhandItem().isEmpty();
        boolean bypass = sp.isSecondaryUseActive() && anyHeld && !sp.getMainHandItem().doesSneakBypassUse(d, pos, sp);
        if (!bypass) {
            ItemInteractionResult ir = bs.useItemOn(stack, d, sp, InteractionHand.MAIN_HAND, hr);
            if (ir.consumesAction()) return ir.result();
            if (ir == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                InteractionResult u = bs.useWithoutItem(d, sp, hr);
                if (u.consumesAction()) return u;
            }
        }
        if (stack.isEmpty() || sp.getCooldowns().isOnCooldown(stack.getItem())) return InteractionResult.PASS;
        int n = stack.getCount();
        InteractionResult res = stack.useOn(ctx);
        if (sp.isCreative()) stack.setCount(n);
        return res;
    }

    // ---------------------------------------------------------------- mining
    private static void hit(ServerPlayer sp, ColossusEntity c, BlockPos p, BlockState st) {
        if (st.getBlock() instanceof HullCoreBlock) { sp.displayClientMessage(Component.literal("Park the Colossus first to remove its Hull Core."), true); return; }
        ServerLevel lv = sp.serverLevel();
        Vec3 w = c.blockWorldCenter(p);
        BlockPos wp = BlockPos.containing(w);
        float hard = st.getDestroySpeed(lv, wp);
        if (hard < 0) return;
        double prog;
        if (sp.isCreative() || hard == 0) prog = 1;
        else {
            double perTick = sp.getDigSpeed(st, wp) / hard / (sp.hasCorrectToolForDrops(st, lv, wp) ? 30.0 : 100.0);
            prog = c.mining.merge(p, perTick * 8, Double::sum); // one click counts as a few ticks of digging
        }
        lv.playSound(null, wp, st.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.7f, 0.8f);
        if (prog < 1) return;
        c.mining.remove(p);
        List<BlockPos> del = new java.util.ArrayList<>(List.of(p));
        BlockPos other = otherHalf(c, p, st); if (other != null) del.add(other);
        c.dropBlock(other != null && st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? other : p, sp);
        lv.playSound(null, wp, st.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1f, 0.9f);
        if (!sp.isCreative()) sp.getMainHandItem().hurtAndBreak(1, sp, EquipmentSlot.MAINHAND);
        c.editBlocks(Map.of(), Map.of(), del);
    }

    /** Break a Colossus block now: drops (with a chest's contents), sound, tool wear; the other half of a door goes too. */
    private static void destroy(ServerPlayer sp, ColossusEntity c, BlockPos p, BlockState st) {
        ServerLevel lv = sp.serverLevel();
        BlockPos wp = BlockPos.containing(c.blockWorldCenter(p));
        c.mining.remove(p);
        List<BlockPos> del = new java.util.ArrayList<>(List.of(p));
        BlockPos other = otherHalf(c, p, st); if (other != null) del.add(other);
        c.dropBlock(other != null && st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? other : p, sp);
        lv.levelEvent(null, 2001, wp, net.minecraft.world.level.block.Block.getId(st)); // break sound and bits, at the Colossus
        if (!sp.isCreative()) sp.getMainHandItem().hurtAndBreak(1, sp, EquipmentSlot.MAINHAND);
        c.editBlocks(Map.of(), Map.of(), del);
    }

    // ---------------------------------------------------------------- normal use / mine packets aimed at a Colossus' dock blocks
    /** The Colossus a dock place belongs to, if the player is in its world and near enough. */
    private static ColossusEntity shipOwner(ServerPlayer sp, BlockPos dockPos) {
        if (!ColossusDock.isShipPos(dockPos)) return null;
        ColossusEntity c = ColossusDock.owner(dockPos);
        return c == null || c.isRemoved() || c.level() != sp.level() || c.model == null ? null : c;
    }
    /** Right-click on a Colossus block (the client sees it as its dock block). Returns true if handled. */
    public static boolean packetUse(ServerPlayer sp, net.minecraft.network.protocol.game.ServerboundUseItemOnPacket pk) {
        BlockHitResult hr = pk.getHitResult(); BlockPos dp = hr.getBlockPos();
        if (!ColossusDock.isShipPos(dp)) return false;
        sp.connection.ackBlockChangesUpTo(pk.getSequence());
        ColossusEntity c = shipOwner(sp, dp);
        if (c == null) { ColossusDock.resend(sp, dp); return true; }
        BlockPos p = dp.subtract(ColossusDock.origin(c.dockSlot));
        BlockState st = c.blocks.get(p);
        boolean near = st != null && sp.getEyePosition().distanceTo(c.blockWorldCenter(p)) <= sp.blockInteractionRange() + 2;
        if (near && pk.getHand() == InteractionHand.MAIN_HAND) {
            sp.resetLastActionTime();
            Vec3 at = hr.getLocation().subtract(Vec3.atLowerCornerOf(dp));
            useInDock(sp, c, p, st, hr.getDirection(), new Vec3(clamp01(at.x), clamp01(at.y), clamp01(at.z)));
        }
        ColossusDock.resend(sp, dp); ColossusDock.resend(sp, dp.relative(hr.getDirection()));
        return true;
    }
    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    /** Mining a Colossus block (start, finish, stop), timed like normal mining. Returns true if handled. */
    public static boolean packetMine(ServerPlayer sp, net.minecraft.network.protocol.game.ServerboundPlayerActionPacket pk) {
        var a = pk.getAction();
        if (a != net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && a != net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                && a != net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) return false;
        BlockPos dp = pk.getPos();
        if (!ColossusDock.isShipPos(dp)) return false;
        sp.connection.ackBlockChangesUpTo(pk.getSequence());
        ColossusEntity c = shipOwner(sp, dp);
        if (c == null) { ColossusDock.resend(sp, dp); return true; }
        BlockPos p = dp.subtract(ColossusDock.origin(c.dockSlot));
        BlockState st = c.blocks.get(p);
        if (st == null || sp.getEyePosition().distanceTo(c.blockWorldCenter(p)) > sp.blockInteractionRange() + 2) { ColossusDock.resend(sp, dp); return true; }
        sp.resetLastActionTime();
        if (a == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) { c.mining.remove(p); return true; }
        if (st.getBlock() instanceof HullCoreBlock) { sp.displayClientMessage(Component.literal("Park the Colossus first to remove its Hull Core."), true); ColossusDock.resend(sp, dp); return true; }
        ServerLevel d = ColossusDock.level(sp.server);
        float per = d == null ? 1 : st.getDestroyProgress(sp, d, dp); // the same mining speed as on the ground
        long now = sp.serverLevel().getGameTime();
        boolean go;
        if (a == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            go = sp.isCreative() || per >= 1;
            if (!go) { c.mining.put(p, (double) now); sp.serverLevel().playSound(null, BlockPos.containing(c.blockWorldCenter(p)), st.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.7f, 0.8f); }
        } else {
            Double t0 = c.mining.get(p);
            go = t0 != null && (now - t0 + 1) * per >= 0.7;
        }
        if (go && (per > 0 || sp.isCreative())) destroy(sp, c, p, st); else if (a != net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) ColossusDock.resend(sp, dp);
        return true;
    }

    /** The other half of a door on the Colossus, if any. */
    private static BlockPos otherHalf(ColossusEntity c, BlockPos p, BlockState st) {
        if (!st.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return null;
        BlockPos o = st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? p.above() : p.below();
        BlockState os = c.blocks.get(o);
        return os != null && os.getBlock() == st.getBlock() ? o : null;
    }

    // ---------------------------------------------------------------- right-click
    private static void use(ServerPlayer sp, ColossusEntity c, BlockPos p, BlockState st, Direction face) {
        ItemStack held = sp.getMainHandItem();
        if (!sp.isSecondaryUseActive() || held.isEmpty()) {
            if (toggle(sp, c, p, st)) return;
            if (openMenu(sp, c, p, st)) return;
        }
        if (held.getItem() instanceof BlockItem bi && place(sp, c, p, face, bi, held)) return;
        sp.gameMode.useItem(sp, sp.level(), held, InteractionHand.MAIN_HAND); // nothing to do with the block: use the item as usual
    }

    /** Doors, trapdoors, fence gates and levers. */
    private static boolean toggle(ServerPlayer sp, ColossusEntity c, BlockPos p, BlockState st) {
        Block b = st.getBlock();
        BlockPos wp = BlockPos.containing(c.blockWorldCenter(p));
        Map<BlockPos, BlockState> set = new HashMap<>();
        if ((b instanceof DoorBlock d && d.type().canOpenByHand()) || (b instanceof TrapDoorBlock && b != Blocks.IRON_TRAPDOOR) || b instanceof FenceGateBlock) {
            boolean open = !st.getValue(BlockStateProperties.OPEN);
            set.put(p, st.setValue(BlockStateProperties.OPEN, open));
            BlockPos o = otherHalf(c, p, st);
            if (o != null) set.put(o, c.blocks.get(o).setValue(BlockStateProperties.OPEN, open));
            var snd = b instanceof DoorBlock ? (open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE)
                    : b instanceof TrapDoorBlock ? (open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE)
                    : (open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE);
            sp.level().playSound(null, wp, snd, SoundSource.BLOCKS, 1f, 1f);
        } else if (b instanceof LeverBlock) {
            boolean on = !st.getValue(BlockStateProperties.POWERED);
            set.put(p, st.setValue(BlockStateProperties.POWERED, on));
            sp.level().playSound(null, wp, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3f, on ? 0.6f : 0.5f);
        } else return false;
        c.editBlocks(set, Map.of(), List.of());
        return true;
    }

    /** Crafting tables, engines and containers. */
    private static boolean openMenu(ServerPlayer sp, ColossusEntity c, BlockPos p, BlockState st) {
        Block b = st.getBlock();
        if (b instanceof CraftingTableBlock) {
            sp.openMenu(new SimpleMenuProvider((id, inv, pl) -> new CraftingMenu(id, inv, ContainerLevelAccess.NULL), Component.translatable("container.crafting")));
            return true;
        }
        BlockEntity be = c.liveBe(p);
        if (be instanceof PowerBlockEntity pe) {
            sp.openMenu(new SimpleMenuProvider((id, inv, pl) -> { EngineMenu m = new EngineMenu(id, inv, pe); m.valid = q -> stillThere(c, p, be, q); return m; }, b.getName()),
                    buf -> EngineMenu.writeOpen(buf, p, st));
            return true;
        }
        if (be instanceof Container ct) {
            int n = ct.getContainerSize();
            Container proxy = new Proxy(c, p, be, ct);
            Component title = be instanceof Nameable nm ? nm.getDisplayName() : b.getName();
            MenuType<?>[] rows = {MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6};
            SimpleMenuProvider mp;
            if (n == 5) mp = new SimpleMenuProvider((id, inv, pl) -> new HopperMenu(id, inv, proxy), title);
            else if (n == 9 && !(be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity)) mp = new SimpleMenuProvider((id, inv, pl) -> new DispenserMenu(id, inv, proxy), title);
            else if (n > 0 && n % 9 == 0 && n <= 54) mp = new SimpleMenuProvider((id, inv, pl) -> new ChestMenu(rows[n / 9 - 1], id, inv, proxy, n / 9), title);
            else { sp.displayClientMessage(Component.literal("This can't be opened on a moving Colossus yet: park it first."), true); return true; }
            sp.openMenu(mp);
            sp.level().playSound(null, BlockPos.containing(c.blockWorldCenter(p)), SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1f);
            return true;
        }
        return false;
    }

    private static boolean stillThere(ColossusEntity c, BlockPos p, BlockEntity be, Player pl) {
        return !c.isRemoved() && c.liveBe(p) == be && c.blocks.containsKey(p) && pl.distanceToSqr(c.blockWorldCenter(p)) < 64;
    }

    /** A container riding on a Colossus: edits go to the live block entity and are saved back; it closes if the block goes away. */
    private record Proxy(ColossusEntity c, BlockPos p, BlockEntity be, Container ct) implements Container {
        public int getContainerSize() { return ct.getContainerSize(); }
        public boolean isEmpty() { return ct.isEmpty(); }
        public ItemStack getItem(int i) { return ct.getItem(i); }
        public ItemStack removeItem(int i, int n) { ItemStack s = ct.removeItem(i, n); setChanged(); return s; }
        public ItemStack removeItemNoUpdate(int i) { ItemStack s = ct.removeItemNoUpdate(i); setChanged(); return s; }
        public void setItem(int i, ItemStack s) { ct.setItem(i, s); setChanged(); }
        public void setChanged() { c.saveLive(p); }
        public boolean stillValid(Player pl) { return stillThere(c, p, be, pl); }
        public void clearContent() { ct.clearContent(); setChanged(); }
        public void startOpen(Player pl) {}
        public void stopOpen(Player pl) { c.saveLive(p); }
    }

    // ---------------------------------------------------------------- placing
    private static boolean place(ServerPlayer sp, ColossusEntity c, BlockPos p, Direction face, BlockItem bi, ItemStack held) {
        Block nb = bi.getBlock();
        if (nb instanceof HullCoreBlock) { sp.displayClientMessage(Component.literal("A Colossus has only one Hull Core."), true); return true; }
        BlockPos q = p.relative(face);
        if (c.blocks.containsKey(q)) return false;
        BlockState ns = nb.defaultBlockState();
        if (ns.hasProperty(BlockStateProperties.BED_PART) || (ns.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && !(nb instanceof DoorBlock))) {
            sp.displayClientMessage(Component.literal("Park the Colossus to place this."), true); return true;
        }
        // the player's facing, turned into the Colossus' own frame
        int k = Math.floorMod((int) Math.round(c.yaw / (Math.PI / 2)), 4);
        Rotation back = new Rotation[]{Rotation.NONE, Rotation.COUNTERCLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.CLOCKWISE_90}[k];
        Direction look = back.rotate(sp.getDirection());
        if (ns.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) ns = ns.setValue(BlockStateProperties.HORIZONTAL_FACING, nb instanceof DoorBlock ? look : look.getOpposite());
        else if (ns.hasProperty(BlockStateProperties.FACING)) ns = ns.setValue(BlockStateProperties.FACING, face);
        else if (ns.hasProperty(BlockStateProperties.AXIS)) ns = ns.setValue(BlockStateProperties.AXIS, face.getAxis());
        if (ns.hasProperty(BlockStateProperties.SLAB_TYPE)) ns = ns.setValue(BlockStateProperties.SLAB_TYPE, face == Direction.DOWN ? SlabType.TOP : SlabType.BOTTOM);
        Map<BlockPos, BlockState> set = new HashMap<>();
        set.put(q, ns);
        if (nb instanceof DoorBlock) {
            if (c.blocks.containsKey(q.above())) return false;
            set.put(q, ns.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
            set.put(q.above(), ns.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        }
        // not into the ground or other solid world blocks
        for (BlockPos x : set.keySet()) {
            BlockPos wp = BlockPos.containing(c.toWorld(new Vec3(x.getX() + .5, x.getY() + .5, x.getZ() + .5), c.position(), c.yaw));
            if (!sp.level().getBlockState(wp).getCollisionShape(sp.level(), wp).isEmpty()) { sp.displayClientMessage(Component.literal("Something in the world is in the way."), true); return true; }
        }
        Map<BlockPos, CompoundTag> setBes = new HashMap<>();
        for (var e : set.entrySet()) if (e.getValue().getBlock() instanceof EntityBlock eb) {
            BlockEntity nbe = eb.newBlockEntity(e.getKey(), e.getValue());
            if (nbe != null) setBes.put(e.getKey(), nbe.saveWithFullMetadata(sp.level().registryAccess()));
        }
        c.editBlocks(set, setBes, List.of());
        if (!sp.isCreative()) held.shrink(1);
        sp.level().playSound(null, BlockPos.containing(c.blockWorldCenter(q)), ns.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 0.9f);
        sp.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }
}
