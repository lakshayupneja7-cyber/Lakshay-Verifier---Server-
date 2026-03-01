package me.lakshay.verifier;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VerifierPlugin extends JavaPlugin implements Listener, PluginMessageListener {

    private static final String CHANNEL = "lakshay:verify";

    // loaded from config.yml
    private boolean requireVerifier;
    private int timeoutSeconds;
    private long firstSendDelayTicks;
    private long resendEveryTicks;
    private int maxAttempts;
    private Set<String> blacklist = new HashSet<>();

    // messages.yml
    private File messagesFile;
    private FileConfiguration messages;

    // state
    private final Map<UUID, Long> deadlineMs = new ConcurrentHashMap<>();
    private final Set<UUID> verified = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // 1) create folder + config.yml
        saveDefaultConfig();

        // 2) create messages.yml
        setupMessagesFile();

        // 3) load settings
        reloadAll();

        // 4) events + channels
        Bukkit.getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

        // 5) timeout checker
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (verified.contains(id)) continue;

                Long dl = deadlineMs.get(id);
                if (dl != null && now >= dl) {
                    deadlineMs.remove(id);
                    attempts.remove(id);

                    if (requireVerifier) {
                        getLogger().warning("No verifier response from " + p.getName() + " (timed out)");
                        p.kickPlayer(color(msg("timeout", "§cVerification timed out. Please rejoin."), p, ""));
                    }
                }
            }
        }, 20L, 20L);

        getLogger().info("LakshayVerifier enabled.");
    }

    private void reloadAll() {
        reloadConfig();
        loadMainConfig();
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        getLogger().info("Loaded blacklist: " + blacklist);
    }

    private void loadMainConfig() {
        requireVerifier = getConfig().getBoolean("requireVerifier", true);
        timeoutSeconds = getConfig().getInt("timeoutSeconds", 25);
        firstSendDelayTicks = getConfig().getLong("firstSendDelayTicks", 40L);
        resendEveryTicks = getConfig().getLong("resendEveryTicks", 40L);
        maxAttempts = getConfig().getInt("maxAttempts", 6);

        blacklist.clear();
        for (String s : getConfig().getStringList("blacklist")) {
            if (s == null) continue;
            blacklist.add(s.trim().toLowerCase(Locale.ROOT));
        }
    }

    private void setupMessagesFile() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false); // copies from src/main/resources/messages.yml
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("verifier")) return false;

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("verifier.reload")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            reloadAll();
            sender.sendMessage("§aLakshayVerifier reloaded.");
            return true;
        }

        sender.sendMessage("§7Usage: §f/verifier reload");
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        verified.remove(id);
        attempts.put(id, 0);
        deadlineMs.put(id, System.currentTimeMillis() + timeoutSeconds * 1000L);

        Bukkit.getScheduler().runTaskLater(this, () -> startResendLoop(p), firstSendDelayTicks);
    }

    private void startResendLoop(Player p) {
        if (!p.isOnline()) return;

        UUID id = p.getUniqueId();
        if (verified.contains(id)) return;

        int a = attempts.getOrDefault(id, 0);
        if (a >= maxAttempts) {
            // give up; timeout checker will kick later (if requireVerifier)
            return;
        }

        attempts.put(id, a + 1);

        p.sendPluginMessage(this, CHANNEL, "REQ".getBytes(StandardCharsets.UTF_8));

        Bukkit.getScheduler().runTaskLater(this, () -> startResendLoop(p), resendEveryTicks);
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

        if (!payload.startsWith("MODS|")) return;

        UUID id = player.getUniqueId();
        verified.add(id);
        deadlineMs.remove(id);
        attempts.remove(id);

        String list = payload.substring("MODS|".length()).trim();

        Set<String> mods = new HashSet<>();
        if (!list.isEmpty()) {
            for (String m : list.split(",")) {
                mods.add(m.trim().toLowerCase(Locale.ROOT));
            }
        }

        // Find blacklisted mods
        List<String> found = new ArrayList<>();
        for (String bad : blacklist) {
            if (mods.contains(bad)) found.add(bad);
        }

        if (!found.isEmpty()) {
            // Per-mod message (first match wins)
            String chosen = null;
            for (String bad : found) {
                String per = messages.getString("perMod." + bad);
                if (per != null && !per.isBlank()) {
                    chosen = per;
                    break;
                }
            }

            if (chosen == null) {
                chosen = msg("blacklistedDefault", "§cPlease remove {mods}, then rejoin ...");
            }

            String modsText = String.join(", ", found);
            player.kickPlayer(color(chosen, player, modsText));
            return;
        }

        // If not blacklisted -> allow
    }

    private String msg(String key, String def) {
        String v = messages.getString(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private String color(String s, Player p, String modsText) {
        return s
                .replace("{player}", p.getName())
                .replace("{mods}", modsText)
                .replace("{plural}", modsText.contains(",") ? "s" : "")
                .replace("&", "§");
    }
}
