package com.melocet.bedrockskinbridge;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Bedrock clients break and place blocks on their own screen before the server answers. When a
 * plugin cancels that (a land claim, spawn protection, a minigame rule), the Java client puts the
 * block back by itself, but a Bedrock client often keeps showing the block as broken (or the
 * placed one as there) until the chunk reloads. Turn it off with ghost-block-fix.enabled: false. So for Bedrock players we send the real blocks
 * again, once on the next tick and once a little later for slow connections.
 */
final class GhostBlockFix implements Listener {

    private static final BlockFace[] AROUND = {BlockFace.SELF, BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final JavaPlugin plugin;
    private final Method isFloodgate; // FloodgateApi#isFloodgatePlayer, null without Floodgate
    private final Object floodgate;

    GhostBlockFix(JavaPlugin plugin) {
        this.plugin = plugin;
        Object api = null;
        Method m = null;
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            api = c.getMethod("getInstance").invoke(null);
            m = c.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable ignored) {
            // no Floodgate: fall back to the UUID shape below
        }
        this.floodgate = api;
        this.isFloodgate = m;
    }

    /** Bedrock player: Floodgate knows (linked accounts too), else a Floodgate-shaped UUID. */
    private boolean bedrock(Player p) {
        if (isFloodgate != null) {
            try {
                return (Boolean) isFloodgate.invoke(floodgate, p.getUniqueId());
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return p.getUniqueId().getMostSignificantBits() == 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        if (e.isCancelled() && bedrock(e.getPlayer())) resend(e.getPlayer(), e.getBlock(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        if (!e.isCancelled() || !bedrock(e.getPlayer())) return;
        resend(e.getPlayer(), e.getBlock(), true);
        resend(e.getPlayer(), e.getBlockAgainst(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        // doors, trapdoors, gates and levers flip on the Bedrock screen before the server says no
        if (e.getClickedBlock() == null || e.useInteractedBlock() != org.bukkit.event.Event.Result.DENY) return;
        if (bedrock(e.getPlayer())) resend(e.getPlayer(), e.getClickedBlock(), false);
    }

    private void resend(Player p, Block b, boolean inventory) {
        Runnable send = () -> {
            if (!p.isOnline() || !p.getWorld().equals(b.getWorld())) return;
            for (BlockFace f : AROUND) {
                Block n = b.getRelative(f);
                p.sendBlockChange(n.getLocation(), n.getBlockData());
            }
            if (inventory) p.updateInventory(); // the block the client thought it used up
        };
        plugin.getServer().getScheduler().runTaskLater(plugin, send, 1L);
        plugin.getServer().getScheduler().runTaskLater(plugin, send, 6L);
    }
}
