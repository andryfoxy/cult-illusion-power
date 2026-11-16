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
 * Безымянный: постобработка кадра для визуальных помех (assets/cult_illusion/shaders/post/nameless_glitch.fsh,
 * цепочка assets/cult_illusion/post_effect/nameless_glitch.json). Тот же штатный механизм, что у Разума Божьего
 * (MindPost), но отдельная цепочка и отдельные данные: Разум Божий не затрагивается.
 *
 * В игре одновременно работает один постэффект. Безымянный включает свою цепочку только когда текущего постэффекта нет
 * и искажение Разума не идёт; если Разум включился во время помех, его цепочка заменяет нашу, и мы её не трогаем.
 * Выключается только собственная цепочка. Если цепочку не удалось загрузить, NamelessGlitch.extract рисует слои поверх HUD.
 */
public final class NamelessPost {
    private NamelessPost() {
    }

    public static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("cult_illusion", "nameless_glitch");
    private static final String BLOCK = "NamelessConfig";
    private static final Logger LOG = LoggerFactory.getLogger("cult_illusion");

    private static boolean applied = false;
    private static boolean running = false;
    private static boolean failed = false;
    private static long startNanos = 0L;

    /** Шейдер реально работает: слои поверх HUD не рисуются. */
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
    }

    /** Каждый тик: включает цепочку, пока идут помехи, и убирает, когда они закончились. */
    public static void tick(Minecraft mc) {
        GameRenderer renderer = mc.gameRenderer;
        if (renderer == null) {
            return;
        }
        boolean want = NamelessGlitch.isActive() && !failed && mc.player != null && mc.level != null
                && !MindDistortion.isActive();
        if (want) {
            Identifier current = renderer.currentPostEffect();
            if (current == null) {
                ((MindPostHook) renderer).cult_illusion$setPostEffect(CHAIN_ID);
                startNanos = System.nanoTime();
                applied = true;
            } else if (!CHAIN_ID.equals(current)) {
                running = false; // чужой постэффект: не вмешиваемся, помехи рисуются слоями поверх HUD
            }
        } else if (applied) {
            reset();
        }
    }

    /** Перед отрисовкой кадра: интенсивность, время и зерно в блок NamelessConfig. */
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
            float time = (System.nanoTime() - startNanos) / 1.0e9F;
            for (PostPass pass : ((PostChainAccessor) chain).cult_illusion$passes()) {
                Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).cult_illusion$customUniforms();
                GpuBuffer buffer = uniforms.get(BLOCK);
                if (buffer == null) {
                    continue;
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer data = Std140Builder.onStack(stack, 16)
                            .putVec4(NamelessGlitch.level(), time, NamelessGlitch.seed(), 0.0F)
                            .get();
                    GpuDevice device = RenderSystem.getDevice();
                    if ((buffer.usage() & GpuBuffer.USAGE_COPY_DST) == 0) {
                        // Буфер из JSON создаётся без права записи: один раз заменяем его перезаписываемым
                        GpuBuffer writable = device.createBuffer(() -> "cult_illusion nameless uniforms",
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
            LOG.error("[nameless] post-processing failed, falling back to HUD layers", e);
            reset();
        }
    }
}
