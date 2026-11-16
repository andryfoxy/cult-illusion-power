package cult.illusion.mixin;

import cult.illusion.SealController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftChatMixin {
    // Печать божья: любой показ обычного ChatScreen (клавиша чата с любым назначением, другие способы вызова) отменяется у жертвы.
    // Все пути открытия чата проходят через setScreen, поэтому привязка к физической клавише не нужна. Другие экраны не затрагиваются.
    // require = 0: если имя метода в вашей версии другое, мод не падает; тогда уже открытый чат закрывается каждый тик в SealController.tick.
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void cult_illusion$sealBlocksChat(Screen screen, CallbackInfo ci) {
        if (screen != null && SealController.blocksScreen(screen)) {
            ci.cancel();
        }
    }
}
