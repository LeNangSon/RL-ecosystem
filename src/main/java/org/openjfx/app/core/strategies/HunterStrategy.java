package org.openjfx.app.core.strategies;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openjfx.app.core.DeathCause;
import org.openjfx.app.core.RelationManager;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.base.MovableEntity;
import org.openjfx.app.entities.staticobjs.Plant;

public class HunterStrategy implements MoveStrategy {
    private static final double STEERING_GAIN = 4.0;
    private static final int MIN_BLOCKED_WAYPOINTS = 3;
    private static final int MAX_BLOCKED_WAYPOINTS = 20;
    private static final double DEFAULT_WANDER_DISTANCE_FACTOR = 0.6;
    private static final double DEFAULT_WANDER_RADIUS_FACTOR = 0.35;
    private static final double WAYPOINT_ADVANCE_RADIUS = 10.0;
    private static final double REPLAN_PREY_MOVE = 20.0;
    private static final double REPLAN_COOLDOWN = 0.3;
    // Trong khoảng này (theo số ô lưới) thì bỏ bám đường, đuổi thẳng vào điểm đón đầu của
    // mồi -> hết cảnh vờn theo điểm lưới cũ ở pha cận chiến. Ở xa vẫn dùng A* để né vật cản.
    private static final double DIRECT_PURSUIT_CELLS = 2.5;
    // Đón đầu: trần thời gian dự đoán vị trí mồi (giây). Lớn quá -> nhắm hụt khi mồi bẻ hướng.
    // RBS dùng giá trị mặc định cố định; RL (rl_v03) dựng nhiều HunterStrategy với leadTimeCap
    // khác nhau làm các "kiểu săn" macro-action, rồi học chọn kiểu theo tình huống.
    public static final double DEFAULT_LEAD_TIME = 0.7;
    private final double leadTimeCap;

    /** Constructor mặc định (RBS): đón đầu cố định {@value #DEFAULT_LEAD_TIME}s. */
    public HunterStrategy() { this(DEFAULT_LEAD_TIME); }

    /** Constructor có tham số: cho RL chọn độ "cắt góc" đón đầu (0 = lao thẳng, lớn = mai phục). */
    public HunterStrategy(double leadTimeCap) { this.leadTimeCap = leadTimeCap; }

    private double logCooldown;
    private double replanCooldown;
    private WanderStrategy wanderFallback;
    private List<Vector2D> cachedPath;
    private int waypointIdx;
    private int cachedTargetId = -1;
    private Vector2D lastKnownPreyPos;

    public static final class DebugPathState {
        private final List<Vector2D> path;
        public DebugPathState(List<Vector2D> path) { this.path = path; }
        public List<Vector2D> getPath() { return path; }
    }

    private static final Map<Integer, DebugPathState> DEBUG_PATH_STATES = new ConcurrentHashMap<>();

    public static DebugPathState getDebugPathState(int entityId) { return DEBUG_PATH_STATES.get(entityId); }
    public static void clearDebugPathState(int entityId) { DEBUG_PATH_STATES.remove(entityId); }

    @Override
    public void updateVelocity(LivingEntity owner, List<Entity> neighbors, double dt, WorldMap world) {
        if (!owner.isAlive()) return;

        Entity prey = findClosestPrey(owner, neighbors, world);
        if (prey == null) {
            resetPath(owner);
            runWanderFallback(owner, neighbors, dt, world);
            return;
        }

        String ownerName = owner.getClass().getSimpleName() + "#" + owner.getId();
        String preyName = prey.getClass().getSimpleName() + "#" + prey.getId();
        logCooldown -= dt;
        if (logCooldown <= 0) {
            world.notifyAction(ownerName, "đang săn đuổi", preyName);
            logCooldown = 3.0;
        }

        double range = owner.getPosition().distance(prey.getPosition());
        if (range < world.getInteractionDistance(owner, prey)) {
            owner.setAcceleration(new Vector2D(0, 0));
            owner.setVelocity(new Vector2D(0, 0));
            world.notifyAction(ownerName, "đã bắt được", preyName);

            boolean preyWasAlive = prey instanceof LivingEntity preyLiving && preyLiving.isAlive();
            owner.eat(prey, dt);
            if (preyWasAlive && prey instanceof LivingEntity preyLiving && !preyLiving.isAlive()) {
                world.recordDeath(prey.getType(), DeathCause.PREDATION);
                world.broadcastDeath(preyName + " đã chết vì bị " + ownerName + " săn");
            }
            return;
        }

        Vector2D ownerPos = owner.getPosition();
        Vector2D preyPos = prey.getPosition();
        replanCooldown -= dt;
        boolean needReplan = cachedPath == null
                || waypointIdx >= cachedPath.size()
                || cachedTargetId != prey.getId()
                || owner.getBlockedLastStep()
                || (lastKnownPreyPos != null && preyPos.distance(lastKnownPreyPos) > REPLAN_PREY_MOVE);

        // Giới hạn tần suất tính lại đường (A* nặng): tối đa ~3 lần/giây mỗi predator.
        // Giữa các lần replan, hunter vẫn bám theo path cũ / đi thẳng tới mồi (fallback bên dưới).
        if (needReplan && replanCooldown <= 0) {
            Set<String> avoidedGridKeys = owner.getBlockedLastStep() ? collectBlockedWaypointKeys(owner.getId(), world) : null;
            // Đón đầu: tìm đường tới chỗ mồi SẼ tới; nếu điểm đón rơi vào vật cản (A* null)
            // thì lui về tìm đường tới vị trí thật của mồi.
            Vector2D aim = leadAimPoint(owner, prey, ownerPos, preyPos);
            List<Vector2D> rawPath = world.findPathAStar(owner, ownerPos, aim, avoidedGridKeys);
            if (rawPath == null) rawPath = world.findPathAStar(owner, ownerPos, preyPos, avoidedGridKeys);
            cachedPath = rawPath == null ? null : world.densifyPath(rawPath, world.getCellSize() / 3.0);
            waypointIdx = 0;
            cachedTargetId = prey.getId();
            lastKnownPreyPos = preyPos;
            replanCooldown = REPLAN_COOLDOWN;
        }

        if (cachedPath != null) {
            DEBUG_PATH_STATES.put(owner.getId(), new DebugPathState(cachedPath));
            // Tiến waypoint khi đã tới gần HOẶC đã vọt qua (điểm kế không xa hơn điểm hiện
            // tại) -> không kẹt quay đầu khi ôm cua rộng hơn dung sai WAYPOINT_ADVANCE_RADIUS.
            while (waypointIdx < cachedPath.size() - 1) {
                double distCur = ownerPos.distance(cachedPath.get(waypointIdx));
                double distNext = ownerPos.distance(cachedPath.get(waypointIdx + 1));
                if (distCur < WAYPOINT_ADVANCE_RADIUS || distNext <= distCur) waypointIdx++;
                else break;
            }
        } else {
            clearDebugPathState(owner.getId());
        }

        Vector2D desiredVelocity;
        Vector2D nextWaypoint = cachedPath != null && waypointIdx < cachedPath.size() ? cachedPath.get(waypointIdx) : null;
        if (ownerPos.distance(preyPos) <= world.getCellSize() * DIRECT_PURSUIT_CELLS || nextWaypoint == null) {
            // Cận chiến (hoặc không có đường) -> lao thẳng vào điểm đón đầu của mồi.
            Vector2D aim = leadAimPoint(owner, prey, ownerPos, preyPos);
            desiredVelocity = ownerPos.directionTo(aim).multiply(owner.getMaxSpeed());
        } else {
            desiredVelocity = ownerPos.directionTo(nextWaypoint).multiply(owner.getMaxSpeed());
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

    // Điểm đón đầu: vị trí dự đoán của mồi sau leadTime (tỉ lệ khoảng cách / tốc độ sói,
    // chặn trần MAX_LEAD_TIME). Mồi đứng yên / không phải vật di chuyển -> trả về vị trí hiện tại.
    private Vector2D leadAimPoint(LivingEntity owner, Entity prey, Vector2D ownerPos, Vector2D preyPos) {
        double speed = owner.getMaxSpeed();
        if (speed <= 1e-6 || !(prey instanceof MovableEntity moving)) return preyPos;
        double leadTime = Math.min(ownerPos.distance(preyPos) / speed, leadTimeCap);
        return preyPos.add(moving.getVelocity().multiply(leadTime));
    }

    private Entity findClosestPrey(LivingEntity owner, List<Entity> neighbors, WorldMap world) {
        double minDistance = Double.MAX_VALUE;
        Entity closest = null;
        for (Entity neighbor : neighbors) {
            if (neighbor instanceof Plant plant && !plant.isAlive()) continue;
            if (!RelationManager.isPrey(neighbor.getType(), owner.getType())) continue;
            if (world.getTerrainAt(neighbor.getPosition()) == TerrainType.BUSH) continue;
            double distance = owner.getPosition().distance(neighbor.getPosition());
            if (distance < minDistance) {
                minDistance = distance;
                closest = neighbor;
            }
        }
        return closest;
    }

    private void resetPath(LivingEntity owner) {
        logCooldown = 0;
        cachedPath = null;
        cachedTargetId = -1;
        clearDebugPathState(owner.getId());
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

    private Set<String> collectBlockedWaypointKeys(int ownerId, WorldMap world) {
        DebugPathState lastPathState = DEBUG_PATH_STATES.get(ownerId);
        if (lastPathState == null || lastPathState.getPath() == null || lastPathState.getPath().isEmpty()) return null;

        Set<String> blockedKeys = new HashSet<>();
        List<Vector2D> lastPath = lastPathState.getPath();
        int halfPlusOne = (lastPath.size() / 2) + 1;
        int limit = Math.min(lastPath.size(), Math.min(MAX_BLOCKED_WAYPOINTS, Math.max(MIN_BLOCKED_WAYPOINTS, halfPlusOne)));
        for (int i = 0; i < limit; i++) {
            WorldMap.GridCoordinate grid = world.worldToGrid(lastPath.get(i));
            if (grid != null) blockedKeys.add(grid.getRow() + ":" + grid.getCol());
        }
        return blockedKeys.isEmpty() ? null : blockedKeys;
    }
}
