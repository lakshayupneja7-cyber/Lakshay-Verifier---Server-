package me.lakshay.verifier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VerifierPlugin extends JavaPlugin implements Listener, PluginMessageListener {

    // Must match client mod exactly
    private static final String CHANNEL = "lakshay:verify";

    // Messages
    private static final String KICK_MISSING = "§cPlease install the server verifier mod, then rejoin.";
    private static final String KICK_BLACKLIST = "§cPlease Remove %s, then rejoin ...";

    // Settings
    private static final int TIMEOUT_SECONDS = 10; // kick if no reply
    private static final boolean REQUIRE_VERIFIER = true; // set false if you want to allow non-verifier players

    // Blacklisted mod IDs (lowercase)
    private final Set<String> blacklist = new HashSet<>(Arrays.asList(
            "freecam",
            "autototem"
    ));

    // State
    private final Map<UUID, Long> deadlineMs = new ConcurrentHashMap<>();
    private final Set<UUID> verified = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        // Plugin messaging
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

        // Timeout checker
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (verified.contains(id)) continue;

                Long dl = deadlineMs.get(id);
                if (dl != null && now >= dl) {
                    deadlineMs.remove(id);
                    if (REQUIRE_VERIFIER) {
                        p.kickPlayer(KICK_MISSING);
                    } else {
                        // allow them but mark as "no verifier"
                        getLogger().warning("Player " + p.getName() + " joined without verifier response.");
                    }
                }
            }
        }, 20L, 20L);

        getLogger().info("LakshayVerifier enabled.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        verified.remove(id);
        deadlineMs.put(id, System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L);

        // Request modlist from client verifier
        p.sendPluginMessage(this, CHANNEL, "REQ".getBytes(StandardCharsets.UTF_8));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        verified.remove(id);
        deadlineMs.remove(id);
    }

    /**
     * Client sends: "MODS|id1,id2,id3"
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;

        String payload = new String(message, StandardCharsets.UTF_8);
        if (!payload.startsWith("MODS|")) return;

        UUID id = player.getUniqueId();
        verified.add(id);
        deadlineMs.remove(id);

        String list = payload.substring("MODS|".length()).trim();
        Set<String> mods = new HashSet<>();
        if (!list.isEmpty()) {
            for (String m : list.split(",")) {
                mods.add(m.trim().toLowerCase(Locale.ROOT));
            }
        }

        // Log full mod list (admin visibility)
        getLogger().info("Mods for " + player.getName() + ": " + String.join(", ", mods));

        // Check blacklist
        List<String> found = new ArrayList<>();
        for (String bad : blacklist) {
            if (mods.contains(bad)) found.add(bad);
        }

        if (!found.isEmpty()) {
            player.kickPlayer(String.format(KICK_BLACKLIST, String.join(", ", found)));
        }
    }
}
