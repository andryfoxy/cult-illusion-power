package cult.illusion;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Постоянный ритуал Безымянного (клавиша K по умолчанию). Дополняет церемонию стадий (BladeCeremony, клавиша J) и
 * полностью от неё не зависит: стадии J накладываются поверх ритуала и не перезапускают его, повторное K завершает
 * только ритуал, а уже идущая 15-секундная стадия заканчивается сама.
 *
 * Состояния: STARTING (формирование, ~3,5 с) -> ACTIVE (до повторного нажатия, по времени не заканчивается) ->
 * ENDING (схлопывание, ~3,5 с) -> состояние удаляется. Нажатия во время переходов игнорируются.
 *
 * Клиентский мод, серверной части нет. Остальным игрокам с модом в радиусе 32 блоков начало и завершение передаются
 * скрытыми метками в чате (отдельные от меток способностей и стадий J). Метка несёт seed и 32-битный идентификатор
 * сессии; по нему повторы отбрасываются, а завершение связывается с конкретным ритуалом. Вращение геометрии и
 * расписание аномалий вычисляются только из seed и возраста ритуала, поэтому все наблюдатели видят одну композицию.
 * Центр ритуала: ближайший стенд для брони с именем «Ритуал» в радиусе 16 блоков от инициатора в момент запуска
 * (клиентский поиск без новых сущностей и блоков: стенд остаётся обычной сущностью мира). Без такого стенда
 * центром служит сам инициатор, и вся композиция следует за ним. Ритуал занимает область радиусом до 16 блоков вокруг
 * центра; ритуалы разных инициаторов независимы.
 * Смерть, смена измерения, выход из мира и потеря инициатора сразу очищают эффекты и звуки.
 */
public final class RitualAura {
    private RitualAura() {
    }

    static final int STARTING = 0;
    static final int ACTIVE = 1;
    static final int ENDING = 2;
    static final int START_TICKS = 70;     // ~3,5 с
    static final int END_TICKS = 70;       // ~3,5 с
    private static final int LOST_TICKS = 20;
    private static final double FX_RANGE = 48.0;

    /** Пространственная аномалия (общее для всех наблюдателей расписание). */
    static final class Anomaly {
        final int start;
        final int type;       // 0 = волны, 1 = разрыв, 2 = ускорение вращения, 3 = сходящиеся потоки
        final int life;
        final double angle;
        final double distance;
        boolean soundDone = false;

        Anomaly(int start, int type, int life, double angle, double distance) {
            this.start = start;
            this.type = type;
            this.life = life;
            this.angle = angle;
            this.distance = distance;
        }
    }

    /** Один ритуал одного инициатора. */
    static final class Ritual {
        final UUID initiator;
        final long session;
        final int seed;
        final boolean local;
        final Random fx = new Random();       // локальные случайности (джиттер частиц, шёпоты)
        final Random sched;                   // расписание аномалий: одинаково у всех наблюдателей
        final List<Anomaly> anomalies = new ArrayList<>();
        int state = STARTING;
        int age = 0;                          // тиков с начала ритуала
        int stateAge = 0;                     // тиков в текущем состоянии
        int lost = 0;
        double x;
        double y;
        double z;
        ArmorStand stand = null;              // именованный стенд для брони, задающий центр (если найден)
        double ax;                            // последняя известная позиция стенда
        double ay;
        double az;
        double viewerDistance = 0.0;
        int nextAnomaly;
        double settledBoost = 0.0;            // накопленное ускорение вращения от завершённых аномалий
        RitualSound.Hum hum = null;
        int nextWhisper = 0;
        int nextPulse = 0;
        int nextChime = 0;

        Ritual(UUID initiator, long session, int seed, boolean local) {
            this.initiator = initiator;
            this.session = session;
            this.seed = seed;
            this.local = local;
            this.sched = new Random(seed * 7919L + 13L);
            this.nextAnomaly = 90 + sched.nextInt(40);
        }

        boolean anchored() {
            return stand != null;
        }

        /** Дополнительный угол вращения от аномалий ускорения и от завершения: одинаков у всех наблюдателей. */
        double boost() {
            double sum = settledBoost;
            for (Anomaly a : anomalies) {
                if (a.type == 2) {
                    sum += 2.4 * smooth((age - a.start) / (double) a.life);
                }
            }
            if (state == ENDING) {
                double c = stateAge / (double) END_TICKS;
                sum += 7.0 * c * c; // вращение ускоряется при завершении
            }
            return sum;
        }

        void beginEnd() {
            if (state != ENDING) {
                state = ENDING;
                stateAge = 0;
            }
        }
    }

    static double smooth(double t) {
        double c = t < 0.0 ? 0.0 : Math.min(1.0, t);
        return c * c * (3.0 - 2.0 * c);
    }

    private static final Map<UUID, Ritual> ACTIVE_RITUALS = new HashMap<>();
    private static final Map<UUID, Long> LAST_SESSION = new HashMap<>();
    private static Object boundLevel = null;

    // Остановка звука ритуала
    private static void cleanup(Minecraft mc, Ritual r) {
        RitualSound.stop(mc, r);
    }

    /** Центр идущего ритуала инициатора (стенд «Ритуал» или сам инициатор) или null, если ритуала нет. */
    static double[] centerOf(UUID initiator) {
        Ritual r = ACTIVE_RITUALS.get(initiator);
        return r == null ? null : new double[]{r.x, r.y, r.z};
    }

    /** Центр идущего ритуала инициатора, если он привязан к стенду «Ритуал»; иначе null. */
    static double[] anchoredCenter(UUID initiator) {
        Ritual r = ACTIVE_RITUALS.get(initiator);
        return r != null && r.anchored() ? new double[]{r.x, r.y, r.z} : null;
    }

    public static boolean isActive(UUID initiator) {
        return ACTIVE_RITUALS.containsKey(initiator);
    }

    /** Выход из мира: все ритуалы и их звуки немедленно снимаются. */
    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        for (Ritual r : ACTIVE_RITUALS.values()) {
            cleanup(mc, r);
        }
        ACTIVE_RITUALS.clear();
        LAST_SESSION.clear();
        boundLevel = null;
    }

    // ---------------------------------------------------------------- запуск и завершение

    /** Нажатие K: запускает ритуал, а если он активен, запускает завершение. Во время переходов игнорируется. */
    public static void requestToggle(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.player.isDeadOrDying()) {
            return;
        }
        UUID self = mc.player.getUUID();
        Ritual current = ACTIVE_RITUALS.get(self);
        if (current == null) {
            int seed = Fx.RANDOM.nextInt(1 << 16);
            long session = Fx.RANDOM.nextInt() & 0xFFFFFFFFL;
            begin(mc, self, session, seed, true);
            CultIllusionClient.sendRitualMarker(mc, true, seed, session);
        } else if (current.state == ACTIVE) {
            current.beginEnd();
            CultIllusionClient.sendRitualMarker(mc, false, current.seed, current.session);
        }
    }

    /** Метка начала от другого игрока (он уже проверен: рядом, в радиусе 32 блоков). */
    public static void receiveStart(Minecraft mc, Player sender, int seed, long session) {
        UUID id = sender.getUUID();
        Long last = LAST_SESSION.get(id);
        if (last != null && last == session) {
            return; // повторная обработка той же метки
        }
        LAST_SESSION.put(id, session);
        Ritual old = ACTIVE_RITUALS.get(id);
        if (old != null) {
            cleanup(mc, old); // новый запуск заменяет остаток прежней сессии
        }
        begin(mc, id, session, seed, false);
    }

    /** Метка завершения: применяется только к ритуалу с тем же идентификатором сессии. */
    public static void receiveEnd(Minecraft mc, Player sender, long session) {
        Ritual r = ACTIVE_RITUALS.get(sender.getUUID());
        if (r != null && r.session == session) {
            r.beginEnd();
        }
    }

    private static void begin(Minecraft mc, UUID initiator, long session, int seed, boolean local) {
        Ritual r = new Ritual(initiator, session, seed, local);
        Player who = local ? mc.player : mc.level.getPlayerByUUID(initiator);
        if (who != null) {
            r.x = who.getX();
            r.y = who.getY();
            r.z = who.getZ();
            r.stand = findStand(mc, who);
            if (r.stand != null) {
                r.ax = r.stand.getX();
                r.ay = r.stand.getY();
                r.az = r.stand.getZ();
                r.x = r.ax;
                r.y = r.ay;
                r.z = r.az;
            }
        }
        ACTIVE_RITUALS.put(initiator, r);
        RitualSound.start(mc, r);
    }

    /** Ближайший к инициатору стенд с именем «Ритуал» в радиусе 16 блоков. Нет такого: null (центр - сам инициатор). */
    private static ArmorStand findStand(Minecraft mc, Player who) {
        return StandFinder.find(mc, who.getX(), who.getY(), who.getZ(), StandFinder.RITUAL, 16.0);
    }

    // ---------------------------------------------------------------- тик

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (boundLevel != mc.level) {
            boundLevel = mc.level;
            reset();               // другое измерение или мир: всё очищается, звуки останавливаются
            boundLevel = mc.level;
            return;
        }
        if (ACTIVE_RITUALS.isEmpty() || mc.isPaused()) {
            return;
        }
        int count = ACTIVE_RITUALS.size();
        for (Iterator<Ritual> it = ACTIVE_RITUALS.values().iterator(); it.hasNext(); ) {
            Ritual r = it.next();
            Player who = r.local ? mc.player : mc.level.getPlayerByUUID(r.initiator);
            if (who == null || who.isDeadOrDying()) {
                if (who != null || ++r.lost > LOST_TICKS) {
                    cleanup(mc, r);
                    it.remove();
                }
                continue;
            }
            r.lost = 0;
            // Центр ритуала: стенд (если он ещё в мире, иначе последняя известная позиция) или сам инициатор
            if (r.stand != null) {
                if (!r.stand.isRemoved()) {
                    r.ax = r.stand.getX();
                    r.ay = r.stand.getY();
                    r.az = r.stand.getZ();
                }
                r.x = r.ax;
                r.y = r.ay;
                r.z = r.az;
            } else {
                r.x = who.getX();
                r.y = who.getY();
                r.z = who.getZ();
            }
            r.viewerDistance = Math.sqrt(mc.player.distanceToSqr(r.x, r.y, r.z)); // до центра ритуала

            if (r.state != ENDING) {
                scheduleAnomalies(r);
            }
            settleAnomalies(r);
            if (r.viewerDistance <= FX_RANGE) {
                RitualFx.tick(mc, r, count);
            }
            RitualSound.tick(mc, r);
            // Редкие реплики для находящихся рядом: начало, переход в постоянное состояние и завершение
            if (r.state == STARTING && r.age == 0) {
                DeityVoice.say(mc, r.viewerDistance, "ritual_start");
            } else if (r.state == STARTING && r.stateAge == START_TICKS - 1) {
                DeityVoice.say(mc, r.viewerDistance, "ritual_active");
            } else if (r.state == ENDING && r.stateAge == 0) {
                DeityVoice.say(mc, r.viewerDistance, "ritual_end");
            }

            r.age++;
            r.stateAge++;
            if (r.state == STARTING && r.stateAge >= START_TICKS) {
                r.state = ACTIVE;
                r.stateAge = 0;
            } else if (r.state == ENDING && r.stateAge >= END_TICKS) {
                cleanup(mc, r); // полная тишина после окончания анимации
                it.remove();
            }
        }
    }

    // Расписание аномалий: интервал 5-8 секунд (100-160 тиков) со случайными отклонениями, одинаково у всех наблюдателей
    private static void scheduleAnomalies(Ritual r) {
        while (r.age >= r.nextAnomaly) {
            int type = r.sched.nextInt(4);
            int life = type == 0 ? 36 : type == 1 ? 16 : type == 2 ? 40 : 30;
            r.anomalies.add(new Anomaly(r.nextAnomaly, type, life, r.sched.nextDouble() * Math.PI * 2.0, 4.0 + r.sched.nextDouble() * 10.0)); // в пределах области (до 14 блоков)
            r.nextAnomaly += 100 + r.sched.nextInt(61);
        }
    }

    private static void settleAnomalies(Ritual r) {
        for (Iterator<Anomaly> it = r.anomalies.iterator(); it.hasNext(); ) {
            Anomaly a = it.next();
            if (r.age > a.start + a.life && a.soundDone) {
                if (a.type == 2) {
                    r.settledBoost += 2.4;
                }
                it.remove();
            }
        }
    }

    // ---------------------------------------------------------------- экранный эффект

    /** Добавляет в список экранные эффекты ритуалов для наблюдателя (виньетка и вспышки, только в области ритуала). */
    static void collectScreen(Minecraft mc, List<RitualScreenFx.Source> out) {
        if (mc.player == null) {
            return;
        }
        for (Ritual r : ACTIVE_RITUALS.values()) {
            double dist = Math.sqrt(mc.player.distanceToSqr(r.x, r.y, r.z));
            double inside = smooth((20.0 - dist) / 8.0);   // полный эффект в 12 блоках от центра, ноль за 20
            if (inside <= 0.0) {
                continue;
            }
            double phase = r.state == STARTING ? smooth(r.age / 50.0) : r.state == ACTIVE ? 1.0 : 1.0 - r.stateAge / (double) END_TICKS;
            double flash = 0.0;
            for (Anomaly a : r.anomalies) {
                int t = r.age - a.start;
                if ((a.type == 0 || a.type == 1) && t >= 0 && t < 8) {
                    flash = Math.max(flash, 0.5 * (1.0 - t / 8.0));
                }
            }
            if (r.state == ENDING && r.stateAge >= END_TICKS - 6) {
                flash = Math.max(flash, 1.0 - (r.stateAge - (END_TICKS - 6)) / 6.0);
            }
            out.add(new RitualScreenFx.Source(0x1A0536, 0.55 * inside * phase, flash * inside, 0xB58CFF));
        }
    }

    // ---------------------------------------------------------------- клавиша при открытой наковальне

    /** Как BladeCeremony.onScreenKey: K перехватывается до поля названия, символ в имя предмета не попадает. */
    public static boolean onScreenKey(int action, KeyEvent event, KeyMapping key) {
        Minecraft mc = Minecraft.getInstance();
        if (key == null || key.isUnbound() || !(ScreenAccess.current(mc) instanceof AnvilScreen) || !key.matches(event)) {
            return false;
        }
        if (action == 1) {
            requestToggle(mc);
        }
        if (action == 1 || action == 2) {
            BladeCeremony.swallowNextChar();
            return true;
        }
        return false;
    }
}
