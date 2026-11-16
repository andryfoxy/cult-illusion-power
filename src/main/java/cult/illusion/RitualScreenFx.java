package cult.illusion;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Экранный эффект ритуала для тех, кто находится в его области (клиентский слой HUD, рисуется под интерфейсами, в том
 * числе под наковальней). Только по краям экрана, центр остаётся чистым, поэтому переименовывать предметы можно как обычно:
 *  - пульсирующая фиолетово-чёрная виньетка, плотность растёт при приближении к центру и при запуске, спадает при завершении;
 *  - редкие короткие глитч-полосы, отсылающие к проявлениям Безымянного (только пока виньетка достаточно сильна);
 *  - вспышки при ударных волнах, молниях и финальной вспышке ( ослабленные и быстро гаснущие);
 *  - те же виньетки для стадий церемонии (Тень темнит края, Шепот мерцает, Печать светится при замыкании).
 */
final class RitualScreenFx {
    private RitualScreenFx() {
    }

    private static final int BANDS = 12;

    /** Источник экранного эффекта: цвет, сила 0..1 и сила вспышки 0..1. */
    static final class Source {
        final int rgb;
        final double level;
        final double flash;
        final int flashRgb;

        Source(int rgb, double level, double flash, int flashRgb) {
            this.rgb = rgb;
            this.level = level;
            this.flash = flash;
            this.flashRgb = flashRgb;
        }
    }

    static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        java.util.List<Source> sources = new java.util.ArrayList<>();
        RitualAura.collectScreen(mc, sources);
        BladeCeremony.collectScreen(mc, sources);
        if (sources.isEmpty()) {
            return;
        }
        int width = g.guiWidth();
        int height = g.guiHeight();
        double time = mc.level.getGameTime() + delta.getGameTimeDeltaPartialTick(false);
        for (Source s : sources) {
            if (s.level > 0.01) {
                double pulse = 0.8 + 0.2 * Math.sin(time * 0.12);
                vignette(g, width, height, s.rgb, s.level * pulse);
                if (s.level > 0.35) {
                    glitchBars(g, width, height, (long) time, s.level);
                }
                if (s.level > 0.2) {
                    runes(g, mc, width, height, time, s.level, s.rgb);
                }
            }
            if (s.flash > 0.01) {
                int alpha = (int) Math.min(90.0, 90.0 * s.flash);
                g.fill(0, 0, width, height, (alpha << 24) | (s.flashRgb & 0xFFFFFF));
            }
        }
    }

    private static final String RUNES = "\u16A0\u16A2\u16A6\u16A8\u16B1\u16B2\u16B7\u16B9\u16BA\u16BE\u16C1\u16C3\u16C7\u16C8\u16C9\u16CA\u16CF\u16D2\u16D6\u16D7\u16DA\u16DC\u16DE\u16DF";

    // Руны медленно всплывают вдоль краёв экрана и гаснут (читаются как письмена, проступающие в воздухе); центр свободен
    private static void runes(GuiGraphicsExtractor g, Minecraft mc, int width, int height, double time, double level, int rgb) {
        int count = 14;
        for (int i = 0; i < count; i++) {
            double life = ((time * 0.6 + i * 37.0) % 160.0) / 160.0;      // 0..1: положение в цикле жизни руны
            long seed = (long) ((time * 0.6 + i * 37.0) / 160.0) * 31L + i * 17L;
            int column = (int) ((seed * 2654435761L >>> 4) & 7);
            boolean left = (i & 1) == 0;
            int x = left ? 6 + column * 4 : width - 14 - column * 4;
            int y = (int) (height * (1.0 - life) * 0.9 + height * 0.05);
            double fade = Math.sin(life * Math.PI);
            int alpha = (int) Math.min(170.0, 200.0 * level * fade);
            if (alpha <= 8) {
                continue;
            }
            String glyph = String.valueOf(RUNES.charAt((int) ((seed * 40503L >>> 3) % RUNES.length())));
            int color = (alpha << 24) | (i % 3 == 0 ? 0xD8C4FF : 0x9B3FE8);
            g.text(mc.font, glyph, x, y, color);
        }
    }

    // Виньетка: рамка из полос, прозрачность растёт к краю экрана; центр остаётся чистым
    private static void vignette(GuiGraphicsExtractor g, int width, int height, int rgb, double level) {
        int bandW = Math.max(2, width / 36);
        int bandH = Math.max(2, height / 24);
        for (int i = 0; i < BANDS; i++) {
            double t = 1.0 - i / (double) BANDS;
            int alpha = (int) Math.min(150.0, 160.0 * level * t * t);
            if (alpha <= 0) {
                continue;
            }
            int color = (alpha << 24) | (rgb & 0xFFFFFF);
            int x0 = i * bandW;
            int y0 = i * bandH;
            g.fill(x0, y0, width - x0, y0 + bandH, color);                  // верх
            g.fill(x0, height - y0 - bandH, width - x0, height - y0, color); // низ
            g.fill(x0, y0 + bandH, x0 + bandW, height - y0 - bandH, color);  // слева
            g.fill(width - x0 - bandW, y0 + bandH, width - x0, height - y0 - bandH, color); // справа
        }
    }

    // Редкие короткие глитч-полосы: окно в 3 тика примерно раз в 4-5 секунд, только по краям, низкая прозрачность
    private static void glitchBars(GuiGraphicsExtractor g, int width, int height, long time, double level) {
        long window = time / 3;
        if (window % 30 != 7) {
            return;
        }
        int bars = 2 + (int) (window % 3);
        for (int i = 0; i < bars; i++) {
            long h = (window * 2654435761L + i * 40503L) & 0xFFFF;
            int y = (int) (h % Math.max(1, height - 6));
            int thickness = 1 + (int) (h % 4);
            int shift = (int) ((h % 41) - 20);
            int alpha = (int) (60.0 * level);
            int edge = Math.max(8, width / 8);
            g.fill(Math.max(0, shift), y, edge + shift, Math.min(height, y + thickness), (alpha << 24) | 0xB58CFF);
            g.fill(width - edge + shift, y + 2, Math.min(width, width + shift), Math.min(height, y + 2 + thickness), (alpha << 24) | 0x7A2FD6);
        }
    }
}
