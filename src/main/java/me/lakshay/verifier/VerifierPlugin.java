package me.lakshay.verifier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VerifierPlugin extends JavaPlugin implements Listener, PluginMessageListener {

    private static final String CHANNEL = "lakshay:verify";

    private static final boolean REQUIRE_VERIFIER = true;

    private static final String KICK_MISSING = "§cPlease install the server verifier mod, then rejoin.";
    private static final String KICK_BLACKLIST = "§cPlease Remove %s, then rejoin ...";

    // ✅ More forgiving timing
    private static final int TIMEOUT_SECONDS = 25;     // total time allowed
    private static final int FIRST_SEND_DELAY_TICKS = 40; // 2 seconds after join
    private static final int RESEND_EVERY_TICKS = 40;  // resend every 2 seconds
    private static final int MAX_ATTEMPTS = 6;         // up to 6 tries

    private final Set<String> blacklist = new HashSet<>(Arrays.asList(
            "freecam",
            "autototem"
    ));

    private final Map<UUID, Long> deadlineMs = new ConcurrentHashMap<>();
    private final Set<UUID> verified = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

        // Timeout checker (kicks if no verification)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (verified.contains(id)) continue;

                Long dl = deadlineMs.get(id);
                if (dl != null && now >= dl) {
                    deadlineMs.remove(id);
                    attempts.remove(id);

                    if (REQUIRE_VERIFIER) {
                        getLogger().warning("No verifier response from " + p.getName() + " (timed out)");
                        p.kickPlayer(KICK_MISSING);
                    } else {
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
        attempts.put(id, 0);
        deadlineMs.put(id, System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L);

        // ✅ Send later + resend (because first packets can get lost)
        Bukkit.getScheduler().runTaskLater(this, () -> startResendLoop(p), FIRST_SEND_DELAY_TICKS);
    }

    private void startResendLoop(Player p) {
        if (!p.isOnline()) return;

        UUID id = p.getUniqueId();
        if (verified.contains(id)) return;

        int a = attempts.getOrDefault(id, 0);
        if (a >= MAX_ATTEMPTS) return;

        attempts.put(id, a + 1);

        // Debug
        getLogger().info("Sending REQ to " + p.getName() + " (attempt " + (a + 1) + "/" + MAX_ATTEMPTS + ")");

        p.sendPluginMessage(this, CHANNEL, "REQ".getBytes(StandardCharsets.UTF_8));

        Bukkit.getScheduler().runTaskLater(this, () -> startResendLoop(p), RESEND_EVERY_TICKS);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        verified.remove(id);
        deadlineMs.remove(id);
        attempts.remove(id);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;

        String payload = new String(message, StandardCharsets.UTF_8);

        // Debug: log ANY message on that channel
        getLogger().info("Received on " + CHANNEL + " from " + player.getName() + ": " + payload);

        if (!payload.startsWith("MODS|")) return;

        UUID id = player.getUniqueId();
        verified.add(id);
        deadlineMs.remove(id);
        attempts.remove(id);

        String list = payload.substring("MODS|".length()).trim();
        Set<String> mods = new HashSet<>();
        if (!list.isEmpty()) {
            for (String m : list.split(",")) mods.add(m.trim().toLowerCase(Locale.ROOT));
        }

        getLogger().info("Mods for " + player.getName() + ": " + String.join(", ", mods));

        List<String> found = new ArrayList<>();
        for (String bad : blacklist) {
            if (mods.contains(bad)) found.add(bad);
        }

        if (!found.isEmpty()) {
            player.kickPlayer(String.format(KICK_BLACKLIST, String.join(", ", found)));
        }
    }
}
