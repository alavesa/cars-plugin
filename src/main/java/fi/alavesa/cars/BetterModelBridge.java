package fi.alavesa.cars;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.platform.PlatformEntity;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/**
 * Soft link to the BetterModel plugin, a `provided` compile-time dependency that may not be on the server
 * at runtime. Every method that touches a BetterModel class is only ever reached once {@link #available()}
 * has confirmed the plugin is present - so when BetterModel is absent these classes are never linked and
 * Cars runs exactly as before on its own ItemDisplay + CustomModelData body.
 *
 * API used (BetterModel 3.4.1): BetterModel.modelOrNull(name) -> ModelRenderer, then
 * renderer.create(BukkitAdapter.adapt(entity)) -> EntityTracker (auto-follows the source entity's position
 * and body yaw), closed later with EntityTracker.close() / the entity's EntityTrackerRegistry.close().
 */
final class BetterModelBridge {

    private BetterModelBridge() { }

    /** True if the BetterModel plugin is installed. Touches only Bukkit, so it is always safe to call. */
    static boolean available() {
        return Bukkit.getPluginManager().getPlugin("BetterModel") != null;
    }

    /** Attach the named BetterModel to a Bukkit entity (the car's base), returning a tracker handle to keep
     *  for later removal, or null if the model name is unknown / anything goes wrong. The tracker follows the
     *  entity's position and body rotation on its own, so attaching to the moving base is enough. */
    static Object apply(Entity entity, String modelName) {
        try {
            ModelRenderer renderer = BetterModel.modelOrNull(modelName);
            if (renderer == null) return null;
            PlatformEntity platformEntity = BukkitAdapter.adapt(entity);
            return renderer.create(platformEntity);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Detach a model previously attached with {@link #apply}. Closes the kept tracker handle and, as a
     *  belt-and-braces fallback (e.g. after a restart re-created the tracker from BetterModel's own store),
     *  also closes any tracker registry still bound to the entity. */
    static void remove(Object trackerHandle, Entity entity) {
        try {
            if (trackerHandle instanceof EntityTracker tracker && !tracker.isClosed()) tracker.close();
        } catch (Throwable ignored) { }
        try {
            EntityTrackerRegistry registry = BetterModel.registryOrNull(entity.getUniqueId());
            if (registry != null && !registry.isClosed()) registry.close();
        } catch (Throwable ignored) { }
    }
}
