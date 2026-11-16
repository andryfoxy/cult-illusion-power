package cult.illusion;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Печать божья: неподвижная конструкция из четырёх цепей, обвивающих голову жертвы. Видна всем клиентам с модом.
 *
 * Стиль целиком берётся у цепей Власти (DivineChains): те же звенья (размеры, чередование плоского и узкого, перекрытие
 * соседних звеньев на 35%), тот же фиолетовый цвет CHAINS_RGB, тот же способ передачи геометрии в сборщик отрисовки
 * (Recorder, submitRibbons / submitBlocks через DivineChains.submitBars). Отличается только расположение.
 *
 * Конструкция:
 *  1) кольцо на уровне рта; 2) и 3) два скрещенных кольца, наклонённых в разные стороны; 4) наклонное кольцо под подбородком и над
 *  затылком. Все кольца скруглённые квадраты, чуть шире головы со слоем шляпы, поэтому не врезаются в модель.
 *
 * Положение задаётся в системе головы (влево / вверх / вперёд) по повороту головы и наклону взгляда, без собственных
 * анимаций: ни роста, ни пульсации, ни частиц. Звенья считаются один раз и дальше только переносятся вместе с головой.
 */
public final class SealChains {
    private SealChains() {
    }

    private static final double HEAD_CENTER_BELOW_EYE = 0.07; // центр головы чуть ниже уровня глаз
    private static final double SQUIRCLE_EXPONENT = 0.5;      // 2/n для n = 4: скруглённый квадрат

    // Звенья в системе головы: {a0, b0, c0, a1, b1, c1}, a = влево, b = вверх, c = вперёд, в блоках при росте 1,8
    private static List<double[]> links = null;

    // ---------------------------------------------------------------- Форма колец

    /** Кольцо: полуоси по a и c, смещение вверх, наклон вокруг оси «влево» (tilt) и крен вокруг оси «вперёд» (roll), в градусах. */
    private record Ring(double wa, double wc, double up, double tilt, double roll) {
    }

    private static final Ring[] RINGS = {
            new Ring(0.34, 0.34, -0.12, 0.0, 0.0),     // на уровне рта
            new Ring(0.38, 0.38, 0.05, 0.0, 32.0),     // скрещенное кольцо, крен в одну сторону
            new Ring(0.38, 0.38, 0.05, 0.0, -32.0),    // скрещенное кольцо, крен в другую
            new Ring(0.38, 0.46, 0.03, 35.0, 0.0)      // под подбородком спереди, над затылком сзади (проверено: не заходит в голову со слоем шляпы и не задевает торс)
    };

    private static List<double[]> links() {
        if (links == null) {
            List<double[]> result = new ArrayList<>();
            for (Ring ring : RINGS) {
                buildRing(ring, result);
            }
            links = result;
        }
        return links;
    }

    private static double[] ringPoint(Ring ring, double theta) {
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double a = ring.wa() * Math.signum(cos) * Math.pow(Math.abs(cos), SQUIRCLE_EXPONENT);
        double c = ring.wc() * Math.signum(sin) * Math.pow(Math.abs(sin), SQUIRCLE_EXPONENT);
        double tilt = Math.toRadians(ring.tilt());
        double roll = Math.toRadians(ring.roll());
        // наклон вокруг оси «влево»
        double b1 = -c * Math.sin(tilt);
        double c1 = c * Math.cos(tilt);
        // крен вокруг оси «вперёд»
        double a2 = a * Math.cos(roll) - b1 * Math.sin(roll);
        double b2 = a * Math.sin(roll) + b1 * Math.cos(roll);
        return new double[]{a2, b2 + ring.up(), c1};
    }

    // Равномерная расстановка звеньев по длине кольца: звено от s до s + 1,35 шага, как у цепей Власти
    private static void buildRing(Ring ring, List<double[]> out) {
        int samples = 720;
        double[][] pts = new double[samples + 1][];
        double[] cumulative = new double[samples + 1];
        for (int i = 0; i <= samples; i++) {
            pts[i] = ringPoint(ring, Math.PI * 2.0 * i / samples);
            if (i > 0) {
                double da = pts[i][0] - pts[i - 1][0];
                double db = pts[i][1] - pts[i - 1][1];
                double dc = pts[i][2] - pts[i - 1][2];
                cumulative[i] = cumulative[i - 1] + Math.sqrt(da * da + db * db + dc * dc);
            }
        }
        double total = cumulative[samples];
        int count = Math.max(8, (int) Math.round(total / Config.SEAL_LINK_PITCH));
        double pitch = total / count;
        for (int i = 0; i < count; i++) {
            double[] p0 = pointAt(pts, cumulative, total, i * pitch);
            double[] p1 = pointAt(pts, cumulative, total, (i + 1.35) * pitch);
            out.add(new double[]{p0[0], p0[1], p0[2], p1[0], p1[1], p1[2]});
        }
    }

    private static double[] pointAt(double[][] pts, double[] cumulative, double total, double s) {
        double d = ((s % total) + total) % total;
        int lo = 0;
        int hi = cumulative.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] <= d) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = cumulative[hi] - cumulative[lo];
        double t = span < 1.0e-9 ? 0.0 : (d - cumulative[lo]) / span;
        return new double[]{
                pts[lo][0] + (pts[hi][0] - pts[lo][0]) * t,
                pts[lo][1] + (pts[hi][1] - pts[lo][1]) * t,
                pts[lo][2] + (pts[hi][2] - pts[lo][2]) * t};
    }

    // ---------------------------------------------------------------- Отрисовка

    public static void render(LevelRenderContext context) {
        if (!SealController.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player victim = SealController.victim(mc);
        if (victim == null || mc.player == null) {
            return;
        }
        // Свою голову в виде от первого лица не рисуем: цепи закрывали бы камеру (как и модель головы в игре)
        if (victim == mc.player && mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        double limit = Config.CHAIN_RENDER_DISTANCE * Config.CHAIN_RENDER_DISTANCE;
        if (mc.player.distanceToSqr(victim) > limit) {
            return;
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 camera = context.levelState().cameraRenderState.pos;

        // Система головы: вперёд f, влево l, вверх u (поворот головы и наклон взгляда, с интерполяцией кадра)
        double yaw = Math.toRadians(Mth.rotLerp(partial, victim.yHeadRotO, victim.yHeadRot));
        double pitch = Math.toRadians(Mth.lerp(partial, victim.xRotO, victim.getXRot()));
        double sy = Math.sin(yaw);
        double cy = Math.cos(yaw);
        double sp = Math.sin(pitch);
        double cp = Math.cos(pitch);
        double[] f = {-sy * cp, -sp, cy * cp};
        double[] l = {cy, 0.0, sy};
        double[] u = {-sy * sp, cp, cy * sp};
        double scale = Math.max(0.3, victim.getBbHeight() / 1.8);

        Vec3 eye = victim.getEyePosition(partial);
        double cx = eye.x - u[0] * HEAD_CENTER_BELOW_EYE * scale - camera.x;
        double cyy = eye.y - u[1] * HEAD_CENTER_BELOW_EYE * scale - camera.y;
        double cz = eye.z - u[2] * HEAD_CENTER_BELOW_EYE * scale - camera.z;

        DivineChains.Recorder rec = new DivineChains.Recorder();
        rec.rgb = Config.CHAINS_RGB;
        List<double[]> all = links();
        for (int i = 0; i < all.size(); i++) {
            double[] k = all.get(i);
            double[] a = toWorld(k[0], k[1], k[2], cx, cyy, cz, l, u, f, scale);
            double[] b = toWorld(k[3], k[4], k[5], cx, cyy, cz, l, u, f, scale);
            DivineChains.linkBar(rec, a[0], a[1], a[2], b[0], b[1], b[2], i);
        }
        PoseStack pose = context.poseStack();
        DivineChains.submitBars(pose, context.submitNodeCollector(), rec.bars);
    }

    private static double[] toWorld(double a, double b, double c, double cx, double cy, double cz,
                                    double[] l, double[] u, double[] f, double scale) {
        a *= scale;
        b *= scale;
        c *= scale;
        return new double[]{
                cx + l[0] * a + u[0] * b + f[0] * c,
                cy + l[1] * a + u[1] * b + f[1] * c,
                cz + l[2] * a + u[2] * b + f[2] * c};
    }
}
