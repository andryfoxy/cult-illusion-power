package cult.illusion;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Разум Божий, «Иллюзорные двойники». Двойники — это не сущности: модель игрока, активировавшего способность,
 * дополнительно отрисовывается в нескольких точках, у них нет ни хитбоксов, ни физики. Атаки двойников и ложные
 * образы тоже только рисуются: на сервер ничего не отправляется.
 * Одновременно могут идти несколько способностей разных игроков, каждая в своём экземпляре.
 */
public final class MirrorDoubles {
    private MirrorDoubles() {
    }

    private static final int MODE_WANDER = 0;
    private static final int MODE_APPROACH = 1;
    private static final int MODE_RETREAT = 2;

    private static final class Clone {
        int index;
        double x;
        double y;
        double z;
        double prevX;
        double prevY;
        double prevZ;
        double heading;       // направление движения, в градусах как у yaw в Minecraft
        double prevHeading;
        double targetHeading;
        double speed;
        double targetSpeed;
        int behaviorTimer;
        int appearTick;
        boolean visible;
        boolean vanished;
        float alpha;          // 0..1: полупрозрачный силуэт становится полноценной копией и обратно
        int bias;             // склонность: приближаться к противнику или удаляться
        int mode;
        double aimOffset;     // индивидуальный разброс направления
        int attackCooldown;
        int swingTick = -1;   // идёт взмах оружия, тик взмаха
        double walkPos;
    }

    /** Отрисовка сохранённого образа в заданной точке. */
    private interface Drawer {
        void draw(LevelRenderContext context, EntityRenderDispatcher dispatcher, double dx, double dy, double dz, float alpha, int salt);
    }

    /** Ложный образ: след оригинала на прежнем месте. Не сущность, только сохранённая поза. */
    private static final class Afterimage {
        double x;
        double y;
        double z;
        int age;
        int salt;
        Drawer drawer;
    }

    private static final class Instance {
        UUID subject;
        Vec3 origin;
        int tick;
        Random random;
        Entity opponent;      // противник владельца (может быть null)
        int moveStartTick;    // движение начинается, когда проявилась последняя копия
        final List<Clone> clones = new ArrayList<>();
        final List<Afterimage> images = new ArrayList<>();
        boolean sampleValid;
        double sampleX;
        double sampleY;
        double sampleZ;
        double lastVx;
        double lastVz;
        double lastSpeed;
        int imageCooldown;
    }

    private static final List<Instance> instances = new ArrayList<>();

    public static boolean isActive(UUID subject) {
        for (Instance instance : instances) {
            if (instance.subject.equals(subject)) {
                return true;
            }
        }
        return false;
    }

    /** Оригинальная модель игрока скрыта только на короткое время в начале способности. */
    public static boolean isOriginalHidden(Entity entity) {
        for (Instance instance : instances) {
            if (instance.tick < Config.ORIGINAL_HIDDEN_TICKS && instance.subject.equals(entity.getUUID())) {
                return true;
            }
        }
        return false;
    }

    public static void reset() {
        instances.clear();
    }

    /** Вызывается после пятого удара; subject — игрок, активировавший способность, opponent — его противник или null. */
    public static void start(Minecraft mc, Player subject, int seed, Entity opponent) {
        if (subject == null || isActive(subject.getUUID())) {
            return;
        }
        Instance instance = new Instance();
        instance.subject = subject.getUUID();
        instance.origin = subject.position();
        instance.tick = 0;
        instance.random = new Random(seed);
        instance.opponent = opponent;

        double yaw = subject.getYRot();
        int count = Math.max(3, Math.min(4, Config.DOUBLE_COUNT));
        // Начальные поведения: вперёд, вправо, на месте, влево
        double[] headings = {yaw, yaw + 90.0, yaw, yaw - 90.0};
        double[] speeds = {Config.DOUBLE_SPEED_FAST, Config.DOUBLE_SPEED_SLOW, 0.0, Config.DOUBLE_SPEED_SLOW};
        for (int i = 0; i < count; i++) {
            Clone clone = new Clone();
            clone.index = i;
            double angle = Math.PI * 2.0 * i / count + Math.toRadians(yaw);
            clone.x = instance.origin.x + Math.cos(angle) * Config.DOUBLES_SPAWN_RADIUS;
            clone.z = instance.origin.z + Math.sin(angle) * Config.DOUBLES_SPAWN_RADIUS;
            clone.y = groundY(mc, clone.x, clone.z, instance.origin.y);
            clone.prevX = clone.x;
            clone.prevY = clone.y;
            clone.prevZ = clone.z;
            // Пока копия проявляется, она стоит лицом от центра, как будто расходится образ
            clone.heading = headings[i];
            clone.prevHeading = headings[i];
            clone.targetHeading = headings[i];
            clone.speed = speeds[i];
            clone.targetSpeed = speeds[i];
            clone.behaviorTimer = Config.DOUBLE_INITIAL_HOLD_TICKS;
            clone.appearTick = Config.DOUBLE_APPEAR_DELAY_TICKS + i * Config.DOUBLE_APPEAR_STAGGER_TICKS;
            clone.visible = false;
            clone.bias = i % 2 == 0 ? MODE_APPROACH : MODE_RETREAT;
            clone.mode = MODE_WANDER;
            clone.aimOffset = (instance.random.nextDouble() - 0.5) * 60.0;
            clone.attackCooldown = Config.DOUBLE_ATTACK_MIN_TICKS + instance.random.nextInt(Config.DOUBLE_ATTACK_RANDOM_TICKS);
            instance.clones.add(clone);
        }
        instance.moveStartTick = Config.DOUBLE_APPEAR_DELAY_TICKS + (count - 1) * Config.DOUBLE_APPEAR_STAGGER_TICKS
                + Config.DOUBLE_FADE_IN_TICKS;
        instances.add(instance);
        // Этап 2: разделение образа
        Fx.sound(mc, SoundEvents.ENDERMAN_TELEPORT, instance.origin.x, instance.origin.y, instance.origin.z, 1.0F, 1.0F);
    }

    public static void tick(Minecraft mc) {
        if (instances.isEmpty()) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        Iterator<Instance> iterator = instances.iterator();
        while (iterator.hasNext()) {
            Instance instance = iterator.next();
            tickInstance(mc, instance);
            boolean past = instance.tick > Config.DOUBLES_DURATION_TICKS;
            if (instance.tick > Config.DOUBLES_DURATION_TICKS + Config.DOUBLES_FINISH_TICKS || (past && allGone(instance))) {
                // Последняя копия исчезла: все визуальные эффекты прекращаются
                iterator.remove();
            }
        }
    }

    private static boolean allGone(Instance instance) {
        for (Clone clone : instance.clones) {
            if (!clone.vanished) {
                return false;
            }
        }
        return instance.images.isEmpty();
    }

    private static void tickInstance(Minecraft mc, Instance instance) {
        int t = instance.tick;
        Player subject = mc.level.getPlayerByUUID(instance.subject);

        // Частицы вокруг настоящего персонажа, пока оригинал скрыт
        if (t < Config.ORIGINAL_HIDDEN_TICKS && subject != null) {
            Vec3 p = subject.position();
            Fx.burst(mc, ParticleTypes.PORTAL, p.x, p.y + 1.0, p.z, 6, 1.0, 0.4);
            Fx.burst(mc, ParticleTypes.REVERSE_PORTAL, p.x, p.y + 1.0, p.z, 4, 0.8, 0.2);
        }

        boolean finishing = t >= Config.DOUBLES_DURATION_TICKS;

        if (t == Config.DOUBLES_DURATION_TICKS) {
            // Этап 5: двойники постепенно останавливаются
            for (Clone clone : instance.clones) {
                clone.targetSpeed = 0.0;
                clone.swingTick = -1;
            }
            Fx.sound(mc, SoundEvents.ENDERMAN_TELEPORT, instance.origin.x, instance.origin.y, instance.origin.z, 1.0F, 1.0F);
        }

        if (!finishing) {
            for (Clone clone : instance.clones) {
                if (!clone.visible && t >= clone.appearTick) {
                    clone.visible = true;
                    Fx.burst(mc, ParticleTypes.PORTAL, clone.x, clone.y + 1.0, clone.z, 15, 0.8, 0.4);
                    Fx.burst(mc, ParticleTypes.REVERSE_PORTAL, clone.x, clone.y + 1.0, clone.z, 10, 0.6, 0.2);
                }
                if (!clone.visible) {
                    continue;
                }
                // Этап 3: полупрозрачный силуэт постепенно становится полноценной копией
                float progress = Math.min(1.0F, (t - clone.appearTick) / (float) Config.DOUBLE_FADE_IN_TICKS);
                clone.alpha = Config.DOUBLE_GHOST_ALPHA_START + (1.0F - Config.DOUBLE_GHOST_ALPHA_START) * progress;
                if (t >= instance.moveStartTick) {
                    // Этап 4: независимое движение начинается, когда проявились все копии
                    move(mc, instance, clone);
                } else {
                    holdStill(clone);
                    if (progress < 1.0F && t % 4 == clone.index) {
                        Fx.burst(mc, ParticleTypes.REVERSE_PORTAL, clone.x, clone.y + 1.0, clone.z, 2, 0.5, 0.05);
                    }
                }
            }
            maintainIllusion(mc, instance);
            if (t >= Config.ORIGINAL_HIDDEN_TICKS && subject != null) {
                trackSubject(mc, instance, subject);
            }
        } else {
            finishClones(mc, instance);
        }
        tickImages(mc, instance, finishing);

        instance.tick++;
    }

    // Ложный образ: резкая смена направления или рывок оригинала оставляют на прежнем месте полупрозрачную копию
    private static void trackSubject(Minecraft mc, Instance instance, Player subject) {
        if (instance.tick % Config.AFTERIMAGE_SAMPLE_TICKS != 0) {
            return;
        }
        Vec3 pos = subject.position();
        if (instance.sampleValid) {
            double vx = (pos.x - instance.sampleX) / Config.AFTERIMAGE_SAMPLE_TICKS;
            double vz = (pos.z - instance.sampleZ) / Config.AFTERIMAGE_SAMPLE_TICKS;
            double speed = Math.sqrt(vx * vx + vz * vz);
            boolean sharp = false;
            if (instance.imageCooldown <= 0 && instance.images.size() < Config.AFTERIMAGE_MAX) {
                if (speed > Config.AFTERIMAGE_MIN_SPEED && instance.lastSpeed > Config.AFTERIMAGE_MIN_SPEED) {
                    double cos = (vx * instance.lastVx + vz * instance.lastVz) / (speed * instance.lastSpeed);
                    sharp = cos < Config.AFTERIMAGE_TURN_COS;
                }
                if (!sharp && speed - instance.lastSpeed > Config.AFTERIMAGE_JUMP_SPEED) {
                    sharp = true;
                }
                if (!sharp && speed > Config.AFTERIMAGE_TELEPORT_SPEED) {
                    sharp = true;
                }
            }
            if (sharp) {
                spawnAfterimage(mc, instance, subject, instance.sampleX, instance.sampleY, instance.sampleZ);
                instance.imageCooldown = Config.AFTERIMAGE_COOLDOWN_TICKS;
            }
            instance.lastVx = vx;
            instance.lastVz = vz;
            instance.lastSpeed = speed;
        }
        instance.sampleValid = true;
        instance.sampleX = pos.x;
        instance.sampleY = pos.y;
        instance.sampleZ = pos.z;
    }

    private static void spawnAfterimage(Minecraft mc, Instance instance, Player subject, double x, double y, double z) {
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // Поза и экипировка фиксируются в момент появления образа
        var state = mc.getEntityRenderDispatcher().extractEntity(subject, partial);
        Afterimage image = new Afterimage();
        image.x = x;
        image.y = y;
        image.z = z;
        image.age = 0;
        image.salt = instance.tick * 31 + instance.images.size() * 977;
        image.drawer = (context, dispatcher, dx, dy, dz, alpha, salt) -> {
            if (GhostRender.prepare(state, alpha, salt)) {
                dispatcher.submit(state, context.levelState().cameraRenderState, dx, dy, dz,
                        context.poseStack(), context.submitNodeCollector());
            }
        };
        instance.images.add(image);
    }

    private static void tickImages(Minecraft mc, Instance instance, boolean finishing) {
        if (instance.imageCooldown > 0) {
            instance.imageCooldown--;
        }
        Iterator<Afterimage> iterator = instance.images.iterator();
        while (iterator.hasNext()) {
            Afterimage image = iterator.next();
            image.age++;
            if (finishing || image.age >= Config.AFTERIMAGE_LIFETIME_TICKS) {
                // Образ растворяется в частицах reverse_portal
                Fx.burst(mc, ParticleTypes.REVERSE_PORTAL, image.x, image.y + 1.0, image.z, 14, 0.7, 0.2);
                iterator.remove();
                continue;
            }
            if (image.age % 6 == 0) {
                Fx.burst(mc, ParticleTypes.REVERSE_PORTAL, image.x, image.y + 1.0, image.z, 2, 0.5, 0.05);
            }
        }
    }

    // Исчезновение: копии останавливаются, становятся полупрозрачными и растворяются по очереди
    private static void finishClones(Minecraft mc, Instance instance) {
        int t = instance.tick;
        for (Clone clone : instance.clones) {
            if (clone.vanished || !clone.visible) {
                continue;
            }
            glide(mc, instance, clone);
            int fadeStart = Config.DOUBLES_DURATION_TICKS + Config.DOUBLE_FADE_DELAY_TICKS
                    + clone.index * Config.DOUBLE_VANISH_STAGGER_TICKS;
            if (t < fadeStart) {
                continue;
            }
            if (t == fadeStart) {
                Fx.burst(mc, ParticleTypes.POOF, clone.x, clone.y + 1.0, clone.z, 12, 0.7, 0.1);
                Fx.burst(mc, ParticleTypes.PORTAL, clone.x, clone.y + 1.0, clone.z, 14, 0.8, 0.4);
            }
            float progress = (t - fadeStart) / (float) Config.DOUBLE_FADE_OUT_TICKS;
            clone.alpha = Math.max(0.0F, 1.0F - progress);
            if (t % 3 == 0) {
                Fx.burst(mc, ParticleTypes.PORTAL, clone.x, clone.y + 1.0, clone.z, 3, 0.6, 0.2);
            }
            if (progress >= 1.0F) {
                clone.vanished = true;
                clone.visible = false;
                Fx.burst(mc, ParticleTypes.POOF, clone.x, clone.y + 1.0, clone.z, 10, 0.6, 0.1);
                Fx.burst(mc, ParticleTypes.PORTAL, clone.x, clone.y + 1.0, clone.z, 12, 0.7, 0.3);
            }
        }
    }

    // Этап 4: изредка частицы portal у копий и звук эндермена из позиции одной из них
    private static void maintainIllusion(Minecraft mc, Instance instance) {
        List<Clone> visible = new ArrayList<>();
        for (Clone clone : instance.clones) {
            if (clone.visible) {
                visible.add(clone);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        if (instance.tick % Config.DOUBLE_PARTICLE_INTERVAL_TICKS == 0) {
            Clone pick = visible.get(Fx.RANDOM.nextInt(visible.size()));
            Fx.burst(mc, ParticleTypes.PORTAL, pick.x, pick.y + 1.0, pick.z, 4, 0.6, 0.3);
        }
        if (instance.tick % Config.DOUBLE_AMBIENT_SOUND_INTERVAL_TICKS == Config.DOUBLE_AMBIENT_SOUND_INTERVAL_TICKS / 2) {
            Clone pick = visible.get(Fx.RANDOM.nextInt(visible.size()));
            Fx.sound(mc, SoundEvents.ENDERMAN_AMBIENT, pick.x, pick.y + 1.0, pick.z, Config.DOUBLE_AMBIENT_VOLUME, 1.0F);
        }
    }

    private static void holdStill(Clone clone) {
        clone.prevX = clone.x;
        clone.prevY = clone.y;
        clone.prevZ = clone.z;
        clone.prevHeading = clone.heading;
    }

    // Остановка в конце способности: скорость плавно падает до нуля
    private static void glide(Minecraft mc, Instance instance, Clone clone) {
        clone.prevX = clone.x;
        clone.prevY = clone.y;
        clone.prevZ = clone.z;
        clone.prevHeading = clone.heading;
        clone.speed += (0.0 - clone.speed) * 0.15;
        double radians = Math.toRadians(clone.heading);
        clone.x += -Math.sin(radians) * clone.speed;
        clone.z += Math.cos(radians) * clone.speed;
        double ground = groundY(mc, clone.x, clone.z, instance.origin.y);
        clone.y += (ground - clone.y) * 0.4;
        clone.walkPos += Math.min(1.0, clone.speed * 4.0) * 0.6;
    }

    private static void move(Minecraft mc, Instance instance, Clone clone) {
        clone.prevX = clone.x;
        clone.prevY = clone.y;
        clone.prevZ = clone.z;
        clone.prevHeading = clone.heading;

        tickAttack(mc, instance, clone);

        clone.behaviorTimer--;
        if (clone.behaviorTimer <= 0) {
            chooseBehavior(mc, instance, clone);
        }
        steer(instance, clone);
        if (clone.swingTick >= 0) {
            clone.targetSpeed = 0.0;
        }

        clone.heading = approach(clone.heading, clone.targetHeading, Config.DOUBLE_TURN_DEGREES_PER_TICK);
        clone.speed += (clone.targetSpeed - clone.speed) * 0.15;

        double radians = Math.toRadians(clone.heading);
        clone.x += -Math.sin(radians) * clone.speed;
        clone.z += Math.cos(radians) * clone.speed;

        // Не уходить слишком далеко от точки призыва
        double dx = instance.origin.x - clone.x;
        double dz = instance.origin.z - clone.z;
        if (dx * dx + dz * dz > Config.DOUBLES_MAX_RADIUS * Config.DOUBLES_MAX_RADIUS) {
            clone.targetHeading = Math.toDegrees(Math.atan2(-dx, dz));
            clone.targetSpeed = Config.DOUBLE_SPEED_SLOW;
            clone.behaviorTimer = 20;
            clone.mode = MODE_WANDER;
        }

        double ground = groundY(mc, clone.x, clone.z, instance.origin.y);
        clone.y += (ground - clone.y) * 0.4;
        clone.walkPos += Math.min(1.0, clone.speed * 4.0) * 0.6;
    }

    // Одни копии приближаются к противнику, другие удаляются; направление пересматривается редко, повороты плавные
    private static void steer(Instance instance, Clone clone) {
        Entity opponent = instance.opponent;
        if (clone.mode == MODE_WANDER || opponent == null || opponent.isRemoved()) {
            return;
        }
        if ((instance.tick + clone.index) % 8 != 0) {
            return;
        }
        double dx = opponent.getX() - clone.x;
        double dz = opponent.getZ() - clone.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        if (clone.mode == MODE_APPROACH) {
            clone.targetHeading = bearing + clone.aimOffset;
            clone.targetSpeed = distance > Config.DOUBLE_APPROACH_STOP_DISTANCE ? Config.DOUBLE_SPEED_SLOW : 0.0;
        } else {
            clone.targetHeading = bearing + 180.0 + clone.aimOffset * 0.5;
            clone.targetSpeed = Config.DOUBLE_SPEED_SLOW;
        }
    }

    // Иллюзорные атаки: только анимация взмаха, звук и частицы. Урона, хитбоксов и пакетов серверу нет.
    private static void tickAttack(Minecraft mc, Instance instance, Clone clone) {
        if (clone.swingTick >= 0) {
            clone.swingTick++;
            if (clone.swingTick == Config.DOUBLE_ATTACK_SWING_TICKS / 2) {
                Fx.sound(mc, SoundEvents.PLAYER_ATTACK_SWEEP, clone.x, clone.y + 1.0, clone.z, 0.9F, 0.8F);
            }
            if (clone.swingTick >= Config.DOUBLE_ATTACK_SWING_TICKS) {
                double radians = Math.toRadians(clone.heading);
                double fx = clone.x - Math.sin(radians) * 1.3;
                double fz = clone.z + Math.cos(radians) * 1.3;
                Fx.particle(mc, ParticleTypes.SWEEP_ATTACK, fx, clone.y + 1.1, fz, 0.0, 0.0, 0.0);
                Fx.burst(mc, ParticleTypes.WITCH, fx, clone.y + 1.0, fz, 6, 0.4, 0.12);
                clone.swingTick = -1;
            }
            return;
        }
        clone.attackCooldown--;
        if (clone.attackCooldown > 0) {
            return;
        }
        clone.attackCooldown = Config.DOUBLE_ATTACK_MIN_TICKS + instance.random.nextInt(Config.DOUBLE_ATTACK_RANDOM_TICKS);
        Entity opponent = instance.opponent;
        boolean hasOpponent = opponent != null && !opponent.isRemoved();
        double dx = hasOpponent ? opponent.getX() - clone.x : 0.0;
        double dz = hasOpponent ? opponent.getZ() - clone.z : 0.0;
        if (!hasOpponent || dx * dx + dz * dz <= Config.DOUBLE_ATTACK_RANGE * Config.DOUBLE_ATTACK_RANGE) {
            clone.swingTick = 0;
            if (hasOpponent) {
                clone.targetHeading = Math.toDegrees(Math.atan2(-dx, dz));
            }
        }
    }

    private static void chooseBehavior(Minecraft mc, Instance instance, Clone clone) {
        double roll = instance.random.nextDouble();
        clone.targetSpeed = roll < 0.25 ? 0.0 : (roll < 0.65 ? Config.DOUBLE_SPEED_SLOW : Config.DOUBLE_SPEED_FAST);
        double turn = (instance.random.nextDouble() - 0.5) * 200.0;
        clone.targetHeading = clone.heading + turn;
        clone.behaviorTimer = Config.DOUBLE_BEHAVIOR_MIN_TICKS + instance.random.nextInt(Config.DOUBLE_BEHAVIOR_RANDOM_TICKS);
        double modeRoll = instance.random.nextDouble();
        clone.mode = instance.opponent != null && modeRoll < 0.55 ? clone.bias : MODE_WANDER;
        if (Math.abs(turn) > 70.0 && clone.speed > 0.05) {
            // Короткий след при резкой смене направления
            Fx.burst(mc, ParticleTypes.REVERSE_PORTAL, clone.x, clone.y + 1.0, clone.z, 6, 0.4, 0.15);
        }
    }

    private static double approach(double current, double target, double maxStep) {
        double diff = Mth.wrapDegrees((float) (target - current));
        if (Math.abs(diff) <= maxStep) {
            return current + diff;
        }
        return current + Math.signum(diff) * maxStep;
    }

    private static double groundY(Minecraft mc, double x, double z, double baseY) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        int top = Mth.floor(baseY) + 2;
        for (int y = top; y >= top - 6; y--) {
            BlockPos pos = new BlockPos(blockX, y, blockZ);
            if (!mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) {
                return y + 1.0;
            }
        }
        return baseY;
    }

    /** Отрисовка копий модели игрока через штатный рендерер, без регистрации сущностей. */
    public static void render(LevelRenderContext context) {
        if (instances.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        PoseStack pose = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;

        for (Instance instance : instances) {
            Player subject = mc.level.getPlayerByUUID(instance.subject);
            if (subject != null) {
                for (Clone clone : instance.clones) {
                    if (!clone.visible || clone.vanished) {
                        continue;
                    }
                    double x = Mth.lerp(partial, clone.prevX, clone.x);
                    double y = Mth.lerp(partial, clone.prevY, clone.y);
                    double z = Mth.lerp(partial, clone.prevZ, clone.z);
                    // Состояние берётся заново для каждой копии: у каждой свой поворот, шаг и взмах
                    var state = dispatcher.extractEntity(subject, partial);
                    float yaw = Mth.rotLerp(partial, (float) clone.prevHeading, (float) clone.heading);
                    float walkSpeed = (float) Math.min(1.0, clone.speed * 4.0);
                    float swing = clone.swingTick >= 0
                            ? Math.min(1.0F, (clone.swingTick + partial) / Config.DOUBLE_ATTACK_SWING_TICKS)
                            : 0.0F;
                    GhostRender.pose(state, yaw, walkSpeed, (float) clone.walkPos, swing);
                    if (!GhostRender.prepare(state, clone.alpha, clone.index * 7919 + 13)) {
                        continue;
                    }
                    dispatcher.submit(state, context.levelState().cameraRenderState,
                            x - camera.x, y - camera.y, z - camera.z, pose, context.submitNodeCollector());
                }
            }
            for (Afterimage image : instance.images) {
                float life = Config.AFTERIMAGE_LIFETIME_TICKS;
                float alpha = Config.AFTERIMAGE_ALPHA * Math.min(1.0F, (life - image.age) / (life * 0.4F));
                if (alpha <= 0.0F) {
                    continue;
                }
                image.drawer.draw(context, dispatcher, image.x - camera.x, image.y - camera.y, image.z - camera.z,
                        alpha, image.salt);
            }
        }
    }
}
