package com.melocet.bedrockskinbridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;
import java.util.UUID;

/**
 * Intercepts manual {@code /npc skin <npc> <bedrock-name>} commands and
 * routes them through FancyNpcs API when the target name is a Bedrock
 * player we have cached. Otherwise the command runs unchanged.
 *
 * Without this, FancyNpcs sees the Floodgate-suffixed name (e.g.
 * {@code Melocet~}), tries to look it up via Mojang, gets 404, and falls
 * back to Steve.
 *
 * Handles three command roots — {@code /npc}, {@code /fnpc},
 * {@code /fancynpcs} — and both player-issued and console-issued forms.
 */
public final class NpcSkinCommandListener implements Listener {

    private final BedrockSkinBridge plugin;

    public NpcSkinCommandListener(BedrockSkinBridge plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (tryIntercept(e.getMessage())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                    "[Bedrock] Skin applied via texture override.",
                    net.kyori.adventure.text.format.NamedTextColor.GREEN));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        // Console-prefixed slash isn't included; tryIntercept tolerates both.
        if (tryIntercept(e.getCommand())) {
            e.setCancelled(true);
            e.getSender().sendMessage("[Bedrock] Skin applied via texture override.");
        }
    }

    /**
     * If the command is {@code /npc skin <npc> <bedrock-name>} and the name
     * resolves to a cached Bedrock skin, applies it and returns true.
     * Otherwise returns false so the original command runs normally.
     */
    private boolean tryIntercept(String raw) {
        if (raw == null) return false;
        String body = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = body.trim().split("\\s+");
        // Expect: <root> skin <npc> <name>  (4 tokens)
        if (parts.length < 4) return false;
        String root = parts[0].toLowerCase(Locale.ROOT);
        // Strip plugin:command prefix if present (e.g., "fancynpcs:npc").
        int colon = root.indexOf(':');
        if (colon > 0) root = root.substring(colon + 1);
        if (!root.equals("npc") && !root.equals("fnpc") && !root.equals("fancynpcs")) return false;
        if (!"skin".equalsIgnoreCase(parts[1])) return false;

        String npcName = parts[2];
        String targetName = parts[3];

        // Only intercept if we have a cached Bedrock skin for this target.
        if (plugin.skinTracker().getEntry(targetName) == null) return false;

        // The UUID is needed by the underlying API call. For an online
        // bedrock player we have it; otherwise we synthesize a fake
        // Floodgate-style UUID (MSB=0) so the canSkin guard passes.
        UUID uuid = lookupUuid(targetName);
        return plugin.npcSkin().apply(npcName, uuid, targetName);
    }

    private UUID lookupUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        // Synthetic Floodgate-style UUID (MSB=0) so isFloodgateId returns true.
        // The actual value doesn't matter — BedrockNpcSkin.apply only uses
        // the name to look up the cached SkinEntry.
        return new UUID(0L, 1L);
    }
}
