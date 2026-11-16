package cult.illusion;

import cult.illusion.mixin.PostChainAccessor;
import cult.illusion.mixin.PostPassAccessor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Разум Божий: настоящая постобработка кадра у выбранной жертвы (шейдер assets/cult_illusion/shaders/post/mind_distortion.fsh,
 * цепочка assets/cult_illusion/post_effect/mind_distortion.json): волновое искажение, хроматическое раздвоение, глитч
 * и остаточные изображения. Включается штатным механизмом игры (GameRenderer.setPostEffect), пока активен
 * MindDistortion, и выключается вместе с ним. Интенсивность и время передаются в шейдер каждый кадр.
 *
 * Только клиент и только жертва: MindDistortion запускается лишь у выбранного игрока. Если цепочку не удалось
 * загрузить или обновить, об этом пишется в лог, а MindDistortion рисует прежние слои поверх HUD.
 */
public final class MindPost {
    private MindPost() {
    }

    public static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("cult_illusion", "mind_distortion");
    private static final String BLOCK = "MindConfig";
    private static final Logger LOG = LoggerFactory.getLogger("cult_illusion");

    private static boolean applied = false;   // мы включили цепочку и должны её выключить
    private static boolean running = false;   // шейдер получил значения хотя бы в одном кадре
    private static boolean failed = false;
    private static long startNanos = 0L;
    private static long lastNanos = 0L;
    private static int frames = 0;

    /** Шейдер реально работает, прежние слои поверх HUD в этом случае не рисуются. */
    public static boolean isRunning() {
        return running && applied && !failed;
    }

    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (applied && mc != null && mc.gameRenderer != null && CHAIN_ID.equals(mc.gameRenderer.currentPostEffect())) {
            mc.gameRenderer.clearPostEffect();
        }
        applied = false;
        running = false;
        frames = 0;
    }

    /** Каждый тик: включает цепочку, пока идёт искажение, и убирает, когда оно закончилось (220 тиков или сброс). */
    public static void tick(Minecraft mc) {
        GameRenderer renderer = mc.gameRenderer;
        if (renderer == null) {
            return;
        }
        boolean want = MindDistortion.isActive() && !failed && mc.player != null && mc.level != null;
        if (want) {
            if (!CHAIN_ID.equals(renderer.currentPostEffect())) {
                ((MindPostHook) renderer).cult_illusion$setPostEffect(CHAIN_ID);
                frames = 0;
                startNanos = System.nanoTime();
                lastNanos = startNanos;
            }
            applied = true;
        } else if (applied) {
            reset();
        }
    }

    /** Перед отрисовкой кадра: интенсивность, время и длительность кадра в блок MindConfig каждого прохода. */
    public static void beforeRender() {
        if (!applied || failed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || !CHAIN_ID.equals(mc.gameRenderer.currentPostEffect())) {
            running = false;
            return;
        }
        try {
            PostChain chain = mc.getShaderManager().getPostChain(CHAIN_ID, LevelTargetBundle.MAIN_TARGETS);
            if (chain == null) {
                running = false;
                return;
            }
            long now = System.nanoTime();
            float time = (now - startNanos) / 1.0e9F;
            float dt = (now - lastNanos) / 1.0e9F;
            lastNanos = now;
            // первые кадры история пуста (или осталась от прошлого запуска): следы в них не смешиваются
            float historyValid = frames >= 2 ? 1.0F : 0.0F;
            frames++;
            float level = MindDistortion.renderLevel();

            for (PostPass pass : ((PostChainAccessor) chain).cult_illusion$passes()) {
                Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).cult_illusion$customUniforms();
                GpuBuffer buffer = uniforms.get(BLOCK);
                if (buffer == null) {
                    continue;
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer data = Std140Builder.onStack(stack, 32)
                            .putVec4(level, time, historyValid, dt)
                            .putVec4(Config.MIND_POST_WAVE, Config.MIND_POST_CHROMA, Config.MIND_POST_GLITCH, Config.MIND_POST_TRAIL)
                            .get();
                    GpuDevice device = RenderSystem.getDevice();
                    if ((buffer.usage() & GpuBuffer.USAGE_COPY_DST) == 0) {
                        // Буфер из JSON создаётся без права записи: один раз заменяем его перезаписываемым
                        GpuBuffer writable = device.createBuffer(() -> "cult_illusion mind uniforms",
                                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, data);
                        uniforms.put(BLOCK, writable);
                        buffer.close();
                    } else {
                        device.createCommandEncoder().writeToBuffer(buffer.slice(), data);
                    }
                }
            }
            running = true;
        } catch (RuntimeException e) {
            failed = true;
            running = false;
            LOG.error("[mind] post-processing failed, falling back to HUD layers", e);
            reset();
        }
    }
}
