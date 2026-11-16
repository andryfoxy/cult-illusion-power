package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import java.util.Locale;

/**
 * Поиск именованных стендов для брони: центры ритуала и стадий церемонии. Клиентский поиск среди сущностей мира,
 * ничего не создаётся и не изменяется. Имя сравнивается без учёта регистра, буквы «е» и «ё» считаются одинаковыми.
 */
final class StandFinder {
    private StandFinder() {
    }

    static final String RITUAL = "ритуал";
    static final String SHADOW = "тень";
    static final String WHISPER = "шепот";
    static final String SEAL = "печать";

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /** Ближайший к точке стенд с заданным именем в радиусе radius блоков (по высоте 12), либо null. */
    static ArmorStand find(Minecraft mc, double x, double y, double z, String name, double radius) {
        if (mc.level == null) {
            return null;
        }
        AABB box = new AABB(x - radius, y - 12.0, z - radius, x + radius, y + 12.0, z + radius);
        ArmorStand best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, box)) {
            if (stand.isRemoved() || !stand.hasCustomName() || !normalize(stand.getCustomName().getString()).equals(name)) {
                continue;
            }
            double d = stand.distanceToSqr(x, y, z);
            if (d < bestDistance) {
                bestDistance = d;
                best = stand;
            }
        }
        return best;
    }
}
