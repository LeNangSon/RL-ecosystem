package org.openjfx.app.core.strategies;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.RelationManager;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;

public class FleeStrategy implements MoveStrategy {
    private static final double STEERING_GAIN = 4.0;
    private static final double HIDE_DURATION = 1.0;
    private static final double DEFAULT_WANDER_DISTANCE_FACTOR = 0.6;
    private static final double DEFAULT_WANDER_RADIUS_FACTOR = 0.35;

    private double logCooldown;
    private double hidingTimer;
    private WanderStrategy wanderFallback;

    public static final class DebugPathState {
        private final List<Vector2D> path;
        public DebugPathState(List<Vector2D> path) { this.path = path; }
        public List<Vector2D> getPath() { return path; }
    }

    private static final Map<Integer, DebugPathState> DEBUG_PATH_STATES = new ConcurrentHashMap<>();

    public static DebugPathState getDebugPathState(int entityId) { return DEBUG_PATH_STATES.get(entityId); }
    public static void clearDebugPathState(int entityId) { DEBUG_PATH_STATES.remove(entityId); }

    public boolean isHiding() {
        return hidingTimer > 0;
    }

    @Override
    public void updateVelocity(LivingEntity owner, List<Entity> neighbors, double dt, WorldMap world) {
        if (!owner.isAlive()) return;

        Entity threat = findClosestThreat(owner, neighbors, world);
        Vector2D ownerPos = owner.getPosition();

        if (world.getTerrainAt(ownerPos) == TerrainType.BUSH) {
            if (threat != null) {
                hidingTimer = HIDE_DURATION;
                owner.setVelocity(new Vector2D(0, 0));
                owner.setAcceleration(new Vector2D(0, 0));
                clearDebugPathState(owner.getId());
                return;
            }
            hidingTimer = 0;
        }

        if (threat == null) {
            hidingTimer = 0;
            logCooldown = 0;
            clearDebugPathState(owner.getId());
            runWanderFallback(owner, neighbors, dt, world);
            return;
        }

        logCooldown -= dt;
        if (logCooldown <= 0) {
            world.notifyAction(
                    owner.getClass().getSimpleName() + "#" + owner.getId(),
                    "đang chạy trốn khỏi",
                    threat.getClass().getSimpleName() + "#" + threat.getId());
            logCooldown = 2.5;
        }

        Vector2D destination = pickFleeDestination(owner, world, ownerPos, threat.getPosition());
        Vector2D desiredVelocity = desiredVelocityAlongPath(owner, world, ownerPos, destination);
        if (desiredVelocity == null) {
            desiredVelocity = threat.getPosition().directionTo(ownerPos).multiply(owner.getMaxSpeed());
        }

        Vector2D steering = desiredVelocity.sub(owner.getVelocity());
        Vector2D acceleration = steering.multiply(STEERING_GAIN).limit(owner.getMaxForce());
        Vector2D newVelocity = owner.getVelocity().add(acceleration.multiply(dt));
        if (newVelocity.magnitude() > 1e-6) {
            newVelocity = newVelocity.normalize().multiply(owner.getMaxSpeed());
        }
        owner.setAcceleration(acceleration);
        owner.setVelocity(newVelocity);
    }

    private Entity findClosestThreat(LivingEntity owner, List<Entity> neighbors, WorldMap world) {
        double minDistance = Double.MAX_VALUE;
        Entity closest = null;
        for (Entity neighbor : neighbors) {
            if (RelationManager.isScaredOf(owner.getType(), neighbor.getType())) {
                double distance = owner.getPosition().distance(neighbor.getPosition());
                if (distance < minDistance) {
                    minDistance = distance;
                    closest = neighbor;
                }
            }
        }
        return closest;
    }

    private void runWanderFallback(LivingEntity owner, List<Entity> neighbors, double dt, WorldMap world) {
        if (wanderFallback == null) {
            double baseRadius = Math.max(owner.getVisionRadius(), 10.0);
            wanderFallback = new WanderStrategy(
                    baseRadius * DEFAULT_WANDER_DISTANCE_FACTOR,
                    baseRadius * DEFAULT_WANDER_RADIUS_FACTOR);
        }
        wanderFallback.updateVelocity(owner, neighbors, dt, world);
    }

    private Vector2D pickFleeDestination(LivingEntity owner, WorldMap world, Vector2D ownerPos, Vector2D threatPos) {
        double searchRadius = owner.getVisionRadius();
        EntityType type = owner.getType();
        if (type == EntityType.RABBIT) {
            if (world.getTerrainAt(ownerPos) == TerrainType.BUSH) return null;
            return world.findNearestTerrainPositionInRadius(ownerPos, TerrainType.BUSH, searchRadius);
        }
        if (type == EntityType.FISH) {
            return world.findFarthestTerrainPositionFromThreat(ownerPos, threatPos, TerrainType.WATER, searchRadius, owner);
        }
        return world.findFarthestTerrainPositionFromThreat(ownerPos, threatPos, TerrainType.LAND, searchRadius, owner);
    }

    private Vector2D desiredVelocityAlongPath(LivingEntity owner, WorldMap world, Vector2D ownerPos, Vector2D destination) {
        if (destination == null) {
            clearDebugPathState(owner.getId());
            return null;
        }
        List<Vector2D> path = world.findPathAStar(owner, ownerPos, destination);
        if (path == null || path.isEmpty()) {
            clearDebugPathState(owner.getId());
            return null;
        }
        DEBUG_PATH_STATES.put(owner.getId(), new DebugPathState(path));
        for (Vector2D waypoint : path) {
            if (waypoint != null && ownerPos.distance(waypoint) > 1e-3) {
                return ownerPos.directionTo(waypoint).multiply(owner.getMaxSpeed());
            }
        }
        clearDebugPathState(owner.getId());
        return null;
    }
}
