package com.example.limitedlife;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class LimitedLife extends JavaPlugin implements Listener {

    private static final String[] TEAMS = {"ll_darkgreen", "ll_green", "ll_yellow", "ll_red"};

    private final Map<UUID, Integer> seconds = new HashMap<>();
    private final Set<UUID> boogeymen = new HashSet<>();   // active, not yet cured
    private final Set<UUID> cured = new HashSet<>();       // cured this round
    private boolean running = false;
    private File dataFile;
    private YamlConfiguration data;
    private Scoreboard board;
    private int saveCounter = 0;
    private NamespacedKey tntKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        running = data.getBoolean("running", false);
        if (data.isConfigurationSection("times")) {
            for (String key : data.getConfigurationSection("times").getKeys(false)) {
                seconds.put(UUID.fromString(key), data.getInt("times." + key));
            }
        }
        for (String s : data.getStringList("boogeymen")) boogeymen.add(UUID.fromString(s));
        for (String s : data.getStringList("cured")) cured.add(UUID.fromString(s));

        board = Bukkit.getScoreboardManager().getMainScoreboard();
        setupTeam(TEAMS[0], ChatColor.DARK_GREEN);
        setupTeam(TEAMS[1], ChatColor.GREEN);
        setupTeam(TEAMS[2], ChatColor.YELLOW);
        setupTeam(TEAMS[3], ChatColor.RED);

        tntKey = new NamespacedKey(this, "life_series_tnt");
        setupRecipes();

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    /** Recipe changes apply on server start (restart to re-add anything you removed). */
    private void setupRecipes() {
        if (getConfig().getBoolean("recipes.remove-enchanting-table", true)) {
            Bukkit.removeRecipe(NamespacedKey.minecraft("enchanting_table"));
        }

        Bukkit.removeRecipe(tntKey); // avoid duplicates
        if (getConfig().getBoolean("recipes.life-series-tnt", true)) {
            ShapelessRecipe r = new ShapelessRecipe(tntKey, new ItemStack(Material.TNT));
            r.addIngredient(4, Material.SAND);
            r.addIngredient(4, Material.PAPER);
            r.addIngredient(1, Material.GUNPOWDER);
            Bukkit.addRecipe(r);
        }
        if (!getConfig().getBoolean("recipes.keep-vanilla-tnt", true)) {
            Bukkit.removeRecipe(NamespacedKey.minecraft("tnt"));
        }
    }

    private void setupTeam(String name, ChatColor color) {
        Team t = board.getTeam(name);
        if (t == null) t = board.registerNewTeam(name);
        t.setColor(color);
    }

    private void saveData() {
        data.set("running", running);
        data.set("times", null);
        for (Map.Entry<UUID, Integer> e : seconds.entrySet()) {
            data.set("times." + e.getKey(), e.getValue());
        }
        List<String> b = new ArrayList<>();
        for (UUID u : boogeymen) b.add(u.toString());
        data.set("boogeymen", b);
        List<String> c = new ArrayList<>();
        for (UUID u : cured) c.add(u.toString());
        data.set("cured", c);
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data: " + ex.getMessage());
        }
    }

    // ---------- helpers ----------

    private int startSeconds() {
        return (int) (getConfig().getDouble("start-hours", 6) * 3600);
    }

    private int timeOf(UUID id) {
        return seconds.getOrDefault(id, startSeconds());
    }

    /** Adds (or removes, if negative) time. Gains respect max-time-hours. */
    private void adjust(UUID id, int delta) {
        int cur = timeOf(id);
        int next = cur + delta;
        int cap = (int) (getConfig().getDouble("max-time-hours", 0) * 3600);
        if (delta > 0 && cap > 0 && next > cap) next = Math.max(cur, cap);
        seconds.put(id, next);
    }

    private String msg(String key, String def, String... repl) {
        String s = getConfig().getString("messages." + key, def);
        for (int i = 0; i + 1 < repl.length; i += 2) s = s.replace(repl[i], repl[i + 1]);
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private GameMode outMode() {
        try {
            return GameMode.valueOf(getConfig().getString("elimination.gamemode", "SPECTATOR").toUpperCase());
        } catch (IllegalArgumentException ex) {
            return GameMode.SPECTATOR;
        }
    }

    private ChatColor tierColor(int secs) {
        double hours = secs / 3600.0;
        if (hours >= getConfig().getDouble("tiers.dark-green-min-hours", 6)) return ChatColor.DARK_GREEN;
        if (hours >= getConfig().getDouble("tiers.green-min-hours", 4)) return ChatColor.GREEN;
        if (hours >= getConfig().getDouble("tiers.yellow-min-hours", 2)) return ChatColor.YELLOW;
        return ChatColor.RED;
    }

    private boolean isRed(UUID id) {
        return tierColor(timeOf(id)) == ChatColor.RED;
    }

    private String teamFor(ChatColor c) {
        if (c == ChatColor.DARK_GREEN) return TEAMS[0];
        if (c == ChatColor.GREEN) return TEAMS[1];
        if (c == ChatColor.YELLOW) return TEAMS[2];
        return TEAMS[3];
    }

    private String format(int secs) {
        secs = Math.max(0, secs);
        return String.format("%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "Unknown" : n;
    }

    private void revive(Player p) {
        if (p.getGameMode() == outMode() || p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
        }
    }

    // ---------- core timer ----------

    private void tick() {
        if (running) {
            boolean warn = getConfig().getBoolean("warnings.enabled", true);
            List<Integer> warnMins = getConfig().getIntegerList("warnings.minutes");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR) continue;
                int cur = timeOf(p.getUniqueId());
                if (cur <= 0) continue;
                int left = cur - 1;
                seconds.put(p.getUniqueId(), left);
                if (left <= 0) {
                    eliminate(p);
                } else if (warn && left % 60 == 0 && warnMins.contains(left / 60)) {
                    p.sendMessage(msg("warning", "&eYou only have {time} left!", "{time}", (left / 60) + " minute(s)"));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 0.8f);
                }
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) updateDisplay(p);

        if (++saveCounter >= 60) {
            saveCounter = 0;
            saveData();
        }
    }

    private void eliminate(Player p) {
        seconds.put(p.getUniqueId(), 0);
        boogeymen.remove(p.getUniqueId());
        p.setGameMode(outMode());
        if (getConfig().getBoolean("elimination.broadcast", true)) {
            Bukkit.broadcastMessage(msg("eliminated", "&4{player} has run out of time!", "{player}", p.getName()));
        }
        if (getConfig().getBoolean("elimination.lightning", true)) {
            p.getWorld().strikeLightningEffect(p.getLocation());
        }
        if (getConfig().getBoolean("elimination.sound", true)) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.playSound(o.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1f);
            }
        }
    }

    private void updateDisplay(Player p) {
        int left = timeOf(p.getUniqueId());
        ChatColor color = tierColor(left);

        if (getConfig().getBoolean("display.name-colors", true)) {
            Team team = board.getTeam(teamFor(color));
            if (team != null && !team.hasEntry(p.getName())) {
                for (String n : TEAMS) {
                    Team other = board.getTeam(n);
                    if (other != null) other.removeEntry(p.getName());
                }
                team.addEntry(p.getName());
            }
        }
        if (getConfig().getBoolean("display.tab-list", true)) {
            p.setPlayerListName(color + p.getName() + ChatColor.GRAY + " [" + format(left) + "]");
        }
        if (getConfig().getBoolean("display.action-bar", true)) {
            String bar = color + "Time left: " + format(left);
            if (boogeymen.contains(p.getUniqueId()) && getConfig().getBoolean("boogeyman.action-bar-hint", true)) {
                bar += ChatColor.DARK_RED + "  |  Boogeyman: kill a player!";
            }
            p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(bar));
        }
    }

    // ---------- boogeyman ----------

    private void rollBoogeymen(CommandSender s, int count) {
        List<Player> pool = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR && timeOf(p.getUniqueId()) > 0) pool.add(p);
        }
        if (pool.size() < 2) {
            s.sendMessage(ChatColor.RED + "Need at least 2 active players online.");
            return;
        }
        count = Math.max(1, Math.min(count, pool.size() - 1));
        Collections.shuffle(pool);
        final List<Player> chosen = new ArrayList<>(pool.subList(0, count));

        boogeymen.clear();
        cured.clear();
        Bukkit.broadcastMessage(ChatColor.GOLD + "The Boogeyman is about to be chosen...");

        final int countdown = Math.max(1, getConfig().getInt("boogeyman.countdown-seconds", 10));
        new BukkitRunnable() {
            int t = countdown;

            @Override
            public void run() {
                if (t > 0) {
                    for (Player o : Bukkit.getOnlinePlayers()) {
                        o.sendTitle(ChatColor.GOLD + "" + t, ChatColor.GRAY + "Choosing the Boogeyman...", 0, 25, 5);
                        o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    }
                    t--;
                    return;
                }
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (chosen.contains(o) && o.isOnline()) {
                        boogeymen.add(o.getUniqueId());
                        o.sendTitle(ChatColor.DARK_RED + "You are the Boogeyman",
                                ChatColor.GRAY + "Kill a player before the session ends, or drop to red.", 10, 100, 20);
                        o.sendMessage(ChatColor.DARK_RED + "You are the Boogeyman. Kill any other player to be cured. "
                                + "If the session ends and you haven't, you are reduced to red.");
                        o.playSound(o.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 1f, 0.7f);
                    } else {
                        o.sendTitle(ChatColor.GREEN + "You are NOT the Boogeyman", "", 10, 60, 10);
                    }
                }
                Bukkit.broadcastMessage(ChatColor.GOLD + "The Boogeyman has been chosen. Watch your back.");
                cancel();
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void endBoogeymanRound(CommandSender s) {
        if (boogeymen.isEmpty() && cured.isEmpty()) {
            s.sendMessage(ChatColor.RED + "No Boogeyman round is active.");
            return;
        }
        int cap = (int) (getConfig().getDouble("boogeyman.fail-time-hours", 1.5) * 3600);
        boolean announce = getConfig().getBoolean("boogeyman.announce-failures", true);
        int failed = 0;
        for (UUID id : boogeymen) {
            if (timeOf(id) > cap) seconds.put(id, cap);
            failed++;
            if (announce) {
                Bukkit.broadcastMessage(ChatColor.DARK_RED + nameOf(id)
                        + " failed as the Boogeyman and has been reduced to red!");
            }
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendTitle(ChatColor.RED + "You failed", ChatColor.GRAY + "You are now red.", 10, 80, 20);
            }
        }
        Bukkit.broadcastMessage(ChatColor.GOLD + "The Boogeyman round is over. "
                + failed + " failed, " + cured.size() + " cured.");
        boogeymen.clear();
        cured.clear();
        saveData();
    }

    private void cureBoogeyman(Player killer) {
        boogeymen.remove(killer.getUniqueId());
        cured.add(killer.getUniqueId());
        int bonus = getConfig().getInt("boogeyman.cure-bonus-minutes", 0) * 60;
        if (bonus > 0) adjust(killer.getUniqueId(), bonus);
        killer.sendTitle(ChatColor.GREEN + "You are cured!", ChatColor.GRAY + "You claimed a victim.", 10, 80, 20);
        killer.sendMessage(ChatColor.GREEN + "You are no longer the Boogeyman.");
    }

    // ---------- events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        seconds.putIfAbsent(p.getUniqueId(), startSeconds());
        if (getConfig().getBoolean("recipes.life-series-tnt", true)) p.discoverRecipe(tntKey);
        if (seconds.get(p.getUniqueId()) <= 0) p.setGameMode(outMode());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!running) return;
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        boolean pvp = killer != null && killer != victim;

        // Work out colors BEFORE any time changes hands.
        boolean victimRed = isRed(victim.getUniqueId());
        boolean killerRed = pvp && isRed(killer.getUniqueId());

        int penalty = 0;
        if (!getConfig().getBoolean("death.pvp-only", false) || pvp) {
            penalty += getConfig().getInt("death.penalty-minutes", 30) * 60;
        }
        if (pvp) penalty += getConfig().getInt("death.pvp-extra-penalty-minutes", 0) * 60;
        if (penalty > 0) {
            adjust(victim.getUniqueId(), -penalty);
            victim.sendMessage(msg("time-lost", "&cYou lost {minutes} minutes.", "{minutes}", String.valueOf(penalty / 60)));
        }

        if (pvp) {
            int reward = getConfig().getInt("kill.reward-minutes", 15) * 60;
            boolean allowed = reward > 0
                    && (!victimRed || getConfig().getBoolean("kill.reward-if-victim-red", true))
                    && (!killerRed || getConfig().getBoolean("kill.reward-if-killer-red", true));
            if (allowed) {
                adjust(killer.getUniqueId(), reward);
                killer.sendMessage(msg("time-gained", "&6You gained {minutes} minutes for the kill.", "{minutes}", String.valueOf(reward / 60)));
            }
            if (boogeymen.contains(killer.getUniqueId()) && !boogeymen.contains(victim.getUniqueId())) {
                cureBoogeyman(killer);
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (timeOf(p.getUniqueId()) <= 0) eliminate(p);
        }, 2L);
    }

    // ---------- commands ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("/life <check|start|pause|add|remove|set|reset|resetall|list|status|reload|boogeyman> ...");
            return true;
        }
        String sub = a[0].toLowerCase();

        if (sub.equals("check")) {
            Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player ? (Player) s : null);
            if (t == null) { s.sendMessage(ChatColor.RED + "Player not found."); return true; }
            s.sendMessage(t.getName() + ": " + format(timeOf(t.getUniqueId())));
            return true;
        }

        if (!s.hasPermission("life.admin")) {
            s.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        switch (sub) {
            case "start":
                running = true;
                Bukkit.broadcastMessage(msg("started", "&6Limited Life has begun! Timers are ticking."));
                return true;
            case "pause":
                running = false;
                Bukkit.broadcastMessage(msg("paused", "&6Limited Life is paused."));
                return true;
            case "reload":
                reloadConfig();
                s.sendMessage(ChatColor.GREEN + "Config reloaded.");
                return true;
            case "status":
                s.sendMessage(ChatColor.GOLD + "Running: " + running
                        + " | Tracked players: " + seconds.size()
                        + " | Active Boogeymen: " + boogeymen.size());
                return true;
            case "list": {
                List<Map.Entry<UUID, Integer>> es = new ArrayList<>(seconds.entrySet());
                es.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
                s.sendMessage(ChatColor.GOLD + "--- Time left ---");
                for (Map.Entry<UUID, Integer> en : es) {
                    s.sendMessage(tierColor(en.getValue()) + nameOf(en.getKey())
                            + ChatColor.GRAY + " " + format(en.getValue()));
                }
                return true;
            }
            case "reset": {
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "Usage: /life reset <player>"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(ChatColor.RED + "Player must be online."); return true; }
                seconds.put(t.getUniqueId(), startSeconds());
                boogeymen.remove(t.getUniqueId());
                revive(t);
                s.sendMessage(ChatColor.GREEN + t.getName() + " reset to " + format(startSeconds()));
                return true;
            }
            case "resetall": {
                for (UUID id : new ArrayList<>(seconds.keySet())) seconds.put(id, startSeconds());
                boogeymen.clear();
                cured.clear();
                for (Player p : Bukkit.getOnlinePlayers()) revive(p);
                s.sendMessage(ChatColor.GREEN + "Everyone reset to " + format(startSeconds()));
                return true;
            }
            case "boogeyman": {
                if (a.length < 2) {
                    s.sendMessage(ChatColor.RED + "Usage: /life boogeyman <roll [count]|end|list>");
                    return true;
                }
                String action = a[1].toLowerCase();
                if (action.equals("roll")) {
                    int count = getConfig().getInt("boogeyman.count", 1);
                    if (a.length > 2) {
                        try { count = Integer.parseInt(a[2]); }
                        catch (NumberFormatException ex) { s.sendMessage(ChatColor.RED + "Count must be a number."); return true; }
                    }
                    rollBoogeymen(s, count);
                } else if (action.equals("end")) {
                    endBoogeymanRound(s);
                } else if (action.equals("list")) {
                    if (boogeymen.isEmpty()) {
                        s.sendMessage(ChatColor.GRAY + "No active Boogeymen.");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (UUID id : boogeymen) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(nameOf(id));
                        }
                        s.sendMessage(ChatColor.GOLD + "Active Boogeymen: " + sb);
                    }
                } else {
                    s.sendMessage(ChatColor.RED + "Usage: /life boogeyman <roll [count]|end|list>");
                }
                return true;
            }
            case "add": case "remove": case "set": {
                if (a.length < 3) { s.sendMessage(ChatColor.RED + "Usage: /life " + sub + " <player> <minutes>"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(ChatColor.RED + "Player must be online."); return true; }
                int mins;
                try { mins = Integer.parseInt(a[2]); }
                catch (NumberFormatException ex) { s.sendMessage(ChatColor.RED + "Minutes must be a number."); return true; }
                if (sub.equals("add")) adjust(t.getUniqueId(), mins * 60);
                else if (sub.equals("remove")) adjust(t.getUniqueId(), -mins * 60);
                else seconds.put(t.getUniqueId(), mins * 60);
                int next = timeOf(t.getUniqueId());
                if (next > 0) revive(t);
                else eliminate(t);
                s.sendMessage(ChatColor.GREEN + t.getName() + " now has " + format(next));
                return true;
            }
            default:
                s.sendMessage(ChatColor.RED + "Unknown subcommand.");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) {
            return filter(Arrays.asList("check", "start", "pause", "add", "remove", "set", "reset",
                    "resetall", "list", "status", "reload", "boogeyman"), a[0]);
        }
        if (a.length == 2) {
            String sub = a[0].toLowerCase();
            if (sub.equals("boogeyman")) return filter(Arrays.asList("roll", "end", "list"), a[1]);
            if (Arrays.asList("check", "add", "remove", "set", "reset").contains(sub)) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                return filter(names, a[1]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : opts) {
            if (o.toLowerCase().startsWith(prefix.toLowerCase())) out.add(o);
        }
        return out;
    }
}
