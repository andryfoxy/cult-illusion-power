package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/** Общая последовательность призыва: 2 удара колокола, третий звук особый, затем запускается сама способность. */
public final class Summon {
    private Summon() {
    }

    private static int tick = -1;
    private static Ability ability = null;
    private static UUID subjectId = null; // кто активировал способность; null = локальный игрок
    private static int seed = 0;          // общий для всех клиентов seed случайных частей эффекта
    private static long targetCode = 0L;  // противник, выбранный активировавшим игроком (id сущности + 1, 0 = нет)

    public static boolean isActive() {
        return tick >= 0;
    }

    /** Идёт призыв именно Власти Божьей. */
    public static boolean isPowerActive() {
        return tick >= 0 && ability == Ability.POWER;
    }

    /** Идёт призыв именно Разума Божьего. */
    public static boolean isMindActive() {
        return tick >= 0 && ability == Ability.MIND;
    }

    /** Идёт призыв именно Шепота божьего. */
    public static boolean isWhisperActive() {
        return tick >= 0 && ability == Ability.WHISPER;
    }

    /** Идёт призыв именно Печати божьей. */
    public static boolean isSealActive() {
        return tick >= 0 && ability == Ability.SEAL;
    }

    /** Сколько тиков призыва уже обработано (удар n происходит в обработке тика n * STRIKE_INTERVAL_TICKS). */
    public static int currentTick() {
        return tick;
    }

    public static void start(Ability chosen, UUID subject, int effectSeed, long target) {
        ability = chosen;
        subjectId = subject;
        seed = effectSeed;
        targetCode = target;
        tick = 0;
        // Экранное искажение запускается только у выбранной жертвы; у остальных клиентов эта строка ничего не делает
        MindDistortion.begin(chosen, subject, target);
        // Фиолетовая отметка над выбранной целью Разума и Шепота: видна всем клиентам, получившим метку способности
        TargetMarkers.beginMind(chosen, target);
    }

    public static void reset() {
        tick = -1;
        ability = null;
        subjectId = null;
    }

    public static void tick(Minecraft mc) {
        if (tick < 0 || ability == null) {
            return;
        }
        if (tick % Config.STRIKE_INTERVAL_TICKS == 0) {
            int index = tick / Config.STRIKE_INTERVAL_TICKS;
            if (index < Config.BELL_COUNT) {
                Fx.ui(mc, SoundEvents.BELL_BLOCK, Config.BELL_VOLUME, 1.0F);
                if (ability != Ability.SHADOW && ability != Ability.WHISPER && ability != Ability.SEAL) { // у Тени, Шепота и Печати нет тряски призыва
                    ScreenShake.strike(Config.STRIKE_SHAKE[index]);
                }
                if (ability == Ability.MIND) {
                    // Символы enchant вокруг активирующего игрока: в тот же тик, что и удар колокола
                    MindSymbols.strike(mc, subjectId == null ? mc.player
                            : (mc.level == null ? null : mc.level.getPlayerByUUID(subjectId)), index);
                }
                if (index == 0 && ability == Ability.POWER) {
                    // Магический круг начинает формироваться уже с первого удара
                    DivineChains.begin(mc, DivineJudgment.activeOwner());
                }
            } else {
                fifthStrike(mc);
                return;
            }
        }
        tick++;
    }

    // Особый звук (третий): здесь способность срабатывает
    private static void fifthStrike(Minecraft mc) {
        Ability finished = ability;
        UUID subject = subjectId;
        int effectSeed = seed;
        long opponentCode = targetCode;
        tick = -1;
        ability = null;
        subjectId = null;

        if (finished != Ability.SHADOW && finished != Ability.WHISPER && finished != Ability.SEAL) {
            ScreenShake.strike(Config.STRIKE_SHAKE[Config.BELL_COUNT]);
            ScreenShake.startFadeOut();
        }

        if (finished == Ability.POWER) {
            Fx.ui(mc, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0F, 1.0F);
            OverlayHud.showTitle(Config.TITLE_POWER);
            DivineJudgment.begin(mc);
        } else if (finished == Ability.WHISPER) {
            Fx.ui(mc, SoundEvents.WARDEN_SONIC_CHARGE, 1.0F, 1.0F); // особый третий звук: entity.warden.sonic_charge
            WhisperCurse.start(mc, subject, opponentCode); // только у выбранной жертвы
        } else if (finished == Ability.SEAL) {
            Fx.ui(mc, SoundEvents.EVOKER_CAST_SPELL, 1.0F, 1.0F); // особый третий звук: entity.evoker.cast_spell
            SealController.start(mc, opponentCode); // цепи видны всем клиентам; блокировка чата и микрофона только у самой жертвы
        } else if (finished == Ability.SHADOW) {
            ShadowSound.play(mc); // особый третий звук: Respawn Anchor Ambient, вариант 3
            ShadowCloak.start(mc, subject == null ? mc.player : (mc.level == null ? null : mc.level.getPlayerByUUID(subject)));
        } else {
            Fx.ui(mc, SoundEvents.WARDEN_SONIC_BOOM, 1.0F, 1.0F);
            OverlayHud.showTitle(Config.TITLE_MIND);
            Player owner = subject == null ? mc.player : (mc.level == null ? null : mc.level.getPlayerByUUID(subject));
            MindSymbols.strike(mc, owner, Config.BELL_COUNT);
            MindDistortion.peak(); // максимум на особом звуке, дальше затухание 20 тиков
            Entity opponent = opponentCode > 0L && mc.level != null ? mc.level.getEntity((int) (opponentCode - 1L)) : null;
            MirrorDoubles.start(mc, owner, effectSeed, opponent);
        }
    }
}
