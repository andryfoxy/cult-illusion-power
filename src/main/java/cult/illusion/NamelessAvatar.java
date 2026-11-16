package cult.illusion;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.UUID;

/**
 * Модель Безымянного: стандартная модель игрока Steve (широкие руки) с предоставленным скином
 * assets/cult_illusion/textures/entity/nameless.png. Это клиентский объект для отрисовки: он никогда не добавляется
 * в мир, не тикает, не имеет столкновений и существует только в Nameless (на клиенте одного игрока).
 * Предметов в руках нет (инвентарь пуст), ник и индикаторы не отображаются.
 */
public final class NamelessAvatar extends RemotePlayer {
    /** Модель по ТЗ: широкие руки (Steve). Единственное место, где выбирается тип модели. */
    public static final PlayerModelType MODEL = PlayerModelType.WIDE;

    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("cult_illusion", "entity/nameless");
    private static final Identifier TEXTURE_PATH = Identifier.fromNamespaceAndPath("cult_illusion", "textures/entity/nameless.png");
    private static final PlayerSkin SKIN = new PlayerSkin(new ClientAsset.ResourceTexture(TEXTURE_ID, TEXTURE_PATH), null, null, MODEL, true);

    public NamelessAvatar(ClientLevel level) {
        super(level, new GameProfile(UUID.randomUUID(), "Nameless"));
    }

    @Override
    public PlayerSkin getSkin() {
        return SKIN;
    }

    /** Ник не отображается. */
    @Override
    public boolean shouldShowName() {
        return false;
    }

    @Override
    public boolean isCustomNameVisible() {
        return false;
    }

    /** Все слои скина (шляпа, куртка, рукава, штаны) показываются так, как нарисованы в текстуре. Плаща нет. */
    @Override
    public boolean isModelPartShown(PlayerModelPart part) {
        return part != PlayerModelPart.CAPE;
    }
}
