package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.Random;

/**
 * Звук церемонии. Каждый звук воспроизводится в точке, где сейчас находится инициатор (Fx.sound: позиционный звук,
 * громкость падает с расстоянием до слушателя; у разных инициаторов звуки независимы). Громкость выше 1 только
 * увеличивает дальность слышимости (радиус 16 * громкость блоков), чтобы церемония слышалась в радиусе 32 блоков.
 *
 * Стадия 1 (Тень божья): низкочастотный пульс, глубокий звук поглощения, приглушённый протяжный гул.
 * Стадия 2 (Шепот божий): cult_illusion:whisper.voice - несколько голосов с интервалами, с разных сторон, приглушённо.
 * Стадия 3 (Печать божья): minecraft:block.chain.place в моменты замыкания структуры,
 *                          minecraft:block.amethyst_block.resonate как завершающий акцент.
 */
final class BladeCeremonySound {
    private BladeCeremonySound() {
    }

    private static final Identifier VOICE_ID = Identifier.fromNamespaceAndPath("cult_illusion", "whisper.voice");
    private static SoundEvent voice = null;

    private static SoundEvent voice() {
        if (voice == null) {
            voice = SoundEvent.createVariableRangeEvent(VOICE_ID);
        }
        return voice;
    }

    static void start(Minecraft mc, BladeCeremony.Run run) {
        run.nextEvent = 0;
    }

    private static void play(Minecraft mc, BladeCeremony.Run run, SoundEvent event, double dx, double dy, double dz,
                             float volume, float pitch) {
        Fx.sound(mc, event, run.x + dx, run.y + 1.0 + dy, run.z + dz, volume, pitch);
    }

    static void tick(Minecraft mc, BladeCeremony.Run run) {
        // Безымянный приходит и уходит (на 20-м и 288-м тиках): глубокий тон и звук ухода
        if (run.age == 20) {
            play(mc, run, SoundEvents.ELDER_GUARDIAN_CURSE, 0, 0, 0, 1.6F, 0.65F);
        } else if (run.age == 288) {
            play(mc, run, SoundEvents.ENDERMAN_TELEPORT, 0, 0, 0, 2.0F, 0.6F);
        }
        if (run.stage == 1) {
            shadow(mc, run);
        } else if (run.stage == 2) {
            whisper(mc, run);
        } else {
            seal(mc, run);
        }
    }

    // ---- Стадия 1: поглощение ----
    private static void shadow(Minecraft mc, BladeCeremony.Run run) {
        int age = run.age;
        double i = run.intensity();
        // низкочастотный пульс, учащающийся к финалу
        if (age >= run.nextEvent && age < 280) {
            play(mc, run, SoundEvents.WARDEN_HEARTBEAT, 0, 0, 0, (float) (2.0 + i), 0.5F);
            run.nextEvent = age + (int) (40 - 28 * i);
        }
        // глубокий звук поглощения: в начале и дальше волнами
        if (age == 0 || age == 120 || age == 230) {
            play(mc, run, SoundEvents.WARDEN_SONIC_CHARGE, 0, 0, 0, 2.2F, 0.55F);
        }
        // приглушённый протяжный гул
        if (age % 70 == 5 && age < 280) {
            play(mc, run, SoundEvents.BEACON_AMBIENT, 0, 0, 0, 1.6F, 0.5F);
        }
        // финал: схлопывание в точку и исчезновение
        if (age == 282) {
            play(mc, run, SoundEvents.ENDERMAN_TELEPORT, 0, 0, 0, 2.5F, 0.3F);
        }
        if (age == 290) {
            play(mc, run, SoundEvents.WARDEN_HEARTBEAT, 0, 0, 0, 3.0F, 0.4F);
        }
    }

    // ---- Стадия 2: голоса вокруг ----
    private static void whisper(Minecraft mc, BladeCeremony.Run run) {
        int age = run.age;
        double i = run.intensity();
        if (age < run.nextEvent || age >= 290) {
            return;
        }
        Random rng = run.rng;
        boolean fin = age >= BladeCeremony.FINAL_START;
        double fp = fin ? Math.min(1.0, (age - BladeCeremony.FINAL_START) / 35.0) : 0.0;
        // голоса возникают по одному, с разных направлений; в финале приближаются к инициатору
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double distance = (2.5 + rng.nextDouble() * 3.5) * (1.0 - 0.9 * fp);
        double dy = -0.6 + rng.nextDouble() * 1.8;
        float volume = (float) (0.9 + 0.5 * i);            // приглушённо: ослабляется расстоянием и низким тоном
        float pitch = 0.72F + rng.nextFloat() * 0.22F;      // ниже обычного: глухие, сверхъестественные
        play(mc, run, voice(), Math.cos(angle) * distance, dy, Math.sin(angle) * distance, volume, pitch);
        if (rng.nextInt(4) == 0) {
            // иногда второй голос почти сразу после первого, с противоположной стороны
            double a2 = angle + Math.PI * (0.7 + rng.nextDouble() * 0.6);
            play(mc, run, voice(), Math.cos(a2) * distance, dy * 0.5, Math.sin(a2) * distance, volume * 0.8F, pitch - 0.05F);
        }
        run.nextEvent = age + (int) ((8 + rng.nextInt(10)) * (1.4 - 0.8 * i));
    }

    // ---- Стадия 3: замыкание печати ----
    private static void seal(Minecraft mc, BladeCeremony.Run run) {
        int age = run.age;
        // кольца замыкаются
        if (age == 50 || age == 100 || age == 150) {
            float pitch = age == 50 ? 0.7F : age == 100 ? 0.85F : 1.0F;
            play(mc, run, PowerFx.chainPlace(), 0, 0, 0, 2.0F, pitch);
        }
        // каждая цепь ложится на клинок
        for (int c = 0; c < 6; c++) {
            if (age == BladeCeremonyFx.CHAIN_START + c * BladeCeremonyFx.CHAIN_STEP + BladeCeremonyFx.CHAIN_DROP) {
                play(mc, run, PowerFx.chainPlace(), 0, 0, 0, 1.8F, 0.9F + 0.06F * c);
            }
        }
        // линии между кольцами: по одному звуку на каждую
        for (int m = 0; m < 6; m++) {
            if (age == BladeCeremonyFx.SPOKE_START + 8 + m * 5) {
                play(mc, run, PowerFx.chainPlace(), 0, 0, 0, 1.2F, 1.1F + 0.05F * m);
            }
        }
        // грани геометрической фигуры замыкаются одна за другой
        for (int e = 0; e < 6; e++) {
            if (age == BladeCeremonyFx.EDGE_START + e * BladeCeremonyFx.EDGE_STEP + BladeCeremonyFx.EDGE_LEN) {
                play(mc, run, PowerFx.chainPlace(), 0, 0, 0, 1.6F, 0.6F + 0.07F * e);
            }
        }
        // резонанс: структура замкнута, затем схлопывание
        if (age == BladeCeremony.FINAL_START) {
            play(mc, run, SoundEvents.AMETHYST_BLOCK_RESONATE, 0, 0, 0, 1.5F, 0.5F);
        }
        if (age == 285) {
            play(mc, run, PowerFx.chainPlace(), 0, 0, 0, 2.5F, 0.5F); // знак-печать падает на клинок
            play(mc, run, SoundEvents.AMETHYST_BLOCK_RESONATE, 0, 0, 0, 2.5F, 0.7F);
        }
        if (age == 290) {
            play(mc, run, SoundEvents.AMETHYST_BLOCK_RESONATE, 0, 0, 0, 2.5F, 0.5F); // завершающий акцент
        }
    }
}
