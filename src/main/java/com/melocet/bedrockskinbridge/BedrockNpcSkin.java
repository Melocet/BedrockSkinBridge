package com.melocet.bedrockskinbridge;

import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.skins.SkinData;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies a Bedrock player's captured texture to a FancyNpcs NPC directly
 * via the FancyNpcs API, bypassing the {@code /npc skin} command's Mojang
 * lookup that always 404s for Floodgate names. Other plugins call us when
 * they would otherwise dispatch {@code /npc skin <npc> <bedrock-name>}.
 */
public final class BedrockNpcSkin {

    private final JavaPlugin plugin;
    private final BedrockSkinTracker tracker;

    public BedrockNpcSkin(JavaPlugin plugin, BedrockSkinTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    /**
     * Decides whether this player should be skinned via Bedrock texture
     * instead of /npc skin. The UUID is the source of truth — name alone
     * is ambiguous between Java {@code Melocet} and Bedrock {@code Melocet~}
     * which Floodgate stripped/added at various times.
     */
    public boolean canSkin(java.util.UUID uuid, String playerName) {
        if (uuid == null || !isFloodgateId(uuid)) return false;
        // Look up the texture from the tracker (handles both ~ and bare keys).
        return tracker.getEntry(playerName) != null;
    }

    /**
     * Applies the Bedrock player's captured texture to {@code npcName}.
     * Caller must have verified the UUID is Floodgate-managed via
     * {@link #canSkin(java.util.UUID, String)} first.
     */
    public boolean apply(String npcName, java.util.UUID uuid, String playerName) {
        try {
            if (Bukkit.getPluginManager().getPlugin("FancyNpcs") == null) return false;
            BedrockSkinTracker.SkinEntry entry = tracker.getEntry(playerName);
            if (entry == null || entry.value() == null) return false;

            Npc npc = FancyNpcsPlugin.get().getNpcManager().getNpc(npcName);
            if (npc == null) {
                plugin.getLogger().fine("[bedrock-npc] no NPC named " + npcName);
                return false;
            }

            SkinData skin = new SkinData(
                    "bedrock-" + playerName,
                    SkinData.SkinVariant.AUTO,
                    entry.value(),
                    entry.signature());
            npc.getData().setSkinData(skin);
            // updateForAll() doesn't refresh skin texture on Java clients —
            // they need the entity removed and respawned to pick up the new
            // GameProfile. Then persist so the change survives /npc reload.
            npc.removeForAll();
            npc.spawnForAll();
            FancyNpcsPlugin.get().getNpcManager().saveNpcs(false);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[bedrock-npc] failed to apply skin "
                    + playerName + " -> " + npcName + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * A Floodgate UUID has its 64 most-significant bits all zero — that's
     * how Floodgate constructs them from the bedrock XUID. Cheap check, no
     * Floodgate API call needed (works even if Floodgate is missing).
     */
    private static boolean isFloodgateId(java.util.UUID uuid) {
        return uuid != null && uuid.getMostSignificantBits() == 0L;
    }
}
