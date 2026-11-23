package cult.illusion;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Локальный переключатель механического воздействия способностей на самого игрока (переключатель «Влияние сил»
 * в главном меню). Решение принимает каждый игрок для себя: состояние никуда не передаётся, другие клиенты о нём
 * не знают.
 *
 * Включено (по умолчанию): способности работают как раньше. Выключено: цепи Власти не обездвиживают, Шепот не переназначает
 * клавиши, Печать не блокирует чат и микрофон, экранное искажение Разума сведено к едва заметному (без тряски камеры).
 * Все визуальные эффекты и звуки остаются. Состояние читается каждый тик самими эффектами, поэтому переключение во время
 * уже идущей способности сразу возвращает или снова накладывает ограничения; длительность способностей не меняется.
 * При выходе из мира не сбрасывается: это настройка игрока, она сохраняется в файле настроек (ModSettings)
 * и действует после перезапуска игры.
 */
public final class AbilityInfluence {
    private AbilityInfluence() {
    }

    private static boolean enabled = ModSettings.influence();

    /** true: способности механически воздействуют на этого игрока. */
    public static boolean enabled() {
        return enabled;
    }

    /** Переключение из главного меню (и совместимости ради прежний вызов). */
    public static void toggle(Minecraft mc) {
        set(mc, !enabled);
    }

    /** Явно задаёт состояние, сохраняет его и показывает сообщение над панелью предметов. */
    public static void set(Minecraft mc, boolean value) {
        enabled = value;
        ModSettings.setInfluence(value);
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.translatable(enabled
                    ? "gui.cult_illusion.influence.on" : "gui.cult_illusion.influence.off"));
        }
    }
}
