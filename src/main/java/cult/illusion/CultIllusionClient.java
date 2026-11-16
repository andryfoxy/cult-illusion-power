package cult.illusion;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

public class CultIllusionClient implements ClientModInitializer {
    private static final String MOD_ID = "cult_illusion";

    // Имена как в ресурспаке (assets/minecraft/items/netherite_sword.json)
    private static final String NAME_KORU = "K\u014Dru y\u014Dna samu-sa"; // Kōru yōna samu-sa: Власть Божья
    private static final String NAME_BLACK_IRON = "Black Iron Greatsword"; // Разум Божий
    private static final String NAME_HAIKYO = "Haikyo de no seikatsu"; // Тень божья (имя как в ресурспаке; регистр не важен)
    private static final String NAME_HIRO = "Mystical Spellblade"; // Шепот божий (регистр не важен)
    private static final String NAME_LYCAN = "Lycanbane"; // Печать божья (регистр не важен)

    private static final int SWORD_NONE = 0;
    private static final int SWORD_KORU = 1;
    private static final int SWORD_BLACK = 2;
    private static final int SWORD_HAIKYO = 3;
    private static final int SWORD_HIRO = 4;
    private static final int SWORD_LYCAN = 5;

    // Радиус (в блоках), в котором способность видят другие игроки с модом
    private static final double RADIUS = 32.0;

    // Метки в общем чате: неразрывные пробелы (U+00A0), в игре отображаются пустым местом.
    // Длина серии определяет тип метки: 13 = Власть Божья, 15 = Разум Божий, 17 = Тень божья, 19 = Шепот божий, 21 = Печать божья;
    // церемония передачи силы: 23 = стадия Тени божьей, 25 = стадия Шепота божьего, 27 = стадия Печати божьей;
    // постоянный ритуал: 29 = начало, 31 = завершение.
    private static final char NBSP = '\u00A0';
    private static final int LEN_POWER = 13;
    private static final int LEN_MIND = 15;
    private static final int LEN_SHADOW = 17;
    private static final int LEN_WHISPER = 19;
    private static final int LEN_SEAL = 21;
    private static final int LEN_CEREMONY_SHADOW = 23;
    private static final int LEN_CEREMONY_WHISPER = 25;
    private static final int LEN_CEREMONY_SEAL = 27;
    private static final int LEN_RITUAL_START = 29;
    private static final int LEN_RITUAL_END = 31;
    private static final String MARKER_MIN = String.valueOf(NBSP).repeat(LEN_POWER);
    private static final String MARKER_POWER = String.valueOf(NBSP).repeat(LEN_POWER);
    private static final String MARKER_MIND = String.valueOf(NBSP).repeat(LEN_MIND);
    private static final String MARKER_SHADOW = String.valueOf(NBSP).repeat(LEN_SHADOW);
    private static final String MARKER_WHISPER = String.valueOf(NBSP).repeat(LEN_WHISPER);
    private static final String MARKER_SEAL = String.valueOf(NBSP).repeat(LEN_SEAL);
    private static final String[] MARKER_CEREMONY = {
            String.valueOf(NBSP).repeat(LEN_CEREMONY_SHADOW),
            String.valueOf(NBSP).repeat(LEN_CEREMONY_WHISPER),
            String.valueOf(NBSP).repeat(LEN_CEREMONY_SEAL)
    };
    private static final String MARKER_RITUAL_START = String.valueOf(NBSP).repeat(LEN_RITUAL_START);
    private static final String MARKER_RITUAL_END = String.valueOf(NBSP).repeat(LEN_RITUAL_END);

    // Данные эффекта идут сразу после метки невидимыми символами нулевой ширины:
    // U+200B = бит 0, U+200C = бит 1. Формат: 16 бит seed + 32 бита цели (id сущности + 1, 0 = цели нет).
    // Если сервер или чат-плагин вырежет эти символы, получатель считает всё локально, как раньше.
    private static final char BIT_ZERO = '\u200B';
    private static final char BIT_ONE = '\u200C';
    private static final int SEED_BITS = 16;
    private static final int TARGET_BITS = 32;
    private static final int PAYLOAD_BITS = SEED_BITS + TARGET_BITS;

    private static KeyMapping effectKey;
    private static KeyMapping namelessKey;  // Безымянный: включение и выключение автоматических проявлений
    private static KeyMapping ceremonyKey;  // церемония передачи силы клинкам
    private static KeyMapping ritualKey;    // постоянный ритуал Безымянного
    private static KeyMapping influenceKey; // влияние способностей на самого игрока: локальное вкл/выкл

    @Override
    public void onInitializeClient() {
        effectKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cult_illusion.effect",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_R,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))));

        // Безымянный: клавиша включения и выключения автоматических проявлений (та же категория, что и у способностей)
        namelessKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cult_illusion.nameless_toggle",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_H,
                effectKey.getCategory()));

        // Церемония передачи силы клинкам: клавиша очередной стадии
        ceremonyKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cult_illusion.ceremony",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_J,
                effectKey.getCategory()));

        // Постоянный ритуал: включение и завершение
        ritualKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cult_illusion.ritual",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_K,
                effectKey.getCategory()));

        // Влияние способностей на самого игрока: локальный переключатель (та же категория; по умолчанию клавиша не назначена, назначается в настройках управления)
        influenceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cult_illusion.influence",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                effectKey.getCategory()));

        ClientTickEvents.END_CLIENT_TICK.register(CultIllusionClient::onTick);
        // Шепот божий: переназначение клавиш у жертвы. Регистрируется раньше оков, чтобы блокировка оков всегда имела приоритет
        ClientTickEvents.START_CLIENT_TICK.register(WhisperCurse::startTick);
        // Божественные оковы: блокировка управления у цели сбрасывается до обработки клавиш игрой
        ClientTickEvents.START_CLIENT_TICK.register(DivineChains::startTick);

        // Выбор цели ударом: фактическое попадание подтверждается позже (TargetMarkers.tickHit)
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (ShadowCloak.blocksAttack(player, entity)) {
                return InteractionResult.FAIL; // Тень божья в невидимости не может атаковать игроков
            }
            TargetMarkers.onAttack(player, entity);
            return InteractionResult.PASS;
        });

        // Метка активации от другого игрока в обычном чате: скрыть строку и запустить способность у себя
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String text = message.getString();
            int[] marker = findMarker(text);
            if (marker == null) {
                return true;
            }
            onMarkerReceived(marker[0], sender == null ? null : sender.id(), null, decodePayload(text, marker[2]));
            return false;
        });

        // Метка в сообщении, которое сервер (чат-плагин) отправил как системное: ник ищем в тексте перед меткой
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String text = message.getString();
            int[] marker = findMarker(text);
            if (marker == null) {
                return true;
            }
            onMarkerReceived(marker[0], null, text.substring(0, marker[1]), decodePayload(text, marker[2]));
            return false;
        });

        // Печать божья: пока она действует, жертва не может отправить обычное сообщение чата (страховка к блокировке экрана чата).
        // Метки способностей (начинаются с неразрывного пробела) пропускаются: другие способности работают как раньше.
        ClientSendMessageEvents.ALLOW_CHAT.register(message ->
                !SealController.isChatLocked() || (!message.isEmpty() && message.charAt(0) == NBSP));

        // Экранные эффекты ритуала и стадий церемонии в их области (по краям экрана, под интерфейсами)
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath(MOD_ID, "ritual_screen"),
                RitualScreenFx::extract);

        // Безымянный: запасные слои помех поверх HUD (рисуются только если шейдер постобработки не работает)
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath(MOD_ID, "nameless_glitch"),
                NamelessGlitch::extract);

        // Надпись призыва и вспышка рисуются поверх HUD
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath(MOD_ID, "overlay"),
                OverlayHud::extract);

        // Иллюзорные двойники: дополнительная отрисовка модели игрока
        LevelRenderEvents.COLLECT_SUBMITS.register(MirrorDoubles::render);
        // Божественные оковы: звенья цепей на модели игрока
        LevelRenderEvents.COLLECT_SUBMITS.register(DivineChains::render);
        // Печать божья: цепи вокруг головы жертвы (та же геометрия звеньев, что у цепей Власти)
        LevelRenderEvents.COLLECT_SUBMITS.register(SealChains::render);
        // Безымянный: силуэт-наблюдатель (клиентская модель игрока, в мир не добавляется)
        LevelRenderEvents.COLLECT_SUBMITS.register(Nameless::render);
        // Единственная фигура Безымянного в локальных ритуалах (стадии J)
        LevelRenderEvents.COLLECT_SUBMITS.register(NamelessFigure::render);
    }

    private static void onTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            resetAll();
            while (effectKey.consumeClick()) {
                // сброс накопленных нажатий
            }
            while (namelessKey.consumeClick()) {
                // сброс накопленных нажатий
            }
            while (ceremonyKey.consumeClick()) {
                // сброс накопленных нажатий
            }
            while (ritualKey.consumeClick()) {
                // сброс накопленных нажатий
            }
            while (influenceKey.consumeClick()) {
                // сброс накопленных нажатий
            }
            return;
        }

        ScreenShake.tick();
        OverlayHud.tick();
        TargetMarkers.tickGaze(mc, swordType(mc.player) == SWORD_KORU);
        TargetMarkers.tickHit(mc);
        TargetMarkers.tickVisibility(mc);
        Summon.tick(mc);
        TargetMarkers.tickMind(mc);
        // Отметка цели при удержании Black Iron Greatsword (Разум), Hiro no Tsuki (Шепот) или Lycanbane (Печать); у Тени цель - сам владелец
        int held = swordType(mc.player);
        TargetMarkers.tickHold(mc, held == SWORD_BLACK, held == SWORD_HIRO || held == SWORD_LYCAN);
        TargetMarkers.tickSelf(held == SWORD_HAIKYO);
        MindDistortion.tick(mc);
        MindPost.tick(mc);
        DivineJudgment.tick(mc);
        DivineChains.tick(mc);
        PowerScreen.tick(mc);
        DivineAura.tick(mc);
        MirrorDoubles.tick(mc);
        ShadowCloak.tick(mc);
        WhisperCurse.tick(mc);
        SealController.tick(mc);
        Nameless.tick(mc);
        BladeCeremony.tick(mc);
        RitualAura.tick(mc);

        // Каждое нажатие даёт ровно одну активацию: удержание клавиши (автоповтор) ложных срабатываний не вызывает
        if (KeyGuard.pressed(namelessKey)) {
            Nameless.toggleAuto(mc);
        }
        if (KeyGuard.pressed(ceremonyKey)) {
            BladeCeremony.requestStart(mc);
        }
        if (KeyGuard.pressed(ritualKey)) {
            RitualAura.requestToggle(mc);
        }
        if (KeyGuard.pressed(influenceKey)) {
            AbilityInfluence.toggle(mc);
        }
        if (KeyGuard.pressed(effectKey)) {
            activateEffect(mc);
        }
    }

    // Активация способности меча в руке (вызывается один раз за нажатие, см. KeyGuard)
    private static void activateEffect(Minecraft mc) {
        {
            int sword = swordType(mc.player);
            if (sword == SWORD_NONE || abilityBusy()) {
                return;
            }
            int seed = Fx.RANDOM.nextInt(1 << SEED_BITS);
            if (sword == SWORD_KORU) {
                DivineJudgment.selectTarget(mc, mc.player);
                Summon.start(Ability.POWER, null, seed, 0L);
                // другим клиентам уходит и выбранная цель, чтобы все видели удар в одном и том же месте
                mc.player.connection.sendChat(MARKER_POWER + encodePayload(seed, DivineJudgment.targetCode()));
            } else if (sword == SWORD_BLACK) {
                // противник: только игрок под прямым взглядом (как цель приговора), уходит другим клиентам вместе с seed
                Entity opponent = DivineJudgment.pickTarget(mc, mc.player);
                long opponentCode = opponent == null ? 0L : (long) opponent.getId() + 1L;
                Summon.start(Ability.MIND, null, seed, opponentCode);
                mc.player.connection.sendChat(MARKER_MIND + encodePayload(seed, opponentCode));
            } else if (sword == SWORD_HAIKYO) {
                Summon.start(Ability.SHADOW, null, seed, 0L);
                mc.player.connection.sendChat(MARKER_SHADOW + encodePayload(seed, 0L));
            } else if (sword == SWORD_HIRO) {
                // жертва: только игрок под прямым взглядом (как у Разума); без прямого взгляда цели нет
                Entity victim = WhisperCurse.pickTarget(mc, mc.player);
                long victimCode = victim == null ? 0L : (long) victim.getId() + 1L;
                Summon.start(Ability.WHISPER, null, seed, victimCode);
                mc.player.connection.sendChat(MARKER_WHISPER + encodePayload(seed, victimCode));
            } else if (sword == SWORD_LYCAN) {
                // жертва выбирается как у Шепота (только игрок под прямым взглядом; мобы не выбираются), цель уходит в том же формате
                Entity victim = WhisperCurse.pickTarget(mc, mc.player);
                long victimCode = victim == null ? 0L : (long) victim.getId() + 1L;
                Summon.start(Ability.SEAL, null, seed, victimCode);
                mc.player.connection.sendChat(MARKER_SEAL + encodePayload(seed, victimCode));
            }
        }
    }

    /** Обладатель Власти Божьей: меч Kōru yōna samu-sa в основной руке (видно всем клиентам по экипировке). */
    public static boolean isPowerHolder(Player player) {
        return swordType(player) == SWORD_KORU;
    }

    private static boolean abilityBusy() {
        Minecraft mc = Minecraft.getInstance();
        return Summon.isActive() || DivineJudgment.isActive() || ScreenShake.isActive()
                || (mc.player != null && (MirrorDoubles.isActive(mc.player.getUUID()) || ShadowCloak.isActive(mc.player.getUUID())));
    }

    // type: 1 = Власть Божья, 2 = Разум Божий, 3 = Тень божья, 4 = Шепот божий, 5 = Печать божья; 6-8 = стадии церемонии (Тень, Шепот, Печать).
    // Игрок, активировавший способность, должен быть рядом.
    // payload: {seed, цель} из чата или null, если данных в сообщении нет
    private static void onMarkerReceived(int type, UUID senderId, String prefix, long[] payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // Постоянный ритуал: 9 = начало (инициатор рядом), 10 = завершение (применяется к уже идущему ритуалу той же сессии)
        if (type >= 9) {
            Player ritualSender = findNearbySender(mc, senderId, prefix, type == 9 ? RADIUS : Double.MAX_VALUE);
            if (ritualSender != null && payload != null) {
                if (type == 9) {
                    RitualAura.receiveStart(mc, ritualSender, (int) payload[0], payload[1]);
                } else {
                    RitualAura.receiveEnd(mc, ritualSender, payload[1]);
                }
            }
            return;
        }
        // Церемония передачи силы не зависит от способностей и не блокируется ими
        if (type >= 6) {
            Player ceremonySender = findNearbySender(mc, senderId, prefix, RADIUS);
            if (ceremonySender != null && payload != null) {
                BladeCeremony.receive(mc, ceremonySender, type - 5, (int) payload[0], payload[1]);
            }
            return;
        }
        if (abilityBusy()) {
            return;
        }
        // Цель приговора выбирается в пределах JUDGMENT_RANGE, поэтому саму цель способность должна достигать
        // и дальше обычного радиуса отображения
        boolean targetsMe = payload != null && payload[1] == (long) mc.player.getId() + 1L;
        Player sender = findNearbySender(mc, senderId, prefix, targetsMe ? Math.max(RADIUS, Config.JUDGMENT_RANGE) : RADIUS);
        if (sender == null) {
            return;
        }
        int seed = payload != null ? (int) payload[0] : Fx.RANDOM.nextInt(1 << SEED_BITS);
        if (type == 1) {
            DivineJudgment.selectTarget(mc, sender, payload != null ? payload[1] : DivineJudgment.REMOTE_UNKNOWN);
            Summon.start(Ability.POWER, sender.getUUID(), seed, 0L);
        } else if (type == 2) {
            Summon.start(Ability.MIND, sender.getUUID(), seed, payload != null ? payload[1] : 0L);
        } else if (type == 3) {
            Summon.start(Ability.SHADOW, sender.getUUID(), seed, 0L);
        } else if (type == 4) {
            Summon.start(Ability.WHISPER, sender.getUUID(), seed, payload != null ? payload[1] : 0L);
        } else if (type == 5) {
            Summon.start(Ability.SEAL, sender.getUUID(), seed, payload != null ? payload[1] : 0L);
        }
    }

    // Другой игрок (не вы) в радиусе RADIUS: по UUID, а если его нет, то по нику в тексте перед меткой
    private static Player findNearbySender(Minecraft mc, UUID senderId, String prefix, double range) {
        if (senderId != null) {
            if (senderId.equals(mc.player.getUUID())) {
                return null;
            }
            Player other = mc.level.getPlayerByUUID(senderId);
            return other != null && mc.player.distanceTo(other) <= range ? other : null;
        }
        if (prefix == null) {
            return null;
        }
        String ownName = mc.player.getName().getString();
        for (Player other : mc.level.players()) {
            String name = other.getName().getString();
            if (name.equals(ownName) || !prefix.contains(name)) {
                continue;
            }
            if (mc.player.distanceTo(other) <= range) {
                return other;
            }
        }
        return null;
    }

    // Возвращает {тип, позиция} или null, если метки нет
    private static int[] findMarker(String text) {
        int index = text.indexOf(MARKER_MIN);
        if (index < 0) {
            return null;
        }
        int length = 0;
        while (index + length < text.length() && text.charAt(index + length) == NBSP) {
            length++;
        }
        if (length == LEN_POWER) {
            return new int[]{1, index, index + length};
        }
        if (length == LEN_MIND) {
            return new int[]{2, index, index + length};
        }
        if (length == LEN_SHADOW) {
            return new int[]{3, index, index + length};
        }
        if (length == LEN_WHISPER) {
            return new int[]{4, index, index + length};
        }
        if (length == LEN_SEAL) {
            return new int[]{5, index, index + length};
        }
        if (length == LEN_CEREMONY_SHADOW) {
            return new int[]{6, index, index + length};
        }
        if (length == LEN_CEREMONY_WHISPER) {
            return new int[]{7, index, index + length};
        }
        if (length == LEN_CEREMONY_SEAL) {
            return new int[]{8, index, index + length};
        }
        if (length == LEN_RITUAL_START) {
            return new int[]{9, index, index + length};
        }
        if (length == LEN_RITUAL_END) {
            return new int[]{10, index, index + length};
        }
        return null;
    }

    /** Скрытая метка стадии церемонии (stage 1..3): seed и уникальный идентификатор запуска в 32 битах. */
    static void sendCeremonyMarker(Minecraft mc, int stage, int seed, long launchId) {
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendChat(MARKER_CEREMONY[stage - 1] + encodePayload(seed, launchId));
        }
    }

    /** Скрытая метка начала (start) или завершения постоянного ритуала: seed и идентификатор сессии. */
    static void sendRitualMarker(Minecraft mc, boolean start, int seed, long session) {
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendChat((start ? MARKER_RITUAL_START : MARKER_RITUAL_END) + encodePayload(seed, session));
        }
    }

    /** Клавиша ритуала (для обработки нажатия при открытой наковальне). */
    public static KeyMapping ritualKey() {
        return ritualKey;
    }

    /** Клавиша церемонии (для обработки нажатия при открытой наковальне). */
    public static KeyMapping ceremonyKey() {
        return ceremonyKey;
    }

    private static String encodePayload(int seed, long target) {
        StringBuilder sb = new StringBuilder(PAYLOAD_BITS);
        appendBits(sb, seed, SEED_BITS);
        appendBits(sb, target, TARGET_BITS);
        return sb.toString();
    }

    private static void appendBits(StringBuilder sb, long value, int bits) {
        for (int i = bits - 1; i >= 0; i--) {
            sb.append(((value >> i) & 1L) != 0L ? BIT_ONE : BIT_ZERO);
        }
    }

    // Читает данные сразу после метки; {seed, цель} или null, если их нет или они повреждены
    private static long[] decodePayload(String text, int start) {
        if (start + PAYLOAD_BITS > text.length()) {
            return null;
        }
        int[] sizes = {SEED_BITS, TARGET_BITS};
        long[] values = new long[2];
        int pos = start;
        for (int part = 0; part < sizes.length; part++) {
            long value = 0L;
            for (int i = 0; i < sizes[part]; i++) {
                char c = text.charAt(pos++);
                if (c != BIT_ZERO && c != BIT_ONE) {
                    return null;
                }
                value = (value << 1) | (c == BIT_ONE ? 1L : 0L);
            }
            values[part] = value;
        }
        return values;
    }

    // Закрытие мира, выход с сервера: все временные визуальные состояния сбрасываются
    private static void resetAll() {
        Summon.reset();
        DivineJudgment.reset();
        MirrorDoubles.reset();
        ShadowCloak.reset();
        WhisperCurse.reset();
        SealController.reset(); // снимает блокировку чата и микрофона
        Nameless.reset();       // Безымянный: модель, звуки, помехи и таймер (при следующем входе интервал выбирается заново)
        MindDistortion.reset();
        MindPost.reset();
        ScreenShake.reset();
        OverlayHud.reset();
        TargetMarkers.reset();
        DivineChains.reset();
        PowerScreen.reset();
        DivineAura.reset();
        BladeCeremony.reset();
        RitualAura.reset();
    }

    private static int swordType(Player player) {
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(Items.NETHERITE_SWORD)) {
            return SWORD_NONE;
        }
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) {
            return SWORD_NONE;
        }
        String text = name.getString();
        if (text.equals(NAME_KORU)) {
            return SWORD_KORU;
        }
        if (text.equals(NAME_BLACK_IRON)) {
            return SWORD_BLACK;
        }
        if (text.equalsIgnoreCase(NAME_HAIKYO)) {
            return SWORD_HAIKYO;
        }
        if (text.equalsIgnoreCase(NAME_HIRO)) {
            return SWORD_HIRO;
        }
        if (text.equalsIgnoreCase(NAME_LYCAN)) {
            return SWORD_LYCAN;
        }
        return SWORD_NONE;
    }
}
