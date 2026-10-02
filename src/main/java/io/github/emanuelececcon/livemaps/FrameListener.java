package io.github.emanuelececcon.livemaps;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/** Follows maps going into and out of item frames. */
final class FrameListener implements Listener {

    private final LiveMapsPlugin plugin;
    private final FrameRegistry registry;

    FrameListener(LiveMapsPlugin plugin, FrameRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<ItemFrame> frames = new ArrayList<>();
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemFrame frame) {
                frames.add(frame);
            }
        }
        registry.entitiesLoaded(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ(), frames);
    }

    /** A player put a map in, or took it out. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameChange(PlayerItemFrameChangeEvent event) {
        if (event.getAction() != PlayerItemFrameChangeEvent.ItemFrameChangeAction.ROTATE) {
            observeNextTick(event.getItemFrame());
        }
    }

    /** Arrows and explosions knock the item out of a frame without a player involved. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            observeNextTick(frame);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameBreak(HangingBreakEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            registry.forgetFrame(frame.getUniqueId());
        }
    }

    /** The event fires before the item changes, so look again once it has. */
    private void observeNextTick(ItemFrame frame) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            // A frame that is gone was broken (HangingBreakEvent) or unloaded, which keeps it.
            if (frame.isValid()) {
                registry.observe(frame);
            }
        });
    }
}
