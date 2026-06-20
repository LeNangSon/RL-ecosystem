package org.openjfx.app.core.strategies;

import java.util.List;
import java.util.Random;

import org.openjfx.app.core.RelationManager;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.montecarlo.MonteCarloAgent;
import org.openjfx.app.core.qlearning.QTable;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.staticobjs.Plant;

/**
 * Chiến lược di chuyển dùng Monte Carlo Control — phiên bản <b>HIERARCHICAL / OPTIONS</b>
 * (rl_v03), song song với {@link QLearningStrategy}: RL học <i>chọn macro-action</i> = chọn
 * 1 trong {@value #NUM_ACTIONS} chiến lược luật (0=Flee,1=SeekWater,2=Hunter,3=Mate,4=Wander),
 * mỗi strategy tự lo A* + ăn/uống/đẻ. Khác QL ở chỗ ghi trajectory rồi cập nhật một lần cuối
 * episode ({@link MonteCarloAgent}) thay vì update mỗi bước.</p>
 */
public class MonteCarloStrategy implements MoveStrategy {

    public enum Role { PREDATOR, PREY }

    // Macro-action = chọn chiến lược. Index khớp options():
    //   0 Flee, 1 SeekWater, 2 Mate, 3 Wander, 4 Hunt-direct, 5 Hunt-short(~RBS), 6 Hunt-long, 7 Hunt-ambush.
    public static final int NUM_ACTIONS = 8;
    private static final double[] HUNT_LEADS = {0.0, HunterStrategy.DEFAULT_LEAD_TIME, 1.3, 2.0};

    private static final double THIRST_HIGH  = 60.0;
    private static final double THIRST_SATED = 25.0;
    private static final double HUNGER_SEEK  = 60.0;   // thỏ: tìm cỏ khi đói > ngưỡng này
    private static final double HUNT_HUNGER  = 50.0;   // sói: săn khi đói > ngưỡng này
    private static final double THIRST_PENALTY = 0.2;  // phạt/bước tối đa khi khát chạm 100 (vùng >THIRST_HIGH)

    private final QTable         q;
    private final Role           role;
    private final double         alpha;
    private final double         gamma;
    private       double         epsilon;
    private final boolean        training;
    private final Random         rng;
    private final MonteCarloAgent agent;

    // 5 strategy luật, khởi tạo lười theo owner.
    private MoveStrategy[] options;

    // Trạng thái bước trước — cần để tính reward ở bước sau (1-step lag, giống QL).
    private String  prevState;
    private int     prevAction     = -1;
    private double  prevTargetDist = -1;
    private double  prevEnemyDist  = -1;
    private int     prevTargetType = 0;

    // Cờ sự kiện (đặt qua delta hunger/thirst) — dùng trong stepReward().
    private boolean pendingCaught;
    private boolean pendingAte;
    private boolean pendingDrank;

    // Side-effects của encode().
    private boolean thirstCommit;
    private double  curTargetDist = -1;
    private double  curEnemyDist  = -1;
    private double  curVision     = 1.0;
    private int     curTargetType = 0;
    private boolean curThirsty;
    private double  curThirstLevel;            // mức khát hiện tại (cho phạt khát)

    public MonteCarloStrategy(QTable q, Role role, double alpha, double gamma,
                              double epsilon, boolean training, Random rng) {
        this.q        = q;
        this.role     = role;
        this.alpha    = alpha;
        this.gamma    = gamma;
        this.epsilon  = epsilon;
        this.training = training;
        this.rng      = rng != null ? rng : new Random();
        this.agent    = new MonteCarloAgent();
    }

    /** Factory: play-only (không học), epsilon nhỏ để tránh kẹt vòng lặp tất định. */
    public static MonteCarloStrategy play(QTable q, Role role) {
        return play(q, role, new Random());
    }

    /**
     * Như {@link #play(QTable, Role)} nhưng nhận RNG có seed cho 5% bước epsilon. Dùng ở
     * harness so sánh (BrainCompare) để cùng seed ra cùng kết quả (so theo cặp tái lập được).
     */
    public static MonteCarloStrategy play(QTable q, Role role, Random rng) {
        // epsilon = 0 lúc đánh giá để công bằng với RBS tất định (bỏ handicap 5% ngẫu nhiên).
        return new MonteCarloStrategy(q, role, 0.0, 0.97, 0.0, false, rng);
    }

    public void setEpsilon(double epsilon) { this.epsilon = epsilon; }

    // ================================================================= main loop

    @Override
    public void updateVelocity(LivingEntity owner, List<Entity> neighbors, double dt, WorldMap world) {
        if (!owner.isAlive()) return;

        String state = encode(owner, neighbors, world);  // đặt cur* fields

        // Điền reward cho (prevState, prevAction) vào buffer — MC không update Q ngay.
        if (training && prevState != null) {
            agent.setLastReward(stepReward());
        }

        int action = q.selectAction(state, epsilon, rng);
        double hungerBefore = owner.getHunger();
        double thirstBefore = owner.getThirst();
        options(owner)[action].updateVelocity(owner, neighbors, dt, world);

        if (training) agent.record(state, action);

        double hungerDrop = hungerBefore - owner.getHunger();
        double thirstDrop = thirstBefore - owner.getThirst();
        pendingCaught = role == Role.PREDATOR && hungerDrop > 1.0;
        pendingAte    = role == Role.PREY && hungerDrop > 0.01;
        pendingDrank  = thirstDrop > 0.01;

        prevState      = state;
        prevAction     = action;
        prevTargetDist = curTargetDist;
        prevEnemyDist  = curEnemyDist;
        prevTargetType = curTargetType;
    }

    private MoveStrategy[] options(LivingEntity owner) {
        if (options == null) {
            options = new MoveStrategy[] {
                    new FleeStrategy(),                                                          // 0
                    new SeekWaterStrategy(owner.getWanderDistance(), owner.getWanderRadius()),   // 1
                    new MateStrategy(),                                                          // 2
                    new WanderStrategy(owner.getWanderDistance(), owner.getWanderRadius()),      // 3
                    new HunterStrategy(HUNT_LEADS[0]),                                           // 4 direct
                    new HunterStrategy(HUNT_LEADS[1]),                                           // 5 short ~ RBS
                    new HunterStrategy(HUNT_LEADS[2]),                                           // 6 long
                    new HunterStrategy(HUNT_LEADS[3]),                                           // 7 ambush
            };
        }
        return options;
    }

    // ================================================================= kết thúc episode

    /** Entity chết: reward terminal -10, finishEpisode. Gọi từ trainer khi entity hết alive. */
    public void learnTerminal() {
        if (!training) return;
        if (prevState != null) agent.setLastReward(-10.0);
        agent.finishEpisode(q, gamma, alpha);
        resetPrev();
    }

    /** Episode hết maxSteps mà entity còn sống: cập nhật Q theo trajectory, reward cuối = 0. */
    public void learnEpisodeEnd() {
        if (!training) return;
        if (prevState != null) agent.setLastReward(0.0);
        agent.finishEpisode(q, gamma, alpha);
        resetPrev();
    }

    // ================================================================= reward

    // OUTCOME-BASED (xem giải thích ở QLearningStrategy.stepReward): thưởng theo kết quả option,
    // tiến-gần bất đối xứng (không phạt lùi) để ambush/lead-long không bị trừng phạt.
    private double stepReward() {
        double r;
        if (role == Role.PREDATOR) {
            r = -0.02;
            if (pendingCaught) r += 10.0;
        } else {
            r = 0.05;
            if (pendingAte) r += 1.0;
        }
        // PHẠT KHÁT (xem QLearningStrategy.stepReward): chỉ vùng >THIRST_HIGH, không farm được.
        if (curThirstLevel > THIRST_HIGH) {
            r -= THIRST_PENALTY * (curThirstLevel - THIRST_HIGH) / (100.0 - THIRST_HIGH);
        }
        if (prevTargetType == curTargetType && prevTargetDist >= 0 && curTargetDist >= 0) {
            double closed = prevTargetDist - curTargetDist;
            if (closed > 0) r += 0.03 * closed;
        }
        if (role == Role.PREY && prevEnemyDist >= 0 && curEnemyDist >= 0) {
            r += 0.06 * (curEnemyDist - prevEnemyDist);
        }
        return r;
    }

    // ================================================================= encode

    private String encode(LivingEntity owner, List<Entity> neighbors, WorldMap world) {
        Vector2D pos = owner.getPosition();
        double vision = Math.max(owner.getVisionRadius(), 1.0);
        curVision = vision;

        Vector2D enemy = nearestScaredOf(owner, neighbors);
        curEnemyDist = enemy == null ? -1 : pos.distance(enemy);

        curThirstLevel = owner.getThirst();
        thirstCommit = thirstCommit
                ? owner.getThirst() > THIRST_SATED
                : owner.getThirst() >= THIRST_HIGH;
        curThirsty = thirstCommit;

        int targetType;
        Vector2D target;
        Entity preyEntity = null;
        if (curThirsty) {
            target = world.findNearestTerrainPositionInRadius(pos, TerrainType.WATER, vision);
            targetType = target == null ? 0 : 2;
        } else {
            Entity prey = nearestPreyEntity(owner, neighbors, world);
            boolean wantPrey = role == Role.PREDATOR
                    ? owner.getHunger() >= HUNT_HUNGER
                    : owner.getHunger() >= HUNGER_SEEK;
            if (prey != null && wantPrey) {
                target = prey.getPosition();
                targetType = 1;
                preyEntity = prey;
            } else {
                target = null;
                targetType = 0;
            }
        }
        curTargetType = targetType;
        curTargetDist = target == null ? -1 : pos.distance(target);

        int enemyDir = enemy == null ? 8 : sector(enemy.sub(pos));
        int enemyBin = distBin(curEnemyDist, vision);
        int targetDir = target == null ? 8 : sector(target.sub(pos));
        int targetBin = distBin(curTargetDist, vision);
        int hungerBin = owner.getHunger() >= 50 ? 1 : 0;
        int thirstBin = curThirsty ? 1 : 0;
        int preyMoveDir = preyMovementSector(preyEntity);
        int mateBin = (owner.canReproduce() && owner.hasMateNearby()) ? 1 : 0;

        return (role == Role.PREDATOR ? "P" : "R")
                + "|e" + enemyDir + ',' + enemyBin
                + "|t" + targetType + ',' + targetDir + ',' + targetBin
                + "|m" + preyMoveDir
                + "|h" + hungerBin
                + "|w" + thirstBin
                + "|p" + mateBin;
    }

    // ================================================================= helpers

    private void resetPrev() {
        prevState      = null;
        prevAction     = -1;
        prevTargetDist = -1;
        prevEnemyDist  = -1;
        prevTargetType = 0;
        pendingCaught  = false;
        pendingAte     = false;
        pendingDrank   = false;
    }

    protected Vector2D nearestScaredOf(LivingEntity owner, List<Entity> neighbors) {
        Vector2D best     = null;
        double   bestDist = Double.MAX_VALUE;
        for (Entity n : neighbors) {
            if (n == null || !RelationManager.isScaredOf(owner.getType(), n.getType())) continue;
            double d = owner.getPosition().distance(n.getPosition());
            if (d < bestDist) { bestDist = d; best = n.getPosition(); }
        }
        return best;
    }

    protected Entity nearestPreyEntity(LivingEntity owner, List<Entity> neighbors, WorldMap world) {
        Entity best     = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity n : neighbors) {
            if (n == null || !RelationManager.isPrey(n.getType(), owner.getType())) continue;
            if (n instanceof Plant p && !p.isAlive()) continue;
            if (world.getTerrainAt(n.getPosition()) == TerrainType.BUSH) continue;
            double d = owner.getPosition().distance(n.getPosition());
            if (d < bestDist) { bestDist = d; best = n; }
        }
        return best;
    }

    protected int preyMovementSector(Entity prey) {
        if (prey instanceof LivingEntity le) {
            Vector2D v = le.getVelocity();
            if (v != null && v.magnitude() > 1.0) return sector(v);
        }
        return 8;
    }

    protected int sector(Vector2D d) {
        double angle = Math.atan2(d.y, d.x);
        int s = (int) Math.round(angle / (Math.PI / 4));
        return ((s % 8) + 8) % 8;
    }

    protected int distBin(double dist, double vision) {
        if (dist < 0) return 0;
        if (dist < vision / 3)       return 1;
        if (dist < 2 * vision / 3)   return 2;
        return 3;
    }

    // ----------------------------------------------------------------- constants exposed
    public static double getThirstHigh()  { return THIRST_HIGH;  }
    public static double getThirstSated() { return THIRST_SATED; }
    public static double getHungerSeek()  { return HUNGER_SEEK;  }
    public static double getHuntHunger()  { return HUNT_HUNGER;  }
}
