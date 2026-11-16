package cult.illusion;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Необязательная интеграция с Simple Voice Chat (проверено по voicechat-fabric 2.6.23+26.2): на время Печати собственный голос
 * игрока не передаётся, а приём голосов других игроков, группы и подключение работают как обычно.
 *
 * Три независимых слоя:
 *  1) MicThread.setMicrophoneLocked(true) (публичный метод голосового мода): цикл потока микрофона не читает звук и вместо
 *     этого досылает пакет остановки передачи. Не зависит ни от режима активации (Push-to-Talk / Voice Activation), ни от
 *     назначения клавиш. Метод вызывается через рефлексию; в самом поле microphoneLocked исходное значение читается
 *     отдельно, чтобы вернуть только то, что изменила сама Печать.
 *  2) VoiceChatStateMixin: пока isSealed(), ClientPlayerStateManager.isMuted() возвращает true, а setMuted() ничего не делает.
 *     Сохранённый в config флаг muted не затрагивается, поэтому после Печати исходное состояние микрофона то же.
 *     Этот слой закрывает и короткий промежуток, если поток микрофона пересоздан и ещё не заблокирован.
 *  3) Каждый тик актуальный MicThread сравнивается с тем, который заблокирован; новый поток блокируется сразу.
 *
 * Классы голосового мода не импортируются и не копируются: только имена строками. Если мода нет или его внутренности другие,
 * об этом один раз пишется в лог, остальные эффекты Печати работают.
 */
final class SealVoice {
    private SealVoice() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("cult_illusion");

    private static final String CLIENT_MANAGER = "de.maxhenkel.voicechat.voice.client.ClientManager";
    private static final String MOD_ID = "voicechat";

    /** Читается из VoiceChatStateMixin (другой поток и другой загрузчик контекста), поэтому volatile. */
    private static volatile boolean sealed = false;

    private static boolean unavailable = false;
    private static boolean warned = false;

    private static Method getClient;         // ClientManager.getClient() -> ClientVoicechat (static)
    private static Method getMicThread;      // ClientVoicechat.getMicThread() -> MicThread
    private static Method setLocked;         // MicThread.setMicrophoneLocked(boolean)
    private static Field lockedField;        // MicThread.microphoneLocked (private volatile boolean), только чтение

    private static Object lockedThread = null; // поток, на котором блокировку поставили мы
    private static boolean weLocked = false;   // мы перевели его false -> true

    static boolean isSealed() {
        return sealed;
    }

    static void begin() {
        sealed = true;
        apply();
    }

    static void tick() {
        if (sealed) {
            apply();
        }
    }

    static void end() {
        sealed = false;
        restore();
        lockedThread = null;
        weLocked = false;
    }

    private static boolean resolve() {
        if (unavailable) {
            return false;
        }
        if (getMicThread != null) {
            return true;
        }
        try {
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
                unavailable = true; // голосового мода нет: это нормальный случай
                return false;
            }
            Class<?> manager = Class.forName(CLIENT_MANAGER);
            getClient = manager.getMethod("getClient");
            Class<?> client = getClient.getReturnType();
            getMicThread = client.getMethod("getMicThread");
            Class<?> thread = getMicThread.getReturnType();
            setLocked = thread.getMethod("setMicrophoneLocked", boolean.class);
            lockedField = thread.getDeclaredField("microphoneLocked");
            lockedField.setAccessible(true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            unavailable = true;
            getMicThread = null;
            warnOnce("Simple Voice Chat internals differ from 2.6.23+26.2, microphone lock is limited to the mute mixin: " + e);
            return false;
        }
    }

    private static void warnOnce(String text) {
        if (!warned) {
            warned = true;
            LOG.warn("[seal] {}", text);
        }
    }

    /** Текущий поток микрофона или null (нет подключения к голосовому серверу, микрофон ещё не запущен). */
    private static Object currentThread() throws ReflectiveOperationException {
        Object client = getClient.invoke(null);
        return client == null ? null : getMicThread.invoke(client);
    }

    private static boolean readLocked(Object thread) throws ReflectiveOperationException {
        return lockedField.getBoolean(thread);
    }

    private static void apply() {
        if (!resolve()) {
            return;
        }
        try {
            Object thread = currentThread();
            if (thread == null) {
                return;
            }
            if (thread != lockedThread) {
                // первый вызов или поток пересоздан: запоминаем, была ли уже блокировка от кого-то другого
                boolean previous = readLocked(thread);
                lockedThread = thread;
                weLocked = !previous;
                if (!previous) {
                    setLocked.invoke(thread, true);
                }
            } else if (!readLocked(thread)) {
                // кто-то снял блокировку во время Печати: ставим снова и считаем её своей
                weLocked = true;
                setLocked.invoke(thread, true);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            unavailable = true;
            warnOnce("microphone lock failed, only the mute mixin is active: " + e);
        }
    }

    private static void restore() {
        if (!weLocked || lockedThread == null || getMicThread == null) {
            return;
        }
        try {
            Object thread = currentThread();
            if (thread == lockedThread) {
                setLocked.invoke(thread, false); // снимаем только то, что поставила Печать
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce("microphone unlock failed: " + e);
        }
    }
}
