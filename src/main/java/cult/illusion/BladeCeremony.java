package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
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
 * Церемония передачи силы Безымянного трём клинкам: Тень божья, Шепот божий, Печать божья.
 * Только визуальные эффекты и звуки: предметы, их названия, инвентарь и наковальню мод не трогает.
 *
 * Одна клавиша (по умолчанию J) последовательно запускает три стадии по 300 тиков (15 секунд) каждая:
 * 1 - Тень божья, 2 - Шепот божий, 3 - Печать божья, затем всё сначала. Следующая стадия запускается только новым
 * нажатием; пока своя стадия идёт, нажатия игнорируются. Клиентский мод: серверной части нет. Остальным игрокам с
 * модом в радиусе 32 блоков стадия передаётся скрытой меткой в чате (тот же механизм, что у способностей, метки
 * отдельные); получатель запускает эффект у себя, привязав его к инициатору (эффект и звук следуют за ним).
 * Церемонии разных инициаторов идут независимо и не перезаписывают друг друга.
 *
 * Привязка к клинкам: если рядом (в радиусе 24 блоков от инициатора) стоит стенд для брони с именем «Тень», «Шепот»
 * или «Печать» (соответственно стадии) и клинок лежит у него в руках, сила течёт прямо в этот клинок: центр эффектов
 * находится на стенде, а потоки идут к нему от инициатора и от стенда «Ритуал» (если ритуал идёт). Без такого стенда
 * эффекты проявляются вокруг самого инициатора, как раньше.
 */
public final class BladeCeremony {
    private BladeCeremony() {
    }

    public static final int DURATION_TICKS = 300;   // ровно 15 секунд на каждую стадию
    public static final int FINAL_START = 250;      // начало финальной фазы
    public static final int STAGE_COUNT = 3;

    private static final int LOST_TICKS = 20;       // инициатор не найден (выгружен) столько тиков подряд: эффект снимается
    private static final double FX_RANGE = 48.0;    // дальше этого расстояния до наблюдателя частицы не создаются

    /** Состояние одной идущей церемонии (у каждого инициатора своё). */
    static final class Run {
        final UUID initiator;
        final int stage;                // 1 = Тень божья, 2 = Шепот божий, 3 = Печать божья
        final Random rng;
        final boolean local;            // инициатор - этот игрок
        final double phase;             // общий для всех клиентов поворот структуры (из seed)
        int age = 0;
        int lost = 0;
        double x;                       // центр эффектов (стенд-клинок или инициатор), обновляется каждый тик
        double y;
        double z;
        double ix;                      // позиция инициатора (ноги)
        double iy;
        double iz;
        ArmorStand stand = null;        // стенд-клинок (имя по стадии), если найден
        double sx;
        double sy;
        double sz;
        double viewerDistance = 0.0;
        final List<BladeCeremonyFx.Wave> waves = new ArrayList<>();
        int nextEvent = 0;              // расписание звуков и вспышек стадии

        Run(UUID initiator, int stage, int seed, boolean local) {
            this.initiator = initiator;
            this.stage = stage;
            this.rng = new Random(seed * 2654435761L + stage);
            this.local = local;
            this.phase = new Random(seed ^ 0x5DEECE66DL).nextDouble() * Math.PI * 2.0;
        }

        /** Нарастание интенсивности 0..1 до финальной фазы. */
        double intensity() {
            double t = Math.min(1.0, age / (double) FINAL_START);
            return Math.pow(t, 1.5);
        }
    }

    private static final Map<UUID, Run> ACTIVE = new HashMap<>();
    private static final Map<UUID, Long> LAST_LAUNCH = new HashMap<>();
    private static int nextStage = 0;           // 0..2: какая стадия запустится следующим нажатием
    private static Object boundLevel = null;
    private static boolean swallowChar = false;

    /** Идущая стадия инициатора (для связи с постоянным ритуалом) или null. */
    static Run runOf(UUID initiator) {
        return ACTIVE.get(initiator);
    }

    public static boolean isActive(UUID initiator) {
        return ACTIVE.containsKey(initiator);
    }

    /** Выход из мира: все церемонии снимаются, последовательность начинается заново. */
    public static void reset() {
        NamelessFigure.clearAll();
        ACTIVE.clear();
        LAST_LAUNCH.clear();
        nextStage = 0;
        boundLevel = null;
        swallowChar = false;
    }

    // ---------------------------------------------------------------- запуск

    /** Нажатие клавиши церемонии: запускает очередную стадию у инициатора и отправляет метку другим игрокам. */
    public static void requestStart(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.player.isDeadOrDying()) {
            return;
        }
        UUID self = mc.player.getUUID();
        if (ACTIVE.containsKey(self)) {
            return; // своя стадия ещё идёт: нажатие игнорируется
        }
        int stage = nextStage + 1;
        nextStage = (nextStage + 1) % STAGE_COUNT; // после третьей стадии последовательность начинается заново
        int seed = Fx.RANDOM.nextInt(1 << 16);
        long launchId = Fx.RANDOM.nextInt() & 0xFFFFFFFFL;
        begin(mc, self, stage, seed, true);
        CultIllusionClient.sendCeremonyMarker(mc, stage, seed, launchId);
    }

    /** Метка от другого игрока (он уже проверен: рядом, в радиусе 32 блоков). */
    public static void receive(Minecraft mc, Player sender, int stage, int seed, long launchId) {
        UUID id = sender.getUUID();
        Long last = LAST_LAUNCH.get(id);
        if (last != null && last == launchId && ACTIVE.containsKey(id)) {
            return; // то же сообщение второй раз
        }
        LAST_LAUNCH.put(id, launchId);
        if (ACTIVE.containsKey(id)) {
            return; // у инициатора стадия уже идёт
        }
        begin(mc, id, stage, seed, false);
    }

    private static void begin(Minecraft mc, UUID initiator, int stage, int seed, boolean local) {
        Run run = new Run(initiator, stage, seed, local);
        Player who = local ? mc.player : mc.level.getPlayerByUUID(initiator);
        if (who != null) {
            run.x = run.ix = who.getX();
            run.y = run.iy = who.getY();
            run.z = run.iz = who.getZ();
            String name = stage == 1 ? StandFinder.SHADOW : stage == 2 ? StandFinder.WHISPER : StandFinder.SEAL;
            run.stand = StandFinder.find(mc, run.ix, run.iy, run.iz, name, 24.0);
            if (run.stand != null) {
                run.sx = run.stand.getX();
                run.sy = run.stand.getY();
                run.sz = run.stand.getZ();
                run.x = run.sx;
                run.y = run.sy;
                run.z = run.sz;
            }
        }
        ACTIVE.put(initiator, run);
        BladeCeremonySound.start(mc, run);
        BladeCeremonyFx.start(mc, run);
    }

    // ---------------------------------------------------------------- тик

    public static void tick(Minecraft mc) {
        swallowChar = false; // подавление символа действует только в пределах одного кадра ввода
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (boundLevel != mc.level) {
            boundLevel = mc.level;
            NamelessFigure.clearAll();
            ACTIVE.clear(); // другое измерение: эффекты привязаны к прежнему миру
        }
        if (ACTIVE.isEmpty() || mc.isPaused()) {
            return;
        }
        for (Iterator<Run> it = ACTIVE.values().iterator(); it.hasNext(); ) {
            Run run = it.next();
            Player who = run.local ? mc.player : mc.level.getPlayerByUUID(run.initiator);
            if (who == null) {
                if (++run.lost > LOST_TICKS) {
                    NamelessFigure.remove(run);
                    it.remove();
                }
                continue;
            }
            run.lost = 0;
            if (who.isDeadOrDying()) {
                NamelessFigure.remove(run);
                it.remove();
                continue;
            }
            run.ix = who.getX();
            run.iy = who.getY();
            run.iz = who.getZ();
            if (run.stand != null) {
                if (!run.stand.isRemoved()) {
                    run.sx = run.stand.getX();
                    run.sy = run.stand.getY();
                    run.sz = run.stand.getZ();
                }
                run.x = run.sx;
                run.y = run.sy;
                run.z = run.sz;
            } else {
                run.x = run.ix;
                run.y = run.iy;
                run.z = run.iz;
            }
            run.viewerDistance = Math.sqrt(mc.player.distanceToSqr(run.x, run.y, run.z)); // до центра эффектов
            if (run.viewerDistance <= FX_RANGE) {
                BladeCeremonyFx.tick(mc, run);
            }
            BladeCeremonySound.tick(mc, run);
            if (run.age == 290) { // выраженная финальная вспышка стадии: реплика для находящихся рядом
                DeityVoice.say(mc, run.viewerDistance, run.stage == 1 ? "stage_shadow" : run.stage == 2 ? "stage_whisper" : "stage_seal");
            }
            run.age++;
            if (run.age >= DURATION_TICKS) {
                NamelessFigure.remove(run);
                it.remove();
            }
        }
    }

    // ---------------------------------------------------------------- экранный эффект

    /** Добавляет экранные эффекты стадий (виньетка по краям) для наблюдателя в области эффекта. */
    static void collectScreen(Minecraft mc, java.util.List<RitualScreenFx.Source> out) {
        if (mc.player == null) {
            return;
        }
        for (Run run : ACTIVE.values()) {
            double dist = Math.sqrt(mc.player.distanceToSqr(run.x, run.y, run.z));
            double inside = RitualAura.smooth((22.0 - dist) / 10.0);
            if (inside <= 0.0) {
                continue;
            }
            double build = RitualAura.smooth(run.age / (double) FINAL_START);
            double fade = run.age < 290 ? 1.0 : Math.max(0.0, (DURATION_TICKS - run.age) / 10.0);
            double flash = 0.0;
            int rgb;
            double level;
            if (run.stage == 1) {            // Тень: края темнеют, свет утекает
                rgb = 0x040208;
                level = 0.65 * build * fade;
                if (run.age >= 290 && run.age < 296) {
                    flash = 0.5 * (1.0 - (run.age - 290) / 6.0);
                }
            } else if (run.stage == 2) {     // Шепот: мерцающая фиолетовая дымка
                rgb = 0x2E1060;
                double flicker = 0.6 + 0.4 * (((run.age / 3) * 2654435761L & 0xFF) / 255.0);
                level = 0.5 * build * flicker * fade;
                if (run.age >= 290 && run.age < 296) {
                    flash = 0.4 * (1.0 - (run.age - 290) / 6.0);
                }
            } else {                         // Печать: холодное фиолетовое свечение, вспышки при замыкании
                rgb = 0x50208A;
                level = 0.4 * build * fade;
                int[] locks = {50, 100, 150, 210, 250, 285, 290};
                for (int lock : locks) {
                    int t = run.age - lock;
                    if (t >= 0 && t < 5) {
                        flash = Math.max(flash, (lock >= 285 ? 0.55 : 0.3) * (1.0 - t / 5.0));
                    }
                }
            }
            out.add(new RitualScreenFx.Source(rgb, level * inside, flash * inside, run.stage == 1 ? 0x30104F : 0xD8C4FF));
        }
    }

    // ---------------------------------------------------------------- клавиша при открытой наковальне

    /**
     * Вызывается из KeyboardHandlerMixin до обработки клавиши экраном. Пока открыт интерфейс наковальни, клавиша
     * церемонии запускает стадию и не доходит до поля названия предмета (иначе в имя попал бы символ клавиши).
     * Возвращает true, если нажатие обработано и дальше передавать его не нужно.
     */
    public static boolean onScreenKey(int action, KeyEvent event, KeyMapping key) {
        Minecraft mc = Minecraft.getInstance();
        if (key == null || key.isUnbound() || !(ScreenAccess.current(mc) instanceof AnvilScreen) || !key.matches(event)) {
            return false;
        }
        if (action == 1) { // GLFW_PRESS
            requestStart(mc);
        }
        if (action == 1 || action == 2) { // нажатие и автоповтор: символ клавиши в поле названия не попадает
            swallowChar = true;
            return true;
        }
        return false;
    }

    /** Помечает ближайший ввод символа как относящийся к клавише церемонии или ритуала (он будет отброшен). */
    static void swallowNextChar() {
        swallowChar = true;
    }

    /** true, если ближайший ввод символа относится к клавише церемонии и должен быть отброшен. */
    public static boolean swallowChar() {
        if (swallowChar) {
            swallowChar = false;
            return true;
        }
        return false;
    }
}
