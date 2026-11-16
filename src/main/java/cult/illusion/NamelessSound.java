package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * Звуки Безымянного. Только клиент одного игрока, без позиции в мире (звук слышен только ему и не зависит от дистанции).
 *
 * 1) Присутствие: assets/cult_illusion/sounds/nameless/presence.ogg (исходный soundreality-feu-follet-148082.ogg без
 *    изменений). Играется по кругу, пока силуэт в мире; громкость PRESENCE_VOLUME задана здесь, файл не менялся.
 * 2) Шёпот: assets/cult_illusion/sounds/nameless/whisper.ogg (исходный helamangile-whisper-voices-1-193087.ogg без
 *    изменений). Только при визуальных помехах и в звуковом тесте. Держит громкость hold тиков, затем затихает за fade тиков.
 *
 * Одновременно играет не больше одного экземпляра каждого звука.
 */
public final class NamelessSound {
    private NamelessSound() {
    }

    /** Звук присутствия: максимально тихий. */
    public static final float PRESENCE_VOLUME = 0.05F;
    /** Шёпот при помехах: заметный, но не перекрывающий игровые звуки. */
    public static final float WHISPER_VOLUME = 0.45F;

    private static final Identifier PRESENCE_ID = Identifier.fromNamespaceAndPath("cult_illusion", "nameless.presence");
    private static final Identifier WHISPER_ID = Identifier.fromNamespaceAndPath("cult_illusion", "nameless.whisper");

    private static Instance presence = null;
    private static Instance whisper = null;

    private static final class Instance extends AbstractSoundInstance implements TickableSoundInstance {
        private final float baseVolume;
        private final int hold;   // тиков полной громкости (-1 = без затухания, играет до остановки)
        private final int fade;   // тиков затухания после hold
        private int age = 0;
        private boolean stopped = false;

        Instance(Identifier id, float volume, boolean loop, int hold, int fade) {
            super(id, SoundSource.MASTER, SoundInstance.createUnseededRandom());
            this.baseVolume = volume;
            this.volume = volume;
            this.pitch = 1.0F;
            this.looping = loop;
            this.delay = 0;
            this.relative = true;                                  // без позиции: не зависит от положения игрока
            this.attenuation = SoundInstance.Attenuation.NONE;     // и не становится громче при приближении
            this.hold = hold;
            this.fade = fade;
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
            if (stopped || hold < 0) {
                return;
            }
            age++;
            if (age >= hold + fade) {
                stopped = true;
            } else if (age > hold) {
                this.volume = baseVolume * (1.0F - (age - hold) / (float) Math.max(1, fade));
            }
        }
    }

    /** Запускает звук присутствия (повторный вызов не создаёт второй экземпляр). */
    public static void startPresence(Minecraft mc) {
        if (presence != null && !presence.isStopped()) {
            return;
        }
        presence = new Instance(PRESENCE_ID, PRESENCE_VOLUME, true, -1, 0);
        mc.getSoundManager().play(presence);
    }

    public static void stopPresence(Minecraft mc) {
        if (presence != null) {
            presence.halt();
            mc.getSoundManager().stop(presence);
            presence = null;
        }
    }

    /** Запускает шёпот: hold тиков полной громкости, затем плавное затухание за fade тиков и остановка. */
    public static void startWhisper(Minecraft mc, int hold, int fade) {
        stopWhisper(mc); // не больше одного экземпляра
        whisper = new Instance(WHISPER_ID, WHISPER_VOLUME, false, hold, fade);
        mc.getSoundManager().play(whisper);
    }

    public static void stopWhisper(Minecraft mc) {
        if (whisper != null) {
            whisper.halt();
            mc.getSoundManager().stop(whisper);
            whisper = null;
        }
    }

    /** Останавливает оба звука (исчезновение, смерть, выход из мира). */
    public static void stopAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getSoundManager() != null) {
            stopPresence(mc);
            stopWhisper(mc);
        } else {
            presence = null;
            whisper = null;
        }
    }
}
