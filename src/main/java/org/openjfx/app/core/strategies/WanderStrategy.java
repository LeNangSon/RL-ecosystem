package org.openjfx.app.core.strategies;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;

public class WanderStrategy implements MoveStrategy {

    public static final class DebugWanderState {
        private final Vector2D circleCenter;
        private final double wanderRadius;
        private final Vector2D randomPoint;

        public DebugWanderState(Vector2D circleCenter, double wanderRadius, Vector2D randomPoint) {
            this.circleCenter = circleCenter;
            this.wanderRadius = wanderRadius;
            this.randomPoint = randomPoint;
        }

        public Vector2D getCircleCenter() {
            return circleCenter;
        }

        public double getWanderRadius() {
            return wanderRadius;
        }

        public Vector2D getRandomPoint() {
            return randomPoint;
        }
    }

    private static final Map<Integer, DebugWanderState> DEBUG_STATES = new ConcurrentHashMap<>();

    private static final double STEERING_GAIN = 4.0;

    private double wanderDistance;
    private double wanderRadius;
    private double wanderTheta = Math.PI / 2;

    public WanderStrategy(double wanderDistance,double wanderRadius) {
        this.wanderDistance = wanderDistance;
        this.wanderRadius = wanderRadius;
    }

    public static DebugWanderState getDebugState(int entityId) {
        return DEBUG_STATES.get(entityId);
    }

    public static void clearDebugState(int entityId) {
        DEBUG_STATES.remove(entityId);
    }

    @Override
    public void updateVelocity(LivingEntity owner, List<Entity> neighbors, double dt, WorldMap world) {
        // Né chướng ngại (đá/nước/biên) do LivingEntity.avoidBlockedDirection() xử lý;
        // ở đây chỉ lo việc đi lang thang mượt.
        Vector2D currentVel = owner.getVelocity();
        Vector2D currentPos = owner.getPosition();

        double maxSpeed = owner.getWanderSpeed();

        double maxForce = owner.getMaxForce();

        double safeWanderDistance = this.wanderDistance;
        double safeWanderRadius = this.wanderRadius;

        Vector2D forward = currentVel.magnitude() < 0.01
                ? new Vector2D(1, 0)
                : currentVel.normalize();

        Vector2D circleCenter = currentPos.add(forward.multiply(safeWanderDistance));

        double heading = Math.atan2(forward.y, forward.x);
        double theta = wanderTheta + heading;
        Vector2D offset = new Vector2D(Math.cos(theta), Math.sin(theta)).multiply(safeWanderRadius);
        Vector2D wanderPoint = circleCenter.add(offset);

        DEBUG_STATES.put(owner.getId(), new DebugWanderState(circleCenter, safeWanderRadius, wanderPoint));

        double displaceRange = 0.25;
        wanderTheta += (-displaceRange + Math.random() * 2 * displaceRange);

        // Lái kiểu Reynolds: steering = vận tốc mong muốn - vận tốc hiện tại.
        // Lực giảm dần khi đã đi đúng hướng -> đổi hướng mượt, không giật.
        Vector2D desired = wanderPoint.sub(currentPos);
        if (desired.magnitude() > 1e-6) {
            desired = desired.normalize().multiply(maxSpeed);
        } else {
            desired = forward.multiply(maxSpeed);
        }

        Vector2D steering = desired.sub(currentVel);
        Vector2D acceleration = steering.multiply(STEERING_GAIN).limit(maxForce);
        owner.setAcceleration(acceleration);

        Vector2D nextVelocity = currentVel.add(acceleration.multiply(dt));
        if (maxSpeed > 0 && nextVelocity.magnitude() > 1e-6) {
            nextVelocity = nextVelocity.normalize().multiply(maxSpeed);
        }
        owner.setVelocity(nextVelocity);
    }
}
