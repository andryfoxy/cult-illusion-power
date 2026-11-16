package cult.illusion;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Единственная фигура Безымянного в локальном ритуале (стадии J): божество стоит напротив клинка и вливает в него силу.
 * Это клиентский образ (NamelessAvatar со скином Безымянного), не сущность мира: он не сталкивается, не взаимодействует
 * с миром и существует только пока идёт стадия. В каждой стадии ровно одна фигура. Лицом она всегда обращена к клинку,
 * слегка покачивается; вертикальное смещение позволяет ей подниматься из земли.
 */
final class NamelessFigure {
    private NamelessFigure() {
    }

    private static final class Entry {
        NamelessAvatar avatar;
        double x;
        double y;
        double z;
        double rise;
        double faceX;
        double faceY;
        double faceZ;
        float phase;
    }

    private static final Map<Object, Entry> FIGURES = new HashMap<>();
    private static int nextId = Integer.MAX_VALUE - 2000; // id клиентских моделей (в мире не регистрируются)

    /** Ставит фигуру ключа key на землю в (x,y,z) лицом к точке (faceX,faceY,faceZ). */
    static void place(Minecraft mc, Object key, double x, double y, double z, double faceX, double faceY, double faceZ) {
        if (mc.level == null) {
            return;
        }
        Entry e = new Entry();
        e.avatar = new NamelessAvatar(mc.level);
        e.avatar.setId(nextId--);
        e.avatar.setPos(x, y, z);
        e.avatar.setOldPosAndRot();
        e.x = x;
        e.y = y;
        e.z = z;
        e.faceX = faceX;
        e.faceY = faceY;
        e.faceZ = faceZ;
        e.phase = (float) (Math.abs(key.hashCode()) % 100);
        FIGURES.put(key, e);
    }

    /** Новое положение на земле и вертикальное смещение rise (отрицательное: фигура ещё «в земле»). */
    static void move(Object key, double x, double y, double z, double rise) {
        Entry e = FIGURES.get(key);
        if (e != null) {
            e.x = x;
            e.y = y;
            e.z = z;
            e.rise = rise;
        }
    }

    /** Положение фигуры (x, y с учётом подъёма, z) или null, если фигуры нет. */
    static double[] position(Object key) {
        Entry e = FIGURES.get(key);
        return e == null ? null : new double[]{e.x, e.y + e.rise, e.z};
    }

    static void remove(Object key) {
        FIGURES.remove(key);
    }

    static void clearAll() {
        FIGURES.clear();
    }

    static void render(LevelRenderContext context) {
        if (FIGURES.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double time = mc.level.getGameTime() + partial;
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        for (Entry f : FIGURES.values()) {
            NamelessAvatar model = f.avatar;
            if (model == null || model.level() != mc.level) {
                continue;
            }
            double dx = f.faceX - f.x;
            double dz = f.faceZ - f.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float faceYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            float facePitch = (float) (-Math.atan2(f.faceY - (f.y + f.rise + 1.62), Math.max(1.0e-4, horizontal)) * (180.0 / Math.PI));
            float bodyYaw = faceYaw + (float) (Math.sin(time * 0.035 + f.phase) * 1.5);
            float headYaw = faceYaw + (float) (Math.sin(time * 0.05 + f.phase * 1.3) * 2.5);
            float pitch = Mth.clamp(facePitch + (float) (Math.sin(time * 0.045 + f.phase) * 1.5), -89.0F, 89.0F);
            model.tickCount = (int) time;
            model.setYRot(bodyYaw);
            model.yRotO = bodyYaw;
            model.yBodyRot = bodyYaw;
            model.yBodyRotO = bodyYaw;
            model.setYHeadRot(headYaw);
            model.yHeadRot = headYaw;
            model.yHeadRotO = headYaw;
            model.setXRot(pitch);
            model.xRotO = pitch;
            var state = dispatcher.extractEntity(model, partial);
            state.nameTag = null;
            state.scoreText = null;
            dispatcher.submit(state, context.levelState().cameraRenderState,
                    f.x - camera.x, f.y + f.rise - camera.y, f.z - camera.z, context.poseStack(), context.submitNodeCollector());
        }
    }
}
