package org.openjfx.app.core.strategies;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;

public class StrategyCandidate {
    // Bonus "cam kết" cho strategy đang chạy: cao ngay sau khi đổi việc (chống giật/
    // nhảy qua lại giữa 2 strategy điểm xấp xỉ), rồi loãng dần theo thời gian. Nhờ vậy
    // việc vừa bắt đầu được giữ ổn định, nhưng nếu kéo dài thì vẫn nhường được cho nhu
    // cầu cấp thiết hơn -> không khóa cứng kiểu "đang làm gì phải làm cho xong".
    private static final double COMMIT_BONUS = 0.25;
    private static final double COMMIT_DECAY_SECONDS = 2.0;

    private final Supplier<MoveStrategy> factory;
    private final BiFunction<LivingEntity, List<Entity>, Double> scorer;
    private MoveStrategy instance;
    private double activeSeconds;

    public StrategyCandidate(Supplier<MoveStrategy> factory,
                             BiFunction<LivingEntity, List<Entity>, Double> scorer) {
        this.factory = factory;
        this.scorer = scorer;
    }

    public MoveStrategy getStrategy() {
        if (instance == null) instance = factory.get();
        return instance;
    }

    public static MoveStrategy selectBest(List<StrategyCandidate> candidates,
                                          MoveStrategy current,
                                          double dt,
                                          LivingEntity entity,
                                          List<Entity> neighbors) {
        StrategyCandidate winner = null;
        double winnerScore = Double.NEGATIVE_INFINITY;
        for (StrategyCandidate c : candidates) {
            double score = c.scorer.apply(entity, neighbors);
            if (c.instance != null && c.instance == current) {
                c.activeSeconds += dt;
                score += COMMIT_BONUS * Math.exp(-c.activeSeconds / COMMIT_DECAY_SECONDS);
            }
            if (score > winnerScore) {
                winnerScore = score;
                winner = c;
            }
        }
        if (winner == null) return current;
        if (winner.instance != current) winner.activeSeconds = 0.0; // bắt đầu việc mới -> reset đồng hồ cam kết
        return winner.getStrategy();
    }
}