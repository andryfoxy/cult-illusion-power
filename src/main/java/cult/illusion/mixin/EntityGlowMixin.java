package cult.illusion.mixin;

import cult.illusion.Config;
import cult.illusion.DivineJudgment;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityGlowMixin {
    // Клеймо приговора: контур помеченной цели (штатное свечение, видно сквозь стены). Только отрисовка на клиенте,
    // данные сущности не меняются. require = 0: если имя метода в вашей версии другое, мод просто останется без контура.
    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true, require = 0)
    private void cult_illusion$markedGlow(CallbackInfoReturnable<Boolean> cir) {
        if (DivineJudgment.isMarked((Entity) (Object) this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true, require = 0)
    private void cult_illusion$markedOutlineColor(CallbackInfoReturnable<Integer> cir) {
        if (DivineJudgment.isMarked((Entity) (Object) this)) {
            cir.setReturnValue(Config.OUTLINE_RGB);
        }
    }
}
