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
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.PlayerDropItemEvent;
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
        loadRoles();
        try { openDatabase(); } catch (SQLException e) { getLogger().severe("SQLite startup failed: " + e.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }
        for (String name : List.of("azurpvp", "spawn", "setspawn", "stats", "kit", "duel", "accept", "msg", "reply", "ignore", "rules", "discord", "ping", "tps", "fly", "heal", "feed", "vanish", "freeze", "report", "staffchat", "role", "back", "top", "tphere", "tpa", "home", "sethome", "warp", "setwarp", "unwarp", "party", "clan", "friends", "achievements", "quests", "settings", "arena", "spectate", "bounty", "killstreak", "playtime", "whois", "maintenance", "staffmode", "invsee", "ec", "announce", "mute", "unmute", "ban", "tempban", "unban", "kick", "warn", "history", "alerts", "cps", "clear", "repair", "gamemode", "speed", "backup", "world")) {
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
        p.setPlayerListHeaderFooter(c("\n&5&lAZUR&d&lPVP\n&7LONDON &8| &f1.21.11+\n"), "");

        Scoreboard board = p.getScoreboard();
        if (board == Bukkit.getScoreboardManager().getMainScoreboard()) { board = Bukkit.getScoreboardManager().getNewScoreboard(); p.setScoreboard(board); updateTabRole(p); board = p.getScoreboard(); }
        org.bukkit.scoreboard.Objective objective = board.getObjective("azurpvp");
        if (objective == null) objective = board.registerNewObjective("azurpvp", "dummy", c("&5&lAZUR&d&lPVP"));
        objective.setDisplayName(c("&5&lAZUR&d&lPVP"));
        objective.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
        // Hide vanilla sidebar score numbers on Paper versions that support NumberFormat,
        // using reflection so this source still compiles against older API artifacts.
        try {
            Class<?> numberFormatClass = Class.forName("org.bukkit.scoreboard.NumberFormat");
            Object blankFormat = numberFormatClass.getMethod("blank").invoke(null);
            org.bukkit.scoreboard.Objective.class.getMethod("setNumberFormat", numberFormatClass).invoke(objective, blankFormat);
        } catch (ReflectiveOperationException ignored) { }
        String role = roleOf(p.getUniqueId());
        String[] lines = {
            "&7Online: &d" + Bukkit.getOnlinePlayers().size(),
            "&dPlayer: &f" + p.getName(),
            "&r ",
            "&dServer: &fLondon",
            "&dYour role: " + rolePrefix(role),
            "&r  ",
            "&dDuel: &f/duel <player>"
        };
        for (String entry : new HashSet<>(board.getEntries())) objective.getScore(entry).resetScore();
        int score = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String unique = c(lines[i]) + ChatColor.values()[i].toString();
            objective.getScore(unique).setScore(score--);
        }
        p.setScoreboard(board);
    }

    private char colorCode(String role) {
        return switch(role) { case "owner" -> 'd'; case "admin" -> 'c'; case "mod" -> 'b'; case "helper" -> 'a'; case "creator" -> 'e'; case "azur+" -> '5'; default -> 'f'; };
    }
    @EventHandler public void hideRestrictedCommands(PlayerCommandSendEvent e) {
        Player p = e.getPlayer();
        Set<String> restricted = Set.of("world","setspawn","maintenance","staffmode","invsee","ec","announce","mute","unmute","ban","tempban","timeout","unban","kick","warn","history","alerts","clear","repair","gamemode","speed","backup","tphere","whois","heal","feed","vanish","freeze","staffchat","role","arena");
        if (isDev(p)) return;
        e.getCommands().removeIf(command -> restricted.contains(command.toLowerCase(Locale.ROOT).split(":")[0]) && !can(p, commandCategory(command)));
    }
    private String commandCategory(String command) {
        return switch(command.toLowerCase(Locale.ROOT).split(":")[0]) {
            case "setspawn","maintenance","backup","role","arena","world" -> "owner";
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
    @EventHandler public void onDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player victim && e.getDamager() instanceof Player attacker) {
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
                    tell(s, "&aCreated void world &f" + worldName + "&a. Use /world delete " + worldName + " to remove it.");
                } catch (Exception ex) {
                    getLogger().warning("Could not create void world '" + worldName + "': " + ex.getMessage());
                    tell(s, "&cCould not create world. Check the server console.");
                }
                return true;
            }
            if (action.equals("delete")) {
                World target = Bukkit.getWorld(worldName);
                if (target == null) { tell(s, "&cThat world is not currently loaded."); return true; }
                if (target.getName().equalsIgnoreCase("world") || target.getName().equalsIgnoreCase("world_nether") || target.getName().equalsIgnoreCase("world_the_end")) {
                    tell(s, "&cYou cannot delete the server's default world with this command."); return true;
                }
                World fallback = Bukkit.getWorld(getConfig().getString("spawn.world", ""));
                if (fallback == null || fallback.equals(target)) fallback = Bukkit.getWorlds().stream().filter(w -> !w.equals(target)).findFirst().orElse(null);
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
        if(n.equals("setspawn")) {
            if(!(s instanceof Player p)) {tell(s,"&cPlayers only.");return true;} if(!isDev(p)){tell(s,"&cNo permission.");return true;}
            Location l=p.getLocation(); getConfig().set("spawn.world",l.getWorld().getName()); getConfig().set("spawn.x",l.getX()); getConfig().set("spawn.y",l.getY()); getConfig().set("spawn.z",l.getZ()); getConfig().set("spawn.yaw",l.getYaw()); getConfig().set("spawn.pitch",l.getPitch()); saveConfig(); tell(p,"&aSpawn saved."); return true;
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
            if(!(s instanceof Player p)) return true;
            if(a.length >= 2 && a[0].equalsIgnoreCase("create")) {
                if(!isDev(p)) { tell(p,"&cOnly the owner can create kits."); return true; }
                String kitName=String.join("_",Arrays.copyOfRange(a,1,a.length)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                if(kitName.isBlank()) { tell(p,"&cUse /kit create <name>"); return true; }
                getConfig().set("kits."+kitName+".contents", p.getInventory().getContents());
                getConfig().set("kits."+kitName+".cooldown-seconds", 0); saveConfig();
                tell(p,"&aSaved kit &f"+kitName+" &afrom your current inventory."); return true;
            }
            if(a.length==2 && a[0].equalsIgnoreCase("delete")) {
                if(!isDev(p)) { tell(p,"&cOnly the owner can delete kits."); return true; }
                String kitName=a[1].toLowerCase(Locale.ROOT);
                if(!getConfig().contains("kits."+kitName)) { tell(p,"&cThat kit does not exist."); return true; }
                getConfig().set("kits."+kitName,null); saveConfig(); tell(p,"&aDeleted kit &f"+kitName+"&a."); return true;
            }
            if(a.length==0) { openKitMenu(p, "claim", null); return true; }
            String kit=a[0]; ConfigurationSection sec=getConfig().getConfigurationSection("kits."+kit);
            if(sec==null){tell(p,"&cUnknown kit. Use /kit to view available kits.");return true;}
            long cd=sec.getLong("cooldown-seconds",30)*1000L, now=System.currentTimeMillis(), next=kitCooldowns.getOrDefault(p.getUniqueId(),0L);
            if(next>now){tell(p,"&cWait "+((next-now+999)/1000)+"s before using a kit again.");return true;}
            if(sec.isList("contents")) { List<?> items=sec.getList("contents"); for(int i=0;i<Math.min(items.size(),p.getInventory().getSize());i++) if(items.get(i) instanceof ItemStack item) p.getInventory().setItem(i,item.clone()); }
            else for(String entry:sec.getStringList("items")){String[] parts=entry.split(":");Material m=Material.matchMaterial(parts[0]);if(m==null)continue;int amount=parts.length>1?Integer.parseInt(parts[1]):1;p.getInventory().addItem(new ItemStack(m,Math.max(1,Math.min(amount,m.getMaxStackSize()))));}
            kitCooldowns.put(p.getUniqueId(),now+cd);tell(p,"&dKit claimed: &f"+kit); return true;
        }
        if(n.equals("duel")) {
            if(!(s instanceof Player p)||a.length<1){tell(s,"Use /duel <player>");return true;} Player target=Bukkit.getPlayerExact(a[0]);
            if(target==null||target.equals(p)){tell(p,"&cPlayer not found or invalid.");return true;}
            duelTargets.put(p.getUniqueId(),target.getName()); duelGuiMode.put(p.getUniqueId(),"challenge"); openKitMenu(p,"duel",target.getName()); return true;
        }
        if(n.equals("accept")) {
            if(!(s instanceof Player p)||a.length<1)return true; UUID challenger=duelRequests.remove(p.getUniqueId()); Player from=challenger==null?null:Bukkit.getPlayer(challenger);
            if(from==null||!from.getName().equalsIgnoreCase(a[0])){tell(p,"&cNo matching duel request.");return true;}
            tell(p,"&dDuel accepted. &7Kit: &f"+duelChosenKits.getOrDefault(from.getUniqueId(),"default")+" &7| rounds: &f"+duelRounds.getOrDefault(from.getUniqueId(),3)+" &7| map: &f"+duelMaps.getOrDefault(from.getUniqueId(),"random"));tell(from,"&d"+p.getName()+" accepted your duel request. &7Arena teleporting still requires an arena configured by the server owner.");return true;
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
    private void openKitMenu(Player p, String mode, String target) {
        Inventory inv=Bukkit.createInventory(null,54,c("&5&lAzurPvP &8» &dSelect a kit"));
        ConfigurationSection kits=getConfig().getConfigurationSection("kits"); int slot=0;
        if(kits!=null) for(String key:kits.getKeys(false)) { if(slot>=45)break; inv.setItem(slot++,namedItem(Material.IRON_SWORD,"&d"+key,List.of("&7Click to select this kit"))); }
        if(slot==0) inv.setItem(22,namedItem(Material.BARRIER,"&cNo kits created",List.of("&7Owner: use /kit create <name>")));
        duelGuiMode.put(p.getUniqueId(), mode); if(target!=null)duelTargets.put(p.getUniqueId(),target);
        p.openInventory(inv);
    }
    private void openDuelSettings(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,c("&5&lDuel Settings"));
        inv.setItem(10,namedItem(Material.PAPER,"&dRounds: &f"+duelRounds.getOrDefault(p.getUniqueId(),3),List.of("&7Click to cycle 1, 3, 5, 7")));
        inv.setItem(13,namedItem(Material.MAP,"&dMap: &f"+duelMaps.getOrDefault(p.getUniqueId(),"random"),List.of("&7Click to cycle configured maps")));
        inv.setItem(16,namedItem(Material.LIME_CONCRETE,"&aSend Duel Request",List.of("&7Send settings to your opponent")));
        p.openInventory(inv); duelGuiMode.put(p.getUniqueId(),"settings");
    }
    private boolean isDuelItem(ItemStack item) { return item!=null && item.getType()==Material.IRON_SWORD && item.hasItemMeta() && item.getItemMeta().hasDisplayName() && DUEL_ITEM_NAME.equals(item.getItemMeta().getDisplayName()); }
    private void giveDuelItem(Player p) { for(ItemStack item:p.getInventory().getContents()) if(isDuelItem(item))return; ItemStack sword=namedItem(Material.IRON_SWORD,DUEL_ITEM_NAME,List.of("§7Right or left click to choose a kit")); p.getInventory().setItem(8,sword); }
    @EventHandler public void onDuelItemUse(PlayerInteractEvent e) { if(isDuelItem(e.getItem()) && (e.getAction()==Action.RIGHT_CLICK_AIR||e.getAction()==Action.RIGHT_CLICK_BLOCK||e.getAction()==Action.LEFT_CLICK_AIR||e.getAction()==Action.LEFT_CLICK_BLOCK)) { e.setCancelled(true); openKitMenu(e.getPlayer(),"queue",null); } }
    @EventHandler public void onDuelItemDrop(PlayerDropItemEvent e) { if(isDuelItem(e.getItemDrop().getItemStack()))e.setCancelled(true); }
    @EventHandler public void onDuelInventoryClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p))return;
        if(isDuelItem(e.getCurrentItem()) || isDuelItem(e.getCursor())) { e.setCancelled(true); return; }
        String title=ChatColor.stripColor(e.getView().getTitle());
        if(title==null || (!title.contains("Select a kit")&&!title.contains("Duel Settings")))return;
        e.setCancelled(true); if(e.getClickedInventory()==null||e.getClickedInventory()!=e.getView().getTopInventory())return;
        ItemStack item=e.getCurrentItem(); if(item==null||!item.hasItemMeta()||!item.getItemMeta().hasDisplayName())return;
        String display=ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if(title.contains("Select a kit")) {
            if(item.getType()==Material.BARRIER)return;
            String kit=display; if(!getConfig().contains("kits."+kit))return;
            String mode=duelGuiMode.getOrDefault(p.getUniqueId(),"queue");
            if(mode.equals("duel")) { duelChosenKits.put(p.getUniqueId(),kit); duelRounds.putIfAbsent(p.getUniqueId(),3); duelMaps.putIfAbsent(p.getUniqueId(),"random"); openDuelSettings(p); }
            else if(mode.equals("challenge")) { duelChosenKits.put(p.getUniqueId(),kit); duelRounds.putIfAbsent(p.getUniqueId(),3); duelMaps.putIfAbsent(p.getUniqueId(),"random"); openDuelSettings(p); }
            else { p.closeInventory(); tell(p,"&dQueued with kit &f"+kit+"&d. &7Matchmaking requires an arena/queue system to be configured."); }
        } else {
            if(e.getSlot()==10) { int[] values={1,3,5,7}; int current=duelRounds.getOrDefault(p.getUniqueId(),3), next=3; for(int i=0;i<values.length;i++)if(values[i]==current)next=values[(i+1)%values.length]; duelRounds.put(p.getUniqueId(),next); openDuelSettings(p); }
            else if(e.getSlot()==13) { List<String> maps=getConfig().getStringList("duel.maps"); if(maps.isEmpty())maps=List.of("random"); String current=duelMaps.getOrDefault(p.getUniqueId(),"random"); int i=maps.indexOf(current); duelMaps.put(p.getUniqueId(),maps.get((i+1)%maps.size())); openDuelSettings(p); }
            else if(e.getSlot()==16) { String targetName=duelTargets.get(p.getUniqueId()); Player target=targetName==null?null:Bukkit.getPlayerExact(targetName); if(target==null){p.closeInventory();tell(p,"&cYour opponent is no longer online.");return;} duelRequests.put(target.getUniqueId(),p.getUniqueId()); p.closeInventory(); tell(p,"&dDuel request sent to &f"+target.getName()+"&d. Kit: &f"+duelChosenKits.getOrDefault(p.getUniqueId(),"default")+"&d, rounds: &f"+duelRounds.getOrDefault(p.getUniqueId(),3)+"&d, map: &f"+duelMaps.getOrDefault(p.getUniqueId(),"random")); tell(target,"&d"+p.getName()+" challenged you! Use &f/accept "+p.getName()+"&d."); }
        }
    }
    @EventHandler public void onDuelInventoryDrag(InventoryDragEvent e) { String title=ChatColor.stripColor(e.getView().getTitle()); if(title!=null&&(title.contains("Select a kit")||title.contains("Duel Settings")))e.setCancelled(true); }
    @EventHandler public void onDuelJoin(PlayerJoinEvent e) { giveDuelItem(e.getPlayer()); }
    @EventHandler public void onDuelRespawn(PlayerRespawnEvent e) { Bukkit.getScheduler().runTask(this,()->giveDuelItem(e.getPlayer())); }
    @EventHandler public void onDuelDeath(PlayerDeathEvent e) { e.getDrops().removeIf(this::isDuelItem); }

    @Override public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if(args.length==1 && Set.of("duel","accept","msg","reply","ignore","heal","feed","freeze","stats").contains(cmd.getName().toLowerCase(Locale.ROOT)))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n->n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(cmd.getName().equalsIgnoreCase("world")) {
            if (args.length == 1) return List.of("create", "delete").stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("delete")) return Bukkit.getWorlds().stream().map(World::getName).filter(v -> v.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if(cmd.getName().equalsIgnoreCase("kit") && args.length==1){ List<String> choices=new ArrayList<>(List.of("create","delete")); ConfigurationSection ks=getConfig().getConfigurationSection("kits"); if(ks!=null)choices.addAll(ks.getKeys(false)); return choices.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList(); }
        return Collections.emptyList();
    }
}
