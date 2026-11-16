package cult.illusion;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Шепот божий (клинок Hiro no Tsuki): 15 секунд галлюцинаций у выбранной жертвы. Работает только на клиенте жертвы
 * (как экранное искажение Разума): у активировавшего игрока и у остальных клиентов эффект не запускается.
 *
 * 1) Управление. Каждые 2-5 секунд (случайно) формируется новая перестановка клавиш W, A, S, D, прыжок, приседание и
 *    спринт без неподвижных точек: каждая физическая клавиша вызывает ровно одно действие, и ни одна не остаётся на своём.
 *    Настройки игрока не меняются: в начале каждого тика состояние клавиш заново читается с клавиатуры
 *    (KeyMapping.setAll) и подменяется только на время этого тика. Поэтому после окончания эффекта, смерти, смены мира
 *    или выхода с сервера управление возвращается само, а options.txt никогда не затрагивается.
 * 2) Звук: тип А (шёпот: фрагменты из assets/cult_illusion/sounds/whisper и тихие ванильные звуки) и тип Б (искажённые
 *    игровые звуки с резкими перепадами высоты). Звуки возникают в случайных точках вокруг головы жертвы и слышны только ей.
 * 3) Картинка: редкие короткие глитчи (сдвинутые цветные полосы, помехи, затемнение) и лёгкая дрожь камеры.
 *
 * Хронология: 0-300 тиков полный эффект и переназначенное управление; 300-340 тиков управление уже возвращено, звуки
 * и искажения плавно затихают; затем всё сбрасывается.
 */
public final class WhisperCurse {
    private WhisperCurse() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("cult_illusion");

    private static final int KEY_COUNT = 7;
    private static final int KIND_SLICES = 1;
    private static final int KIND_DARK = 2;
    private static final int KIND_STATIC = 4;

    private static final String VOICE_EVENT = "cult_illusion:whisper.voice";
    // тип А: тихие ванильные звуки, подмешиваются к шёпоту
    private static final String[] VOICE_EXTRA = {
            "minecraft:ambient.cave", "minecraft:entity.warden.nearby_close", "minecraft:entity.phantom.ambient"};
    // тип Б: игровые звуки, которые играются искажённо
    private static final String[] NOISE = {
            "minecraft:entity.zombie.ambient", "minecraft:entity.skeleton.ambient", "minecraft:entity.creeper.primed",
            "minecraft:block.wooden_door.open", "minecraft:block.iron_door.close", "minecraft:block.chest.open",
            "minecraft:block.stone.step", "minecraft:entity.ghast.ambient", "minecraft:block.glass.break",
            "minecraft:entity.enderman.ambient", "minecraft:entity.warden.heartbeat", "minecraft:block.sculk_sensor.clicking"};

    private static final class Echo {
        int delay;
        SoundEvent event;
        double x;
        double y;
        double z;
        float volume;
        float pitch;
    }

    private static int age = -1;                 // тики с начала эффекта; -1 = не идёт
    private static Object boundLevel = null;
    private static int[] perm = null;            // действие i вызывается физической клавишей perm[i]
    private static int remapCooldown = 0;
    private static boolean keysTouched = false;  // мы подменяли состояние клавиш и должны вернуть реальное
    private static boolean remapUnavailable = false;
    private static boolean warnedUnavailable = false;
    private static int voiceCooldown = 0;
    private static int noiseCooldown = 0;
    private static int glitchCooldown = 0;
    private static int glitchLeft = 0;
    private static int glitchTotal = 0;
    private static int glitchKind = 0;
    private static int glitchSeed = 0;
    private static final List<Echo> echoes = new ArrayList<>();
    private static final Map<String, SoundEvent> EVENTS = new HashMap<>();

    public static boolean isActive() {
        return age >= 0;
    }

    /** Сейчас идёт экранный глитч (нужно камере и отрисовке). */
    public static boolean isGlitching() {
        return age >= 0 && glitchLeft > 0;
    }

    /** Жёсткий сброс: конец эффекта, смерть, смена мира, выход с сервера, ошибка. Реальное управление возвращается сразу. */
    public static void reset() {
        age = -1;
        boundLevel = null;
        perm = null;
        remapCooldown = 0;
        voiceCooldown = 0;
        noiseCooldown = 0;
        glitchCooldown = 0;
        glitchLeft = 0;
        glitchTotal = 0;
        glitchKind = 0;
        echoes.clear();
        restoreKeys();
    }

    /**
     * Особый третий звук: запуск эффекта. subject — кто активировал (null = локальный игрок), targetCode — выбранная
     * жертва (id сущности + 1). Эффект запускается только у жертвы; повторная активация начинает всё заново, старое
     * состояние не накладывается (сначала сбрасывается, управление при этом возвращается).
     */
    public static void start(Minecraft mc, UUID subject, long targetCode) {
        if (mc.player == null || mc.level == null || subject == null || targetCode <= 0L) {
            return;
        }
        if (subject.equals(mc.player.getUUID()) || targetCode != (long) mc.player.getId() + 1L) {
            return;
        }
        reset();
        age = 0;
        boundLevel = mc.level;
        perm = derangement();
        remapCooldown = nextRemap();
        voiceCooldown = 5 + Fx.RANDOM.nextInt(10);
        noiseCooldown = 20 + Fx.RANDOM.nextInt(20);
        glitchCooldown = 20 + Fx.RANDOM.nextInt(30);
    }

    /**
     * Жертва для Шепота: игрок, на которого активирующий смотрит прямо (см. DivineJudgment.pickPlayerInCone); мобы не выбираются.
     * Запасного выбора ближайшего игрока нет: без прямого взгляда цели нет. Так же выбирается отметка на HUD.
     */
    public static Entity pickTarget(Minecraft mc, Player viewer) {
        return DivineJudgment.pickPlayerInCone(mc, viewer);
    }

    // ---------------------------------------------------------------- Управление (только клиент жертвы)

    private static int nextRemap() {
        return Config.WHISPER_REMAP_MIN_TICKS + Fx.RANDOM.nextInt(Math.max(1, Config.WHISPER_REMAP_RANDOM_TICKS));
    }

    // Случайная перестановка без неподвижных точек: взаимно однозначная, значит конфликтов клавиш нет
    private static int[] derangement() {
        int[] result = new int[KEY_COUNT];
        while (true) {
            for (int i = 0; i < KEY_COUNT; i++) {
                result[i] = i;
            }
            for (int i = KEY_COUNT - 1; i > 0; i--) {
                int j = Fx.RANDOM.nextInt(i + 1);
                int tmp = result[i];
                result[i] = result[j];
                result[j] = tmp;
            }
            boolean fixed = false;
            for (int i = 0; i < KEY_COUNT; i++) {
                if (result[i] == i) {
                    fixed = true;
                    break;
                }
            }
            if (!fixed) {
                return result;
            }
        }
    }

    private static boolean controlsActive(Minecraft mc) {
        return age >= 0 && age < Config.WHISPER_DURATION_TICKS && perm != null && AbilityInfluence.enabled()
                && mc.player != null && mc.level != null && mc.level == boundLevel
                && mc.player.isAlive() && ScreenAccess.current(mc) == null;
    }

    /**
     * Начало тика, до обработки клавиш игрой. Пока управление переназначено, реальное состояние клавиш читается заново
     * и подменяется по текущей перестановке; как только условие перестаёт выполняться, возвращается реальное состояние.
     */
    public static void startTick(Minecraft mc) {
        if (controlsActive(mc) && !remapUnavailable) {
            try {
                applyRemap(mc);
            } catch (RuntimeException e) {
                remapUnavailable = true;
                LOG.error("[whisper] key remap failed, controls restored and remap disabled", e);
                restoreKeys();
            }
        } else if (keysTouched) {
            restoreKeys();
        }
    }

    private static KeyMapping[] keys(Options options) {
        return new KeyMapping[]{options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
                options.keyJump, options.keyShift, options.keySprint};
    }

    private static void applyRemap(Minecraft mc) {
        if (!syncRealKeys()) {
            remapUnavailable = true;
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                LOG.warn("[whisper] KeyMapping.setAll is not available in this version: control remap is disabled");
            }
            return;
        }
        KeyMapping[] keys = keys(mc.options);
        boolean[] real = new boolean[KEY_COUNT];
        for (int i = 0; i < KEY_COUNT; i++) {
            real[i] = keys[i].isDown();
        }
        keysTouched = true;
        for (int i = 0; i < KEY_COUNT; i++) {
            keys[i].setDown(real[perm[i]]);
        }
    }

    // Клавиши снова читаются из реального состояния клавиатуры
    private static boolean syncRealKeys() {
        try {
            KeyMapping.class.getMethod("setAll").invoke(null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static void restoreKeys() {
        if (!keysTouched) {
            return;
        }
        keysTouched = false;
        syncRealKeys();
    }

    // ---------------------------------------------------------------- Тик: таймеры, звук, глитчи

    private static float smooth(float t) {
        float c = Math.max(0.0F, Math.min(1.0F, t));
        return c * c * (3.0F - 2.0F * c);
    }

    /** Сила звуков и искажений: плавное нарастание, полная сила 15 секунд, затем плавное затухание. */
    private static float strength() {
        float in = Math.min(1.0F, (age + 1) / (float) Math.max(1, Config.WHISPER_FADE_IN_TICKS));
        float out = age < Config.WHISPER_DURATION_TICKS ? 1.0F
                : 1.0F - (age - Config.WHISPER_DURATION_TICKS) / (float) Math.max(1, Config.WHISPER_FADE_OUT_TICKS);
        return smooth(in) * smooth(out);
    }

    public static void tick(Minecraft mc) {
        if (age < 0) {
            return;
        }
        if (mc.player == null || mc.level == null || mc.level != boundLevel || mc.player.isDeadOrDying()) {
            reset();
            return;
        }
        float g = strength();
        if (age < Config.WHISPER_DURATION_TICKS && --remapCooldown <= 0) {
            perm = derangement();
            remapCooldown = nextRemap();
        }
        // чем слабее эффект, тем реже новые звуки и глитчи
        float stretch = 1.0F + 2.0F * (1.0F - g);
        if (g > 0.03F) {
            if (--voiceCooldown <= 0) {
                playVoice(mc, g);
                voiceCooldown = Math.round((Config.WHISPER_VOICE_MIN_TICKS + Fx.RANDOM.nextInt(Math.max(1, Config.WHISPER_VOICE_RANDOM_TICKS))) * stretch);
            }
            if (--noiseCooldown <= 0) {
                playNoise(mc, g);
                noiseCooldown = Math.round((Config.WHISPER_NOISE_MIN_TICKS + Fx.RANDOM.nextInt(Math.max(1, Config.WHISPER_NOISE_RANDOM_TICKS))) * stretch);
            }
            if (glitchLeft > 0) {
                glitchLeft--;
            } else if (--glitchCooldown <= 0) {
                startGlitch(stretch);
            }
        } else {
            glitchLeft = 0;
        }
        for (Iterator<Echo> it = echoes.iterator(); it.hasNext(); ) {
            Echo echo = it.next();
            if (--echo.delay <= 0) {
                Fx.sound(mc, echo.event, echo.x, echo.y, echo.z, echo.volume, echo.pitch);
                it.remove();
            }
        }
        age++;
        if (age >= Config.WHISPER_DURATION_TICKS + Config.WHISPER_FADE_OUT_TICKS) {
            reset(); // всё снято: звуки, глитчи, управление
        }
    }

    private static SoundEvent event(String id) {
        SoundEvent cached = EVENTS.get(id);
        if (cached == null) {
            int colon = id.indexOf(':');
            cached = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(id.substring(0, colon), id.substring(colon + 1)));
            EVENTS.put(id, cached);
        }
        return cached;
    }

    // Случайная точка вокруг головы жертвы: звук приходит с определённой стороны, в том числе из-за спины
    private static Vec3 around(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        double angle = Fx.RANDOM.nextDouble() * Math.PI * 2.0;
        double radius = Config.WHISPER_SOUND_RADIUS_MIN
                + Fx.RANDOM.nextDouble() * (Config.WHISPER_SOUND_RADIUS_MAX - Config.WHISPER_SOUND_RADIUS_MIN);
        return eye.add(Math.cos(angle) * radius, -0.6 + Fx.RANDOM.nextDouble() * 1.8, Math.sin(angle) * radius);
    }

    // Тип А: неразборчивый шёпот из случайной стороны; иногда вместо него тихий низкий фоновый звук
    private static void playVoice(Minecraft mc, float g) {
        Vec3 at = around(mc);
        if (Fx.RANDOM.nextInt(4) == 0) {
            String id = VOICE_EXTRA[Fx.RANDOM.nextInt(VOICE_EXTRA.length)];
            Fx.sound(mc, event(id), at.x, at.y, at.z,
                    Config.WHISPER_VOICE_VOLUME * 0.5F * g * (0.5F + 0.5F * Fx.RANDOM.nextFloat()), 0.6F + 0.3F * Fx.RANDOM.nextFloat());
            return;
        }
        float pitch = Fx.RANDOM.nextInt(5) == 0 ? 0.7F + 0.1F * Fx.RANDOM.nextFloat() : 0.9F + 0.2F * Fx.RANDOM.nextFloat();
        Fx.sound(mc, event(VOICE_EVENT), at.x, at.y, at.z,
                Config.WHISPER_VOICE_VOLUME * g * (0.5F + 0.5F * Fx.RANDOM.nextFloat()), pitch);
    }

    // Тип Б: игровой звук с неожиданной высотой, иногда с эхом-заиканием
    private static void playNoise(Minecraft mc, float g) {
        Vec3 at = around(mc);
        SoundEvent noise = event(NOISE[Fx.RANDOM.nextInt(NOISE.length)]);
        float pitch = Fx.RANDOM.nextFloat() < 0.35F ? 0.5F + 0.2F * Fx.RANDOM.nextFloat() : 0.8F + 0.9F * Fx.RANDOM.nextFloat();
        float volume = Config.WHISPER_NOISE_VOLUME * g * (0.4F + 0.6F * Fx.RANDOM.nextFloat());
        Fx.sound(mc, noise, at.x, at.y, at.z, volume, pitch);
        if (Fx.RANDOM.nextFloat() < 0.3F) {
            Echo echo = new Echo();
            echo.delay = 2 + Fx.RANDOM.nextInt(4);
            echo.event = noise;
            echo.x = at.x;
            echo.y = at.y;
            echo.z = at.z;
            echo.volume = volume * 0.6F;
            echo.pitch = Fx.RANDOM.nextBoolean() ? pitch * 0.7F : pitch * 1.4F;
            echoes.add(echo);
        }
    }

    private static void startGlitch(float stretch) {
        glitchTotal = Config.WHISPER_GLITCH_LENGTH_MIN_TICKS + Fx.RANDOM.nextInt(Math.max(1, Config.WHISPER_GLITCH_LENGTH_RANDOM_TICKS));
        glitchLeft = glitchTotal;
        int[] kinds = {KIND_SLICES, KIND_DARK, KIND_STATIC, KIND_SLICES | KIND_DARK, KIND_SLICES | KIND_STATIC};
        glitchKind = kinds[Fx.RANDOM.nextInt(kinds.length)];
        glitchSeed = Fx.RANDOM.nextInt();
        glitchCooldown = Math.round((Config.WHISPER_GLITCH_MIN_TICKS + Fx.RANDOM.nextInt(Math.max(1, Config.WHISPER_GLITCH_RANDOM_TICKS))) * stretch);
    }

    // ---------------------------------------------------------------- Картинка

    /** Лёгкая дрожь отображения камеры во время глитча; поворот самого игрока не меняется. */
    public static float yawOffset() {
        if (!isGlitching()) {
            return 0.0F;
        }
        double t = System.nanoTime() / 1.0e9;
        double noise = Math.sin(t * 53.0) * 0.6 + Math.sin(t * 29.1 + 1.3) * 0.4;
        return (float) (noise * strength() * Config.WHISPER_SHAKE_DEGREES);
    }

    public static float pitchOffset() {
        if (!isGlitching()) {
            return 0.0F;
        }
        double t = System.nanoTime() / 1.0e9;
        double noise = Math.sin(t * 47.0 + 0.5) * 0.6 + Math.sin(t * 23.7 + 2.1) * 0.4;
        return (float) (noise * strength() * Config.WHISPER_SHAKE_DEGREES * 0.7);
    }

    /** Экранные глитчи: рисуются только пока идёт короткий глитч, в остальное время экран чистый. */
    public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!isGlitching()) {
            return;
        }
        float strength = strength();
        if (strength <= 0.01F) {
            return;
        }
        int width = g.guiWidth();
        int height = g.guiHeight();
        float progress = 1.0F - glitchLeft / (float) Math.max(1, glitchTotal);
        double envelope = 0.35 + 0.65 * Math.sin(Math.PI * Math.min(1.0F, progress));
        double power = strength * envelope;
        // рисунок меняется каждый тик, но стабилен между кадрами одного тика
        Random rnd = new Random(glitchSeed * 31L + glitchLeft);

        if ((glitchKind & KIND_DARK) != 0) {
            int alpha = (int) Math.round(255.0 * Config.WHISPER_DARK_MAX_ALPHA * power);
            rect(g, width, height, 0, 0, width, height, (alpha << 24) | 0x000000);
        }
        if ((glitchKind & KIND_SLICES) != 0) {
            int bands = 5 + rnd.nextInt(5);
            for (int i = 0; i < bands; i++) {
                int y = rnd.nextInt(Math.max(1, height));
                int thick = 3 + rnd.nextInt(Math.max(1, height / 18));
                int x0 = rnd.nextInt(Math.max(1, width / 2));
                int x1 = Math.min(width, x0 + width / 4 + rnd.nextInt(Math.max(1, width / 2)));
                int shift = (int) Math.round((rnd.nextInt(41) - 20) * power);
                int alpha = (int) Math.round(Config.WHISPER_SLICE_MAX_ALPHA * power * (0.5 + 0.5 * rnd.nextDouble()));
                rect(g, width, height, x0, y, x1, y + thick, (alpha << 24) | 0xC8C8D0);
                rect(g, width, height, x0 + shift, y, x1 + shift, y + Math.max(1, thick / 2), (alpha << 24) | 0xE01030);
                rect(g, width, height, x0 - shift, y + thick / 2, x1 - shift, y + thick, (alpha << 24) | 0x10E0FF);
            }
        }
        if ((glitchKind & KIND_STATIC) != 0) {
            int specks = 40 + rnd.nextInt(40);
            for (int i = 0; i < specks; i++) {
                int x = rnd.nextInt(Math.max(1, width));
                int y = rnd.nextInt(Math.max(1, height));
                int size = 2 + rnd.nextInt(5);
                int level = 90 + rnd.nextInt(140);
                int alpha = (int) Math.round(Config.WHISPER_STATIC_MAX_ALPHA * power * (0.4 + 0.6 * rnd.nextDouble()));
                rect(g, width, height, x, y, x + size * (1 + rnd.nextInt(3)), y + size, (alpha << 24) | (level << 16) | (level << 8) | level);
            }
        }
    }

    private static void rect(GuiGraphicsExtractor g, int width, int height, int x0, int y0, int x1, int y1, int color) {
        int ax = Math.max(0, Math.min(x0, x1));
        int bx = Math.min(width, Math.max(x0, x1));
        int ay = Math.max(0, Math.min(y0, y1));
        int by = Math.min(height, Math.max(y0, y1));
        if (bx > ax && by > ay && (color >>> 24) > 0) {
            g.fill(ax, ay, bx, by, color);
        }
    }
}
