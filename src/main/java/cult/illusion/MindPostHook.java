package cult.illusion;

import net.minecraft.resources.Identifier;

/** Доступ к закрытому методу GameRenderer.setPostEffect (реализуется миксином GameRendererMixin). */
public interface MindPostHook {
    void cult_illusion$setPostEffect(Identifier id);
}
