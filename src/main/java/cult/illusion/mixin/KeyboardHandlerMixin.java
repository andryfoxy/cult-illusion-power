package cult.illusion.mixin;

import cult.illusion.BladeCeremony;
import cult.illusion.CultIllusionClient;
import cult.illusion.RitualAura;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Клавиши церемонии (J) и ритуала (K) при открытом интерфейсе наковальни: обычная обработка клавиш в экранах не вызывает KeyMapping,
 * поэтому нажатие перехватывается здесь, до экрана. В поле названия предмета символ клавиши не попадает.
 * В остальных экранах (и без экрана) ничего не меняется.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void cult_illusion$ceremonyKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (BladeCeremony.onScreenKey(action, event, CultIllusionClient.ceremonyKey())
                || RitualAura.onScreenKey(action, event, CultIllusionClient.ritualKey())) {
            ci.cancel();
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void cult_illusion$ceremonyChar(long window, CharacterEvent event, CallbackInfo ci) {
        if (BladeCeremony.swallowChar()) {
            ci.cancel();
        }
    }
}
