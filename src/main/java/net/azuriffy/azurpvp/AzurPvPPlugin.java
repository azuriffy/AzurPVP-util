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
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
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
import org.bukkit.boss.*;
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
    private final Map<UUID, UUID> duelRequests = new ConcurrentHashMap<>();
    private final Map<UUID, String> duelChosenKits = new ConcurrentHashMap<>();
    private final Map<UUID, String> duelTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> duelRounds = new ConcurrentHashMap<>();
    private final Map<UUID, String> duelMaps = new ConcurrentHashMap<>();
    private final Map<UUID, String> duelGuiMode = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> activeDuels = new ConcurrentHashMap<>();
    private final Set<UUID> duelCountdown = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingDuelLosers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> duelArenaByPlayer = new ConcurrentHashMap<>();
    private static final String DUEL_ITEM_NAME = "§d§lDuel";
    private final Map<UUID, UUID> lastMessaged = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignored = new ConcurrentHashMap<>();
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Set<UUID> god = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> kitCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> combatBars = new ConcurrentHashMap<>();
    private BukkitTask combatTask;
    private YamlConfiguration roles;
    private File rolesFile;
    private final Map<UUID, Long> lastPlaytimeSave = new ConcurrentHashMap<>();
    private final Map<UUID, Long> itemCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pearlCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> gappleCooldowns = new ConcurrentHashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        ensureDefaultKits();
        loadRoles();
        try { openDatabase(); } catch (SQLException e) { getLogger().severe("SQLite startup failed: " + e.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }
        for (String name : List.of("azurpvp", "spawn", "setspawn", "stats", "kit", "duel", "accept", "msg", "reply", "ignore", "rules", "discord", "ping", "tps", "fly", "heal", "feed", "vanish", "freeze", "report", "staffchat", "role", "back", "top", "tphere", "tpa", "home", "sethome", "warp", "setwarp", "unwarp", "party", "clan", "friends", "achievements", "quests", "settings", "arena", "spectate", "bounty", "killstreak", "playtime", "whois", "maintenance", "staffmode", "invsee", "ec", "announce", "mute", "unmute", "ban", "tempban", "unban", "kick", "warn", "history", "alerts", "cps", "clear", "repair", "gamemode", "speed", "backup", "world", "tp", "addarena", "set")) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(this); c.setTabCompleter(this); }
        }
        getServer().getPluginManager().registerEvents(this, this);
        combatTask = Bukkit.getScheduler().runTaskTimer(this, this::updateCombat, 1L, 10L);
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
        saveRoles();
        combatBars.values().forEach(BossBar::removeAll);
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
        p.setPlayerListHeaderFooter(
            c("\n&5&lAZUR&d&lPVP\n&7Minecraft 1.21.11+\n"),
            c("\n&7Players: &d" + Bukkit.getOnlinePlayers().size() + "\n"
                + "&7Ping: &a" + p.getPing() + "ms\n"
                + "&d/discord &8| &d/store &8| &d/website\n")
        );
        Scoreboard board = p.getScoreboard();
        if (board == Bukkit.getScoreboardManager().getMainScoreboard()) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(board);
            updateTabRole(p);
            board = p.getScoreboard();
        }
        org.bukkit.scoreboard.Objective objective = board.getObjective("azurpvp");
        if (objective == null) objective = board.registerNewObjective("azurpvp", "dummy", c("&5&lAZUR&d&lPVP"));
        objective.setDisplayName(c("&5&lAZUR&d&lPVP"));
        objective.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
        String role = roleOf(p.getUniqueId());
        String[] lines = {
            "&7Online: &d" + Bukkit.getOnlinePlayers().size(),
            "&dPlayer: &f" + p.getName(),
            "&7Ping: &a" + p.getPing() + "ms",
            "&dYour role: " + rolePrefix(role),
            "&r ",
            "&d⚔ or /duel to duel"
        };
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
        Set<String> restricted = Set.of("world","setspawn","maintenance","staffmode","invsee","ec","announce","mute","unmute","ban","tempban","timeout","unban","kick","warn","history","alerts","clear","repair","gamemode","speed","backup","tphere","whois","heal","feed","vanish","freeze","staffchat","role","arena","addarena","set");
        if (isDev(p)) return;
        e.getCommands().removeIf(command -> restricted.contains(command.toLowerCase(Locale.ROOT).split(":")[0]) && !can(p, commandCategory(command)));
    }
    private String commandCategory(String command) {
        return switch(command.toLowerCase(Locale.ROOT).split(":")[0]) {
            case "setspawn","maintenance","backup","role","arena","addarena","set","world","tp" -> "owner";
            case "ban","tempban","timeout","unban","kick","mute","unmute","warn","history","alerts","clear","repair","gamemode","speed","heal","feed" -> "moderation";
            case "staffmode","invsee","ec","announce","whois","vanish","freeze","staffchat" -> "staff";
            case "tphere" -> "teleport";
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
        File file = new File(getDataFolder(), getConfig().getString("database.file", "players.db"));
        db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS stats (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, kills INTEGER DEFAULT 0, deaths INTEGER DEFAULT 0, streak INTEGER DEFAULT 0, best_streak INTEGER DEFAULT 0, playtime_seconds INTEGER DEFAULT 0)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY AUTOINCREMENT, reporter TEXT, target TEXT, reason TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
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
    private int getStat(UUID uuid, String field) {
        if (!Set.of("kills","deaths","streak","best_streak","playtime_seconds").contains(field)) return 0;
        try (PreparedStatement ps = db.prepareStatement("SELECT " + field + " FROM stats WHERE uuid=?")) {
            ps.setString(1, uuid.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { return 0; }
    }
    private void tag(Player p) {
        if (!getConfig().getBoolean("combat.enabled", true)) return;
        combatUntil.put(p.getUniqueId(), System.currentTimeMillis() + getConfig().getLong("combat.tag-seconds",15)*1000L);
        if (getConfig().getBoolean("combat.bossbar", true)) {
            BossBar bar = combatBars.computeIfAbsent(p.getUniqueId(), id -> Bukkit.createBossBar(c("&5&lCOMBAT &8» &dDo not log out"), BarColor.PURPLE, BarStyle.SOLID));
            if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
            bar.setVisible(true); bar.setProgress(1.0);
        }
        p.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(c("&5&lCOMBAT &8» &d" + getConfig().getLong("combat.tag-seconds",15) + "s")));
    }
    private boolean tagged(Player p) { return combatUntil.getOrDefault(p.getUniqueId(),0L) > System.currentTimeMillis(); }
    private void updateCombat() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(combatUntil.keySet())) {
            Player p = Bukkit.getPlayer(id); long until = combatUntil.getOrDefault(id,0L);
            if (until <= now || p == null || !p.isOnline()) {
                combatUntil.remove(id); BossBar b = combatBars.remove(id); if (b != null) b.removeAll();
            } else if (p != null) {
                double remain = Math.max(0, Math.min(1, (until-now)/(double)(getConfig().getLong("combat.tag-seconds",15)*1000L)));
                BossBar b = combatBars.get(id); if (b != null) b.setProgress(remain);
                p.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(c("&5&lCOMBAT &8» &d" + ((until-now+999)/1000) + "s")));
            }
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer(); ensurePlayer(p); if(!roleExists(roleOf(p.getUniqueId()))) assignRole(p.getUniqueId(),p.getName(),"member");
        updateTabRole(p);
        updateAzurTabAndScoreboard(p);
        if (getConfig().getBoolean("maintenance.enabled", false) && !isStaff(p)) { Bukkit.getScheduler().runTask(this, () -> p.kickPlayer(c("&5AzurPvP &8» &cServer maintenance is enabled."))); return; }
        e.setJoinMessage(c("&8[&a+&8] &d" + p.getName()));
        p.sendTitle(c("&5&lAZUR&d&lPVP"), c("&7Welcome to the arena"), 10, 45, 15);
        giveDuelItem(p); giveKitEditor(p);
        p.sendMessage(prefix()+c("&7Use &d/azurpvp &7for plugin commands."));
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p=e.getPlayer(); e.setQuitMessage(c("&8[&c-&8] &d"+p.getName()));
        if (tagged(p) && getConfig().getBoolean("combat.punish-logout",true) && !p.hasPermission("azurpvp.bypass.combattag")) {
            if ("KILL".equalsIgnoreCase(getConfig().getString("combat.punishment","KILL"))) p.setHealth(0.0);
            else for (ItemStack item : p.getInventory().getContents()) if (item != null) p.getWorld().dropItemNaturally(p.getLocation(),item);
        }
        BossBar b=combatBars.remove(p.getUniqueId()); if(b!=null)b.removeAll();
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
    @EventHandler public void protectSpawnBlockBreak(BlockBreakEvent e) {
        if(inSpawnProtection(e.getBlock().getLocation())) { e.setCancelled(true); tell(e.getPlayer(),"&cSpawn protection: building is disabled here."); return; }
        for(String key: arenaKeys()) { String path="arenas."+key; Location center=arenaCenter(key); if(center!=null && center.getWorld().equals(e.getBlock().getWorld()) && center.distanceSquared(e.getBlock().getLocation())<=10000.0) { boolean allowed=getConfig().getBoolean(path+".break-below-zero",false); if(!allowed || e.getBlock().getY()>=0) e.setCancelled(true); return; } }
    }
    @EventHandler public void protectSpawnBlockPlace(BlockPlaceEvent e) { if(inSpawnProtection(e.getBlock().getLocation())) { e.setCancelled(true); tell(e.getPlayer(),"&cSpawn protection: building is disabled here."); } }
    @EventHandler public void protectSpawnInteractions(PlayerInteractEvent e) {
        if (e.getClickedBlock()==null || !inSpawnProtection(e.getClickedBlock().getLocation())) return;
        Material type=e.getClickedBlock().getType();
        if (type==Material.ENDER_CHEST || type==Material.CHEST || type==Material.TRAPPED_CHEST || type==Material.BARREL || type.name().endsWith("SHULKER_BOX")) {
            if (!type.name().endsWith("SHULKER_BOX")) { e.setCancelled(true); tell(e.getPlayer(),"&cContainer access is disabled in spawn protection."); }
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
    @EventHandler public void onDeath(PlayerDeathEvent e) {
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
        if (root.equals("gmsp")) { e.setCancelled(true); if (!can(p, "moderation")) { tell(p, "&cAdmin or Owner required."); return; } p.setGameMode(GameMode.SPECTATOR); tell(p, "&dSpectator mode enabled silently."); return; }
        if (root.equals("gmc") || root.equals("gms") || root.equals("gamemode")) {
            e.setCancelled(true);
            if (!isDev(p)) { tell(p, "&cOnly the Owner can change gamemodes. Use /gmsp if you are Admin or above."); return; }
            if (root.equals("gmc")) p.setGameMode(GameMode.CREATIVE);
            else if (root.equals("gms")) p.setGameMode(GameMode.SURVIVAL);
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
            tell(p, "&dGamemode updated."); return;
        }
        if ((root.equals("ban") || root.equals("tempban") || root.equals("timeout") || root.equals("unban")) && !can(p, "moderation")) { e.setCancelled(true); tell(p, "&cAdmin or Owner required."); return; }
        if (tagged(p) && getConfig().getBoolean("combat.prevent-commands", true) &&
            getConfig().getStringList("combat.blocked-commands").stream().map(v -> v.toLowerCase(Locale.ROOT)).anyMatch(root::equals)) {
            e.setCancelled(true); tell(p, "&cYou cannot use that command while combat-tagged.");
        }
    }

    private void replyToLast(Player p, String message) {
        UUID id = lastMessaged.get(p.getUniqueId());
        Player to = id == null ? null : Bukkit.getPlayer(id);
        if (to == null) { tell(p, "&cNobody to reply to."); return; }
        tell(p, "&dTo " + to.getName() + ": &f" + message);
        tell(to, "&dFrom " + p.getName() + ": &f" + message);
        lastMessaged.put(to.getUniqueId(), p.getUniqueId());
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        String worldName=getConfig().getString("spawn.world","");
        World w=Bukkit.getWorld(worldName);
        if(w!=null)e.setRespawnLocation(new Location(w,getConfig().getDouble("spawn.x",0.5),getConfig().getDouble("spawn.y",80),getConfig().getDouble("spawn.z",0.5), (float)getConfig().getDouble("spawn.yaw",0),(float)getConfig().getDouble("spawn.pitch",0)));
    }

    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n=cmd.getName().toLowerCase(Locale.ROOT);
        String category = commandCategory(n);
        if (!can(s, category)) { tell(s, "&cYou don't have permission to use that command."); return true; }
        if(n.equals("role")) { roleCommand(s,a); return true; }
        if(n.equals("help")) {
            tell(s, "&d&lAzurPvP Commands &8— &7private help");
            tell(s, "&7Players: &f/spawn, /stats, /kit, /duel, /accept, /msg, /reply, /ignore, /rules, /discord, /ping, /tps, /top, /back, /tpa, /home, /sethome, /warp, /playtime, /report");
            if (can(s,"staff")) tell(s, "&aStaff: &f/fly, /vanish, /freeze, /staffchat, /whois, /announce");
            if (can(s,"moderation")) tell(s, "&cModeration: &f/ban, /tempban, /unban, /kick, /mute, /unmute, /warn, /history, /heal, /feed, /gamemode, /speed, /clear, /repair");
            if (isDev(s)) tell(s, "&dOwner: &f/role, /setspawn, /maintenance, /backup, /arena");
            return true;
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
        if(n.equals("setspawn")) {
            if(!(s instanceof Player p)) {tell(s,"&cPlayers only.");return true;} if(!isDev(p)){tell(s,"&cNo permission.");return true;}
            Location l=p.getLocation(); getConfig().set("spawn.world",l.getWorld().getName()); getConfig().set("spawn.x",l.getX()); getConfig().set("spawn.y",l.getY()); getConfig().set("spawn.z",l.getZ()); getConfig().set("spawn.yaw",l.getYaw()); getConfig().set("spawn.pitch",l.getPitch()); saveConfig(); tell(p,"&aSpawn saved. &7A 500-block radius is now protected from PvP, block changes, and container access."); return true;
        }
        if(n.equals("spawn")) {
            if(!(s instanceof Player p))return true; if(tagged(p)){tell(p,"&cYou cannot teleport while combat-tagged.");return true;}
            String wn=getConfig().getString("spawn.world",""); World w=Bukkit.getWorld(wn); if(w==null){tell(p,"&cSpawn is not configured. Ask an admin to use /setspawn.");return true;}
            p.teleport(new Location(w,getConfig().getDouble("spawn.x"),getConfig().getDouble("spawn.y"),getConfig().getDouble("spawn.z"),(float)getConfig().getDouble("spawn.yaw"),(float)getConfig().getDouble("spawn.pitch")));tell(p,"&dTeleported to spawn.");return true;
        }
        if(n.equals("stats")) {
            if(a.length==0 && !(s instanceof Player)){tell(s,"Use /stats <player>");return true;}
            OfflinePlayer target=a.length>0?Bukkit.getOfflinePlayer(a[0]):(Player)s;
            int k=getStat(target.getUniqueId(),"kills"), d=getStat(target.getUniqueId(),"deaths");
            tell(s,"&dStats for &f"+(target.getName()==null?"Unknown":target.getName()));
            tell(s,"&7Kills: &d"+k+" &8| &7Deaths: &d"+d+" &8| &7KDR: &d"+String.format(Locale.US,"%.2f",d==0?k:(double)k/d));
            tell(s,"&7Streak: &d"+getStat(target.getUniqueId(),"streak")+" &8| &7Best: &d"+getStat(target.getUniqueId(),"best_streak")); return true;
        }
        if(n.equals("kit")) {
            if(!(s instanceof Player p)) { tell(s, "&cPlayers only."); return true; }
            if(a.length == 1 && a[0].equalsIgnoreCase("clear")) {
                boolean hasItems = false;
                for (ItemStack item : p.getInventory().getContents()) if (item != null && item.getType() != Material.AIR) { hasItems = true; break; }
                if (hasItems || p.getInventory().getHelmet() != null || p.getInventory().getChestplate() != null || p.getInventory().getLeggings() != null || p.getInventory().getBoots() != null || p.getInventory().getItemInOffHand().getType() != Material.AIR) {
                    p.getInventory().clear();
                    p.getInventory().setArmorContents(null);
                    p.getInventory().setItemInOffHand(null);
                    tell(p, "&aInventory cleared. Run &f/kit clear &aone more time to restore the Duel sword and Kit editor.");
                } else {
                    giveDuelItem(p);
                    giveKitEditor(p);
                    tell(p, "&dDuel sword and Kit editor restored.");
                }
                return true;
            }
            if(a.length >= 2 && a[0].equalsIgnoreCase("create")) {
                if(!isDev(p)) { tell(p,"&cOnly the owner can create global kits."); return true; }
                String kitName=normalizeKitName(String.join("_",Arrays.copyOfRange(a,1,a.length)));
                if(kitName.isBlank()) { tell(p,"&cUse /kit create <name>"); return true; }
                if(getConfig().contains("kits."+kitName)) { tell(p,"&cThat kit already exists. Use /kit upload "+kitName+" to replace its contents."); return true; }
                getConfig().set("kits."+kitName+".contents", new ItemStack[41]);
                getConfig().set("kits."+kitName+".cooldown-seconds", 0); saveConfig();
                tell(p,"&aCreated kit &f"+kitName+"&a. Hold the inventory you want and use &f/kit upload "+kitName+"&a."); return true;
            }
            if(a.length >= 2 && a[0].equalsIgnoreCase("delete")) {
                if(!isDev(p)) { tell(p,"&cOnly the owner can delete global kits."); return true; }
                String kitName=normalizeKitName(a[1]);
                if(!getConfig().contains("kits."+kitName)) { tell(p,"&cThat kit does not exist."); return true; }
                getConfig().set("kits."+kitName,null); saveConfig(); tell(p,"&aDeleted kit &f"+kitName+"&a."); return true;
            }
            if(a.length >= 2 && a[0].equalsIgnoreCase("upload")) {
                if(!isDev(p)) { tell(p,"&cOnly the owner can upload global kits."); return true; }
                String kitName=normalizeKitName(a[1]);
                if(kitName.isBlank()) { tell(p,"&cUse /kit upload <name>"); return true; }
                if(!getConfig().contains("kits."+kitName)) getConfig().set("kits."+kitName+".cooldown-seconds", 0);
                ItemStack[] uploaded=p.getInventory().getContents();
                for(int i=0;i<uploaded.length;i++) if(isDuelItem(uploaded[i]) || isKitEditor(uploaded[i])) uploaded[i]=null;
                getConfig().set("kits."+kitName+".contents", uploaded);
                getConfig().set("kits."+kitName+".armor", p.getInventory().getArmorContents());
                getConfig().set("kits."+kitName+".offhand", p.getInventory().getItemInOffHand());
                saveConfig(); reloadConfig(); tell(p,"&aUploaded and saved server-default kit &f"+kitName+"&a to config.yml. It will survive plugin updates that keep the server config."); return true;
            }
            if(a.length==1 && a[0].equalsIgnoreCase("editor")) { openKitMenu(p,"editor",null); return true; }
            if(a.length==0) { openKitMenu(p, "claim", null); return true; }
            String kit=normalizeKitName(a[0]); ConfigurationSection sec=getConfig().getConfigurationSection("kits."+kit);
            if(sec==null){tell(p,"&cUnknown kit. Use /kit to view available kits.");return true;}
            long cd=sec.getLong("cooldown-seconds",30)*1000L, now=System.currentTimeMillis(), next=kitCooldowns.getOrDefault(p.getUniqueId(),0L);
            if(next>now){tell(p,"&cWait "+((next-now+999)/1000)+"s before using a kit again.");return true;}
            String personal="player-kits."+p.getUniqueId()+"."+kit;
            List<?> items=getConfig().getList(personal+".contents"); if(items==null) items=sec.getList("contents");
            p.getInventory().clear(); if(items!=null) for(int i=0;i<Math.min(items.size(),p.getInventory().getSize());i++) if(items.get(i) instanceof ItemStack item) p.getInventory().setItem(i,item.clone());
            List<?> armor=getConfig().getList(personal+".armor"); if(armor==null) armor=sec.getList("armor");
            if(armor!=null) { ItemStack[] arr=new ItemStack[4]; for(int i=0;i<Math.min(4,armor.size());i++) if(armor.get(i) instanceof ItemStack it) arr[i]=it.clone(); p.getInventory().setArmorContents(arr); }
            ItemStack off=getConfig().getItemStack(personal+".offhand"); if(off==null) off=sec.getItemStack("offhand"); if(off!=null) p.getInventory().setItemInOffHand(off.clone());
            kitCooldowns.put(p.getUniqueId(),now+cd);tell(p,"&dKit claimed: &f"+kit); giveDuelItem(p); giveKitEditor(p); return true;
        }
        if(n.equals("duel")) {
            if(!(s instanceof Player p)||a.length<1){tell(s,"Use /duel <player>");return true;} Player target=Bukkit.getPlayerExact(a[0]);
            if(target==null||target.equals(p)){tell(p,"&cPlayer not found or invalid.");return true;}
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
            tell(s,"&dTo "+to.getName()+": &f"+msg);tell(to,"&dFrom "+s.getName()+": &f"+msg);if(s instanceof Player from)lastMessaged.put(to.getUniqueId(),from.getUniqueId());if(s instanceof Player from)lastMessaged.put(from.getUniqueId(),to.getUniqueId());return true;
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
        if(n.equals("tphere")) { if(!isStaff(s)){tell(s,"&cStaff role required.");return true;} if(!(s instanceof Player p)||a.length<1)return true;Player t=Bukkit.getPlayerExact(a[0]);if(t==null){tell(s,"&cPlayer not found.");return true;}t.teleport(p);tell(s,"&dTeleported "+t.getName()+" to you.");return true; }
        if(n.equals("gamemode")) {
            if(!isDev(s)){tell(s,"&cOnly Owner can use /gamemode. Admins may use /gmsp.");return true;}
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
        if(n.equals("settings")) { if(!(s instanceof Player p))return true;tell(p,"&dUse config.yml to configure personal/server feature toggles in this build.");return true; }
        if(n.equals("playtime")) { if(!(s instanceof Player p))return true;long seconds=getStat(p.getUniqueId(),"playtime_seconds");tell(p,"&dPlaytime: &f"+(seconds/3600)+"h "+((seconds%3600)/60)+"m");return true; }
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
    private List<String> arenaKeys() { ConfigurationSection sec=getConfig().getConfigurationSection("arenas"); if(sec==null)return Collections.emptyList(); return new ArrayList<>(sec.getKeys(false)); }
    private Location arenaCenter(String id) { String path="arenas."+id; World w=Bukkit.getWorld(getConfig().getString(path+".world","")); if(w==null)return null; return new Location(w,getConfig().getDouble(path+".x"),getConfig().getDouble(path+".y"),getConfig().getDouble(path+".z")); }
    private Location arenaSpawn(String id,int n) { String path="arenas."+id+".spawn"+n; if(!getConfig().contains(path+".world"))return null; World w=Bukkit.getWorld(getConfig().getString(path+".world","")); if(w==null)return null; return new Location(w,getConfig().getDouble(path+".x"),getConfig().getDouble(path+".y"),getConfig().getDouble(path+".z"),(float)getConfig().getDouble(path+".yaw"),(float)getConfig().getDouble(path+".pitch")); }
    private void startDuel(Player first,Player second) {
        String arena=null; for(String id:arenaKeys()) if(arenaSpawn(id,1)!=null&&arenaSpawn(id,2)!=null) { arena=id; break; }
        if(arena==null) { tell(first,"&cNo usable arena: every arena needs spawn 1 and spawn 2. Configure another arena first.");tell(second,"&cDuel cancelled: no arena has both spawns configured.");return; }
        if(activeDuels.containsKey(first.getUniqueId())||activeDuels.containsKey(second.getUniqueId())) { tell(first,"&cOne of you is already in a duel.");tell(second,"&cOne of you is already in a duel.");return; }
        activeDuels.put(first.getUniqueId(),second.getUniqueId());activeDuels.put(second.getUniqueId(),first.getUniqueId()); duelArenaByPlayer.put(first.getUniqueId(),Integer.parseInt(arena));duelArenaByPlayer.put(second.getUniqueId(),Integer.parseInt(arena)); duelCountdown.add(first.getUniqueId());duelCountdown.add(second.getUniqueId());
        first.setGameMode(GameMode.SURVIVAL);second.setGameMode(GameMode.SURVIVAL);first.setHealth(first.getMaxHealth());second.setHealth(second.getMaxHealth());first.setFoodLevel(20);second.setFoodLevel(20);
        giveDuelKit(first,duelChosenKits.getOrDefault(first.getUniqueId(),"sword"));giveDuelKit(second,duelChosenKits.getOrDefault(first.getUniqueId(),"sword"));first.teleport(arenaSpawn(arena,1));second.teleport(arenaSpawn(arena,2));
        tell(first,"&dDuel starting in 5 seconds!");tell(second,"&dDuel starting in 5 seconds!");
        for(int sec=4;sec>=1;sec--){final int n=sec;Bukkit.getScheduler().runTaskLater(this,()->{if(activeDuels.containsKey(first.getUniqueId())&&activeDuels.containsKey(second.getUniqueId())){first.sendTitle(c("&5&l"+n),c("&7Get ready"),0,20,0);second.sendTitle(c("&5&l"+n),c("&7Get ready"),0,20,0);}},(5-sec)*20L);}
        Bukkit.getScheduler().runTaskLater(this,()->{if(activeDuels.containsKey(first.getUniqueId())&&activeDuels.containsKey(second.getUniqueId())){duelCountdown.remove(first.getUniqueId());duelCountdown.remove(second.getUniqueId());first.sendTitle(c("&a&lFIGHT!"),c("&7Good luck"),0,30,10);second.sendTitle(c("&a&lFIGHT!"),c("&7Good luck"),0,30,10);}},100L);
        tell(first,"&aDuel accepted: &f"+second.getName()+" &7| Arena "+arena+" | 1 round");tell(second,"&aDuel accepted: &f"+first.getName()+" &7| Arena "+arena+" | 1 round");
    }
    private void giveDuelKit(Player p,String kit) {
        String path="player-kits."+p.getUniqueId()+"."+kit; if(!getConfig().contains("kits."+kit)){kit="sword";path="kits.sword";} else if(getConfig().contains(path+".contents")){} else path="kits."+kit;
        List<?> contents=getConfig().getList(path+".contents");p.getInventory().clear();if(contents!=null)for(int i=0;i<Math.min(36,contents.size());i++)if(contents.get(i) instanceof ItemStack it)p.getInventory().setItem(i,it.clone());
        List<?> armor=getConfig().getList(path+".armor");if(armor!=null){ItemStack[] arr=new ItemStack[4];for(int i=0;i<Math.min(4,armor.size());i++)if(armor.get(i) instanceof ItemStack it)arr[i]=it.clone();p.getInventory().setArmorContents(arr);} ItemStack off=getConfig().getItemStack(path+".offhand");if(off!=null)p.getInventory().setItemInOffHand(off.clone());
    }
    private void finishDuel(Player winner,Player loser) {
        if(winner==null||loser==null)return; activeDuels.remove(winner.getUniqueId());activeDuels.remove(loser.getUniqueId());duelCountdown.remove(winner.getUniqueId());duelCountdown.remove(loser.getUniqueId());duelArenaByPlayer.remove(winner.getUniqueId());duelArenaByPlayer.remove(loser.getUniqueId());
        winner.sendTitle(c("&a&lVICTORY"),c("&7You won the duel!"),0,100,0);loser.sendTitle(c("&c&lDEFEAT"),c("&7Returning to spawn soon..."),0,100,0);pendingDuelLosers.add(loser.getUniqueId());if(loser.isOnline()&&!loser.isDead())loser.setGameMode(GameMode.SPECTATOR);
        Bukkit.getScheduler().runTaskLater(this,()->{String sw=getConfig().getString("spawn.world","");World world=Bukkit.getWorld(sw);if(world!=null){Location l=new Location(world,getConfig().getDouble("spawn.x"),getConfig().getDouble("spawn.y"),getConfig().getDouble("spawn.z"),(float)getConfig().getDouble("spawn.yaw"),(float)getConfig().getDouble("spawn.pitch"));loser.setGameMode(GameMode.SURVIVAL);loser.teleport(l);} if(winner.isOnline()) {winner.setGameMode(GameMode.SURVIVAL);String sw2=getConfig().getString("spawn.world","");World w2=Bukkit.getWorld(sw2);if(w2!=null)winner.teleport(new Location(w2,getConfig().getDouble("spawn.x"),getConfig().getDouble("spawn.y"),getConfig().getDouble("spawn.z")));} if(loser.isOnline()){pendingDuelLosers.remove(loser.getUniqueId());giveDuelItem(loser);giveKitEditor(loser);}if(winner.isOnline()){giveDuelItem(winner);giveKitEditor(winner);}},100L);
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
            if(iconMeta!=null){ iconMeta.setDisplayName(c("&d&l"+prettyKitName(key))); iconMeta.setLore(List.of(c(mode.equals("editor")?"&7Click to edit your personal version":"&7Click to select this kit"))); icon.setItemMeta(iconMeta); }
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
        if(contents!=null) for(int i=0;i<Math.min(45,contents.size());i++) if(contents.get(i) instanceof ItemStack it) inv.setItem(i,it.clone());
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
        ItemStack[] contents=new ItemStack[45]; for(int i=0;i<45;i++){ItemStack it=inv.getItem(i);contents[i]=it==null?null:it.clone();}
        ItemStack[] armor=new ItemStack[4]; for(int i=0;i<4;i++){ItemStack it=inv.getItem(45+i);armor[i]=it==null?null:it.clone();}
        ItemStack offhand=inv.getItem(49);
        getConfig().set("player-kits."+p.getUniqueId()+"."+kit+".contents",contents);
        getConfig().set("player-kits."+p.getUniqueId()+"."+kit+".armor",armor);
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
    }
    private boolean isKitEditor(ItemStack item) { return item!=null && item.getType()==Material.BOOK && item.hasItemMeta() && item.getItemMeta().hasDisplayName() && c("&d&lKit editor").equals(item.getItemMeta().getDisplayName()); }
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
        if (isDuelItem(e.getItem()) && (e.getAction()==Action.RIGHT_CLICK_AIR||e.getAction()==Action.RIGHT_CLICK_BLOCK||e.getAction()==Action.LEFT_CLICK_AIR||e.getAction()==Action.LEFT_CLICK_BLOCK)) { e.setCancelled(true); openKitMenu(e.getPlayer(),"queue",null); return; }
        if (isKitEditor(e.getItem()) && (e.getAction()==Action.RIGHT_CLICK_AIR||e.getAction()==Action.RIGHT_CLICK_BLOCK||e.getAction()==Action.LEFT_CLICK_AIR||e.getAction()==Action.LEFT_CLICK_BLOCK)) { e.setCancelled(true); openKitMenu(e.getPlayer(),"editor",null); }
    }
    @EventHandler public void onDuelItemDrop(PlayerDropItemEvent e) { if(isDuelItem(e.getItemDrop().getItemStack()) || isKitEditor(e.getItemDrop().getItemStack()))e.setCancelled(true); }
    private void openDuelSettings(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, c("&5&lAzurPvP &8» &dDuel Settings"));
        int rounds = 1; duelRounds.put(p.getUniqueId(),1);
        String map = duelMaps.getOrDefault(p.getUniqueId(), "random");
        String kit = duelChosenKits.getOrDefault(p.getUniqueId(), "sword");
        inv.setItem(10, namedItem(Material.PAPER, "&d&lRounds: &f" + rounds, List.of("&7Click to change rounds", "&71, 3, 5, or 7 rounds")));
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
        if(isDuelItem(e.getCurrentItem()) || isDuelItem(e.getCursor()) || isKitEditor(e.getCurrentItem()) || isKitEditor(e.getCursor())) { e.setCancelled(true); return; }
        String title=ChatColor.stripColor(e.getView().getTitle());
        if(title==null)return;
        if(title.startsWith("Editing:")) {
            if(e.getClickedInventory()!=e.getView().getTopInventory()) { e.setCancelled(true); return; }
            if(e.getSlot()==51) { e.setCancelled(true); saveEditedKit(p,e.getView().getTopInventory()); return; }
            if(e.getSlot()==53) { e.setCancelled(true); resetEditedKit(p); return; }
            if(e.getSlot()>=50) { e.setCancelled(true); return; }
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
            if(mode.equals("duel")) { duelChosenKits.put(p.getUniqueId(),kit); duelRounds.put(p.getUniqueId(),1); duelMaps.putIfAbsent(p.getUniqueId(),"random"); openDuelSettings(p); }
            else if(mode.equals("challenge")) { duelChosenKits.put(p.getUniqueId(),kit); duelRounds.put(p.getUniqueId(),1); duelMaps.putIfAbsent(p.getUniqueId(),"random"); openDuelSettings(p); }
            else { p.closeInventory(); tell(p,"&dQueued with kit &f"+kit+"&d. &7Matchmaking requires an arena/queue system to be configured."); }
        } else {
            if(e.getSlot()==10) { duelRounds.put(p.getUniqueId(),1); openDuelSettings(p); }
            else if(e.getSlot()==13) { List<String> maps=getConfig().getStringList("duel.maps"); if(maps.isEmpty())maps=List.of("random"); String current=duelMaps.getOrDefault(p.getUniqueId(),"random"); int i=maps.indexOf(current); duelMaps.put(p.getUniqueId(),maps.get((i+1)%maps.size())); openDuelSettings(p); }
            else if(e.getSlot()==16) { String targetName=duelTargets.get(p.getUniqueId()); Player target=targetName==null?null:Bukkit.getPlayerExact(targetName); if(target==null){p.closeInventory();tell(p,"&cYour opponent is no longer online.");return;} duelRequests.put(target.getUniqueId(),p.getUniqueId()); p.closeInventory(); tell(p,"&dDuel request sent to &f"+target.getName()+"&d. Kit: &f"+duelChosenKits.getOrDefault(p.getUniqueId(),"default")+"&d, rounds: &f"+duelRounds.getOrDefault(p.getUniqueId(),3)+"&d, map: &f"+duelMaps.getOrDefault(p.getUniqueId(),"random")); tell(target,"&d"+p.getName()+" challenged you! Use &f/accept "+p.getName()+"&d."); }
        }
    }
    @EventHandler public void onDuelInventoryDrag(InventoryDragEvent e) { String title=ChatColor.stripColor(e.getView().getTitle()); if(title!=null&&(title.contains("Select a Kit")||title.contains("Kit Editor")||title.contains("Duel Settings")||title.startsWith("Editing:"))) { if(title.startsWith("Editing:") && e.getRawSlots().stream().allMatch(slot -> slot < 50)) return; e.setCancelled(true); } }
    @EventHandler public void onDuelJoin(PlayerJoinEvent e) { giveDuelItem(e.getPlayer()); giveKitEditor(e.getPlayer()); }
    @EventHandler public void onDuelRespawn(PlayerRespawnEvent e) { Player p=e.getPlayer(); Bukkit.getScheduler().runTask(this,()->{if(pendingDuelLosers.contains(p.getUniqueId()))p.setGameMode(GameMode.SPECTATOR);else {giveDuelItem(p);giveKitEditor(p);}}); }
    @EventHandler public void onDuelDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isDuelItem);
        Player loser=e.getEntity(); UUID other=activeDuels.get(loser.getUniqueId()); if(other!=null){Player winner=Bukkit.getPlayer(other); Bukkit.getScheduler().runTask(this,()->finishDuel(winner,loser));}
    }

    @Override public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
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
