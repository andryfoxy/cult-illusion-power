package cult.illusion;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Экранный эффект Власти Божьей у поражённого игрока: тёмно-фиолетовая виньетка по краям с волнообразной границей
 * и медленной пульсацией и едва заметный холодный оттенок. Только клиентский рисунок поверх HUD; без элементов
 * интерфейса, центр экрана не затрагивается.
 *
 * Сила эффекта плавно движется к цели (1, пока игрок в оковах, иначе 0). Поэтому он длится ровно столько же,
 * сколько оковы, исчезает за SCREEN_FADE_OUT_TICKS, а повторное наложение или поддержание способности не запускает
 * появление заново.
 */
public final class PowerScreen {
    private PowerScreen() {
    }

    private static final int COLUMN = 6;      // ширина столбца волнистой границы, в пикселях интерфейса
    private static final int BANDS = 6;       // ступеней затухания виньетки от края к центру
    private static final double MAX_ALPHA = 150.0;
    private static final double TINT_ALPHA = 14.0;

    private static float intensity = 0.0F;
    private static float previous = 0.0F;
    private static int phase = 0;

    public static void reset() {
        intensity = 0.0F;
        previous = 0.0F;
        phase = 0;
    }

    public static void tick(Minecraft mc) {
        previous = intensity;
        boolean active = DivineChains.isLocalBound(mc);
        if (active) {
            intensity = Math.min(1.0F, intensity + 1.0F / Config.SCREEN_FADE_IN_TICKS);
        } else {
            intensity = Math.max(0.0F, intensity - 1.0F / Config.SCREEN_FADE_OUT_TICKS);
        }
        if (intensity > 0.0F) {
            phase++;
        } else {
            phase = 0;
        }
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (intensity <= 0.0F && previous <= 0.0F) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        double level = previous + (intensity - previous) * partial;
        if (level <= 0.001) {
            return;
        }
        double time = phase + partial;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        double pulse = 1.0 + 0.12 * Math.sin(angle(time, Config.SCREEN_PULSE_TICKS));
        double depthBase = Math.min(width, height) * 0.14 * pulse;
        double alphaScale = MAX_ALPHA * level * pulse;

        // Едва заметный холодный оттенок на весь кадр
        int tint = (int) Math.round(TINT_ALPHA * level);
        if (tint > 0) {
            graphics.fill(0, 0, width, height, (tint << 24) | Config.SCREEN_TINT_RGB);
        }

        // Виньетка: у каждого столбца своя глубина, она плавно колышется волной (волнообразная граница)
        for (int x = 0; x < width; x += COLUMN) {
            double depth = depthBase * wave(x / (double) width, time);
            int x2 = Math.min(width, x + COLUMN);
            for (int j = 0; j < BANDS; j++) {
                int alpha = bandAlpha(alphaScale, j);
                int y0 = (int) Math.round(depth * j / BANDS);
                int y1 = (int) Math.round(depth * (j + 1) / BANDS);
                if (alpha <= 0 || y1 <= y0) {
                    continue;
                }
                int color = (alpha << 24) | Config.SCREEN_VIGNETTE_RGB;
                graphics.fill(x, y0, x2, y1, color);                       // верхний край
                graphics.fill(x, height - y1, x2, height - y0, color);     // нижний край
            }
        }
        for (int y = 0; y < height; y += COLUMN) {
            double depth = depthBase * wave(y / (double) height + 0.37, time);
            int y2 = Math.min(height, y + COLUMN);
            for (int j = 0; j < BANDS; j++) {
                int alpha = bandAlpha(alphaScale, j);
                int x0 = (int) Math.round(depth * j / BANDS);
                int x1 = (int) Math.round(depth * (j + 1) / BANDS);
                if (alpha <= 0 || x1 <= x0) {
                    continue;
                }
                int color = (alpha << 24) | Config.SCREEN_VIGNETTE_RGB;
                graphics.fill(x0, y, x1, y2, color);                       // левый край
                graphics.fill(width - x1, y, width - x0, y2, color);       // правый край
            }
        }
    }

    // Затухание от края к центру
    private static int bandAlpha(double scale, int band) {
        double k = 1.0 - band / (double) BANDS;
        return (int) Math.round(scale * Math.pow(k, 1.6));
    }

    // Глубина виньетки вдоль края: около 1 с лёгкими волнами
    private static double wave(double position, double time) {
        return 1.0 + 0.22 * Math.sin(position * Math.PI * 2.0 * 3.0 + time * 0.07 * 2.0)
                + 0.10 * Math.sin(position * Math.PI * 2.0 * 7.0 - time * 0.05 * 3.0);
    }

    private static double angle(double time, int period) {
        return Math.PI * 2.0 * time / period;
    }
}
