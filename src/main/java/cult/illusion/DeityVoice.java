package cult.illusion;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Редкие реплики ритуала для тех, кто находится рядом (в чате, мелким курсивом): Безымянный не говорит, но мир
 * отвечает на его взгляд. Только на этом клиенте, без сетевых сообщений; тексты берутся из lang-файлов.
 */
final class DeityVoice {
    private DeityVoice() {
    }

    private static final double HEARING_RANGE = 24.0;

    /** Показывает реплику key (gui.cult_illusion.deity.<key>), если до центра эффекта не больше 24 блоков. */
    static void say(Minecraft mc, double distance, String key) {
        if (mc.player != null && distance <= HEARING_RANGE) {
            mc.player.sendSystemMessage(Component.translatable("gui.cult_illusion.deity." + key).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        }
    }
}
