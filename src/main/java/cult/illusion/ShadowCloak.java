package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Тень божья (клинок Haikyo de no Seikatsu): персонаж растворяется в земле, 20 секунд невидим и возвращается.
 *
 * Хронология от пятого сигнала (тики): подъём чёрного облака от земли до головы (SHADOW_RISE_TICKS) -> персонаж
 * исчезает -> частицы оседают в землю (SHADOW_SETTLE_TICKS) -> невидимость SHADOW_HIDDEN_TICKS (20 секунд от конца
 * исчезновения), следы только у земли и только при движении -> тот же подъём облака, теперь снизу вверх вокруг
 * невидимого персонажа -> персонаж снова виден -> частицы оседают в землю.
 * Пока модель скрыта, на её месте остаётся непрерывное облако тех же чёрных частиц (для других игроков).
 *
 * Как и остальные способности мода, всё на клиенте: каждый клиент, получивший метку активации, ведёт свой таймер
 * и скрывает отрисовку этого игрока (EntityRendererMixin). Сущность не создаётся, координаты не меняются.
 */
public final class ShadowCloak {
    private ShadowCloak() {
    }

    private static final class Instance {
        UUID id;
        int tick;
        Vec3 last;
        double walked;
    }

    private static final int HIDE_AT = Config.SHADOW_RISE_TICKS;
    private static final int REFORM_AT = HIDE_AT + Config.SHADOW_HIDDEN_TICKS;
    private static final int SHOW_AT = REFORM_AT + Config.SHADOW_RISE_TICKS;
    private static final int END_AT = SHOW_AT + Config.SHADOW_SETTLE_TICKS;

    private static final Map<UUID, Instance> instances = new HashMap<>();
    private static Object boundLevel = null;

    public static boolean isActive(UUID id) {
        return instances.containsKey(id);
    }

    private static boolean invisible(Instance instance) {
        return instance.tick >= HIDE_AT && instance.tick < SHOW_AT;
    }

    /** Модель игрока (с бронёй, предметами и всем остальным) не рисуется для других игроков, пока он невидим. */
    public static boolean isHidden(Entity entity) {
        if (instances.isEmpty() || !(entity instanceof Player)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player) {
            return false; // себя владелец продолжает видеть, невидим он для остальных
        }
        Instance instance = instances.get(entity.getUUID());
        return instance != null && invisible(instance);
    }

    /** Пока Тень невидима, она не может атаковать других игроков. */
    public static boolean blocksAttack(Player attacker, Entity victim) {
        Minecraft mc = Minecraft.getInstance();
        if (instances.isEmpty() || attacker != mc.player || !(victim instanceof Player)) {
            return false;
        }
        Instance instance = instances.get(attacker.getUUID());
        return instance != null && invisible(instance);
    }

    public static void reset() {
        instances.clear();
        boundLevel = null;
    }

    /** Вызывается в момент пятого сигнала; повторный запуск для того же игрока игнорируется. */
    public static void start(Minecraft mc, Player subject) {
        if (subject == null || instances.containsKey(subject.getUUID())) {
            return;
        }
        if (instances.isEmpty()) {
            boundLevel = mc.level;
        }
        Instance instance = new Instance();
        instance.id = subject.getUUID();
        instance.tick = 0;
        instance.last = subject.position();
        instances.put(instance.id, instance);
    }

    public static void tick(Minecraft mc) {
        if (instances.isEmpty()) {
            return;
        }
        // Смена мира, выход с сервера: состояние сбрасывается, видимость возвращается сама (скрытие зависит только от таймера)
        if (mc.player == null || mc.level == null || mc.level != boundLevel) {
            reset();
            return;
        }
        List<UUID> finished = new ArrayList<>();
        for (Instance instance : instances.values()) {
            Player player = mc.level.getPlayerByUUID(instance.id);
            if (instance.tick >= END_AT || (player != null && (!player.isAlive() || player.isDeadOrDying()))) {
                finished.add(instance.id); // время вышло или игрок погиб: невидимость и частицы снимаются сразу
                continue;
            }
            if (player != null && mc.player.distanceToSqr(player) <= Config.SHADOW_PARTICLE_DISTANCE * Config.SHADOW_PARTICLE_DISTANCE) {
                effects(mc, instance, player);
            }
            instance.tick++;
        }
        for (UUID id : finished) {
            instances.remove(id);
        }
    }

    private static void effects(Minecraft mc, Instance instance, Player player) {
        int t = instance.tick;
        int rise = Config.SHADOW_RISE_TICKS;
        int settle = Config.SHADOW_SETTLE_TICKS;
        if (t < HIDE_AT) {
            cloud(mc, player, (t + 1) / (double) rise, true);                 // растворение: облако поднимается
        } else if (t < HIDE_AT + settle) {
            cloud(mc, player, (t - HIDE_AT + 1) / (double) settle, false);    // частицы оседают в землю
        } else if (t < REFORM_AT) {
            // Модель скрыта, но на её месте остаётся то же чёрное облако (полная высота, параметры подъёма): присутствие Тени
            // не обрывается. Владелец своё облако не видит, он продолжает видеть себя. Эффект только визуальный.
            if (player != mc.player) {
                cloud(mc, player, 1.0, true);
            }
        } else if (t >= REFORM_AT && t < SHOW_AT) {
            cloud(mc, player, (t - REFORM_AT + 1) / (double) rise, true);     // возвращение: то же облако снизу вверх
        } else if (t >= SHOW_AT) {
            cloud(mc, player, (t - SHOW_AT + 1) / (double) settle, false);
        }
        if (t >= HIDE_AT && t < REFORM_AT) {
            trail(mc, instance, player);
        }
    }

    // Облако вокруг игрока. rising: покрытая высота растёт от ног к голове; иначе она убывает к земле, частицы летят вниз
    private static void cloud(Minecraft mc, Player player, double progress, boolean rising) {
        double height = player.getBbHeight() + 0.15;
        double covered = rising ? height * Math.min(1.0, progress) : height * Math.max(0.0, 1.0 - progress);
        double density = rising ? 1.0 : Math.max(0.15, 1.0 - progress);
        double vy = rising ? 0.035 : -0.04;
        Vec3 base = player.position();
        ParticleOptions dust = PowerFx.dust(Config.SHADOW_BLACK_RGB, Config.SHADOW_DUST_SCALE);
        for (int i = 0; i < Math.max(1, Math.round(Config.SHADOW_DUST_PER_TICK * density)); i++) {
            spawn(mc, dust, base, covered, height, vy, 1.0);
        }
        for (int i = 0; i < Math.round(Config.SHADOW_LARGE_SMOKE_PER_TICK * density); i++) {
            spawn(mc, ParticleTypes.LARGE_SMOKE, base, covered, height, vy * 0.6, 1.1); // объём облака
        }
        for (int i = 0; i < Math.round(Config.SHADOW_SMOKE_PER_TICK * density); i++) {
            spawn(mc, ParticleTypes.SMOKE, base, covered, height, vy * 0.8, 1.0);
        }
        for (int i = 0; i < Math.round(Config.SHADOW_ASH_PER_TICK * density); i++) {
            spawn(mc, ParticleTypes.ASH, base, covered, height, vy * 0.5, 1.2);
        }
    }

    // Частица в пределах покрытой высоты: у земли облако шире, к голове уже, лёгкая закрутка вокруг тела
    private static void spawn(Minecraft mc, ParticleOptions type, Vec3 base, double covered, double height, double vy, double spread) {
        double y = covered * Math.sqrt(Fx.RANDOM.nextDouble());
        double angle = Fx.RANDOM.nextDouble() * Math.PI * 2.0;
        double radius = Config.SHADOW_CLOUD_RADIUS * (0.5 + 0.5 * Fx.RANDOM.nextDouble()) * (1.25 - 0.55 * Math.min(1.0, y / height)) * spread;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        Fx.particle(mc, type, base.x + cos * radius, base.y + y + 0.02, base.z + sin * radius,
                -sin * 0.012 - cos * 0.008, vy * (0.6 + 0.8 * Fx.RANDOM.nextDouble()), cos * 0.012 - sin * 0.008);
    }

    // Следы: небольшое скопление у земли раз в SHADOW_TRAIL_STEP блоков пути; стоящий на месте или летящий игрок следов не оставляет
    private static void trail(Minecraft mc, Instance instance, Player player) {
        Vec3 pos = player.position();
        double dx = pos.x - instance.last.x;
        double dz = pos.z - instance.last.z;
        instance.last = pos;
        if (!player.onGround()) {
            return;
        }
        instance.walked += Math.sqrt(dx * dx + dz * dz);
        if (instance.walked < Config.SHADOW_TRAIL_STEP) {
            return;
        }
        instance.walked = 0.0;
        double maxDistance = Config.SHADOW_TRAIL_DISTANCE;
        if (mc.player.distanceToSqr(player) > maxDistance * maxDistance) {
            return;
        }
        ParticleOptions dust = PowerFx.dust(Config.SHADOW_BLACK_RGB, Config.SHADOW_TRAIL_DUST_SCALE);
        for (int i = 0; i < 3; i++) {
            Fx.particle(mc, dust, pos.x + (Fx.RANDOM.nextDouble() - 0.5) * 0.5, pos.y + 0.05, pos.z + (Fx.RANDOM.nextDouble() - 0.5) * 0.5, 0.0, 0.004, 0.0);
        }
        Fx.particle(mc, ParticleTypes.SMOKE, pos.x + (Fx.RANDOM.nextDouble() - 0.5) * 0.4, pos.y + 0.05, pos.z + (Fx.RANDOM.nextDouble() - 0.5) * 0.4, 0.0, 0.006, 0.0);
        Fx.particle(mc, ParticleTypes.ASH, pos.x + (Fx.RANDOM.nextDouble() - 0.5) * 0.4, pos.y + 0.08, pos.z + (Fx.RANDOM.nextDouble() - 0.5) * 0.4, 0.0, 0.003, 0.0);
    }
}
