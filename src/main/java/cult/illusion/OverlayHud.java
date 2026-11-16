package cult.illusion;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

/** Надпись призыва и белая вспышка на экране: только клиентский HUD. */
public final class OverlayHud {
    private OverlayHud() {
    }

    private static final int[][] GLOW_OFFSETS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private static Component title = null;
    private static int titleAge = -1;
    private static int flashAge = -1;
    private static int flashRgb = 0xFFFFFF;
    private static int dimAge = -1;
    private static int dimTicks = 0;

    public static void showTitle(String text) {
        title = Component.literal(text);
        titleAge = 0;
    }

    public static void flash(int rgb) {
        flashRgb = rgb;
        flashAge = 0;
    }

    /** Лёгкое затемнение экрана перед ударом приговора. */
    public static void startDim(int ticks) {
        dimTicks = Math.max(1, ticks);
        dimAge = 0;
    }

    public static void clearDim() {
        dimAge = -1;
    }

    public static void reset() {
        title = null;
        titleAge = -1;
        flashAge = -1;
        dimAge = -1;
    }

    public static void tick() {
        int titleTotal = Config.TITLE_FADE_IN_TICKS + Config.TITLE_STAY_TICKS + Config.TITLE_FADE_OUT_TICKS;
        if (titleAge >= 0) {
            titleAge++;
            if (titleAge > titleTotal) {
                titleAge = -1;
            }
        }
        if (flashAge >= 0) {
            flashAge++;
            if (flashAge > Config.FLASH_TICKS) {
                flashAge = -1;
            }
        }
        if (dimAge >= 0) {
            dimAge++;
            if (dimAge > dimTicks) {
                dimAge = -1;
            }
        }
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        if (dimAge >= 0) {
            float dimProgress = Math.min(1.0F, (dimAge + partial) / dimTicks);
            int dimAlpha = (int) (255.0F * Config.JUDGMENT_DIM_MAX_ALPHA * dimProgress);
            if (dimAlpha > 0) {
                graphics.fill(0, 0, width, height, (dimAlpha << 24) | 0x0A0010);
            }
        }

        if (flashAge >= 0) {
            float progress = Math.min(1.0F, (flashAge + partial) / Config.FLASH_TICKS);
            int alpha = (int) (255.0F * Config.FLASH_MAX_ALPHA * (1.0F - progress));
            if (alpha > 0) {
                graphics.fill(0, 0, width, height, (alpha << 24) | flashRgb);
            }
        }

        // Экранный эффект Власти Божьей у поражённого игрока
        PowerScreen.extract(graphics, delta);

        // Экранное искажение Разума Божьего: рисуется только у выбранной жертвы (у остальных слой пуст)
        MindDistortion.extract(graphics, delta);

        // Глитчи Шепота божьего: только у выбранной жертвы, только во время коротких вспышек
        WhisperCurse.extract(graphics, delta);

        // Метки цели (клеймо приговора и взгляд власти) рисуются поверх мира, поэтому видны и сквозь стены
        TargetMarkers.extract(graphics, delta);

        if (titleAge >= 0 && title != null) {
            float age = titleAge + partial;
            float fadeIn = Math.min(1.0F, age / Config.TITLE_FADE_IN_TICKS);
            float fadeOutStart = Config.TITLE_FADE_IN_TICKS + Config.TITLE_STAY_TICKS;
            float fadeOut = age <= fadeOutStart ? 1.0F
                    : Math.max(0.0F, 1.0F - (age - fadeOutStart) / Config.TITLE_FADE_OUT_TICKS);
            float factor = fadeIn * fadeOut;
            int alpha = (int) (255.0F * factor);
            if (alpha < 8) {
                return;
            }
            int textWidth = mc.font.width(title);
            float fitScale = textWidth > 0 ? (width * 0.9F) / textWidth : Config.TITLE_SCALE;
            float ease = 1.0F - (1.0F - fadeIn) * (1.0F - fadeIn);
            float scale = Math.min(Config.TITLE_SCALE, fitScale) * (0.85F + 0.15F * ease);

            Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(width / 2.0F, height / 2.0F - 20.0F);
            pose.scale(scale, scale);
            int x = -textWidth / 2;
            int y = -mc.font.lineHeight / 2;
            int glowColor = ((int) (alpha * 0.45F) << 24) | Config.TITLE_GLOW_RGB;
            for (int[] offset : GLOW_OFFSETS) {
                graphics.text(mc.font, title, x + offset[0], y + offset[1], glowColor, false);
            }
            graphics.text(mc.font, title, x, y, (alpha << 24) | Config.TITLE_COLOR_RGB, false);
            pose.popMatrix();
        }
    }
}
