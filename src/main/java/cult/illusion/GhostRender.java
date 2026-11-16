package cult.illusion;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Настройка копии модели игрока при отрисовке: полупрозрачность, поворот, шаг, взмах.
 * Поля состояния рендера читаются по имени через рефлексию: если в вашей версии игры поле называется иначе,
 * соответствующая часть эффекта просто не применяется, мод не падает.
 */
public final class GhostRender {
    private GhostRender() {
    }

    private static final Map<String, Optional<Field>> FIELDS = new HashMap<>();

    private static Field find(Object state, String name) {
        String key = state.getClass().getName() + "#" + name;
        Optional<Field> cached = FIELDS.get(key);
        if (cached == null) {
            Field field = null;
            try {
                field = state.getClass().getField(name);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // поля нет
            }
            cached = Optional.ofNullable(field);
            FIELDS.put(key, cached);
        }
        return cached.orElse(null);
    }

    private static boolean setFloat(Object state, String name, float value) {
        Field field = find(state, name);
        if (field == null) {
            return false;
        }
        try {
            field.setFloat(state, value);
            return true;
        } catch (IllegalAccessException | RuntimeException e) {
            return false;
        }
    }

    private static boolean setBoolean(Object state, String name, boolean value) {
        Field field = find(state, name);
        if (field == null) {
            return false;
        }
        try {
            field.setBoolean(state, value);
            return true;
        } catch (IllegalAccessException | RuntimeException e) {
            return false;
        }
    }

    /** Направление тела, шаг и взмах оружия копии (attack 0..1, 0 = нет взмаха). Только отрисовка. */
    public static void pose(Object state, float bodyYaw, float walkSpeed, float walkPos, float attack) {
        setFloat(state, "bodyRot", bodyYaw);
        setFloat(state, "yRot", 0.0F);
        setFloat(state, "xRot", 0.0F);
        setFloat(state, "walkAnimationSpeed", walkSpeed);
        setFloat(state, "walkAnimationPos", walkPos);
        setFloat(state, "attackTime", attack);
    }

    /**
     * Готовит модель к отрисовке с прозрачностью alpha (0..1). Возвращает false, если модель в этот кадр рисовать не нужно.
     * Если игра позволяет, используется штатная полупрозрачная модель, иначе призрачное мерцание.
     */
    public static boolean prepare(Object state, float alpha, int salt) {
        if (alpha >= 0.97F) {
            return true;
        }
        if (alpha <= 0.02F) {
            return false;
        }
        boolean translucent = find(state, "isInvisible") != null && find(state, "isInvisibleToPlayer") != null;
        double noise = noise(salt);
        if (!translucent) {
            return noise < alpha;
        }
        if (alpha >= 0.6F) {
            double solid = (alpha - 0.6F) / 0.37F;
            if (noise >= solid) {
                setBoolean(state, "isInvisible", true);
                setBoolean(state, "isInvisibleToPlayer", false);
            }
            return true;
        }
        setBoolean(state, "isInvisible", true);
        setBoolean(state, "isInvisibleToPlayer", false);
        return noise < Math.min(1.0F, alpha / 0.35F);
    }

    // Шум, меняющийся примерно 16 раз в секунду: мерцание без дрожи на каждом кадре
    private static double noise(int salt) {
        int bin = (int) (System.nanoTime() / 60_000_000L);
        int h = bin * 0x9E3779B1 + salt * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & 0xFFFF) / 65536.0;
    }
}
