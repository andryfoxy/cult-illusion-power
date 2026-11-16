package cult.illusion;

/** Публичная точка для миксина Simple Voice Chat (SealVoice сам пакетный и не должен быть виден снаружи). */
public final class SealVoiceHook {
    private SealVoiceHook() {
    }

    public static boolean sealed() {
        return SealVoice.isSealed();
    }
}
