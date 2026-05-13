package com.melocet.bedrockskinbridge;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures the texture URL + signed property of every Bedrock player at
 * join. Two consumers: the website (texture URL) and FancyNpcs NPC skin
 * setter (texture value + signature).
 *
 * Storage: {@code plugins/BedrockSkinBridge/bedrock-skins.json}. Survives
 * restarts so Bedrock players seen on a previous boot are still served
 * until the next join refreshes the entry.
 */
public final class BedrockSkinTracker {

    /** Full skin descriptor — URL for the website, value+signature for in-game skin setters. */
    public record SkinEntry(String url, String value, String signature) {}

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, SkinEntry> nameToSkin = new ConcurrentHashMap<>();

    public BedrockSkinTracker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bedrock-skins.json");
        load();
    }

    public String getUrl(String name) {
        if (name == null) return null;
        SkinEntry e = nameToSkin.get(name);
        return e == null ? null : e.url();
    }

    public SkinEntry getEntry(String name) {
        if (name == null) return null;
        // Tracker stores under BOTH the Java-side name and the bare bedrock
        // gamertag (see recordIfBedrock). A direct lookup covers both, plus
        // a Floodgate-prefix-aware fallback for legacy entries written
        // before the dual-key change.
        SkinEntry direct = nameToSkin.get(name);
        if (direct != null) return direct;

        // Strip Floodgate's configured prefix if present (handles cases
        // where caller passes the prefixed form but tracker only has bare).
        try {
            String prefix = org.geysermc.floodgate.api.FloodgateApi
                    .getInstance().getPlayerPrefix();
            if (prefix != null && !prefix.isEmpty() && name.startsWith(prefix)) {
                SkinEntry stripped = nameToSkin.get(name.substring(prefix.length()));
                if (stripped != null) return stripped;
            }
        } catch (Throwable ignored) {}

        // Legacy '~' suffix handling for older saves before dual-key.
        if (name.endsWith("~")) {
            return nameToSkin.get(name.substring(0, name.length() - 1));
        }
        return nameToSkin.get(name + "~");
    }

    public void recordIfBedrock(Player player) {
        if (player == null) return;
        org.geysermc.floodgate.api.player.FloodgatePlayer fp;
        try {
            if (Bukkit.getPluginManager().getPlugin("floodgate") == null) return;
            org.geysermc.floodgate.api.FloodgateApi api =
                    org.geysermc.floodgate.api.FloodgateApi.getInstance();
            if (!api.isFloodgatePlayer(player.getUniqueId())) return;
            fp = api.getPlayer(player.getUniqueId());
        } catch (Throwable t) {
            return;
        }

        // The Floodgate API gives us both names: the Java-side display name
        // (with prefix/suffix, e.g. "Melocet~") and the bare bedrock gamertag
        // (e.g. "Melocet"). Storing under both keys makes lookups work
        // regardless of which form the caller has, and adapts automatically
        // if the prefix/suffix is changed in the Floodgate config later.
        final String javaName    = fp != null ? fp.getJavaUsername() : player.getName();
        final String bedrockName = fp != null ? fp.getUsername()     : player.getName();
        final java.util.UUID uuid = player.getUniqueId();

        // Prefer GeyserMC's public skin API: it returns the player's actual
        // Bedrock skin texture. The local profile usually has Floodgate's
        // "GeyserMC" fallback when MineSkin upload fails.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SkinEntry preferred = fetchFromGeyserApi(uuid);
            SkinEntry result = preferred != null ? preferred : extractSkinSafe(player);
            if (result == null) return;
            final SkinEntry entry = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean changed = false;
                if (javaName != null && !javaName.isEmpty()) changed |= recordOne(javaName, entry);
                if (bedrockName != null && !bedrockName.isEmpty()
                        && !bedrockName.equals(javaName)) {
                    changed |= recordOne(bedrockName, entry);
                }
                if (changed) save();
            });
        });
    }

    /** Wrapper for extractSkin that catches any threading-related issue. */
    private SkinEntry extractSkinSafe(Player player) {
        try { return extractSkin(player); }
        catch (Throwable t) { return null; }
    }

    /**
     * Fetch the player's real Bedrock skin from {@code api.geysermc.org}.
     * Uses the Floodgate UUID's lower 64 bits as the XUID. Returns null on
     * any failure so the caller falls back to the local profile property.
     */
    private SkinEntry fetchFromGeyserApi(java.util.UUID uuid) {
        try {
            long xuid = uuid.getLeastSignificantBits();
            if (xuid <= 0) return null; // not a Floodgate UUID
            java.net.URI endpoint = java.net.URI.create(
                    "https://api.geysermc.org/v2/skin/" + xuid);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) endpoint.toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            if (status != 200) return null;
            String body;
            try (java.io.InputStream is = conn.getInputStream()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String textureId = root.has("texture_id") ? root.get("texture_id").getAsString() : null;
            String value     = root.has("value")      ? root.get("value").getAsString()      : null;
            String signature = root.has("signature") && !root.get("signature").isJsonNull()
                    ? root.get("signature").getAsString() : null;
            if (textureId == null || value == null) return null;
            return new SkinEntry(
                    "http://textures.minecraft.net/texture/" + textureId,
                    value,
                    signature);
        } catch (Throwable t) {
            plugin.getLogger().fine("[bedrock-skin] GeyserMC API fetch failed: " + t.getMessage());
            return null;
        }
    }

    private boolean recordOne(String key, SkinEntry entry) {
        SkinEntry prev = nameToSkin.put(key, entry);
        return prev == null
                || !entry.url().equals(prev.url())
                || !java.util.Objects.equals(entry.value(), prev.value());
    }

    private SkinEntry extractSkin(Player player) {
        try {
            for (ProfileProperty prop : player.getPlayerProfile().getProperties()) {
                if (!"textures".equals(prop.getName())) continue;
                String value = prop.getValue();
                String signature = prop.getSignature();
                String decoded = new String(Base64.getDecoder().decode(value),
                        StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(decoded).getAsJsonObject();
                JsonObject textures = root.has("textures") ? root.getAsJsonObject("textures") : null;
                if (textures == null) continue;
                JsonObject skin = textures.has("SKIN") ? textures.getAsJsonObject("SKIN") : null;
                if (skin == null || !skin.has("url")) continue;
                return new SkinEntry(skin.get("url").getAsString(), value, signature);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[bedrock-skin] failed to extract texture for "
                    + player.getName() + ": " + t.getMessage());
        }
        return null;
    }

    private void load() {
        if (!file.exists()) return;
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (content.isBlank()) return;
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                com.google.gson.JsonElement v = e.getValue();
                if (v.isJsonPrimitive()) {
                    nameToSkin.put(e.getKey(), new SkinEntry(v.getAsString(), null, null));
                } else if (v.isJsonObject()) {
                    JsonObject o = v.getAsJsonObject();
                    String url = o.has("url") ? o.get("url").getAsString() : null;
                    String value = o.has("value") ? o.get("value").getAsString() : null;
                    String sig = o.has("signature") && !o.get("signature").isJsonNull()
                            ? o.get("signature").getAsString() : null;
                    if (url != null) nameToSkin.put(e.getKey(), new SkinEntry(url, value, sig));
                }
            }
            plugin.getLogger().info("[bedrock-skin] loaded " + nameToSkin.size() + " entries");
        } catch (Throwable t) {
            plugin.getLogger().warning("[bedrock-skin] failed to load: " + t.getMessage());
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        nameToSkin.forEach((name, entry) -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("url", entry.url());
            if (entry.value() != null) obj.addProperty("value", entry.value());
            if (entry.signature() != null) obj.addProperty("signature", entry.signature());
            root.add(name, obj);
        });
        try {
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            Files.writeString(file.toPath(),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().warning("[bedrock-skin] failed to save: " + ex.getMessage());
        }
    }
}
