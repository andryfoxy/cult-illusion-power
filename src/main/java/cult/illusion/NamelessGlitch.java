package cult.illusion;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Random;

/**
 * Безымянный: состояние визуальных помех (аналоговый хоррор). Только клиент игрока, для которого идёт проявление.
 * Сама картинка рисуется шейдером (NamelessPost, assets/cult_illusion/shaders/post/nameless_glitch.fsh); если шейдер
 * не удалось запустить, extract рисует простые слои поверх HUD.
 *
 * Режимы:
 *  FULL  - проявление: 2-3 секунды (40-60 тиков) резких всплесков со случайной интенсивностью и случайной частотой
 *          (длина всплесков и пауз выбирается случайно), без постепенного нарастания;
 *  AFTER - последействие: 5-7 секунд (100-140 тиков) слабых остаточных помех, интенсивность плавно падает до нуля;
 *          никаких штрафов и эффектов, кроме картинки;
 *  SHORT - короткий глитч при исчезновении силуэта (без последействия).
 */
public final class NamelessGlitch {
    private NamelessGlitch() {
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_FULL = 1;
    private static final int MODE_AFTER = 2;
    private static final int MODE_SHORT = 3;

    private static final int FULL_MIN_TICKS = 40;       // 2 с
    private static final int FULL_MAX_TICKS = 60;       // 3 с
    private static final int AFTER_MIN_TICKS = 100;     // 5 с
    private static final int AFTER_MAX_TICKS = 140;     // 7 с
    private static final float AFTER_START_RATIO = 0.30F; // остаточные помехи: доля базовой интенсивности в начале последействия
    private static final int SHORT_MIN_TICKS = 6;
    private static final int SHORT_MAX_TICKS = 10;

    private static final Random RANDOM = new Random();

    private static int mode = MODE_NONE;
    private static int age = 0;
    private static int duration = 0;
    private static int afterDuration = 0;
    private static float base = 0.0F;       // базовая интенсивность эпизода (выбирается случайно)
    private static float level = 0.0F;      // текущая интенсивность для шейдера
    private static float seed = 0.0F;       // зерно текущего всплеска
    private static int burstLeft = 0;
    private static int gapLeft = 0;
    private static float burstLevel = 0.0F;
    private static int gapMax = 3;          // случайная «частота»: максимальная длина паузы между всплесками

    public static boolean isActive() {
        return mode != MODE_NONE;
    }

    /** Идёт именно основное проявление (не последействие и не короткий глитч). */
    public static boolean isFull() {
        return mode == MODE_FULL;
    }

    public static float level() {
        return mode == MODE_NONE ? 0.0F : level;
    }

    public static float seed() {
        return seed;
    }

    /** Длительность основного проявления в тиках текущего эпизода (для синхронизации звука). */
    public static int fullDuration() {
        return duration;
    }

    public static int afterDuration() {
        return afterDuration;
    }

    public static void reset() {
        mode = MODE_NONE;
        age = 0;
        level = 0.0F;
        burstLeft = 0;
        gapLeft = 0;
    }

    /** Основное проявление: 2-3 секунды, затем последействие 5-7 секунд. */
    public static void startFull() {
        reset();
        mode = MODE_FULL;
        duration = FULL_MIN_TICKS + RANDOM.nextInt(FULL_MAX_TICKS - FULL_MIN_TICKS + 1);
        afterDuration = AFTER_MIN_TICKS + RANDOM.nextInt(AFTER_MAX_TICKS - AFTER_MIN_TICKS + 1);
        base = 0.60F + RANDOM.nextFloat() * 0.40F;      // интенсивность выбирается случайно
        gapMax = 1 + RANDOM.nextInt(6);                  // частота выбирается случайно
        nextBurst(true);
    }

    /** Короткий глитч при исчезновении силуэта; возвращает его длительность в тиках. */
    public static int startShort() {
        reset();
        mode = MODE_SHORT;
        duration = SHORT_MIN_TICKS + RANDOM.nextInt(SHORT_MAX_TICKS - SHORT_MIN_TICKS + 1);
        base = 0.75F + RANDOM.nextFloat() * 0.25F;
        gapMax = 1;
        nextBurst(true);
        return duration;
    }

    private static void nextBurst(boolean immediate) {
        burstLeft = 2 + RANDOM.nextInt(5);                              // всплеск 2-6 тиков
        gapLeft = immediate ? 0 : RANDOM.nextInt(gapMax + 1);           // пауза 0..gapMax тиков перед следующим
        burstLevel = base * (0.55F + RANDOM.nextFloat() * 0.45F);
        seed = RANDOM.nextFloat();
    }

    /** Один тик. Возвращает true, когда последовательность целиком закончена (последействие тоже). */
    public static boolean tick() {
        if (mode == MODE_NONE) {
            return false;
        }
        age++;
        if (mode == MODE_FULL || mode == MODE_SHORT) {
            if (age > duration) {
                if (mode == MODE_SHORT) {
                    reset();
                    return true;
                }
                mode = MODE_AFTER;
                age = 0;
                level = base * AFTER_START_RATIO;
                return false;
            }
            if (gapLeft > 0) {
                gapLeft--;
                level = base * 0.10F; // в паузах между всплесками картинка почти чистая
            } else if (burstLeft > 0) {
                burstLeft--;
                level = burstLevel;
                if (burstLeft == 0) {
                    nextBurst(false);
                }
            }
            return false;
        }
        // последействие: интенсивность плавно падает до нуля
        if (age >= afterDuration) {
            reset();
            return true;
        }
        float k = age / (float) afterDuration;
        float fall = (1.0F - k);
        level = base * AFTER_START_RATIO * fall * fall;
        if (age % 7 == 0) {
            seed = RANDOM.nextFloat();
        }
        return false;
    }

    // ---- Запасной вариант: простые слои поверх HUD, только если шейдер не работает ----

    public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (mode == MODE_NONE || NamelessPost.isRunning()) {
            return;
        }
        float k = level;
        if (k <= 0.01F) {
            return;
        }
        int width = g.guiWidth();
        int height = g.guiHeight();
        int bands = 2 + (int) (k * 10.0F);
        for (int i = 0; i < bands; i++) {
            int y = RANDOM.nextInt(Math.max(1, height));
            int thick = 1 + RANDOM.nextInt(1 + (int) (k * 8.0F));
            int shift = (int) ((RANDOM.nextFloat() - 0.5F) * 40.0F * k);
            int alpha = (int) (110.0F * k);
            g.fill(Math.max(0, shift), y, Math.min(width, width + shift), Math.min(height, y + thick), (alpha << 24) | 0xE0E0E0);
            g.fill(0, Math.min(height, y + thick), width, Math.min(height, y + thick + 1), ((alpha / 2) << 24) | 0xFF0030);
        }
        int veil = (int) (50.0F * k);
        g.fill(0, 0, width, height, (veil << 24) | 0x101018);
    }
}
