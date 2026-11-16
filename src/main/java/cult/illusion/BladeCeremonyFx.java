package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/**
 * Частицы церемонии передачи силы. Только клиент. Все стадии длятся ровно 300 тиков: нарастание, финальная фаза с
 * 250-го тика, схлопывание в точку, выраженная финальная вспышка около 290-го и затухание до 300-го.
 * Центр эффектов - клинок: стенд с именем стадии («Тень», «Шепот», «Печать») или, если такого нет, сам инициатор.
 * Когда центр - стенд, к клинку идут потоки силы от инициатора и от центра ритуала («Ритуал»), если он идёт.
 * Экранных наложений нет (виньетка рисуется отдельно по краям экрана), тряски камеры нет.
 *
 * Стадия 1, Тень божья - поглощение света и скрытность: чёрное затмение над клинком с фиолетовой короной, тени-щупальца
 *   с земли тянутся в клинок, потоки дыма и портала сходятся, три силуэта Безымянного смотрят на клинок, земля темнеет.
 * Стадия 2, Шепот божий - голос и разум: потоки рун-глифов (ENCHANT) вливаются в клинок, эхо-волны расходятся от него и
 *   пересекаются, силуэты шепчут клинку, пространство глитчит; в финале все волны и руны сходятся в клинок.
 * Стадия 3, Печать божья - молчание и оковы (клинок, который запечатывает голос и разум): круги печати на земле с лучами
 *   и гексаграммой, шесть цепей с неба одна за другой опускаются спиралью и обвивают клинок, над клинком складывается
 *   вертикальный знак-печать (кольца, гексаграмма, замочная скважина), повёрнутый к инициатору; в финале всё сходится
 *   в одну точку, знак падает на клинок и ставит оттиск, по земле идёт волна тишины.
 */
final class BladeCeremonyFx {
    private BladeCeremonyFx() {
    }

    // Расписание стадии 3 (тики): кольца 0-150, соединения 150-190, грани фигуры 190-250 (используется и звуком)
    static final int RING_LEN = 50;
    static final int SPOKE_START = 150;
    static final int SPOKE_LEN = 40;
    static final int EDGE_START = 190;
    static final int EDGE_STEP = 8;
    static final int EDGE_LEN = 20;

    private static final int COLLAPSE_SHADOW = 282;
    private static final int POP_SHADOW = 290;
    private static final int COLLAPSE_SEAL = 285;
    private static final int POP_WHISPER = 290;
    private static final int CHAINS = 6;
    static final int CHAIN_START = 120;   // цепи опускаются одна за другой: 120-220 (используется и звуком)
    static final int CHAIN_STEP = 14;
    static final int CHAIN_DROP = 30;
    private static final double CHAIN_TOP = 14.0;
    private static final int SIGIL_START = 160;     // знак-печать формируется над клинком 160-250
    private static final int SIGIL_FORMED = 250;
    private static final int STAMP = 285;           // знак падает на клинок и ставит оттиск
    private static final double SIGIL_RADIUS = 3.2;
    private static final double SIGIL_HEIGHT = 5.0;
    private static final double TWO_PI = Math.PI * 2.0;

    private static final int PURPLE = 0x7A2FD6;
    private static final int VIOLET = 0x9B3FE8;
    private static final int DEEP_PURPLE = 0x4B1A8A;
    private static final int PALE_PURPLE = 0xB58CFF;
    private static final int WHITE = 0xF2E8FF;
    private static final int BLACK = 0x07050C;

    /** Кольцо-волна стадии 2: центр смещён от клинка, плоскость задаёт нормаль. */
    static final class Wave {
        double ox;
        double oy;
        double oz;
        double nx;
        double ny;
        double nz;
        int born;
        int life;
        double maxRadius;
    }

    private static ParticleOptions dust(int rgb, float size) {
        return PowerFx.dust(rgb, size);
    }

    private static int n(Random rng, double value) {
        return value <= 0.0 ? 0 : (int) (value + rng.nextDouble());
    }

    private static double density(Minecraft mc, BladeCeremony.Run run) {
        double d = 1.0;
        ParticleStatus status = mc.options.particles().get();
        if (status == ParticleStatus.DECREASED) {
            d = 0.55;
        } else if (status == ParticleStatus.MINIMAL) {
            d = 0.3;
        }
        if (run.viewerDistance > 30.0) {
            d *= 0.5;
        } else if (run.viewerDistance > 16.0) {
            d *= 0.75;
        }
        return d;
    }

    private static double groundY(Minecraft mc, BladeCeremony.Run run, double x, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (mc.level == null || !mc.level.hasChunkAt(bx, bz)) {
            return run.y;
        }
        double h = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        return Math.abs(h - run.y) > 5.0 ? run.y : h;
    }

    static void start(Minecraft mc, BladeCeremony.Run run) {
        run.nextEvent = 20 + run.rng.nextInt(20);
        run.waves.clear();
    }

    static void tick(Minecraft mc, BladeCeremony.Run run) {
        double dens = density(mc, run);
        if (run.stage == 1) {
            shadow(mc, run, dens);
        } else if (run.stage == 2) {
            whisper(mc, run, dens);
        } else {
            seal(mc, run, dens);
        }
        if (run.stand != null) {
            transfer(mc, run, dens);
        }
        figure(mc, run, dens);
        if (run.stand != null) {
            bladeBeacon(mc, run, dens);
        }
    }

    // ---------------------------------------------------------------- клинок хорошо виден и связан с ритуалом

    /**
     * Эффект клинка на стенде: высокий столб цвета стадии над клинком, нимб над ним и, если рядом идёт ритуал, проводник
     * энергии от центра ритуала (по земле и дугой от гироскопа). Так передача силы читается издалека и встроена в структуру
     * ритуала: ядро ритуала питает клинок (см. RitualFx.feedBlade), клинок отвечает столбом и нимбом.
     */
    private static void bladeBeacon(Minecraft mc, BladeCeremony.Run run, double dens) {
        Random rng = run.rng;
        int age = run.age;
        double bx = run.x;
        double bz = run.z;
        double by = run.y + 1.1;
        double i = run.intensity();
        double fp = clamp01((age - BladeCeremony.FINAL_START) / 35.0);

        // Столб над клинком: растёт при запуске, схлопывается к клинку перед финальной вспышкой
        double rise = RitualAura.smooth(age / 40.0);
        double fall = age < 282 ? 1.0 : Math.max(0.0, 1.0 - (age - 282) / 8.0);
        double height = 16.0 * rise * fall;
        if (height > 0.5) {
            int count = n(rng, 7 * dens * (0.4 + 0.6 * i));
            for (int c = 0; c < count; c++) {
                double h = rng.nextDouble() * height;
                double a = h * 0.9 + age * 0.12 + rng.nextDouble() * 0.5;
                double w = 0.3 + 0.15 * rng.nextDouble();
                double px = bx + Math.cos(a) * w;
                double pz = bz + Math.sin(a) * w;
                if (run.stage == 1) {
                    Fx.particle(mc, rng.nextInt(3) == 0 ? dust(VIOLET, 1.3F) : dust(BLACK, 1.6F), px, run.y + h, pz, 0.0, 0.1, 0.0);
                } else if (run.stage == 2) {
                    if (rng.nextInt(3) == 0) {
                        Fx.particle(mc, ParticleTypes.ENCHANT, px, run.y + h, pz, 0.0, -h, 0.0); // глифы поднимаются вдоль столба
                    } else {
                        Fx.particle(mc, dust(rng.nextBoolean() ? PALE_PURPLE : VIOLET, 1.2F), px, run.y + h, pz, 0.0, 0.1, 0.0);
                    }
                } else {
                    Fx.particle(mc, rng.nextInt(4) == 0 ? ParticleTypes.END_ROD : dust(rng.nextBoolean() ? WHITE : PALE_PURPLE, 1.2F), px, run.y + h, pz, 0.0, 0.1, 0.0);
                }
            }
        }

        // Нимб над клинком: кольцо цвета стадии, сужается в финале
        if (age < 288) {
            double haloR = 2.4 * (1.0 - 0.7 * fp * fp) * RitualAura.smooth(age / 30.0);
            int ring = n(rng, 8 * dens * (0.4 + 0.6 * i));
            for (int c = 0; c < ring; c++) {
                double a = rng.nextDouble() * TWO_PI;
                if (((int) (a / (TWO_PI / 24.0)) & 1) == 1) {
                    continue;
                }
                double ang = age * 0.05 * (run.stage == 2 ? -1.0 : 1.0) + a;
                ParticleOptions p = run.stage == 1 ? dust(rng.nextBoolean() ? BLACK : VIOLET, 1.4F)
                        : run.stage == 2 ? dust(PALE_PURPLE, 1.2F) : dust(rng.nextBoolean() ? WHITE : PURPLE, 1.2F);
                Fx.particle(mc, p, bx + Math.cos(ang) * haloR, by + 4.4, bz + Math.sin(ang) * haloR, 0.0, 0.0, 0.0);
            }
        }

        // Проводник от центра ритуала: по земле и дугой от гироскопа к клинку
        double[] center = RitualAura.centerOf(run.initiator);
        if (center != null && age < 290) {
            double dx = bx - center[0];
            double dz = bz - center[2];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 2.0) {
                double strength = 0.3 + 0.7 * i + 0.5 * fp;
                int ground = n(rng, 9 * dens * strength);
                for (int c = 0; c < ground; c++) {
                    double t = rng.nextDouble();
                    double x = center[0] + dx * t;
                    double z = center[2] + dz * t;
                    ParticleOptions p = rng.nextBoolean() ? dust(VIOLET, 1.2F) : stageColor(run, rng);
                    Fx.particle(mc, p, x, groundY(mc, run, x, z) + 0.2 + Math.sin(t * Math.PI) * 0.3, z, dx / len * 0.1, 0.0, dz / len * 0.1);
                }
                if (n(rng, 1.5 * dens * strength) > 0) {
                    // импульс бежит по проводнику к клинку
                    double x = center[0] + dx * 0.05;
                    double z = center[2] + dz * 0.05;
                    Fx.particle(mc, ParticleTypes.END_ROD, x, groundY(mc, run, x, z) + 0.3, z, dx / len * 0.35, 0.0, dz / len * 0.35);
                }
                int arc = n(rng, 5 * dens * strength);
                double gyH = center[1] + 9.0;
                for (int c = 0; c < arc; c++) {
                    double t = rng.nextDouble();
                    double x = center[0] + dx * t;
                    double z = center[2] + dz * t;
                    double y = gyH + (by + 4.4 - gyH) * t + Math.sin(t * Math.PI) * 3.0;
                    Fx.particle(mc, stageColor(run, rng), x, y, z, 0.0, 0.0, 0.0);
                }
            }
        }
    }

    // Цвет частиц стадии: Тень - чёрный с фиолетовым, Шепот - бледно-фиолетовый и руны, Печать - белый и пурпурный
    private static ParticleOptions stageColor(BladeCeremony.Run run, Random rng) {
        if (run.stage == 1) {
            return rng.nextInt(3) == 0 ? dust(VIOLET, 1.2F) : dust(BLACK, 1.4F);
        } else if (run.stage == 2) {
            return rng.nextInt(3) == 0 ? ParticleTypes.ENCHANT : dust(PALE_PURPLE, 1.1F);
        }
        return rng.nextInt(3) == 0 ? ParticleTypes.END_ROD : dust(rng.nextBoolean() ? WHITE : PURPLE, 1.1F);
    }

    // ---------------------------------------------------------------- Безымянный вливает силу в клинок

    private static final int FIGURE_ARRIVE = 20;
    private static final int FIGURE_LEAVE = 288;

    /**
     * Единственная фигура Безымянного (стадии J): появляется на 20-м тике напротив клинка (с противоположной от инициатора
     * стороны), смотрит на клинок и вливает в него силу своей стадии, к финалу подходит ближе и растворяется перед
     * финальной вспышкой. Тень: поднимается из земли и льёт чёрно-фиолетовую тьму. Шепот: шепчет, от неё к клинку летят
     * глифы. Печать: тянет к клинку натянутую цепь из светлых и тёмных звеньев.
     */
    private static void figure(Minecraft mc, BladeCeremony.Run run, double dens) {
        int age = run.age;
        if (age < FIGURE_ARRIVE || age > FIGURE_LEAVE) {
            return;
        }
        Random rng = run.rng;
        double bx = run.x;
        double bz = run.z;
        double by = run.y + 1.1;
        double dx = bx - run.ix;
        double dz = bz - run.iz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) {
            dx = Math.cos(run.phase);
            dz = Math.sin(run.phase);
            len = 1.0;
        }
        double fp = clamp01((age - BladeCeremony.FINAL_START) / 35.0);
        double dist = 4.5 - 1.3 * fp * fp;
        double fx = bx + dx / len * dist;
        double fz = bz + dz / len * dist;
        double gy = groundY(mc, run, fx, fz);
        double rise = run.stage == 1 ? -2.4 * (1.0 - RitualAura.smooth((age - FIGURE_ARRIVE) / 40.0)) : 0.0;

        if (age == FIGURE_ARRIVE) {
            NamelessFigure.place(mc, run, fx, gy, fz, bx, by, bz);
            figureBurst(mc, run, fx, gy, fz);
        }
        NamelessFigure.move(run, fx, gy, fz, rise);
        if (age == FIGURE_LEAVE) {
            figureBurst(mc, run, fx, gy, fz);
            NamelessFigure.remove(run);
            return;
        }

        double chest = gy + rise + 1.3;
        double i = run.intensity();
        double sx = fx;
        double sy = chest;
        double sz = fz;
        double vx = bx - sx;
        double vy = by - sy;
        double vz = bz - sz;
        double vlen = Math.max(1.0e-3, Math.sqrt(vx * vx + vy * vy + vz * vz));
        if (run.stage == 1) {
            // тьма стекает с фигуры к клинку; у ног фигуры чёрный дым
            int count = n(rng, (1.5 + 3.0 * i) * dens);
            for (int c = 0; c < count; c++) {
                double t = rng.nextDouble();
                Fx.particle(mc, rng.nextInt(3) == 0 ? dust(VIOLET, 1.1F) : dust(BLACK, 1.4F), sx + vx * t, sy + vy * t + Math.sin(t * Math.PI) * 0.8, sz + vz * t,
                        vx / vlen * 0.18, vy / vlen * 0.18, vz / vlen * 0.18);
            }
            if (n(rng, 1.5 * dens) > 0 && rise < -0.05) {
                Fx.particle(mc, ParticleTypes.LARGE_SMOKE, fx + (rng.nextDouble() - 0.5), gy + 0.1, fz + (rng.nextDouble() - 0.5), 0.0, 0.08, 0.0);
            }
        } else if (run.stage == 2) {
            // шёпот: слова-глифы от головы фигуры к клинку
            int count = n(rng, (1.2 + 3.0 * i) * dens);
            for (int c = 0; c < count; c++) {
                Fx.particle(mc, ParticleTypes.ENCHANT, bx, by, bz, fx - bx + (rng.nextDouble() - 0.5) * 0.3, gy + 1.7 - by, fz - bz + (rng.nextDouble() - 0.5) * 0.3);
            }
        } else if (age >= 150) {
            // печать: натянутая цепь из чередующихся светлых и тёмных звеньев от фигуры к клинку
            int links = n(rng, 9 * dens * clamp01((age - 150) / 30.0));
            for (int c = 0; c < links; c++) {
                double t = rng.nextDouble();
                double sag = Math.sin(t * Math.PI) * 0.35 * (1.0 - fp);
                Fx.particle(mc, ((int) (t * 30.0) & 1) == 0 ? dust(WHITE, 0.9F) : dust(DEEP_PURPLE, 1.1F), sx + vx * t, sy + vy * t - sag, sz + vz * t, 0.0, 0.0, 0.0);
            }
        }
    }

    // Появление и исчезновение фигуры сопровождается вспышкой своей стадии (скрывает возникновение)
    private static void figureBurst(Minecraft mc, BladeCeremony.Run run, double x, double gy, double z) {
        double y = gy + 1.0;
        if (run.stage == 1) {
            Fx.burst(mc, ParticleTypes.SMOKE, x, y, z, 30, 0.35, 0.12);
            Fx.burst(mc, ParticleTypes.SQUID_INK, x, y, z, 14, 0.3, 0.12);
            Fx.burst(mc, dust(VIOLET, 1.3F), x, y, z, 14, 0.3, 0.2);
        } else if (run.stage == 2) {
            Fx.burst(mc, ParticleTypes.ENCHANT, x, y, z, 24, 0.5, 0.4);
            Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, x, y, z, 10, 0.3, 0.3);
            Fx.burst(mc, dust(PALE_PURPLE, 1.2F), x, y, z, 14, 0.3, 0.2);
        } else {
            Fx.burst(mc, dust(WHITE, 1.2F), x, y, z, 12, 0.3, 0.2);
            Fx.burst(mc, dust(PURPLE, 1.3F), x, y, z, 18, 0.3, 0.25);
            Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, x, y, z, 10, 0.3, 0.3);
        }
    }

    // ---------------------------------------------------------------- поток силы в клинок

    // От инициатора и от центра ритуала к клинку (только когда клинок лежит в руках стенда)
    private static void transfer(Minecraft mc, BladeCeremony.Run run, double dens) {
        double bx = run.x;
        double by = run.y + 1.1;
        double bz = run.z;
        double i = run.intensity();
        double strength = run.age >= BladeCeremony.FINAL_START && run.age < 290 ? 1.4 : (0.3 + 0.7 * i);
        if (run.age >= 290) {
            return;
        }
        double[][] sources = new double[2][];
        sources[0] = new double[]{run.ix, run.iy + 1.3, run.iz};
        double[] ritual = RitualAura.anchoredCenter(run.initiator);
        if (ritual != null) {
            sources[1] = new double[]{ritual[0], ritual[1] + 2.0, ritual[2]};
        }
        for (double[] s : sources) {
            if (s == null) {
                continue;
            }
            double dx = bx - s[0];
            double dy = by - s[1];
            double dz = bz - s[2];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.5) {
                continue;
            }
            int count = n(run.rng, 3.0 * strength * dens);
            for (int c = 0; c < count; c++) {
                double t = run.rng.nextDouble();
                double arc = Math.sin(t * Math.PI) * Math.min(2.5, len * 0.12);
                ParticleOptions p;
                if (run.stage == 1) {
                    p = run.rng.nextInt(3) == 0 ? dust(VIOLET, 1.0F) : dust(BLACK, 1.2F);
                } else if (run.stage == 2) {
                    p = run.rng.nextInt(3) == 0 ? ParticleTypes.ENCHANT : dust(PALE_PURPLE, 0.9F);
                } else {
                    p = run.rng.nextInt(3) == 0 ? ParticleTypes.END_ROD : dust(run.rng.nextBoolean() ? PURPLE : PALE_PURPLE, 1.0F);
                }
                Fx.particle(mc, p, s[0] + dx * t, s[1] + dy * t + arc, s[2] + dz * t, dx / len * 0.18, dy / len * 0.18, dz / len * 0.18);
            }
        }
    }

    // ================================================================ Стадия 1: Тень божья

    private static void shadow(Minecraft mc, BladeCeremony.Run run, double dens) {
        Random rng = run.rng;
        int age = run.age;
        double bx = run.x;
        double by = run.y + 1.1;
        double bz = run.z;
        double i = run.intensity();
        boolean fin = age >= BladeCeremony.FINAL_START;

        if (age < COLLAPSE_SHADOW) {
            double fp = fin ? Math.min(1.0, (age - BladeCeremony.FINAL_START) / (double) (COLLAPSE_SHADOW - BladeCeremony.FINAL_START)) : 0.0;
            // Закрученные потоки дыма и портала втягиваются в клинок
            int streams = 4 + (int) (i * 4.0);
            double distance = (8.0 - 2.5 * i) * (1.0 - 0.85 * fp);
            for (int k = 0; k < streams; k++) {
                double angle = run.phase + k * (TWO_PI / streams) + age * 0.07 + fp * 1.5;
                double h = Math.sin(k * 2.1 + age * 0.04) * 1.2;
                double ox = Math.cos(angle) * distance;
                double oz = Math.sin(angle) * distance;
                double tx = -Math.sin(angle) * 0.03;
                double tz = Math.cos(angle) * 0.03;
                int count = n(rng, (1.0 + (fin ? 1.0 : 0.0)) * dens);
                for (int c = 0; c < count; c++) {
                    double jx = (rng.nextDouble() - 0.5) * 0.5;
                    double jy = (rng.nextDouble() - 0.5) * 0.5;
                    double jz = (rng.nextDouble() - 0.5) * 0.5;
                    Fx.particle(mc, ParticleTypes.PORTAL, bx, by, bz, ox + jx, h + jy, oz + jz);
                    Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE, bx + ox + jx, by + h + jy, bz + oz + jz,
                            -ox * 0.045 + tx, -h * 0.045, -oz * 0.045 + tz);
                    if (rng.nextInt(4) == 0) {
                        Fx.particle(mc, ParticleTypes.SQUID_INK, bx + ox * 0.7, by + h * 0.7, bz + oz * 0.7, -ox * 0.03, -h * 0.03, -oz * 0.03);
                    }
                }
                if (rng.nextInt(2) == 0) {
                    Fx.particle(mc, dust(rng.nextBoolean() ? PURPLE : VIOLET, 1.1F), bx + ox, by + h, bz + oz, -ox * 0.05, -h * 0.05, -oz * 0.05);
                }
            }

            // Щупальца тени: с земли дугой тянутся к клинку, у каждого чёрный след с фиолетовой искрой на конце
            int tendrils = 5 + (int) (i * 3.0);
            for (int k = 0; k < tendrils; k++) {
                double a = run.phase * 1.7 + k * TWO_PI / tendrils;
                double rad = (4.5 + 3.0 * ((k * 37) % 10) / 10.0) * (1.0 - 0.6 * fp);
                double sx = bx + Math.cos(a) * rad;
                double sz = bz + Math.sin(a) * rad;
                double sy = groundY(mc, run, sx, sz) + 0.1;
                double head = (age * 0.022 + k * 0.17) % 1.0;
                for (int j = 0; j < 4; j++) {
                    double s = head - j * 0.045;
                    if (s < 0.0 || n(rng, 0.9 * dens) == 0) {
                        continue;
                    }
                    double px = sx + (bx - sx) * s;
                    double pz = sz + (bz - sz) * s;
                    double py = sy + (by - sy) * s + Math.sin(s * Math.PI) * 1.6;
                    Fx.particle(mc, j == 0 ? dust(VIOLET, 1.1F) : dust(BLACK, 1.5F - j * 0.15F), px, py, pz, 0.0, 0.0, 0.0);
                }
            }

            // Затмение над клинком: чёрный диск с фиолетовой короной и вращающимися лучами
            double rise = 3.2 * (1.0 - Math.min(1.0, fp * fp));
            double discR = 1.9 * (0.4 + 0.6 * i) * (1.0 - 0.8 * fp);
            double ey = by + rise;
            int disc = n(rng, 8 * dens * i);
            for (int k = 0; k < disc; k++) {
                double a = rng.nextDouble() * TWO_PI;
                double d = discR * Math.sqrt(rng.nextDouble());
                Fx.particle(mc, rng.nextInt(4) == 0 ? dust(DEEP_PURPLE, 1.5F) : dust(BLACK, 1.7F), bx + Math.cos(a) * d, ey, bz + Math.sin(a) * d, 0.0, 0.0, 0.0);
            }
            int corona = n(rng, 7 * dens * i);
            for (int k = 0; k < corona; k++) {
                double a = rng.nextDouble() * TWO_PI;
                Fx.particle(mc, dust(rng.nextBoolean() ? VIOLET : PALE_PURPLE, 1.0F), bx + Math.cos(a) * discR * 1.15, ey, bz + Math.sin(a) * discR * 1.15, 0.0, 0.0, 0.0);
            }
            int rays = n(rng, 5 * dens * i);
            for (int k = 0; k < rays; k++) {
                double a = age * 0.05 + rng.nextInt(12) * TWO_PI / 12.0;
                double d = discR * (1.2 + rng.nextDouble() * 0.9);
                Fx.particle(mc, dust(PURPLE, 0.9F), bx + Math.cos(a) * d, ey, bz + Math.sin(a) * d, Math.cos(a) * 0.02, 0.0, Math.sin(a) * 0.02);
            }

            // Земля темнеет: чёрно-фиолетовая дымка вокруг стенда
            int ground = n(rng, 12 * dens * i);
            for (int k = 0; k < ground; k++) {
                double a = rng.nextDouble() * TWO_PI;
                double d = 9.0 * Math.sqrt(rng.nextDouble()) * (1.0 - 0.6 * fp);
                double x = bx + Math.cos(a) * d;
                double z = bz + Math.sin(a) * d;
                Fx.particle(mc, rng.nextInt(3) == 0 ? dust(DEEP_PURPLE, 1.5F) : dust(BLACK, 1.6F), x, groundY(mc, run, x, z) + 0.15 + rng.nextDouble() * 0.5, z,
                        -Math.sin(a) * 0.03, 0.0, Math.cos(a) * 0.03);
            }

            // Пространственные искажения: расходящиеся кольца и вспышки искр
            if (age >= run.nextEvent) {
                double a = rng.nextDouble() * TWO_PI;
                double r = 1.5 + rng.nextDouble() * 3.0;
                Fx.particle(mc, ParticleTypes.SONIC_BOOM, bx + Math.cos(a) * r, by + (rng.nextDouble() - 0.5), bz + Math.sin(a) * r, 0.0, 0.0, 0.0);
                Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, bx + Math.cos(a) * r, by, bz + Math.sin(a) * r, 6, 0.3, 0.2);
                run.nextEvent = age + (int) ((40 + rng.nextInt(30)) * (1.3 - 0.9 * i));
            }
        } else if (age < POP_SHADOW) {
            // Всё собрано в одной точке на клинке: плотный сгусток тьмы
            for (int k = 0; k < 7; k++) {
                Fx.particle(mc, dust(BLACK, 1.7F), bx + (rng.nextDouble() - 0.5) * 0.25, by + (rng.nextDouble() - 0.5) * 0.25,
                        bz + (rng.nextDouble() - 0.5) * 0.25, 0.0, 0.0, 0.0);
            }
            Fx.particle(mc, dust(VIOLET, 1.3F), bx, by, bz, 0.0, 0.0, 0.0);
        } else if (age == POP_SHADOW) {
            // Выраженная финальная вспышка: клинок наделён силой Тени
            Fx.burst(mc, ParticleTypes.SMOKE, bx, by, bz, 40, 0.25, 0.32);
            Fx.burst(mc, dust(VIOLET, 1.5F), bx, by, bz, 40, 0.25, 0.35);
            Fx.burst(mc, ParticleTypes.WITCH, bx, by, bz, 25, 0.25, 0.25);
            Fx.burst(mc, ParticleTypes.SQUID_INK, bx, by, bz, 20, 0.2, 0.25);
            Fx.particle(mc, ParticleTypes.SONIC_BOOM, bx, by, bz, 0.0, 0.0, 0.0);
        } else if (age <= POP_SHADOW + 6) {
            // Ударная волна тьмы по земле
            double radius = 7.0 * (age - POP_SHADOW) / 6.0;
            int points = n(rng, 30 * dens);
            for (int k = 0; k < points; k++) {
                double a = rng.nextDouble() * TWO_PI;
                double x = bx + Math.cos(a) * radius;
                double z = bz + Math.sin(a) * radius;
                Fx.particle(mc, rng.nextInt(3) == 0 ? dust(VIOLET, 1.2F) : dust(BLACK, 1.5F), x, groundY(mc, run, x, z) + 0.2, z, Math.cos(a) * 0.08, 0.03, Math.sin(a) * 0.08);
            }
        } else if (age % 3 == 0) {
            Fx.particle(mc, ParticleTypes.SMOKE, bx + (rng.nextDouble() - 0.5), by + (rng.nextDouble() - 0.5), bz + (rng.nextDouble() - 0.5), 0.0, 0.02, 0.0);
        }
    }

    // ================================================================ Стадия 2: Шепот божий

    private static void whisper(Minecraft mc, BladeCeremony.Run run, double dens) {
        Random rng = run.rng;
        int age = run.age;
        double bx = run.x;
        double by = run.y + 1.1;
        double bz = run.z;
        double i = run.intensity();
        boolean fin = age >= BladeCeremony.FINAL_START;

        // Четыре безмолвных источника голоса вокруг клинка (светящиеся огоньки на земле): от каждого к клинку летят глифы, как слова
        if (age < POP_WHISPER - 6) {
            for (int k = 0; k < 4; k++) {
                double a = run.phase + k * Math.PI / 2.0 + age * 0.004;
                double sx = bx + Math.cos(a) * 5.0;
                double sz = bz + Math.sin(a) * 5.0;
                double sy = groundY(mc, run, sx, sz) + 1.4 + Math.sin(age * 0.06 + k) * 0.25;
                if (age >= 40 && n(rng, 0.8 * dens) > 0) {
                    Fx.particle(mc, dust(k % 2 == 0 ? PALE_PURPLE : VIOLET, 1.1F), sx, sy, sz, 0.0, 0.0, 0.0);
                }
                int words = n(rng, 1.6 * (0.4 + i) * dens * (age >= 40 ? 1.0 : 0.0));
                for (int w = 0; w < words; w++) {
                    Fx.particle(mc, ParticleTypes.ENCHANT, bx, by, bz, sx - bx + (rng.nextDouble() - 0.5) * 0.3, sy - by, sz - bz + (rng.nextDouble() - 0.5) * 0.3);
                }
            }
        }

        if (!fin) {
            // Потоки глифов закручиваются спиралью и вливаются в клинок
            int arms = 4;
            for (int k = 0; k < arms; k++) {
                double a = age * 0.06 + k * TWO_PI / arms + run.phase;
                double rad = 2.5 + 4.5 * (0.5 + 0.5 * Math.sin(age * 0.03 + k));
                double h = Math.sin(age * 0.05 + k * 1.7) * 1.4;
                int count = n(rng, (0.6 + 2.4 * i) * dens);
                for (int c = 0; c < count; c++) {
                    Fx.particle(mc, ParticleTypes.ENCHANT, bx, by, bz, Math.cos(a) * rad, h + 0.5, Math.sin(a) * rad);
                }
            }
            // Эхо-волны от клинка: кольца расходятся в разных плоскостях и пересекаются
            if (age % Math.max(7, (int) (24 - 14 * i)) == 0) {
                run.waves.add(newWave(rng, age));
            }
            for (java.util.Iterator<Wave> it = run.waves.iterator(); it.hasNext(); ) {
                Wave w = it.next();
                int t = age - w.born;
                if (t >= w.life) {
                    it.remove();
                    continue;
                }
                double progress = t / (double) w.life;
                double radius = w.maxRadius * (1.0 - Math.pow(1.0 - progress, 2.0));
                ring(mc, rng, bx + w.ox, by + w.oy, bz + w.oz, w.nx, w.ny, w.nz, radius, (int) Math.round(13 * (1.0 - progress) * dens) + 1, false);
            }
            // Глитчи: резкие линии искажения и всполохи
            if (age >= run.nextEvent) {
                glitch(mc, rng, bx, by, bz);
                run.nextEvent = age + (int) ((22 + rng.nextInt(30)) * (1.3 - 0.8 * i));
            }
            // Дымка шёпота над землёй
            int haze = n(rng, 8 * dens * i);
            for (int k = 0; k < haze; k++) {
                double a = rng.nextDouble() * TWO_PI;
                double d = 8.0 * Math.sqrt(rng.nextDouble());
                double x = bx + Math.cos(a) * d;
                double z = bz + Math.sin(a) * d;
                Fx.particle(mc, rng.nextBoolean() ? dust(DEEP_PURPLE, 1.3F) : dust(PURPLE, 1.1F), x, groundY(mc, run, x, z) + 0.2 + rng.nextDouble() * 0.5, z, 0.0, 0.01, 0.0);
            }
        } else if (age < POP_WHISPER) {
            // Финал: все волны и глифы одновременно сходятся в клинок
            double fp = (age - BladeCeremony.FINAL_START) / (double) (POP_WHISPER - BladeCeremony.FINAL_START);
            if (age == BladeCeremony.FINAL_START) {
                run.waves.clear();
                for (int k = 0; k < 8; k++) {
                    Wave w = newWave(rng, age);
                    w.maxRadius = 4.0 + rng.nextDouble() * 2.5;
                    run.waves.add(w);
                }
            }
            for (Wave w : run.waves) {
                double radius = w.maxRadius * (1.0 - fp) * (1.0 - fp);
                ring(mc, rng, bx + w.ox * (1.0 - fp), by + w.oy * (1.0 - fp), bz + w.oz * (1.0 - fp), w.nx, w.ny, w.nz, radius, (int) Math.round(14 * dens) + 1, true);
            }
            int rush = n(rng, 5 * dens);
            for (int k = 0; k < rush; k++) {
                double a = rng.nextDouble() * TWO_PI;
                double d = 6.0 * (1.0 - fp) + 1.0;
                Fx.particle(mc, ParticleTypes.ENCHANT, bx, by, bz, Math.cos(a) * d, (rng.nextDouble() - 0.3) * 2.0, Math.sin(a) * d);
            }
            if (age % 8 == 0) {
                glitch(mc, rng, bx, by, bz);
            }
        } else if (age == POP_WHISPER) {
            // Выраженная финальная вспышка: клинок «заговорил»
            Fx.burst(mc, ParticleTypes.ENCHANT, bx, by, bz, 60, 0.5, 0.6);
            Fx.burst(mc, dust(VIOLET, 1.5F), bx, by, bz, 40, 0.3, 0.45);
            Fx.burst(mc, ParticleTypes.END_ROD, bx, by, bz, 18, 0.3, 0.4);
            Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, bx, by, bz, 20, 0.2, 0.5);
            Fx.particle(mc, ParticleTypes.SONIC_BOOM, bx, by, bz, 0.0, 0.0, 0.0);
            run.waves.clear();
        } else if (age <= POP_WHISPER + 6) {
            // Звуковая волна по земле
            double radius = 8.0 * (age - POP_WHISPER) / 6.0;
            int points = n(rng, 32 * dens);
            for (int k = 0; k < points; k++) {
                double a = rng.nextDouble() * TWO_PI;
                double x = bx + Math.cos(a) * radius;
                double z = bz + Math.sin(a) * radius;
                Fx.particle(mc, rng.nextInt(4) == 0 ? dust(PALE_PURPLE, 1.2F) : dust(VIOLET, 1.2F), x, groundY(mc, run, x, z) + 0.2, z, Math.cos(a) * 0.1, 0.03, Math.sin(a) * 0.1);
            }
        } else if (age % 3 == 0) {
            Fx.particle(mc, ParticleTypes.ENCHANT, bx, by, bz, (rng.nextDouble() - 0.5) * 2.0, 0.5, (rng.nextDouble() - 0.5) * 2.0);
        }
    }

    private static Wave newWave(Random rng, int age) {
        Wave w = new Wave();
        double a = rng.nextDouble() * TWO_PI;
        double d = rng.nextDouble() * 1.2;
        w.ox = Math.cos(a) * d;
        w.oz = Math.sin(a) * d;
        w.oy = (rng.nextDouble() - 0.5) * 1.2;
        double nx = rng.nextDouble() - 0.5;
        double ny = rng.nextDouble() * 1.6 - 0.8;
        double nz = rng.nextDouble() - 0.5;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-3) {
            nx = 0.0;
            ny = 1.0;
            nz = 0.0;
            len = 1.0;
        }
        w.nx = nx / len;
        w.ny = ny / len;
        w.nz = nz / len;
        w.born = age;
        w.life = 38 + rng.nextInt(14);
        w.maxRadius = 3.5 + rng.nextDouble() * 3.0;
        return w;
    }

    // Окружность радиусом radius с центром в (x,y,z) в плоскости с нормалью (nx,ny,nz)
    private static void ring(Minecraft mc, Random rng, double x, double y, double z, double nx, double ny, double nz,
                             double radius, int points, boolean inward) {
        double ux;
        double uy;
        double uz;
        if (Math.abs(ny) < 0.9) {
            ux = nz;
            uy = 0.0;
            uz = -nx;
        } else {
            ux = 0.0;
            uy = nz;
            uz = -ny;
        }
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        double vx = ny * uz - nz * uy;
        double vy = nz * ux - nx * uz;
        double vz = nx * uy - ny * ux;
        for (int p = 0; p < points; p++) {
            double a = rng.nextDouble() * TWO_PI;
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            double rx = ux * cos + vx * sin;
            double ry = uy * cos + vy * sin;
            double rz = uz * cos + vz * sin;
            double speed = inward ? -0.04 : 0.02;
            int kind = rng.nextInt(5);
            ParticleOptions type = kind == 0 ? ParticleTypes.END_ROD : kind == 1 ? dust(PALE_PURPLE, 0.8F) : kind == 2 ? ParticleTypes.WITCH : dust(VIOLET, 0.9F);
            Fx.particle(mc, type, x + rx * radius, y + ry * radius, z + rz * radius, rx * speed, ry * speed, rz * speed);
        }
    }

    // Глитч: резкая прямая линия частиц со случайным наклоном и короткая вспышка искр
    private static void glitch(Minecraft mc, Random rng, double cx, double cy, double cz) {
        double a = rng.nextDouble() * TWO_PI;
        double d = 1.0 + rng.nextDouble() * 4.0;
        double px = cx + Math.cos(a) * d;
        double py = cy + (rng.nextDouble() - 0.5) * 2.0;
        double pz = cz + Math.sin(a) * d;
        double dir = rng.nextDouble() * Math.PI;
        double dx = Math.cos(dir);
        double dz = Math.sin(dir);
        for (int s = -10; s <= 10; s++) {
            double t = s * 0.25;
            Fx.particle(mc, rng.nextInt(3) == 0 ? ParticleTypes.END_ROD : dust(rng.nextBoolean() ? PALE_PURPLE : VIOLET, 0.9F), px + dx * t, py, pz + dz * t, 0.0, 0.0, 0.0);
        }
        Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, px, py, pz, 8, 0.25, 0.25);
    }

    // ================================================================ Стадия 3: Печать божья

    private static final double[] SEAL_RADII = {2.0, 3.5, 5.2};

    private static void seal(Minecraft mc, BladeCeremony.Run run, double dens) {
        Random rng = run.rng;
        int age = run.age;
        double bx = run.x;
        double by = run.y + 1.1;
        double bz = run.z;

        if (age >= STAMP) {
            stampAfter(mc, run, dens, age);
            return;
        }

        boolean fin = age >= BladeCeremony.FINAL_START;
        double fp = fin ? (age - BladeCeremony.FINAL_START) / (double) (STAMP - BladeCeremony.FINAL_START) : 0.0;
        double scale = 1.0 - 0.95 * fp * fp;
        double rot = run.phase + age * 0.012 + fp * fp * 2.5;
        double gy = run.y + 0.12;

        // Круги печати на земле вокруг стенда: кольца дорисовываются дугой и замыкаются, затем лучи и гексаграмма
        for (int k = 0; k < SEAL_RADII.length; k++) {
            double formed = clamp01((age - k * RING_LEN) / (double) RING_LEN);
            if (formed <= 0.0) {
                continue;
            }
            double radius = SEAL_RADII[k] * scale;
            double dir = k % 2 == 0 ? 1.0 : -1.0;
            int samples = n(rng, (10 + k * 3) * dens);
            for (int sIdx = 0; sIdx < samples; sIdx++) {
                double a = rot * dir + rng.nextDouble() * TWO_PI * formed;
                Fx.particle(mc, sealGlow(rng), bx + Math.cos(a) * radius, gy, bz + Math.sin(a) * radius, 0.0, 0.0, 0.0);
            }
            double head = rot * dir + TWO_PI * formed;
            Fx.particle(mc, ParticleTypes.END_ROD, bx + Math.cos(head) * radius, gy + 0.05, bz + Math.sin(head) * radius,
                    -Math.sin(head) * 0.12 * dir, 0.0, Math.cos(head) * 0.12 * dir);
        }
        double innerR = SEAL_RADII[0] * scale;
        double outerR = SEAL_RADII[2] * scale;
        for (int m = 0; m < 6; m++) {
            double g = clamp01((age - SPOKE_START - m * 5) / (double) SPOKE_LEN);
            if (g <= 0.0) {
                continue;
            }
            double a = rot + m * Math.PI / 3.0;
            double ex = Math.cos(a);
            double ez = Math.sin(a);
            for (int sIdx = 0; sIdx < 3; sIdx++) {
                double t = innerR + (outerR - innerR) * g * rng.nextDouble();
                Fx.particle(mc, sealGlow(rng), bx + ex * t, gy, bz + ez * t, 0.0, 0.0, 0.0);
            }
            double dirSign = fin ? -1.0 : 1.0;
            double start = fin ? outerR : innerR;
            Fx.particle(mc, ParticleTypes.ELECTRIC_SPARK, bx + ex * start, gy + 0.05, bz + ez * start, ex * 0.14 * dirSign, 0.0, ez * 0.14 * dirSign);
        }
        for (int e = 0; e < 6; e++) {
            double g = clamp01((age - EDGE_START - e * EDGE_STEP) / (double) EDGE_LEN);
            if (g <= 0.0) {
                continue;
            }
            int tri = e % 2;
            int which = e / 2;
            double a0 = rot + Math.toRadians(tri * 60.0 + which * 120.0);
            double a1 = rot + Math.toRadians(tri * 60.0 + (which + 1) * 120.0);
            double x0 = Math.cos(a0) * outerR;
            double z0 = Math.sin(a0) * outerR;
            double x1 = Math.cos(a1) * outerR;
            double z1 = Math.sin(a1) * outerR;
            for (int sIdx = 0; sIdx < 4; sIdx++) {
                double t = g * rng.nextDouble();
                Fx.particle(mc, sealGlow(rng), bx + x0 + (x1 - x0) * t, gy, bz + z0 + (z1 - z0) * t, 0.0, 0.0, 0.0);
            }
            double len = Math.max(1.0e-3, Math.sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0)));
            Fx.particle(mc, ParticleTypes.ELECTRIC_SPARK, bx + x0, gy + 0.05, bz + z0, (x1 - x0) / len * 0.16, 0.0, (z1 - z0) / len * 0.16);
        }
        // Замыкание колец: волна по земле
        if (age == 50 || age == 100 || age == 150) {
            shockRing(mc, run, dens, 3.0 + age / 50.0);
        }

        // Цепи с неба опускаются спиралью и обвивают клинок, затем затягиваются
        double tighten = clamp01((age - 220) / 30.0);
        for (int c = 0; c < CHAINS; c++) {
            int t = age - (CHAIN_START + c * CHAIN_STEP);
            if (t < 0) {
                continue;
            }
            double drop = clamp01(t / (double) CHAIN_DROP);
            double a0 = run.phase + c * TWO_PI / CHAINS;
            double top = by + CHAIN_TOP * (1.0 - 0.9 * fp);
            int links = n(rng, 7 * dens);
            for (int j = 0; j < links; j++) {
                double sp = rng.nextDouble() * drop;           // 0 = верх цепи, 1 = клинок
                double radius = (3.0 * (1.0 - sp) + 0.45 * sp) * (1.0 - 0.5 * tighten) * (1.0 - 0.8 * fp);
                double ang = a0 + sp * 3.0 * Math.PI + age * 0.01;
                double py = top + (by - top) * sp;
                int link = (int) (sp * 44.0) & 1;               // звенья чередуются: светлое и тёмное
                Fx.particle(mc, link == 0 ? dust(WHITE, 0.9F) : dust(DEEP_PURPLE, 1.1F), bx + Math.cos(ang) * radius, py, bz + Math.sin(ang) * radius, 0.0, 0.0, 0.0);
            }
            if (t >= CHAIN_DROP && n(rng, 0.8 * dens) > 0) {
                // затянутая цепь: искра скользит вниз к клинку
                double sp = rng.nextDouble() * 0.5;
                double radius = (3.0 * (1.0 - sp) + 0.45 * sp) * (1.0 - 0.5 * tighten) * (1.0 - 0.8 * fp);
                double ang = a0 + sp * 3.0 * Math.PI + age * 0.01;
                Fx.particle(mc, ParticleTypes.ELECTRIC_SPARK, bx + Math.cos(ang) * radius, top + (by - top) * sp, bz + Math.sin(ang) * radius, 0.0, -0.12, 0.0);
            }
            if (t == CHAIN_DROP) {
                shockRing(mc, run, dens, 2.5 + 0.3 * c); // цепь легла на клинок: волна по земле
                Fx.burst(mc, ParticleTypes.END_ROD, bx, by, bz, 6, 0.15, 0.12);
            }
        }

        // Знак-печать над клинком: кольца, гексаграмма, замочная скважина; повёрнут к инициатору
        sigil(mc, run, dens, bx, by, bz, fp);

        if (fin) {
            double a = rng.nextDouble() * TWO_PI;
            double r = outerR * (0.4 + 0.6 * rng.nextDouble());
            Fx.particle(mc, ParticleTypes.END_ROD, bx + Math.cos(a) * r, by, bz + Math.sin(a) * r, -Math.cos(a) * r * 0.08, 0.0, -Math.sin(a) * r * 0.08);
        }
    }

    // Вертикальный знак: формируется 160-250 над клинком, затем стремительно падает на клинок (к моменту STAMP)
    private static void sigil(Minecraft mc, BladeCeremony.Run run, double dens, double bx, double by, double bz, double fp) {
        int age = run.age;
        double f = clamp01((age - SIGIL_START) / (double) (SIGIL_FORMED - SIGIL_START));
        if (f <= 0.0) {
            return;
        }
        Random rng = run.rng;
        double nx = run.ix - bx;
        double nz = run.iz - bz;
        double len = Math.sqrt(nx * nx + nz * nz);
        if (len < 0.5) {
            nx = Math.cos(run.phase);
            nz = Math.sin(run.phase);
            len = 1.0;
        }
        double hx = -nz / len;   // горизонтальная ось плоскости знака (перпендикулярна направлению к инициатору)
        double hz = nx / len;
        double descend = fp * fp;                                  // падение ускоряется
        double centerY = by + SIGIL_HEIGHT * (1.0 - descend) + Math.sin(age * 0.05) * 0.1 * (1.0 - fp);
        double radius = SIGIL_RADIUS * (1.0 - 0.65 * descend) * (0.55 + 0.45 * RitualAura.smooth(f * 1.5));
        double inner = radius * 0.68;
        double spinOuter = age * 0.02;
        double spinInner = -age * 0.03;

        // внешнее кольцо (пунктир, дорисовывается дугой)
        double ringFrac = RitualAura.smooth(f / 0.3);
        int outerSamples = n(rng, 14 * dens * ringFrac);
        for (int i = 0; i < outerSamples; i++) {
            double a = rng.nextDouble() * TWO_PI * ringFrac;
            if (((int) (a / (TWO_PI / 36.0)) & 1) == 1) {
                continue;
            }
            put(mc, rng, bx, centerY, bz, hx, hz, radius, spinOuter + a);
        }
        // внутреннее кольцо
        double innerFrac = RitualAura.smooth((f - 0.2) / 0.3);
        int innerSamples = n(rng, 10 * dens * innerFrac);
        for (int i = 0; i < innerSamples; i++) {
            put(mc, rng, bx, centerY, bz, hx, hz, inner, spinInner + rng.nextDouble() * TWO_PI * innerFrac);
        }
        // гексаграмма на внутреннем кольце: грани появляются по очереди
        for (int e = 0; e < 6; e++) {
            double g = clamp01((f - 0.45 - e * 0.05) / 0.12);
            if (g <= 0.0) {
                continue;
            }
            int tri = e % 2;
            int which = e / 2;
            double a0 = -spinInner * 0.5 + Math.toRadians(tri * 60.0 + which * 120.0);
            double a1 = -spinInner * 0.5 + Math.toRadians(tri * 60.0 + (which + 1) * 120.0);
            int samples = n(rng, 3 * dens);
            for (int i = 0; i < samples; i++) {
                double t = g * rng.nextDouble();
                double u = (Math.cos(a0) * (1.0 - t) + Math.cos(a1) * t) * inner;
                double v = (Math.sin(a0) * (1.0 - t) + Math.sin(a1) * t) * inner;
                Fx.particle(mc, sealGlow(rng), bx + hx * u, centerY + v, bz + hz * u, 0.0, 0.0, 0.0);
            }
        }
        // метки между кольцами
        if (f > 0.6) {
            int ticks = n(rng, 4 * dens);
            for (int i = 0; i < ticks; i++) {
                double a = spinOuter * 0.5 + Math.round(rng.nextDouble() * 24.0) * TWO_PI / 24.0;
                double d = inner + 0.12 * radius + (radius - inner - 0.24 * radius) * rng.nextDouble();
                Fx.particle(mc, dust(PALE_PURPLE, 0.8F), bx + hx * Math.cos(a) * d, centerY + Math.sin(a) * d, bz + hz * Math.cos(a) * d, 0.0, 0.0, 0.0);
            }
        }
        // замочная скважина в центре: круг и ножка, всегда вертикальна
        if (f > 0.8) {
            double kr = radius * 0.12;
            int keyhole = n(rng, 4 * dens);
            for (int i = 0; i < keyhole; i++) {
                if (rng.nextInt(3) == 0) {
                    double v = -kr - rng.nextDouble() * radius * 0.28;
                    double u = (rng.nextDouble() - 0.5) * kr * (1.0 + (-v - kr) / (radius * 0.28));
                    Fx.particle(mc, dust(WHITE, 0.8F), bx + hx * u, centerY + v, bz + hz * u, 0.0, 0.0, 0.0);
                } else {
                    double a = rng.nextDouble() * TWO_PI;
                    Fx.particle(mc, dust(WHITE, 0.8F), bx + hx * Math.cos(a) * kr, centerY + Math.sin(a) * kr, bz + hz * Math.cos(a) * kr, 0.0, 0.0, 0.0);
                }
            }
        }
        // луч от знака к клинку
        if (f > 0.5 && n(rng, 2.0 * dens) > 0) {
            double t = rng.nextDouble();
            Fx.particle(mc, dust(PALE_PURPLE, 0.9F), bx + (rng.nextDouble() - 0.5) * 0.3, centerY + (by - centerY) * t, bz + (rng.nextDouble() - 0.5) * 0.3, 0.0, -0.1, 0.0);
        }
        // при падении за знаком тянется шлейф
        if (fp > 0.0) {
            int trail = n(rng, 8 * dens * fp);
            for (int i = 0; i < trail; i++) {
                double a = rng.nextDouble() * TWO_PI;
                put(mc, rng, bx, centerY + 0.4 + rng.nextDouble() * 1.5, bz, hx, hz, radius * (0.5 + 0.5 * rng.nextDouble()), a);
            }
        }
    }

    private static void put(Minecraft mc, Random rng, double cx, double cy, double cz, double hx, double hz, double radius, double angle) {
        Fx.particle(mc, sealGlow(rng), cx + hx * Math.cos(angle) * radius, cy + Math.sin(angle) * radius, cz + hz * Math.cos(angle) * radius, 0.0, 0.0, 0.0);
    }

    // Оттиск: после удара знак остаётся светящимся отпечатком на земле вокруг клинка и гаснет к 300-му тику
    private static void stampAfter(Minecraft mc, BladeCeremony.Run run, double dens, int age) {
        Random rng = run.rng;
        double bx = run.x;
        double by = run.y + 1.1;
        double bz = run.z;
        if (age == STAMP) {
            Fx.burst(mc, ParticleTypes.END_ROD, bx, by, bz, 40, 0.15, 0.4);
            Fx.burst(mc, dust(PURPLE, 1.4F), bx, by, bz, 40, 0.15, 0.35);
            Fx.burst(mc, dust(WHITE, 1.2F), bx, by, bz, 15, 0.1, 0.3);
            Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, bx, by, bz, 25, 0.1, 0.5);
        }
        if (age == 290) {
            shockRing(mc, run, dens, 7.0); // волна тишины
            Fx.particle(mc, ParticleTypes.SONIC_BOOM, bx, by, bz, 0.0, 0.0, 0.0);
        }
        double fade = clamp01((BladeCeremony.DURATION_TICKS - age) / 15.0);
        double gy = run.y + 0.12;
        int ring = n(rng, 14 * dens * fade);
        for (int i = 0; i < ring; i++) {
            double a = rng.nextDouble() * TWO_PI;
            double r = rng.nextBoolean() ? 1.7 : 1.0;
            Fx.particle(mc, sealGlow(rng), bx + Math.cos(a) * r, gy, bz + Math.sin(a) * r, 0.0, 0.0, 0.0);
        }
        int star = n(rng, 8 * dens * fade);
        for (int i = 0; i < star; i++) {
            int e = rng.nextInt(6);
            double a0 = Math.toRadians((e % 2) * 60.0 + (e / 2) * 120.0);
            double a1 = Math.toRadians((e % 2) * 60.0 + (e / 2 + 1) * 120.0);
            double t = rng.nextDouble();
            Fx.particle(mc, dust(PALE_PURPLE, 0.9F), bx + (Math.cos(a0) * (1.0 - t) + Math.cos(a1) * t) * 1.7, gy,
                    bz + (Math.sin(a0) * (1.0 - t) + Math.sin(a1) * t) * 1.7, 0.0, 0.0, 0.0);
        }
    }

    // Мгновенная круговая волна на земле вокруг стенда (при замыкании элементов печати)
    private static void shockRing(Minecraft mc, BladeCeremony.Run run, double dens, double radius) {
        int points = n(run.rng, 40 * dens);
        for (int k = 0; k < points; k++) {
            double a = run.rng.nextDouble() * TWO_PI;
            double x = run.x + Math.cos(a) * radius;
            double z = run.z + Math.sin(a) * radius;
            Fx.particle(mc, run.rng.nextInt(3) == 0 ? dust(WHITE, 1.0F) : dust(VIOLET, 1.1F), x, groundY(mc, run, x, z) + 0.15, z, Math.cos(a) * 0.09, 0.02, Math.sin(a) * 0.09);
        }
    }

    // Печать: белые и фиолетовые частицы
    private static ParticleOptions sealGlow(Random rng) {
        int k = rng.nextInt(5);
        return k == 0 ? ParticleTypes.END_ROD : k == 1 ? dust(WHITE, 0.8F) : k == 2 ? dust(PALE_PURPLE, 0.9F) : dust(PURPLE, 0.9F);
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(1.0, v);
    }
}
