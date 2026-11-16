package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Постоянная аура обладателей Власти Божьей: только клиентские частицы вокруг игрока, без сущностей и без изменения
 * его модели. Обладатель определяется по мечу Kōru yōna samu-sa в основной руке (его видят все клиенты), а также
 * по тому, кто сейчас применяет способность. Частицы рисует сама игра с проверкой глубины, поэтому сквозь стены
 * аура не видна; кроме того, для игроков вне прямой видимости частицы вообще не создаются.
 * При применении способности аура усиливается и затем плавно возвращается к обычному состоянию.
 */
public final class DivineAura {
    private DivineAura() {
    }

    private static final double TAU = Math.PI * 2.0;

    private static final class State {
        float boost;        // 0..1: усиление при применении способности
        boolean visible = true;
        boolean seen;
    }

    private static final Map<UUID, State> states = new HashMap<>();
    private static int clock = 0;

    public static void reset() {
        states.clear();
        clock = 0;
    }

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        clock++;
        UUID owner = DivineJudgment.activeOwner();
        double rangeSqr = Config.AURA_RANGE * Config.AURA_RANGE;

        for (State state : states.values()) {
            state.seen = false;
        }
        List<Player> holders = new ArrayList<>();
        for (Player player : mc.level.players()) {
            if (player.isSpectator() || !player.isAlive() || player.distanceToSqr(mc.player) > rangeSqr) {
                continue;
            }
            State known = states.get(player.getUUID());
            boolean power = CultIllusionClient.isPowerHolder(player)
                    || player.getUUID().equals(owner)
                    || (known != null && known.boost > 0.0F);
            if (power) {
                holders.add(player);
            }
        }
        holders.sort(Comparator.comparingDouble(player -> player.distanceToSqr(mc.player)));

        for (int i = 0; i < holders.size() && i < Config.AURA_MAX_HOLDERS; i++) {
            Player player = holders.get(i);
            State state = states.computeIfAbsent(player.getUUID(), id -> new State());
            state.seen = true;
            boolean boosted = player.getUUID().equals(owner);
            if (boosted) {
                state.boost = Math.min(1.0F, state.boost + 1.0F / Config.AURA_BOOST_RISE_TICKS);
            } else {
                state.boost = Math.max(0.0F, state.boost - 1.0F / Config.AURA_BOOST_FALL_TICKS);
            }
            if (player == mc.player) {
                state.visible = true;
            } else if ((clock + (player.getUUID().hashCode() & 0xFF)) % Config.AURA_SIGHT_INTERVAL_TICKS == 0) {
                state.visible = Visibility.canSee(mc, mc.player.getEyePosition(), player, mc.player);
            }
            if (state.visible) {
                emit(mc, player, state, player == mc.player && mc.options.getCameraType().isFirstPerson());
            }
        }

        Iterator<State> iterator = states.values().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().seen) {
                iterator.remove();
            }
        }
    }

    // Случайное количество с заданным средним
    private static int roll(double expected) {
        int whole = (int) Math.floor(expected);
        return whole + (Fx.RANDOM.nextDouble() < expected - whole ? 1 : 0);
    }

    private static void emit(Minecraft mc, Player p, State state, boolean firstPersonSelf) {
        double boost = state.boost;
        // От первого лица вокруг камеры остаётся только нижняя, малозаметная часть
        double density = (0.6 + 0.9 * boost) * (firstPersonSelf ? 0.4 : 1.0);
        double maxY = firstPersonSelf ? 1.0 : p.getBbHeight();
        double x = p.getX();
        double y = p.getY();
        double z = p.getZ();
        double yaw = Math.toRadians(p.yBodyRot);
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        int phase = p.getUUID().hashCode() & 0xFF;

        // Медленно циркулирующие вокруг тела частицы
        int swirl = roll(1.3 * density);
        for (int i = 0; i < swirl; i++) {
            double a = Fx.RANDOM.nextDouble() * TAU;
            double r = 0.5 + Fx.RANDOM.nextDouble() * 0.25;
            double height = Fx.RANDOM.nextDouble() * maxY;
            double speed = 0.03 * (1.0 + 1.5 * boost);
            Fx.particle(mc, Fx.RANDOM.nextInt(10) < 7 ? PowerFx.dust(Config.AURA_RGB, 0.8F) : ParticleTypes.WITCH,
                    x + Math.cos(a) * r, y + height, z + Math.sin(a) * r,
                    -Math.sin(a) * speed, 0.004, Math.cos(a) * speed);
        }

        // Небольшие скопления частиц у ног
        int footInterval = boost > 0.5 ? 4 : 8;
        if ((clock + phase) % footInterval == 0) {
            int count = roll((4.0 + 4.0 * boost) * (firstPersonSelf ? 0.5 : 1.0));
            for (int i = 0; i < count; i++) {
                double a = Fx.RANDOM.nextDouble() * TAU;
                double r = Fx.RANDOM.nextDouble() * 0.45;
                Fx.particle(mc, PowerFx.dust(Config.AURA_RGB, 0.9F), x + Math.cos(a) * r, y + 0.05, z + Math.sin(a) * r,
                        0.0, 0.03, 0.0);
            }
        }

        // Редкие вертикальные потоки вдоль тела
        if (Fx.RANDOM.nextDouble() < 0.10 * density) {
            double a = Fx.RANDOM.nextDouble() * TAU;
            for (int k = 0; k < 5; k++) {
                double height = 0.2 + k * 0.35;
                if (height > maxY) {
                    break;
                }
                Fx.particle(mc, PowerFx.dust(Config.AURA_RGB, 0.7F), x + Math.cos(a) * 0.45, y + height, z + Math.sin(a) * 0.45,
                        0.0, 0.06 + 0.04 * boost, 0.0);
            }
        }

        if (!firstPersonSelf) {
            // Небольшие вспышки у плеч и верхней части тела; при усилении ярче и бело-фиолетовые
            if (Fx.RANDOM.nextDouble() < 0.04 * density) {
                double side = Fx.RANDOM.nextBoolean() ? 0.4 : -0.4;
                double height = p.getBbHeight() * 0.82;
                Fx.particle(mc, boost > 0.3 && Fx.RANDOM.nextBoolean() ? PowerFx.dust(Config.AURA_FLASH_RGB, 0.9F) : ParticleTypes.END_ROD,
                        x + side * cos, y + height, z + side * sin, 0.0, 0.0, 0.0);
            }
        }

        // Короткие электрические разряды: чаще и заметнее при применении способности
        if (Fx.RANDOM.nextDouble() < (0.03 + 0.12 * boost) * (firstPersonSelf ? 0.4 : 1.0)) {
            int sparks = 2 + (boost > 0.5 ? 1 : 0);
            for (int i = 0; i < sparks; i++) {
                double a = Fx.RANDOM.nextDouble() * TAU;
                double r = 0.4 + Fx.RANDOM.nextDouble() * 0.3;
                Fx.particle(mc, ParticleTypes.ELECTRIC_SPARK, x + Math.cos(a) * r, y + Fx.RANDOM.nextDouble() * maxY,
                        z + Math.sin(a) * r, 0.0, 0.0, 0.0);
            }
        }
    }
}
