package cult.illusion;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.UUID;

/**
 * Разум Божий: экранное искажение восприятия у выбранной жертвы. Работает только на клиенте игрока, чей id совпадает
 * с id цели, выбранной активировавшим способность (тот же код цели, что приходит всем клиентам с меткой в чате).
 * У активировавшего игрока и у остальных клиентов эффект не запускается.
 *
 * Время берётся прямо из счётчика призыва (Summon), поэтому стадии привязаны к ударам колокола: 0-50, 50-100,
 * 100-150, 150-200 тиков, затем пятый удар и затухание ровно MIND_FADE_OUT_TICKS (200 -> 220). Интенсивность
 * плавно переходит от стадии к стадии (Config.MIND_STAGE_INTENSITY).
 *
 * Искажение складывается из двух частей, обе только на стороне клиента, обе не блокируют управление:
 * 1) лёгкое «плавание» отображения камеры (поворот игрока не меняется, как у ScreenShake);
 * 2) полупрозрачные слои поверх HUD: раздвоенные контуры, остаточные следы, призрачные рамки. Сплошных заливок нет.
 * Клоны (MirrorDoubles) от этого эффекта не зависят.
 */
public final class MindDistortion {
    private MindDistortion() {
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_BUILDUP = 1; // идёт призыв, время берётся из Summon
    private static final int MODE_FADE = 2;    // пятый удар прошёл, плавное затухание

    private static int mode = MODE_NONE;
    private static float intensity = 0.0F;
    private static float previous = 0.0F;
    private static float fadeFrom = 0.0F;
    private static int fadeAge = 0;
    private static int phase = 0;

    public static boolean isActive() {
        return mode != MODE_NONE;
    }

    public static void reset() {
        mode = MODE_NONE;
        intensity = 0.0F;
        previous = 0.0F;
        fadeFrom = 0.0F;
        fadeAge = 0;
        phase = 0;
    }

    /**
     * Начало призыва Разума. subject — кто активировал (null = локальный игрок), targetCode — id выбранной цели + 1
     * (0 = цели нет). Запускается только у жертвы: не у активировавшего и не у остальных.
     */
    public static void begin(Ability chosen, UUID subject, long targetCode) {
        if (chosen != Ability.MIND) {
            return;
        }
        reset(); // повторная активация никогда не накладывается на старое состояние
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || subject == null || targetCode <= 0L) {
            return;
        }
        if (subject.equals(mc.player.getUUID()) || targetCode != (long) mc.player.getId() + 1L) {
            return;
        }
        mode = MODE_BUILDUP;
    }

    /** Особый звук: максимум достигнут, начинается затухание. Вызывается из Summon в тик особого звука (100). */
    public static void peak() {
        if (mode != MODE_BUILDUP) {
            return;
        }
        mode = MODE_FADE;
        fadeAge = -1; // тик самого удара (200) ещё держит максимум; затухание считается со следующего тика
        intensity = Config.MIND_STAGE_INTENSITY[Config.MIND_STAGE_INTENSITY.length - 1]; // максимум на особом звуке
        previous = intensity;
        fadeFrom = intensity;
    }

    public static void tick(Minecraft mc) {
        if (mode == MODE_NONE) {
            return;
        }
        // Цель умерла или недоступна: эффект сразу прекращается без остатков
        if (mc.player == null || mc.level == null || mc.player.isDeadOrDying()) {
            reset();
            return;
        }
        previous = intensity;
        if (mode == MODE_BUILDUP) {
            if (!Summon.isMindActive()) {
                reset(); // призыв прерван (сброс состояния): эффект не должен остаться
                return;
            }
            // Summon увеличивает счётчик сразу после удара, поэтому в тик удара n возраст равен n * интервал
            intensity = stageIntensity(Math.max(0, Summon.currentTick() - 1));
        } else {
            fadeAge++;
            if (fadeAge >= Config.MIND_FADE_OUT_TICKS) {
                reset(); // 220 тиков: экран полностью в норме
                return;
            }
            float k = fadeAge / (float) Config.MIND_FADE_OUT_TICKS;
            intensity = fadeFrom * (1.0F - smooth(k));
        }
        phase++;
    }

    /** Интенсивность в момент age тиков от первого удара (0..200): стадии с плавным переходом на границах. */
    static float stageIntensity(int age) {
        float[] stages = Config.MIND_STAGE_INTENSITY;
        int interval = Math.max(1, Config.STRIKE_INTERVAL_TICKS);
        int stage = Math.min(Math.min(age / interval, Config.BELL_COUNT - 1), stages.length - 1);
        float target = stages[stage];
        float from = stage == 0 ? 0.0F : stages[stage - 1];
        int local = age - stage * interval;
        int blend = Math.max(1, Config.MIND_STAGE_BLEND_TICKS);
        if (local >= blend) {
            return target;
        }
        return from + (target - from) * smooth(local / (float) blend);
    }

    private static float smooth(float t) {
        float c = Math.max(0.0F, Math.min(1.0F, t));
        return c * c * (3.0F - 2.0F * c);
    }

    private static double level(float partial) {
        double raw = previous + (intensity - previous) * partial;
        // Влияние способностей выключено: искажение остаётся едва заметным (слабая цветовая кромка и оттенок, без глитча)
        return AbilityInfluence.enabled() ? raw : Math.min(raw, Config.MIND_REDUCED_LEVEL_CAP);
    }

    /** Интенсивность для шейдера постобработки в текущем кадре; 0, если искажение не идёт. */
    public static float renderLevel() {
        return mode == MODE_NONE ? 0.0F : (float) Math.max(0.0, level(partialNow()));
    }

    private static float partialNow() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    /** Смещение отображения камеры (градусы); поворот самого игрока и управление не затрагиваются. */
    public static float yawOffset() {
        if (!AbilityInfluence.enabled()) {
            return 0.0F; // без влияния: камера не качается и не плывёт
        }
        double t = System.nanoTime() / 1.0e9;
        double level = level(partialNow());
        double wave = Math.sin(t * 0.9) * 0.6 + Math.sin(t * 1.7 + 1.3) * 0.4;
        double shake = Math.sin(t * 5.3) * 0.6 + Math.sin(t * 8.1 + 1.3) * 0.4;
        return (float) (wave * level * Config.MIND_SWAY_YAW_DEGREES + shake * shakeAmount(level) * Config.MIND_SHAKE_YAW_DEGREES);
    }

    public static float pitchOffset() {
        if (!AbilityInfluence.enabled()) {
            return 0.0F;
        }
        double t = System.nanoTime() / 1.0e9;
        double level = level(partialNow());
        double wave = Math.sin(t * 0.7 + 0.5) * 0.6 + Math.sin(t * 1.3 + 2.1) * 0.4;
        double shake = Math.sin(t * 6.1 + 0.5) * 0.6 + Math.sin(t * 9.3 + 2.1) * 0.4;
        return (float) (wave * level * Config.MIND_SWAY_PITCH_DEGREES + shake * shakeAmount(level) * Config.MIND_SHAKE_PITCH_DEGREES);
    }

    // Доля максимальной тряски: 0 на первой стадии, постепенно растёт по стадиям, 1 на четвёртой; при затухании уходит в 0 вместе с интенсивностью
    private static double shakeAmount(double level) {
        double onset = Config.MIND_SHAKE_ONSET;
        double k = clamp01((level - onset) / Math.max(1.0e-3, 1.0 - onset));
        return Math.pow(k, Config.MIND_SHAKE_CURVE);
    }

    // ---- Отрисовка слоёв ----

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (mode == MODE_NONE || MindPost.isRunning()) {
            return; // когда работает шейдер, прежние слои поверх HUD не нужны
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        double level = level(partial);
        if (level <= 0.002) {
            return;
        }
        double time = phase + partial;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        drawTint(graphics, width, height, level, time);
        drawEchoContours(graphics, width, height, level, time);
        // Остаточные следы: со второй стадии (интенсивность около 35%)
        double trails = clamp01((level - 0.2) / 0.45);
        if (trails > 0.0) {
            drawTrails(graphics, width, height, level, trails, time);
        }
        // Наложенные реальности: с третьей стадии (65%), к четвёртой слоёв больше
        double ghosts = clamp01((level - 0.45) / 0.5);
        if (ghosts > 0.0) {
            drawGhostFrames(graphics, width, height, level, ghosts, time);
        }
    }

    // Едва заметный общий оттенок, а не затемнение
    private static void drawTint(GuiGraphicsExtractor g, int width, int height, double level, double time) {
        double flicker = 0.65 + 0.35 * Math.sin(time * 0.21);
        int alpha = (int) Math.round(Config.MIND_TINT_MAX_ALPHA * level * level * flicker);
        if (alpha > 0) {
            g.fill(0, 0, width, height, (alpha << 24) | Config.MIND_MID_RGB);
        }
    }

    // Раздвоение контуров: тонкие вертикальные штрихи парами, светлый и тёмный, со сдвигом друг относительно друга
    private static void drawEchoContours(GuiGraphicsExtractor g, int width, int height, double level, double time) {
        double countF = Config.MIND_ECHO_LINES_MAX * (0.25 + 0.75 * level) * level;
        int count = (int) Math.ceil(countF);
        for (int i = 0; i < count; i++) {
            double fadeIn = clamp01(countF - i); // новые штрихи появляются постепенно
            double baseX = rand(i * 7 + 1) * width;
            double y0 = rand(i * 7 + 2) * height * 0.85;
            double length = (0.10 + 0.30 * rand(i * 7 + 3)) * height;
            double drift = Math.sin(time * 0.045 * (0.5 + rand(i * 7 + 4)) + i) * 3.0 * level;
            double split = (1.5 + Config.MIND_ECHO_SPLIT_PX * level) * (0.45 + 0.55 * (0.5 + 0.5 * Math.sin(time * 0.08 + i * 1.7)));
            int alpha = (int) Math.round(Config.MIND_ECHO_MAX_ALPHA * level * fadeIn * (0.5 + 0.5 * rand(i * 7 + 5)));
            if (alpha <= 0) {
                continue;
            }
            int x = (int) Math.round(baseX + drift);
            int xs = (int) Math.round(baseX + drift + split);
            int ya = (int) Math.round(y0);
            int yb = (int) Math.round(y0 + length);
            rect(g, width, height, x, ya, x + 1, yb, (alpha << 24) | Config.MIND_LIGHT_RGB);
            rect(g, width, height, xs, ya, xs + 1, yb, (alpha << 24) | Config.MIND_DARK_RGB);
        }
    }

    // Остаточные следы: тонкие горизонтальные ленты, разорванные пополам и смещённые, медленно плывут по экрану
    private static void drawTrails(GuiGraphicsExtractor g, int width, int height, double level, double strength, double time) {
        double bandsF = Config.MIND_TRAIL_BANDS_MAX * strength;
        int bands = (int) Math.ceil(bandsF);
        for (int j = 0; j < bands; j++) {
            double fadeIn = clamp01(bandsF - j);
            double speed = 0.25 + 0.5 * rand(j * 5 + 11);
            double yPos = ((rand(j * 5 + 12) * height + time * speed * (j % 2 == 0 ? 1 : -1)) % height + height) % height;
            int thickness = 2 + (int) Math.round(rand(j * 5 + 13) * (4.0 + 8.0 * level));
            double shift = Math.sin(time * 0.06 + j * 1.9) * Config.MIND_TRAIL_SHIFT_PX * level;
            int alpha = (int) Math.round(Config.MIND_TRAIL_MAX_ALPHA * strength * level * fadeIn);
            if (alpha <= 0) {
                continue;
            }
            int y = (int) Math.round(yPos);
            int split = (int) Math.round(width * (0.3 + 0.4 * rand(j * 5 + 14)));
            int light = (alpha << 24) | Config.MIND_LIGHT_RGB;
            int dark = (alpha << 24) | Config.MIND_DARK_RGB;
            int s = (int) Math.round(shift);
            // две половины ленты со сдвигом: смещение изображения по горизонтали
            rect(g, width, height, 0, y, split, y + thickness, light);
            rect(g, width, height, split + s, y, width, y + thickness, light);
            // тёмный остаточный след чуть ниже
            rect(g, width, height, 0 + s / 2, y + thickness, split + s / 2, y + thickness + 2, dark);
        }
    }

    // Наложенные варианты реальности: смещённые призрачные рамки кадра (тонкие линии по краям)
    private static void drawGhostFrames(GuiGraphicsExtractor g, int width, int height, double level, double strength, double time) {
        int layers = level > 0.8 ? 3 : 2;
        for (int k = 0; k < layers; k++) {
            double layerFade = k == 2 ? clamp01((level - 0.8) / 0.2) : 1.0;
            int alpha = (int) Math.round(Config.MIND_GHOST_FRAME_ALPHA * strength * layerFade * (k == 0 ? 1.0 : 0.7));
            if (alpha <= 0) {
                continue;
            }
            int ox = (int) Math.round(Math.sin(time * 0.05 + k * 2.1) * Config.MIND_GHOST_SHIFT_PX * strength);
            int oy = (int) Math.round(Math.cos(time * 0.043 + k * 1.3) * Config.MIND_GHOST_SHIFT_PX * 0.6 * strength);
            int rgb = k % 2 == 0 ? Config.MIND_LIGHT_RGB : Config.MIND_MID_RGB;
            int color = (alpha << 24) | rgb;
            int soft = ((alpha / 2) << 24) | Config.MIND_DARK_RGB;
            // левая и правая кромки смещённой копии кадра, затем верхняя и нижняя
            rect(g, width, height, ox, 0, ox + 1, height, color);
            rect(g, width, height, width + ox - 1, 0, width + ox, height, color);
            rect(g, width, height, 0, oy, width, oy + 1, color);
            rect(g, width, height, 0, height + oy - 1, width, height + oy, color);
            // мягкая тёмная кромка рядом
            rect(g, width, height, ox + 1, 0, ox + 4, height, soft);
            rect(g, width, height, width + ox - 4, 0, width + ox - 1, height, soft);
        }
    }

    private static void rect(GuiGraphicsExtractor g, int width, int height, int x0, int y0, int x1, int y1, int color) {
        int ax = Math.max(0, Math.min(x0, x1));
        int bx = Math.min(width, Math.max(x0, x1));
        int ay = Math.max(0, Math.min(y0, y1));
        int by = Math.min(height, Math.max(y0, y1));
        if (bx > ax && by > ay) {
            g.fill(ax, ay, bx, by, color);
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // Детерминированный «шум» по номеру: положение штрихов стабильно между кадрами
    private static double rand(int n) {
        int h = n * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }
}
