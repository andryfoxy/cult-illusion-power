package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;

/**
 * Разум Божий: магические символы вокруг активирующего игрока. Каждый удар колокола призыва добавляет новую группу
 * символов зачарования (штатные частицы enchant: те же знаки, что летят над столом зачарований). Символы появляются
 * в случайных точках вокруг тела, немного подплывают к ним, ненадолго висят в воздухе и исчезают сами; рядом
 * вспыхивают фиолетовые искры, которые и задают палитру.
 *
 * Эффект мировой: его запускает каждый клиент, у которого идёт призыв этого игрока (Summon), поэтому видят все.
 * Своего таймера нет: вызывается из Summon ровно в тик удара колокола. Ничего не отправляется на сервер.
 */
public final class MindSymbols {
    private MindSymbols() {
    }

    /** Группа символов для удара index (0..BELL_COUNT; последний индекс — особый третий звук). */
    public static void strike(Minecraft mc, Player owner, int index) {
        if (mc.level == null || owner == null || owner.isRemoved()) {
            return;
        }
        int count = Config.MIND_GLYPH_BASE_COUNT + Config.MIND_GLYPH_PER_STRIKE * Math.max(0, index);
        ParticleOptions glyph = PowerFx.type("ENCHANT", ParticleTypes.WITCH);
        ParticleOptions dark = PowerFx.dust(Config.MIND_SYMBOL_DARK_RGB, Config.MIND_SPARK_SCALE);
        ParticleOptions mid = PowerFx.dust(Config.MIND_SYMBOL_MID_RGB, Config.MIND_SPARK_SCALE);
        ParticleOptions light = PowerFx.dust(Config.MIND_SYMBOL_LIGHT_RGB, Config.MIND_SPARK_SCALE * 0.8F);

        double cx = owner.getX();
        double cy = owner.getY();
        double cz = owner.getZ();
        for (int i = 0; i < count; i++) {
            double angle = Fx.RANDOM.nextDouble() * Math.PI * 2.0;
            double radius = Config.MIND_GLYPH_RADIUS_MIN
                    + Fx.RANDOM.nextDouble() * (Config.MIND_GLYPH_RADIUS_MAX - Config.MIND_GLYPH_RADIUS_MIN);
            double height = Config.MIND_GLYPH_HEIGHT_MIN
                    + Fx.RANDOM.nextDouble() * (Config.MIND_GLYPH_HEIGHT_MAX - Config.MIND_GLYPH_HEIGHT_MIN);
            double x = cx + Math.cos(angle) * radius;
            double y = cy + height;
            double z = cz + Math.sin(angle) * radius;

            // Частица enchant появляется в точке (x, y, z) + скорость и плавно приходит в (x, y, z):
            // символ возникает чуть дальше и выше и подплывает к своей точке
            double drift = Config.MIND_GLYPH_DRIFT * (0.5 + Fx.RANDOM.nextDouble() * 0.5);
            double vx = Math.cos(angle) * drift * 0.7 + (Fx.RANDOM.nextDouble() - 0.5) * drift * 0.4;
            double vy = drift * (0.2 + Fx.RANDOM.nextDouble() * 0.6);
            double vz = Math.sin(angle) * drift * 0.7 + (Fx.RANDOM.nextDouble() - 0.5) * drift * 0.4;
            Fx.particle(mc, glyph, x, y, z, vx, vy, vz);

            for (int s = 0; s < Config.MIND_SPARK_PER_GLYPH; s++) {
                double roll = Fx.RANDOM.nextDouble();
                ParticleOptions spark = roll < 0.4 ? dark : (roll < 0.75 ? mid : light);
                Fx.particle(mc, spark,
                        x + (Fx.RANDOM.nextDouble() - 0.5) * 0.5,
                        y + (Fx.RANDOM.nextDouble() - 0.5) * 0.5,
                        z + (Fx.RANDOM.nextDouble() - 0.5) * 0.5,
                        0.0, 0.01, 0.0);
            }
        }
    }
}
