package com.melocet.bedrockskinbridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lightweight helper plugin: captures Bedrock player skins at join and
 * exposes them in two places — over HTTP for the SplitCraft website, and
 * directly through the FancyNpcs API for any plugin that wants to skin
 * an NPC after a Bedrock player.
 *
 * Other Melocet plugins look us up via {@link #getInstance()} (cast from
 * {@code Bukkit.getPluginManager().getPlugin("BedrockSkinBridge")}).
 */
public final class BedrockSkinBridge extends JavaPlugin {

    private static BedrockSkinBridge instance;

    private BedrockSkinTracker skinTracker;
    private BedrockNpcSkin npcSkin;
    private BedrockSkinHttpServer httpServer;

    public static BedrockSkinBridge getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        skinTracker = new BedrockSkinTracker(this);
        npcSkin = new BedrockNpcSkin(this, skinTracker);

        getServer().getPluginManager().registerEvents(new BedrockSkinJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new NpcSkinCommandListener(this), this);

        // Capture skins for anyone already online (server reload case).
        for (Player p : Bukkit.getOnlinePlayers()) {
            skinTracker.recordIfBedrock(p);
        }

        httpServer = new BedrockSkinHttpServer(this, skinTracker);
        httpServer.start();

        getLogger().info("BedrockSkinBridge enabled.");
    }

    @Override
    public void onDisable() {
        if (httpServer != null) httpServer.stop();
        getLogger().info("BedrockSkinBridge disabled.");
    }

    public BedrockSkinTracker skinTracker() { return skinTracker; }
    public BedrockNpcSkin npcSkin() { return npcSkin; }
}
