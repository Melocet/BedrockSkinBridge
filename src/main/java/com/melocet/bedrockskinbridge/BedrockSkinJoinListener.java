package com.melocet.bedrockskinbridge;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Captures the Bedrock player's skin texture URL 1 second after join, so
 * Floodgate has had time to install the player's profile.
 */
public final class BedrockSkinJoinListener implements Listener {

    private final BedrockSkinBridge plugin;

    public BedrockSkinJoinListener(BedrockSkinBridge plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.skinTracker().recordIfBedrock(e.getPlayer()), 20L);
    }
}
