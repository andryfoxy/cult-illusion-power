package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Печать божья (клинок Lycanbane): 15 секунд (300 тиков) выбранный игрок не может пользоваться обычным текстовым
 * чатом и передавать свой голос. Получение сообщений и прослушивание других игроков не затрагиваются.
 *
 * Работает только на клиенте. Выбранная жертва приходит по тому же каналу, что и у других способностей
 * (невидимая метка в чате с seed и id цели); никакого нового протокола и никакой серверной части нет.
 *
 * Два уровня состояния:
 *  - цепи вокруг головы (SealChains) показывает каждый клиент, у которого жертва есть в мире;
 *  - блокировка чата и микрофона включается только на клиенте самой жертвы (local = true).
 *
 * Любое завершение (конец 300 тиков, смерть, смена мира или измерения, выход с сервера, повторная активация)
 * проходит через reset(), который снимает блокировки и возвращает микрофон в исходное состояние.
 */
public final class SealController {
    private SealController() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("cult_illusion");

    private static int age = -1;              // тики с начала эффекта; -1 = не идёт
    private static Object boundLevel = null;
    private static int victimId = -1;
    private static UUID victimUuid = null;
    private static boolean local = false;     // жертва это игрок этого клиента: только тогда блокируются чат и микрофон

    // Поля ChatScreen типа String: по ним определяется экран, открытый клавишей команды (начинается с «/»)
    private static Field[] chatStringFields = null;

    public static boolean isActive() {
        return age >= 0 && age < Config.SEAL_DURATION_TICKS;
    }

    /** Чат заблокирован у игрока этого клиента (используется миксином и событием отправки сообщений). */
    public static boolean isChatLocked() {
        return local && isActive() && AbilityInfluence.enabled();
    }

    /** Жертва Печати в текущем мире клиента или null (вне зоны прорисовки, мёртва, эффект не идёт). */
    public static Player victim(Minecraft mc) {
        if (!isActive() || mc.level == null || mc.level != boundLevel) {
            return null;
        }
        Entity entity = mc.level.getEntity(victimId);
        return entity instanceof Player player && player.getUUID().equals(victimUuid) ? player : null;
    }

    /**
     * Особый третий звук: запуск. targetCode: id сущности жертвы + 1 (0 = цели нет, тогда ничего не происходит).
     * Выполняется на каждом клиенте, получившем способность; повторная активация начинает всё заново.
     */
    public static void start(Minecraft mc, long targetCode) {
        reset();
        if (mc.player == null || mc.level == null || targetCode <= 0L) {
            return;
        }
        int id = (int) (targetCode - 1L);
        Entity entity = mc.level.getEntity(id);
        if (!(entity instanceof Player player) || !player.isAlive()) {
            return; // жертвы нет в этом мире клиента: показывать нечего
        }
        age = 0;
        boundLevel = mc.level;
        victimId = id;
        victimUuid = player.getUUID();
        local = player == mc.player;

        // Один раз при появлении всей конструкции, из позиции головы жертвы
        Fx.sound(mc, PowerFx.chainPlace(), player.getX(), player.getEyeY(), player.getZ(), Config.SEAL_SOUND_VOLUME, 1.0F);

        if (local && AbilityInfluence.enabled()) {
            SealVoice.begin();
            closeChatIfOpen(mc);
        }
    }

    /** Конец эффекта по любой причине. Идемпотентен. */
    public static void reset() {
        boolean wasLocal = local;
        age = -1;
        boundLevel = null;
        victimId = -1;
        victimUuid = null;
        local = false;
        if (wasLocal || SealVoice.isSealed()) {
            SealVoice.end();
        }
    }

    public static void tick(Minecraft mc) {
        if (age < 0) {
            return;
        }
        if (mc.player == null || mc.level == null || mc.level != boundLevel || mc.player.isDeadOrDying()) {
            reset(); // выход из мира, смена измерения, смерть локального игрока
            return;
        }
        Player victim = victim(mc);
        if (victim != null && victim.isDeadOrDying()) {
            reset(); // жертва погибла: цепи исчезают
            return;
        }
        if (local) {
            // Переключение влияния во время Печати: микрофон блокируется или возвращается сразу
            boolean locked = AbilityInfluence.enabled();
            if (locked && !SealVoice.isSealed()) {
                SealVoice.begin();
            } else if (!locked && SealVoice.isSealed()) {
                SealVoice.end();
            }
            SealVoice.tick(); // если поток микрофона был пересоздан, блокировка применяется заново
            closeChatIfOpen(mc); // без влияния blocksScreen возвращает false, чат не трогается
        }
        age++;
        if (age >= Config.SEAL_DURATION_TICKS) {
            reset();
        }
    }

    // ---------------------------------------------------------------- Текстовый чат

    /** Нужно ли запретить показ этого экрана: только обычный ChatScreen, и только у жертвы во время Печати. */
    public static boolean blocksScreen(Screen screen) {
        return isChatLocked() && screen instanceof ChatScreen && !isCommandScreen(screen);
    }

    private static void closeChatIfOpen(Minecraft mc) {
        Screen screen = ScreenAccess.current(mc);
        if (screen != null && blocksScreen(screen)) {
            ScreenAccess.close(mc);
        }
    }

    /**
     * Экран чата, открытый клавишей команды, начинается с «/»: команды Печать не блокирует. Определяется по строковым полям
     * ChatScreen; если определить не удалось, экран считается обычным чатом и блокируется. Отправка обычного текста
     * из такого экрана всё равно запрещена событием ClientSendMessageEvents.ALLOW_CHAT.
     */
    private static boolean isCommandScreen(Screen screen) {
        try {
            if (chatStringFields == null) {
                java.util.List<Field> fields = new java.util.ArrayList<>();
                for (Field field : ChatScreen.class.getDeclaredFields()) {
                    if (field.getType() == String.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        fields.add(field);
                    }
                }
                chatStringFields = fields.toArray(new Field[0]);
            }
            for (Field field : chatStringFields) {
                Object value = field.get(screen);
                if (value instanceof String text && text.startsWith("/")) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            chatStringFields = new Field[0];
            LOG.warn("[seal] cannot tell command screen from chat screen, all chat screens are blocked: {}", e.toString());
        }
        return false;
    }
}
