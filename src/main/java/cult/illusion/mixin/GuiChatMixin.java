package cult.illusion.mixin;

import cult.illusion.SealController;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** То же, что MinecraftChatMixin, для версий игры, где смена экрана перенесена в Gui. Если метода нет, инъекция не применяется. */
@Pseudo
@Mixin(targets = "net.minecraft.client.gui.Gui")
public abstract class GuiChatMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void cult_illusion$sealBlocksChat(Screen screen, CallbackInfo ci) {
        if (screen != null && SealController.blocksScreen(screen)) {
            ci.cancel();
        }
    }
}
