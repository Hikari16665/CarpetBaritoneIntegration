package me.nuoyuan.carpetbaritoneintegration.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Clears hunger debt while OVERLOAD MODE is active. */
@Mixin(FoodData.class)
public interface FoodDataAccessor {
    @Accessor("exhaustionLevel")
    void cbi$setExhaustionLevel(float exhaustionLevel);
}
