package net.azuriffy.azurpvp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Firework;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AzurPvPPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Connection db;
    private final Map<UUID, Long> combatUntil = new ConcurrentHashMap<>();
    private final Set<UUID> arenaCombat = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> duelRequests = new ConcurrentHashMap<>();
    private final Map<UUID, String> duelChosenKits = new ConcurrentHashMap<>();
    private final Map<UUID, String> duelTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> duelRounds = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> duelRoundWins = new ConcurrentHashMap<>();
    private final Set<UUID> awaitingRoundsInput = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> duelMaps = new ConcurrentHashMap<>();
    private final Map<UUID, String> duelGuiMode = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> activeDuels = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> spectating = new ConcurrentHashMap<>(); // spectator -> duelist being watched
    private final LinkedHashMap<UUID, LinkedHashMap<String, Long>> duelQueue = new LinkedHashMap<>();
    private final Set<UUID> duelCountdown = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingDuelLosers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> duelArenaByPlayer = new ConcurrentHashMap<>();
    private final Set<String> occupiedDuelArenas = ConcurrentHashMap.newKeySet();
    private static final String DUEL_ITEM_NAME = "§d§lDuel";
    private final Map<UUID, UUID> lastMessaged = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignored = new ConcurrentHashMap<>();
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Set<UUID> god = ConcurrentHashMap.newKeySet();
    private final Set<String> cmodeWorlds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> kitCooldowns = new ConcurrentHashMap<>();
    private BukkitTask combatTask;
    private YamlConfiguration roles;
    private File rolesFile;
    private final Map<UUID, Long> lastPlaytimeSave = new ConcurrentHashMap<>();
    private final Map<UUID, Long> itemCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pearlCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> gappleCooldowns = new ConcurrentHashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        cmodeWorlds.addAll(getConfig().getStringList("cmode-bypass-worlds"));
        // One-time cleanup of placeholder arenas 1, 2, and 3 (including their spawn data).
        // The marker prevents deleting them again if the owner creates those arenas later.
        if (!getConfig().getBoolean("migrations.removed-placeholder-arenas-1-3", false)) {
            for (String arenaId : List.of("1", "2", "3")) {
                getConfig().set("arenas." + arenaId, null);
            }
            getConfig().set("migrations.removed-placeholder-arenas-1-3", true);
            saveConfig();
        }
        ensureDefaultKits();
        loadRoles();
        try { openDatabase(); } catch (SQLException e) { getLogger().severe("SQLite startup failed: " + e.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }
        for (String name : List.of("azurpvp", "spawn", "setspawn", "stats", "kit", "duel", "accept", "msg", "reply", "ignore", "rules", "discord", "ping", "tps", "fly", "heal", "feed", "vanish", "freeze", "report", "staffchat", "role", "back", "top", "tpa", "home", "sethome", "warp", "setwarp", "unwarp", "party", "clan", "friends", "achievements", "quests", "settings", "arena", "spectate", "leave", "leaderboard", "bounty", "killstreak", "playtime", "whois", "maintenance", "staffmode", "invsee", "ec", "announce", "mute", "unmute", "ban", "tempban", "unban", "kick", "warn", "history", "alerts", "cps", "clear", "repair", "gamemode", "speed", "backup", "world", "tp", "addarena", "cpvparena", "set", "cmode")) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(this); c.setTabCompleter(this); }
        }
        getServer().getPluginManager().registerEvents(this, this);
        startLobbyTips();
        startSpectatorWatch();
        startPlaytimeTracking();
        startLobbyItemGuard();
        startAdventureGuard();
        combatTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            updateCombat(); updateQueueActionBars();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (inSpawnProtection(online.getLocation()) && !activeDuels.containsKey(online.getUniqueId())) {
                    online.setHealth(online.getMaxHealth());
                    online.setFoodLevel(20);
                    online.setSaturation(20.0f);
                    online.setExhaustion(0.0f);
                    online.setFireTicks(0);
                    online.setFreezeTicks(0);
                }
            }
        }, 1L, 10L);
        // Periodic world saves reduce data loss on clean restarts; host-level resets still need host configuration fixed.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (World world : Bukkit.getWorlds()) {
                try { world.save(); } catch (Exception ex) { getLogger().warning("World save failed for " + world.getName() + ": " + ex.getMessage()); }
            }
        }, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { for (Player online : Bukkit.getOnlinePlayers()) { updateTabRole(online); updateAzurTabAndScoreboard(online); } }, 20L, 20L);
        // Bootstrap: migrate the old owner role to owner and grant the first operator owner if none exists.
        migrateAndEnsureRoles();
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) { updateTabRole(online); updateAzurTabAndScoreboard(online); }
            if (roles.getConfigurationSection("users") == null || roles.getConfigurationSection("users").getKeys(false).stream().noneMatch(id -> "owner".equalsIgnoreCase(roles.getString("users."+id+".role","")))) {
                Bukkit.getOnlinePlayers().stream().filter(Player::isOp).findFirst().ifPresent(p -> assignRole(p.getUniqueId(), p.getName(), "owner"));
            }
        });
        getLogger().info("AzurPvP Utils enabled (Paper 1.21.11 target, SQLite, role system).");
    }

    @Override public void onDisable() {
        if (combatTask != null) combatTask.cancel();
        // Explicitly flush every loaded world before the plugin shuts down.
        // Paper normally autosaves worlds, but this helps ensure edits are written during a clean stop.
        for (World world : Bukkit.getWorlds()) {
            try {
                world.save();
                getLogger().info("Saved world on plugin shutdown: " + world.getName());
            } catch (Exception ex) {
                getLogger().severe("Could not save world '" + world.getName() + "': " + ex.getMessage());
            }
        }
        saveRoles();
        for (Player online : Bukkit.getOnlinePlayers()) flushPlaytime(online);
        if (db != null) try { db.close(); } catch (SQLException ignored) {}
    }

    private void loadRoles() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        rolesFile = new File(getDataFolder(), "roles.yml");
        roles = rolesFile.exists() ? YamlConfiguration.loadConfiguration(rolesFile) : new YamlConfiguration();
        migrateAndEnsureRoles();
    }
    private void migrateAndEnsureRoles() {
        ensureRole("owner", "*", "&d&lOWNER", 0);
        ensureRole("admin", "moderation", "&c&lADMIN", 1);
        ensureRole("mod", "moderation,staff,teleport", "&b&lMOD", 2);
        ensureRole("helper", "staff", "&a&lHELPER", 3);
        ensureRole("creator", "member", "&d&lCREATOR", 4);
        ensureRole("azur+", "member", "&5&lAZUR+", 5);
        ensureRole("member", "member", "&f&lMEMBER", 6);
        if (roles.getConfigurationSection("users") != null) {
            for (String id : roles.getConfigurationSection("users").getKeys(false)) {
                if ("owner".equalsIgnoreCase(roles.getString("users."+id+".role", ""))) roles.set("users."+id+".role", "owner");
                if ("member".equalsIgnoreCase(roles.getString("users."+id+".role", ""))) roles.set("users."+id+".role", "member");
            }
        }
        saveRoles();
    }
    private void ensureRole(String role, String permissions, String prefix, int order) {
        String path = "roles." + role;
        if (!roles.isConfigurationSection(path)) roles.createSection(path);
        List<String> perms = switch (role) {
            case "owner" -> List.of("*");
            case "admin" -> List.of("moderation","staff","teleport","inspect","utility");
            case "mod" -> List.of("moderation","staff","teleport");
            case "helper" -> List.of("staff");
            default -> List.of("player");
        };
        roles.set(path + ".permissions", perms);
        roles.set(path + ".prefix", prefix);
        roles.set(path + ".order", order);
        roles.set(path + ".rgb", switch(role) {
            case "owner" -> "177,156,217"; case "admin" -> "255,0,0"; case "mod" -> "144,213,255";
            case "helper" -> "144,238,144"; case "creator" -> "255,105,180"; case "azur+" -> "128,0,128"; default -> "239,198,245";
        });
    }
    private void saveRoles() { try { if (roles != null && rolesFile != null) roles.save(rolesFile); } catch (Exception ex) { getLogger().warning("Could not save roles.yml: "+ex.getMessage()); } }
    private String roleOf(UUID id) { return roles.getString("users."+id+".role", "member").toLowerCase(Locale.ROOT); }
    private boolean hasRole(UUID id, String role) { return roleOf(id).equalsIgnoreCase(role); }
    // Only the owner role (or server console) can manage roles.
    private boolean isDev(CommandSender s) { return s instanceof ConsoleCommandSender || (s instanceof Player p && hasRole(p.getUniqueId(), "owner")); }
    private boolean isStaff(CommandSender s) {
        if (isDev(s)) return true;
        if (!(s instanceof Player p)) return true;
        String role = roleOf(p.getUniqueId());
        return Set.of("admin", "mod", "helper").contains(role);
    }
    private boolean isAdminOrAbove(CommandSender s) {
        if (s instanceof ConsoleCommandSender) return true;
        return s instanceof Player p && Set.of("owner", "admin").contains(roleOf(p.getUniqueId()));
    }
    private boolean isModOrAbove(CommandSender s) {
        if (s instanceof ConsoleCommandSender) return true;
        return s instanceof Player p && Set.of("owner", "admin", "mod").contains(roleOf(p.getUniqueId()));
    }
    private boolean can(CommandSender s, String category) {
        if (!(s instanceof Player p)) return true;
        String role = roleOf(p.getUniqueId());
        if (role.equals("owner")) return true;
        List<String> perms = roles.getStringList("roles." + role + ".permissions");
        return perms.contains("*") || perms.contains(category) || category.equals("member");
    }
    private String rolePrefix(String role) { return c(roles.getString("roles."+role+".prefix", "&dMEMBER")); }
    private void updateTabRole(Player p) {
        Scoreboard board = p.getScoreboard();
        if (board == Bukkit.getScoreboardManager().getMainScoreboard()) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(board);
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            String role = roleOf(online.getUniqueId());
            if (!roleExists(role)) role = "member";
            String teamName = String.format(Locale.ROOT, "%02d_%s", roles.getInt("roles."+role+".order", 6), role.replaceAll("[^a-zA-Z0-9_]", ""));
            Team team = board.getTeam(teamName);
            if (team == null) team = board.registerNewTeam(teamName);
            team.setPrefix(rolePrefix(role) + c(" &f"));
            try { team.setColor(ChatColor.getByChar(colorCode(role))); } catch (Exception ignored) {}
            for (Team other : board.getTeams()) if (other != team && other.hasEntry(online.getName())) other.removeEntry(online.getName());
            if (!team.hasEntry(online.getName())) team.addEntry(online.getName());
        }
        p.setScoreboard(board);
    }

    private void updateAzurTabAndScoreboard(Player p) {
        if (playerSetting(p, "tab_info", true)) p.setPlayerListHeaderFooter(
            c("\n&5&lAZUR&d&lPVP\n&7Minecraft 1.21.11+\n"),
            c("\n&7Players: &d" + Bukkit.getOnlinePlayers().size() + "\n"
                + "&7Ping: &a" + p.getPing() + "ms\n"
                + "&d/discord &8| &d/store &8| &d/website\n")
        ); else p.setPlayerListHeaderFooter("", "");
        Scoreboard board = p.getScoreboard();
        if (board == Bukkit.getScoreboardManager().getMainScoreboard()) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(board);
            updateTabRole(p);
            board = p.getScoreboard();
        }
        org.bukkit.scoreboard.Objective objective = board.getObjective("azurpvp");
        if (!playerSetting(p, "scoreboard", true)) { if (objective != null) objective.setDisplaySlot(null); p.setScoreboard(board); return; }
        if (objective == null) objective = board.registerNewObjective("azurpvp", "dummy", c("&5&lAZUR&d&lPVP"));
        objective.setDisplayName(c("&5&lAZUR&d&lPVP"));
        objective.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
        String role = roleOf(p.getUniqueId());
        UUID opponentId = activeDuels.get(p.getUniqueId());
        String[] lines;
        if (opponentId != null) {
            Player opponent = Bukkit.getPlayer(opponentId);
            int yours = duelRoundWins.getOrDefault(p.getUniqueId(), 0);
            int theirs = duelRoundWins.getOrDefault(opponentId, 0);
            lines = new String[] {
                "&7Online: &d" + Bukkit.getOnlinePlayers().size(),
                "&dPlayer: &f" + p.getName(),
                "&7Ping: &a" + p.getPing() + "ms",
                "&dScore: &f" + yours + "&7-" + theirs,
                "&dOpponent: &f" + (opponent == null ? "Offline" : opponent.getName()),
                "&dRound: &f" + (yours + theirs + 1)
            };
        } else {
            lines = new String[] {
                "&7Online: &d" + Bukkit.getOnlinePlayers().size(),
                "&dPlayer: &f" + p.getName(),
                "&7Ping: &a" + p.getPing() + "ms",
                "&dYour role: " + rolePrefix(role),
                "&r ",
                "&d⚔ or /duel to duel"
            };
        }
        for (String entry : new HashSet<>(board.getEntries())) objective.getScore(entry).resetScore();
        int score = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String unique = c(lines[i]) + ChatColor.values()[i].toString();
            org.bukkit.scoreboard.Score sc = objective.getScore(unique);
            sc.setScore(score--);
            // Paper exposes blank score-number formatting on Score. Use reflection for API compatibility.
            try {
                // Invoke through the Score API interface (not the server's implementation class),
                // otherwise the implementation's non-public class can make reflection fail.
                Class<?> nf = Class.forName("org.bukkit.scoreboard.NumberFormat");
                Object blank = nf.getMethod("blank").invoke(null);
                try { org.bukkit.scoreboard.Score.class.getMethod("numberFormat", nf).invoke(sc, blank); }
                catch (NoSuchMethodException ex) { org.bukkit.scoreboard.Score.class.getMethod("setNumberFormat", nf).invoke(sc, blank); }
            } catch (ReflectiveOperationException ignored) { }
        }
        p.setScoreboard(board);
    }

    private char colorCode(String role) {
        return switch(role) { case "owner" -> 'd'; case "admin" -> 'c'; case "mod" -> 'b'; case "helper" -> 'a'; case "creator" -> 'e'; case "azur+" -> '5'; default -> 'f'; };
    }
    @EventHandler public void hideRestrictedCommands(PlayerCommandSendEvent e) {
        Player p = e.getPlayer();
        Set<String> restricted = Set.of("world","setspawn","maintenance","staffmode","invsee","ec","announce","mute","unmute","ban","tempban","timeout","unban","kick","warn","history","alerts","clear","repair","gamemode","speed","backup","whois","heal","feed","vanish","freeze","staffchat","role","arena","addarena","set");
        if (isDev(p)) return;
        e.getCommands().removeIf(command -> {
            String root = command.toLowerCase(Locale.ROOT).split(":")[0];
            if (Set.of("ban", "tempban", "timeout", "unban").contains(root)) return !isAdminOrAbove(p);
            if (Set.of("gms", "gmsp").contains(root)) return !isModOrAbove(p);
            if (root.equals("gmc")) return true;
            return restricted.contains(root) && !can(p, commandCategory(command));
        });
    }
    private String commandCategory(String command) {
        return switch(command.toLowerCase(Locale.ROOT).split(":")[0]) {
            case "setspawn","maintenance","backup","role","arena","addarena","set","world","tp","cmode" -> "owner";
            case "ban","tempban","timeout","unban","kick","mute","unmute","warn","history","alerts","clear","repair","gamemode","speed","heal","feed" -> "moderation";
            case "staffmode","invsee","ec","announce","whois","vanish","freeze","staffchat" -> "staff";
            default -> "member";
        };
    }
    private void assignRole(UUID id, String name, String role) {
        if (!roles.contains("roles."+role)) return;
        String normalized = role.toLowerCase(Locale.ROOT);
        roles.set("users."+id+".role", normalized);
        roles.set("users."+id+".name", name);
        saveRoles();
        Player online = Bukkit.getPlayer(id);
        if (online != null) updateTabRole(online);
    }
    private boolean roleExists(String role) { return roles.isConfigurationSection("roles."+role.toLowerCase(Locale.ROOT)); }
    private void roleCommand(CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            tell(s,"&dRole system: &f/role list, /role create <name>, /role delete <name>, /role set <player> <role>, /role permission <role> <category>, /role info <name>");
            tell(s,"&7Only owner can manage roles. The built-in &downer &7role cannot be deleted.");
            return;
        }
        if (!isDev(s)) { tell(s,"&cOnly users with the owner role can manage roles."); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                tell(s,"&dRoles:");
                for (String r : roles.getConfigurationSection("roles").getKeys(false)) tell(s,"&8- &f"+r+(r.equalsIgnoreCase("owner")?" &d(locked)":""));
            }
            case "create" -> {
                if (a.length<2) { tell(s,"&cUsage: /role create <name>"); return; }
                String r=a[1].toLowerCase(Locale.ROOT);
                if (!r.matches("[a-z0-9_-]{2,24}") || roleExists(r)) { tell(s,"&cInvalid or existing role name."); return; }
                roles.set("roles."+r+".permissions", List.of("member")); saveRoles(); tell(s,"&aCreated role &d"+r+"&a.");
            }
            case "delete" -> {
                if (a.length<2) { tell(s,"&cUsage: /role delete <name>"); return; }
                String r=a[1].toLowerCase(Locale.ROOT);
                if (r.equals("owner")) { tell(s,"&cThe owner role is permanent and cannot be deleted."); return; }
                if (!roleExists(r)) { tell(s,"&cThat role does not exist."); return; }
                roles.set("roles."+r,null);
                if (roles.getConfigurationSection("users")!=null) for(String id:roles.getConfigurationSection("users").getKeys(false)) if (roles.getString("users."+id+".role","member").equalsIgnoreCase(r)) roles.set("users."+id+".role","member");
                saveRoles(); tell(s,"&aDeleted role &d"+r+"&a; affected users are now players.");
            }
            case "set", "assign" -> {
                if (a.length<3) { tell(s,"&cUsage: /role set <online-player> <role>"); return; }
                Player target=Bukkit.getPlayerExact(a[1]); String r=a[2].toLowerCase(Locale.ROOT);
                if(target==null) { tell(s,"&cPlayer must be online."); return; }
                if(!roleExists(r)) { tell(s,"&cThat role does not exist."); return; }
                assignRole(target.getUniqueId(),target.getName(),r); tell(s,"&aSet "+target.getName()+"'s role to &d"+r+"&a."); tell(target,"&7Your role is now &d"+r+"&7.");
            }
            case "permission", "perm" -> {
                if(a.length<3 || !roleExists(a[1])) { tell(s,"&cUsage: /role permission <role> <category>"); return; }
                String r=a[1].toLowerCase(Locale.ROOT), category=a[2].toLowerCase(Locale.ROOT);
                if(r.equals("owner")) { tell(s,"&cThe owner role already has all permissions."); return; }
                if(!Set.of("member","staff","moderation","teleport","inspect","utility").contains(category)) { tell(s,"&cCategories: player, staff, moderation, teleport, inspect, utility"); return; }
                List<String> perms=new ArrayList<>(roles.getStringList("roles."+r+".permissions"));
                if(perms.contains(category)) { perms.remove(category); tell(s,"&dRemoved &f"+category+"&d from role &f"+r+"&d."); }
                else { perms.add(category); tell(s,"&aAdded &f"+category+"&a to role &f"+r+"&a."); }
                roles.set("roles."+r+".permissions",perms); saveRoles();
            }
            case "info" -> {
                if(a.length<2 || !roleExists(a[1])) { tell(s,"&cUsage: /role info <name>"); return; }
                tell(s,"&dRole &f"+a[1]+" &7permissions: &f"+roles.getStringList("roles."+a[1].toLowerCase(Locale.ROOT)+".permissions"));
            }
            default -> tell(s,"&cUnknown role subcommand. Use /role help.");
        }
    }
    private void openDatabase() throws SQLException {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File file = new File(getDataFolder(), "stats.db");
        db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        // One-time full stats reset the first time this version starts. Runs once, then never again.
        if (!getConfig().getBoolean("migrations.stats-db-reset-v1", false)) {
            try (Statement s = db.createStatement()) {
                s.executeUpdate("DROP TABLE IF EXISTS stats");
            }
            getConfig().set("migrations.stats-db-reset-v1", true);
            saveConfig();
            getLogger().info("All stats were reset; now saving to stats.db");
        }
        try (Statement s = db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS stats (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, kills INTEGER DEFAULT 0, deaths INTEGER DEFAULT 0, streak INTEGER DEFAULT 0, best_streak INTEGER DEFAULT 0, playtime_seconds INTEGER DEFAULT 0)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY AUTOINCREMENT, reporter TEXT, target TEXT, reason TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS duel_history (id INTEGER PRIMARY KEY AUTOINCREMENT, winner_uuid TEXT NOT NULL, winner_name TEXT NOT NULL, loser_uuid TEXT NOT NULL, loser_name TEXT NOT NULL, kit TEXT NOT NULL, winner_score INTEGER NOT NULL, loser_score INTEGER NOT NULL, arena TEXT, ended_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        }
        // SQLite has no "ADD COLUMN IF NOT EXISTS": a failure just means the column is already there.
        for (String col : List.of("duel_wins INTEGER DEFAULT 0", "duel_losses INTEGER DEFAULT 0", "duel_streak INTEGER DEFAULT 0", "best_duel_streak INTEGER DEFAULT 0", "elo INTEGER DEFAULT 1000", "last_online INTEGER DEFAULT 0")) {
            try (Statement alter = db.createStatement()) { alter.executeUpdate("ALTER TABLE stats ADD COLUMN " + col); } catch (SQLException ignored) {}
        }
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(stats)")) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) sb.append(rs.getString("name")).append(' ');
            getLogger().info("[DuelStats] stats columns: " + sb);
        }
    }

    private boolean deleteWorldFolder(File folder) {
        if (folder == null || !folder.exists()) return true;
        File[] children = folder.listFiles();
        if (children != null) for (File child : children) if (!deleteWorldFolder(child)) return false;
        return folder.delete();
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private String prefix() { return c(getConfig().getString("prefix", "&8[&5AzurPvP&8] &r")); }
    private void tell(CommandSender sender, String message) { sender.sendMessage(prefix() + c(message)); }
    private void recordDuelResult(Player winner, Player loser, String kit, int winnerScore, int loserScore, String arena) {
        ensurePlayer(winner); ensurePlayer(loser);
        String w = winner.getUniqueId().toString(), l = loser.getUniqueId().toString();
        try {
            db.setAutoCommit(false);
            int wElo = getStat(winner.getUniqueId(), "elo"), lElo = getStat(loser.getUniqueId(), "elo");
            if (wElo <= 0) wElo = 1000; if (lElo <= 0) lElo = 1000;
            int delta = (int) Math.round(32 * (1.0 - 1.0 / (1.0 + Math.pow(10, (lElo - wElo) / 400.0))));
            try (PreparedStatement ps = db.prepareStatement("UPDATE stats SET duel_wins=duel_wins+1, duel_streak=duel_streak+1, best_duel_streak=MAX(best_duel_streak, duel_streak+1), elo=? WHERE uuid=?")) {
                ps.setInt(1, wElo + delta); ps.setString(2, w);
                if (ps.executeUpdate() == 0) getLogger().warning("[DuelStats] No stats row for winner " + winner.getName());
            }
            try (PreparedStatement ps = db.prepareStatement("UPDATE stats SET duel_losses=duel_losses+1, duel_streak=0, elo=? WHERE uuid=?")) {
                ps.setInt(1, Math.max(0, lElo - delta)); ps.setString(2, l);
                if (ps.executeUpdate() == 0) getLogger().warning("[DuelStats] No stats row for loser " + loser.getName());
            }
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO duel_history(winner_uuid,winner_name,loser_uuid,loser_name,kit,winner_score,loser_score,arena) VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, w); ps.setString(2, winner.getName()); ps.setString(3, l); ps.setString(4, loser.getName());
                ps.setString(5, kit); ps.setInt(6, winnerScore); ps.setInt(7, loserScore); ps.setString(8, arena);
                ps.executeUpdate();
            }
            db.commit();
            getLogger().info("[DuelStats] Saved duel: " + winner.getName() + " beat " + loser.getName() + " " + winnerScore + "-" + loserScore + " (kit " + kit + ", elo " + wElo + "->" + (wElo + delta) + ")");
        } catch (SQLException e) {
            getLogger().warning("[DuelStats] Could not record duel result: " + e.getMessage());
            try { db.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }
    private void awardKill(Player killer, Player victim) {
        ensurePlayer(killer); ensurePlayer(victim);
        stat(victim.getUniqueId(), "deaths", 1);
        stat(victim.getUniqueId(), "streak", -getStat(victim.getUniqueId(), "streak"));
        stat(killer.getUniqueId(), "kills", 1);
        stat(killer.getUniqueId(), "streak", 1);
        int streak = getStat(killer.getUniqueId(), "streak");
        if (streak > getStat(killer.getUniqueId(), "best_streak")) {
            try (PreparedStatement ps = db.prepareStatement("UPDATE stats SET best_streak=? WHERE uuid=?")) { ps.setInt(1, streak); ps.setString(2, killer.getUniqueId().toString()); ps.executeUpdate(); } catch (SQLException ignored) {}
        }
    }
    private void ensurePlayer(Player p) {
        try (PreparedStatement ps = db.prepareStatement("INSERT OR IGNORE INTO stats(uuid,name) VALUES(?,?)")) {
            ps.setString(1, p.getUniqueId().toString()); ps.setString(2, p.getName()); ps.executeUpdate();
            try (PreparedStatement update = db.prepareStatement("UPDATE stats SET name=? WHERE uuid=?")) { update.setString(1,p.getName()); update.setString(2,p.getUniqueId().toString()); update.executeUpdate(); }
        } catch (SQLException e) { getLogger().warning("Stats update failed: " + e.getMessage()); }
    }
    private void stat(UUID uuid, String field, int delta) {
        if (!Set.of("kills","deaths","streak","best_streak").contains(field)) return;
        try (PreparedStatement ps = db.prepareStatement("UPDATE stats SET " + field + "=" + field + "+? WHERE uuid=?")) {
            ps.setInt(1, delta); ps.setString(2, uuid.toString()); ps.executeUpdate();
        } catch (SQLException e) { getLogger().warning("Stats write failed: " + e.getMessage()); }
    }
    private long getStatLong(UUID uuid, String field) {
        if (!Set.of("last_online", "playtime_seconds").contains(field)) return 0;
        try (PreparedStatement ps = db.prepareStatement("SELECT " + field + " FROM stats WHERE uuid=?")) {
            ps.setString(1, uuid.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        } catch (SQLException e) { return 0; }
    }
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    private long sessionSeconds(UUID id) { Long st = sessionStart.get(id); return st == null ? 0 : Math.max(0, (System.currentTimeMillis() - st) / 1000); }
    private void flushPlaytime(Player p) {
        Long st = sessionStart.remove(p.getUniqueId());
        if (st == null) return;
        long secs = Math.max(0, (System.currentTimeMillis() - st) / 1000);
        try (PreparedStatement ps = db.prepareStatement("UPDATE stats SET playtime_seconds=playtime_seconds+?, last_online=? WHERE uuid=?")) {
            ps.setLong(1, secs); ps.setLong(2, System.currentTimeMillis()); ps.setString(3, p.getUniqueId().toString()); ps.executeUpdate();
        } catch (SQLException ex) { getLogger().warning("Could not save playtime: " + ex.getMessage()); }
    }
    private void startPlaytimeTracking() {
        for (Player online : Bukkit.getOnlinePlayers()) sessionStart.put(online.getUniqueId(), System.currentTimeMillis());
        // Save every 5 minutes so a crash can't lose much playtime.
        Bukkit.getScheduler().runTaskTimer(this, () -> { for (Player online : Bukkit.getOnlinePlayers()) { flushPlaytime(online); sessionStart.put(online.getUniqueId(), System.currentTimeMillis()); } }, 6000L, 6000L);
    }
    @EventHandler public void onStatsJoin(PlayerJoinEvent e) { sessionStart.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); }
    @EventHandler public void onStatsQuit(PlayerQuitEvent e) { flushPlaytime(e.getPlayer()); }
    private String fmtDuration(long sec) {
        long d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60;
        List<String> parts = new ArrayList<>();
        if (d > 0) parts.add(d + (d == 1 ? " day" : " days"));
        if (h > 0) parts.add(h + (h == 1 ? " hour" : " hours"));
        if (m > 0) parts.add(m + (m == 1 ? " minute" : " minutes"));
        if (parts.isEmpty()) return "less than a minute";
        return String.join(" ", parts.subList(0, Math.min(2, parts.size())));
    }
    private int getStat(UUID uuid, String field) {
        if (!Set.of("kills","deaths","streak","best_streak","playtime_seconds","duel_wins","duel_losses","duel_streak","best_duel_streak","elo").contains(field)) return 0;
        try (PreparedStatement ps = db.prepareStatement("SELECT " + field + " FROM stats WHERE uuid=?")) {
            ps.setString(1, uuid.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { return 0; }
    }
    private void tag(Player p) {
        if (!getConfig().getBoolean("combat.enabled", true)) return;
        combatUntil.put(p.getUniqueId(), System.currentTimeMillis() + getConfig().getLong("combat.tag-seconds",15)*1000L);

    }
    private boolean tagged(Player p) { return arenaCombat.contains(p.getUniqueId()) || combatUntil.getOrDefault(p.getUniqueId(),0L) > System.currentTimeMillis(); }
    private void updateCombat() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(combatUntil.keySet())) {
            if (combatUntil.getOrDefault(id, 0L) <= now || Bukkit.getPlayer(id) == null) combatUntil.remove(id);
        }
    }

    private void updateQueueActionBars() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, LinkedHashMap<String, Long>> entry : new ArrayList<>(duelQueue.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || activeDuels.containsKey(entry.getKey())) {
                duelQueue.remove(entry.getKey());
                continue;
            }
            LinkedHashMap<String, Long> queued = entry.getValue();
            if (queued.isEmpty()) { duelQueue.remove(entry.getKey()); continue; }
            long oldest = queued.values().stream().mapToLong(Long::longValue).min().orElse(now);
            long totalSeconds = Math.max(0, (now - oldest) / 1000L);
            String totalTime = String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
            List<Map.Entry<String, Long>> kits = new ArrayList<>(queued.entrySet());
            int phase = (int)((now / 2500L) % (kits.size() + 1));
            String message;
            if (phase == kits.size()) {
                message = "&d&lQueued &f" + kits.size() + " &dkit" + (kits.size() == 1 ? "" : "s") + " &8(&f" + totalTime + "&8)";
            } else {
                Map.Entry<String, Long> kit = kits.get(phase);
                long seconds = Math.max(0, (now - kit.getValue()) / 1000L);
                String kitTime = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
                message = "&d&lQueued &f" + prettyKitName(kit.getKey()) + " &8(&f" + kitTime + "&8)";
            }
            player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(c(message)));
        }
        processWaitingDuelQueues();
    }

    // This server is PvP-only: block all living mob spawns in every loaded world, regardless of spawn reason.
    @EventHandler public void blockAllMobSpawns(CreatureSpawnEvent e) {
        e.setCancelled(true);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer(); ensurePlayer(p); if(!roleExists(roleOf(p.getUniqueId()))) assignRole(p.getUniqueId(),p.getName(),"member");
        updateTabRole(p);
        updateAzurTabAndScoreboard(p);
        if (getConfig().getBoolean("maintenance.enabled", false) && !isStaff(p)) { Bukkit.getScheduler().runTask(this, () -> p.kickPlayer(c("&5AzurPvP &8» &cServer maintenance is enabled."))); return; }
        // Always send players to the configured /setspawn point after joining, rather than their logout location.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            String spawnWorldName = getConfig().getString("spawn.world", "");
            World spawnWorld = Bukkit.getWorld(spawnWorldName);
            if (spawnWorld == null) return; // /setspawn has not been configured or its world is not loaded.
            Location spawnLocation = new Location(spawnWorld,
                    getConfig().getDouble("spawn.x", 0.5),
                    getConfig().getDouble("spawn.y", 80.0),
                    getConfig().getDouble("spawn.z", 0.5),
                    (float) getConfig().getDouble("spawn.yaw", 0.0),
                    (float) getConfig().getDouble("spawn.pitch", 0.0));
            p.teleport(spawnLocation);
            List<String> loggedLosses = getConfig().getStringList("combat.logged-losses");
            String uuid = p.getUniqueId().toString();
            if (loggedLosses.contains(uuid)) {
                // A combat logger must not carry their duel kit/armor back into spawn.
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
                p.getInventory().setItemInOffHand(null);
                p.setFireTicks(0);
                p.setFallDistance(0);
                p.setHealth(p.getMaxHealth());
                p.setFoodLevel(20);
                p.setSaturation(20.0f);
                loggedLosses.removeIf(uuid::equals);
                getConfig().set("combat.logged-losses", loggedLosses);
                saveConfig();
                p.sendMessage(prefix() + c("&cYou combat logged and lost your last match."));
                giveDuelItem(p);
                giveKitEditor(p);
            }
        });
        e.setJoinMessage(c("&8[&a+&8] &d" + p.getName()));
        p.sendTitle(c("&5&lAZUR&d&lPVP"), c("&7Welcome to the arena"), 10, 45, 15);
        giveDuelItem(p); giveKitEditor(p);
        p.sendMessage(prefix()+c("&7Use &d/azurpvp &7for plugin commands."));
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p=e.getPlayer(); e.setQuitMessage(c("&8[&c-&8] &d"+p.getName()));
        if (tagged(p) && !activeDuels.containsKey(p.getUniqueId()) && getConfig().getBoolean("combat.punish-logout",true) && !p.hasPermission("azurpvp.bypass.combattag")) {
            if ("KILL".equalsIgnoreCase(getConfig().getString("combat.punishment","KILL"))) p.setHealth(0.0);
            else for (ItemStack item : p.getInventory().getContents()) if (item != null) p.getWorld().dropItemNaturally(p.getLocation(),item);
        }
        combatUntil.remove(p.getUniqueId());
    }
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId()) && e.getTo()!=null &&
            (e.getFrom().getBlockX()!=e.getTo().getBlockX() || e.getFrom().getBlockY()!=e.getTo().getBlockY() || e.getFrom().getBlockZ()!=e.getTo().getBlockZ())) {
            e.setTo(e.getFrom());
        }
        if (e.getTo()!=null && (e.getFrom().getBlockX()!=e.getTo().getBlockX() || e.getFrom().getBlockZ()!=e.getTo().getBlockZ()))
            lastLocations.put(e.getPlayer().getUniqueId(), e.getFrom().clone());
    }
    private boolean inSpawnProtection(Location location) {
        if (location == null || location.getWorld() == null || !getConfig().contains("spawn.world")) return false;
        if (!location.getWorld().getName().equals(getConfig().getString("spawn.world"))) return false;
        double dx=location.getX()-getConfig().getDouble("spawn.x"), dz=location.getZ()-getConfig().getDouble("spawn.z");
        return dx*dx+dz*dz <= 500.0*500.0;
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void protectSpawnBlockBreak(BlockBreakEvent e) {
        // Owner-enabled /cmode bypasses this plugin's protections and earlier protection listeners in this world.
        if (cmodeWorlds.contains(e.getBlock().getWorld().getName())) { e.setCancelled(false); return; }
        if(inSpawnProtection(e.getBlock().getLocation())) { e.setCancelled(true); tell(e.getPlayer(),"&cSpawn protection: building is disabled here."); return; }
        Location blockLoc = e.getBlock().getLocation();
        for (String key : arenaKeys()) {
            Location center = arenaCenter(key);
            if (center != null && center.getWorld().equals(blockLoc.getWorld()) && center.distanceSquared(blockLoc) <= 10000.0) {
                e.setCancelled(true);
                return;
            }
        }
        ConfigurationSection cpvps = getConfig().getConfigurationSection("cpvparenas");
        if (cpvps != null) for (String key : cpvps.getKeys(false)) {
            String path = "cpvparenas." + key;
            World world = Bukkit.getWorld(getConfig().getString(path + ".world", ""));
            if (world == null || !world.equals(blockLoc.getWorld())) continue;
            double dx = blockLoc.getX() - getConfig().getDouble(path + ".x");
            double dz = blockLoc.getZ() - getConfig().getDouble(path + ".z");
            if (dx * dx + dz * dz > 200.0 * 200.0) continue;
            Material type = e.getBlock().getType();
            boolean allowed = blockLoc.getBlockY() < getConfig().getInt(path + ".y-level")
                    && (type == Material.GRASS_BLOCK || type == Material.DIRT || type == Material.COARSE_DIRT
                    || type == Material.ROOTED_DIRT || type == Material.STONE || type == Material.DEEPSLATE
                    || type == Material.COBBLED_DEEPSLATE || type == Material.RESPAWN_ANCHOR
                    || type == Material.OBSIDIAN || type == Material.GLOWSTONE);
            e.setCancelled(!allowed);
            return;
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void protectSpawnBlockPlace(BlockPlaceEvent e) {
        // Owner-enabled /cmode bypasses this plugin's protections and earlier protection listeners in this world.
        if (cmodeWorlds.contains(e.getBlock().getWorld().getName())) { e.setCancelled(false); return; }
        Location location = e.getBlock().getLocation();
        ConfigurationSection cpvps = getConfig().getConfigurationSection("cpvparenas");
        if (cpvps != null) for (String key : cpvps.getKeys(false)) {
            String path = "cpvparenas." + key;
            World world = Bukkit.getWorld(getConfig().getString(path + ".world", ""));
            if (world == null || !world.equals(location.getWorld())) continue;
            double dx = location.getX() - getConfig().getDouble(path + ".x");
            double dz = location.getZ() - getConfig().getDouble(path + ".z");
            if (dx * dx + dz * dz <= 200.0 * 200.0) return;
        }
        if (inSpawnProtection(location)) { e.setCancelled(true); tell(e.getPlayer(), "&cSpawn protection: building is disabled here."); return; }
        for (String key : arenaKeys()) {
            Location center = arenaCenter(key);
            if (center != null && center.getWorld().equals(location.getWorld()) && center.distanceSquared(location) <= 10000.0) {
                e.setCancelled(true);
                return;
            }
        }
    }
    @EventHandler public void protectSpawnInteractions(PlayerInteractEvent e) {
        if (e.getClickedBlock()==null || !inSpawnProtection(e.getClickedBlock().getLocation())) return;
        Material type=e.getClickedBlock().getType();
        if (type==Material.ENDER_CHEST || type==Material.CHEST || type==Material.TRAPPED_CHEST || type==Material.BARREL || type.name().endsWith("SHULKER_BOX")) {
            if (!type.name().endsWith("SHULKER_BOX")) { e.setCancelled(true); tell(e.getPlayer(),"&cContainer access is disabled in spawn protection."); }
        }
    }
    @EventHandler public void protectSpawnFromAllDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof Player player && inSpawnProtection(player.getLocation())
                && !activeDuels.containsKey(player.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler public void onDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player victim && e.getDamager() instanceof Player attacker) {
            if (duelCountdown.contains(victim.getUniqueId()) || duelCountdown.contains(attacker.getUniqueId())) { e.setCancelled(true); return; }
            if (inSpawnProtection(victim.getLocation()) || inSpawnProtection(attacker.getLocation())) { e.setCancelled(true); return; }
            tag(victim); tag(attacker);
            if (god.contains(victim.getUniqueId())) e.setCancelled(true);
            if (getConfig().getBoolean("pvp.custom-knockback",false)) {
                // Paper's modern combat remains enabled; full knockback control requires a dedicated damage/velocity module.
            }
        }
    }
    // Prevent the vanilla death/respawn screen for duel losses. Intercept lethal damage
    // before health reaches zero, then finish the duel and put the loser in spectator.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalDuelDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player loser)) return;
        UUID otherId = activeDuels.get(loser.getUniqueId());
        if (otherId == null || e.getFinalDamage() < loser.getHealth()) return;

        // Let vanilla handle a totem only when one is actually held in either hand.
        // If one is held, the resurrection event below chains the next inventory totem.
        if (isTotem(loser.getInventory().getItemInMainHand())
                || isTotem(loser.getInventory().getItemInOffHand())) return;
        Player winner = Bukkit.getPlayer(otherId);
        if (winner == null || !winner.isOnline()) return;
        // Intercept lethal damage so the round can be counted without ending the whole duel.
        // The shared round handler decides whether this is the match point or another round.
        e.setCancelled(true);
        loser.setHealth(1.0);
        handleDuelRoundWin(winner, loser);
    }

    private boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING && item.getAmount() > 0;
    }

    // Totems are intentionally NOT replenished automatically. A popped totem is consumed
    // normally; the player must manually place another totem in a hand to use it.

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        if (activeDuels.containsKey(e.getEntity().getUniqueId())) return; // duels are tracked separately
        Player victim=e.getEntity(); Player killer=victim.getKiller();
        stat(victim.getUniqueId(),"deaths",1); stat(victim.getUniqueId(),"streak",-getStat(victim.getUniqueId(),"streak"));
        if(killer!=null && !killer.equals(victim)) {
            ensurePlayer(killer); stat(killer.getUniqueId(),"kills",1); stat(killer.getUniqueId(),"streak",1);
            int streak=getStat(killer.getUniqueId(),"streak"); if(streak>getStat(killer.getUniqueId(),"best_streak")) {
                try(PreparedStatement ps=db.prepareStatement("UPDATE stats SET best_streak=? WHERE uuid=?")) {ps.setInt(1,streak);ps.setString(2,killer.getUniqueId().toString());ps.executeUpdate();}catch(SQLException ignored){}
            }
            e.setDeathMessage(c("&5"+victim.getName()+" &7was defeated by &d"+killer.getName()+" &8(&d"+(int)Math.ceil(killer.getHealth()/2)+"❤&8)"));
        }
    }
    @EventHandler public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String[] pieces = e.getMessage().substring(1).split("\\s+", 2);
        String root = pieces[0].toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        String args = pieces.length > 1 ? pieces[1] : "";
        if (root.equals("r")) { e.setCancelled(true); if (args.isBlank()) tell(p, "&cUsage: /r <message>"); else replyToLast(p, args); return; }
        if (root.equals("gmsp")) { e.setCancelled(true); if (!isModOrAbove(p)) { tell(p, "&cMod or above required."); return; } p.setGameMode(GameMode.SPECTATOR); tell(p, "&dSpectator mode enabled silently."); return; }
        if (root.equals("gmc") || root.equals("gms") || root.equals("gamemode")) {
            e.setCancelled(true);
            if (root.equals("gmc")) {
                if (!isDev(p)) { tell(p, "&cOnly the Owner can use /gmc."); return; }
                p.setGameMode(GameMode.CREATIVE); tell(p, "&dGamemode updated."); return;
            }
            if (root.equals("gms")) {
                if (!isModOrAbove(p)) { tell(p, "&cMod or above required."); return; }
                p.setGameMode(GameMode.SURVIVAL); tell(p, "&dGamemode updated."); return;
            }
            if (!isDev(p)) { tell(p, "&cOnly the Owner can use /gamemode. Mods and above may use /gms and /gmsp."); return; }
            else {
                String[] gmArgs = args.isBlank() ? new String[0] : args.trim().split("\\s+");
                if (gmArgs.length < 1 || gmArgs.length > 2) {
                    tell(p, "&cUsage: /gamemode <mode> [player]");
                    return;
                }
                GameMode mode;
                try {
                    mode = switch (gmArgs[0].toLowerCase(Locale.ROOT)) {
                        case "s", "survival" -> GameMode.SURVIVAL;
                        case "c", "creative" -> GameMode.CREATIVE;
                        case "a", "adventure" -> GameMode.ADVENTURE;
                        case "sp", "spectator" -> GameMode.SPECTATOR;
                        default -> throw new IllegalArgumentException();
                    };
                } catch (IllegalArgumentException ex) {
                    tell(p, "&cUse survival, creative, adventure, or spectator.");
                    return;
                }
                Player target = gmArgs.length == 2 ? Bukkit.getPlayerExact(gmArgs[1]) : p;
                if (target == null) { tell(p, "&cThat player is not online."); return; }
                target.setGameMode(mode);
                tell(p, "&dSet &f" + target.getName() + "&d's gamemode to &f" + mode.name().toLowerCase(Locale.ROOT) + "&d.");
                if (!target.equals(p)) tell(target, "&dYour gamemode was set to &f" + mode.name().toLowerCase(Locale.ROOT) + "&d by &f" + p.getName() + "&d.");
                return;
            }
        }
        if (Set.of("ban", "tempban", "timeout", "unban").contains(root) && !isAdminOrAbove(p)) { e.setCancelled(true); tell(p, "&cAdmin or Owner required."); return; }
        if (tagged(p) && getConfig().getBoolean("combat.prevent-commands", true) &&
            getConfig().getStringList("combat.blocked-commands").stream().map(v -> v.toLowerCase(Locale.ROOT)).anyMatch(root::equals)) {
            e.setCancelled(true); tell(p, "&cYou cannot use that command while combat-tagged.");
        }
    }

    private void replyToLast(Player p, String message) {
        UUID id = lastMessaged.get(p.getUniqueId());
        Player to = id == null ? null : Bukkit.getPlayer(id);
        if (to == null) { tell(p, "&cNobody to reply to."); return; }
        if (!isStaff(p) && settingString(to, "private_messages").equals("Off")) { tell(p, "&cThat player has private messages turned off."); return; }
        tell(p, "&dTo " + to.getName() + ": &f" + message);
        tell(to, "&dFrom " + p.getName() + ": &f" + message);
        messageSound(to);
        lastMessaged.put(to.getUniqueId(), p.getUniqueId());
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        String worldName=getConfig().getString("spawn.world","");
        World w=Bukkit.getWorld(worldName);
        if(w!=null)e.setRespawnLocation(new Location(w,getConfig().getDouble("spawn.x",0.5),getConfig().getDouble("spawn.y",80),getConfig().getDouble("spawn.z",0.5), (float)getConfig().getDouble("spawn.yaw",0),(float)getConfig().getDouble("spawn.pitch",0)));
    }

    @Override public boolean onCommand(CommandSender s, Command cmd, String commandLabel, String[] a) {
        String n=cmd.getName().toLowerCase(Locale.ROOT);
        if (n.equals("cmode")) {
            if (!isDev(s)) { tell(s, "&cOnly the Owner can use /cmode."); return true; }
            if (!(s instanceof Player player)) { tell(s, "&cRun this command in-game while standing in the target world."); return true; }
            String worldName = player.getWorld().getName();
            if (cmodeWorlds.remove(worldName)) {
                getConfig().set("cmode-bypass-worlds", new ArrayList<>(cmodeWorlds)); saveConfig();
                tell(player, "&e/cmode OFF: normal block protections restored in &f" + worldName + "&e.");
            } else {
                cmodeWorlds.add(worldName);
                getConfig().set("cmode-bypass-worlds", new ArrayList<>(cmodeWorlds)); saveConfig();
                tell(player, "&a/cmode ON: block placing and breaking allowed in &f" + worldName + "&a, bypassing this plugin's protections. Run /cmode again here to turn it off.");
            }
            return true;
        }
        String category = commandCategory(n);
        if (!can(s, category)) { tell(s, "&cYou don't have permission to use that command."); return true; }
        if(n.equals("role")) { roleCommand(s,a); return true; }
        if(n.equals("help")) {
            tell(s, "&d&lAzurPvP Commands &8— &7private help");
            tell(s, "&7Players: &f/spawn, /stats, /duel, /accept, /msg, /reply, /ignore, /rules, /discord, /ping, /tps, /top, /back, /tpa, /home, /sethome, /warp, /playtime, /report, /spectate, /leave, /leaderboard");
            if (can(s,"staff")) tell(s, "&aStaff: &f/fly, /vanish, /freeze, /staffchat, /whois, /announce");
            if (can(s,"moderation")) tell(s, "&cModeration: &f/ban, /tempban, /unban, /kick, /mute, /unmute, /warn, /history, /heal, /feed, /gamemode, /speed, /clear, /repair");
            if (isDev(s)) tell(s, "&dOwner: &f/role, /setspawn, /maintenance, /backup, /arena");
            return true;
        }
        if(n.equals("spectate")) {
            if(!(s instanceof Player p)){tell(s,"&cPlayers only.");return true;}
            if(a.length<1){tell(p,"&cUsage: /spectate <player>");return true;}
            Player target=Bukkit.getPlayerExact(a[0]);
            if(target==null||!activeDuels.containsKey(target.getUniqueId())){tell(p,"&cThis player is not in a match.");return true;}
            if(activeDuels.containsKey(p.getUniqueId())){tell(p,"&cYou can't spectate while you are in a match.");return true;}
            if(tagged(p)){tell(p,"&cYou can't spectate while combat-tagged.");return true;}
            Player opp=Bukkit.getPlayer(activeDuels.get(target.getUniqueId()));
            if(!isStaff(p)&&(!playerSetting(target,"allow_spectators",true)||(opp!=null&&!playerSetting(opp,"allow_spectators",true)))){tell(p,"&cThis player does not allow spectators.");return true;}
            startSpectating(p,target); return true;
        }
        if(n.equals("leave")) {
            if(!(s instanceof Player p))return true;
            if(!spectating.containsKey(p.getUniqueId())){tell(p,"&cYou are not spectating anyone.");return true;}
            stopSpectating(p,"&dYou left spectator mode."); return true;
        }
        if(n.equals("leaderboard")) {
            if(!(s instanceof Player p)){tell(s,"&cPlayers only.");return true;}
            if(a.length>=1){
                String want=a[0].toLowerCase(Locale.ROOT);
                for(Board b:boards()) if(b.id().equals(want)){openBoard(p,b);return true;}
                tell(p,"&cUnknown leaderboard. Options: &f"+String.join(", ",boards().stream().map(Board::id).toList()));return true;
            }
            openLeaderboards(p); return true;
        }
        if(n.equals("azurpvp")) { tell(s,"&dAzurPvP Utils &8— &fPaper 1.21.11"); tell(s,"&7Use &d/help &7to privately view the commands available to your role."); return true; }
        if(n.equals("world")) {
            if (!isDev(s)) { tell(s, "&cOnly the owner can manage worlds."); return true; }
            if (a.length < 2) { tell(s, "&cUsage: /world create <name> or /world delete <name>"); return true; }
            String action = a[0].toLowerCase(Locale.ROOT);
            String worldName = a[1].replaceAll("[^A-Za-z0-9_-]", "");
            if (worldName.isBlank() || worldName.length() > 48) { tell(s, "&cInvalid world name."); return true; }
            if (action.equals("create")) {
                if (Bukkit.getWorld(worldName) != null || new File(Bukkit.getWorldContainer(), worldName).exists()) {
                    tell(s, "&cA world or folder with that name already exists."); return true;
                }
                try {
                    WorldCreator creator = new WorldCreator(worldName);
                    creator.environment(World.Environment.NORMAL);
                    creator.generator(new ChunkGenerator() {
                        @Override public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
                            return createChunkData(world);
                        }
                    });
                    World created = creator.createWorld();
                    if (created == null) { tell(s, "&cWorld creation failed. Check the console."); return true; }
                    created.setSpawnLocation(0, Math.max(created.getMinHeight() + 1, 64), 0);
                    getConfig().set("void-worlds." + worldName, true);
                    saveConfig();
                    if (s instanceof Player creatorPlayer) {
                        creatorPlayer.teleport(new Location(created, 0.5, 64.0, 0.5));
                        tell(creatorPlayer, "&aCreated void world &f" + worldName + "&a and teleported you to it.");
                    } else {
                        tell(s, "&aCreated void world &f" + worldName + "&a.");
                    }
                    tell(s, "&7Use &f/tp world " + worldName + "&7 to return to its coordinates 0,0.");
                } catch (Exception ex) {
                    getLogger().warning("Could not create void world '" + worldName + "': " + ex.getMessage());
                    tell(s, "&cCould not create world. Check the server console.");
                }
                return true;
            }
            if (action.equals("delete")) {
                World target = Bukkit.getWorld(worldName);
                if (target == null && new File(Bukkit.getWorldContainer(), worldName).exists()) { try { target = Bukkit.createWorld(new WorldCreator(worldName)); } catch (Exception ignored) {} }
                if (target == null) { tell(s, "&cThat world is not loaded and no matching world folder exists."); return true; }
                if (target.getName().equalsIgnoreCase(Bukkit.getWorlds().get(0).getName())) {
                    tell(s, "&cYou cannot delete the primary world. Nether and End can be deleted if they are not the primary world."); return true;
                }
                World fallback = Bukkit.getWorld(getConfig().getString("spawn.world", ""));
                final World worldBeingDeleted = target;
                if (fallback == null || fallback.equals(worldBeingDeleted)) {
                    fallback = Bukkit.getWorlds().stream()
                            .filter(w -> !w.equals(worldBeingDeleted))
                            .findFirst()
                            .orElse(null);
                }
                if (fallback == null) { tell(s, "&cNo safe destination world is loaded; cannot delete this world."); return true; }
                for (Player online : new ArrayList<>(target.getPlayers())) online.teleport(fallback.getSpawnLocation());
                File folder = target.getWorldFolder();
                if (!Bukkit.unloadWorld(target, false)) { tell(s, "&cCould not unload that world; it was not deleted."); return true; }
                if (!deleteWorldFolder(folder)) { tell(s, "&eWorld unloaded, but its folder could not be fully deleted. Remove it manually after stopping the server."); return true; }
                getConfig().set("void-worlds." + worldName, null);
                saveConfig();
                tell(s, "&aDeleted world &f" + worldName + "&a.");
                return true;
            }
            tell(s, "&cUsage: /world create <name> or /world delete <name>"); return true;
        }
        if (n.equals("tp")) {
            if (!(s instanceof Player p)) { tell(s, "&cPlayers only."); return true; }
            if (!isDev(p)) { tell(p, "&cOnly the owner can use /tp world."); return true; }
            if (a.length != 2 || !a[0].equalsIgnoreCase("world")) {
                tell(p, "&cUsage: /tp world <worldname>"); return true;
            }
            World destination = Bukkit.getWorld(a[1]);
            if (destination == null) { tell(p, "&cThat world is not loaded."); return true; }
            p.teleport(new Location(destination, 0.5, Math.max(64, destination.getMinHeight() + 1), 0.5));
            tell(p, "&dTeleported to &f" + destination.getName() + " &dat coordinates &f0,0&d.");
            return true;
        }
        if(n.equals("addarena")) {
            if(!isDev(s)){tell(s,"&cOnly the owner can create arenas.");return true;}
            if(!(s instanceof Player p)||a.length!=2){tell(s,"&cUsage: /addarena <number> <true/false>");return true;}
            int id; boolean mine; try{id=Integer.parseInt(a[0]); if(id<1)throw new NumberFormatException();}catch(NumberFormatException ex){tell(s,"&cArena number must be a positive integer.");return true;}
            if(!a[1].equalsIgnoreCase("true")&&!a[1].equalsIgnoreCase("false")){tell(s,"&cUse true or false for block breaking.");return true;}
            String path="arenas."+id; if(getConfig().contains(path)){tell(p,"&cArena "+id+" already exists. Each number can only be used once.");return true;}
            Location l=p.getLocation(); getConfig().set(path+".world",l.getWorld().getName());getConfig().set(path+".x",l.getX());getConfig().set(path+".y",l.getY());getConfig().set(path+".z",l.getZ());getConfig().set(path+".break-below-zero",Boolean.parseBoolean(a[1]));getConfig().set(path+".spawn1",null);getConfig().set(path+".spawn2",null);saveConfig();
            tell(p,"&aCreated arena "+id+" with a 100-block radius. Breaking is "+(Boolean.parseBoolean(a[1])?"allowed below Y=0":"disabled")+".");return true;
        }
        if(n.equals("set")) {
            if(!isDev(s)){tell(s,"&cOnly the owner can configure arena spawns.");return true;}
            if(!(s instanceof Player p)||a.length!=4||!a[0].equalsIgnoreCase("arena")||!a[1].equalsIgnoreCase("spawn")){tell(s,"&cUsage: /set arena spawn <arena number> <spawn 1|2>");return true;}
            int id,which;try{id=Integer.parseInt(a[2]);which=Integer.parseInt(a[3]);}catch(NumberFormatException ex){tell(s,"&cArena and spawn numbers must be integers.");return true;}
            if(which<1||which>2||!getConfig().contains("arenas."+id)){tell(p,"&cArena does not exist or spawn must be 1 or 2.");return true;}
            Location l=p.getLocation();String path="arenas."+id+".spawn"+which;getConfig().set(path+".world",l.getWorld().getName());getConfig().set(path+".x",l.getX());getConfig().set(path+".y",l.getY());getConfig().set(path+".z",l.getZ());getConfig().set(path+".yaw",l.getYaw());getConfig().set(path+".pitch",l.getPitch());saveConfig();tell(p,"&aSaved arena "+id+" spawn "+which+".");return true;
        }
        if(n.equals("cpvparena")) {
            if (!(s instanceof Player p)) { tell(s, "&cPlayers only."); return true; }
            if (!isDev(p)) { tell(p, "&cOnly the owner can manage CPvP arenas."); return true; }
            if (a.length < 2 || !a[0].equalsIgnoreCase("add")) {
                tell(p, "&cUsage: /cpvparena add <y-level>");
                return true;
            }
            int yLevel;
            try { yLevel = Integer.parseInt(a[1]); }
            catch (NumberFormatException ex) { tell(p, "&cY-level must be a number."); return true; }
            String id = "cpvparena-" + p.getWorld().getName() + "-" + p.getLocation().getBlockX() + "-" + p.getLocation().getBlockZ();
            String path = "cpvparenas." + id;
            getConfig().set(path + ".world", p.getWorld().getName());
            getConfig().set(path + ".x", p.getLocation().getX());
            getConfig().set(path + ".z", p.getLocation().getZ());
            getConfig().set(path + ".y-level", yLevel);
            getConfig().set(path + ".radius", 200);
            saveConfig();
            tell(p, "&aCPvP mining region added: 200-block radius; permitted mining below Y " + yLevel + ".");
            return true;
        }
        if(n.equals("setspawn")) {
            if(!(s instanceof Player p)) {tell(s,"&cPlayers only.");return true;} if(!isDev(p)){tell(s,"&cNo permission.");return true;}
            Location l=p.getLocation(); getConfig().set("spawn.world",l.getWorld().getName()); getConfig().set("spawn.x",l.getX()); getConfig().set("spawn.y",l.getY()); getConfig().set("spawn.z",l.getZ()); getConfig().set("spawn.yaw",l.getYaw()); getConfig().set("spawn.pitch",l.getPitch()); saveConfig(); tell(p,"&aSpawn saved. &7A 500-block radius is now protected from PvP, block changes, and container access. Players in this world are now in &fAdventure &7mode (not in duels, CPvP arenas or /cmode worlds)."); return true;
        }
        if(n.equals("spawn")) {
            if(!(s instanceof Player p))return true; if(tagged(p)){tell(p,"&cYou cannot teleport while combat-tagged.");return true;}
            String wn=getConfig().getString("spawn.world",""); World w=Bukkit.getWorld(wn); if(w==null){tell(p,"&cSpawn is not configured. Ask an admin to use /setspawn.");return true;}
            p.teleport(new Location(w,getConfig().getDouble("spawn.x"),getConfig().getDouble("spawn.y"),getConfig().getDouble("spawn.z"),(float)getConfig().getDouble("spawn.yaw"),(float)getConfig().getDouble("spawn.pitch")));tell(p,"&dTeleported to spawn.");return true;
        }
        if(n.equals("stats")) {
            if(a.length==0 && !(s instanceof Player)){tell(s,"Use /stats <player>");return true;}
            OfflinePlayer target=a.length>0?Bukkit.getOfflinePlayer(a[0]):(Player)s;
            UUID tid=target.getUniqueId();
            int k=getStat(tid,"kills"), d=getStat(tid,"deaths"), dw=getStat(tid,"duel_wins"), dl=getStat(tid,"duel_losses"), games=dw+dl;
            Player online=target.getPlayer();
            long play=getStatLong(tid,"playtime_seconds")+(online!=null?sessionSeconds(tid):0);
            String last;
            if(online!=null) last="&aOnline Now";
            else { long lo=getStatLong(tid,"last_online"); last=lo<=0?"&7Unknown":"&e"+fmtDuration((System.currentTimeMillis()-lo)/1000)+" ago"; }
            s.sendMessage(" ");
            s.sendMessage(c("&f&l"+(target.getName()==null?"Unknown":target.getName())));
            s.sendMessage(" ");
            s.sendMessage(c("&7Kills: &a"+k));
            s.sendMessage(c("&7Deaths: &c"+d));
            s.sendMessage(c("&7K/D Ratio: &6"+String.format(Locale.US,"%.2f",d==0?(double)k:(double)k/d)));
            s.sendMessage(" ");
            s.sendMessage(c("&7Wins: &a"+dw));
            s.sendMessage(c("&7Losses: &c"+dl));
            s.sendMessage(c("&7Win Rate: &6"+String.format(Locale.US,"%.1f%%",games==0?0.0:dw*100.0/games)));
            s.sendMessage(c("&7Win Streak: &a"+getStat(tid,"duel_streak")));
            s.sendMessage(c("&7Best Win Streak: &a"+getStat(tid,"best_duel_streak")));
            s.sendMessage(c("&7Games Played: &b"+games));
            s.sendMessage(c("&7Elo: &d"+getStat(tid,"elo")));
            s.sendMessage(c("&7Playtime: &6"+fmtDuration(play)));
            s.sendMessage(c("&7Last Online: "+last));
            s.sendMessage(" ");
            return true;
        }
        if(n.equals("kit")) {
            if(!(s instanceof Player p)) { tell(s, "&cPlayers only."); return true; }
            if(a.length == 1 && a[0].equalsIgnoreCase("clear")) {
                if(!isDev(p)) { tell(p, "&cOnly the owner can use /kit clear."); return true; }
                boolean hasItems = false;
                for (ItemStack item : p.getInventory().getContents()) if (item != null && item.getType() != Material.AIR) { hasItems = true; break; }
                for (ItemStack item : p.getInventory().getArmorContents()) if (item != null && item.getType() != Material.AIR) hasItems = true;
                if (p.getInventory().getItemInOffHand().getType() != Material.AIR) hasItems = true;
                if (hasItems) {
                    p.getInventory().clear();
                    p.getInventory().setArmorContents(null);
                    p.getInventory().setItemInOffHand(null);
                    kitLoaded.add(p.getUniqueId());
                    tell(p, "&aInventory, armor and offhand cleared. Run &f/kit clear &aagain to restore the Duel sword and Kit editor.");
                } else {
                    kitLoaded.remove(p.getUniqueId());
                    giveDuelItem(p);
                    giveKitEditor(p);
                    tell(p, "&dDuel sword and Kit editor restored.");
                }
                return true;
            }
            if(a.length >= 2 && a[0].equalsIgnoreCase("upload")) {
                if(!isDev(p)) { tell(p,"&cOnly the owner can upload kits."); return true; }
                String kitName=normalizeKitName(a[1]);
                if(kitName.isBlank()) { tell(p,"&cUse /kit upload <name>"); return true; }
                if(!getConfig().contains("kits."+kitName)) getConfig().set("kits."+kitName+".cooldown-seconds", 0);
                // Store only the 36 storage slots. Bukkit's getContents() also includes
                // armor and offhand slots, which must be saved separately below.
                ItemStack[] playerContents = p.getInventory().getStorageContents();
                ItemStack[] uploaded = new ItemStack[36];
                for (int i = 0; i < 36; i++) {
                    ItemStack item = i < playerContents.length ? playerContents[i] : null;
                    uploaded[i] = (item == null || isDuelItem(item) || isKitEditor(item)) ? null : item.clone();
                }
                getConfig().set("kits."+kitName+".contents", uploaded);
                getConfig().set("kits."+kitName+".armor", p.getInventory().getArmorContents());
                getConfig().set("kits."+kitName+".offhand", p.getInventory().getItemInOffHand());
                saveConfig(); reloadConfig(); tell(p,"&aUploaded server-default kit &f"+kitName+"&a."); return true;
            }
            if(a.length==1) {
                if(!isDev(p)) { tell(p, "&cOnly the owner can use /kit <name>. Use the Duel sword to select a kit."); return true; }
                String kitName=normalizeKitName(a[0]);
                if(kitName.isBlank() || !getConfig().contains("kits."+kitName)) {
                    ConfigurationSection ks=getConfig().getConfigurationSection("kits");
                    tell(p, "&cUnknown kit. Available: &f" + (ks==null ? "none" : String.join(", ", ks.getKeys(false))));
                    return true;
                }
                giveDuelKit(p, kitName);
                kitLoaded.add(p.getUniqueId());
                tell(p, "&aLoaded kit &f" + kitName + "&a. Run &f/kit clear &atwice to get your Duel sword and Kit editor back.");
                return true;
            }
            tell(p, isDev(p) ? "&cUse /kit <name>, /kit upload <name> or /kit clear." : "&cKit commands are unavailable. Use the Duel sword to select a kit.");
            return true;
        }
        if(n.equals("duel")) {
            if(!(s instanceof Player p)||a.length<1){tell(s,"&cUse /duel <kit> to queue, or /duel <player> to challenge someone.");return true;}
            String kitArg=normalizeKitName(a[0]);
            if(!kitArg.isBlank() && getConfig().contains("kits."+kitArg)) { queueForDuel(p,kitArg); return true; }
            Player target=Bukkit.getPlayerExact(a[0]);
            if(target==null||target.equals(p)){tell(p,"&cNo kit or player found with that name. Use /duel <kit> to queue, or /duel <player> to challenge someone.");return true;}
            if(!isStaff(p) && settingString(target,"duel_requests").equals("Off")){tell(p,"&c"+target.getName()+" is not accepting duel requests.");return true;}
            duelTargets.put(p.getUniqueId(),target.getName()); duelGuiMode.put(p.getUniqueId(),"challenge"); openKitMenu(p,"duel",target.getName()); return true;
        }
        if(n.equals("accept")) {
            if(!(s instanceof Player p)||a.length<1)return true; UUID challenger=duelRequests.remove(p.getUniqueId()); Player from=challenger==null?null:Bukkit.getPlayer(challenger);
            if(from==null||!from.getName().equalsIgnoreCase(a[0])){tell(p,"&cNo matching duel request.");return true;}
            startDuel(from,p); return true;
        }
        if(n.equals("msg")) {
            if(a.length<2){tell(s,"Use /msg <player> <message>");return true;} Player to=Bukkit.getPlayerExact(a[0]);if(to==null){tell(s,"&cPlayer not found.");return true;}
            String msg=String.join(" ",Arrays.copyOfRange(a,1,a.length)); if(s instanceof Player from && ignored.getOrDefault(to.getUniqueId(),Set.of()).contains(from.getUniqueId())){tell(s,"&cThat player is ignoring you.");return true;}
            if(s instanceof Player sender0 && !isStaff(sender0) && settingString(to,"private_messages").equals("Off")){tell(s,"&cThat player has private messages turned off.");return true;} tell(s,"&dTo "+to.getName()+": &f"+msg);tell(to,"&dFrom "+s.getName()+": &f"+msg);messageSound(to);if(s instanceof Player from)lastMessaged.put(to.getUniqueId(),from.getUniqueId());if(s instanceof Player from)lastMessaged.put(from.getUniqueId(),to.getUniqueId());return true;
        }
        if(n.equals("reply")) { if(!(s instanceof Player p)||a.length<1)return true; replyToLast(p, String.join(" ", a)); return true; }
        if(n.equals("ignore")) {
            if(!(s instanceof Player p)||a.length<1)return true;Player target=Bukkit.getPlayerExact(a[0]);if(target==null){tell(p,"&cPlayer not found.");return true;}
            Set<UUID> set=ignored.computeIfAbsent(p.getUniqueId(),id->ConcurrentHashMap.newKeySet());if(!set.add(target.getUniqueId())){set.remove(target.getUniqueId());tell(p,"&dStopped ignoring "+target.getName()+".");}else tell(p,"&dIgnoring "+target.getName()+".");return true;
        }
        if(n.equals("top")) {
            String field=a.length>0?a[0].toLowerCase(Locale.ROOT):"kills";
            if(!Set.of("kills","deaths","best_streak","playtime_seconds").contains(field)) field="kills";
            tell(s,"&dTop players by "+field+":");
            try(Statement st=db.createStatement(); ResultSet rs=st.executeQuery("SELECT name,"+field+" FROM stats ORDER BY "+field+" DESC LIMIT 10")) { int i=1; while(rs.next()) tell(s,"&8"+(i++)+". &f"+rs.getString(1)+" &8— &d"+rs.getLong(2)); } catch(SQLException ex){tell(s,"&cCould not load leaderboard.");}
            return true;
        }
        if(n.equals("back")) { if(!(s instanceof Player p))return true; Location l=lastLocations.get(p.getUniqueId()); if(l==null){tell(p,"&cNo previous location saved.");return true;} if(tagged(p)){tell(p,"&cYou cannot use /back during combat.");return true;} p.teleport(l);tell(p,"&dReturned to your previous location.");return true; }
        if(n.equals("tpa")) {
            if (!isAdminOrAbove(s)) { tell(s, "&cAdmin or Owner required."); return true; }
            if (!(s instanceof Player p)) { tell(s, "&cOnly an in-game player can use /tpa."); return true; }
            if (a.length != 1) { tell(p, "&cUsage: /tpa <player>"); return true; }
            Player target = Bukkit.getPlayerExact(a[0]);
            if (target == null) { tell(p, "&cThat player is not online."); return true; }
            if (target.equals(p)) { tell(p, "&cYou cannot teleport to yourself."); return true; }
            p.teleport(target.getLocation());
            tell(p, "&dTeleported to &f" + target.getName() + "&d.");
            tell(target, "&dAdmin &f" + p.getName() + " &dteleported to you.");
            return true;
        }
        if(n.equals("gamemode")) {
            if(!isDev(s)){tell(s,"&cOnly Owner can use /gamemode. Mods and above may use /gms and /gmsp.");return true;}
            if(a.length < 1 || a.length > 2){tell(s,"&cUsage: /gamemode <mode> [player]");return true;}
            GameMode mode;
            try { mode = switch(a[0].toLowerCase(Locale.ROOT)) { case "s","survival" -> GameMode.SURVIVAL; case "c","creative" -> GameMode.CREATIVE; case "a","adventure" -> GameMode.ADVENTURE; case "sp","spectator" -> GameMode.SPECTATOR; default -> throw new IllegalArgumentException(); }; }
            catch(Exception ex){tell(s,"&cUse survival, creative, adventure, or spectator.");return true;}
            Player target = a.length == 2 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player p ? p : null);
            if(target == null){tell(s, a.length == 2 ? "&cThat player is not online." : "&cFrom console, specify a player: /gamemode <mode> <player>");return true;}
            target.setGameMode(mode); tell(s,"&dSet &f"+target.getName()+"&d's gamemode to &f"+mode.name().toLowerCase(Locale.ROOT)+"&d.");
            if(!(s instanceof Player p) || !target.equals(p)) tell(target,"&dYour gamemode was set to &f"+mode.name().toLowerCase(Locale.ROOT)+"&d.");
            return true;
        }
        if(n.equals("speed")) { if(!isStaff(s)){tell(s,"&cStaff role required.");return true;} if(!(s instanceof Player p)||a.length<1)return true;try{float v=Math.max(0.1f,Math.min(10f,Float.parseFloat(a[0]))/10f);if(p.isFlying())p.setFlySpeed(v);else p.setWalkSpeed(v);tell(p,"&dSpeed updated.");}catch(Exception ex){tell(p,"&cUsage: /speed <1-10>");}return true; }
        if(n.equals("clear")) { if(!(s instanceof Player p))return true; p.getInventory().clear();tell(p,"&dInventory cleared.");return true; }
        if(n.equals("repair")) { if(!(s instanceof Player p))return true;ItemStack it=p.getInventory().getItemInMainHand();if(it.getType()==Material.AIR||it.getType().getMaxDurability()==0){tell(p,"&cHold a damageable item.");return true;}it.setDurability((short)0);tell(p,"&dItem repaired.");return true; }
        if(n.equals("whois")) { if(!isStaff(s)){tell(s,"&cStaff role required.");return true;} if(a.length<1)return true;Player t=Bukkit.getPlayerExact(a[0]);if(t==null){tell(s,"&cPlayer not found.");return true;}tell(s,"&d"+t.getName()+" &7UUID: &f"+t.getUniqueId()+" &7Ping: &f"+t.getPing()+"ms &7Role: &f"+roleOf(t.getUniqueId()));return true; }
        if(n.equals("announce")) { if(!isStaff(s)){tell(s,"&cStaff role required.");return true;} if(a.length<1)return true;Bukkit.broadcastMessage(prefix()+c(String.join(" ",a)));return true; }
        if(n.equals("maintenance")) { if(!isDev(s)){tell(s,"&cOwner role required.");return true;} boolean enabled=!getConfig().getBoolean("maintenance.enabled",false);getConfig().set("maintenance.enabled",enabled);saveConfig(); if(enabled) for(Player online:Bukkit.getOnlinePlayers()) if(!isStaff(online)) online.kickPlayer(c("&5AzurPvP &8» &cServer maintenance is enabled.")); tell(s,"&dMaintenance mode "+(enabled?"enabled":"disabled")+".");return true; }
        if(n.equals("settings")) { if(!(s instanceof Player p))return true;openSettings(p);return true; }
        if(n.equals("playtime")) { if(!(s instanceof Player p))return true;long seconds=getStatLong(p.getUniqueId(),"playtime_seconds")+sessionSeconds(p.getUniqueId());tell(p,"&dPlaytime: &f"+fmtDuration(seconds));return true; }
        if(n.equals("cps")) { tell(s,"&7CPS monitoring is not active in this build; use a dedicated anti-cheat for enforcement.");return true; }
        if(n.equals("rules")){tell(s,getConfig().getString("messages.rules","&dBe respectful."));return true;}
        if(n.equals("discord")){tell(s,getConfig().getString("messages.discord","&dSet your Discord invite in config.yml"));return true;}
        if(n.equals("ping")){if(s instanceof Player p)tell(p,"&7Ping: &d"+p.getPing()+"ms");return true;}
        if(n.equals("tps")){tell(s,"&7TPS: &d"+String.format(Locale.US,"%.2f",Bukkit.getTPS()[0]));return true;}
        if(n.equals("fly")){if(!(s instanceof Player p))return true;if(!isStaff(p)){tell(p,"&cStaff role required for /fly.");return true;}p.setAllowFlight(!p.getAllowFlight());tell(p,"&dFlight "+(p.getAllowFlight()?"enabled":"disabled")+".");return true;}
        if(n.equals("heal")||n.equals("feed")){if(!isStaff(s)){tell(s,"&cNo permission.");return true;}Player target=a.length>0?Bukkit.getPlayerExact(a[0]):(s instanceof Player?(Player)s:null);if(target==null){tell(s,"Use /"+n+" [player]");return true;}if(n.equals("heal")){target.setHealth(target.getMaxHealth());target.setFireTicks(0);}else{target.setFoodLevel(20);target.setSaturation(20);}tell(target,"&dYou have been "+(n.equals("heal")?"healed":"fed")+".");return true;}
        if(n.equals("vanish")){if(!(s instanceof Player p))return true;if(!isStaff(p)){tell(p,"&cNo permission.");return true;}if(vanished.remove(p.getUniqueId())){for(Player other:Bukkit.getOnlinePlayers())other.showPlayer(this,p);tell(p,"&dVanish disabled.");}else{vanished.add(p.getUniqueId());for(Player other:Bukkit.getOnlinePlayers())if(!isStaff(other))other.hidePlayer(this,p);tell(p,"&dVanish enabled.");}return true;}
        if(n.equals("freeze")){if(!isStaff(s)){tell(s,"&cNo permission.");return true;}if(a.length<1)return true;Player target=Bukkit.getPlayerExact(a[0]);if(target==null){tell(s,"&cPlayer not found.");return true;}if(!frozen.add(target.getUniqueId())){frozen.remove(target.getUniqueId());tell(s,"&dUnfroze "+target.getName()+".");tell(target,"&aYou are unfrozen.");}else{tell(s,"&dFroze "+target.getName()+".");tell(target,"&cYou have been frozen by staff.");}return true;}
        if(n.equals("report")){if(a.length<2){tell(s,"Use /report <player> <reason>");return true;}try(PreparedStatement ps=db.prepareStatement("INSERT INTO reports(reporter,target,reason) VALUES(?,?,?)")){ps.setString(1,s.getName());ps.setString(2,a[0]);ps.setString(3,String.join(" ",Arrays.copyOfRange(a,1,a.length)));ps.executeUpdate();tell(s,"&aReport submitted to staff.");for(Player p:Bukkit.getOnlinePlayers())if(isStaff(p))tell(p,"&5Report: &d"+s.getName()+" reported "+a[0]+" &8— &f"+String.join(" ",Arrays.copyOfRange(a,1,a.length)));}catch(SQLException ex){tell(s,"&cCould not save report.");}return true;}
        if(n.equals("staffchat")){if(!isStaff(s)){tell(s,"&cNo permission.");return true;}if(a.length<1)return true;String msg=c("&8[&5Staff&8] &d"+s.getName()+"&8: &f"+String.join(" ",a));for(Player p:Bukkit.getOnlinePlayers())if(isStaff(p))p.sendMessage(msg);getLogger().info(ChatColor.stripColor(msg));return true;}
        return false;
    }
    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item=new ItemStack(material); ItemMeta meta=item.getItemMeta(); if(meta!=null){meta.setDisplayName(c(name)); if(lore!=null)meta.setLore(lore.stream().map(this::c).toList()); item.setItemMeta(meta);} return item;
    }
    private void queueForDuel(Player player, String kit) {
        UUID playerId = player.getUniqueId();
        if (activeDuels.containsKey(playerId)) { tell(player, "&cYou are already in a duel."); return; }
        if (spectating.containsKey(playerId)) { tell(player, "&cUse &f/leave &cbefore queueing."); return; }
        String normalizedKit = normalizeKitName(kit);
        LinkedHashMap<String, Long> playerKits = duelQueue.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        if (playerKits.containsKey(normalizedKit)) {
            playerKits.remove(normalizedKit);
            if (playerKits.isEmpty()) duelQueue.remove(playerId);
            if (playerSetting(player, "queue_sounds", false)) player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.65f);
            tell(player, "&eRemoved &f" + prettyKitName(normalizedKit) + " &efrom your queue.");
            return;
        }
        playerKits.put(normalizedKit, System.currentTimeMillis());
        if (playerSetting(player, "queue_sounds", false)) player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.5f);
        tell(player, "&dQueued for &f" + prettyKitName(normalizedKit) + "&d. Click the same kit again to leave that queue.");

        Player opponent = null;
        String matchedKit = null;
        for (Map.Entry<UUID, LinkedHashMap<String, Long>> entry : new ArrayList<>(duelQueue.entrySet())) {
            UUID candidateId = entry.getKey();
            if (candidateId.equals(playerId) || activeDuels.containsKey(candidateId)) continue;
            Player candidate = Bukkit.getPlayer(candidateId);
            if (candidate == null || !candidate.isOnline()) { duelQueue.remove(candidateId); continue; }
            if (entry.getValue().containsKey(normalizedKit) && pingCompatible(player, candidate)) {
                opponent = candidate;
                matchedKit = normalizedKit;
                break;
            }
        }
        if (opponent != null) {
            duelChosenKits.put(playerId, matchedKit);
            duelChosenKits.put(opponent.getUniqueId(), matchedKit);
            if (!startDuel(player, opponent)) {
                if (normalizeKitName(matchedKit).equals("vanilla")) {
                    tell(player, "&eAll CPvP arenas are full. You are still queued and will teleport when an arena becomes free.");
                    tell(opponent, "&eAll CPvP arenas are full. You are still queued and will teleport when an arena becomes free.");
                } else {
                    tell(player, "&eAll duel arenas are full. You are still queued and will teleport when an arena becomes free.");
                    tell(opponent, "&eAll duel arenas are full. You are still queued and will teleport when an arena becomes free.");
                }
            }
        }
    }

    private void processWaitingDuelQueues() {
        List<UUID> ids = new ArrayList<>(duelQueue.keySet());
        Set<UUID> paired = new HashSet<>();
        for (UUID firstId : ids) {
            if (paired.contains(firstId) || activeDuels.containsKey(firstId)) continue;
            Player first = Bukkit.getPlayer(firstId);
            LinkedHashMap<String, Long> firstKits = duelQueue.get(firstId);
            if (first == null || firstKits == null || firstKits.isEmpty()) continue;
            for (UUID secondId : ids) {
                if (secondId.equals(firstId) || paired.contains(secondId) || activeDuels.containsKey(secondId)) continue;
                Player second = Bukkit.getPlayer(secondId);
                LinkedHashMap<String, Long> secondKits = duelQueue.get(secondId);
                if (second == null || secondKits == null) continue;
                String common = firstKits.keySet().stream().filter(secondKits::containsKey).findFirst().orElse(null);
                if (common == null || !pingCompatible(first, second)) continue;
                duelChosenKits.put(firstId, common);
                duelChosenKits.put(secondId, common);
                if (startDuel(first, second)) {
                    duelQueue.remove(firstId);
                    duelQueue.remove(secondId);
                    first.sendActionBar(net.kyori.adventure.text.Component.empty());
                    second.sendActionBar(net.kyori.adventure.text.Component.empty());
                    tell(first, "&aMatched with &f" + second.getName() + " &afor &f" + prettyKitName(common) + "&a.");
                    tell(second, "&aMatched with &f" + first.getName() + " &afor &f" + prettyKitName(common) + "&a.");
                    paired.add(firstId);
                    paired.add(secondId);
                }
                break;
            }
        }
    }

    private List<String> arenaKeys() { ConfigurationSection sec=getConfig().getConfigurationSection("arenas"); if(sec==null)return Collections.emptyList(); return new ArrayList<>(sec.getKeys(false)); }
    private Location arenaCenter(String id) { String path="arenas."+id; World w=Bukkit.getWorld(getConfig().getString(path+".world","")); if(w==null)return null; return new Location(w,getConfig().getDouble(path+".x"),getConfig().getDouble(path+".y"),getConfig().getDouble(path+".z")); }
    private Location arenaSpawn(String id,int n) { String path="arenas."+id+".spawn"+n; if(!getConfig().contains(path+".world"))return null; World w=Bukkit.getWorld(getConfig().getString(path+".world","")); if(w==null)return null; return new Location(w,getConfig().getDouble(path+".x"),getConfig().getDouble(path+".y"),getConfig().getDouble(path+".z"),(float)getConfig().getDouble(path+".yaw"),(float)getConfig().getDouble(path+".pitch")); }
    private boolean isInsideCpvPArena(Location location, String cpvpId) {
        if (location == null || location.getWorld() == null) return false;
        String path = "cpvparenas." + cpvpId;
        World world = Bukkit.getWorld(getConfig().getString(path + ".world", ""));
        if (world == null || !world.equals(location.getWorld())) return false;
        double dx = location.getX() - getConfig().getDouble(path + ".x");
        double dz = location.getZ() - getConfig().getDouble(path + ".z");
        double radius = getConfig().getDouble(path + ".radius", 200.0);
        return dx * dx + dz * dz <= radius * radius;
    }

    private String cpvpRegionForArena(String arenaId) {
        Location one = arenaSpawn(arenaId, 1), two = arenaSpawn(arenaId, 2);
        if (one == null || two == null) return null;
        ConfigurationSection regions = getConfig().getConfigurationSection("cpvparenas");
        if (regions == null) return null;
        for (String id : regions.getKeys(false)) {
            if (isInsideCpvPArena(one, id) && isInsideCpvPArena(two, id)) return id;
        }
        return null;
    }

    private boolean startDuel(Player first,Player second) {
        if(activeDuels.containsKey(first.getUniqueId())||activeDuels.containsKey(second.getUniqueId())) { tell(first,"&cOne of you is already in a duel.");tell(second,"&cOne of you is already in a duel.");return false; }
        // The challenger/first player is authoritative for direct /duel requests.
        // Copy their selected kit and round count to BOTH participants so stale selections
        // left on the target from an earlier duel cannot override the challenge settings.
        String kit = normalizeKitName(duelChosenKits.getOrDefault(first.getUniqueId(), duelChosenKits.getOrDefault(second.getUniqueId(), "sword")));
        duelChosenKits.put(first.getUniqueId(), kit);
        duelChosenKits.put(second.getUniqueId(), kit);
        int configuredRounds = Math.max(1, Math.min(99, duelRounds.getOrDefault(first.getUniqueId(), duelRounds.getOrDefault(second.getUniqueId(), 1))));
        duelRounds.put(first.getUniqueId(), configuredRounds);
        duelRounds.put(second.getUniqueId(), configuredRounds);
        boolean vanilla = kit.equals("vanilla");
        String arena = null;
        for (String id : arenaKeys()) {
            if (arenaSpawn(id, 1) == null || arenaSpawn(id, 2) == null || occupiedDuelArenas.contains(id)) continue;
            boolean cpvpArena = cpvpRegionForArena(id) != null;
            if (vanilla == cpvpArena) { arena = id; break; }
        }
        if (arena == null) return false;
        final String selectedArena = arena;
        duelRoundWins.put(first.getUniqueId(), 0); duelRoundWins.put(second.getUniqueId(), 0);
        occupiedDuelArenas.add(selectedArena);
        activeDuels.put(first.getUniqueId(),second.getUniqueId());activeDuels.put(second.getUniqueId(),first.getUniqueId()); duelArenaByPlayer.put(first.getUniqueId(),selectedArena);duelArenaByPlayer.put(second.getUniqueId(),selectedArena); duelCountdown.add(first.getUniqueId());duelCountdown.add(second.getUniqueId());
        refreshAllLobbyVisibility();
        first.setGameMode(GameMode.SURVIVAL);second.setGameMode(GameMode.SURVIVAL);first.setHealth(first.getMaxHealth());second.setHealth(second.getMaxHealth());first.setFoodLevel(20);second.setFoodLevel(20);
        tell(first,"&aDuel accepted: &f"+second.getName()+"&a!"); tell(second,"&aDuel accepted: &f"+first.getName()+"&a!");
        if (playerSetting(first, "duel_sounds", true)) first.playSound(first.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        if (playerSetting(second, "duel_sounds", true)) second.playSound(second.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        first.sendTitle(c("&d&lDUEL READY"),c("&7Teleporting in 3 seconds..."),0,60,0); second.sendTitle(c("&d&lDUEL READY"),c("&7Teleporting in 3 seconds..."),0,60,0);
        for(int sec=3;sec>=1;sec--){final int n=sec; Bukkit.getScheduler().runTaskLater(this,()->{if(activeDuels.containsKey(first.getUniqueId())&&activeDuels.containsKey(second.getUniqueId())){if(playerSetting(first,"duel_titles",true)) first.sendTitle(c("&5&l"+n),c("&7Teleporting to arena"),0,20,0); if(playerSetting(second,"duel_titles",true)) second.sendTitle(c("&5&l"+n),c("&7Teleporting to arena"),0,20,0); if(playerSetting(first,"duel_sounds",true)) first.playSound(first.getLocation(),org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING,1.0f,1.0f+n*0.15f); if(playerSetting(second,"duel_sounds",true)) second.playSound(second.getLocation(),org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING,1.0f,1.0f+n*0.15f);}},(4-sec)*20L);}
        Bukkit.getScheduler().runTaskLater(this,()->{
            if(!activeDuels.containsKey(first.getUniqueId())||!activeDuels.containsKey(second.getUniqueId())||!first.isOnline()||!second.isOnline())return;
            first.teleport(arenaSpawn(selectedArena,1)); second.teleport(arenaSpawn(selectedArena,2));
            // Duel combat tagging begins on arena entry and remains until the duel ends.
            arenaCombat.add(first.getUniqueId());
            arenaCombat.add(second.getUniqueId());
            // Equip the selected kits only after both players have arrived at their arena spawns.
            giveDuelKit(first, duelChosenKits.getOrDefault(first.getUniqueId(), "sword"));
            giveDuelKit(second, duelChosenKits.getOrDefault(second.getUniqueId(), "sword"));
            first.setHealth(first.getMaxHealth()); second.setHealth(second.getMaxHealth());
            first.setFoodLevel(20); second.setFoodLevel(20);
            first.setSaturation(0.0f); second.setSaturation(0.0f);
            // Start each duel at zero saturation; afterward food items and golden apples
            // can restore saturation normally during the fight.
            first.sendTitle(c("&5&l5"),c("&7PvP enabled in 5 seconds"),0,20,0); second.sendTitle(c("&5&l5"),c("&7PvP enabled in 5 seconds"),0,20,0);
            for(int sec=4;sec>=1;sec--){final int n=sec;Bukkit.getScheduler().runTaskLater(this,()->{if(activeDuels.containsKey(first.getUniqueId())&&activeDuels.containsKey(second.getUniqueId())){first.sendTitle(c("&5&l"+n),c("&7Get ready"),0,20,0);second.sendTitle(c("&5&l"+n),c("&7Get ready"),0,20,0);}},(5-sec)*20L);}
            Bukkit.getScheduler().runTaskLater(this,()->{if(activeDuels.containsKey(first.getUniqueId())&&activeDuels.containsKey(second.getUniqueId())){duelCountdown.remove(first.getUniqueId());duelCountdown.remove(second.getUniqueId());first.sendTitle(c("&a&lFIGHT!"),c("&7Good luck"),0,30,10);second.sendTitle(c("&a&lFIGHT!"),c("&7Good luck"),0,30,10);}},100L);
        },60L);
        return true;
    }

    private void giveDuelKit(Player p,String kit) {
        String path="player-kits."+p.getUniqueId()+"."+kit; if(!getConfig().contains("kits."+kit)){kit="sword";path="kits.sword";} else if(getConfig().contains(path+".contents")){} else path="kits."+kit;
        List<?> contents=getConfig().getList(path+".contents");
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        if(contents!=null)for(int i=0;i<Math.min(36,contents.size());i++)if(contents.get(i) instanceof ItemStack it)p.getInventory().setItem(i,it.clone());
        List<?> armor=getConfig().getList(path+".armor");
        ItemStack[] arr=new ItemStack[4];
        if(armor!=null)for(int i=0;i<Math.min(4,armor.size());i++)if(armor.get(i) instanceof ItemStack it)arr[i]=it.clone();
        p.getInventory().setArmorContents(arr);
        ItemStack off=getConfig().getItemStack(path+".offhand");
        p.getInventory().setItemInOffHand(off==null?new ItemStack(Material.AIR):off.clone());
    }
    private void finishDuel(Player winner,Player loser) {
        if(winner==null||loser==null)return;
        // Record the result once (finishDuel can be reached twice, e.g. final round + quit).
        if (activeDuels.containsKey(winner.getUniqueId()) && activeDuels.containsKey(loser.getUniqueId())) {
            try {
                recordDuelResult(winner, loser, normalizeKitName(duelChosenKits.getOrDefault(winner.getUniqueId(), "sword")),
                    duelRoundWins.getOrDefault(winner.getUniqueId(), 0), duelRoundWins.getOrDefault(loser.getUniqueId(), 0), duelArenaByPlayer.get(winner.getUniqueId()));
            } catch (Throwable t) { getLogger().severe("[DuelStats] recordDuelResult crashed: " + t); t.printStackTrace(); }
        } else getLogger().warning("[DuelStats] finishDuel skipped recording (duel already finished): " + winner.getName() + " vs " + loser.getName());
        String finishedArenaId = duelArenaByPlayer.get(winner.getUniqueId());
        if (finishedArenaId == null) finishedArenaId = duelArenaByPlayer.get(loser.getUniqueId());
        if (finishedArenaId != null) occupiedDuelArenas.remove(finishedArenaId);
        final String arenaToClean = finishedArenaId;
        activeDuels.remove(winner.getUniqueId());activeDuels.remove(loser.getUniqueId()); duelRoundWins.remove(winner.getUniqueId()); duelRoundWins.remove(loser.getUniqueId());arenaCombat.remove(winner.getUniqueId());arenaCombat.remove(loser.getUniqueId());combatUntil.remove(winner.getUniqueId());combatUntil.remove(loser.getUniqueId());duelCountdown.remove(winner.getUniqueId());duelCountdown.remove(loser.getUniqueId());duelArenaByPlayer.remove(winner.getUniqueId());duelArenaByPlayer.remove(loser.getUniqueId());
        if(playerSetting(winner,"duel_titles",true))winner.sendTitle(c("&a&lVICTORY"),c("&7You won the duel!"),0,100,0);if(playerSetting(loser,"duel_titles",true))loser.sendTitle(c("&c&lDEFEAT"),c("&7Returning to spawn soon..."),0,100,0);
        Bukkit.getScheduler().runTask(this, this::refreshAllLobbyVisibility);
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(spectating.entrySet())) {
            if (!en.getValue().equals(winner.getUniqueId()) && !en.getValue().equals(loser.getUniqueId())) continue;
            Player sp = Bukkit.getPlayer(en.getKey());
            if (sp == null) continue;
            tell(sp, "&dThe match has ended. Returning to the lobby in 5 seconds...");
            final UUID spId = en.getKey(), tId = en.getValue();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player again = Bukkit.getPlayer(spId);
                if (again != null && tId.equals(spectating.get(spId))) stopSpectating(again, "&dReturned to the lobby.");
            }, 100L);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> { for (Player gg : List.of(winner, loser)) if (gg.isOnline() && playerSetting(gg, "auto_gg", false)) gg.chat("gg"); }, 20L);
        if (winner.isOnline() && playerSetting(winner, "duel_sounds", true)) winner.playSound(winner.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f);
        if (loser.isOnline() && playerSetting(loser, "duel_sounds", true)) loser.playSound(loser.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
        pendingDuelLosers.add(loser.getUniqueId());if(loser.isOnline()&&!loser.isDead())loser.setGameMode(GameMode.SPECTATOR);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            String spawnWorldName = getConfig().getString("spawn.world", "");
            World spawnWorld = Bukkit.getWorld(spawnWorldName);
            Location spawnLocation = null;
            if (spawnWorld != null) {
                spawnLocation = new Location(spawnWorld,
                    getConfig().getDouble("spawn.x", 0.5),
                    getConfig().getDouble("spawn.y", 80),
                    getConfig().getDouble("spawn.z", 0.5),
                    (float) getConfig().getDouble("spawn.yaw", 0),
                    (float) getConfig().getDouble("spawn.pitch", 0));
            }
            if (loser.isOnline()) {
                loser.setGameMode(GameMode.SURVIVAL);
                loser.getInventory().clear();
                loser.getInventory().setArmorContents(null);
                loser.getInventory().setItemInOffHand(null);
                loser.setFireTicks(0);
                loser.setFallDistance(0);
                if (spawnLocation != null) loser.teleport(spawnLocation);
                pendingDuelLosers.remove(loser.getUniqueId());
                giveDuelItem(loser);
                giveKitEditor(loser);
            }
            if (winner.isOnline()) {
                winner.setGameMode(GameMode.SURVIVAL);
                winner.getInventory().clear();
                winner.getInventory().setArmorContents(null);
                winner.getInventory().setItemInOffHand(null);
                winner.setFireTicks(0);
                winner.setFallDistance(0);
                if (spawnLocation != null) winner.teleport(spawnLocation);
                giveDuelItem(winner);
                giveKitEditor(winner);
            }
            // Once both players have returned, remove any dropped duel items/projectiles from the arena.
            if (arenaToClean != null) {
                Bukkit.getScheduler().runTaskLater(this, () -> cleanArenaEntities(arenaToClean), 1L);
            }
        }, 100L);
    }

    private void cleanArenaEntities(String arenaId) {
        Location center = arenaCenter(arenaId);
        if (center == null || center.getWorld() == null) return;
        for (Entity entity : center.getWorld().getNearbyEntities(center, 100.0, 256.0, 100.0)) {
            if (entity instanceof Item || entity instanceof Arrow || entity instanceof Trident
                    || entity instanceof Snowball || entity instanceof Egg || entity instanceof EnderPearl
                    || entity instanceof ExperienceOrb || entity instanceof Firework) {
                entity.remove();
            }
        }
    }

    private void openKitMenu(Player p, String mode, String target) {
        Inventory inv=Bukkit.createInventory(null,54,c(mode.equals("editor") ? "&5&lAzurPvP &8» &dKit Editor" : "&5&lAzurPvP &8» &dSelect a Kit"));
        ConfigurationSection kits=getConfig().getConfigurationSection("kits");
        int[] slots=new int[45]; int[] centered={10,12,14,16,19,21,23,25,31}; int si=0;
        for(int value:centered) slots[si++]=value;
        for(int value=0;value<45;value++) { boolean used=false; for(int center:centered) if(center==value) { used=true; break; } if(!used) slots[si++]=value; }
        int index=0;
        if(kits!=null) for(String key:kits.getKeys(false)) {
            if(index>=slots.length) break;
            ItemStack icon=kitIconItem(key);
            ItemMeta iconMeta=icon.getItemMeta();
            if(iconMeta!=null){
                iconMeta.setDisplayName(c("&d&l"+prettyKitName(key)));
                boolean queued = duelQueue.getOrDefault(p.getUniqueId(), new LinkedHashMap<>()).containsKey(normalizeKitName(key));
                List<String> lore = new ArrayList<>();
                if (mode.equals("editor")) lore.add(c("&7Click to edit your personal version"));
                else {
                    lore.add(c(queued ? "&a✔ QUEUED" : "&7Click to queue for this kit"));
                    lore.add(c(queued ? "&eClick again to leave this queue" : "&7You can queue for multiple kits"));
                }
                iconMeta.setLore(lore); icon.setItemMeta(iconMeta);
            }
            inv.setItem(slots[index++],icon);
        }
        if(index==0) inv.setItem(22,namedItem(Material.BARRIER,"&cNo kits created",List.of("&7Owner: use /kit create <name>")));
        duelGuiMode.put(p.getUniqueId(), mode); if(target!=null)duelTargets.put(p.getUniqueId(),target);
        p.openInventory(inv);
    }
    private void openKitEditInventory(Player p, String kit) {
        Inventory inv=Bukkit.createInventory(null,54,c("&5&lEditing: &d"+prettyKitName(kit)));
        String path="player-kits."+p.getUniqueId()+"."+kit+".contents";
        List<?> contents=getConfig().getList(path);
        if(contents==null) contents=getConfig().getList("kits."+kit+".contents");
        // Only slots 0-35 are player storage. Older kit data may contain armor/offhand
        // appended to contents (from Bukkit's 41-slot getContents()); ignore those legacy entries.
        if(contents!=null) for(int i=0;i<Math.min(36,contents.size());i++) if(contents.get(i) instanceof ItemStack it) inv.setItem(i,it.clone());
        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, "&8", List.of());
        for (int slot = 36; slot <= 44; slot++) inv.setItem(slot, filler.clone());
        inv.setItem(50, filler.clone());
        inv.setItem(52, filler.clone());
        List<?> armor=getConfig().getList("player-kits."+p.getUniqueId()+"."+kit+".armor");
        if(armor==null) armor=getConfig().getList("kits."+kit+".armor");
        if(armor!=null) for(int i=0;i<Math.min(4,armor.size());i++) if(armor.get(i) instanceof ItemStack it) inv.setItem(45+i,it.clone());
        ItemStack offhand=getConfig().getItemStack("player-kits."+p.getUniqueId()+"."+kit+".offhand");
        if(offhand==null) offhand=getConfig().getItemStack("kits."+kit+".offhand");
        inv.setItem(49,offhand==null?new ItemStack(Material.AIR):offhand.clone());
        inv.setItem(51,namedItem(Material.LIME_DYE,"&a&lSave Kit",List.of("&7Save this personal kit")));
        inv.setItem(53,namedItem(Material.REDSTONE,"&c&lReset",List.of("&7Reset to the server's original kit")));
        duelGuiMode.put(p.getUniqueId(),"editing"); duelChosenKits.put(p.getUniqueId(),kit); p.openInventory(inv);
    }
    private void saveEditedKit(Player p, Inventory inv) {
        String kit=duelChosenKits.get(p.getUniqueId()); if(kit==null)return;
        ItemStack[] contents=new ItemStack[36];
        for(int i=0;i<36;i++){ItemStack it=inv.getItem(i);contents[i]=it==null||it.getType()==Material.AIR?null:it.clone();}
        ItemStack[] armor=new ItemStack[4]; for(int i=0;i<4;i++){ItemStack it=inv.getItem(45+i);armor[i]=it==null?null:it.clone();}
        ItemStack offhand=inv.getItem(49);
        getConfig().set("player-kits."+p.getUniqueId()+"."+kit+".contents", Arrays.asList(contents));
        getConfig().set("player-kits."+p.getUniqueId()+"."+kit+".armor", Arrays.asList(armor));
        getConfig().set("player-kits."+p.getUniqueId()+"."+kit+".offhand",offhand==null||offhand.getType()==Material.AIR?null:offhand.clone()); saveConfig();
        p.closeInventory(); tell(p,"&aSaved your personal version of &f"+prettyKitName(kit)+"&a.");
    }
    private void resetEditedKit(Player p) {
        String kit=duelChosenKits.get(p.getUniqueId()); if(kit==null)return;
        getConfig().set("player-kits."+p.getUniqueId()+"."+kit,null); saveConfig(); openKitEditInventory(p,kit); tell(p,"&eYour personal kit was reset to the server default.");
    }
    private String normalizeKitName(String name) { return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", ""); }
    private String prettyKitName(String key) { return Arrays.stream(key.replace('_',' ').split(" ")).filter(x->!x.isBlank()).map(x->Character.toUpperCase(x.charAt(0))+x.substring(1)).reduce((a,b)->a+" "+b).orElse(key); }
    private ItemStack kitIconItem(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "spear_mace" -> new ItemStack(Material.DIAMOND_SPEAR);
            case "diamond_pot" -> {
                ItemStack potion = new ItemStack(Material.SPLASH_POTION);
                if (potion.getItemMeta() instanceof PotionMeta meta) {
                    meta.setBasePotionType(PotionType.STRONG_HEALING);
                    potion.setItemMeta(meta);
                }
                yield potion;
            }
            case "neth_pot" -> new ItemStack(Material.NETHERITE_HELMET);
            case "vanilla" -> new ItemStack(Material.END_CRYSTAL);
            case "sword" -> new ItemStack(Material.DIAMOND_SWORD);
            case "axe" -> new ItemStack(Material.DIAMOND_AXE);
            case "mace" -> new ItemStack(Material.MACE);
            case "uhc" -> new ItemStack(Material.GOLDEN_APPLE);
            case "diamond_smp" -> new ItemStack(Material.DIAMOND_CHESTPLATE);
            default -> new ItemStack(Material.CHEST);
        };
    }
    private Material kitIcon(String key) { return kitIconItem(key).getType(); }
    private void ensureDefaultKits() {
        String[] names={"sword","axe","mace","spear_mace","diamond_pot","neth_pot","uhc","diamond_smp","vanilla"};
        for(String name:names) if(!getConfig().contains("kits."+name)) {
            ItemStack[] contents=new ItemStack[41]; ItemStack[] armor=new ItemStack[4];
            switch(name) {
                case "sword" -> { contents[0]=new ItemStack(Material.DIAMOND_SWORD); contents[1]=new ItemStack(Material.SHIELD); contents[2]=new ItemStack(Material.GOLDEN_APPLE,8); armor[0]=new ItemStack(Material.DIAMOND_BOOTS); armor[1]=new ItemStack(Material.DIAMOND_LEGGINGS); armor[2]=new ItemStack(Material.DIAMOND_CHESTPLATE); armor[3]=new ItemStack(Material.DIAMOND_HELMET); }
                case "axe" -> { contents[0]=new ItemStack(Material.DIAMOND_AXE); contents[1]=new ItemStack(Material.SHIELD); contents[2]=new ItemStack(Material.GOLDEN_APPLE,8); armor[0]=new ItemStack(Material.DIAMOND_BOOTS); armor[1]=new ItemStack(Material.DIAMOND_LEGGINGS); armor[2]=new ItemStack(Material.DIAMOND_CHESTPLATE); armor[3]=new ItemStack(Material.DIAMOND_HELMET); }
                case "mace","spear_mace" -> { contents[0]=new ItemStack(Material.MACE); contents[1]=new ItemStack(Material.WIND_CHARGE,16); contents[2]=new ItemStack(Material.ENDER_PEARL,8); contents[3]=new ItemStack(Material.GOLDEN_APPLE,8); }
                case "diamond_pot" -> { contents[0]=new ItemStack(Material.DIAMOND_SWORD); contents[1]=new ItemStack(Material.SPLASH_POTION,16); contents[2]=new ItemStack(Material.ENDER_PEARL,16); contents[3]=new ItemStack(Material.GOLDEN_APPLE,16); armor[0]=new ItemStack(Material.DIAMOND_BOOTS); armor[1]=new ItemStack(Material.DIAMOND_LEGGINGS); armor[2]=new ItemStack(Material.DIAMOND_CHESTPLATE); armor[3]=new ItemStack(Material.DIAMOND_HELMET); }
                case "neth_pot" -> { contents[0]=new ItemStack(Material.NETHERITE_SWORD); contents[1]=new ItemStack(Material.SPLASH_POTION,16); contents[2]=new ItemStack(Material.ENDER_PEARL,16); armor[0]=new ItemStack(Material.NETHERITE_BOOTS); armor[1]=new ItemStack(Material.NETHERITE_LEGGINGS); armor[2]=new ItemStack(Material.NETHERITE_CHESTPLATE); armor[3]=new ItemStack(Material.NETHERITE_HELMET); }
                case "uhc" -> { contents[0]=new ItemStack(Material.DIAMOND_SWORD); contents[1]=new ItemStack(Material.BOW); contents[2]=new ItemStack(Material.ARROW,32); contents[3]=new ItemStack(Material.GOLDEN_APPLE,8); contents[4]=new ItemStack(Material.WATER_BUCKET); contents[5]=new ItemStack(Material.LAVA_BUCKET); }
                case "diamond_smp" -> { contents[0]=new ItemStack(Material.DIAMOND_SWORD); contents[1]=new ItemStack(Material.DIAMOND_PICKAXE); contents[2]=new ItemStack(Material.GOLDEN_APPLE,8); armor[0]=new ItemStack(Material.DIAMOND_BOOTS); armor[1]=new ItemStack(Material.DIAMOND_LEGGINGS); armor[2]=new ItemStack(Material.DIAMOND_CHESTPLATE); armor[3]=new ItemStack(Material.DIAMOND_HELMET); }
                default -> { contents[0]=new ItemStack(Material.IRON_SWORD); contents[1]=new ItemStack(Material.BOW); contents[2]=new ItemStack(Material.ARROW,32); contents[3]=new ItemStack(Material.COOKED_BEEF,16); }
            }
            getConfig().set("kits."+name+".contents",contents); getConfig().set("kits."+name+".armor",armor); getConfig().set("kits."+name+".cooldown-seconds",0);
        }
        saveConfig();
    }
    private void giveKitEditor(Player p) {
        ItemStack book=namedItem(Material.BOOK,"&d&lKit editor",List.of("&7Click to edit your personal kits"));
        p.getInventory().setItem(7,book);
        giveSettingsItem(p); // the settings anvil always comes together with the kit editor
    }
    private final Set<UUID> kitLoaded = ConcurrentHashMap.newKeySet();      // owner used /kit <name>: don't overwrite their inventory
    private final Set<UUID> autoAdventure = ConcurrentHashMap.newKeySet();  // players this plugin put into Adventure in the spawn world
    private boolean inCpvpRegion(Location loc) {
        ConfigurationSection regions = getConfig().getConfigurationSection("cpvparenas");
        if (regions == null) return false;
        for (String id : regions.getKeys(false)) if (isInsideCpvPArena(loc, id)) return true;
        return false;
    }
    // The spawn world (set with /setspawn) is Adventure mode. Duels, spectating, CPvP mining areas,
    // /cmode worlds and any non-survival gamemode (staff) are left alone.
    private void startAdventureGuard() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            String wn = getConfig().getString("spawn.world", "");
            if (wn.isBlank()) return;
            World lobby = Bukkit.getWorld(wn);
            if (lobby == null) return;
            boolean bypass = cmodeWorlds.contains(wn);
            for (Player pl : Bukkit.getOnlinePlayers()) {
                UUID id = pl.getUniqueId();
                boolean busy = activeDuels.containsKey(id) || spectating.containsKey(id) || pendingDuelLosers.contains(id);
                boolean inLobby = pl.getWorld().equals(lobby) && !inCpvpRegion(pl.getLocation());
                if (inLobby && !bypass && !busy && pl.getGameMode() == GameMode.SURVIVAL) {
                    pl.setGameMode(GameMode.ADVENTURE);
                    autoAdventure.add(id);
                } else if (!inLobby && autoAdventure.remove(id) && pl.getGameMode() == GameMode.ADVENTURE) {
                    pl.setGameMode(GameMode.SURVIVAL);
                }
            }
        }, 20L, 10L);
    }
    private boolean isLobbyItem(ItemStack item) { return isDuelItem(item) || isKitEditor(item) || isSettingsItem(item); }
    private void giveLobbyItems(Player p) {
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) { if (i == 0 || i == 7 || i == 8) continue; if (isLobbyItem(inv.getItem(i))) inv.setItem(i, null); }
        if (isLobbyItem(inv.getItemInOffHand())) inv.setItemInOffHand(null);
        giveDuelItem(p); giveKitEditor(p);
    }
    // Safety net: anyone standing in the lobby (not in a duel or spectating) always has all three items in their slots.
    private void startLobbyItemGuard() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (activeDuels.containsKey(online.getUniqueId()) || spectating.containsKey(online.getUniqueId()) || pendingDuelLosers.contains(online.getUniqueId()) || kitLoaded.contains(online.getUniqueId())) continue;
                if (online.getGameMode() != GameMode.SURVIVAL && online.getGameMode() != GameMode.ADVENTURE) continue;
                if (!inSpawnProtection(online.getLocation())) continue;
                org.bukkit.inventory.PlayerInventory inv = online.getInventory();
                if (!isDuelItem(inv.getItem(0)) || !isKitEditor(inv.getItem(7)) || !isSettingsItem(inv.getItem(8))) giveLobbyItems(online);
            }
        }, 40L, 20L);
    }
    @EventHandler public void onLobbyItemSwapHand(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        if (isLobbyItem(e.getMainHandItem()) || isLobbyItem(e.getOffHandItem())) e.setCancelled(true);
    }
    private boolean isKitEditor(ItemStack item) { return item!=null && item.getType()==Material.BOOK && item.hasItemMeta() && item.getItemMeta().hasDisplayName() && c("&d&lKit editor").equals(item.getItemMeta().getDisplayName()); }
    private ItemStack settingsItem() { return namedItem(Material.ANVIL, "&d&lSettings", List.of("&7Personal PvP settings", "&7Only changes your own preferences")); }
    private boolean isSettingsItem(ItemStack item) { return item != null && item.getType() == Material.ANVIL && item.hasItemMeta() && item.getItemMeta().hasDisplayName() && c("&d&lSettings").equals(item.getItemMeta().getDisplayName()); }
    private boolean playerSetting(Player p, String key, boolean fallback) { return getConfig().getBoolean("player-settings." + p.getUniqueId() + "." + key, fallback); }
    private void setPlayerSetting(Player p, String key, boolean value) { getConfig().set("player-settings." + p.getUniqueId() + "." + key, value); saveConfig(); }
    private void giveSettingsItem(Player p) { p.getInventory().setItem(8, settingsItem()); }
    // ---------- Settings system (anvil) ----------
    private record SettingDef(String key, String label, String category, List<String> options, String def, Material icon, String lore) {
        boolean isToggle() { return options == null; }
    }
    private static SettingDef tgl(String key, String label, String cat, boolean def, Material icon, String lore) { return new SettingDef(key, label, cat, null, String.valueOf(def), icon, lore); }
    private static SettingDef cyc(String key, String label, String cat, List<String> opts, String def, Material icon, String lore) { return new SettingDef(key, label, cat, opts, def, icon, lore); }
    private static final List<String> SETTING_CATEGORIES = List.of("matches", "visuals", "chat", "social");
    private static final List<SettingDef> SETTINGS = List.of(
        // Matches
        cyc("ping_range", "Ping Range", "matches", List.of("No Limit", "50ms", "100ms", "150ms", "200ms"), "No Limit", Material.COMPASS, "Only get queued against players whose ping is within this limit"),
        tgl("allow_spectators", "Allow Spectators", "matches", true, Material.ENDER_EYE, "Let other players /spectate your matches"),
        tgl("spectator_alerts", "Spectator Alerts", "matches", true, Material.BELL, "Get a message when someone starts or stops spectating your match"),
        tgl("see_other_spectators", "See Other Spectators", "matches", false, Material.SPYGLASS, "While spectating, see other spectators"),
        tgl("auto_gg", "Auto GG", "matches", false, Material.NAME_TAG, "Automatically say gg when a duel ends"),
        tgl("duel_titles", "Duel Titles", "matches", true, Material.PAPER, "Countdown, round and result titles"),
        tgl("round_messages", "Round Messages", "matches", true, Material.BOOK, "Round won/lost chat messages"),
        tgl("duel_sounds", "Duel Sounds", "matches", true, Material.NOTE_BLOCK, "Countdown and duel result sounds"),
        tgl("queue_sounds", "Queue Sounds", "matches", false, Material.GOAT_HORN, "Queue confirmation sounds"),
        // Visuals
        tgl("scoreboard", "Scoreboard", "visuals", true, Material.ITEM_FRAME, "Show your personal sidebar"),
        tgl("tab_info", "Tab Info", "visuals", true, Material.PAINTING, "Custom tab header and footer"),
        tgl("kill_effects", "Kill Effects", "visuals", true, Material.BLAZE_POWDER, "Particle burst when you win a round"),
        tgl("lobby_players", "Lobby Players", "visuals", true, Material.PLAYER_HEAD, "Show other players while you are in the lobby"),
        cyc("preferred_time", "Preferred Time", "visuals", List.of("Server", "Sunrise", "Noon", "Sunset", "Midnight"), "Server", Material.CLOCK, "Your own time of day (only you see it)"),
        // Chat
        tgl("public_chat", "Public Chat", "chat", true, Material.OAK_SIGN, "Receive public chat messages"),
        tgl("lobby_tips", "Lobby Tips", "chat", true, Material.LANTERN, "Occasional tips while you are in the lobby"),
        tgl("family_friendly", "Family Friendly Chat", "chat", false, Material.MILK_BUCKET, "Censor swear words in chat you receive"),
        tgl("message_sounds", "Message Sounds", "chat", false, Material.JUKEBOX, "Sound when you receive a private message"),
        cyc("match_chat", "Match Chat", "chat", List.of("Everyone", "Duel Only", "Off"), "Everyone", Material.WRITABLE_BOOK, "Whose chat you see while you are in a duel"),
        // Social & Privacy
        cyc("duel_requests", "Duel Requests", "social", List.of("On", "Off"), "On", Material.IRON_SWORD, "Allow other players to challenge you"),
        cyc("private_messages", "Private Messages", "social", List.of("On", "Off"), "On", Material.FEATHER, "Allow other players to /msg you (staff can always message you)")
    );
    private SettingDef settingDef(String key) { for (SettingDef d : SETTINGS) if (d.key().equals(key)) return d; return null; }
    private List<SettingDef> settingsIn(String category) { return SETTINGS.stream().filter(d -> d.category().equals(category)).toList(); }
    private String settingString(Player p, String key) {
        SettingDef d = settingDef(key);
        Object raw = getConfig().get("player-settings." + p.getUniqueId() + "." + key);
        if (raw == null) return d == null ? "" : d.def();
        return String.valueOf(raw);
    }
    private String categoryTitle(String category) { return switch (category) { case "matches" -> "Matches"; case "visuals" -> "Visuals"; case "chat" -> "Chat"; default -> "Social & Privacy"; }; }
    private Material categoryIcon(String category) { return switch (category) { case "matches" -> Material.IRON_SWORD; case "visuals" -> Material.NETHER_STAR; case "chat" -> Material.OAK_SIGN; default -> Material.SHIELD; }; }
    private String categoryLore(String category) { return switch (category) { case "matches" -> "Duel and queue preferences"; case "visuals" -> "Scoreboard, effects and time"; case "chat" -> "Chat and message preferences"; default -> "Requests and privacy"; }; }
    // Two columns (slots col 2 and col 6), as many rows as needed, back button in the row below.
    private int settingSlot(int index) { return (index / 2) * 9 + (index % 2 == 0 ? 2 : 6); }
    private int settingRows(int count) { return (count + 1) / 2 + 1; }
    private int settingBackSlot(int count) { return (settingRows(count) - 1) * 9 + 4; }

    private void openSettings(Player p) {
        Inventory inv = Bukkit.createInventory(null, settingRows(SETTING_CATEGORIES.size()) * 9, c("&5&lAzurPvP &8» &dSettings"));
        for (int i = 0; i < SETTING_CATEGORIES.size(); i++) {
            String cat = SETTING_CATEGORIES.get(i);
            inv.setItem(settingSlot(i), namedItem(categoryIcon(cat), "&d&l" + categoryTitle(cat), List.of("&7" + categoryLore(cat))));
        }
        inv.setItem(settingBackSlot(SETTING_CATEGORIES.size()), namedItem(Material.BARRIER, "&cClose", List.of("&7Close settings")));
        p.openInventory(inv);
    }
    private void openSettingsCategory(Player p, String category) {
        List<SettingDef> defs = settingsIn(category);
        Inventory inv = Bukkit.createInventory(null, settingRows(defs.size()) * 9, c("&5&lAzurPvP &8» &d" + categoryTitle(category)));
        for (int i = 0; i < defs.size(); i++) {
            SettingDef d = defs.get(i);
            if (d.isToggle()) {
                boolean on = playerSetting(p, d.key(), Boolean.parseBoolean(d.def()));
                inv.setItem(settingSlot(i), settingIcon(d.icon(), on, (on ? "&a✔ &f" : "&c✖ &7") + d.label(), List.of("&7" + d.lore(), "", on ? "&aEnabled" : "&cDisabled", "&8Click to toggle")));
            } else {
                inv.setItem(settingSlot(i), namedItem(d.icon(), "&6⇄ &f" + d.label() + ": &e" + settingString(p, d.key()), List.of("&7" + d.lore(), "", "&8Click to change")));
            }
        }
        inv.setItem(settingBackSlot(defs.size()), namedItem(Material.ARROW, "&6← &fBack", List.of("&7Return to settings")));
        p.openInventory(inv);
    }
    private ItemStack settingIcon(Material m, boolean glow, String name, List<String> lore) {
        ItemStack it = namedItem(m, name, lore);
        if (glow) { ItemMeta meta = it.getItemMeta(); if (meta != null) { meta.setEnchantmentGlintOverride(true); it.setItemMeta(meta); } }
        return it;
    }
    private void cyclePlayerSetting(Player p, SettingDef d) {
        List<String> opts = d.options();
        int idx = opts.indexOf(settingString(p, d.key()));
        String next = opts.get((idx + 1) % opts.size());
        getConfig().set("player-settings." + p.getUniqueId() + "." + d.key(), next);
        saveConfig();
        tell(p, "&d" + d.label() + " set to &f" + next + "&d.");
        if (d.key().equals("preferred_time")) applyPreferredTime(p);
    }
    private void applyPreferredTime(Player p) {
        switch (settingString(p, "preferred_time")) {
            case "Sunrise" -> p.setPlayerTime(23000L, false);
            case "Noon" -> p.setPlayerTime(6000L, false);
            case "Sunset" -> p.setPlayerTime(12500L, false);
            case "Midnight" -> p.setPlayerTime(18000L, false);
            default -> p.resetPlayerTime();
        }
    }
    // Hides/shows other players for viewers who turned "Lobby Players" off. Your duel opponent is always visible.
    // Vanished staff are left alone so /vanish keeps working.
    private void refreshAllLobbyVisibility() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean showAll = playerSetting(viewer, "lobby_players", true);
            UUID opponent = activeDuels.get(viewer.getUniqueId());
            UUID watched = spectating.get(viewer.getUniqueId());
            UUID watchedOpponent = watched == null ? null : activeDuels.get(watched);
            boolean viewerSpectating = watched != null;
            boolean seeSpectators = playerSetting(viewer, "see_other_spectators", false);
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(viewer) || vanished.contains(other.getUniqueId())) continue;
                UUID oid = other.getUniqueId();
                boolean visible = showAll || (opponent != null && opponent.equals(oid)) || (viewerSpectating && (oid.equals(watched) || oid.equals(watchedOpponent)));
                if (viewerSpectating && spectating.containsKey(oid) && !seeSpectators) visible = false;
                if (visible) viewer.showPlayer(this, other); else viewer.hidePlayer(this, other);
            }
        }
    }
    private int pingLimit(Player p) {
        String v = settingString(p, "ping_range");
        if (v.equals("No Limit")) return Integer.MAX_VALUE;
        try { return Integer.parseInt(v.replace("ms", "")); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }
    private boolean pingCompatible(Player a, Player b) { return b.getPing() <= pingLimit(a) && a.getPing() <= pingLimit(b); }
    private void messageSound(Player to) { if (playerSetting(to, "message_sounds", false)) to.playSound(to.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f); }
    private static final List<String> DEFAULT_FAMILY_FRIENDLY_WORDS = List.of("fuck", "shit", "bitch", "cunt", "whore", "slut", "nigger", "nigga", "faggot", "retard");
    private String filterProfanity(String msg) {
        List<String> words = getConfig().getStringList("chat.family-friendly-words");
        if (words.isEmpty()) words = DEFAULT_FAMILY_FRIENDLY_WORDS;
        String out = msg;
        for (String w : words) { if (!w.isBlank()) out = out.replaceAll("(?i)" + java.util.regex.Pattern.quote(w), "*".repeat(w.length())); }
        return out;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChatSettings(AsyncPlayerChatEvent e) {
        Player sender = e.getPlayer();
        UUID senderId = sender.getUniqueId();
        String filtered = filterProfanity(e.getMessage());
        boolean hasProfanity = !filtered.equals(e.getMessage());
        List<Player> censored = new ArrayList<>();
        Iterator<Player> it = e.getRecipients().iterator();
        while (it.hasNext()) {
            Player r = it.next();
            if (r.equals(sender)) continue;
            if (!playerSetting(r, "public_chat", true)) { it.remove(); continue; }
            UUID rOpponent = activeDuels.get(r.getUniqueId());
            if (rOpponent != null) {
                String mc = settingString(r, "match_chat");
                if (mc.equals("Off") || (mc.equals("Duel Only") && !rOpponent.equals(senderId))) { it.remove(); continue; }
            }
            if (hasProfanity && playerSetting(r, "family_friendly", false)) { it.remove(); censored.add(r); }
        }
        if (!censored.isEmpty()) {
            String line;
            try { line = String.format(e.getFormat(), sender.getDisplayName(), filtered); } catch (RuntimeException ex) { line = "<" + sender.getName() + "> " + filtered; }
            for (Player r : censored) r.sendMessage(line);
        }
    }
    @EventHandler public void onSettingsJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) { applyPreferredTime(p); refreshAllLobbyVisibility(); } }, 5L);
    }
    private int lobbyTipIndex = 0;

    // ---------- Spectating ----------
    private Location lobbySpawnLocation() {
        World w = Bukkit.getWorld(getConfig().getString("spawn.world", ""));
        if (w == null) return null;
        return new Location(w, getConfig().getDouble("spawn.x", 0.5), getConfig().getDouble("spawn.y", 80), getConfig().getDouble("spawn.z", 0.5),
            (float) getConfig().getDouble("spawn.yaw", 0), (float) getConfig().getDouble("spawn.pitch", 0));
    }
    private void startSpectating(Player p, Player target) {
        duelQueue.remove(p.getUniqueId());
        spectating.put(p.getUniqueId(), target.getUniqueId());
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(target.getLocation());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && target.isOnline() && target.getUniqueId().equals(spectating.get(p.getUniqueId()))) p.setSpectatorTarget(target);
        }, 2L);
        tell(p, "&dYou are now spectating &f" + target.getName() + "&d. Use &f/leave &dto stop.");
        alertDuelists(p, target, "&f" + p.getName() + " &dis now spectating your match.");
        refreshAllLobbyVisibility();
    }
    private void alertDuelists(Player spectator, Player target, String message) {
        if (vanished.contains(spectator.getUniqueId())) return;
        UUID oppId = activeDuels.get(target.getUniqueId());
        Player opp = oppId == null ? null : Bukkit.getPlayer(oppId);
        for (Player d : new Player[]{target, opp}) {
            if (d != null && d.isOnline() && playerSetting(d, "spectator_alerts", true)) tell(d, message);
        }
    }
    private void stopSpectating(Player p, String message) {
        UUID targetId = spectating.remove(p.getUniqueId());
        if (targetId == null || !p.isOnline()) return;
        p.setSpectatorTarget(null);
        p.setGameMode(GameMode.SURVIVAL);
        Location spawn = lobbySpawnLocation();
        if (spawn != null) p.teleport(spawn);
        if (message != null) tell(p, message);
        Player target = Bukkit.getPlayer(targetId);
        if (target != null && activeDuels.containsKey(targetId)) alertDuelists(p, target, "&f" + p.getName() + " &dstopped spectating your match.");
        refreshAllLobbyVisibility();
    }
    private void startSpectatorWatch() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Map.Entry<UUID, UUID> en : new ArrayList<>(spectating.entrySet())) {
                Player sp = Bukkit.getPlayer(en.getKey());
                if (sp == null) { spectating.remove(en.getKey()); continue; }
                Player t = Bukkit.getPlayer(en.getValue());
                if (t == null || !activeDuels.containsKey(t.getUniqueId())) continue; // match end is handled by finishDuel
                if (sp.getGameMode() != GameMode.SPECTATOR) sp.setGameMode(GameMode.SPECTATOR);
                if (!sp.getWorld().equals(t.getWorld()) || sp.getLocation().distanceSquared(t.getLocation()) > 80 * 80) {
                    sp.teleport(t.getLocation());
                    sp.setSpectatorTarget(t);
                }
            }
        }, 20L, 20L);
    }
    @EventHandler(ignoreCancelled = true) public void onSpectatorTeleport(PlayerTeleportEvent e) {
        // Stop spectators using the spectator menu to teleport out of the match.
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE && spectating.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void onKitLoadedQuit(PlayerQuitEvent e) { kitLoaded.remove(e.getPlayer().getUniqueId()); autoAdventure.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void onSpectatorQuit(PlayerQuitEvent e) {
        if (spectating.remove(e.getPlayer().getUniqueId()) != null) e.getPlayer().setGameMode(GameMode.SURVIVAL);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onSpectatorCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!spectating.containsKey(p.getUniqueId())) return;
        String root = e.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT).replaceFirst("^azurpvputils:", "");
        if (root.equals("spawn")) { e.setCancelled(true); stopSpectating(p, "&dYou left spectator mode."); }
        else if (root.equals("duel") || root.equals("accept")) { e.setCancelled(true); tell(p, "&cUse &f/leave &cfirst."); }
    }

    // ---------- Leaderboards ----------
    private record Board(String id, String title, Material icon, String label, String sql) {}
    private List<Board> boards() {
        int minDuels = Math.max(1, getConfig().getInt("leaderboard.min-duels-for-win-rate", 5));
        return List.of(
            new Board("ranked", "Ranked Leaderboards", Material.NETHERITE_SWORD, "Elo", "SELECT uuid,name,elo AS v FROM stats WHERE duel_wins+duel_losses>0 ORDER BY elo DESC, duel_wins DESC LIMIT 10"),
            new Board("wins", "Top Wins", Material.DIAMOND_SWORD, "Wins", "SELECT uuid,name,duel_wins AS v FROM stats WHERE duel_wins>0 ORDER BY duel_wins DESC LIMIT 10"),
            new Board("winrate", "Top Win Rate", Material.GOLDEN_APPLE, "Win rate", "SELECT uuid,name,ROUND(duel_wins*100.0/(duel_wins+duel_losses),1) AS v FROM stats WHERE duel_wins+duel_losses>=" + minDuels + " ORDER BY v DESC, duel_wins DESC LIMIT 10"),
            new Board("winstreaks", "Top Win Streaks", Material.BLAZE_POWDER, "Best win streak", "SELECT uuid,name,best_duel_streak AS v FROM stats WHERE best_duel_streak>0 ORDER BY best_duel_streak DESC LIMIT 10"),
            new Board("kills", "Top Kills", Material.IRON_SWORD, "Kills", "SELECT uuid,name,kills AS v FROM stats WHERE kills>0 ORDER BY kills DESC LIMIT 10"),
            new Board("killstreaks", "Top Kill Streaks", Material.TNT, "Best kill streak", "SELECT uuid,name,best_streak AS v FROM stats WHERE best_streak>0 ORDER BY best_streak DESC LIMIT 10")
        );
    }
    private void openLeaderboards(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, c("&5&lAzurPvP &8» &dLeaderboards"));
        List<Board> list = boards();
        for (int i = 0; i < list.size(); i++) inv.setItem(10 + i, namedItem(list.get(i).icon(), "&d&l" + list.get(i).title(), List.of("&7Click to view the top 10")));
        inv.setItem(22, namedItem(Material.BARRIER, "&cClose", List.of("&7Close leaderboards")));
        p.openInventory(inv);
    }
    private void openBoard(Player p, Board b) {
        Inventory inv = Bukkit.createInventory(null, 45, c("&5&lAzurPvP &8» &d" + b.title()));
        int[] slots = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
        int rank = 0;
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(b.sql())) {
            while (rs.next() && rank < slots.length) {
                String uuid = rs.getString("uuid"), name = rs.getString("name");
                double v = rs.getDouble("v");
                String value = b.id().equals("winrate") ? String.format(Locale.US, "%.1f%%", v) : String.valueOf((long) v);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                ItemMeta meta = head.getItemMeta();
                if (meta instanceof org.bukkit.inventory.meta.SkullMeta sm) {
                    try { sm.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(uuid))); } catch (IllegalArgumentException ignored) {}
                    String color = rank == 0 ? "&6" : rank == 1 ? "&f" : rank == 2 ? "&c" : "&7";
                    sm.setDisplayName(c(color + "#" + (rank + 1) + " &f" + name));
                    sm.setLore(List.of(c("&7" + b.label() + ": &d" + value)));
                    head.setItemMeta(sm);
                }
                inv.setItem(slots[rank++], head);
            }
        } catch (SQLException e) { getLogger().warning("Leaderboard query failed: " + e.getMessage()); }
        if (rank == 0) inv.setItem(22, namedItem(Material.BARRIER, "&cNo data yet", List.of("&7Nobody is on this leaderboard yet")));
        inv.setItem(40, namedItem(Material.ARROW, "&6← &fBack", List.of("&7Return to leaderboards")));
        p.openInventory(inv);
    }
    private void startLobbyTips() {
        long interval = Math.max(30L, getConfig().getLong("tips.interval-seconds", 300L)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            List<String> tips = getConfig().getStringList("tips.messages");
            if (tips.isEmpty()) tips = List.of("&7Challenge someone with &d/duel <player>&7.", "&7Change your preferences with the &dSettings &7anvil in your hotbar.", "&7Check your stats with &d/stats&7.", "&7Join the community with &d/discord&7.");
            String tip = tips.get(lobbyTipIndex++ % tips.size());
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (activeDuels.containsKey(online.getUniqueId()) || !playerSetting(online, "lobby_tips", true)) continue;
                tell(online, "&dTip &8» " + tip);
            }
        }, interval, interval);
    }
    private void togglePlayerSetting(Player p, String key, boolean fallback) {
        boolean value = !playerSetting(p, key, fallback);
        setPlayerSetting(p, key, value);
        tell(p, "&d" + key.replace('_', ' ') + " set to " + (value ? "&aON" : "&cOFF") + "&d.");
        if (key.equals("scoreboard")) {
            if (value) updateAzurTabAndScoreboard(p);
            else { Scoreboard board = p.getScoreboard(); org.bukkit.scoreboard.Objective objective = board.getObjective("azurpvp"); if (objective != null) objective.setDisplaySlot(null); }
        }
        if (key.equals("lobby_players")) refreshAllLobbyVisibility();
        if (key.equals("tab_info")) {
            if (value) p.setPlayerListHeaderFooter(c("\n&5&lAZUR&d&lPVP\n&7Minecraft 1.21.11+\n"), c("\n&7Players: &d" + Bukkit.getOnlinePlayers().size() + "\n&7Ping: &a" + p.getPing() + "ms\n&d/discord &8| &d/store &8| &d/website\n"));
            else p.setPlayerListHeaderFooter("", "");
        }
    }
    private boolean isDuelItem(ItemStack item) { return item!=null && item.getType()==Material.IRON_SWORD && item.hasItemMeta() && item.getItemMeta().hasDisplayName() && DUEL_ITEM_NAME.equals(item.getItemMeta().getDisplayName()); }
    private void giveDuelItem(Player p) {
        ItemStack sword = null;
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack item = p.getInventory().getItem(i);
            if (isDuelItem(item)) { sword = item; if (i != 0) p.getInventory().setItem(i, null); break; }
        }
        if (sword == null) sword = namedItem(Material.IRON_SWORD, DUEL_ITEM_NAME, List.of("§7Right or left click to choose a kit"));
        p.getInventory().setItem(0, sword);
    }
    @EventHandler public void onDuelItemUse(PlayerInteractEvent e) {
        if (e.getAction()==Action.RIGHT_CLICK_AIR||e.getAction()==Action.RIGHT_CLICK_BLOCK||e.getAction()==Action.LEFT_CLICK_AIR||e.getAction()==Action.LEFT_CLICK_BLOCK) {
            if (isSettingsItem(e.getItem())) { e.setCancelled(true); openSettings(e.getPlayer()); return; }
            if (isDuelItem(e.getItem())) { e.setCancelled(true); openKitMenu(e.getPlayer(),"queue",null); return; }
            if (isKitEditor(e.getItem())) { e.setCancelled(true); openKitMenu(e.getPlayer(),"editor",null); }
        }
    }
    @EventHandler public void onDuelItemDrop(PlayerDropItemEvent e) { if(isDuelItem(e.getItemDrop().getItemStack()) || isKitEditor(e.getItemDrop().getItemStack()) || isSettingsItem(e.getItemDrop().getItemStack()))e.setCancelled(true); }
    private void openRoundsInputMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, c("&5&lAzurPvP &8» &dSet Rounds"));
        inv.setItem(11, namedItem(Material.PAPER, "&d&lEnter Round Count", List.of("&7Click to type a number in chat", "&7Allowed: 1-99 rounds")));
        inv.setItem(15, namedItem(Material.ARROW, "&a&lBack", List.of("&7Return to duel settings")));
        p.openInventory(inv);
    }

    private void openDuelSettings(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, c("&5&lAzurPvP &8» &dDuel Settings"));
        int rounds = duelRounds.getOrDefault(p.getUniqueId(), 1);
        String map = duelMaps.getOrDefault(p.getUniqueId(), "random");
        String kit = duelChosenKits.getOrDefault(p.getUniqueId(), "sword");
        inv.setItem(10, namedItem(Material.PAPER, "&d&lRounds: &f" + rounds, List.of("&7Click to set the amount", "&7Enter 1-99 rounds")));
        inv.setItem(13, namedItem(Material.FILLED_MAP, "&d&lMap: &f" + map, List.of("&7Click to cycle available maps")));
        inv.setItem(16, namedItem(Material.LIME_CONCRETE, "&a&lSend Duel Request", List.of("&7Kit: &f" + prettyKitName(kit), "&7Rounds: &f" + rounds, "&7Map: &f" + map)));
        ItemStack selectedIcon=kitIconItem(kit);
        ItemMeta selectedMeta=selectedIcon.getItemMeta();
        if(selectedMeta!=null){ selectedMeta.setDisplayName(c("&dSelected Kit: &f"+prettyKitName(kit))); selectedMeta.setLore(List.of(c("&7Close this menu to cancel"))); selectedIcon.setItemMeta(selectedMeta); }
        inv.setItem(22, selectedIcon);
        p.openInventory(inv);
    }

    @EventHandler public void onDuelInventoryClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p))return;
        if(isDuelItem(e.getCurrentItem()) || isDuelItem(e.getCursor()) || isKitEditor(e.getCurrentItem()) || isKitEditor(e.getCursor()) || isSettingsItem(e.getCurrentItem()) || isSettingsItem(e.getCursor())) { e.setCancelled(true); return; }
        if (e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY && (isLobbyItem(e.getView().getBottomInventory().getItem(e.getHotbarButton())) || isLobbyItem(e.getCurrentItem()))) { e.setCancelled(true); return; }
        if (e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND && (isLobbyItem(e.getCurrentItem()) || isLobbyItem(p.getInventory().getItemInOffHand()))) { e.setCancelled(true); return; }
        if (e.getClick() == org.bukkit.event.inventory.ClickType.DROP || e.getClick() == org.bukkit.event.inventory.ClickType.CONTROL_DROP) { if (isLobbyItem(e.getCurrentItem())) { e.setCancelled(true); return; } }
        String title=ChatColor.stripColor(e.getView().getTitle());
        if(title==null)return;
        if (title.equals("AzurPvP » Settings")) {
            e.setCancelled(true); if (e.getClickedInventory()!=e.getView().getTopInventory()) return;
            if (e.getSlot()==settingBackSlot(SETTING_CATEGORIES.size())) { p.closeInventory(); return; }
            for (int i = 0; i < SETTING_CATEGORIES.size(); i++) if (e.getSlot()==settingSlot(i)) { openSettingsCategory(p, SETTING_CATEGORIES.get(i)); return; }
            return;
        }
        String settingsCategory = null;
        for (String cat : SETTING_CATEGORIES) if (title.equals("AzurPvP » " + categoryTitle(cat))) settingsCategory = cat;
        if (settingsCategory != null) {
            e.setCancelled(true); if (e.getClickedInventory()!=e.getView().getTopInventory()) return;
            List<SettingDef> defs = settingsIn(settingsCategory);
            if (e.getSlot()==settingBackSlot(defs.size())) { openSettings(p); return; }
            for (int i = 0; i < defs.size(); i++) {
                if (e.getSlot()!=settingSlot(i)) continue;
                SettingDef d = defs.get(i);
                if (d.isToggle()) togglePlayerSetting(p, d.key(), Boolean.parseBoolean(d.def())); else cyclePlayerSetting(p, d);
                openSettingsCategory(p, settingsCategory);
                return;
            }
            return;
        }
        if (title.equals("AzurPvP » Leaderboards")) {
            e.setCancelled(true); if (e.getClickedInventory()!=e.getView().getTopInventory()) return;
            if (e.getSlot()==22) { p.closeInventory(); return; }
            List<Board> list = boards();
            if (e.getSlot()>=10 && e.getSlot()-10<list.size()) openBoard(p, list.get(e.getSlot()-10));
            return;
        }
        for (Board lbBoard : boards()) if (title.equals("AzurPvP » " + lbBoard.title())) {
            e.setCancelled(true); if (e.getClickedInventory()!=e.getView().getTopInventory()) return;
            if (e.getSlot()==40) openLeaderboards(p);
            return;
        }
        if(title.startsWith("Editing:")) {
            if(e.getClickedInventory()!=e.getView().getTopInventory()) { e.setCancelled(true); return; }
            if(e.getSlot()==51) { e.setCancelled(true); saveEditedKit(p,e.getView().getTopInventory()); return; }
            if(e.getSlot()==53) { e.setCancelled(true); resetEditedKit(p); return; }
            int slot = e.getSlot();
            // Never allow shift-clicking from player inventory into the editor or
            // taking filler/control items. Normal clicks within editable slots remain allowed.
            if (e.getClickedInventory() != e.getView().getTopInventory()
                    || (slot >= 36 && slot <= 44) || slot == 50 || slot == 52 || slot >= 54
                    || e.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || e.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
                e.setCancelled(true);
                return;
            }
            return;
        }
        if (title.contains("Set Rounds")) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            if (e.getSlot() == 11) { awaitingRoundsInput.add(p.getUniqueId()); p.closeInventory(); tell(p, "&dType the number of rounds in chat (&f1-99&d), or type &fcancel&d."); }
            else if (e.getSlot() == 15) openDuelSettings(p);
            return;
        }
        if (!title.contains("Select a Kit") && !title.contains("Kit Editor") && !title.contains("Duel Settings")) return;
        e.setCancelled(true); if(e.getClickedInventory()==null||e.getClickedInventory()!=e.getView().getTopInventory())return;
        ItemStack item=e.getCurrentItem(); if(item==null||!item.hasItemMeta()||!item.getItemMeta().hasDisplayName())return;
        String display=ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if(title.contains("Kit Editor")) { String kit=normalizeKitName(display); if(getConfig().contains("kits."+kit)) openKitEditInventory(p,kit); return; }
        if(title.contains("Select a Kit")) {
            if(item.getType()==Material.BARRIER)return;
            String kit=normalizeKitName(display); if(!getConfig().contains("kits."+kit))return;
            String mode=duelGuiMode.getOrDefault(p.getUniqueId(),"queue");
            if(mode.equals("duel")) { duelChosenKits.put(p.getUniqueId(),kit); duelRounds.putIfAbsent(p.getUniqueId(),1); duelMaps.putIfAbsent(p.getUniqueId(),"random"); openDuelSettings(p); }
            else if(mode.equals("challenge")) { duelChosenKits.put(p.getUniqueId(),kit); duelRounds.putIfAbsent(p.getUniqueId(),1); duelMaps.putIfAbsent(p.getUniqueId(),"random"); openDuelSettings(p); }
            else { queueForDuel(p, kit); if (!activeDuels.containsKey(p.getUniqueId())) openKitMenu(p, "queue", null); }
        } else {
            if(e.getSlot()==10) { openRoundsInputMenu(p); }
            else if(e.getSlot()==13) { List<String> maps=getConfig().getStringList("duel.maps"); if(maps.isEmpty())maps=List.of("random"); String current=duelMaps.getOrDefault(p.getUniqueId(),"random"); int i=maps.indexOf(current); duelMaps.put(p.getUniqueId(),maps.get((i+1)%maps.size())); openDuelSettings(p); }
            else if(e.getSlot()==16) { String targetName=duelTargets.get(p.getUniqueId()); Player target=targetName==null?null:Bukkit.getPlayerExact(targetName); if(target==null){p.closeInventory();tell(p,"&cYour opponent is no longer online.");return;} if(!isStaff(p) && settingString(target,"duel_requests").equals("Off")){p.closeInventory();tell(p,"&c"+target.getName()+" is not accepting duel requests.");return;} duelRequests.put(target.getUniqueId(),p.getUniqueId()); p.closeInventory(); tell(p,"&dDuel request sent to &f"+target.getName()+"&d. Kit: &f"+duelChosenKits.getOrDefault(p.getUniqueId(),"default")+"&d, rounds: &f"+duelRounds.getOrDefault(p.getUniqueId(),3)+"&d, map: &f"+duelMaps.getOrDefault(p.getUniqueId(),"random")); tell(target,"&d"+p.getName()+" challenged you! Use &f/accept "+p.getName()+"&d."); }
        }
    }
    @EventHandler public void onRoundsInputChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        if (!awaitingRoundsInput.remove(player.getUniqueId())) return;
        e.setCancelled(true);
        String input = e.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            if (input.equalsIgnoreCase("cancel")) tell(player, "&eRound count unchanged.");
            else try {
                int rounds = Integer.parseInt(input);
                if (rounds < 1 || rounds > 99) throw new NumberFormatException();
                duelRounds.put(player.getUniqueId(), rounds);
                tell(player, "&aRound count set to &f" + rounds + "&a.");
            } catch (NumberFormatException ex) { tell(player, "&cEnter a whole number from 1 to 99."); }
            openDuelSettings(player);
        });
    }

    @EventHandler public void onDuelInventoryDrag(InventoryDragEvent e) {
        if (isLobbyItem(e.getOldCursor())) { e.setCancelled(true); return; }
        String title = ChatColor.stripColor(e.getView().getTitle());
        if (title == null) return;
        if (title.contains("Select a Kit") || title.contains("Kit Editor") || title.contains("Duel Settings")) {
            e.setCancelled(true);
            return;
        }
        if (title.startsWith("Editing:")) {
            // Dragging can cause cursor items to escape into the player's inventory on close.
            // Use ordinary clicks inside editable slots only; never allow drag operations.
            e.setCancelled(true);
        }
    }
    @EventHandler public void preventKitEditorItemsEscaping(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        String title = ChatColor.stripColor(e.getView().getTitle());
        if (title == null || !title.startsWith("Editing:")) return;
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType() == Material.AIR) return;
        // Put any cursor item back into an editable kit slot, never into player storage.
        Inventory editor = e.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack existing = editor.getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                editor.setItem(slot, cursor.clone());
                player.setItemOnCursor(new ItemStack(Material.AIR));
                return;
            }
        }
        player.setItemOnCursor(new ItemStack(Material.AIR));
        player.getWorld().dropItemNaturally(player.getLocation(), cursor.clone());
    }
    @EventHandler public void onDuelQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        duelQueue.remove(id);
        UUID other = activeDuels.get(id);
        if (other != null) {
            List<String> loggedLosses = getConfig().getStringList("combat.logged-losses");
            String uuid = id.toString();
            if (!loggedLosses.contains(uuid)) loggedLosses.add(uuid);
            getConfig().set("combat.logged-losses", loggedLosses);
            saveConfig();
            arenaCombat.remove(id);
            Player opponent = Bukkit.getPlayer(other);
            if (opponent != null && opponent.isOnline()) finishDuel(opponent, e.getPlayer());
        }
        arenaCombat.remove(id);
        combatUntil.remove(id);
    }
    @EventHandler public void onDuelJoin(PlayerJoinEvent e) { giveDuelItem(e.getPlayer()); giveKitEditor(e.getPlayer()); giveSettingsItem(e.getPlayer()); }
    @EventHandler public void onDuelRespawn(PlayerRespawnEvent e) { Player p=e.getPlayer(); Bukkit.getScheduler().runTask(this,()->{if(pendingDuelLosers.contains(p.getUniqueId()))p.setGameMode(GameMode.SPECTATOR);else {giveDuelItem(p);giveKitEditor(p);giveSettingsItem(p);}}); }
    @EventHandler public void onDuelDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isLobbyItem);
        Player loser = e.getEntity();
        UUID winnerId = activeDuels.get(loser.getUniqueId());
        if (winnerId == null) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
        Player winner = Bukkit.getPlayer(winnerId);
        pendingDuelLosers.add(loser.getUniqueId());
        Bukkit.getScheduler().runTask(this, () -> handleDuelRoundWin(winner, loser));
    }

    private void handleDuelRoundWin(Player winner, Player loser) {
        if (winner == null || loser == null || !winner.isOnline() || !loser.isOnline()) return;
        UUID winnerId = winner.getUniqueId();
        UUID loserId = loser.getUniqueId();
        if (!activeDuels.containsKey(winnerId) || !activeDuels.containsKey(loserId)) return;

        int winnerScore = duelRoundWins.merge(winnerId, 1, Integer::sum);
        int loserScore = duelRoundWins.getOrDefault(loserId, 0);
        awardKill(winner, loser); // every duel round counts as a kill for the winner and a death for the loser
        int rounds = Math.max(1, duelRounds.getOrDefault(winnerId, duelRounds.getOrDefault(loserId, 1)));
        int winsNeeded = rounds / 2 + 1;
        if (playerSetting(winner, "round_messages", true)) tell(winner, "&aRound won! Score: &f" + winnerScore + "-" + loserScore);
        if (playerSetting(loser, "round_messages", true)) tell(loser, "&cRound lost. Score: &f" + loserScore + "-" + winnerScore);
        if (playerSetting(winner, "kill_effects", true)) winner.spawnParticle(org.bukkit.Particle.CRIT, loser.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.2);
        updateAzurTabAndScoreboard(winner);
        updateAzurTabAndScoreboard(loser);

        if (winnerScore >= winsNeeded) {
            Bukkit.getScheduler().runTask(this, () -> finishDuel(winner, loser));
            return;
        }

        pendingDuelLosers.add(loserId);
        loser.setGameMode(GameMode.SPECTATOR);
        if (playerSetting(winner, "duel_titles", true)) winner.sendTitle(c("&a&lROUND WON"), c("&7Next round..."), 0,  40, 10);
        if (playerSetting(loser, "duel_titles", true)) loser.sendTitle(c("&c&lROUND LOST"), c("&7Next round..."), 0, 40, 10);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!winner.isOnline() || !loser.isOnline() || !activeDuels.containsKey(winnerId) || !activeDuels.containsKey(loserId)) return;
            String arenaId = duelArenaByPlayer.get(winnerId);
            Location spawn1 = arenaId == null ? null : arenaSpawn(arenaId, 1);
            Location spawn2 = arenaId == null ? null : arenaSpawn(arenaId, 2);
            if (spawn1 != null && spawn2 != null) {
                winner.teleport(spawn1);
                loser.teleport(spawn2);
            }
            for (Player participant : List.of(winner, loser)) {
                participant.setGameMode(GameMode.SURVIVAL);
                participant.getInventory().clear();
                participant.getInventory().setArmorContents(new ItemStack[4]);
                participant.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                giveDuelKit(participant, duelChosenKits.getOrDefault(winnerId, "sword"));
                participant.setHealth(participant.getMaxHealth());
                participant.setFoodLevel(20);
                participant.setSaturation(0.0f);
                participant.setFireTicks(0);
                participant.setFallDistance(0);
                pendingDuelLosers.remove(participant.getUniqueId());
                updateAzurTabAndScoreboard(participant);
            }
            winner.sendTitle(c("&a&lFIGHT!"), c("&7Next round"), 0, 30, 10);
            loser.sendTitle(c("&a&lFIGHT!"), c("&7Next round"), 0, 30, 10);
        }, 40L);
    }

    @Override public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if(args.length==1 && cmd.getName().equalsIgnoreCase("duel")) {
            List<String> out=new ArrayList<>();
            ConfigurationSection ks=getConfig().getConfigurationSection("kits");
            if(ks!=null) out.addAll(ks.getKeys(false));
            Bukkit.getOnlinePlayers().forEach(pl->out.add(pl.getName()));
            return out.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if(args.length==1 && cmd.getName().equalsIgnoreCase("leaderboard")) return boards().stream().map(Board::id).filter(v->v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==1 && Set.of("duel","accept","msg","reply","ignore","heal","feed","freeze","stats").contains(cmd.getName().toLowerCase(Locale.ROOT)))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n->n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(cmd.getName().equalsIgnoreCase("tp")) {
            if (args.length == 1) return List.of("world").stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("world")) return Bukkit.getWorlds().stream().map(World::getName).filter(v -> v.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if(cmd.getName().equalsIgnoreCase("world")) {
            if (args.length == 1) return List.of("create", "delete").stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("delete")) return Bukkit.getWorlds().stream().map(World::getName).filter(v -> v.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if(cmd.getName().equalsIgnoreCase("kit") && args.length==1){ List<String> choices=new ArrayList<>(List.of("create","delete","upload")); ConfigurationSection ks=getConfig().getConfigurationSection("kits"); if(ks!=null)choices.addAll(ks.getKeys(false)); return choices.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList(); }
        if(cmd.getName().equalsIgnoreCase("kit") && args.length==2 && Set.of("delete","upload").contains(args[0].toLowerCase(Locale.ROOT))){ ConfigurationSection ks=getConfig().getConfigurationSection("kits"); return ks==null?Collections.emptyList():ks.getKeys(false).stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList(); }
        return Collections.emptyList();
    }
}
