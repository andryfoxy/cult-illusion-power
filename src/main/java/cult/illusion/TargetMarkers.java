package cult.illusion;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Метки цели на HUD: клеймо приговора (у всех клиентов, пока идёт способность) и «взгляд власти»
 * (только у владельца меча, пока он держит Kōru yōna samu-sa). Метка рисуется только пока цель в прямой видимости
 * наблюдателя. Игровых эффектов и действий для сервера нет.
 */
public final class TargetMarkers {
    private TargetMarkers() {
    }

    private static Entity gazeTarget = null;
    private static boolean gazeAimed = false;
    private static int gazeSuppress = 0;
    private static int gazeTick = 0;
    private static boolean gazeVisible = false;
    private static boolean gazeFromHit = false; // цель выбрана успешным ударом (а не просто наведением)
    // Попадание по игроку: id цели, возраст ожидания подтверждения и значение hurtTime в момент удара
    private static int hitId = -1;
    private static int hitAge = 0;
    private static int hitHurtTime = 0;
    // Разум Божий: id выбранной цели (-1 = нет), сколько тиков отметка ещё держится после призыва, видна ли цель наблюдателю
    private static int mindTargetId = -1;
    private static int mindTail = 0;
    private static boolean mindVisible = false;
    // Отметка при удержании меча Разума или Шепота: цель, которую выберет активация, её точность и видимость наблюдателю
    private static Entity holdTarget = null;
    private static boolean holdAimed = false;
    private static boolean holdVisible = false;
    private static int holdTick = 0;
    // Тень божья: цель - сам владелец, поэтому метка рисуется внизу экрана, пока меч в руке и способность не идёт
    private static boolean selfMark = false;

    public static void reset() {
        selfMark = false;
        clearMind();
        clearHold();
        gazeTarget = null;
        gazeAimed = false;
        gazeSuppress = 0;
        gazeTick = 0;
        gazeVisible = false;
        gazeFromHit = false;
        hitId = -1;
        hitAge = 0;
        hitHurtTime = 0;
    }

    /**
     * Удар владельца Власти Божьей по сущности (событие атаки по сущности, не простое нажатие кнопки).
     * Мобы и другие не-игроки игнорируются. Попадание подтверждается позже, в tickHit.
     */
    public static void onAttack(Player attacker, Entity victim) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || attacker != mc.player || !CultIllusionClient.isPowerHolder(attacker)) {
            return;
        }
        if (!DivineJudgment.isValidTarget(victim, attacker)) {
            return;
        }
        hitId = victim.getId();
        hitAge = 0;
        hitHurtTime = victim instanceof LivingEntity living ? living.hurtTime : 0;
    }

    /**
     * Подтверждение попадания: у цели началась анимация урона (hurtTime вырос). Один удар обрабатывается один раз:
     * ожидание сбрасывается сразу после подтверждения или по истечении HIT_CONFIRM_TICKS.
     */
    public static void tickHit(Minecraft mc) {
        if (hitId < 0) {
            return;
        }
        Entity entity = mc.level == null ? null : mc.level.getEntity(hitId);
        if (!(entity instanceof LivingEntity living) || mc.player == null || !DivineJudgment.isValidTarget(entity, mc.player)) {
            hitId = -1;
            return;
        }
        if (living.hurtTime > hitHurtTime) {
            hitId = -1;
            if (CultIllusionClient.isPowerHolder(mc.player) && !DivineJudgment.isActive()) {
                // Цель отмечена так же, как при наведении: дальше работает обычная активация способности
                gazeTarget = entity;
                gazeFromHit = true;
                gazeAimed = true;
                gazeSuppress = 0;
                gazeVisible = Visibility.canSee(mc, mc.player.getEyePosition(), entity, mc.player);
            }
            return;
        }
        if (++hitAge > Config.HIT_CONFIRM_TICKS) {
            hitId = -1;
        }
    }

    /** Начало призыва Разума или Шепота: запоминает выбранную цель (id + 1, 0 = цели нет). Новая цель заменяет прежнюю. */
    public static void beginMind(Ability chosen, long targetCode) {
        clearMind();
        if ((chosen == Ability.MIND || chosen == Ability.WHISPER || chosen == Ability.SEAL) && targetCode > 0L) {
            mindTargetId = (int) (targetCode - 1L);
            mindTail = Config.MIND_MARK_TAIL_TICKS;
        }
    }

    private static void clearMind() {
        mindTargetId = -1;
        mindTail = 0;
        mindVisible = false;
    }

    /** Отметка Разума живёт, пока идёт призыв, и ещё MIND_MARK_TAIL_TICKS после пятого удара; вызывается каждый тик после Summon.tick. */
    public static void tickMind(Minecraft mc) {
        if (mindTargetId < 0) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            clearMind();
            return;
        }
        if (Summon.isMindActive() || Summon.isWhisperActive() || Summon.isSealActive()) {
            mindTail = Config.MIND_MARK_TAIL_TICKS;
        } else if (--mindTail < 0) {
            clearMind();
            return;
        }
        Entity target = mindTarget(mc);
        mindVisible = target != null && Visibility.canSee(mc, mc.player.getEyePosition(), target, mc.player);
    }

    private static void clearHold() {
        holdTarget = null;
        holdAimed = false;
        holdVisible = false;
        holdTick = 0;
    }

    /**
     * Отметка выбора цели у владельца Black Iron Greatsword (Разум), Hiro no Tsuki (Шепот) или Lycanbane (Печать; жертва выбирается как у Шепота): пока меч в руке, над игроком,
     * которого выберет активация, постоянно видна фиолетовая отметка (без активации способности). Цель определяется той же
     * функцией, что и при активации. Во время призыва и сразу после него работает отметка Разума/Шепота (mindTargetId).
     */
    public static void tickHold(Minecraft mc, boolean holdingMind, boolean holdingWhisper) {
        if ((!holdingMind && !holdingWhisper) || mc.player == null || mc.level == null
                || Summon.isActive() || mindTargetId >= 0) {
            clearHold();
            return;
        }
        if (holdTick++ % Config.GAZE_INTERVAL_TICKS == 0) {
            Entity picked = holdingWhisper ? WhisperCurse.pickTarget(mc, mc.player) : DivineJudgment.pickTarget(mc, mc.player);
            if (picked != null && !DivineJudgment.isValidTarget(picked, mc.player)) {
                picked = null; // отметку получают только игроки
            }
            holdTarget = picked;
            holdAimed = picked != null; // цель выбирается только прямым взглядом, поэтому выбранная всегда «наведена»
        }
        if (holdTarget != null && (holdTarget.isRemoved() || !holdTarget.isAlive())) {
            holdTarget = null;
            holdAimed = false;
        }
        holdVisible = holdTarget != null && Visibility.canSee(mc, mc.player.getEyePosition(), holdTarget, mc.player);
    }

    /** Тень божья действует на самого владельца: пока меч в руке и способность не идёт, внизу экрана видна отметка «Цель: вы». */
    public static void tickSelf(boolean holdingShadow) {
        Minecraft mc = Minecraft.getInstance();
        selfMark = holdingShadow && mc.player != null && !Summon.isActive();
    }

    // Цель Разума: только живой игрок, не сам наблюдатель (мобы и прочие сущности отметки не получают)
    private static Entity mindTarget(Minecraft mc) {
        if (mindTargetId < 0 || mc.level == null || mc.player == null) {
            return null;
        }
        Entity entity = mc.level.getEntity(mindTargetId);
        return DivineJudgment.isValidTarget(entity, mc.player) ? entity : null;
    }

    /** Видимость отмеченной цели для локального наблюдателя; вызывается каждый тик. */
    public static void tickVisibility(Minecraft mc) {
        Entity target = gazeTarget();
        gazeVisible = target != null && mc.player != null
                && Visibility.canSee(mc, mc.player.getEyePosition(), target, mc.player);
    }

    /** После исполнения приговора метка исчезает и не возвращается сразу. */
    public static void suppressGaze() {
        gazeTarget = null;
        gazeFromHit = false;
        gazeAimed = false;
        gazeSuppress = Config.GAZE_SUPPRESS_TICKS;
    }

    /** Запомненная цель взгляда или null. */
    public static Entity gazeTarget() {
        if (gazeTarget != null && (gazeTarget.isRemoved() || !gazeTarget.isAlive())) {
            gazeTarget = null;
        }
        return gazeTarget;
    }

    /** Игрок, выбранный успешным ударом владельца Власти, пока он не сброшен; иначе null. Простое наведение сюда не попадает. */
    public static Entity struckTarget() {
        Entity target = gazeTarget();
        return gazeFromHit ? target : null;
    }

    public static void tickGaze(Minecraft mc, boolean holdingPowerSword) {
        if (gazeSuppress > 0) {
            gazeSuppress--;
        }
        if (mc.player == null || mc.level == null || !holdingPowerSword || gazeSuppress > 0 || DivineJudgment.isActive()) {
            gazeTarget = null;
            gazeAimed = false;
            return;
        }
        if (gazeTick++ % Config.GAZE_INTERVAL_TICKS != 0) {
            return;
        }
        Entity aimed = DivineJudgment.pickPlayerInCone(mc, mc.player);
        if (aimed != null) {
            gazeTarget = aimed;
            gazeFromHit = false;
            gazeAimed = true;
            return;
        }
        gazeAimed = false;
        // Без прямого взгляда остаётся только цель, выбранная ударом; запомненное наведение сбрасывается
        if (gazeTarget != null
                && (!gazeFromHit || gazeTarget.isRemoved() || !gazeTarget.isAlive()
                || mc.player.distanceTo(gazeTarget) > Config.JUDGMENT_RANGE * 1.5)) {
            gazeTarget = null;
        }
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        Entity mindTarget = mindVisible ? mindTarget(mc) : null;
        if (mindTarget != null) {
            Vec3 above = mindTarget.getPosition(partial).add(0.0, mindTarget.getBbHeight() + Config.MIND_MARK_HEIGHT_OFFSET, 0.0);
            drawMarker(graphics, mc, above, partial, true, 0xFF000000 | Config.MIND_MARK_CORE_RGB, 0xFF000000 | Config.MIND_MARK_BORDER_RGB);
        }
        if (mindTarget == null && holdVisible && holdTarget != null) {
            Vec3 above = holdTarget.getPosition(partial).add(0.0, holdTarget.getBbHeight() + Config.MIND_MARK_HEIGHT_OFFSET, 0.0);
            int alpha = holdAimed ? 0xFF : Config.HOLD_MARK_ALPHA_DIM;
            drawMarker(graphics, mc, above, partial, holdAimed, (alpha << 24) | Config.MIND_MARK_CORE_RGB, (alpha << 24) | Config.MIND_MARK_BORDER_RGB);
        }
        if (selfMark) {
            int cx = graphics.guiWidth() / 2;
            int cy = graphics.guiHeight() - 62;
            diamond(graphics, cx, cy, 6, 0xFFFFFFFF);
            diamond(graphics, cx, cy, 5, 0xFF000000 | Config.MIND_MARK_CORE_RGB);
            Component label = Component.translatable("gui.cult_illusion.mark.self");
            graphics.text(mc.font, label, cx - mc.font.width(label) / 2, cy + 9, 0xFFFFFFFF, true);
        }
        Vec3 judged = DivineJudgment.markerPosition(partial);
        if (judged != null) {
            if (DivineJudgment.isMarkerVisible()) {
                drawMarker(graphics, mc, judged, partial, true);
            }
            return;
        }
        Entity target = gazeTarget();
        if (target != null && gazeVisible) {
            Vec3 pos = target.getPosition(partial).add(0.0, target.getBbHeight() + 0.6, 0.0);
            drawMarker(graphics, mc, pos, partial, gazeAimed);
        }
    }

    private static void drawMarker(GuiGraphicsExtractor g, Minecraft mc, Vec3 pos, float partial, boolean bright) {
        int core = bright ? 0xFFB070FF : (0xC0 << 24) | Config.MARKER_RGB;
        int border = bright ? 0xFFFFFFFF : 0xD0A070FF;
        drawMarker(g, mc, pos, partial, bright, core, border);
    }

    private static void drawMarker(GuiGraphicsExtractor g, Minecraft mc, Vec3 pos, float partial, boolean bright, int core, int border) {
        Vec3 eye = mc.player.getEyePosition(partial);
        Vec3 forward = mc.player.getViewVector(partial);
        Vec3 right = forward.cross(new Vec3(0.0, 1.0, 0.0));
        double rightLength = right.length();
        if (rightLength < 1.0e-4) {
            return;
        }
        right = right.scale(1.0 / rightLength);
        Vec3 up = right.cross(forward);

        Vec3 v = pos.subtract(eye);
        double z = v.dot(forward);
        double x = v.dot(right);
        double y = v.dot(up);

        int width = g.guiWidth();
        int height = g.guiHeight();
        double tanY = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        double tanX = tanY * width / (double) height;

        boolean onScreen = false;
        double px = width / 2.0;
        double py = height / 2.0;
        double dirX = 0.0;
        double dirY = 0.0;
        if (z > 0.05) {
            double nx = x / z / tanX;
            double ny = y / z / tanY;
            if (Math.abs(nx) <= 0.95 && Math.abs(ny) <= 0.95) {
                onScreen = true;
                px = width / 2.0 + nx * width / 2.0;
                py = height / 2.0 - ny * height / 2.0;
            } else {
                dirX = nx;
                dirY = -ny;
            }
        } else {
            dirX = x;
            dirY = -y;
        }

        if (onScreen) {
            int r = bright ? 6 : 4;
            diamond(g, (int) px, (int) py, r + 1, border);
            diamond(g, (int) px, (int) py, r, core);
            return;
        }

        // Цель вне поля зрения: указатель на краю экрана в её сторону
        double length = Math.sqrt(dirX * dirX + dirY * dirY);
        if (length < 1.0e-4) {
            dirX = 0.0;
            dirY = 1.0;
            length = 1.0;
        }
        dirX /= length;
        dirY /= length;
        double limitX = width / 2.0 - Config.GAZE_EDGE_MARGIN;
        double limitY = height / 2.0 - Config.GAZE_EDGE_MARGIN;
        double scale = Math.min(Math.abs(dirX) > 1.0e-4 ? limitX / Math.abs(dirX) : Double.MAX_VALUE,
                Math.abs(dirY) > 1.0e-4 ? limitY / Math.abs(dirY) : Double.MAX_VALUE);
        px = width / 2.0 + dirX * scale;
        py = height / 2.0 + dirY * scale;

        diamond(g, (int) px, (int) py, 4, border);
        diamond(g, (int) px, (int) py, 3, core);
        String glyph = Math.abs(dirX) > Math.abs(dirY) ? (dirX > 0 ? "\u25B6" : "\u25C0") : (dirY > 0 ? "\u25BC" : "\u25B2");
        int gx = (int) px + (int) (dirX * 10.0) - mc.font.width(glyph) / 2;
        int gy = (int) py + (int) (dirY * 10.0) - mc.font.lineHeight / 2;
        g.text(mc.font, Component.literal(glyph), gx, gy, border, false);
    }

    private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int argb) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(cx - half, cy + i, cx + half + 1, cy + i + 1, argb);
        }
    }
}
