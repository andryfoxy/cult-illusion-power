package cult.illusion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Власть Божья: цепи на поражённом игроке и магический круг за спиной того, кто применил способность.
 *
 * Цепи: в момент попадания молнии из обеих рук цели вниз к земле опускаются фиолетовые цепи, цель обездвиживается
 * ровно на CHAINS_BIND_TICKS (10 секунд), затем цепи исчезают и управление возвращается.
 * Круг: начинает вырисовываться, когда начинается приговор, держится столько же, сколько цепи, и затем растворяется.
 *
 * Всё только на клиенте: цепи и круг это отдельная геометрия (тонкие фиолетовые бруски штатной модели блока,
 * рисуются только для показа, без блоков, предметов и сущностей), частицы лишь дополняют свечение. Обездвиживание
 * действует только на клиенте самого игрока-цели (если у него установлен мод) и ничего не меняет на сервере.
 */
public final class DivineChains {
    private DivineChains() {
    }

    private static final double TAU = Math.PI * 2.0;
    private static final int FULL_BRIGHT = 15728880; // LightTexture.FULL_BRIGHT
    private static final int NO_OVERLAY = 655360;    // OverlayTexture.NO_OVERLAY

    // Магический символ: одна окружность и четыре одинаковых креста (по референсу). Плоскость вертикальная,
    // позади головы, в системе координат тела владельца (Forward / Right / Up) и поворачивается вместе с ним.
    private static final double CIRCLE_UP = 1.6;          // центр чуть выше верха плеч, на уровне головы
    private static final double CIRCLE_BACK = 0.55;       // за спиной
    private static final double CIRCLE_RADIUS = 1.1;      // уменьшен, чтобы нижний крест оставался над землёй
    private static final double INNER_RADIUS = 0.6;       // внутренняя концентрическая окружность, в долях внешнего радиуса
    private static final int INNER_SEGMENTS = 48;
    private static final double CIRCLE_GAP = 0.18;        // половина разрыва окружности у креста, в радианах
    private static final double CROSS_IN = 0.21;          // радиальная линия креста: от R*(1-0.21)
    private static final double CROSS_OUT = 0.35;         // до R*(1+0.35), то есть пересекает окружность
    private static final double CROSS_ARM_AT = 1.17;      // поперечная линия смещена наружу, за границу окружности
    private static final double CROSS_ARM_HALF = 0.19;    // её половина длины в долях R
    private static final float LINE_WIDTH = 0.05F;        // одинаковая толщина всей геометрии символа
    private static final int CIRCLE_SEGMENTS = 72;

    // Цепи: от рук (в системе координат тела) вниз к точке на земле
    private static final double HAND_X = 0.375;
    private static final double HAND_Y = 0.78;
    private static final double CHAIN_REACH = 0.95;       // на каком расстоянии от руки цепь уходит в землю
    private static final double CHAIN_BULGE = 0.35;       // насколько дуга поднимается над прямой линией
    private static final double CHAIN_SPREAD_DEG = 60.0;  // веер цепей: от -60 до +60 градусов вокруг направления наружу
    private static final int CHAIN_LINKS = 14;            // звеньев в каждой цепи

    // Состояние эффекта. strikeAge: тики с попадания молнии (-1 до удара), circleAge: тики с начала приговора.
    private static UUID circleOwner = null;
    private static UUID bindTarget = null;
    private static int circleAge = -1;
    private static int strikeAge = -1;
    private static boolean releaseDone = false;
    private static Object boundLevel = null;

    // Диагностика: сообщения только при смене состояния или ошибке, не каждый кадр
    private static final Logger LOG = LoggerFactory.getLogger("cult_illusion");
    private static boolean renderInvoked = false;
    private static boolean loggedOwner = false;
    private static boolean loggedRender = false;
    private static boolean loggedBars = false;
    private static boolean warnedNoRender = false;
    private static boolean warnedHandler = false;
    private static String loggedPath = "";
    private static boolean resolved = false;

    // Блокировка движения на клиенте цели
    private static boolean wasLocked = false;
    private static int lockTicks = 0;

    // Отрисовка через штатный сборщик отрисовки (имена ищутся по версии игры)
    private static Class<?> collectorClass = null;
    private static Method customGeometry = null;
    private static Class<?> customRendererType = null;
    private static Object customType = null;
    private static Method submitBlock = null;
    private static Object barState = null;

    /** Жёсткий сброс: закрытие мира, выход с сервера, смена измерения. Блокировка снимается в startTick. */
    public static void reset() {
        circleOwner = null;
        bindTarget = null;
        circleAge = -1;
        strikeAge = -1;
        releaseDone = false;
        boundLevel = null;
    }

    /** Начало приговора: круг за спиной владельца начинает вырисовываться. */
    public static void begin(Minecraft mc, UUID owner) {
        reset();
        circleOwner = owner;
        circleAge = 0;
        boundLevel = mc.level;
        renderInvoked = false;
        loggedOwner = false;
        loggedRender = false;
        loggedBars = false;
        warnedNoRender = false;
        LOG.info("[circle] begin: owner={}, level={}", owner, mc.level == null ? "null" : mc.level.getClass().getSimpleName());
        if (owner == null) {
            LOG.warn("[circle] owner UUID is null: DivineJudgment.selectTarget did not set it, circle cannot be shown");
        }
    }

    /** Молния попала. target: поражённый игрок или null (тогда цепей и обездвиживания нет, круг всё равно держится 10 секунд). */
    public static void strike(Minecraft mc, Player target) {
        if (circleAge < 0) {
            boundLevel = mc.level;
        }
        strikeAge = 0;
        releaseDone = false;
        bindTarget = target == null ? null : target.getUUID();
        if (target != null) {
            double x = target.getX();
            double y = target.getY();
            double z = target.getZ();
            Fx.sound(mc, PowerFx.chainPlace(), x, y + 0.5, z, Config.CHAIN_SOUND_VOLUME, 0.8F);
            Fx.sound(mc, PowerFx.chainPlace(), x, y + 1.0, z, Config.CHAIN_SOUND_VOLUME, 1.1F);
            Fx.burst(mc, ParticleTypes.WITCH, x, y + 1.0, z, 20, 0.6, 0.12);
            Fx.burst(mc, ParticleTypes.END_ROD, x, y + 1.0, z, 8, 0.5, 0.1);
        }
    }

    private static boolean chainsActive() {
        return bindTarget != null && strikeAge >= 0 && strikeAge < Config.CHAINS_BIND_TICKS;
    }

    // ---------------------------------------------------------------- Обездвиживание (только на клиенте цели)

    private static boolean shouldLock(Minecraft mc) {
        return AbilityInfluence.enabled() && isBoundHere(mc);
    }

    // Игрок этого клиента находится в оковах (визуальное состояние); блокировка управления дополнительно зависит от AbilityInfluence
    private static boolean isBoundHere(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.level == boundLevel && mc.player.isAlive()
                && chainsActive() && mc.player.getUUID().equals(bindTarget);
    }

    /**
     * Начало тика, до обработки клавиш игрой: пока цепи активны, передвижение, прыжок, атака и использование предметов сбрасываются, а скорость
     * по горизонтали гасится. Состояние вычисляется заново каждый тик из таймера эффекта, поэтому после окончания
     * 10 секунд, досрочного прекращения, смены мира, выхода с сервера или смерти управление возвращается само.
     */
    public static void startTick(Minecraft mc) {
        boolean lock = shouldLock(mc);
        if (!lock) {
            lockTicks = 0;
        }
        if (lock && lockTicks < Config.LOCK_SAFETY_TICKS) {
            lockTicks++;
            Options options = mc.options;
            KeyMapping[] keys = {options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
                    options.keyJump, options.keyAttack, options.keyUse};
            for (KeyMapping key : keys) {
                while (key.consumeClick()) {
                    // отбрасываем накопленные нажатия атаки и использования
                }
                key.setDown(false); // игра сама прекратит использование предмета и разрушение блока
            }
            Vec3 motion = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0.0, motion.y, 0.0);
            wasLocked = true;
        } else if (wasLocked) {
            wasLocked = false;
            resyncKeys();
        }
    }

    // Клавиши, которые игрок всё ещё держит, снова читаются из реального состояния клавиатуры
    private static void resyncKeys() {
        try {
            KeyMapping.class.getMethod("setAll").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // нет такого метода в этой версии: клавиши обновятся при следующем нажатии или отпускании
        }
    }

    // ---------------------------------------------------------------- Геометрия

    interface BarSink { // пакетный доступ: тот же приёмник брусков использует SealChains
        /** Брусок от a до b; sx и sz: поперечный размер (у звена цепи они чередуются, как у настоящей цепи). */
        void bar(double ax, double ay, double az, double bx, double by, double bz, float sx, float sz);
    }

    /** Система координат тела: положение, поворот корпуса и высота. Всё привязано к ней, а не к миру. */
    private static final class Frame {
        final double x;
        final double y;
        final double z;
        final double cos;
        final double sin;
        final double hs;

        Frame(Player p, float partial) {
            x = Mth.lerp(partial, p.xo, p.getX());
            y = Mth.lerp(partial, p.yo, p.getY());
            z = Mth.lerp(partial, p.zo, p.getZ());
            double yaw = Math.toRadians(Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot));
            cos = Math.cos(yaw);
            sin = Math.sin(yaw);
            hs = Math.max(0.3, p.getBbHeight() / 1.8);
        }

        // lx: влево от персонажа, lz: вперёд
        double wx(double lx, double lz) {
            return x + lx * cos - lz * sin;
        }

        double wz(double lx, double lz) {
            return z + lx * sin + lz * cos;
        }

        double wy(double ly) {
            return y + ly * hs;
        }
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(1.0, v);
    }

    // Порядок исчезновения элементов при растворении: у каждого элемента своё число 0..1
    private static double rank(int index, int salt) {
        int h = (index + 1) * 0x9E3779B1 + salt * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 13;
        return (h & 0xFFFF) / 65536.0;
    }

    /** Плоскость символа в системе координат тела: Right (влево от персонажа), Up, позади спины. */
    private static final class Plane {
        final double cx;
        final double cy;
        final double cz;
        final double ux;
        final double uz;

        Plane(Frame f) {
            cx = f.wx(0.0, -CIRCLE_BACK);
            cy = f.y + CIRCLE_UP; // высота центра не зависит от позы: нижний крест не уходит в землю
            cz = f.wz(0.0, -CIRCLE_BACK);
            ux = f.cos;
            uz = f.sin;
        }

        // a: вдоль оси Right, b: вверх
        double[] at(double a, double b) {
            return new double[]{cx + ux * a, cy + b, cz + uz * a};
        }
    }

    // Точка на окружности: угол от верха по часовой стрелке
    private static double[] circlePoint(Plane pl, double theta) {
        return pl.at(CIRCLE_RADIUS * Math.sin(theta), CIRCLE_RADIUS * Math.cos(theta));
    }

    // Входит ли угол в разрыв окружности у одного из четырёх крестов
    private static boolean inGap(double theta) {
        for (int k = 0; k < 4; k++) {
            double d = Math.abs(((theta - k * Math.PI / 2.0 + Math.PI) % TAU + TAU) % TAU - Math.PI);
            if (d < CIRCLE_GAP) {
                return true;
            }
        }
        return false;
    }

    /**
     * Символ: одна окружность с четырьмя разрывами и четыре одинаковых креста сверху, снизу, слева, справа.
     * Каждый крест: длинная радиальная линия через окружность и короткая поперечная снаружи (как на референсе).
     * Сначала вырисовывается окружность, затем кресты; при растворении элементы пропадают в случайном порядке.
     */
    private static void circle(Player owner, float partial, double ageF, double dissolve, BarSink sink) {
        Plane pl = new Plane(new Frame(owner, partial));
        double form = clamp01(ageF / Config.BACK_CIRCLE_FORM_TICKS);
        double circleP = clamp01(form / 0.6);
        double crossP = clamp01((form - 0.6) / 0.4);

        int shown = (int) Math.ceil(CIRCLE_SEGMENTS * circleP - 1.0e-6);
        for (int i = 0; i < shown; i++) {
            double mid = TAU * (i + 0.5) / CIRCLE_SEGMENTS;
            if (inGap(mid) || rank(i, 1) <= dissolve) {
                continue;
            }
            double[] a = circlePoint(pl, TAU * i / CIRCLE_SEGMENTS);
            double[] b = circlePoint(pl, TAU * (i + 1) / CIRCLE_SEGMENTS);
            sink.bar(a[0], a[1], a[2], b[0], b[1], b[2], LINE_WIDTH, LINE_WIDTH);
        }
        // Внутренняя окружность: тот же центр, без разрывов
        double inner = CIRCLE_RADIUS * INNER_RADIUS;
        int innerShown = (int) Math.ceil(INNER_SEGMENTS * circleP - 1.0e-6);
        for (int i = 0; i < innerShown; i++) {
            if (rank(i, 2) <= dissolve) {
                continue;
            }
            double a0 = -TAU * i / INNER_SEGMENTS;
            double a1 = -TAU * (i + 1) / INNER_SEGMENTS;
            double[] a = pl.at(inner * Math.sin(a0), inner * Math.cos(a0));
            double[] b = pl.at(inner * Math.sin(a1), inner * Math.cos(a1));
            sink.bar(a[0], a[1], a[2], b[0], b[1], b[2], LINE_WIDTH, LINE_WIDTH);
        }
        if (crossP > 0.0) {
            double r = CIRCLE_RADIUS;
            for (int k = 0; k < 4; k++) {
                double theta = k * Math.PI / 2.0;
                double rx = Math.sin(theta);      // радиальное направление (по a)
                double rz = Math.cos(theta);      // радиальное направление (по b)
                double tx = Math.cos(theta);      // поперечное направление
                double tz = -Math.sin(theta);
                // Радиальная линия: растёт вокруг своей середины
                double r0 = r * (1.0 - CROSS_IN);
                double r1 = r * (1.0 + CROSS_OUT);
                double rm = (r0 + r1) * 0.5;
                double rh = (r1 - r0) * 0.5 * crossP;
                if (rank(k * 2, 3) > dissolve) {
                    double[] a = pl.at(rx * (rm - rh), rz * (rm - rh));
                    double[] b = pl.at(rx * (rm + rh), rz * (rm + rh));
                    sink.bar(a[0], a[1], a[2], b[0], b[1], b[2], LINE_WIDTH, LINE_WIDTH);
                }
                // Поперечная линия снаружи окружности
                double armC = r * CROSS_ARM_AT;
                double armH = r * CROSS_ARM_HALF * crossP;
                if (rank(k * 2 + 1, 3) > dissolve) {
                    double[] a = pl.at(rx * armC - tx * armH, rz * armC - tz * armH);
                    double[] b = pl.at(rx * armC + tx * armH, rz * armC + tz * armH);
                    sink.bar(a[0], a[1], a[2], b[0], b[1], b[2], LINE_WIDTH, LINE_WIDTH);
                }
            }
        }
    }

    // Точка цепи k руки side в момент t (0: рука, 1: земля), в системе координат тела: дуга-полуокружность
    private static double[] chainPoint(int side, int k, double t) {
        int n = Config.CHAINS_PER_HAND;
        double phi = n <= 1 ? 0.0 : (k - (n - 1) / 2.0) * (2.0 * Math.toRadians(CHAIN_SPREAD_DEG) / (n - 1));
        double dist = CHAIN_REACH * (1.0 - Math.cos(Math.PI * t)) * 0.5;
        double y = HAND_Y * (1.0 - t) + CHAIN_BULGE * Math.sin(Math.PI * t);
        return new double[]{side * HAND_X + side * Math.cos(phi) * dist, y, Math.sin(phi) * dist};
    }

    /**
     * Несколько цепей из каждой руки: каждая идёт от руки дугой наружу и вниз и уходит в землю; цепи расходятся
     * веером. Растут от рук, на земле заканчиваются небольшим кольцом-якорем.
     */
    private static void chains(Player p, float partial, double strikeF, BarSink sink) {
        Frame f = new Frame(p, partial);
        double grow = clamp01(strikeF / Config.CHAIN_GROW_TICKS);
        int shown = (int) Math.ceil(CHAIN_LINKS * grow - 1.0e-6);
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < Config.CHAINS_PER_HAND; k++) {
                for (int i = 0; i < shown; i++) {
                    double[] a = chainPoint(side, k, i / (double) CHAIN_LINKS);
                    double[] b = chainPoint(side, k, Math.min(1.0, (i + 1.35) / CHAIN_LINKS));
                    boolean flat = (i & 1) == 0;
                    sink.bar(f.wx(a[0], a[2]), f.wy(a[1]), f.wz(a[0], a[2]), f.wx(b[0], b[2]), f.wy(b[1]), f.wz(b[0], b[2]),
                            flat ? 0.08F : 0.03F, flat ? 0.03F : 0.08F);
                }
                if (grow >= 1.0) {
                    // Якорь на земле: цепь соединяется с поверхностью
                    double[] g = chainPoint(side, k, 1.0);
                    int n = 6;
                    for (int i = 0; i < n; i++) {
                        double a0 = TAU * i / n;
                        double a1 = TAU * (i + 1) / n;
                        double l0x = g[0] + 0.07 * Math.cos(a0);
                        double l0z = g[2] + 0.07 * Math.sin(a0);
                        double l1x = g[0] + 0.07 * Math.cos(a1);
                        double l1z = g[2] + 0.07 * Math.sin(a1);
                        sink.bar(f.wx(l0x, l0z), f.wy(0.03), f.wz(l0x, l0z), f.wx(l1x, l1z), f.wy(0.03), f.wz(l1x, l1z), 0.04F, 0.04F);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Тик: таймеры, частицы, звуки

    public static void tick(Minecraft mc) {
        if (circleAge < 0 && strikeAge < 0) {
            return;
        }
        // Смена мира или измерения, выход с сервера: всё связанное с эффектом удаляется
        if (mc.player == null || mc.level == null || mc.level != boundLevel) {
            reset();
            return;
        }
        if (circleAge >= 0) {
            circleAge++;
        }
        if (strikeAge >= 0) {
            strikeAge++;
        } else if (!DivineJudgment.isActive() && !Summon.isPowerActive()) {
            strikeAge = Config.CHAINS_BIND_TICKS; // призыв или приговор прервались до удара: круг просто растворяется
        }

        Player target = bindTarget == null ? null : mc.level.getPlayerByUUID(bindTarget);
        if (chainsActive() && target != null && !target.isAlive()) {
            strikeAge = Config.CHAINS_BIND_TICKS; // цель погибла: цепи убираются, круг растворяется
        }
        if (strikeAge >= Config.CHAINS_BIND_TICKS && bindTarget != null && !releaseDone) {
            releaseDone = true;
            if (target != null) {
                Fx.sound(mc, PowerFx.chainBreak(), target.getX(), target.getY() + 0.8, target.getZ(), Config.CHAIN_SOUND_VOLUME, 0.7F);
                Fx.burst(mc, PowerFx.dust(Config.CHAINS_RGB, 1.2F), target.getX(), target.getY() + 0.6, target.getZ(), 24, 0.6, 0.08);
                Fx.burst(mc, ParticleTypes.WITCH, target.getX(), target.getY() + 0.6, target.getZ(), 12, 0.5, 0.1);
            }
        }
        if (strikeAge >= Config.CHAINS_BIND_TICKS + Config.BACK_CIRCLE_DISSOLVE_TICKS) {
            reset();
            return;
        }

        if (chainsActive() && target != null) {
            emitChains(mc, target);
        }

        Player owner = circleOwner == null ? null : mc.level.getPlayerByUUID(circleOwner);
        if (circleAge >= 0 && !loggedOwner && circleAge >= 1) {
            loggedOwner = true;
            if (owner != null) {
                LOG.info("[circle] owner {} found on this client (local player: {})", circleOwner, owner == mc.player);
            } else {
                LOG.warn("[circle] owner {} NOT found on this client: nothing can be drawn here", circleOwner);
            }
        }
        if (circleAge == 10 && !renderInvoked && !warnedNoRender) {
            warnedNoRender = true;
            LOG.warn("[circle] render() has not been called for 10 ticks: LevelRenderEvents.COLLECT_SUBMITS listener is not running");
        }
        // Круг рисуется только геометрией. Частицы по его рисунку остались лишь аварийным вариантом на случай, если
        // ни один способ отрисовки геометрии недоступен (тогда об этом пишется в лог), чтобы символ не пропал молча
        if (circleAge >= 0 && owner != null && resolved && !geometryAvailable() && circleAge % 3 == 0) {
            double dissolve = strikeAge >= Config.CHAINS_BIND_TICKS
                    ? clamp01((strikeAge - Config.CHAINS_BIND_TICKS) / (double) Config.BACK_CIRCLE_DISSOLVE_TICKS) : 0.0;
            ParticleOptions dust = PowerFx.dust(Config.CIRCLE_RGB, 0.8F);
            circle(owner, 1.0F, circleAge, dissolve, (ax, ay, az, bx, by, bz, sx, sz) -> {
                double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
                int n = Math.max(1, (int) Math.ceil(len / 0.15));
                for (int i = 0; i <= n; i++) {
                    double t = i / (double) n;
                    Fx.particle(mc, Fx.RANDOM.nextInt(4) == 0 ? ParticleTypes.WITCH : dust,
                            ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, 0.0, 0.0, 0.0);
                }
            });
        }
    }

    private static void emitChains(Minecraft mc, Player target) {
        List<double[]> points = new ArrayList<>(64);
        chains(target, 1.0F, strikeAge, (ax, ay, az, bx, by, bz, sx, sz) ->
                points.add(new double[]{(ax + bx) * 0.5, (ay + by) * 0.5, (az + bz) * 0.5, ay}));
        if (points.isEmpty()) {
            return;
        }
        ParticleOptions enchantedHit = PowerFx.type("ENCHANTED_HIT", ParticleTypes.WITCH);
        ParticleOptions portal = PowerFx.type("PORTAL", ParticleTypes.WITCH);
        // Фиолетовые частицы вдоль цепей
        for (int i = 0; i < 3; i++) {
            double[] m = points.get(Fx.RANDOM.nextInt(points.size()));
            Fx.particle(mc, PowerFx.dust(Config.CHAINS_RGB, 1.0F), m[0], m[1], m[2], 0.0, 0.01, 0.0);
        }
        double[] m = points.get(Fx.RANDOM.nextInt(points.size()));
        Fx.particle(mc, PowerFx.dust(Config.CHAINS_DARK_RGB, 1.2F), m[0], m[1], m[2], 0.0, 0.0, 0.0);
        if (strikeAge % 3 == 0) {
            double[] e = points.get(Fx.RANDOM.nextInt(points.size()));
            Fx.particle(mc, ParticleTypes.END_ROD, e[0], e[1], e[2], 0.0, 0.0, 0.0);
        }
        if (strikeAge % 4 == 0) {
            double[] e = points.get(Fx.RANDOM.nextInt(points.size()));
            Fx.particle(mc, enchantedHit, e[0], e[1], e[2], 0.0, 0.02, 0.0);
        }
        // Энергия у точек, где цепи уходят в землю
        if (strikeAge % 2 == 0 && strikeAge >= Config.CHAIN_GROW_TICKS) {
            Frame f = new Frame(target, 1.0F);
            for (int i = 0; i < 2; i++) {
                double[] g = chainPoint(Fx.RANDOM.nextBoolean() ? 1 : -1, Fx.RANDOM.nextInt(Math.max(1, Config.CHAINS_PER_HAND)), 1.0);
                Fx.particle(mc, portal, f.wx(g[0], g[2]), f.wy(0.1), f.wz(g[0], g[2]), 0.0, 0.05, 0.0);
            }
        }
    }

    // ---------------------------------------------------------------- Отрисовка

    /** Запись брусков кадра: потом они уходят в отрисовку одним вызовом. */
    static final class Recorder implements BarSink { // пакетный доступ для SealChains
        final List<float[]> bars = new ArrayList<>(256);
        int rgb = 0xFFFFFF;

        @Override
        public void bar(double ax, double ay, double az, double bx, double by, double bz, float sx, float sz) {
            bars.add(new float[]{(float) ax, (float) ay, (float) az, (float) bx, (float) by, (float) bz,
                    (sx + sz) * 0.5F, Float.intBitsToFloat(rgb)});
        }
    }

    public static void render(LevelRenderContext context) {
        if (circleAge < 0 && strikeAge < 0) {
            return;
        }
        renderInvoked = true;
        if (!loggedRender) {
            loggedRender = true;
            LOG.info("[circle] render() is being called (circleAge={}, strikeAge={})", circleAge, strikeAge);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.level != boundLevel) {
            return;
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack pose = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        double limit = Config.CHAIN_RENDER_DISTANCE * Config.CHAIN_RENDER_DISTANCE;

        // Все координаты переводятся в систему камеры, как и у остальных объектов мира
        Recorder rec = new Recorder();
        BarSink shifted = (ax, ay, az, bx, by, bz, sx, sz) ->
                rec.bar(ax - camera.x, ay - camera.y, az - camera.z, bx - camera.x, by - camera.y, bz - camera.z, sx, sz);

        Player owner = circleOwner == null ? null : mc.level.getPlayerByUUID(circleOwner);
        if (circleAge >= 0 && owner != null && mc.player.distanceToSqr(owner) <= limit) {
            double dissolve = strikeAge >= Config.CHAINS_BIND_TICKS
                    ? clamp01((strikeAge + partial - Config.CHAINS_BIND_TICKS) / Config.BACK_CIRCLE_DISSOLVE_TICKS) : 0.0;
            rec.rgb = Config.CIRCLE_RGB;
            circle(owner, partial, circleAge + partial, dissolve, shifted);
        }
        if (chainsActive()) {
            Player target = mc.level.getPlayerByUUID(bindTarget);
            if (target != null && mc.player.distanceToSqr(target) <= limit) {
                rec.rgb = Config.CHAINS_RGB;
                chains(target, partial, strikeAge + partial, shifted);
            }
        }
        if (rec.bars.isEmpty()) {
            return;
        }
        if (!loggedBars && circleAge >= Config.BACK_CIRCLE_FORM_TICKS) {
            loggedBars = true;
            LOG.info("[circle] fully formed: {} geometry elements submitted this frame", rec.bars.size());
        }
        Object collector = context.submitNodeCollector();
        resolve(collector);
        String path = customGeometry != null && customType != null ? "customGeometry ribbons"
                : (submitBlock != null && barState != null ? "submitBlock bars" : "particles (no geometry API available)");
        if (!path.equals(loggedPath)) {
            loggedPath = path;
            LOG.info("[circle] draw path: {}", path);
        }
        if (customGeometry != null && customType != null) {
            submitRibbons(pose, collector, rec.bars);
        } else if (submitBlock != null && barState != null) {
            submitBlocks(pose, collector, rec.bars);
        }
    }



    /**
     * Общая часть отрисовки для других эффектов (Печать божья): те же способы передачи геометрии в сборщик
     * (submitCustomGeometry, затем запасной submitBlock), тот же тип отрисовки и те же ленты звеньев. render() Власти не затронут.
     * Брусок в bars: {ax, ay, az, bx, by, bz, ширина, цвет} в системе координат камеры.
     */
    static void submitBars(PoseStack pose, Object collector, List<float[]> bars) {
        if (bars.isEmpty() || collector == null) {
            return;
        }
        resolve(collector);
        if (customGeometry != null && customType != null) {
            submitRibbons(pose, collector, bars);
        } else if (submitBlock != null && barState != null) {
            submitBlocks(pose, collector, bars);
        }
    }

    /** Размеры звена цепи: как в chains() (чередуются плоское и узкое звено). */
    static final float LINK_WIDE = 0.08F;
    static final float LINK_THIN = 0.03F;

    /** Одно звено цепи Власти (тот же размер и чередование, что в chains()) между точками a и b; index определяет чётность звена. */
    static void linkBar(BarSink sink, double ax, double ay, double az, double bx, double by, double bz, int index) {
        boolean flat = (index & 1) == 0;
        sink.bar(ax, ay, az, bx, by, bz, flat ? LINK_WIDE : LINK_THIN, flat ? LINK_THIN : LINK_WIDE);
    }

    private static boolean geometryAvailable() {
        return (customGeometry != null && customType != null) || (submitBlock != null && barState != null);
    }

    /** Локальный игрок сейчас в оковах (для экранного эффекта). */
    public static boolean isLocalBound(Minecraft mc) {
        return isBoundHere(mc); // экранный эффект виден и при выключенном влиянии
    }

    // Способ 1: собственная геометрия: плоские ленты с вершинами и цветом, всегда повёрнутые плоскостью к камере
    private static void submitRibbons(PoseStack pose, Object collector, List<float[]> bars) {
        try {
            Object renderer = Proxy.newProxyInstance(customRendererType.getClassLoader(), new Class<?>[]{customRendererType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("render") && args != null && args.length == 2
                                && args[0] instanceof PoseStack.Pose p && args[1] instanceof VertexConsumer vc) {
                            try {
                                for (float[] b : bars) {
                                    ribbon(vc, p, b);
                                }
                            } catch (RuntimeException e) {
                                disableGeometry("vertex building failed: " + e);
                            }
                            return null;
                        }
                        if (!method.getName().equals("hashCode") && !method.getName().equals("equals")
                                && !method.getName().equals("toString") && !warnedHandler) {
                            warnedHandler = true;
                            LOG.error("[circle] unexpected call on geometry renderer: {} with {} args", method, args == null ? 0 : args.length);
                        }
                        if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (method.getName().equals("equals")) {
                            return proxy == args[0];
                        }
                        if (method.getName().equals("toString")) {
                            return "cult_illusion ribbons";
                        }
                        return null;
                    });
            customGeometry.invoke(collector, pose, customType, renderer);
        } catch (ReflectiveOperationException | RuntimeException e) {
            disableGeometry("submitCustomGeometry failed: " + e);
        }
    }

    // Лента вдоль бруска: ширина w, плоскость к камере (камера в начале координат), обе стороны
    private static void ribbon(VertexConsumer vc, PoseStack.Pose p, float[] b) {
        float dx = b[3] - b[0];
        float dy = b[4] - b[1];
        float dz = b[5] - b[2];
        float mx = (b[0] + b[3]) * 0.5F;
        float my = (b[1] + b[4]) * 0.5F;
        float mz = (b[2] + b[5]) * 0.5F;
        // side = d x view
        float sx = dy * mz - dz * my;
        float sy = dz * mx - dx * mz;
        float sz = dx * my - dy * mx;
        float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (len < 1.0e-6F) {
            return;
        }
        float k = b[6] * 0.5F / len;
        sx *= k;
        sy *= k;
        sz *= k;
        int rgb = Float.floatToRawIntBits(b[7]);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int bl = rgb & 0xFF;
        float[][] quad = {
                {b[0] - sx, b[1] - sy, b[2] - sz}, {b[0] + sx, b[1] + sy, b[2] + sz},
                {b[3] + sx, b[4] + sy, b[5] + sz}, {b[3] - sx, b[4] - sy, b[5] - sz}};
        for (int i = 0; i < 4; i++) {
            vc.addVertex(p, quad[i][0], quad[i][1], quad[i][2]).setColor(r, g, bl, 255);
        }
        for (int i = 3; i >= 0; i--) { // обратный обход: видно с любой стороны независимо от отсечения граней
            vc.addVertex(p, quad[i][0], quad[i][1], quad[i][2]).setColor(r, g, bl, 255);
        }
    }

    // Способ 2 (запасной): тонкие бруски из блока штатной моделью
    private static void submitBlocks(PoseStack pose, Object collector, List<float[]> bars) {
        for (float[] b : bars) {
            double dx = b[3] - b[0];
            double dy = b[4] - b[1];
            double dz = b[5] - b[2];
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0e-3 || submitBlock == null) {
                continue;
            }
            pose.pushPose();
            pose.translate(b[0], b[1], b[2]);
            pose.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F,
                    (float) (dx / length), (float) (dy / length), (float) (dz / length)));
            pose.scale(b[6], (float) length, b[6]);
            pose.translate(-0.5, 0.0, -0.5);
            try {
                submitBlock.invoke(collector, pose, barState, FULL_BRIGHT, NO_OVERLAY, 0);
            } catch (ReflectiveOperationException | RuntimeException e) {
                submitBlock = null;
                LOG.error("[circle] submitBlock failed: {}", e.toString());
            } finally {
                pose.popPose();
            }
        }
    }

    private static void disableGeometry(String reason) {
        if (customGeometry != null) {
            LOG.error("[circle] custom geometry disabled: {}", reason);
        }
        customGeometry = null;
    }

    // Ищет в этой версии игры: submitCustomGeometry и тип отрисовки «позиция + цвет»; запасной вариант submitBlock
    private static void resolve(Object collector) {
        if (collectorClass == collector.getClass()) {
            return;
        }
        collectorClass = collector.getClass();
        customGeometry = null;
        customRendererType = null;
        customType = null;
        submitBlock = null;
        StringBuilder available = new StringBuilder();
        for (Method method : collector.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().startsWith("submit")) {
                available.append(method.getName()).append(types.length).append(' ');
            }
            try {
                if (method.getName().equals("submitCustomGeometry") && types.length == 3 && types[0] == PoseStack.class
                        && types[2].isInterface()) {
                    method.setAccessible(true);
                    customGeometry = method;
                    customRendererType = types[2];
                    customType = findRenderType(types[1]);
                } else if (method.getName().equals("submitBlock") && types.length == 5 && types[0] == PoseStack.class
                        && types[2] == int.class && types[3] == int.class && types[4] == int.class) {
                    method.setAccessible(true);
                    submitBlock = method;
                    resolveBarState(types[1]);
                }
            } catch (RuntimeException ignored) {
                // метод недоступен
            }
        }
        if (customType == null) {
            customGeometry = null;
        }
        resolved = true;
        LOG.info("[circle] resolved: submitCustomGeometry={}, renderType={}, submitBlock={}. Collector {} offers: {}",
                customGeometry != null, customType, submitBlock != null && barState != null,
                collector.getClass().getName(), available);
        if (customGeometry == null && (submitBlock == null || barState == null)) {
            LOG.error("[circle] NO geometry submission method found; check the 'offers' list above. Falling back to particles along the symbol geometry");
        }
    }

    // Тип отрисовки без освещения и текстур (позиция + цвет): известные имена в классах типов
    // отрисовки разных версий игры; если ничего нет, об этом пишется в лог
    private static Object findRenderType(Class<?> expected) {
        String[] classes = {"net.minecraft.client.renderer.rendertype.RenderTypes",
                "net.minecraft.client.renderer.RenderTypes", "net.minecraft.client.renderer.rendertype.RenderType",
                "net.minecraft.client.renderer.RenderType"};
        // lightning первым: этот тип поддерживают шейдерные моды (Iris). Для debugQuads в Iris нет программы
        // ("Missing program minecraft:pipeline/debug_quads"), и при включённых шейдерах геометрия не рисуется
        String[] preferred = {"lightning", "debugQuads", "debugFilledBox", "debugTriangleFan", "gui", "guiOverlay"};
        for (String name : preferred) {
            for (String className : classes) {
                Object value = tryRenderType(className, name, expected);
                if (value != null) {
                    LOG.info("[circle] render type: {}.{}()", className, name);
                    return value;
                }
            }
        }
        LOG.warn("[circle] none of the known render types {} exist in {}", java.util.Arrays.toString(preferred),
                java.util.Arrays.toString(classes));
        return null;
    }

    private static Object tryRenderType(String className, String method, Class<?> expected) {
        try {
            Object value = Class.forName(className).getMethod(method).invoke(null);
            return value != null && expected.isInstance(value) ? value : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static void resolveBarState(Class<?> stateType) {
        if (barState != null) {
            return;
        }
        for (String name : new String[]{"PURPLE_CONCRETE", "PURPLE_WOOL", "AMETHYST_BLOCK", "PURPUR_BLOCK"}) {
            try {
                Object block = Blocks.class.getField(name).get(null);
                if (block instanceof Block b && stateType.isInstance(b.defaultBlockState())) {
                    barState = b.defaultBlockState();
                    return;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // такого блока нет
            }
        }
    }
}
