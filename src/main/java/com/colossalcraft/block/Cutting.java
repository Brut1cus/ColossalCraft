package com.colossalcraft.block;

import com.colossalcraft.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The Diamond Stonecutter (cuts what a normal stonecutter can't, like obsidian) and the obsidian pieces it makes. */
public final class Cutting {
    private Cutting() {}

    /** Diamond-blade stonecutter: the vanilla stonecutter screen, but with its own recipes (type colossalcraft:diamond_cutting). */
    public static class DiamondStonecutterBlock extends StonecutterBlock {
        public DiamondStonecutterBlock(Properties p) { super(p); }
        @Override protected MenuProvider getMenuProvider(BlockState s, Level level, BlockPos pos) {
            return new SimpleMenuProvider((id, inv, pl) -> new Menu(id, inv, ContainerLevelAccess.create(level, pos)), Component.translatable("container.colossalcraft.diamond_stonecutter"));
        }
    }
    /** The stonecutter screen's menu with diamond-cutting recipes (a mixin swaps the recipe type it looks up). */
    public static class Menu extends StonecutterMenu {
        private final ContainerLevelAccess access;
        public Menu(int id, Inventory inv) { this(id, inv, ContainerLevelAccess.NULL); }
        public Menu(int id, Inventory inv, ContainerLevelAccess a) { super(id, inv, a); access = a; }
        @Override public boolean stillValid(Player p) { return stillValid(access, p, ModRegistry.DIAMOND_STONECUTTER.get()); }
        @Override public MenuType<?> getType() { return ModRegistry.DIAMOND_CUTTER_MENU.get(); }
    }
    /** A diamond-cutting recipe: one item in, some items out (same format as stonecutting). */
    public static class Recipe extends StonecutterRecipe {
        public Recipe(String group, Ingredient in, ItemStack out) { super(group, in, out); }
        @Override public RecipeType<?> getType() { return ModRegistry.DIAMOND_CUTTING.get(); }
        @Override public RecipeSerializer<?> getSerializer() { return ModRegistry.DIAMOND_CUTTING_SERIALIZER.get(); }
        @Override public ItemStack getToastSymbol() { return new ItemStack(ModRegistry.DIAMOND_STONECUTTER.get()); }
    }
    public static RecipeSerializer<Recipe> serializer() { return new SingleItemRecipe.Serializer<Recipe>(Recipe::new) {}; }

    /** A slab standing on its edge against one side of its block (FACING = that side). */
    public static class VerticalSlabBlock extends Block {
        public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
        public VerticalSlabBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
            Direction f = c.getClickedFace();
            if (f.getAxis().isHorizontal()) return defaultBlockState().setValue(FACING, f.getOpposite()); // against the block clicked
            // placed on top or below: the half nearest where the player looked
            double dx = c.getClickLocation().x - c.getClickedPos().getX() - .5, dz = c.getClickLocation().z - c.getClickedPos().getZ() - .5;
            Direction d = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
            return defaultBlockState().setValue(FACING, d);
        }
        @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SimpleParts.PanelJointBlock.half(s.getValue(FACING)); }
        @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
        @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }
    }
}
