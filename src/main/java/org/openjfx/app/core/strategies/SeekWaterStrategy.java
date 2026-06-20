package org.openjfx.app.core.strategies;

import java.util.List;

import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.movable.Elephant;

public class SeekWaterStrategy implements MoveStrategy {
    private static final double STEERING_GAIN = 4.0;

    private final WanderStrategy searchWander;
    private Vector2D lastKnownWaterPos;
    private Vector2D lastLandPos    = null;
    private Vector2D waterEntryPos  = null; // vị trí lúc tâm vừa chạm zone nước
    private boolean  exitingWater   = false;

    public SeekWaterStrategy(double wanderDistance, double wanderRadius) {
        this.searchWander = new WanderStrategy(wanderDistance, wanderRadius);
    }

    @Override
    public void updateVelocity(LivingEntity owner, List<Entity> neighbors, double dt, WorldMap world) {
        Vector2D currentPos = owner.getPosition();
        boolean elephant = owner instanceof Elephant;

        // --- Voi: uu tien mang nuoc (chouongnuoc) vi co hoat anh uong rieng ---
        if (elephant) {
            Vector2D nearestTrough = world.findNearestTrough(currentPos);
            if (nearestTrough != null) {
                if (world.isInTrough(currentPos)) {
                    owner.setVelocity(new Vector2D(0, 0));
                    owner.setAcceleration(new Vector2D(0, 0));
                    owner.drink(dt);
                    return;
                }
                if (currentPos.distance(nearestTrough) <= owner.getVisionRadius()) {
                    moveToward(owner, currentPos, nearestTrough, dt, world);
                    return;
                }
            }
            // Khong co mang nuoc gan -> voi xuong uong o ho nhu binh thuong (no loi nuoc duoc),
            // khoi chet khat khi mang o xa. Roi xuong logic ho ben duoi.
        }

        if (world.getTerrainAt(currentPos) == TerrainType.WATER) {
            if (waterEntryPos == null) waterEntryPos = new Vector2D(currentPos.x, currentPos.y);

            double depth = currentPos.distance(waterEntryPos); // khoảng đã đi vào trong nước
            double requiredDepth = owner.getSize() / 2.0;     // cần vào ít nhất size/2

            if (!exitingWater) {
                if (depth < requiredDepth) {
                    // Chưa đủ sâu → tiếp tục đi vào nước (giữ velocity hiện tại)
                    return;
                }
                // Đủ sâu → dừng và uống
                owner.setVelocity(new Vector2D(0, 0));
                owner.setAcceleration(new Vector2D(0, 0));
                owner.drink(dt);
                if (owner.getThirst() < 5.0) exitingWater = true;
            } else {
                // Uống xong → đi về vị trí đất cuối cùng
                if (lastLandPos != null) {
                    owner.setVelocity(currentPos.directionTo(lastLandPos).multiply(owner.getMaxSpeed()));
                    owner.setAcceleration(new Vector2D(0, 0));
                }
            }
            return;
        }

        // Trên đất → lưu vị trí, reset trạng thái nước
        lastLandPos   = new Vector2D(currentPos.x, currentPos.y);
        waterEntryPos = null;
        exitingWater  = false;

        // --- Tìm mép nước gần nhất (KHÔNG giới hạn tầm nhìn) → đi tới uống ---
        // Đã khát thì con vật "biết" hướng nguồn nước gần nhất, không lang thang mù
        // rồi chết khát khi hồ nằm ngoài tầm nhìn ngắn.
        Vector2D nearestWater = world.findNearestTerrainPosition(currentPos, TerrainType.WATER);

        if (nearestWater != null) {
            lastKnownWaterPos = nearestWater;
            // Entity không vào được nước (Rabbit, Wolf) → uống khi mép chạm nước
            if (!world.canStandAtPoint(owner, nearestWater)
                    && isEdgeTouchingWater(owner, currentPos, world)) {
                owner.setVelocity(new Vector2D(0, 0));
                owner.setAcceleration(new Vector2D(0, 0));
                owner.drink(dt);
                return;
            }
            // Pathfind thẳng đến mép nước (Elephant/Fish tự bước vào)
            moveToward(owner, currentPos, nearestWater, dt, world);
            return;
        }

        // Không thấy nước → đi về vị trí đã nhớ nếu có
        if (lastKnownWaterPos != null) {
            double distToMemory = currentPos.distance(lastKnownWaterPos);
            if (distToMemory < owner.getSize()) {
                // Đến nơi rồi nhưng không thấy nước → quên đi, wander
                lastKnownWaterPos = null;
            } else {
                moveToward(owner, currentPos, lastKnownWaterPos, dt, world);
                return;
            }
        }

        searchWander.updateVelocity(owner, neighbors, dt, world);
    }

    private boolean isEdgeTouchingWater(LivingEntity owner, Vector2D pos, WorldMap world) {
        double r = owner.getSize() / 2.0 + 4.0;
        return world.getTerrainAt(new Vector2D(pos.x + r, pos.y)) == TerrainType.WATER
            || world.getTerrainAt(new Vector2D(pos.x - r, pos.y)) == TerrainType.WATER
            || world.getTerrainAt(new Vector2D(pos.x, pos.y + r)) == TerrainType.WATER
            || world.getTerrainAt(new Vector2D(pos.x, pos.y - r)) == TerrainType.WATER;
    }

    private void moveToward(LivingEntity owner, Vector2D from, Vector2D target, double dt, WorldMap world) {
        List<Vector2D> path = world.findPathAStar(owner, from, target);

        Vector2D desiredVelocity = null;
        if (path != null && !path.isEmpty()) {
            for (Vector2D waypoint : path) {
                if (waypoint != null && from.distance(waypoint) > 1.0) {
                    desiredVelocity = from.directionTo(waypoint).multiply(owner.getMaxSpeed());
                    break;
                }
            }
        }
        if (desiredVelocity == null) {
            desiredVelocity = from.distance(target) > 0.1
                    ? from.directionTo(target).multiply(owner.getMaxSpeed())
                    : new Vector2D(0, 0);
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
}
