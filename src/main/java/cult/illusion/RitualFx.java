package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/**
 * Визуальная композиция постоянного ритуала: масштабное проявление на всей области радиусом до 16 блоков вокруг
 * центра ритуала (именованный стенд для брони или сам инициатор, см. RitualAura). Центр - точка (r.x, r.y, r.z).
 * Геометрия и расписание аномалий вычисляются только из seed и возраста ритуала, поэтому все наблюдатели видят одну
 * композицию с одной фазой вращения; случайны только мелкие детали частиц.
 *
 * Элементы:
 *  1. огромная печать на земле: четыре концентрических кольца (разные скорости и направления вращения, пунктир,
 *     насечки), гексаграмма на третьем кольце, восемь лучей, импульсы вдоль линий, вспышки секторов;
 *  2. столб энергии из центра (до 14 блоков, ниже Глаза) с чёрным вихрем у основания и фонтаном наверху;
 *  3. гироскоп над центром: три наклонных вращающихся кольца и тёмное ядро с орбитальными точками;
 *  4. шесть колонн по краю области с огнями на вершинах и молниями к ядру гироскопа;
 *  5. фиолетовый туман над землёй, внешние лучи и потоки из всей области к центру;
 *  6. край области: барьер-стена из частиц, 12 жаровен, четыре арки-врата, ореол в небе, дуги молний между жаровнями;
 *     Безымянный присутствует единственным обликом: огромный Глаз в небе над столбом (открывается в конце запуска,
 *     иногда моргает, смотрит на клинок во время стадии, закрывается при завершении), без множества силуэтов;
 *     Ядро ритуала питает клинок идущей стадии J: молния от гироскопа к стенду-клинку (feedBlade);
 *  7. аномалии: ударные волны по земле, молнии с неба, ускорение вращения со вспышками колонн, сходящиеся потоки.
 * Начало (~3,5 с): слабая аномалия, печать, столб, колонны, гироскоп, молнии. Завершение (~3,5 с): вращение
 * ускоряется, всё сходится к центру, столб втягивается, гироскоп схлопывается, финальная вспышка и ударная волна.
 * Основная палитра частиц фиолетовая; белого и чёрного мало. Нагрузка масштабируется настройкой частиц, расстоянием
 * до наблюдателя и числом одновременных ритуалов.
 */
final class RitualFx {
    private RitualFx() {
    }

    private static final int BLACK = 0x07050C;
    private static final int DEEP = 0x2A0A4A;
    private static final int PURPLE = 0x7A2FD6;
    private static final int VIOLET = 0x9B3FE8;
    private static final int PALE = 0xB58CFF;
    private static final int WHITE = 0xF2E8FF;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double AREA_RADIUS = 16.0;
    private static final double BEAM_HEIGHT = 14.0;   // столб заканчивается ниже Глаза (он на высоте 20) и не проходит через него
    private static final double GYRO_HEIGHT = 10.0;
    private static final double PILLAR_RADIUS = 12.5;
    private static final double PILLAR_HEIGHT = 9.0;
    private static final int PILLARS = 6;

    private static ParticleOptions dust(int rgb, float size) {
        return PowerFx.dust(rgb, size);
    }

    /** Фиолетовая палитра (около 90% фиолетовых оттенков). */
    private static ParticleOptions glow(Random rng) {
        int k = rng.nextInt(20);
        if (k <= 8) {
            return dust(PURPLE, 0.9F);
        } else if (k <= 13) {
            return dust(VIOLET, 0.9F);
        } else if (k <= 16) {
            return dust(PALE, 0.85F);
        } else if (k == 17) {
            return dust(DEEP, 1.0F);
        } else if (k == 18) {
            return dust(WHITE, 0.7F);
        }
        return ParticleTypes.END_ROD;
    }

    private static ParticleOptions violet(Random rng, float size) {
        return dust(rng.nextInt(3) == 0 ? VIOLET : PURPLE, size);
    }

    /** Общий множитель нагрузки: настройка частиц, расстояние до наблюдателя, число одновременных ритуалов. */
    private static double density(Minecraft mc, RitualAura.Ritual r, int concurrent) {
        double d = 1.0;
        ParticleStatus status = mc.options.particles().get();
        if (status == ParticleStatus.DECREASED) {
            d = 0.55;
        } else if (status == ParticleStatus.MINIMAL) {
            d = 0.3;
        }
        if (r.viewerDistance > 36.0) {
            d *= 0.4;
        } else if (r.viewerDistance > 20.0) {
            d *= 0.7;
        }
        return d / Math.sqrt(Math.max(1, concurrent));
    }

    // Случайное округление: ожидаемое число частиц равно value
    private static int n(Random rng, double value) {
        return value <= 0.0 ? 0 : (int) (value + rng.nextDouble());
    }

    /** Высота земли в точке области: поверхность рядом с центром, иначе высота самого центра. */
    private static double groundY(Minecraft mc, RitualAura.Ritual r, double x, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (mc.level == null || !mc.level.hasChunkAt(bx, bz)) {
            return r.y;
        }
        double h = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        return Math.abs(h - r.y) > 6.0 ? r.y : h;
    }

    static void tick(Minecraft mc, RitualAura.Ritual r, int concurrent) {
        Random rng = r.fx;
        int age = r.age;
        double dens = density(mc, r, concurrent);
        double pulse = 0.85 + 0.15 * Math.sin(age * 0.02 + r.seed); // постепенное изменение интенсивности

        double seal;
        double beam;
        double pillars;
        double gyro;
        double extras;
        double growth;
        double scale = 1.0;      // масштаб конструкций (схлопывание)
        double collapse = 0.0;
        if (r.state == RitualAura.STARTING) {
            seal = RitualAura.smooth((age - 6) / 30.0);
            beam = RitualAura.smooth((age - 20) / 25.0);
            pillars = RitualAura.smooth((age - 30) / 25.0);
            gyro = RitualAura.smooth((age - 40) / 28.0);
            extras = RitualAura.smooth((age - 52) / 18.0);
            growth = RitualAura.smooth((age - 15) / 50.0);
        } else {
            seal = beam = pillars = gyro = extras = growth = 1.0;
            if (r.state == RitualAura.ENDING) {
                collapse = r.stateAge / (double) RitualAura.END_TICKS;
                scale = Math.pow(1.0 - collapse, 1.3);
            }
        }
        double boost = r.boost();
        double flare = Math.max(flare(r), stageEnergy(r)); // вспышка колонн и луча: аномалия ускорения или идущая стадия J

        if (r.state == RitualAura.STARTING && age <= 14) {
            weakAnomaly(mc, r, dens);
        }
        groundSeal(mc, r, rng, dens * pulse, seal, scale, boost, collapse);
        energyBeam(mc, r, rng, dens * pulse, beam, scale, collapse, flare);
        gyroscope(mc, r, rng, dens * pulse, gyro, scale, boost, collapse);
        pillars(mc, r, rng, dens * pulse, pillars, extras, scale, collapse, flare);
        area(mc, r, rng, dens, growth, scale, boost, collapse);
        feedBlade(mc, r, rng, dens * pulse, scale);
        edge(mc, r, rng, dens * pulse, growth, scale, collapse, flare, beam);
        eye(mc, r, rng, dens * pulse, collapse);
        anomalies(mc, r, rng, dens);
        if (r.state == RitualAura.ENDING && r.stateAge >= RitualAura.END_TICKS - 6) {
            finalFlash(mc, r, r.stateAge - (RitualAura.END_TICKS - 6));
        }
    }

    // Усиление во время аномалии ускорения вращения (0..1)
    private static double flare(RitualAura.Ritual r) {
        double best = 0.0;
        for (RitualAura.Anomaly a : r.anomalies) {
            if (a.type == 2) {
                double t = (r.age - a.start) / (double) a.life;
                if (t >= 0.0 && t <= 1.0) {
                    best = Math.max(best, Math.sin(t * Math.PI));
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- начало: слабая аномалия

    private static void weakAnomaly(Minecraft mc, RitualAura.Ritual r, double dens) {
        if (r.age == 0) {
            Fx.particle(mc, ParticleTypes.SONIC_BOOM, r.x, r.y + 1.0, r.z, 0.0, 0.0, 0.0);
        }
        double radius = 7.0 * r.age / 14.0;
        int points = n(r.fx, 22 * dens);
        for (int i = 0; i < points; i++) {
            double a = r.fx.nextDouble() * TWO_PI;
            double x = r.x + Math.cos(a) * radius;
            double z = r.z + Math.sin(a) * radius;
            Fx.particle(mc, dust(r.fx.nextBoolean() ? PURPLE : DEEP, 0.9F), x, groundY(mc, r, x, z) + 0.2, z, 0.0, 0.0, 0.0);
        }
    }

    // ---------------------------------------------------------------- 1. печать на земле

    private static final double[] SEAL_RADII = {3.5, 6.5, 10.0, 13.5};
    private static final double[] SEAL_SPEEDS = {0.030, -0.020, 0.012, -0.008};   // разные скорости и направления
    private static final double[] SEAL_BOOST = {1.0, -0.8, 0.6, -0.4};

    private static void groundSeal(Minecraft mc, RitualAura.Ritual r, Random rng, double dens, double f, double scale, double boost, double collapse) {
        if (f <= 0.0 || scale <= 0.02) {
            return;
        }
        double cx = r.x;
        double cz = r.z;
        double cy = r.y + 0.1;
        double[] spin = new double[4];
        for (int k = 0; k < 4; k++) {
            spin[k] = r.seed * 0.001 + SEAL_SPEEDS[k] * r.age + SEAL_BOOST[k] * boost;
        }
        for (int k = 0; k < 4; k++) {
            double formed = r.state == RitualAura.STARTING ? RitualAura.smooth((r.age - 6 - k * 7) / 24.0) : 1.0;
            double radius = SEAL_RADII[k] * scale;
            int samples = n(rng, (8 + 2 * k) * dens * f);
            for (int s = 0; s < samples; s++) {
                double a = rng.nextDouble() * TWO_PI * formed;
                if (k == 1 && ((int) (a / (TWO_PI / 48.0)) & 1) == 1) {
                    continue; // второе кольцо: пунктир
                }
                if (k == 3 && ((int) (a / (TWO_PI / 96.0)) & 1) == 1) {
                    continue; // внешнее кольцо: частый пунктир
                }
                double rad = radius;
                if (k == 2) {
                    // третье кольцо: насечки через каждые 15 градусов
                    double snapped = Math.round(a / (Math.PI / 12.0)) * (Math.PI / 12.0);
                    if (rng.nextInt(3) == 0) {
                        a = snapped;
                        rad = radius + (rng.nextDouble() - 0.5) * 0.9;
                    }
                }
                double ang = spin[k] + a;
                double x = cx + Math.cos(ang) * rad;
                double z = cz + Math.sin(ang) * rad;
                Fx.particle(mc, glow(rng), x, groundY(mc, r, x, z) + 0.12, z, 0.0, 0.0, 0.0);
            }
            // импульсы бегут вдоль кольца
            if (n(rng, 0.9 * dens * f) > 0) {
                double ang = spin[k] + rng.nextDouble() * TWO_PI * formed;
                double dir = SEAL_SPEEDS[k] > 0 ? 1.0 : -1.0;
                double x = cx + Math.cos(ang) * radius;
                double z = cz + Math.sin(ang) * radius;
                Fx.particle(mc, dust(PALE, 1.0F), x, groundY(mc, r, x, z) + 0.2, z, -Math.sin(ang) * 0.2 * dir, 0.0, Math.cos(ang) * 0.2 * dir);
            }
        }
        // гексаграмма на третьем кольце (вращается со вторым кольцом) и восемь лучей
        double outer = SEAL_RADII[2] * scale;
        double inner = SEAL_RADII[0] * scale;
        double edgeF = r.state == RitualAura.STARTING ? RitualAura.smooth((r.age - 24) / 24.0) : 1.0;
        int edgePoints = n(rng, 10 * dens * f * edgeF);
        for (int s = 0; s < edgePoints; s++) {
            int e = rng.nextInt(6);
            int tri = e % 2;
            int which = e / 2;
            double a0 = spin[1] + Math.toRadians(tri * 60.0 + which * 120.0);
            double a1 = spin[1] + Math.toRadians(tri * 60.0 + (which + 1) * 120.0);
            double t = rng.nextDouble();
            double x = cx + (Math.cos(a0) * (1.0 - t) + Math.cos(a1) * t) * outer;
            double z = cz + (Math.sin(a0) * (1.0 - t) + Math.sin(a1) * t) * outer;
            Fx.particle(mc, rng.nextInt(8) == 0 ? ParticleTypes.END_ROD : dust(rng.nextBoolean() ? PURPLE : PALE, 0.8F), x, groundY(mc, r, x, z) + 0.12, z, 0.0, 0.0, 0.0);
        }
        int spokes = n(rng, 7 * dens * f * edgeF);
        double ringOuter = SEAL_RADII[3] * scale;
        for (int s = 0; s < spokes; s++) {
            double a = spin[0] + rng.nextInt(8) * Math.PI / 4.0;
            double d = inner + (ringOuter - inner) * rng.nextDouble();
            double x = cx + Math.cos(a) * d;
            double z = cz + Math.sin(a) * d;
            Fx.particle(mc, dust(rng.nextInt(4) == 0 ? PALE : VIOLET, 0.8F), x, groundY(mc, r, x, z) + 0.12, z, 0.0, 0.0, 0.0);
        }
        // импульсы от центра к краю по лучам
        if (n(rng, 1.2 * dens * f * edgeF) > 0) {
            double a = spin[0] + rng.nextInt(8) * Math.PI / 4.0;
            Fx.particle(mc, dust(WHITE, 1.0F), cx + Math.cos(a) * inner, cy + 0.1, cz + Math.sin(a) * inner, Math.cos(a) * 0.35, 0.0, Math.sin(a) * 0.35);
        }
        // нерегулярные вспышки секторов
        if (rng.nextDouble() < 0.12 * f * dens) {
            double ang = spin[rng.nextInt(4)] + rng.nextDouble() * TWO_PI;
            double radius = SEAL_RADII[rng.nextInt(4)] * scale;
            double x = cx + Math.cos(ang) * radius;
            double z = cz + Math.sin(ang) * radius;
            double y = groundY(mc, r, x, z) + 0.2;
            Fx.burst(mc, dust(VIOLET, 1.2F), x, y, z, 6, 0.3, 0.05);
            Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, x, y, z, 4, 0.2, 0.15);
        }
        if (collapse > 0.0) {
            int inward = n(rng, 10 * dens);
            for (int s = 0; s < inward; s++) {
                double a = rng.nextDouble() * TWO_PI;
                double radius = SEAL_RADII[rng.nextInt(4)] * scale;
                double x = cx + Math.cos(a) * radius;
                double z = cz + Math.sin(a) * radius;
                Fx.particle(mc, rng.nextInt(6) == 0 ? ParticleTypes.END_ROD : dust(PURPLE, 0.9F), x, groundY(mc, r, x, z) + 0.2, z,
                        -Math.cos(a) * radius * 0.08, 0.03, -Math.sin(a) * radius * 0.08);
            }
        }
    }

    // ---------------------------------------------------------------- 2. столб энергии до неба

    private static void energyBeam(Minecraft mc, RitualAura.Ritual r, Random rng, double dens, double f, double scale, double collapse, double flare) {
        if (f <= 0.0) {
            return;
        }
        double height = BEAM_HEIGHT * f * (1.0 - collapse);
        if (height < 1.0) {
            return;
        }
        double strength = f * (1.0 + 0.8 * flare);
        int points = n(rng, 11 * dens * strength);
        for (int i = 0; i < points; i++) {
            double h = Math.pow(rng.nextDouble(), 0.8) * height;
            double a = rng.nextDouble() * TWO_PI;
            double w = 0.25 + 0.2 * rng.nextDouble() + 0.05 * (1.0 - h / BEAM_HEIGHT) * 3.0;
            Fx.particle(mc, violet(rng, 1.2F), r.x + Math.cos(a) * w, r.y + h, r.z + Math.sin(a) * w, -Math.sin(a) * 0.03, 0.15, Math.cos(a) * 0.03);
        }
        if (n(rng, 1.2 * dens * strength) > 0) {
            double h = rng.nextDouble() * height;
            Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.END_ROD : ParticleTypes.WITCH, r.x + (rng.nextDouble() - 0.5) * 0.6, r.y + h, r.z + (rng.nextDouble() - 0.5) * 0.6, 0.0, 0.2, 0.0);
        }
        // Фонтан на вершине столба: энергия не уходит выше, а расплёскивается в стороны и падает вниз
        int fountain = n(rng, 4 * dens * strength);
        for (int i = 0; i < fountain; i++) {
            double an = rng.nextDouble() * TWO_PI;
            Fx.particle(mc, violet(rng, 1.1F), r.x, r.y + height, r.z, Math.cos(an) * 0.18, 0.04, Math.sin(an) * 0.18);
        }
        // чёрный вихрь у основания, поднимается вдоль столба
        int smoke = n(rng, 2.0 * dens * f);
        for (int i = 0; i < smoke; i++) {
            double a = rng.nextDouble() * TWO_PI;
            double rad = 1.2 + rng.nextDouble() * 0.8;
            Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE, r.x + Math.cos(a) * rad, r.y + 0.2, r.z + Math.sin(a) * rad,
                    -Math.sin(a) * 0.06, 0.08, Math.cos(a) * 0.06);
        }
    }

    // ---------------------------------------------------------------- 3. гироскоп над центром

    private static final double[] GYRO_RADII = {7.5, 6.0, 4.5};
    private static final double[] GYRO_TILTS = {0.0, 1.05, 2.1};
    private static final double[] GYRO_SPEEDS = {0.021, -0.017, 0.028};

    private static void gyroscope(Minecraft mc, RitualAura.Ritual r, Random rng, double dens, double f, double scale, double boost, double collapse) {
        if (f <= 0.0 || scale <= 0.02) {
            return;
        }
        double gx = r.x;
        double gy = r.y + GYRO_HEIGHT * (0.6 + 0.4 * f);
        double gz = r.z;
        for (int k = 0; k < 3; k++) {
            double s = r.seed * 0.001 + GYRO_SPEEDS[k] * r.age + boost * (k == 1 ? -0.9 : 0.9);
            double tilt = GYRO_TILTS[k] + 0.25 * Math.sin(r.age * 0.01 + k);
            double ux = Math.cos(s);
            double uz = Math.sin(s);
            double hx = -Math.sin(s);
            double hz = Math.cos(s);
            double cosT = Math.cos(tilt);
            double sinT = Math.sin(tilt);
            double radius = GYRO_RADII[k] * scale * (0.5 + 0.5 * f);
            int samples = n(rng, 12 * dens * f);
            for (int i = 0; i < samples; i++) {
                double a = rng.nextDouble() * TWO_PI;
                if (k == 1 && ((int) (a / (TWO_PI / 36.0)) & 1) == 1) {
                    continue; // среднее кольцо: пунктир
                }
                double cu = Math.cos(a);
                double sv = Math.sin(a);
                // плоскость кольца: горизонтальная ось u и наклонная ось v = cos(tilt)*вверх + sin(tilt)*h
                double px = gx + ux * cu * radius + hx * sinT * sv * radius;
                double py = gy + cosT * sv * radius;
                double pz = gz + uz * cu * radius + hz * sinT * sv * radius;
                Fx.particle(mc, glow(rng), px, py, pz, 0.0, 0.0, 0.0);
            }
            // яркие орбитальные точки
            int orbit = n(rng, 1.5 * dens * f);
            for (int i = 0; i < orbit; i++) {
                double a = s * 2.0 + i * Math.PI + k * 2.0;
                double cu = Math.cos(a);
                double sv = Math.sin(a);
                Fx.particle(mc, dust(WHITE, 1.1F), gx + ux * cu * radius + hx * sinT * sv * radius, gy + cosT * sv * radius, gz + uz * cu * radius + hz * sinT * sv * radius, 0.0, 0.0, 0.0);
            }
        }
        // Руны (глифы) поднимаются от земли по всей области к гироскопу: мистический восходящий поток
        int glyphs = n(rng, 4 * dens * f);
        for (int i = 0; i < glyphs; i++) {
            double an = rng.nextDouble() * TWO_PI;
            double d = 3.0 + rng.nextDouble() * 8.0;
            Fx.particle(mc, ParticleTypes.ENCHANT, gx, gy, gz, Math.cos(an) * d, -(GYRO_HEIGHT * (0.6 + 0.4 * f)) + 0.5, Math.sin(an) * d);
        }
        if (n(rng, 1.5 * dens * f) > 0) {
            Fx.particle(mc, ParticleTypes.REVERSE_PORTAL, gx, gy, gz, (rng.nextDouble() - 0.5) * 0.4, (rng.nextDouble() - 0.5) * 0.4, (rng.nextDouble() - 0.5) * 0.4);
        }
        // тёмное ядро в центре гироскопа
        int core = n(rng, 5 * dens * f);
        for (int i = 0; i < core; i++) {
            double a = rng.nextDouble() * TWO_PI;
            double b = rng.nextDouble() * Math.PI - Math.PI / 2.0;
            double rad = 1.3 * scale * rng.nextDouble();
            Fx.particle(mc, rng.nextInt(3) == 0 ? dust(DEEP, 1.4F) : dust(BLACK, 1.5F), gx + Math.cos(a) * Math.cos(b) * rad, gy + Math.sin(b) * rad, gz + Math.sin(a) * Math.cos(b) * rad, 0.0, 0.0, 0.0);
        }
        if (n(rng, 0.5 * dens * f) > 0) {
            Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, gx, gy, gz, 4, 0.8, 0.2);
        }
    }

    // ---------------------------------------------------------------- 4. колонны по краю области

    private static void pillars(Minecraft mc, RitualAura.Ritual r, Random rng, double dens, double f, double extras, double scale, double collapse, double flare) {
        if (f <= 0.0 || scale <= 0.02) {
            return;
        }
        double gy = r.y + GYRO_HEIGHT * (0.6 + 0.4 * RitualAura.smooth((r.age - 40) / 28.0));
        double rot = r.seed * 0.001 + r.age * 0.002;
        for (int k = 0; k < PILLARS; k++) {
            double a = rot + k * TWO_PI / PILLARS;
            double x = r.x + Math.cos(a) * PILLAR_RADIUS * (0.5 + 0.5 * f) * scale;
            double z = r.z + Math.sin(a) * PILLAR_RADIUS * (0.5 + 0.5 * f) * scale;
            double base = groundY(mc, r, x, z);
            double height = PILLAR_HEIGHT * f * scale * (1.0 + 0.25 * flare);
            // колонна: спираль частиц вдоль всей высоты
            int points = n(rng, 3.0 * dens * f * (1.0 + 1.5 * flare));
            for (int i = 0; i < points; i++) {
                double h = rng.nextDouble() * height;
                double sa = h * 1.2 + r.age * 0.15 + k;
                Fx.particle(mc, violet(rng, 1.1F), x + Math.cos(sa) * 0.45, base + h, z + Math.sin(sa) * 0.45, 0.0, 0.08, 0.0);
            }
            // огонь на вершине
            if (n(rng, 1.2 * dens * f) > 0) {
                Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.END_ROD : ParticleTypes.WITCH, x, base + height, z, (rng.nextDouble() - 0.5) * 0.1, 0.06, (rng.nextDouble() - 0.5) * 0.1);
            }
            // молнии от вершины колонны к ядру гироскопа
            if (extras > 0.0 && collapse < 0.7) {
                int bolts = n(rng, 2.2 * dens * extras * (1.0 + flare));
                double tx = r.x;
                double tz = r.z;
                for (int i = 0; i < bolts; i++) {
                    double t = rng.nextDouble();
                    double lx = x + (tx - x) * t + (rng.nextDouble() - 0.5) * 0.9 * Math.sin(t * Math.PI);
                    double ly = base + height + (gy - base - height) * t + (rng.nextDouble() - 0.5) * 0.9 * Math.sin(t * Math.PI);
                    double lz = z + (tz - z) * t + (rng.nextDouble() - 0.5) * 0.9 * Math.sin(t * Math.PI);
                    Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.ELECTRIC_SPARK : dust(VIOLET, 0.9F), lx, ly, lz, 0.0, 0.0, 0.0);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 5. область: туман и потоки

    private static void area(Minecraft mc, RitualAura.Ritual r, Random rng, double dens, double growth, double scale, double boost, double collapse) {
        double shrink = 1.0 - collapse;
        double radiusA = AREA_RADIUS * growth * shrink;
        if (radiusA < 1.0) {
            return;
        }
        double f = growth * (0.4 + 0.6 * shrink);

        // Туман над землёй: тёмные и яркие фиолетовые частицы кружатся вокруг центра
        int mist = n(rng, 20 * dens * f);
        for (int i = 0; i < mist; i++) {
            double a = rng.nextDouble() * TWO_PI;
            double d = radiusA * Math.sqrt(rng.nextDouble());
            double x = r.x + Math.cos(a) * d;
            double z = r.z + Math.sin(a) * d;
            double y = groundY(mc, r, x, z) + 0.15 + rng.nextDouble() * 0.9;
            int k = rng.nextInt(5);
            double dir = collapse > 0.0 ? -1.0 : 1.0;
            Fx.particle(mc, dust(k < 2 ? PURPLE : k < 4 ? DEEP : VIOLET, 1.4F), x, y, z,
                    -Math.sin(a) * 0.04 * dir - (collapse > 0.0 ? Math.cos(a) * 0.08 : 0.0), 0.005,
                    Math.cos(a) * 0.04 * dir - (collapse > 0.0 ? Math.sin(a) * 0.08 : 0.0));
        }
        // Потоки из всей области к центру: стартуют на краю, приходят к основанию столба
        int streams = n(rng, 4.0 * dens * growth);
        for (int i = 0; i < streams; i++) {
            double a = rng.nextDouble() * TWO_PI;
            double d = radiusA * (0.4 + 0.6 * rng.nextDouble());
            Fx.particle(mc, ParticleTypes.PORTAL, r.x, r.y + 1.0, r.z, Math.cos(a) * d, (rng.nextDouble() - 0.4) * 3.0, Math.sin(a) * d);
        }
        // Частицы поднимаются по всей области, словно пространство вытягивают к небу
        int motes = n(rng, 6 * dens * f);
        for (int i = 0; i < motes; i++) {
            double a = rng.nextDouble() * TWO_PI;
            double d = radiusA * Math.sqrt(rng.nextDouble());
            double x = r.x + Math.cos(a) * d;
            double z = r.z + Math.sin(a) * d;
            Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.WITCH : dust(VIOLET, 1.0F), x, groundY(mc, r, x, z) + 0.2, z, 0.0, 0.1 + rng.nextDouble() * 0.1, 0.0);
        }
    }

    // ---------------------------------------------------------------- край области

    private static final double EDGE_RADIUS = 15.5;
    private static final int BRAZIERS = 12;

    private static void edge(Minecraft mc, RitualAura.Ritual r, Random rng, double dens, double growth, double scale, double collapse, double flare, double beam) {
        double f = growth * scale;
        if (f <= 0.02) {
            return;
        }
        double radius = EDGE_RADIUS * (0.4 + 0.6 * f);
        double rot = r.seed * 0.001 + r.age * 0.003 + 0.2 * r.boost();

        // Барьер: вертикальная стена из сегментов, по ней бегут волны высоты; сегменты медленно вращаются
        int wall = n(rng, 11 * dens * f);
        for (int i = 0; i < wall; i++) {
            double a = rng.nextDouble() * TWO_PI;
            if (((int) ((a + rot) / (TWO_PI / 48.0)) & 1) == 1) {
                continue;
            }
            double wave = 0.5 + 0.5 * Math.sin(a * 6.0 + r.age * 0.1);
            double h = rng.nextDouble() * (3.0 + 5.0 * wave) * f;
            double x = r.x + Math.cos(a) * radius;
            double z = r.z + Math.sin(a) * radius;
            Fx.particle(mc, violet(rng, 1.1F), x, groundY(mc, r, x, z) + 0.1 + h, z, -Math.cos(a) * 0.01, 0.05, -Math.sin(a) * 0.01);
        }

        // Двенадцать жаровен по периметру: фиолетовое пламя и угли
        for (int k = 0; k < BRAZIERS; k++) {
            double a = r.seed * 0.0005 + k * TWO_PI / BRAZIERS;
            double x = r.x + Math.cos(a) * radius;
            double z = r.z + Math.sin(a) * radius;
            if (n(rng, 1.0 * dens * f) > 0) {
                double base = groundY(mc, r, x, z);
                Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.WITCH : dust(rng.nextBoolean() ? VIOLET : PURPLE, 1.2F),
                        x + (rng.nextDouble() - 0.5) * 0.5, base + 0.3, z + (rng.nextDouble() - 0.5) * 0.5, 0.0, 0.1 + rng.nextDouble() * 0.08 + 0.05 * flare, 0.0);
            }
        }

        // Четыре арки-врата на краю области, обращены к центру
        for (int k = 0; k < 4; k++) {
            double a = r.seed * 0.001 + k * Math.PI / 2.0 + Math.PI / 4.0;
            double ex = r.x + Math.cos(a) * radius;
            double ez = r.z + Math.sin(a) * radius;
            double base = groundY(mc, r, ex, ez);
            double tx = -Math.sin(a);
            double tz = Math.cos(a);
            int arch = n(rng, 4 * dens * f);
            for (int i = 0; i < arch; i++) {
                double b = rng.nextDouble() * Math.PI;
                double u = Math.cos(b) * 3.0 * f;
                double v = Math.sin(b) * 3.2 * f;
                Fx.particle(mc, glow(rng), ex + tx * u, base + 0.2 + v, ez + tz * u, 0.0, 0.0, 0.0);
            }
            // завеса внутри арки: тёмная вуаль с редкими бликами
            if (n(rng, 1.5 * dens * f) > 0) {
                double u = (rng.nextDouble() * 2.0 - 1.0) * 2.6 * f;
                double v = rng.nextDouble() * 2.6 * f;
                Fx.particle(mc, rng.nextInt(4) == 0 ? dust(PALE, 0.9F) : dust(DEEP, 1.4F), ex + tx * u, base + 0.2 + v, ez + tz * u, -Math.cos(a) * 0.02, 0.0, -Math.sin(a) * 0.02);
            }
        }

        // Ореол вокруг Глаза: огромное пунктирное кольцо на высоте глаза
        if (beam > 0.0) {
            double haloR = 12.5 * f;
            double haloSpin = r.seed * 0.001 + 0.012 * r.age + 0.5 * r.boost();
            int halo = n(rng, 8 * dens * beam * f);
            for (int i = 0; i < halo; i++) {
                double a = rng.nextDouble() * TWO_PI;
                if (((int) (a / (TWO_PI / 60.0)) & 1) == 1) {
                    continue;
                }
                Fx.particle(mc, glow(rng), r.x + Math.cos(haloSpin + a) * haloR, r.y + EYE_HEIGHT * beam, r.z + Math.sin(haloSpin + a) * haloR, 0.0, 0.0, 0.0);
            }
        }

        // Дуги молний между соседними жаровнями
        if (n(rng, 0.8 * dens * f) > 0 && collapse < 0.7) {
            int k = rng.nextInt(BRAZIERS);
            double a0 = r.seed * 0.0005 + k * TWO_PI / BRAZIERS;
            double a1 = a0 + TWO_PI / BRAZIERS;
            for (int s = 0; s <= 8; s++) {
                double t = s / 8.0;
                double a = a0 + (a1 - a0) * t;
                double x = r.x + Math.cos(a) * radius + (rng.nextDouble() - 0.5) * 0.6;
                double z = r.z + Math.sin(a) * radius + (rng.nextDouble() - 0.5) * 0.6;
                Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.ELECTRIC_SPARK : dust(VIOLET, 0.9F), x, groundY(mc, r, x, z) + 2.4 + Math.sin(t * Math.PI) * 1.5 + (rng.nextDouble() - 0.5) * 0.5, z, 0.0, 0.0, 0.0);
            }
        }
    }

    // ---------------------------------------------------------------- подпитка клинка

    /** Пока идёт стадия J со стендом-клинком, ядро ритуала бьёт к клинку молнией: стадия вписана в структуру ритуала. */
    private static void feedBlade(Minecraft mc, RitualAura.Ritual r, Random rng, double dens, double scale) {
        BladeCeremony.Run run = BladeCeremony.runOf(r.initiator);
        if (run == null || run.stand == null || run.age >= 292 || scale <= 0.1) {
            return;
        }
        double e = stageEnergy(r);
        double gx = r.x;
        double gy = r.y + GYRO_HEIGHT * 0.9;
        double gz = r.z;
        double bx = run.x;
        double by = run.y + 1.6;
        double bz = run.z;
        double dx = bx - gx;
        double dy = by - gy;
        double dz = bz - gz;
        if (dx * dx + dz * dz < 4.0) {
            return;
        }
        int bolts = n(rng, 9 * dens * (0.3 + e));
        for (int i = 0; i < bolts; i++) {
            double t = rng.nextDouble();
            double jitter = 0.8 * Math.sin(t * Math.PI);
            Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.ELECTRIC_SPARK : dust(rng.nextBoolean() ? VIOLET : PURPLE, 1.1F),
                    gx + dx * t + (rng.nextDouble() - 0.5) * jitter, gy + dy * t + (rng.nextDouble() - 0.5) * jitter, gz + dz * t + (rng.nextDouble() - 0.5) * jitter, 0.0, 0.0, 0.0);
        }
    }

    // ---------------------------------------------------------------- Глаз Безымянного

    /** Энергия стадии J инициатора: ритуал отзывается на передачу силы (0..1; ярче к финальной вспышке стадии). */
    private static double stageEnergy(RitualAura.Ritual r) {
        BladeCeremony.Run run = BladeCeremony.runOf(r.initiator);
        if (run == null) {
            return 0.0;
        }
        if (run.age < BladeCeremony.FINAL_START) {
            return 0.45 * run.intensity();
        }
        return run.age < 290 ? 0.8 : Math.max(0.0, 1.0 - (run.age - 290) / 10.0);
    }

    private static final double EYE_HEIGHT = 20.0;
    private static final double EYE_WIDTH = 9.0;

    /**
     * Единственный облик Безымянного в ритуале: огромный Глаз в небе над столбом энергии. Контур век, радужка и
     * вертикальный зрачок лежат в горизонтальной плоскости, поэтому снизу виден как глаз. Он открывается в последние
     * секунды запуска, иногда моргает (по seed и возрасту, одинаково у всех наблюдателей), во время стадии J поворачивает
     * радужку к клинку и расширяет зрачок, при завершении закрывается.
     */
    private static void eye(Minecraft mc, RitualAura.Ritual r, Random rng, double dens, double collapse) {
        double open;
        if (r.state == RitualAura.STARTING) {
            open = RitualAura.smooth((r.age - 45) / 25.0);
        } else if (r.state == RitualAura.ACTIVE) {
            open = 1.0;
        } else {
            open = 1.0 - RitualAura.smooth((r.stateAge - 8) / 30.0);
        }
        double cycle = (r.age + r.seed % 97) % 190.0;
        if (cycle < 8.0) {
            open *= Math.abs(cycle - 4.0) / 4.0; // моргание
        }
        if (open <= 0.03) {
            return;
        }
        double cx = r.x;
        double cz = r.z;
        double cy = r.y + EYE_HEIGHT;
        double axis = r.seed * 0.001;
        double ca = Math.cos(axis);
        double sa = Math.sin(axis);
        double energy = stageEnergy(r);
        double half = 3.8 * open * (1.0 - 0.15 * energy);

        // Верхнее и нижнее веко
        int lids = n(rng, 16 * dens * open);
        for (int i = 0; i < lids; i++) {
            double x = (rng.nextDouble() * 2.0 - 1.0) * EYE_WIDTH;
            double lid = half * (1.0 - (x / EYE_WIDTH) * (x / EYE_WIDTH));
            double z = rng.nextBoolean() ? lid : -lid;
            Fx.particle(mc, rng.nextInt(4) == 0 ? dust(PALE, 1.1F) : dust(VIOLET, 1.3F), cx + x * ca - z * sa, cy, cz + x * sa + z * ca, 0.0, 0.0, 0.0);
        }
        // Взгляд: к клинку идущей стадии, иначе медленно блуждает
        double gx;
        double gz;
        BladeCeremony.Run run = BladeCeremony.runOf(r.initiator);
        if (run != null && run.stand != null) {
            double dx = run.x - cx;
            double dz = run.z - cz;
            double len = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
            gx = dx / len * 2.2;
            gz = dz / len * 2.2;
        } else {
            gx = Math.cos(r.age * 0.011 + r.seed) * 1.6;
            gz = Math.sin(r.age * 0.017 + r.seed) * 0.9;
        }
        // Радужка и зрачок (вертикальная щель, шире во время стадии)
        double iris = 2.6 * (0.7 + 0.3 * open);
        int ring = n(rng, 11 * dens * open);
        for (int i = 0; i < ring; i++) {
            double a = rng.nextDouble() * TWO_PI;
            double x = gx + Math.cos(a) * iris;
            double z = gz + Math.sin(a) * iris * Math.min(1.0, half / 2.6);
            Fx.particle(mc, rng.nextInt(3) == 0 ? dust(PALE, 1.0F) : dust(PURPLE, 1.2F), cx + x * ca - z * sa, cy, cz + x * sa + z * ca, 0.0, 0.0, 0.0);
        }
        int pupil = n(rng, 8 * dens * open);
        double slitW = 0.35 + 0.35 * energy;
        for (int i = 0; i < pupil; i++) {
            double a = rng.nextDouble() * TWO_PI;
            double d = Math.sqrt(rng.nextDouble());
            double x = gx + Math.cos(a) * d * slitW;
            double z = gz + Math.sin(a) * d * 1.7 * Math.min(1.0, half / 3.0);
            int shade = rng.nextInt(20);   // зрачок фиолетовый: насыщенный пурпур с яркой сердцевиной и тёмным краем
            Fx.particle(mc, shade < 11 ? dust(PURPLE, 1.6F) : shade < 17 ? dust(VIOLET, 1.5F) : dust(DEEP, 1.6F), cx + x * ca - z * sa, cy, cz + x * sa + z * ca, 0.0, 0.0, 0.0);
        }
        // Редкий всполох на радужке
        if (rng.nextDouble() < 0.1 * dens * open) {
            Fx.particle(mc, ParticleTypes.ELECTRIC_SPARK, cx + gx * ca - gz * sa, cy, cz + gx * sa + gz * ca, 0.0, -0.05, 0.0);
        }
        // Закрывающийся глаз роняет частицы вниз
        if (collapse > 0.0 && n(rng, 3 * dens) > 0) {
            double x = (rng.nextDouble() * 2.0 - 1.0) * EYE_WIDTH * 0.6;
            Fx.particle(mc, dust(VIOLET, 1.2F), cx + x * ca, cy, cz + x * sa, 0.0, -0.25, 0.0);
        }
    }

    // ---------------------------------------------------------------- 6. пространственные аномалии

    private static void anomalies(Minecraft mc, RitualAura.Ritual r, Random rng, double dens) {
        double cx = r.x;
        double cz = r.z;
        for (RitualAura.Anomaly a : r.anomalies) {
            int t = r.age - a.start;
            if (t < 0 || t > a.life) {
                continue;
            }
            if (a.type == 0) {
                // ударные волны расходятся по земле от центра до края области
                if (t == 0) {
                    Fx.particle(mc, ParticleTypes.SONIC_BOOM, cx, r.y + 1.0, cz, 0.0, 0.0, 0.0);
                    Fx.burst(mc, dust(WHITE, 1.2F), cx, r.y + 0.3, cz, 10, 0.4, 0.3);
                }
                for (int j = 0; j < 3; j++) {
                    int tj = t - j * 7;
                    if (tj < 0 || tj > 24) {
                        continue;
                    }
                    double radius = AREA_RADIUS * tj / 24.0;
                    int points = n(rng, 34 * dens);
                    for (int s = 0; s < points; s++) {
                        double ang = rng.nextDouble() * TWO_PI;
                        double x = cx + Math.cos(ang) * radius;
                        double z = cz + Math.sin(ang) * radius;
                        Fx.particle(mc, dust(rng.nextInt(5) == 0 ? PALE : (rng.nextBoolean() ? PURPLE : VIOLET), 1.0F), x, groundY(mc, r, x, z) + 0.2 + j * 0.15, z, 0.0, 0.03, 0.0);
                    }
                }
            } else if (a.type == 1) {
                // молния с неба в точку области
                double sx = cx + Math.cos(a.angle) * a.distance;
                double sz = cz + Math.sin(a.angle) * a.distance;
                double ground = groundY(mc, r, sx, sz);
                if (t == 0) {
                    Fx.particle(mc, ParticleTypes.SONIC_BOOM, sx, ground + 1.0, sz, 0.0, 0.0, 0.0);
                    Fx.burst(mc, dust(VIOLET, 1.4F), sx, ground + 0.4, sz, n(rng, 14 * dens), 0.5, 0.35);
                    Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, sx, ground + 0.4, sz, n(rng, 10 * dens), 0.4, 0.4);
                }
                if (t < 10) {
                    int points = n(rng, 14 * dens);
                    for (int s = 0; s < points; s++) {
                        double h = rng.nextDouble() * 24.0;
                        double wobble = (rng.nextDouble() - 0.5) * 0.7 * (1.0 - h / 30.0);
                        Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.ELECTRIC_SPARK : dust(rng.nextInt(4) == 0 ? WHITE : VIOLET, 1.0F),
                                sx + wobble, ground + h, sz + (rng.nextDouble() - 0.5) * 0.7 * (1.0 - h / 30.0), 0.0, 0.0, 0.0);
                    }
                }
            } else if (a.type == 2) {
                // ускорение вращения (само вращение считается в Ritual.boost, колонны и столб усиливаются): искры по кольцам
                if (t % 4 == 0) {
                    double ang = rng.nextDouble() * TWO_PI;
                    double radius = SEAL_RADII[rng.nextInt(4)];
                    double x = cx + Math.cos(ang) * radius;
                    double z = cz + Math.sin(ang) * radius;
                    Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, x, groundY(mc, r, x, z) + 0.3, z, n(rng, 6 * dens), 0.3, 0.2);
                }
            } else {
                // потоки со всей области одновременно движутся к центру
                for (int j = 0; j < 6; j++) {
                    double ang = a.angle + j * TWO_PI / 6.0;
                    double dist = 10.0 + 1.0 * j;
                    int count = n(rng, 2.5 * dens);
                    for (int s = 0; s < count; s++) {
                        Fx.particle(mc, ParticleTypes.PORTAL, cx, r.y + 1.0, cz, Math.cos(ang) * dist, (rng.nextDouble() - 0.3) * 1.5, Math.sin(ang) * dist);
                        double x = cx + Math.cos(ang) * dist;
                        double z = cz + Math.sin(ang) * dist;
                        Fx.particle(mc, violet(rng, 1.1F), x, groundY(mc, r, x, z) + 0.6, z, -Math.cos(ang) * dist * 0.06, 0.02, -Math.sin(ang) * dist * 0.06);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- завершение: финальная вспышка и ударная волна

    private static void finalFlash(Minecraft mc, RitualAura.Ritual r, int step) {
        double cx = r.x;
        double cy = r.y + 1.0;
        double cz = r.z;
        if (step == 0) {
            double gy = r.y + GYRO_HEIGHT * 0.6;
            Fx.burst(mc, dust(PURPLE, 1.5F), cx, cy, cz, 60, 0.4, 0.6);
            Fx.burst(mc, dust(VIOLET, 1.4F), cx, cy, cz, 40, 0.4, 0.5);
            Fx.burst(mc, ParticleTypes.END_ROD, cx, cy, cz, 14, 0.4, 0.5);
            Fx.burst(mc, dust(WHITE, 1.3F), cx, cy, cz, 10, 0.3, 0.3);
            Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, cx, cy, cz, 24, 0.3, 0.7);
            Fx.burst(mc, dust(VIOLET, 1.5F), cx, gy, cz, 40, 0.4, 0.6);
            Fx.particle(mc, ParticleTypes.SONIC_BOOM, cx, cy, cz, 0.0, 0.0, 0.0);
            Fx.particle(mc, ParticleTypes.SONIC_BOOM, cx, gy, cz, 0.0, 0.0, 0.0);
        }
        // ударная волна по земле до края области
        double radius = AREA_RADIUS * (step + 1) / 6.0;
        int points = n(r.fx, 42 * (density(mc, r, 1)));
        for (int i = 0; i < points; i++) {
            double a = r.fx.nextDouble() * TWO_PI;
            double x = cx + Math.cos(a) * radius;
            double z = cz + Math.sin(a) * radius;
            Fx.particle(mc, dust(r.fx.nextInt(5) == 0 ? PALE : VIOLET, 1.3F), x, groundY(mc, r, x, z) + 0.25, z, Math.cos(a) * 0.1, 0.05, Math.sin(a) * 0.1);
        }
    }
}
