package cult.illusion;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Локальные настройки игрока, которые переживают перезапуск клиента: переключатели «Влияние сил» и «Влияние Безымянного».
 * Хранятся в config/cult_illusion_power.properties. Состояние никуда не передаётся. Ошибки чтения и записи не критичны:
 * тогда действуют значения по умолчанию (оба переключателя включены).
 */
final class ModSettings {
    private ModSettings() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("cult_illusion");
    private static final String FILE_NAME = "cult_illusion_power.properties";
    private static final String KEY_INFLUENCE = "ability_influence";
    private static final String KEY_NAMELESS = "nameless_manifestations";

    private static boolean influence = true;
    private static boolean nameless = true;

    static {
        load();
    }

    static boolean influence() {
        return influence;
    }

    static boolean nameless() {
        return nameless;
    }

    static void setInfluence(boolean value) {
        influence = value;
        save();
    }

    static void setNameless(boolean value) {
        nameless = value;
        save();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Properties props = new Properties();
            props.load(reader);
            influence = Boolean.parseBoolean(props.getProperty(KEY_INFLUENCE, "true"));
            nameless = Boolean.parseBoolean(props.getProperty(KEY_NAMELESS, "true"));
        } catch (IOException | RuntimeException e) {
            LOG.warn("[settings] cannot read {}: {}", path, e.toString());
        }
    }

    private static void save() {
        Path path = file();
        Properties props = new Properties();
        props.setProperty(KEY_INFLUENCE, Boolean.toString(influence));
        props.setProperty(KEY_NAMELESS, Boolean.toString(nameless));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                props.store(writer, "Cult Illusion: local toggles");
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("[settings] cannot write {}: {}", path, e.toString());
        }
    }
}
