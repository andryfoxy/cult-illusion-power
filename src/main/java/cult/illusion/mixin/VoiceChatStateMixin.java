package cult.illusion.mixin;

import cult.illusion.SealVoiceHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Необязательная интеграция с Simple Voice Chat 2.6.23+26.2. @Pseudo: если голосового мода нет, миксин молча пропускается
 * и Cult Illusion загружается без ошибок. Цель задана строкой, голосовой мод не является зависимостью.
 *
 * Во время Печати микрофон считается выключенным (isMuted = true), а переключение (клавиша с любым назначением или кнопка
 * в интерфейсе: KeyEvents -> setMuted(!isMuted())) ничего не меняет. Настоящий setMuted не вызывается вообще, поэтому
 * сохранённый в config флаг muted не трогается и после Печати микрофон остаётся таким, каким был у игрока.
 * require = 0: при другой версии голосового мода (имён нет) инъекция просто не применяется.
 */
@Pseudo
@Mixin(targets = "de.maxhenkel.voicechat.voice.client.ClientPlayerStateManager", remap = false)
public abstract class VoiceChatStateMixin {
    @Inject(method = "isMuted", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void cult_illusion$sealMuted(CallbackInfoReturnable<Boolean> cir) {
        if (SealVoiceHook.sealed()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setMuted", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void cult_illusion$sealKeepsMuted(boolean muted, CallbackInfo ci) {
        if (SealVoiceHook.sealed()) {
            ci.cancel();
        }
    }
}
