package org.openjfx.app.entities.base;

import java.util.List;

import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.strategies.FleeStrategy;
import org.openjfx.app.core.strategies.HunterStrategy;
import org.openjfx.app.core.strategies.MateStrategy;
import org.openjfx.app.core.strategies.SeekWaterStrategy;
import org.openjfx.app.core.strategies.StrategyCandidate;
import org.openjfx.app.core.strategies.WanderStrategy;
import org.openjfx.app.entities.staticobjs.Plant;

public abstract class Herbivore extends LivingEntity {

    private List<StrategyCandidate> candidates;

    public Herbivore(Vector2D position, double size, String shape, double initialHealth, double hungerRate, double thirstRate,
                     double maxSpeed, double maxForce, double mass,
                     double wanderDistance, double wanderRadius) {
        super(position, size, shape, initialHealth, hungerRate, thirstRate, maxSpeed, maxForce, mass, wanderDistance, wanderRadius);
    }

    @Override
    public void eat(Entity target, double dt) {
        if (target instanceof Plant plant) {
            setHunger(getHunger() - plant.consume());
        }
    }

    private List<StrategyCandidate> buildCandidates() {
        return List.of(
                new StrategyCandidate(FleeStrategy::new,
                        (e, n) -> hasThreat(e, n) ? 100.0 : 0.0),
                new StrategyCandidate(() -> new SeekWaterStrategy(wanderDistance, wanderRadius),
                        (e, n) -> {
                            // Sắp chết khát -> uống là ưu tiên sống còn, vượt mọi nhu cầu khác.
                            if (e.getThirst() >= THIRST_CRITICAL) return THIRST_EMERGENCY_SCORE;
                            // Đang uống dở -> uống cho tới khi hết khát hẳn (chống yo-yo ở mép nước).
                            if (e.getMoveStrategy() instanceof SeekWaterStrategy)
                                return e.getThirst() > THIRST_SATED ? DRINK_COMMIT_SCORE : 0.0;
                            // Chưa uống -> chỉ đi tìm nước khi đã khát đáng kể.
                            return e.getThirst() > THIRST_SEEK_START ? e.getThirst() / 100.0 + 0.2 : 0.0;
                        }),
                new StrategyCandidate(HunterStrategy::new,
                        (e, n) -> {
                            if (e.getHunger() < 5.0) return 0.0; // no bị kẹt tìm ăn khi no
                            if (e.getHunger() > 60.0 || e.getMoveStrategy() instanceof HunterStrategy)
                                return e.getHunger() / 100.0;
                            return 0.0;
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
}
