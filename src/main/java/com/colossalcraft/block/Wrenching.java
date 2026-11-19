package com.colossalcraft.block;

import com.colossalcraft.ColossalCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Wrenches turn ColossalCraft parts: right-click turns the part about the face you clicked (like Create's wrench),
 * sneak-right-click picks it up. Works with the Colossus Wrench and with any mod's wrench (item tag c:tools/wrench,
 * which Create's wrench has), so Create's wrench turns our rotors, sails, gearboxes, motors and bearings too.
 */
public final class Wrenching {
    private Wrenching() {}
    static final TagKey<Item> WRENCHES = TagKey.create(net.minecraft.core.registries.Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));

    public static boolean isWrench(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.is(WRENCHES)) return true;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return id.getPath().equals("wrench") || id.getPath().endsWith("_wrench"); // mods that don't tag theirs
    }
    static boolean ours(BlockState s) { return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace().equals(ColossalCraft.MODID); }
    public static boolean turnable(BlockState s) {
        return ours(s) && (s.hasProperty(BlockStateProperties.AXIS) || s.hasProperty(BlockStateProperties.FACING) || s.hasProperty(BlockStateProperties.HORIZONTAL_FACING));
    }

    /** The part turned a quarter turn about the clicked face's axis. */
    public static BlockState turned(BlockState s, Direction face) {
        Direction.Axis about = face.getAxis();
        if (s.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis a = s.getValue(BlockStateProperties.AXIS);
            Direction.Axis n = a == about ? Direction.Axis.values()[(a.ordinal() + 1) % 3] // pointing at you: try the next way
                    : java.util.Arrays.stream(Direction.Axis.values()).filter(x -> x != a && x != about).findFirst().get();
            return s.setValue(BlockStateProperties.AXIS, n);
        }
        if (s.hasProperty(BlockStateProperties.FACING)) {
            Direction f = s.getValue(BlockStateProperties.FACING);
            return s.setValue(BlockStateProperties.FACING, f.getAxis() == about ? f.getOpposite() : f.getClockWise(about));
        }
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.setValue(BlockStateProperties.HORIZONTAL_FACING, s.getValue(BlockStateProperties.HORIZONTAL_FACING).getClockWise());
        return s;
    }

    /** Turn (or pick up, sneaking) a part in the world; player may be null (just turn it). Grid parts (big bearings, motors, ball joints, gyros) re-form their assemblies. */
    public static void use(Level level, BlockPos pos, Player player, Direction face) {
        BlockState s = level.getBlockState(pos);
        if (player != null && player.isSecondaryUseActive()) {
            if (!player.getAbilities().instabuild) for (ItemStack d : Block.getDrops(s, (net.minecraft.server.level.ServerLevel) level, pos, level.getBlockEntity(pos), player, player.getMainHandItem())) player.getInventory().placeItemBackInInventory(d);
            level.destroyBlock(pos, false, player);
            return;
        }
        BlockState n = turned(s, face);
        if (s.getBlock() instanceof SimpleParts.GridBlock) n = Cells.with(n, 1, 0, 0, 1, 0); // on its own until it re-forms
        if (n == s) return;
        level.setBlock(pos, n, Block.UPDATE_ALL);
        if (s.getBlock() instanceof SimpleParts.GridBlock) { Grid.removed(level, pos, s, true); Grid.placed(level, pos, true); }
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.ITEM_FRAME_ROTATE_ITEM, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1.2f);
    }

    public static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (!isWrench(e.getItemStack())) return;
        BlockState s = e.getLevel().getBlockState(e.getPos());
        if (!turnable(s) && !(e.getEntity().isSecondaryUseActive() && ours(s))) return;
        if (!e.getLevel().isClientSide) use(e.getLevel(), e.getPos(), e.getEntity(), e.getFace() == null ? Direction.UP : e.getFace());
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.sidedSuccess(e.getLevel().isClientSide));
    }
}
