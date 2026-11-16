package cult.illusion;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Силуэты Безымянного (тот же клиентский образ, что у проявлений: NamelessAvatar со скином Безымянного) как
 * зрители и шепчущие фигуры церемонии. Это не сущности мира: модели существуют только на клиенте наблюдателя, не
 * сталкиваются, не взаимодействуют с миром и убираются вместе с эффектом, к которому относятся (по ключу группы).
 * Каждая фигура неподвижно стоит на земле лицом к центру группы и слегка покачивается.
 */
final class GhostFigures {
    private GhostFigures() {
    }

    private static final class Figure {
        NamelessAvatar avatar;
        double x;
        double y;
        double z;
        double faceX;
        double faceY;
        double faceZ;
        float phase;
    }

    private static final Map<Object, List<Figure>> GROUPS = new HashMap<>();
    private static int nextId = Integer.MAX_VALUE - 1000; // id клиентских моделей (в мире не регистрируются)

    /** Расставляет count фигур по кругу радиуса radius вокруг центра, лицом к центру. Возвращает число созданных. */
    static int ring(Minecraft mc, Object key, double cx, double cy, double cz, double radius, int count, double startAngle, double faceHeight) {
        if (mc.level == null) {
            return 0;
        }
        List<Figure> list = GROUPS.computeIfAbsent(key, k -> new ArrayList<>());
        int created = 0;
        for (int i = 0; i < count; i++) {
            double a = startAngle + i * Math.PI * 2.0 / count;
            double x = cx + Math.cos(a) * radius;
            double z = cz + Math.sin(a) * radius;
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            if (!mc.level.hasChunkAt(bx, bz)) {
                continue;
            }
            double h = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
            double y = Math.abs(h - cy) > 6.0 ? cy : h;
            Figure f = new Figure();
            f.avatar = new NamelessAvatar(mc.level);
            f.avatar.setId(nextId--);
            f.avatar.setPos(x, y, z);
            f.avatar.setOldPosAndRot();
            f.x = x;
            f.y = y;
            f.z = z;
            f.faceX = cx;
            f.faceY = cy + faceHeight;
            f.faceZ = cz;
            f.phase = (float) (i * 1.7);
            list.add(f);
            created++;
            Fx.particle(mc, net.minecraft.core.particles.ParticleTypes.SMOKE, x, y + 1.0, z, 0.0, 0.02, 0.0);
        }
        return created;
    }

    static void clear(Object key) {
        GROUPS.remove(key);
    }

    static void clearAll() {
        GROUPS.clear();
    }

    /** Позиции фигур группы (для эффектов, привязанных к ним). */
    static List<double[]> positions(Object key) {
        List<double[]> out = new ArrayList<>();
        List<Figure> list = GROUPS.get(key);
        if (list != null) {
            for (Figure f : list) {
                out.add(new double[]{f.x, f.y, f.z});
            }
        }
        return out;
    }

    static void render(LevelRenderContext context) {
        if (GROUPS.isEmpty()) {
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
        for (List<Figure> list : GROUPS.values()) {
            for (Figure f : list) {
                NamelessAvatar model = f.avatar;
                if (model == null || model.level() != mc.level) {
                    continue;
                }
                double dx = f.faceX - f.x;
                double dz = f.faceZ - f.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float faceYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
                float facePitch = (float) (-Math.atan2(f.faceY - (f.y + 1.62), Math.max(1.0e-4, horizontal)) * (180.0 / Math.PI));
                float bodyYaw = faceYaw + (float) (Math.sin(time * 0.04 + f.phase) * 2.0);
                float headYaw = faceYaw + (float) (Math.sin(time * 0.055 + f.phase * 1.3) * 3.0);
                float pitch = Mth.clamp(facePitch + (float) (Math.sin(time * 0.05 + f.phase) * 1.5), -89.0F, 89.0F);
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
                        f.x - camera.x, f.y - camera.y, f.z - camera.z, context.poseStack(), context.submitNodeCollector());
            }
        }
    }
}
