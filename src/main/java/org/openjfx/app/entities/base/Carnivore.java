package org.openjfx.app.entities.base;

import java.util.List;

import org.openjfx.app.core.RelationManager;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.strategies.FleeStrategy;
import org.openjfx.app.core.strategies.HunterStrategy;
import org.openjfx.app.core.strategies.MateStrategy;
import org.openjfx.app.core.strategies.SeekWaterStrategy;
import org.openjfx.app.core.strategies.StrategyCandidate;
import org.openjfx.app.core.strategies.WanderStrategy;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.staticobjs.Plant;

public abstract class Carnivore extends LivingEntity {

    // Chỉ khi đói tới ngưỡng này thú mới CHỦ ĐỘNG đi săn/đuổi mồi. Dưới ngưỡng (no) thì
    // kệ thỏ, không đuổi — trừ khi mồi ở ngay tận miệng (xem POUNCE_WHEN_FULL_CLOSENESS).
    private static final double HUNT_HUNGER_START = 50.0;
    // "Tầm vồ" khi ĐANG ĐÓI: mồi vào trong nửa tầm nhìn thì chốt đuổi dứt khoát (không vờn).
    private static final double POUNCE_CLOSENESS = 0.5;
    // Phản xạ khi CHƯA ĐÓI: chỉ đớp khi mồi sát tận miệng (closeness ~ tầm bắt được, rất gần)
    // -> miếng ăn miễn phí ngay cạnh thì không bỏ, nhưng không chủ động đuổi con ở xa.
    private static final double POUNCE_WHEN_FULL_CLOSENESS = 0.85;
    // Điểm khi vồ: thắng mọi nhu cầu thường (SeekWater tối đa ~1.4) nhưng vẫn thua bỏ chạy (100).
    private static final double POUNCE_HUNT_SCORE = 2.0;

    private List<StrategyCandidate> candidates;
    // Lưu world của frame hiện tại để hàm chấm điểm lọc mồi giống HunterStrategy (địa hình bụi).
    private WorldMap currentWorld;

    public Carnivore(Vector2D position, double size, String shape, double initialHealth, double hungerRate, double thirstRate,
                     double maxSpeed, double maxForce, double mass,
                     double wanderDistance, double wanderRadius) {
        super(position, size, shape, initialHealth, hungerRate, thirstRate,
                maxSpeed, maxForce, mass, wanderDistance, wanderRadius);
    }

    @Override
    public void eat(Entity target, double dt) {
        if (target instanceof LivingEntity prey && prey.isAlive()) {
            prey.setHealth(0);
            setHunger(0);
            setHealth(200);
            setVelocity(new Vector2D(0, 0));
        }
    }

    private List<StrategyCandidate> buildCandidates() {
        return List.of(
                new StrategyCandidate(FleeStrategy::new,
                        (e, n) -> hasThreat(e, n) ? 100.0 : 0.0),
                new StrategyCandidate(() -> new SeekWaterStrategy(wanderDistance, wanderRadius),
                        (e, n) -> {
                            // Sắp chết khát -> uống là ưu tiên sống còn, thắng cả đi săn (vồ 2.0).
                            if (e.getThirst() >= THIRST_CRITICAL) return THIRST_EMERGENCY_SCORE;
                            // Đang uống dở -> uống cho tới khi hết khát hẳn (chống yo-yo ở mép nước).
                            if (e.getMoveStrategy() instanceof SeekWaterStrategy)
                                return e.getThirst() > THIRST_SATED ? DRINK_COMMIT_SCORE : 0.0;
                            // Chưa uống -> chỉ đi tìm nước khi đã khát đáng kể.
                            return e.getThirst() > THIRST_SEEK_START ? e.getThirst() / 100.0 + 0.2 : 0.0;
                        }),
                new StrategyCandidate(HunterStrategy::new,
                        (e, n) -> {
                            double closeness = nearestPreyCloseness(); // 0..1 theo tầm nhìn, 0 nếu không thấy mồi
                            // Mồi tận miệng -> đớp miếng ăn miễn phí dù chưa đói (vẫn thua bỏ chạy 100).
                            if (closeness >= POUNCE_WHEN_FULL_CLOSENESS) return POUNCE_HUNT_SCORE;
                            // Chưa đói tới ngưỡng -> không chủ động đuổi, kệ thỏ.
                            if (e.getHunger() < HUNT_HUNGER_START) return 0.0;
                            if (closeness <= 0) return 0.0; // đói nhưng không thấy mồi
                            // Đang đói + mồi vào nửa tầm nhìn -> chốt đuổi dứt khoát.
                            if (closeness >= POUNCE_CLOSENESS) return POUNCE_HUNT_SCORE;
                            // Đói + mồi còn xa: chấm theo độ đói + độ sát, "đã trót đuổi thì làm nốt".
                            return Math.max(0.75, e.getHunger() / 100.0) + 0.5 * closeness;
                        }),
                new StrategyCandidate(MateStrategy::new,
                        (e, n) -> canReproduce() && hasMateNearby() ? 0.45 : 0.0),
                new StrategyCandidate(() -> new WanderStrategy(wanderDistance, wanderRadius),
                        (e, n) -> 0.3)
        );
    }

    @Override
    public void update(double dt, WorldMap world) {
        setDrinking(false);
        currentWorld = world;
        neighbors = world.getNeighbors(this, visionRadius);
        if (fixedStrategy != null) {
            moveStrategy = fixedStrategy;          // Q-learning điều khiển trực tiếp
        } else {
            if (candidates == null) candidates = buildCandidates();
            moveStrategy = StrategyCandidate.selectBest(candidates, moveStrategy, dt, this, neighbors);
        }
        if (moveStrategy != null && !isAvoidingBlockedPath()) {
            moveStrategy.updateVelocity(this, neighbors, dt, world);
        }
        super.update(dt, world);
    }

    // Mức độ "sát mồi" của con mồi gần nhất trong tầm nhìn: 1.0 = ngay sát, 0.0 = ở rìa
    // tầm nhìn hoặc không có mồi. Dùng để chấm điểm HunterStrategy theo tiến độ săn.
    private double nearestPreyCloseness() {
        if (neighbors == null) return 0.0;
        double vision = Math.max(getVisionRadius(), 1.0);
        double best = 0.0;
        for (Entity neighbor : neighbors) {
            if (neighbor == null || !RelationManager.isPrey(neighbor.getType(), getType())) continue;
            // Lọc giống HunterStrategy.findClosestPrey: bỏ xác chết và mồi núp trong bụi
            // (sói không vào được) -> tránh chấm điểm cao cho con mồi mà rốt cuộc sẽ không đuổi.
            if (neighbor instanceof Plant plant && !plant.isAlive()) continue;
            if (currentWorld != null
                    && currentWorld.getTerrainAt(neighbor.getPosition()) == TerrainType.BUSH) continue;
            double closeness = 1.0 - getPosition().distance(neighbor.getPosition()) / vision;
            if (closeness > best) best = closeness;
        }
        return best;
    }
}
