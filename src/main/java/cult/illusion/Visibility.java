package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Прямая видимость: пересечение луча с блоками между наблюдателем и целью. Только чтение мира, на сервер ничего не уходит. */
public final class Visibility {
    private Visibility() {
    }

    /** true, если между from и to нет блоков, перекрывающих обзор (стекло и другие прозрачные блоки не мешают). */
    public static boolean clear(Minecraft mc, Vec3 from, Vec3 to, Entity context) {
        if (mc.level == null) {
            return false;
        }
        ClipContext clip = new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, context);
        return mc.level.clip(clip).getType() == HitResult.Type.MISS;
    }

    /** Цель видна из точки from, если открыта хотя бы одна из трёх точек её тела: голова, центр, ноги. */
    public static boolean canSee(Minecraft mc, Vec3 from, Entity target, Entity context) {
        AABB box = target.getBoundingBox();
        Vec3 center = box.getCenter();
        double height = box.getYsize();
        return clear(mc, from, new Vec3(center.x, box.minY + height * 0.9, center.z), context)
                || clear(mc, from, center, context)
                || clear(mc, from, new Vec3(center.x, box.minY + 0.15, center.z), context);
    }
}
