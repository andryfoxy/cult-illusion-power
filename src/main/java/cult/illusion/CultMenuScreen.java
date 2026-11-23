package cult.illusion;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Главное меню Cult Illusion (основная версия): категории «Проявления» и «События».
 *
 * «Проявления»: два независимых переключателя («Влияние сил» и «Влияние Безымянного»); они вызывают прежние системы
 * AbilityInfluence и Nameless, состояние видно в подписи кнопки и сохраняется в файле настроек.
 * «События»: «Ритуал» и «Обряд клинков» запускают существующие обработчики RitualAura.requestToggle и
 * BladeCeremony.requestStart. Событие запускается только щелчком мыши по кнопке и сразу закрывает меню; переключатели
 * и вкладки события не запускают. Закрывается клавишей Escape (стандартное поведение экрана) или кнопкой «Закрыть».
 */
public final class CultMenuScreen extends Screen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int MAX_WIDTH = 220;

    private static final int CATEGORY_MANIFEST = 0;
    private static final int CATEGORY_EVENTS = 1;

    // Меню всегда открывается на «Проявлениях»: события запускаются только осознанным переходом на вкладку и щелчком
    private int category = CATEGORY_MANIFEST;

    public CultMenuScreen() {
        super(Component.translatable("gui.cult_illusion.menu.title"));
    }

    @Override
    protected void init() {
        int w = Math.min(MAX_WIDTH, this.width - 20);
        int x = (this.width - w) / 2;
        int rows = 5; // заголовок, вкладки, два пункта, «Закрыть»
        int total = rows * BUTTON_HEIGHT + (rows - 1) * GAP;
        int y = Math.max(4, (this.height - total) / 2);

        Button header = Button.builder(this.title, b -> { }).bounds(x, y, w, BUTTON_HEIGHT).build();
        header.active = false;
        this.addRenderableWidget(header);
        y += BUTTON_HEIGHT + GAP;

        int half = (w - GAP) / 2;
        Button tabManifest = Button.builder(Component.translatable("gui.cult_illusion.menu.cat.manifest"), b -> {
            category = CATEGORY_MANIFEST;
            this.rebuildWidgets();
        }).bounds(x, y, half, BUTTON_HEIGHT).build();
        tabManifest.active = category != CATEGORY_MANIFEST; // выбранная вкладка не нажимается
        this.addRenderableWidget(tabManifest);
        Button tabEvents = Button.builder(Component.translatable("gui.cult_illusion.menu.cat.events"), b -> {
            category = CATEGORY_EVENTS;
            this.rebuildWidgets();
        }).bounds(x + half + GAP, y, w - half - GAP, BUTTON_HEIGHT).build();
        tabEvents.active = category != CATEGORY_EVENTS;
        this.addRenderableWidget(tabEvents);
        y += BUTTON_HEIGHT + GAP;

        if (category == CATEGORY_MANIFEST) {
            this.addRenderableWidget(Button.builder(influenceLabel(), b -> {
                AbilityInfluence.toggle(Minecraft.getInstance());
                b.setMessage(influenceLabel());
            }).bounds(x, y, w, BUTTON_HEIGHT).build());
            y += BUTTON_HEIGHT + GAP;
            this.addRenderableWidget(Button.builder(namelessLabel(), b -> {
                Nameless.toggleAuto(Minecraft.getInstance());
                b.setMessage(namelessLabel());
            }).bounds(x, y, w, BUTTON_HEIGHT).build());
        } else {
            this.addRenderableWidget(Button.builder(Component.translatable("gui.cult_illusion.menu.ritual"),
                    b -> launch(RitualAura::requestToggle)).bounds(x, y, w, BUTTON_HEIGHT).build());
            y += BUTTON_HEIGHT + GAP;
            this.addRenderableWidget(Button.builder(Component.translatable("gui.cult_illusion.menu.ceremony"),
                    b -> launch(BladeCeremony::requestStart)).bounds(x, y, w, BUTTON_HEIGHT).build());
        }
        y += BUTTON_HEIGHT + GAP;

        this.addRenderableWidget(Button.builder(Component.translatable("gui.cult_illusion.menu.close"),
                b -> this.onClose()).bounds(x, y, w, BUTTON_HEIGHT).build());
    }

    // Событие запускается уже после закрытия меню: все существующие обработчики работают, как при прежней клавише
    private static void launch(Consumer<Minecraft> event) {
        Minecraft mc = Minecraft.getInstance();
        ScreenAccess.close(mc);
        event.accept(mc);
    }

    private static Component influenceLabel() {
        return Component.translatable("gui.cult_illusion.menu.influence", state(AbilityInfluence.enabled()));
    }

    private static Component namelessLabel() {
        return Component.translatable("gui.cult_illusion.menu.nameless", state(Nameless.autoEnabled()));
    }

    private static Component state(boolean on) {
        return Component.translatable(on ? "gui.cult_illusion.menu.on" : "gui.cult_illusion.menu.off")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }
}
