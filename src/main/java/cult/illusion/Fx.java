package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Random;

/** Клиентские частицы и звуки: ничего не создаёт в мире и не отправляется на сервер. */
public final class Fx {
    private Fx() {
    }

    public static final Random RANDOM = new Random();

    public static void particle(Minecraft mc, ParticleOptions type, double x, double y, double z,
                                double vx, double vy, double vz) {
        if (mc.level != null) {
            mc.level.addParticle(type, x, y, z, vx, vy, vz);
        }
    }

    /** count частиц, разлетающихся от центра в случайных направлениях. */
    public static void burst(Minecraft mc, ParticleOptions type, double cx, double cy, double cz,
                             int count, double radius, double speed) {
        for (int i = 0; i < count; i++) {
            double u = RANDOM.nextDouble() * 2.0 - 1.0;
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double s = Math.sqrt(1.0 - u * u);
            double dx = s * Math.cos(angle);
            double dz = s * Math.sin(angle);
            double r = radius * RANDOM.nextDouble();
            particle(mc, type, cx + dx * r, cy + u * r, cz + dz * r, dx * speed, u * speed, dz * speed);
        }
    }

    /** Звук из точки мира (с пространственным позиционированием). */
    public static void sound(Minecraft mc, SoundEvent sound, double x, double y, double z, float volume, float pitch) {
        if (mc.level != null) {
            mc.level.playLocalSound(x, y, z, sound, SoundSource.PLAYERS, volume, pitch, false);
        }
    }

    /** Звук без привязки к позиции (для ударов призыва). */
    public static void ui(Minecraft mc, SoundEvent sound, float volume, float pitch) {
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
