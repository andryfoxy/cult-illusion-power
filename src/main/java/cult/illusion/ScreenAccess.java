package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Доступ к текущему экрану и его смене независимо от того, где именно он хранится в данной версии игры
 * (поле или метод в Minecraft либо в Minecraft.gui). В 26.2 прямое поле Minecraft.screen недоступно.
 * Всё ищется один раз по именам; если ничего не найдено, пишется одно предупреждение и экран считается закрытым.
 */
public final class ScreenAccess {
    private ScreenAccess() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("cult_illusion");

    private static boolean resolved = false;
    private static Field screenField;      // поле-экран
    private static Method screenGetter;    // метод без аргументов, возвращающий Screen
    private static Method screenSetter;    // метод setScreen(Screen)
    private static Object holderOfGetter;  // null = сам Minecraft, иначе Minecraft.gui
    private static boolean holderIsGui = false;

    private static Object gui(Minecraft mc) {
        try {
            Field f = Minecraft.class.getField("gui");
            return f.get(mc);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void resolve(Minecraft mc) {
        if (resolved) {
            return;
        }
        resolved = true;
        Object[] holders = {mc, gui(mc)};
        for (int h = 0; h < holders.length; h++) {
            Object holder = holders[h];
            if (holder == null) {
                continue;
            }
            Class<?> type = holder.getClass();
            if (screenField == null && screenGetter == null) {
                try {
                    Field f = type.getField("screen");
                    if (Screen.class.isAssignableFrom(f.getType())) {
                        screenField = f;
                        holderIsGui = h == 1;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // нет такого поля
                }
                if (screenField == null) {
                    for (String name : new String[]{"screen", "getScreen", "currentScreen"}) {
                        try {
                            Method m = type.getMethod(name);
                            if (Screen.class.isAssignableFrom(m.getReturnType())) {
                                screenGetter = m;
                                holderIsGui = h == 1;
                                break;
                            }
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                            // нет такого метода
                        }
                    }
                }
            }
            if (screenSetter == null) {
                try {
                    Method m = type.getMethod("setScreen", Screen.class);
                    screenSetter = m;
                    holderOfGetter = holder;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // нет такого метода
                }
            }
        }
        if (screenField == null && screenGetter == null) {
            LOG.warn("[screen] current screen accessor not found in Minecraft or Minecraft.gui: chat will only be blocked by the mixin");
        }
        if (screenSetter == null) {
            LOG.warn("[screen] setScreen not found: an already open chat cannot be closed by code");
        }
    }

    /** Текущий экран или null. */
    public static Screen current(Minecraft mc) {
        resolve(mc);
        try {
            Object holder = holderIsGui ? gui(mc) : mc;
            if (holder == null) {
                return null;
            }
            if (screenField != null) {
                return (Screen) screenField.get(holder);
            }
            if (screenGetter != null) {
                return (Screen) screenGetter.invoke(holder);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // экран считается закрытым
        }
        return null;
    }

    /** Закрыть текущий экран. */
    public static void close(Minecraft mc) {
        resolve(mc);
        if (screenSetter == null) {
            return;
        }
        try {
            Object holder = holderOfGetter;
            screenSetter.invoke(holder, new Object[]{null});
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("[screen] setScreen(null) failed: {}", e.toString());
        }
    }

    /** Открыть экран через тот же найденный setScreen, что и close (в 26.2 метод может находиться в Minecraft.gui). */
    public static void open(Minecraft mc, Screen screen) {
        resolve(mc);
        if (screenSetter == null) {
            return;
        }
        try {
            screenSetter.invoke(holderOfGetter, screen);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("[screen] setScreen(menu) failed: {}", e.toString());
        }
    }
}
