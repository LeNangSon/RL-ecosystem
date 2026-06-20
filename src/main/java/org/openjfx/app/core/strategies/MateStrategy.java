package org.openjfx.app.core.strategies;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;

public class MateStrategy implements MoveStrategy {

    private static final double STEERING_GAIN = 4.0;
    private static final double DEFAULT_WANDER_DISTANCE_FACTOR = 0.6;
    private static final double DEFAULT_WANDER_RADIUS_FACTOR = 0.35;

    private double logCooldown = 0;
    private WanderStrategy wanderFallback;

    public static final class DebugPathState {
        private final List<Vector2D> path;

        public DebugPathState(List<Vector2D> path) {
            this.path = path;
        }

        public List<Vector2D> getPath() {
            return path;
        }
    }

    private static final Map<Integer, DebugPathState> DEBUG_PATH_STATES = new ConcurrentHashMap<>();

    public static DebugPathState getDebugPathState(int entityId) {
        return DEBUG_PATH_STATES.get(entityId);
    }

    public static void clearDebugPathState(int entityId) {
        DEBUG_PATH_STATES.remove(entityId);
    }

    private LivingEntity findClosestMate(LivingEntity owner, List<Entity> neighbors) {
        double minDistance = Double.MAX_VALUE;
        LivingEntity closest = null;
        for (Entity neighbor : neighbors) {
            if (!(neighbor instanceof LivingEntity)) {
                continue;
            }
            LivingEntity other = (LivingEntity) neighbor;
            if (other.getClass() != owner.getClass() || !other.canReproduce()) {
                continue;
            }
            double distance = owner.getPosition().distance(other.getPosition());
            if (distance < minDistance) {
                minDistance = distance;
                closest = other;
            }
        }
        return closest;
    }

    @Override
    public void updateVelocity(LivingEntity owner, List<Entity> neighbors, double dt, WorldMap world) {
        if (!owner.isAlive()) {
            return;
        }

        LivingEntity mate = findClosestMate(owner, neighbors);
        if (mate == null) {
            logCooldown = 0;
            clearDebugPathState(owner.getId());
            runWanderFallback(owner, neighbors, dt, world);
            return;
        }

        String ownerName = owner.getClass().getSimpleName() + "#" + owner.getId();
        String mateName = mate.getClass().getSimpleName() + "#" + mate.getId();
        double range = owner.getPosition().distance(mate.getPosition());
        double matingRange = (owner.getSize() + mate.getSize()) * 0.6;

        if (range <= matingRange) {
            owner.setAcceleration(new Vector2D(0, 0));
            owner.setVelocity(new Vector2D(0, 0));
            clearDebugPathState(owner.getId());

            if (owner.getId() < mate.getId()) {
                owner.spawnOffspring(world, mate);
            }
            return;
        }

        logCooldown -= dt;
        if (logCooldown <= 0) {
            world.notifyAction(ownerName, "đang tìm bạn tình", mateName);
            logCooldown = 3.0;
        }

        Vector2D ownerPos = owner.getPosition();
        Vector2D matePos = mate.getPosition();
        List<Vector2D> path = world.findPathAStar(owner, ownerPos, matePos);

        Vector2D desiredVelocity = null;
        if (path != null && !path.isEmpty()) {
            DEBUG_PATH_STATES.put(owner.getId(), new DebugPathState(path));
            for (Vector2D waypoint : path) {
                if (waypoint != null && ownerPos.distance(waypoint) > 1) {
                    desiredVelocity = ownerPos.directionTo(waypoint).multiply(owner.getMaxSpeed());
                    break;
                }
            }
        } else {
            clearDebugPathState(owner.getId());
        }

        if (desiredVelocity == null) {
            desiredVelocity = ownerPos.directionTo(matePos).multiply(owner.getMaxSpeed());
        }

        Vector2D steering = desiredVelocity.sub(owner.getVelocity());
        Vector2D acceleration = steering.multiply(STEERING_GAIN).limit(owner.getMaxForce());
        Vector2D newVelocity = owner.getVelocity().add(acceleration.multiply(dt)).limit(owner.getMaxSpeed());
        owner.setAcceleration(acceleration);
        owner.setVelocity(newVelocity);
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
}
