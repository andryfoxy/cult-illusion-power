package cult.illusion.mixin;

import cult.illusion.MindDistortion;
import cult.illusion.ScreenShake;
import cult.illusion.WhisperCurse;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Camera.class)
public abstract class CameraMixin {
    // Тряска только отображения камеры: поворот игрока не меняется
    @ModifyArgs(method = "alignWithEntity",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"),
            require = 0)
    private void cult_illusion$shake(Args args) {
        boolean shake = ScreenShake.isActive();
        boolean sway = MindDistortion.isActive(); // только у жертвы Разума Божьего
        boolean glitch = WhisperCurse.isGlitching(); // только у жертвы Шепота божьего
        if (!shake && !sway && !glitch) {
            return;
        }
        float yaw = args.get(0);
        float pitch = args.get(1);
        if (shake) {
            yaw += ScreenShake.yawOffset();
            pitch += ScreenShake.pitchOffset();
        }
        if (sway) {
            yaw += MindDistortion.yawOffset();
            pitch += MindDistortion.pitchOffset();
        }
        if (glitch) {
            yaw += WhisperCurse.yawOffset();
            pitch += WhisperCurse.pitchOffset();
        }
        args.set(0, yaw);
        args.set(1, pitch);
    }
}
