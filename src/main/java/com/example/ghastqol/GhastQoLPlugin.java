package com.example.ghastqol;

import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class GhastQoLPlugin extends JavaPlugin implements Listener {

    private NamespacedKey modifierKey;
    private double speedMultiplier;
    private boolean onlyWhenRidden;

    // Follow settings
    private boolean followEnabled;
    private double followRange;
    private double followStopDistance;
    private double followSpeed;
    private long followFollowMillis;
    private long followRestMillis;
    private String followTrigger;      // "look" or "timer"
    private double lookDotThreshold;   // cos(look-angle): higher = tighter cone
    private boolean requireLineOfSight;
    private long lookLingerMillis;
    private GoalKey<HappyGhast> followGoalKey;

    // Each ghast's home location (where it rests). Following is limited to this area.
    private final Map<UUID, Location> homes = new HashMap<>();

    // Look-trigger state: when a ghast was last looked at, and by whom (for the linger window).
    private final Map<UUID, Long> lastLookedAt = new HashMap<>();
    private final Map<UUID, UUID> lastLooker = new HashMap<>();

    // Rotate settings
    private double rotateRange;
    private long rotateHoldMillis;

    // Ghasts that were just aligned — the follow goal parks them (instead of following
    // or wandering) until the timestamp, so a fresh alignment isn't immediately undone.
    private final Map<UUID, Long> followHoldUntil = new HashMap<>();

    @Override
    public void onEnable() {
        if (!passesStartupChecks()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        modifierKey = new NamespacedKey(this, "flying_speed_boost");
        followGoalKey = GoalKey.of(HappyGhast.class, new NamespacedKey(this, "follow_player"));
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("ghastqol");
        if (command != null) {
            command.setExecutor(this::onCommand);
            command.setTabCompleter(this::onTabComplete);
        }

        for (HappyGhast ghast : allLoadedGhasts()) {
            update(ghast);
            registerFollowGoal(ghast);
        }
        getLogger().info("GhastQoL enabled (multiplier=" + speedMultiplier
                + ", only-when-ridden=" + onlyWhenRidden + ", follow=" + followEnabled + ").");
    }

    @Override
    public void onDisable() {
        // Clean up so removing the plugin restores vanilla behaviour.
        for (HappyGhast ghast : allLoadedGhasts()) {
            removeBoost(ghast);
            unregisterFollowGoal(ghast);
        }
    }

    // ---- Startup compatibility checks ----------------------------------------

    private boolean passesStartupChecks() {
        // Folia (and Folia forks) are region-threaded; this plugin uses the main-thread
        // scheduler and edits entities off-region, which would throw. Refuse cleanly.
        if (isFolia()) {
            getLogger().severe("GhastQoL is not Folia-compatible (it relies on the main-thread "
                    + "scheduler). Disabling. Regular Paper 26+ works fine.");
            return false;
        }
        // Happy Ghasts, the Mob Goal usage, and Java 25 bytecode all require Paper 26+.
        String mcVersion = Bukkit.getBukkitVersion().split("-")[0]; // e.g. "26.2"
        if (!isAtLeast(mcVersion, 26, 0)) {
            getLogger().severe("GhastQoL requires Paper 26 or newer. Detected Minecraft "
                    + mcVersion + ". Disabling.");
            return false;
        }
        getLogger().info("Compatibility check passed (Minecraft " + mcVersion + ", Paper).");
        return true;
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /** True if {@code version} (e.g. "26.2") is >= the given components (e.g. 26, 0). */
    private static boolean isAtLeast(String version, int... minimum) {
        String[] parts = version.split("\\.");
        for (int i = 0; i < minimum.length; i++) {
            int part = 0;
            if (i < parts.length) {
                try {
                    part = Integer.parseInt(parts[i].replaceAll("\\D.*$", ""));
                } catch (NumberFormatException ignored) {
                    part = 0;
                }
            }
            if (part != minimum[i]) {
                return part > minimum[i];
            }
        }
        return true;
    }

    private void loadSettings() {
        reloadConfig();
        speedMultiplier = getConfig().getDouble("speed-multiplier", 3.0);
        onlyWhenRidden = getConfig().getBoolean("only-when-ridden", false);

        followEnabled = getConfig().getBoolean("follow.enabled", true);
        followRange = getConfig().getDouble("follow.range", 32.0);
        followStopDistance = getConfig().getDouble("follow.stop-distance", 4.0);
        followSpeed = getConfig().getDouble("follow.speed", 1.0);
        followFollowMillis = Math.max(0L, getConfig().getLong("follow.follow-seconds", 10L)) * 1000L;
        followRestMillis = Math.max(0L, getConfig().getLong("follow.rest-seconds", 10L)) * 1000L;
        followTrigger = getConfig().getString("follow.trigger", "look").toLowerCase(Locale.ROOT);
        double lookAngle = getConfig().getDouble("follow.look-angle", 35.0);
        lookDotThreshold = Math.cos(Math.toRadians(Math.max(1.0, Math.min(180.0, lookAngle))));
        requireLineOfSight = getConfig().getBoolean("follow.require-line-of-sight", true);
        lookLingerMillis = Math.max(0L, getConfig().getLong("follow.look-linger-seconds", 2L)) * 1000L;

        rotateRange = getConfig().getDouble("rotate.range", 8.0);
        rotateHoldMillis = Math.max(0L, getConfig().getLong("rotate.hold-seconds", 5L)) * 1000L;
    }

    private List<HappyGhast> allLoadedGhasts() {
        List<HappyGhast> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            result.addAll(world.getEntitiesByClass(HappyGhast.class));
        }
        return result;
    }

    // ---- Flying-speed attribute logic ----------------------------------------

    private void update(HappyGhast ghast) {
        if (onlyWhenRidden) {
            applyIfRidden(ghast);
        } else {
            applyBoost(ghast);
        }
    }

    private void applyIfRidden(HappyGhast ghast) {
        if (!ghast.getPassengers().isEmpty()) {
            applyBoost(ghast);
        } else {
            removeBoost(ghast);
        }
    }

    private void applyBoost(HappyGhast ghast) {
        AttributeInstance attr = ghast.getAttribute(Attribute.FLYING_SPEED);
        if (attr == null) {
            return;
        }
        removeOurModifier(attr); // keep it idempotent — never stack

        double amount = speedMultiplier - 1.0; // ADD_SCALAR adds amount * base
        if (amount <= 0.0) {
            return; // multiplier <= 1 means "no boost"
        }
        attr.addModifier(new AttributeModifier(
                modifierKey, amount, AttributeModifier.Operation.ADD_SCALAR));
    }

    private void removeBoost(HappyGhast ghast) {
        AttributeInstance attr = ghast.getAttribute(Attribute.FLYING_SPEED);
        if (attr != null) {
            removeOurModifier(attr);
        }
    }

    private void removeOurModifier(AttributeInstance attr) {
        for (AttributeModifier existing : new ArrayList<>(attr.getModifiers())) {
            if (modifierKey.equals(existing.getKey())) {
                attr.removeModifier(existing);
            }
        }
    }

    // ---- Follow goal management ----------------------------------------------

    private void registerFollowGoal(HappyGhast ghast) {
        getHome(ghast); // make sure a home is recorded for this ghast
        // Goals added via the API are runtime-only and reset when the entity reloads,
        // so we (re)add on every add-to-world. Priority 0 = highest.
        if (!Bukkit.getMobGoals().hasGoal(ghast, followGoalKey)) {
            Bukkit.getMobGoals().addGoal(ghast, 0, new FollowPlayerGoal(this, ghast));
        }
    }

    private void unregisterFollowGoal(HappyGhast ghast) {
        Bukkit.getMobGoals().removeGoal(ghast, followGoalKey);
    }

    /** The nearest non-spectator player within follow range of this ghast's home, or null. */
    Player nearestPlayerNearHome(HappyGhast ghast) {
        Location home = getHome(ghast);
        Player nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (Player player : home.getNearbyPlayers(followRange)) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double distSq = player.getLocation().distanceSquared(ghast.getLocation());
            if (distSq < bestSq) {
                bestSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    /**
     * The player this ghast should currently follow, or null to stand down.
     * In "timer" mode this is the nearest player during the follow phase; in "look" mode
     * it's whoever is looking at the ghast (plus a short linger after they look away).
     */
    Player followTarget(HappyGhast ghast) {
        if ("timer".equals(followTrigger)) {
            return isInFollowPhase() ? nearestPlayerNearHome(ghast) : null;
        }

        // "look" mode
        Player looker = lookingPlayerNearHome(ghast);
        long now = System.currentTimeMillis();
        if (looker != null) {
            lastLookedAt.put(ghast.getUniqueId(), now);
            lastLooker.put(ghast.getUniqueId(), looker.getUniqueId());
            return looker;
        }
        // Linger: keep following briefly after they last looked, so a quick glance away
        // doesn't make it stop dead.
        Long since = lastLookedAt.get(ghast.getUniqueId());
        if (since != null && now - since <= lookLingerMillis) {
            UUID lookerId = lastLooker.get(ghast.getUniqueId());
            if (lookerId != null) {
                Player prev = Bukkit.getPlayer(lookerId);
                if (prev != null && prev.isValid() && withinHomeRange(ghast, prev)) {
                    return prev;
                }
            }
            return nearestPlayerNearHome(ghast);
        }
        return null;
    }

    /** Nearest non-spectator player, in home range, who is looking at the ghast. */
    private Player lookingPlayerNearHome(HappyGhast ghast) {
        Location home = getHome(ghast);
        Vector center = ghast.getBoundingBox().getCenter();
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (Player player : home.getNearbyPlayers(followRange)) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (!isLookingAt(player, ghast, center)) {
                continue;
            }
            double distSq = player.getLocation().distanceSquared(ghast.getLocation());
            if (distSq < bestSq) {
                bestSq = distSq;
                best = player;
            }
        }
        return best;
    }

    /** True if the ghast is inside the player's view cone (and visible, if required). */
    private boolean isLookingAt(Player player, HappyGhast ghast, Vector ghastCenter) {
        Location eye = player.getEyeLocation();
        Vector toGhast = ghastCenter.clone().subtract(eye.toVector());
        if (toGhast.lengthSquared() < 1.0e-6) {
            return true; // essentially on top of it
        }
        double dot = eye.getDirection().dot(toGhast.normalize());
        if (dot < lookDotThreshold) {
            return false; // outside the "looking at it" cone
        }
        return !requireLineOfSight || player.hasLineOfSight(ghast);
    }

    private boolean withinHomeRange(HappyGhast ghast, Player player) {
        Location home = getHome(ghast);
        return player.getWorld().equals(home.getWorld())
                && player.getLocation().distanceSquared(home) <= followRange * followRange;
    }

    /** This ghast's home, seeding it to the current location the first time we see it. */
    Location getHome(HappyGhast ghast) {
        return homes.computeIfAbsent(ghast.getUniqueId(), id -> ghast.getLocation());
    }

    private void setHome(HappyGhast ghast) {
        homes.put(ghast.getUniqueId(), ghast.getLocation());
    }

    /** True during the "follow" part of the follow/rest cycle (or always, if rest = 0). */
    boolean isInFollowPhase() {
        if (followRestMillis <= 0L) {
            return true;
        }
        long period = followFollowMillis + followRestMillis;
        return (System.currentTimeMillis() % period) < followFollowMillis;
    }

    boolean isFollowEnabled() {
        return followEnabled;
    }

    double getFollowRange() {
        return followRange;
    }

    double getFollowSpeed() {
        return followSpeed;
    }

    double getFollowStopDistance() {
        return followStopDistance;
    }

    GoalKey<HappyGhast> getFollowGoalKey() {
        return followGoalKey;
    }

    boolean isOnFollowHold(HappyGhast ghast) {
        Long until = followHoldUntil.get(ghast.getUniqueId());
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            followHoldUntil.remove(ghast.getUniqueId());
            return false;
        }
        return true;
    }

    // ---- Events ---------------------------------------------------------------

    // Fires both when a ghast is spawned and when it is loaded from a chunk.
    @EventHandler
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof HappyGhast ghast) {
            update(ghast);
            registerFollowGoal(ghast);
        }
    }

    @EventHandler
    public void onMount(EntityMountEvent event) {
        if (onlyWhenRidden && event.getMount() instanceof HappyGhast ghast) {
            applyBoost(ghast);
        }
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (event.getDismounted() instanceof HappyGhast ghast) {
            // Passenger list only updates after the event, so re-check next tick.
            Bukkit.getScheduler().runTask(this, () -> {
                setHome(ghast); // re-home to where the rider left it (mirrors vanilla)
                if (onlyWhenRidden) {
                    applyIfRidden(ghast);
                }
            });
        }
    }

    // ---- Command --------------------------------------------------------------

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("ghastqol.reload")) {
                    sender.sendMessage(Component.text(
                            "You don't have permission to do that.", NamedTextColor.RED));
                    return true;
                }
                loadSettings();
                for (HappyGhast ghast : allLoadedGhasts()) {
                    update(ghast);
                }
                sender.sendMessage(Component.text(
                        "GhastQoL reloaded. Multiplier: " + speedMultiplier
                                + ", follow: " + (followEnabled ? "on" : "off") + ".",
                        NamedTextColor.GREEN));
            }
            case "rotate" -> doRotate(sender);
            default -> sender.sendMessage(Component.text(
                    "Usage: /ghastqol <reload|rotate>", NamedTextColor.RED));
        }
        return true;
    }

    private void doRotate(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "Only players can use /ghastqol rotate.", NamedTextColor.RED));
            return;
        }
        if (!player.hasPermission("ghastqol.rotate")) {
            player.sendMessage(Component.text(
                    "You don't have permission to do that.", NamedTextColor.RED));
            return;
        }

        HappyGhast ghast = findGhastFor(player);
        if (ghast == null) {
            player.sendMessage(Component.text(
                    "No happy ghast found within " + (int) rotateRange + " blocks.",
                    NamedTextColor.RED));
            return;
        }

        // Snap to yaw 0 (facing south, +Z) and level pitch so it lines up with the grid.
        ghast.setRotation(0.0f, 0.0f);
        ghast.setVelocity(new Vector(0, 0, 0));
        // The follow goal will keep it parked & aligned for the hold window.
        if (rotateHoldMillis > 0L) {
            followHoldUntil.put(ghast.getUniqueId(), System.currentTimeMillis() + rotateHoldMillis);
        }

        player.sendMessage(Component.text(
                "Aligned the happy ghast to face 0\u00B0 (south).", NamedTextColor.GREEN));
    }

    /** The ghast the player is riding, or the nearest one within rotateRange. */
    private HappyGhast findGhastFor(Player player) {
        if (player.getVehicle() instanceof HappyGhast riding) {
            return riding;
        }
        HappyGhast nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity entity : player.getNearbyEntities(rotateRange, rotateRange, rotateRange)) {
            if (entity instanceof HappyGhast ghast) {
                double distSq = entity.getLocation().distanceSquared(player.getLocation());
                if (distSq < bestSq) {
                    bestSq = distSq;
                    nearest = ghast;
                }
            }
        }
        return nearest;
    }

    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String option : List.of("reload", "rotate")) {
                if (option.startsWith(prefix)) {
                    out.add(option);
                }
            }
        }
        return out;
    }
}
