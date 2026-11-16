package cult.illusion;

/** Тряска только отображения камеры: реальные координаты и поворот игрока не меняются. */
public final class ScreenShake {
    private ScreenShake() {
    }

    private static float amplitude = 0.0F;
    private static float floorAmplitude = 0.0F;
    private static boolean fading = false;
    private static float fadeStep = 0.0F;

    /** Удар призыва: амплитуда резко растёт до peak, затем затухает до доли пика, но не до нуля. */
    public static void strike(float peak) {
        amplitude = peak;
        floorAmplitude = peak * Config.SHAKE_FLOOR_RATIO;
        fading = false;
    }

    /** Конец призыва: плавный возврат камеры. */
    public static void startFadeOut() {
        fading = true;
        fadeStep = amplitude / Config.SHAKE_FADE_OUT_TICKS;
    }

    /** Резкая тряска с плавным затуханием за ticks тиков. */
    public static void kick(float peak, int ticks) {
        amplitude = Math.max(amplitude, peak);
        fading = true;
        fadeStep = amplitude / ticks;
    }

    public static void reset() {
        amplitude = 0.0F;
        floorAmplitude = 0.0F;
        fading = false;
        fadeStep = 0.0F;
    }

    public static void tick() {
        if (fading) {
            amplitude = Math.max(0.0F, amplitude - fadeStep);
        } else if (amplitude > floorAmplitude) {
            amplitude = Math.max(floorAmplitude, amplitude * Config.SHAKE_DECAY_PER_TICK);
        }
    }

    public static boolean isActive() {
        return amplitude > 0.001F;
    }

    public static float yawOffset() {
        double t = System.nanoTime() / 1.0e9;
        double noise = Math.sin(t * 31.3) * 0.6 + Math.sin(t * 17.1 + 1.3) * 0.4;
        return (float) (noise * amplitude * Config.SHAKE_MAX_YAW_DEGREES);
    }

    public static float pitchOffset() {
        double t = System.nanoTime() / 1.0e9;
        double noise = Math.sin(t * 27.7 + 0.5) * 0.6 + Math.sin(t * 13.9 + 2.1) * 0.4;
        return (float) (noise * amplitude * Config.SHAKE_MAX_PITCH_DEGREES);
    }
}
