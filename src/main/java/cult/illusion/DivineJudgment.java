package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Власть Божья, «Божественный приговор»: только частицы, звуки и клиентский HUD, мир не меняется. */
public final class DivineJudgment {
    private DivineJudgment() {
    }

    /** Цель от другого клиента неизвестна: выбираем сами по положению игрока. */
    public static final long REMOTE_UNKNOWN = -1L;

    private static Entity target = null;
    private static Vec3 lastPos = Vec3.ZERO;
    private static int tick = -1;
    private static double sealAngle = 0.0;
    private static boolean targetVisible = true;  // видна ли цель локальному наблюдателю (отдельно от самого эффекта на цели)
    private static UUID ownerId = null;           // кто активировал способность (для усиления ауры)

    public static boolean isActive() {
        return tick >= 0;
    }

    /** Активировавший способность игрок, пока идёт призыв или сам приговор; иначе null. */
    public static UUID activeOwner() {
        return (tick >= 0 || Summon.isPowerActive()) ? ownerId : null;
    }

    /** Цель способности: только игрок (проверка класса Player), живой, не наблюдатель и не сам активировавший. */
    public static boolean isValidTarget(Entity entity, Entity viewer) {
        return entity != null
                && entity != viewer
                && entity instanceof Player player
                && player.isAlive()
                && !player.isSpectator();
    }

    /** Клеймо: цель, на которую наложен приговор, пока идёт способность. Контур не рисуется, пока цель не видна наблюдателю. */
    public static boolean isMarked(Entity entity) {
        return tick >= 0 && target != null && entity == target && targetVisible;
    }

    /** Метку на HUD можно рисовать: цели нет (метка в точке прицела) или цель в прямой видимости. */
    public static boolean isMarkerVisible() {
        return target == null || targetVisible;
    }

    /** Точка над целью для метки на HUD или null, если способность не идёт. */
    public static Vec3 markerPosition(float partial) {
        if (tick < 0) {
            return null;
        }
        if (target != null && !target.isRemoved()) {
            return target.getPosition(partial).add(0.0, target.getBbHeight() + 0.8, 0.0);
        }
        return lastPos.add(0.0, 2.6, 0.0);
    }

    public static void reset() {
        if (tick >= 0) {
            TargetMarkers.suppressGaze();
        }
        OverlayHud.clearDim();
        target = null;
        lastPos = Vec3.ZERO;
        tick = -1;
        sealAngle = 0.0;
        targetVisible = true;
        ownerId = null;
    }

    /** Существо строго под прицелом viewer (конус) или null. Для выбора целей способностей не используется: там только игроки и прямой взгляд (pickPlayerInCone). */
    public static Entity pickInCone(Minecraft mc, Player viewer) {
        Vec3 eye = viewer.getEyePosition();
        Vec3 look = viewer.getLookAngle();
        AABB area = viewer.getBoundingBox().inflate(Config.JUDGMENT_RANGE);
        List<Entity> candidates = mc.level.getEntities(viewer, area, e -> e instanceof LivingEntity && e.isAlive());

        Entity best = null;
        double bestDot = Config.JUDGMENT_CONE_COS;
        for (Entity candidate : candidates) {
            Vec3 toCandidate = candidate.getBoundingBox().getCenter().subtract(eye);
            double distance = toCandidate.length();
            if (distance < 0.5 || distance > Config.JUDGMENT_RANGE) {
                continue;
            }
            double dot = look.dot(toCandidate) / distance;
            if (dot > bestDot) {
                bestDot = dot;
                best = candidate;
            }
        }
        return best;
    }

    // Отступ от точки попадания луча в хитбокс при проверке блоков: сама поверхность хитбокса не считается преградой
    private static final double AIM_BACKOFF = 0.02;

    /**
     * Игрок, на которого viewer смотрит прямо: луч взгляда из глаз попадает в хитбокс игрока (не дальше JUDGMENT_RANGE),
     * а между глазами и точкой попадания нет непрозрачных блоков. Мобы и другие сущности не рассматриваются, поэтому моб
     * в прицеле не мешает выбрать игрока за ним. Никнейм, табличка с именем, направление «примерно в сторону игрока» и
     * близость выбор не дают. Если на луче несколько игроков, берётся ближайший. Имя метода сохранено, конуса больше нет.
     */
    public static Player pickPlayerInCone(Minecraft mc, Player viewer) {
        if (mc.level == null) {
            return null;
        }
        Vec3 eye = viewer.getEyePosition();
        Vec3 sight = viewer.getLookAngle().scale(Config.JUDGMENT_RANGE);
        Vec3 end = eye.add(sight);
        AABB area = viewer.getBoundingBox().expandTowards(sight).inflate(1.0);
        List<Entity> candidates = mc.level.getEntities(viewer, area, e -> isValidTarget(e, viewer));

        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : candidates) {
            AABB box = candidate.getBoundingBox().inflate(candidate.getPickRadius());
            Vec3 point = box.contains(eye) ? eye : box.clip(eye, end).orElse(null);
            if (point == null) {
                continue; // луч не попадает в хитбокс
            }
            double distance = eye.distanceTo(point);
            if (distance >= bestDistance) {
                continue;
            }
            Vec3 probe = distance > AIM_BACKOFF ? eye.add(point.subtract(eye).scale((distance - AIM_BACKOFF) / distance)) : eye;
            if (Visibility.clear(mc, eye, probe, viewer)) { // точка попадания видна: непрозрачных блоков на пути нет
                bestDistance = distance;
                best = (Player) candidate;
            }
        }
        return best;
    }

    /**
     * Цель под прямым взглядом: только игрок (см. pickPlayerInCone). Запасного выбора «ближайшего игрока» нет:
     * если viewer не смотрит прямо на игрока, цели нет (null).
     */
    public static Entity pickTarget(Minecraft mc, Player viewer) {
        return pickPlayerInCone(mc, viewer);
    }

    /**
     * Цель выбирается в момент нажатия на клиенте активировавшего игрока (viewer == mc.player): игрок под прямым взглядом
     * или игрок, выбранный успешным ударом. Остальные клиенты цель заново не выбирают: они используют идентификатор,
     * переданный в метке.
     */
    public static void selectTarget(Minecraft mc, Player viewer) {
        selectTarget(mc, viewer, REMOTE_UNKNOWN);
    }

    /** Код выбранной цели для отправки другим клиентам: id сущности + 1, 0 = цели нет. */
    public static long targetCode() {
        return target == null ? 0L : (long) target.getId() + 1L;
    }

    /**
     * remoteCode: цель, которую выбрал активировавший игрок (id + 1, 0 = цели нет) или REMOTE_UNKNOWN.
     * Для чужой активации цель берётся только из remoteCode: если такого игрока у нас нет или данных в метке нет, цели нет,
     * относительно собственного взгляда или положения она не выбирается. Выбор по прямому взгляду выполняется только у
     * самого активировавшего игрока.
     */
    public static void selectTarget(Minecraft mc, Player viewer, long remoteCode) {
        target = null;
        ownerId = viewer.getUUID();
        targetVisible = true;
        Vec3 eye = viewer.getEyePosition();
        Vec3 look = viewer.getLookAngle();
        lastPos = eye.add(look.scale(Config.JUDGMENT_FALLBACK_DISTANCE));
        if (remoteCode == 0L) {
            return;
        }
        if (remoteCode > 0L) {
            Entity known = mc.level.getEntity((int) (remoteCode - 1L));
            if (isValidTarget(known, viewer)) {
                target = known;
                lastPos = known.position();
            }
            return;
        }
        if (viewer != mc.player) {
            return; // в метке нет данных о цели: чужой взгляд заново не разбирается
        }
        // Активировавший игрок: прямой взгляд, а если его нет, игрок, выбранный успешным ударом (он должен быть виден и в пределах дальности)
        Entity best = pickPlayerInCone(mc, viewer);
        if (best == null) {
            Entity struck = TargetMarkers.struckTarget();
            if (struck instanceof Player && isValidTarget(struck, viewer) && viewer.distanceTo(struck) <= Config.JUDGMENT_RANGE
                    && Visibility.canSee(mc, eye, struck, viewer)) {
                best = struck;
            }
        }
        target = best;
        lastPos = best != null ? best.position() : lastPos;
    }

    /** Вызывается после пятого удара. */
    public static void begin(Minecraft mc) {
        if (tick >= 0) {
            return;
        }
        tick = 0;
        sealAngle = Fx.RANDOM.nextDouble() * Math.PI * 2.0;
    }

    public static void tick(Minecraft mc) {
        if (tick < 0) {
            return;
        }
        boolean targetAlive = target != null && !target.isRemoved();
        if (targetAlive) {
            lastPos = target.position();
        }
        // Сохранение эффекта на цели и его показ конкретному наблюдателю разделены: состояние не сбрасывается,
        // скрываются только отметка и контур, пока между наблюдателем и целью есть блоки
        targetVisible = !targetAlive || mc.player == null || target == mc.player
                || Visibility.canSee(mc, mc.player.getEyePosition(), target, mc.player);
        Vec3 p = lastPos;
        double height = targetAlive ? target.getBbHeight() : 1.8;
        int buildup = Config.JUDGMENT_BUILDUP_TICKS;

        // Клеймо: метка, печать над головой, круг под ногами, потоки частиц
        tickSeal(mc, p, height, buildup);

        if (tick == buildup - Config.JUDGMENT_DIM_TICKS) {
            // Перед ударом экран наблюдателя слегка темнеет
            OverlayHud.startDim(Config.JUDGMENT_DIM_TICKS);
        }
        if (tick >= buildup - Config.JUDGMENT_DESCENT_TICKS && tick < buildup) {
            tickDescent(mc, p, buildup);
        }

        if (tick == 0) {
            // Этап 2: вспышка на цели и начало накопления энергии
            Fx.burst(mc, ParticleTypes.WITCH, p.x, p.y + height / 2.0, p.z, 40, 0.8, 0.25);
            Fx.burst(mc, ParticleTypes.END_ROD, p.x, p.y + height / 2.0, p.z, 12, 0.6, 0.15);
            Fx.sound(mc, SoundEvents.EVOKER_PREPARE_ATTACK, p.x, p.y, p.z, 1.5F, 1.0F);
        }

        if (tick < buildup) {
            double progress = (double) tick / buildup;
            double radius = 2.2 * (1.0 - progress) + 0.25;
            double y = p.y + 0.2 + progress * (height + 0.8);
            for (int i = 0; i < 4; i++) {
                double angle = Fx.RANDOM.nextDouble() * Math.PI * 2.0;
                double x = p.x + Math.cos(angle) * radius;
                double z = p.z + Math.sin(angle) * radius;
                Fx.particle(mc, ParticleTypes.WITCH, x, y, z, 0.0, 0.02, 0.0);
            }
            if (progress > 0.5) {
                for (int i = 0; i < 2; i++) {
                    double ox = (Fx.RANDOM.nextDouble() - 0.5) * 0.8;
                    double oz = (Fx.RANDOM.nextDouble() - 0.5) * 0.8;
                    Fx.particle(mc, ParticleTypes.ELECTRIC_SPARK, p.x + ox, p.y + height + 0.6 + Fx.RANDOM.nextDouble() * 0.3,
                            p.z + oz, 0.0, 0.0, 0.0);
                }
            }
        } else if (tick == buildup) {
            // Этап 3: исполнение приговора
            execute(mc, p, height);
        } else if (tick == buildup + Config.JUDGMENT_IMPACT_DELAY_TICKS) {
            Fx.sound(mc, SoundEvents.LIGHTNING_BOLT_IMPACT, p.x, p.y, p.z, Config.IMPACT_VOLUME, 1.0F);
        }

        if (tick >= buildup && tick < buildup + Config.RING_WAVE_TICKS) {
            // Кольцо частиц расходится по земле от точки удара
            double r = (tick - buildup + 1) * 0.55;
            int n = Math.max(12, (int) (r * 12.0));
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2.0 * i / n;
                Fx.particle(mc, i % 2 == 0 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.WITCH,
                        p.x + Math.cos(a) * r, p.y + 0.1, p.z + Math.sin(a) * r,
                        Math.cos(a) * 0.08, 0.01, Math.sin(a) * 0.08);
            }
        }

        if (tick > buildup && tick <= buildup + Config.JUDGMENT_AFTERGLOW_TICKS && tick % 2 == 0) {
            // Этап 3, конец: несколько затухающих фиолетовых эффектов
            double fade = 1.0 - (double) (tick - buildup) / Config.JUDGMENT_AFTERGLOW_TICKS;
            int count = (int) Math.round(4.0 * fade);
            for (int i = 0; i < count; i++) {
                double ox = (Fx.RANDOM.nextDouble() - 0.5) * 1.2;
                double oz = (Fx.RANDOM.nextDouble() - 0.5) * 1.2;
                Fx.particle(mc, ParticleTypes.WITCH, p.x + ox, p.y + 0.2 + Fx.RANDOM.nextDouble() * height, p.z + oz,
                        0.0, 0.03, 0.0);
            }
        }

        tick++;
        if (tick > buildup + Config.JUDGMENT_AFTERGLOW_TICKS) {
            // Этап 4: завершение: метка, контур и все частицы прекращаются
            reset();
        }
    }

    // Клеймо приговора. До удара печать растёт и вращается, после удара рассыпается.
    private static void tickSeal(Minecraft mc, Vec3 p, double height, int buildup) {
        boolean crumbling = tick > buildup;
        double fade = crumbling
                ? Math.max(0.0, 1.0 - (double) (tick - buildup) / Config.JUDGMENT_AFTERGLOW_TICKS)
                : 1.0;
        double speed;
        if (crumbling) {
            speed = Config.CIRCLE_SPIN_FAST * (0.4 + 0.6 * fade);
        } else if (tick >= buildup - Config.SEAL_FAST_SPIN_TICKS) {
            speed = Config.CIRCLE_SPIN_FAST;
        } else {
            speed = Config.CIRCLE_SPIN_SLOW;
        }
        sealAngle += speed;

        // Магический круг под ногами
        double form = Math.min(1.0, (tick + 1.0) / Config.CIRCLE_FORM_TICKS);
        double radius = Config.CIRCLE_RADIUS * form;
        int arms = crumbling ? (int) Math.ceil(4.0 * fade) : 4;
        for (int k = 0; k < arms; k++) {
            double a = sealAngle + k * Math.PI / 2.0;
            Fx.particle(mc, ParticleTypes.WITCH, p.x + Math.cos(a) * radius, p.y + 0.05, p.z + Math.sin(a) * radius,
                    0.0, 0.01, 0.0);
            double a2 = -sealAngle * 1.3 + k * Math.PI / 2.0;
            Fx.particle(mc, ParticleTypes.END_ROD, p.x + Math.cos(a2) * radius * 0.6, p.y + 0.05,
                    p.z + Math.sin(a2) * radius * 0.6, 0.0, 0.0, 0.0);
        }
        if (crumbling) {
            int crumbs = (int) Math.round(4.0 * fade);
            for (int i = 0; i < crumbs; i++) {
                double a = Fx.RANDOM.nextDouble() * Math.PI * 2.0;
                double r = Config.CIRCLE_RADIUS * (0.3 + 0.7 * Fx.RANDOM.nextDouble());
                Fx.particle(mc, ParticleTypes.SMOKE, p.x + Math.cos(a) * r, p.y + 0.1, p.z + Math.sin(a) * r,
                        Math.cos(a) * 0.03, 0.04, Math.sin(a) * 0.03);
            }
        }

        // Тонкие вертикальные потоки частиц от круга вверх
        if (tick >= Config.CIRCLE_FORM_TICKS / 2 && fade > 0.0) {
            int columns = 6;
            for (int i = 0; i < columns; i++) {
                if (crumbling && Fx.RANDOM.nextDouble() > fade) {
                    continue;
                }
                double a = sealAngle * 0.25 + Math.PI * 2.0 * i / columns;
                double r = radius * 0.55;
                Fx.particle(mc, i % 2 == 0 ? ParticleTypes.END_ROD : ParticleTypes.WITCH,
                        p.x + Math.cos(a) * r, p.y + 0.1, p.z + Math.sin(a) * r, 0.0, 0.16, 0.0);
            }
        }

        // Метка над головой превращается в церковную печать
        double hy = p.y + height + Config.SEAL_HEIGHT_OFFSET;
        if (!crumbling) {
            if (tick < 10) {
                for (int i = 0; i < 3; i++) {
                    double a = sealAngle * 2.0 + i * Math.PI * 2.0 / 3.0;
                    Fx.particle(mc, ParticleTypes.WITCH, p.x + Math.cos(a) * 0.12, hy, p.z + Math.sin(a) * 0.12, 0.0, 0.0, 0.0);
                }
                Fx.particle(mc, ParticleTypes.END_ROD, p.x, hy, p.z, 0.0, 0.0, 0.0);
            } else {
                double f = Math.min(1.0, (tick - 10) / (double) (Config.SEAL_FORM_TICKS - 10));
                double r = 0.12 + (Config.SEAL_MAX_RADIUS - 0.12) * f;
                double rot = sealAngle * 0.5;
                int points = 10;
                for (int i = 0; i < points; i++) {
                    double a = rot + Math.PI * 2.0 * i / points;
                    Fx.particle(mc, i % 2 == 0 ? ParticleTypes.WITCH : ParticleTypes.END_ROD,
                            p.x + Math.cos(a) * r, hy, p.z + Math.sin(a) * r, 0.0, 0.0, 0.0);
                }
                for (int s = 0; s < 4; s++) {
                    double a = rot + s * Math.PI / 2.0;
                    for (double d = r * 0.25; d <= r * f; d += r * 0.25) {
                        Fx.particle(mc, ParticleTypes.END_ROD, p.x + Math.cos(a) * d, hy, p.z + Math.sin(a) * d, 0.0, 0.0, 0.0);
                    }
                }
                if (f > 0.6) {
                    for (int i = 0; i < 6; i++) {
                        double a = -rot * 1.5 + Math.PI * 2.0 * i / 6.0;
                        Fx.particle(mc, ParticleTypes.WITCH, p.x + Math.cos(a) * r * 0.5, hy, p.z + Math.sin(a) * r * 0.5,
                                0.0, 0.0, 0.0);
                    }
                }
            }
        } else {
            int n = (int) Math.round(8.0 * fade);
            for (int i = 0; i < n; i++) {
                double a = Fx.RANDOM.nextDouble() * Math.PI * 2.0;
                double r = Config.SEAL_MAX_RADIUS * (0.5 + 0.5 * Fx.RANDOM.nextDouble());
                Fx.particle(mc, Fx.RANDOM.nextBoolean() ? ParticleTypes.WITCH : ParticleTypes.END_ROD,
                        p.x + Math.cos(a) * r, hy, p.z + Math.sin(a) * r,
                        Math.cos(a) * 0.04, -0.03 - Fx.RANDOM.nextDouble() * 0.03, Math.sin(a) * 0.04);
            }
        }

        // Перед ударом вокруг цели вспыхивают electric_spark и witch
        if (tick >= buildup - Config.SEAL_FAST_SPIN_TICKS && tick < buildup) {
            for (int i = 0; i < 3; i++) {
                double a = Fx.RANDOM.nextDouble() * Math.PI * 2.0;
                double r = 0.5 + Fx.RANDOM.nextDouble() * 0.5;
                double y = p.y + Fx.RANDOM.nextDouble() * height;
                Fx.particle(mc, ParticleTypes.ELECTRIC_SPARK, p.x + Math.cos(a) * r, y, p.z + Math.sin(a) * r, 0.0, 0.0, 0.0);
                Fx.particle(mc, ParticleTypes.WITCH, p.x - Math.cos(a) * r, y, p.z - Math.sin(a) * r, 0.0, 0.02, 0.0);
            }
        }
    }

    // Столб молнии формируется сверху вниз за несколько тиков до удара
    private static void tickDescent(Minecraft mc, Vec3 p, int buildup) {
        int steps = Config.JUDGMENT_DESCENT_TICKS;
        int step = tick - (buildup - steps);
        double top = Config.JUDGMENT_BOLT_HEIGHT;
        double upper = top * (1.0 - (double) step / steps);
        double lower = top * (1.0 - (double) (step + 1) / steps);
        for (double y = lower; y <= upper; y += 0.35) {
            double ox = (Fx.RANDOM.nextDouble() - 0.5) * 0.3;
            double oz = (Fx.RANDOM.nextDouble() - 0.5) * 0.3;
            Fx.particle(mc, ParticleTypes.END_ROD, p.x + ox * 0.5, p.y + y, p.z + oz * 0.5, 0.0, -0.02, 0.0);
            if (Fx.RANDOM.nextBoolean()) {
                Fx.particle(mc, ParticleTypes.ELECTRIC_SPARK, p.x + ox, p.y + y, p.z + oz, 0.0, 0.0, 0.0);
            }
        }
        Fx.burst(mc, ParticleTypes.WITCH, p.x, p.y + lower, p.z, 6, 0.4, 0.08);
    }

    private static void execute(Minecraft mc, Vec3 p, double height) {
        OverlayHud.clearDim();
        // Молния попала: цепи и обездвиживание только если поражён игрок
        DivineChains.strike(mc, target instanceof Player hit && !target.isRemoved() ? hit : null);
        Fx.sound(mc, SoundEvents.LIGHTNING_BOLT_THUNDER, p.x, p.y, p.z, Config.THUNDER_VOLUME, 1.0F);
        OverlayHud.flash(Config.FLASH_RGB);
        ScreenShake.kick(Config.JUDGMENT_SHAKE, Config.JUDGMENT_SHAKE_TICKS);

        for (double y = 0.0; y <= Config.JUDGMENT_BOLT_HEIGHT; y += 0.5) {
            double ox = (Fx.RANDOM.nextDouble() - 0.5) * 0.3;
            double oz = (Fx.RANDOM.nextDouble() - 0.5) * 0.3;
            Fx.particle(mc, ParticleTypes.ELECTRIC_SPARK, p.x + ox, p.y + y, p.z + oz, 0.0, 0.0, 0.0);
            Fx.particle(mc, ParticleTypes.END_ROD, p.x + ox * 0.5, p.y + y, p.z + oz * 0.5, 0.0, -0.1, 0.0);
        }
        Fx.burst(mc, ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 0.2, p.z, 40, 0.3, 0.5);
        Fx.burst(mc, ParticleTypes.WITCH, p.x, p.y + height / 2.0, p.z, 40, 0.6, 0.35);
        Fx.burst(mc, ParticleTypes.END_ROD, p.x, p.y + height / 2.0, p.z, 20, 0.4, 0.25);
    }
}
