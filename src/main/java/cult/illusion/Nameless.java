package cult.illusion;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Безымянный: система случайных сверхъестественных проявлений (подготовка к будущему ивенту).
 * Только клиент: всё происходит локально у одного игрока (ни сущностей мира, ни пакетов, ни серверной части), поэтому
 * модель, звуки и помехи видит и слышит только он сам.
 *
 * Расписание. После входа в мир запускается случайный интервал 15-30 минут; интервал идёт всегда, в том числе пока игрок
 * мёртв. Когда он истёк, начинается один эпизод из двух типов (случайно, 50/50): силуэт со звуком присутствия ИЛИ
 * визуальные помехи со звуком шёпота. После завершения эпизода выбирается новый интервал. Выход из мира прерывает
 * эпизод и забывает таймер (при следующем входе интервал выбирается заново). Смерть и смена измерения прерывают эпизод.
 *
 * Силуэт. На расстоянии 20-40 блоков в случайном направлении, на твёрдой поверхности под открытым небом (карта высот
 * MOTION_BLOCKING: над блоком нет крыши, поэтому не в здании, пещере или тоннеле), с прямой видимостью от глаз игрока.
 * Стоит неподвижно, всегда лицом к игроку. Исчезает, если на него направлена камера (задержка 0,5-1,5 с, затем короткий
 * глитч) или если игрок подошёл ближе 10 блоков (глитч сразу). Пока открыт экран (инвентарь, пауза), взгляд и дистанция
 * не проверяются: обычные правила наблюдения действуют после его закрытия.
 *
 * Клавиша H включает и выключает автоматические проявления (только на этом клиенте): при выключении текущее проявление
 * прекращается и таймер останавливается; при включении запускается новый случайный интервал 15-30 минут.
 */
public final class Nameless {
    private Nameless() {
    }

    // ---- Параметры ----
    private static final int MIN_INTERVAL_TICKS = 15 * 60 * 20;    // 15 минут
    private static final int MAX_INTERVAL_TICKS = 30 * 60 * 20;    // 30 минут
    private static final double SPAWN_MIN_DISTANCE = 20.0;
    private static final double SPAWN_MAX_DISTANCE = 40.0;
    private static final int SPAWN_ATTEMPTS = 48;                  // попыток подобрать точку за один раз
    private static final int SEARCH_RETRY_TICKS = 10;              // как часто повторять подбор, пока подходящего места нет
    private static final int SEARCH_GIVE_UP_TICKS = 30 * 20;       // естественный эпизод без места не происходит и считается завершённым
    private static final double VANISH_DISTANCE = 10.0;            // приближение, при котором силуэт исчезает
    private static final double GAZE_CONE_DEGREES = 3.0;           // «камера направлена на силуэт»: угол между осью взгляда и силуэтом
    private static final int GAZE_DELAY_MIN_TICKS = 10;            // 0,5 с
    private static final int GAZE_DELAY_MAX_TICKS = 30;            // 1,5 с
    private static final int VANISH_CUT_BEFORE_END = 2;            // модель убирается за столько тиков до конца короткого глитча
    private static final double LEASH_DISTANCE = 96.0;             // игрок ушёл слишком далеко: силуэт тихо пропадает без глитча

    private static final int FAKE_ENTITY_ID = Integer.MAX_VALUE - 7; // id клиентской модели (в мире не регистрируется)
    private static final Random RANDOM = new Random();

    // ---- Состояние ----
    private static final int KIND_NONE = 0;
    private static final int KIND_SILHOUETTE = 1;
    private static final int KIND_GLITCH = 2;

    private static final int PHASE_SEARCH = 0;  // подбор места
    private static final int PHASE_WATCH = 1;   // наблюдает
    private static final int PHASE_DELAY = 2;   // камера направлена на силуэт: идёт случайная задержка
    private static final int PHASE_VANISH = 3;  // короткий глитч перед исчезновением

    private static boolean autoEnabled = ModSettings.nameless();  // переключатель «Влияние Безымянного» в главном меню: автоматические проявления (по умолчанию включены, значение сохраняется)
    private static boolean armed = false;       // таймер взведён для текущего мира
    private static boolean timerRunning = false;
    private static int timer = 0;
    private static Object boundLevel = null;

    private static int kind = KIND_NONE;
    private static int phase = PHASE_SEARCH;
    private static int searchLeft = 0;
    private static int delayLeft = 0;
    private static int vanishLeft = 0;
    private static int ageTicks = 0;

    private static NamelessAvatar avatar = null;
    private static double sx;
    private static double sy;
    private static double sz;

    public static boolean isActive() {
        return kind != KIND_NONE;
    }

    private static int rollInterval() {
        return MIN_INTERVAL_TICKS + RANDOM.nextInt(MAX_INTERVAL_TICKS - MIN_INTERVAL_TICKS + 1);
    }

    /** Выход из мира: всё прекращается, звуки и эффекты удаляются, таймер не сохраняется. */
    public static void reset() {
        clearEpisode();
        armed = false;
        timerRunning = false;
        timer = 0;
        boundLevel = null;
    }

    /** Убирает всё, что создал эпизод: модель, звуки, помехи и постэффект. Таймер не трогает. */
    private static void clearEpisode() {
        NamelessSound.stopAll();
        NamelessGlitch.reset();
        NamelessPost.reset();
        avatar = null;
        kind = KIND_NONE;
        phase = PHASE_SEARCH;
        searchLeft = 0;
        delayLeft = 0;
        vanishLeft = 0;
        ageTicks = 0;
    }

    /** Эпизод закончился (или прерван): если проявления включены, запускается новый случайный интервал. */
    private static void endEpisode() {
        clearEpisode();
        if (autoEnabled) {
            timer = rollInterval();
            timerRunning = true;
        }
    }

    /** true: автоматические проявления Безымянного включены. */
    public static boolean autoEnabled() {
        return autoEnabled;
    }

    /** Переключение из главного меню. */
    public static void toggleAuto(Minecraft mc) {
        setAuto(mc, !autoEnabled);
    }

    /** Явно включает или выключает автоматические проявления на этом клиенте и сохраняет выбор. */
    public static void setAuto(Minecraft mc, boolean value) {
        if (value == autoEnabled) {
            return;
        }
        autoEnabled = value;
        ModSettings.setNameless(value);
        if (autoEnabled) {
            timer = rollInterval(); // предыдущий таймер не продолжается: новый случайный интервал 15-30 минут
            timerRunning = true;
        } else {
            clearEpisode(); // модель, звуки, помехи и постэффект удаляются
            timerRunning = false;
        }
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.translatable(autoEnabled
                    ? "gui.cult_illusion.nameless.auto_on" : "gui.cult_illusion.nameless.auto_off"));
        }
    }

    /** Каждый тик, пока игрок в мире. */
    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!armed) {
            timer = rollInterval(); // вход в мир: новый случайный интервал, старый не сохраняется
            timerRunning = autoEnabled;
            armed = true;
            boundLevel = mc.level;
        }
        if (boundLevel != mc.level) {
            // Другое измерение или мир: проявление прерывается, таймер идёт дальше
            boundLevel = mc.level;
            if (kind != KIND_NONE) {
                endEpisode();
            }
        }
        if (timerRunning && timer > 0) {
            timer--; // отсчёт идёт и пока игрок мёртв
        }

        if (mc.player.isDeadOrDying()) {
            if (kind != KIND_NONE) {
                endEpisode(); // смерть: модель, помехи и звуки удаляются, естественный таймер получает новый интервал
            }
            NamelessPost.tick(mc);
            return;
        }

        if (kind == KIND_NONE) {
            if (autoEnabled && timerRunning && timer <= 0) {
                startNatural(mc);
            }
        } else if (kind == KIND_SILHOUETTE) {
            tickSilhouette(mc);
        } else if (kind == KIND_GLITCH) {
            tickGlitch(mc);
        }
        NamelessPost.tick(mc);
    }

    // ---------------------------------------------------------------- запуск

    private static void startNatural(Minecraft mc) {
        timerRunning = false;
        if (RANDOM.nextBoolean()) {
            beginSilhouette(mc, SEARCH_GIVE_UP_TICKS);
        } else {
            beginGlitch(mc);
        }
    }

    private static void beginSilhouette(Minecraft mc, int searchTicks) {
        kind = KIND_SILHOUETTE;
        phase = PHASE_SEARCH;
        searchLeft = searchTicks;
        ageTicks = 0;
        if (trySpawn(mc)) {
            phase = PHASE_WATCH;
            NamelessSound.startPresence(mc);
        }
    }

    private static void beginGlitch(Minecraft mc) {
        kind = KIND_GLITCH;
        NamelessGlitch.startFull();
        // Шёпот: полная громкость на время помех, затем затухание на время последействия; заново он не запускается
        NamelessSound.startWhisper(mc, NamelessGlitch.fullDuration(), NamelessGlitch.afterDuration());
    }

    // ---------------------------------------------------------------- эпизоды

    private static void tickGlitch(Minecraft mc) {
        if (NamelessGlitch.tick()) {
            endEpisode(); // помехи и последействие закончились, звук к этому моменту затих
        }
    }

    private static void tickSilhouette(Minecraft mc) {
        if (phase == PHASE_SEARCH) {
            if (searchLeft <= 0) {
                endEpisode();
                return;
            }
            searchLeft--;
            if (searchLeft % SEARCH_RETRY_TICKS == 0 && trySpawn(mc)) {
                phase = PHASE_WATCH;
                NamelessSound.startPresence(mc);
            }
            return;
        }

        ageTicks++;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 spot = new Vec3(sx, sy, sz);

        if (phase == PHASE_VANISH) {
            vanishLeft--;
            if (vanishLeft == VANISH_CUT_BEFORE_END && avatar != null) {
                avatar = null;                // модель исчезает под глитчем: ни частиц, ни следов
                NamelessSound.stopPresence(mc);
            }
            boolean glitchDone = NamelessGlitch.tick();
            if (glitchDone || vanishLeft <= 0) {
                endEpisode();
            }
            return;
        }

        // Силуэт остался без почвы (чанк выгружен) или игрок ушёл слишком далеко: тихое прекращение без глитча
        if (!mc.level.hasChunkAt(Mth.floor(sx), Mth.floor(sz)) || mc.player.position().distanceTo(spot) > LEASH_DISTANCE) {
            endEpisode();
            return;
        }

        // Пока открыт экран (инвентарь, пауза), взгляд и дистанция не проверяются
        boolean screenOpen = ScreenAccess.current(mc) != null;
        boolean near = !screenOpen && mc.player.position().distanceTo(spot) <= VANISH_DISTANCE;

        if (near) {
            beginVanish(mc);
            return;
        }
        if (phase == PHASE_WATCH) {
            if (!screenOpen && isLookedAt(mc, eye)) {
                phase = PHASE_DELAY;
                delayLeft = GAZE_DELAY_MIN_TICKS + RANDOM.nextInt(GAZE_DELAY_MAX_TICKS - GAZE_DELAY_MIN_TICKS + 1);
            }
        } else if (phase == PHASE_DELAY) {
            delayLeft--;
            if (delayLeft <= 0) {
                beginVanish(mc);
            }
        }
    }

    private static void beginVanish(Minecraft mc) {
        phase = PHASE_VANISH;
        vanishLeft = NamelessGlitch.startShort();
    }

    // Камера направлена на силуэт: ось взгляда в пределах конуса вокруг его центра и между ними нет блоков
    private static boolean isLookedAt(Minecraft mc, Vec3 eye) {
        Vec3 center = new Vec3(sx, sy + 0.9, sz);
        Vec3 toward = center.subtract(eye);
        double length = toward.length();
        if (length < 1.0e-3) {
            return true;
        }
        Vec3 look = mc.player.getViewVector(1.0F);
        double cos = look.dot(toward) / length;
        if (cos < Math.cos(Math.toRadians(GAZE_CONE_DEGREES))) {
            return false;
        }
        return Visibility.clear(mc, eye, center, mc.player);
    }

    // ---------------------------------------------------------------- подбор места

    /**
     * Подбирает точку 20-40 блоков от игрока в случайном направлении и создаёт модель.
     * Условия: загруженный чанк; сверху нет блоков, мешающих движению (карта высот MOTION_BLOCKING: открытая
     * местность, не здание, не пещера, не тоннель, не под кроной); под ногами сплошной блок с прочной верхней гранью
     * (не воздух, не жидкость, не листва); место для тела свободно; от глаз игрока к голове силуэта нет блоков.
     */
    private static boolean trySpawn(Minecraft mc) {
        ClientLevel level = mc.level;
        Vec3 eye = mc.player.getEyePosition();
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double distance = SPAWN_MIN_DISTANCE + RANDOM.nextDouble() * (SPAWN_MAX_DISTANCE - SPAWN_MIN_DISTANCE);
            int bx = Mth.floor(mc.player.getX() + Math.cos(angle) * distance);
            int bz = Mth.floor(mc.player.getZ() + Math.sin(angle) * distance);
            if (!level.hasChunkAt(bx, bz)) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz); // первый свободный y над самым верхним блоком
            BlockPos ground = new BlockPos(bx, top - 1, bz);
            BlockPos feet = ground.above();
            BlockPos head = feet.above();
            BlockPos over = head.above();
            BlockState groundState = level.getBlockState(ground);
            if (!groundState.isFaceSturdy(level, ground, Direction.UP) || !groundState.getFluidState().isEmpty()) {
                continue;
            }
            if (!bodyClear(level, feet) || !bodyClear(level, head) || !bodyClear(level, over)) {
                continue;
            }
            Vec3 headPoint = new Vec3(bx + 0.5, top + 1.5, bz + 0.5);
            if (!Visibility.clear(mc, eye, headPoint, mc.player)) {
                continue;
            }
            // Точка найдена: модель создаётся на этом месте
            sx = bx + 0.5;
            sy = top;
            sz = bz + 0.5;
            avatar = new NamelessAvatar(level);
            avatar.setId(FAKE_ENTITY_ID); // рендерер читает id сущности; в мир она не добавляется, поэтому id задаётся вручную
            avatar.setPos(sx, sy, sz);
            avatar.setOldPosAndRot();
            return true;
        }
        return false;
    }

    private static boolean bodyClear(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    // ---------------------------------------------------------------- отрисовка

    /** Отрисовка модели штатным рендерером игрока; сущность в мир не добавляется. */
    public static void render(LevelRenderContext context) {
        NamelessAvatar model = avatar;
        if (model == null || phase == PHASE_SEARCH) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || model.level() != mc.level) {
            return;
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        orient(model, mc.player.getEyePosition(partial), partial);

        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        var state = dispatcher.extractEntity(model, partial);
        state.nameTag = null;   // без никнейма
        state.scoreText = null; // без дополнительных индикаторов
        dispatcher.submit(state, context.levelState().cameraRenderState,
                sx - camera.x, sy - camera.y, sz - camera.z, context.poseStack(), context.submitNodeCollector());
    }

    // Лицом к игроку (тело и голова), едва заметные естественные покачивания. Руки качаются штатной анимацией покоя.
    private static void orient(NamelessAvatar model, Vec3 eye, float partial) {
        double dx = eye.x - sx;
        double dz = eye.z - sz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float faceYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float facePitch = (float) (-Math.atan2(eye.y - (sy + 1.62), Math.max(1.0e-4, horizontal)) * (180.0 / Math.PI));

        double t = ageTicks + partial;
        float bodySway = (float) (Math.sin(t * 0.045) * 1.5 + Math.sin(t * 0.021 + 1.3) * 1.0);
        float headSway = (float) (Math.sin(t * 0.060 + 0.7) * 2.0);
        float pitchSway = (float) (Math.sin(t * 0.050 + 2.1) * 1.5);

        float bodyYaw = faceYaw + bodySway;
        float headYaw = faceYaw + headSway;
        float pitch = Mth.clamp(facePitch + pitchSway, -89.0F, 89.0F);

        model.tickCount = ageTicks;
        model.setYRot(bodyYaw);
        model.yRotO = bodyYaw;
        model.yBodyRot = bodyYaw;
        model.yBodyRotO = bodyYaw;
        model.setYHeadRot(headYaw);
        model.yHeadRot = headYaw;
        model.yHeadRotO = headYaw;
        model.setXRot(pitch);
        model.xRotO = pitch;
    }
}
