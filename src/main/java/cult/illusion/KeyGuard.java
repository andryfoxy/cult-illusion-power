package cult.illusion;

import net.minecraft.client.KeyMapping;

import java.util.HashMap;
import java.util.Map;

/**
 * Защита от удержания клавиши. Игра при удержании клавиши присылает повторные нажатия (автоповтор), и KeyMapping.consumeClick
 * возвращает true снова и снова: способности и ритуалы срабатывали бы ложно. Здесь каждое физическое нажатие даёт ровно одну
 * активацию: следующая возможна только после того, как клавишу отпустили.
 */
final class KeyGuard {
    private KeyGuard() {
    }

    private static final Map<KeyMapping, Boolean> ARMED = new HashMap<>();

    /** true один раз за нажатие. Вызывается каждый тик для каждой клавиши (в том числе чтобы вовремя «перевзвести» её). */
    static boolean pressed(KeyMapping key) {
        boolean armed = ARMED.getOrDefault(key, true);
        boolean accepted = false;
        while (key.consumeClick()) {
            if (armed) {
                accepted = true;
                armed = false;
            }
        }
        if (!key.isDown()) {
            armed = true; // клавишу отпустили: следующее нажатие снова считается
        }
        ARMED.put(key, armed);
        return accepted;
    }
}
