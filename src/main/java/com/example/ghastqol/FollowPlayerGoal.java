package com.example.ghastqol;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.Location;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.EnumSet;

/**
 * Makes a happy ghast follow the nearest player, tethered to its home area.
 *
 * Registered through the Mob Goal API at high priority while claiming the MOVE and LOOK
 * slots, so the goal selector suppresses the ghast's vanilla "float around randomly"
 * goal while this one is active — and restores it the rest of the time.
 *
 * Happy ghasts ignore normal path navigation (getPathfinder().moveTo does nothing on
 * them), so we drive movement by setting the entity's velocity toward the target each
 * tick. Our goal owns the MOVE slot, so nothing fights us for control of its motion.
 */
public final class FollowPlayerGoal implements Goal<HappyGhast> {

    private static final double MAX_STEP_PER_TICK = 0.4; // blocks/tick at follow.speed = 1.0
    private static final double APPROACH_EASING = 0.5;   // slows as it nears the target
    private static final double VELOCITY_SMOOTHING = 0.2; // low = snappy (avoids orbiting)

    private final GhastQoLPlugin plugin;
    private final HappyGhast ghast;
    private final EnumSet<GoalType> types = EnumSet.of(GoalType.MOVE, GoalType.LOOK);

    FollowPlayerGoal(GhastQoLPlugin plugin, HappyGhast ghast) {
        this.plugin = plugin;
        this.ghast = ghast;
    }

    @Override
    public boolean shouldActivate() {
        if (!ghast.getPassengers().isEmpty()) {
            return false; // a rider is piloting it — stay out of the way
        }
        if (plugin.isOnFollowHold(ghast)) {
            return true; // just aligned: hold it still (also suppresses vanilla wander)
        }
        if (!plugin.isFollowEnabled()) {
            return false;
        }
        return plugin.followTarget(ghast) != null;
    }

    @Override
    public boolean shouldStayActive() {
        return shouldActivate();
    }

    @Override
    public void start() {
        // Nothing to set up; tick() does the work.
    }

    @Override
    public void tick() {
        // During a post-rotate hold, park in place and keep the alignment.
        if (plugin.isOnFollowHold(ghast)) {
            ghast.setVelocity(new Vector(0, 0, 0));
            ghast.setRotation(0.0f, 0.0f);
            return;
        }

        Player target = plugin.followTarget(ghast);
        if (target == null) {
            return;
        }

        Vector ghastPos = ghast.getLocation().toVector();
        Location home = plugin.getHome(ghast);
        double range = plugin.getFollowRange();
        boolean beyondHome = ghastPos.distance(home.toVector()) > range;

        // If we've drifted past the home range, steer back home instead of chasing.
        Vector aimPoint = beyondHome ? home.toVector() : target.getLocation().toVector();
        Vector toTarget = aimPoint.clone().subtract(ghastPos);
        double distance = toTarget.length();
        double stop = plugin.getFollowStopDistance();

        if (!beyondHome && distance <= stop) {
            // Close enough to the player: ease to a hover (don't crowd them).
            ghast.setVelocity(ghast.getVelocity().multiply(0.4));
            return;
        }
        if (distance < 1.0e-4) {
            return;
        }

        double dx = toTarget.getX();
        double dz = toTarget.getZ();

        double maxStep = MAX_STEP_PER_TICK * plugin.getFollowSpeed();
        double slack = beyondHome ? distance : (distance - stop);
        double step = Math.min(maxStep, slack * APPROACH_EASING + 0.05);
        Vector desired = toTarget.clone().normalize().multiply(step);
        Vector blended = ghast.getVelocity().multiply(VELOCITY_SMOOTHING)
                .add(desired.multiply(1.0 - VELOCITY_SMOOTHING));
        ghast.setVelocity(blended);

        // Face the direction of travel (level pitch).
        if (dx != 0 || dz != 0) {
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            ghast.setRotation((float) yaw, 0.0f);
        }
    }

    @Override
    public void stop() {
        // Let vanilla flight take back over; no cleanup needed.
    }

    @Override
    public GoalKey<HappyGhast> getKey() {
        return plugin.getFollowGoalKey();
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return types;
    }
}
