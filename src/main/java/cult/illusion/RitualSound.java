package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Звук постоянного ритуала (клавиша K). Отдельный от звуков Безымянного (nameless.presence / nameless.whisper, они
 * не используются) и от звуков стадий церемонии. Всё позиционное: звуки идут из точки, где сейчас стоит инициатор, и
 * затухают с расстоянием до слушателя.
 *
 * Начало: низкий протяжный звук, нарастающий гул, короткий искажённый шёпот.
 * Активное состояние: очень тихий постоянный низкочастотный фон, нерегулярные шёпоты (cult_illusion:whisper.voice),
 *   отдельные пульсы, колокольные тона в вышине, глубокие акценты во время аномалий; раскрытие и моргание Глаза.
 * Завершение: фон быстро затихает (END_HUM_FADE_TICKS), искажённый (сильно замедленный) шёпот, глубокий звук схлопывания и
 *   финальный акцент звучат в первые ~1,3 с, затем все звуки ритуала плавно стихают и к END_SOUND_TICKS наступает полная
 *   тишина. Звуковая часть заканчивается раньше визуальной: схлопывание (RitualAura.END_TICKS) длится дольше и не менялось.
 *
 * Фон - единственный протяжный звук: это управляемый экземпляр (следует за инициатором, плавно затухает), который
 * всегда останавливается при завершении, смерти, выходе из мира и смене измерения. Одиночные звуки ритуала тоже
 * управляемые экземпляры (Shot): при завершении они плавно стихают и принудительно останавливаются, поэтому длинные
 * «хвосты» (например, замедленный звук Древнего стража) не звучат после наступления тишины.
 */
final class RitualSound {
    private RitualSound() {
    }

    private static final Identifier VOICE_ID = Identifier.fromNamespaceAndPath("cult_illusion", "whisper.voice");
    private static SoundEvent voice = null;

    private static final float HUM_VOLUME = 0.35F; // очень тихий фон (дальность слышимости при этом не меньше 16 блоков)

    // Завершение, тики с его начала (stateAge). Визуальная часть идёт RitualAura.END_TICKS = 70 тиков и не сокращалась
    private static final int END_HUM_FADE_TICKS = 24;   // фон затихает за 1,2 с (раньше за всё завершение)
    private static final int END_FADE_START = 30;       // с этого тика все остальные звуки ритуала плавно стихают
    private static final int END_SOUND_TICKS = 46;      // с этого тика полная тишина (2,3 с): всё остановлено
    private static final int SHOT_TRACK_TICKS = 400;    // звуки старше 20 с из учёта убираются (их хвосты давно закончились)

    private static SoundEvent voice() {
        if (voice == null) {
            voice = SoundEvent.createVariableRangeEvent(VOICE_ID);
        }
        return voice;
    }

    /** Фоновый гул: управляемый позиционный экземпляр. */
    static final class Hum extends AbstractSoundInstance implements TickableSoundInstance {
        private boolean stopped = false;
        private double tx;
        private double ty;
        private double tz;
        private float level = 0.0F;

        Hum(double x, double y, double z) {
            super(SoundEvents.BEACON_AMBIENT.location(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.pitch = 0.5F;
            this.volume = 0.0F;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tx = x;
            this.ty = y;
            this.tz = z;
        }

        void move(double x, double y, double z, float level) {
            this.tx = x;
            this.ty = y;
            this.tz = z;
            this.level = level;
        }

        void halt() {
            stopped = true;
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void tick() {
            this.x = tx;
            this.y = ty;
            this.z = tz;
            this.volume = HUM_VOLUME * Math.max(0.0F, Math.min(1.0F, level));
        }
    }

    /** Одиночный позиционный звук ритуала: управляемый экземпляр, который можно приглушить и остановить. */
    static final class Shot extends AbstractSoundInstance implements TickableSoundInstance {
        private boolean stopped = false;
        private final float base;
        private float gain = 1.0F;
        final int born;

        Shot(SoundEvent event, double x, double y, double z, float volume, float pitch, int born) {
            super(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.x = x;
            this.y = y;
            this.z = z;
            this.volume = volume;
            this.pitch = pitch;
            this.base = volume;
            this.born = born;
        }

        void gain(float gain) {
            this.gain = gain;
        }

        void halt() {
            stopped = true;
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void tick() {
            this.volume = base * gain;
        }
    }

    // Звуки каждого ритуала, которые ещё могут звучать (учёт нужен, чтобы при завершении их приглушить и остановить)
    private static final Map<RitualAura.Ritual, List<Shot>> SHOTS = new IdentityHashMap<>();

    private static void play(Minecraft mc, RitualAura.Ritual r, SoundEvent event, double dx, double dy, double dz, float volume, float pitch) {
        Shot shot = new Shot(event, r.x + dx, r.y + 1.0 + dy, r.z + dz, volume, pitch, r.age);
        mc.getSoundManager().play(shot);
        SHOTS.computeIfAbsent(r, k -> new ArrayList<>()).add(shot);
    }

    private static void fadeShots(RitualAura.Ritual r, float gain) {
        List<Shot> shots = SHOTS.get(r);
        if (shots != null) {
            for (Shot shot : shots) {
                shot.gain(gain);
            }
        }
    }

    private static void whisper(Minecraft mc, RitualAura.Ritual r, double distance, float volume, float pitch) {
        double a = r.fx.nextDouble() * Math.PI * 2.0;
        play(mc, r, voice(), Math.cos(a) * distance, -0.4 + r.fx.nextDouble() * 1.6, Math.sin(a) * distance, volume, pitch);
    }

    static void start(Minecraft mc, RitualAura.Ritual r) {
        r.hum = new Hum(r.x, r.y + 1.0, r.z);
        mc.getSoundManager().play(r.hum);
        r.nextWhisper = 140 + r.fx.nextInt(120);
        r.nextPulse = 110 + r.fx.nextInt(60);
        r.nextChime = 60 + r.fx.nextInt(60);
    }

    /** Немедленная остановка (завершение, смерть, выход из мира, смена измерения). Ничего не остаётся играть. */
    static void stop(Minecraft mc, RitualAura.Ritual r) {
        if (r.hum != null) {
            r.hum.halt();
            mc.getSoundManager().stop(r.hum);
            r.hum = null;
        }
        List<Shot> shots = SHOTS.remove(r);
        if (shots != null) {
            for (Shot shot : shots) {
                shot.halt();
                mc.getSoundManager().stop(shot);
            }
        }
    }

    static void tick(Minecraft mc, RitualAura.Ritual r) {
        Random fx = r.fx;
        int age = r.age;
        float level;
        List<Shot> tracked = SHOTS.get(r);
        if (tracked != null) {
            tracked.removeIf(shot -> age - shot.born > SHOT_TRACK_TICKS);
        }
        if (r.state == RitualAura.STARTING) {
            level = (float) Math.min(1.0, age / (double) RitualAura.START_TICKS);   // гул нарастает
            if (age == 0) {
                play(mc, r, SoundEvents.WARDEN_SONIC_CHARGE, 0, 0, 0, 2.4F, 0.5F);   // низкий протяжный звук
            } else if (age == 40) {
                whisper(mc, r, 1.5, 1.2F, 0.55F);                                    // короткий искажённый шёпот
            } else if (age == 55) {
                play(mc, r, SoundEvents.WARDEN_HEARTBEAT, 0, 0, 0, 2.0F, 0.45F);
            } else if (age == RitualAura.START_TICKS - 2) {
                play(mc, r, SoundEvents.AMETHYST_BLOCK_RESONATE, 0, 0, 0, 1.5F, 0.5F);
            }
            if (age == 55) {
                play(mc, r, SoundEvents.ELDER_GUARDIAN_CURSE, 0, 8.0, 0, 1.6F, 0.6F); // над местом открывается глаз
            }
        } else if (r.state == RitualAura.ACTIVE) {
            level = 1.0F;
            if (age >= r.nextWhisper) { // нерегулярные шёпоты
                whisper(mc, r, 2.5 + fx.nextDouble() * 3.5, 0.8F, 0.7F + fx.nextFloat() * 0.2F);
                r.nextWhisper = age + 100 + fx.nextInt(180);
            }
            if (age >= r.nextPulse) { // пространственные пульсы
                play(mc, r, SoundEvents.WARDEN_HEARTBEAT, (fx.nextDouble() - 0.5) * 4.0, 0, (fx.nextDouble() - 0.5) * 4.0,
                        1.0F + fx.nextFloat() * 0.4F, 0.42F + fx.nextFloat() * 0.1F);
                r.nextPulse = age + 60 + fx.nextInt(90);
            }
            // колокольные тона в вышине и редкие вздохи пространства
            if (age >= r.nextChime) {
                play(mc, r, SoundEvents.AMETHYST_BLOCK_CHIME, (fx.nextDouble() - 0.5) * 8.0, 4.0 + fx.nextDouble() * 6.0, (fx.nextDouble() - 0.5) * 8.0,
                        0.9F, 0.5F + fx.nextFloat() * 0.4F);
                r.nextChime = age + 40 + fx.nextInt(80);
            }
            if ((age + r.seed % 97) % 190 == 0) { // моргание Глаза
                play(mc, r, SoundEvents.ENDERMAN_STARE, 0, 8.0, 0, 1.2F, 0.35F);
            }
            // редкие глубокие акценты в моменты аномалий (расписание общее для всех наблюдателей)
            for (RitualAura.Anomaly a : r.anomalies) {
                if (!a.soundDone && age >= a.start) {
                    a.soundDone = true;
                    if (a.type == 0) {
                        play(mc, r, SoundEvents.ENDERMAN_TELEPORT, 0, 0, 0, 2.0F, 0.3F);
                    } else if (a.type == 1) {
                        play(mc, r, SoundEvents.WARDEN_SONIC_CHARGE, Math.cos(a.angle) * a.distance, 0, Math.sin(a.angle) * a.distance, 1.6F, 0.45F);
                    } else if (a.type == 2) {
                        play(mc, r, SoundEvents.BEACON_ACTIVATE, 0, 0, 0, 1.5F, 0.5F);
                    } else {
                        play(mc, r, SoundEvents.ENDERMAN_STARE, 0, 0, 0, 1.5F, 0.4F);
                    }
                }
            }
        } else {
            // Завершение: звуковая часть короче визуальной (RitualAura.END_TICKS)
            if (r.stateAge >= END_SOUND_TICKS) {
                stop(mc, r); // полная тишина: ни фона, ни запоздалых звуков (повторные вызовы ничего не делают)
                return;
            }
            level = (float) Math.max(0.0, 1.0 - r.stateAge / (double) END_HUM_FADE_TICKS); // фон затихает быстро
            if (r.stateAge > END_FADE_START) {
                fadeShots(r, 1.0F - (r.stateAge - END_FADE_START) / (float) (END_SOUND_TICKS - END_FADE_START));
            }
            if (r.stateAge == 2) {
                whisper(mc, r, 1.8, 1.0F, 0.5F);                                     // искажённый шёпот
            } else if (r.stateAge == 6) {
                play(mc, r, SoundEvents.ELDER_GUARDIAN_CURSE, 0, 8.0, 0, 1.4F, 0.45F); // глаз закрывается
            } else if (r.stateAge == 12) {
                whisper(mc, r, 1.2, 0.9F, 0.65F);
            } else if (r.stateAge == 20) {
                play(mc, r, SoundEvents.ENDERMAN_TELEPORT, 0, 0, 0, 2.5F, 0.3F);     // схлопывание
            } else if (r.stateAge == 26) {
                play(mc, r, SoundEvents.WARDEN_HEARTBEAT, 0, 0, 0, 3.0F, 0.4F);
                play(mc, r, SoundEvents.AMETHYST_BLOCK_RESONATE, 0, 0, 0, 2.5F, 0.6F);
            }
        }
        if (r.hum != null) {
            r.hum.move(r.x, r.y + 1.0, r.z, level);
        }
    }
}
