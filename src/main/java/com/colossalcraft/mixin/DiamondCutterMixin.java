package com.colossalcraft.mixin;

import com.colossalcraft.ModRegistry;
import com.colossalcraft.block.Cutting;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The Diamond Stonecutter's menu looks up diamond-cutting recipes instead of stonecutting ones. */
@Mixin(StonecutterMenu.class)
public abstract class DiamondCutterMixin {
    @ModifyArg(method = "setupRecipeList", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/RecipeManager;getRecipesFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/List;"), index = 0)
    private RecipeType<?> colossalcraft$list(RecipeType<?> t) { return (Object) this instanceof Cutting.Menu ? ModRegistry.DIAMOND_CUTTING.get() : t; }
    @ModifyArg(method = "quickMoveStack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/RecipeManager;getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;"), index = 0)
    private RecipeType<?> colossalcraft$one(RecipeType<?> t) { return (Object) this instanceof Cutting.Menu ? ModRegistry.DIAMOND_CUTTING.get() : t; }
}
