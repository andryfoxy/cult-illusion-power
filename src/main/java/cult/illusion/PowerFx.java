package cult.illusion;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Вспомогательные клиентские эффекты Власти Божьей: частица dust точного цвета и звуки цепей.
 * Имена в играх разных версий отличаются (например, chain / iron_chain), поэтому они ищутся по имени при первом
 * обращении; если ничего не найдено, используется запасной вариант, мод не падает.
 */
public final class PowerFx {
    private PowerFx() {
    }

    private static final Map<Long, ParticleOptions> DUST = new HashMap<>();
    private static final Map<String, SoundEvent> SOUNDS = new HashMap<>();

    /** Частица dust заданного цвета (0xRRGGBB). Если создать её не удалось, возвращается witch (тоже фиолетовая). */
    public static ParticleOptions dust(int rgb, float scale) {
        long key = ((long) rgb << 32) | Float.floatToIntBits(scale);
        ParticleOptions cached = DUST.get(key);
        if (cached != null) {
            return cached;
        }
        ParticleOptions created = ParticleTypes.WITCH;
        try {
            Class<?> type = Class.forName("net.minecraft.core.particles.DustParticleOptions");
            Object options;
            try {
                options = type.getConstructor(int.class, float.class).newInstance(rgb, scale);
            } catch (NoSuchMethodException e) {
                Vector3f color = new Vector3f(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
                options = type.getConstructor(Vector3f.class, float.class).newInstance(color, scale);
            }
            if (options instanceof ParticleOptions particle) {
                created = particle;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // остаётся witch
        }
        DUST.put(key, created);
        return created;
    }

    /** Простая частица по имени константы ParticleTypes (например, REVERSE_PORTAL); fallback, если её нет. */
    public static ParticleOptions type(String name, ParticleOptions fallback) {
        try {
            Object value = ParticleTypes.class.getField(name).get(null);
            if (value instanceof ParticleOptions options) {
                return options;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // такой частицы нет
        }
        return fallback;
    }

    /** Звук по возможным именам констант SoundEvents, затем по идентификатору ресурса; запасной звук, если ничего нет. */
    public static SoundEvent sound(String resourceId, SoundEvent fallback, String... constantNames) {
        SoundEvent cached = SOUNDS.get(resourceId);
        if (cached != null) {
            return cached;
        }
        SoundEvent found = null;
        for (String name : constantNames) {
            try {
                Object value = SoundEvents.class.getField(name).get(null);
                if (value instanceof SoundEvent event) {
                    found = event;
                    break;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // такой константы нет
            }
        }
        if (found == null) {
            try {
                Object value = SoundEvent.class.getMethod("createVariableRangeEvent", Identifier.class)
                        .invoke(null, Identifier.fromNamespaceAndPath("minecraft", resourceId));
                if (value instanceof SoundEvent event) {
                    found = event;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // запасной звук
            }
        }
        SoundEvent result = found != null ? found : fallback;
        SOUNDS.put(resourceId, result);
        return result;
    }

    public static SoundEvent chainPlace() {
        return sound("block.chain.place", SoundEvents.BELL_BLOCK, "CHAIN_PLACE", "IRON_CHAIN_PLACE");
    }

    public static SoundEvent chainBreak() {
        return sound("block.chain.break", SoundEvents.BELL_BLOCK, "CHAIN_BREAK", "IRON_CHAIN_BREAK");
    }

    public static SoundEvent chainHit() {
        return sound("block.chain.hit", SoundEvents.BELL_BLOCK, "CHAIN_HIT", "IRON_CHAIN_HIT");
    }
}
