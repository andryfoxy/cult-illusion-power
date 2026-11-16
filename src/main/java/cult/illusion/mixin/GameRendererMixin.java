package cult.illusion.mixin;

import cult.illusion.MindPost;
import cult.illusion.MindPostHook;
import cult.illusion.NamelessPost;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin implements MindPostHook {
    // Штатный механизм: включает постобработку по id (тот же, что у зрения паука или крипера)
    @Invoker("setPostEffect")
    @Override
    public abstract void cult_illusion$setPostEffect(Identifier id);

    // Перед отрисовкой кадра обновляются значения шейдера Разума (интенсивность, время)
    @Inject(method = "render", at = @At("HEAD"))
    private void cult_illusion$mindUniforms(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        MindPost.beforeRender();
        NamelessPost.beforeRender(); // Безымянный: значения шейдера помех
    }
}
