package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Тень божья: особый пятый звук, третий аудиовариант minecraft:block.respawn_anchor.ambient.
 *
 * Стандартное событие выбирает один из трёх файлов случайно, а отдельного события ambient3 в игре нет. Поэтому в
 * assets/cult_illusion/sounds.json заведены собственные события, каждое указывает прямо на файл ambient3 ванильного
 * звука (имя файла в разных сборках пишется по-разному, поэтому вариантов несколько). При воспроизведении берётся то
 * событие, чей файл реально найден в ресурсах игры. Если не найден ни один, играет обычное случайное событие, и об этом
 * пишется предупреждение в лог: случайный вариант не выдаётся за ambient3.
 */
public final class ShadowSound {
    private ShadowSound() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("cult_illusion");
    // {событие из sounds.json мода, путь к файлу ванильного звука без расширения}
    private static final String[][] CANDIDATES = {
            {"shadow.ambient3.a", "block/respawn_anchor/ambient3"},
            {"shadow.ambient3.b", "block/respawn_anchor/respawn_anchor_ambient3"},
    };

    private static SoundEvent resolved = null;
    private static boolean exact = false;

    public static void play(Minecraft mc) {
        if (resolved == null) {
            resolve(mc);
        }
        Fx.ui(mc, resolved, 1.0F, 1.0F);
    }

    private static void resolve(Minecraft mc) {
        for (String[] candidate : CANDIDATES) {
            Identifier file = Identifier.fromNamespaceAndPath("minecraft", "sounds/" + candidate[1] + ".ogg");
            try {
                if (mc.getResourceManager().getResource(file).isPresent()) {
                    resolved = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("cult_illusion", candidate[0]));
                    exact = true;
                    LOG.info("[shadow] fifth sound: {} -> {}", candidate[0], file);
                    return;
                }
            } catch (RuntimeException ignored) {
                // пробуем следующий вариант
            }
        }
        resolved = SoundEvents.RESPAWN_ANCHOR_AMBIENT;
        exact = false;
        LOG.warn("[shadow] ambient3 file not found in resources; playing the vanilla event, the variant is random");
    }

    /** true, если играет именно файл ambient3, а не случайный вариант стандартного события. */
    public static boolean isExact() {
        return exact;
    }
}
