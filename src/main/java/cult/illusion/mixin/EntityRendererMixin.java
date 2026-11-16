package cult.illusion.mixin;

import cult.illusion.MirrorDoubles;
import cult.illusion.ShadowCloak;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    // Разум Божий: оригинальная модель активировавшего игрока на короткое время скрывается
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void cult_illusion$hideOriginal(Entity entity, Frustum frustum, double camX, double camY, double camZ,
                                            CallbackInfoReturnable<Boolean> cir) {
        // Тень божья: модель, броня, предметы и остальные части скрыты целиком, пока действует невидимость
        if (MirrorDoubles.isOriginalHidden(entity) || ShadowCloak.isHidden(entity)) {
            cir.setReturnValue(false);
        }
    }
}
