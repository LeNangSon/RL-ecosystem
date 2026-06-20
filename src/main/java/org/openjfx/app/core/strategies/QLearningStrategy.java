package org.openjfx.app.core.strategies;

import java.util.List;
import java.util.Random;

import org.openjfx.app.core.RelationManager;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.qlearning.QTable;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.staticobjs.Plant;

/**
 * Chiến lược di chuyển do Q-learning điều khiển — phiên bản <b>HIERARCHICAL / OPTIONS</b>
 * (rl_v03): RL KHÔNG còn lái từng vector 8 hướng nữa, mà học <i>chọn macro-action</i> = chọn
 * 1 trong {@value #NUM_ACTIONS} chiến lược luật, rồi chiến lược đó tự lo điều hướng (A*) và
 * ăn/uống/đẻ. Nhờ vậy RL và RBS dùng CHUNG bộ primitive + CHUNG A*; chỉ khác <b>bộ chọn</b>:
 * RBS chấm điểm tay ({@link StrategyCandidate#selectBest}), RL học bằng Q-table.
 *
 * <p>Action ↔ strategy: 0=Flee, 1=SeekWater, 2=Hunter, 3=Mate, 4=Wander. Việc ăn/uống/đẻ
 * nằm sẵn trong các strategy đó (xem {@link HunterStrategy}, {@link SeekWaterStrategy},
 * {@link MateStrategy}) nên KHÔNG cần autoInteract như bản 8-hướng cũ; sói bắt được mồi vẫn
 * được {@link HunterStrategy} ghi {@code recordDeath} nên đếm catch không đổi.</p>
 *
 * <p>Phần thưởng (caught/ate/drank) phát hiện qua DELTA hunger/thirst sau khi ủy quyền: sói
 * giết mồi -> {@code Carnivore.eat} đặt hunger=0 (drop lớn); thỏ ăn cỏ -> hunger giảm; uống
 * -> thirst giảm. Reward giữ "trễ một bước" như cũ.</p>
 */
public class QLearningStrategy implements MoveStrategy {

    public enum Role { PREDATOR, PREY }

    // Macro-action = chọn chiến lược. Index PHẢI khớp options():
    //   0 Flee, 1 SeekWater, 2 Mate, 3 Wander,
    //   4 Hunt-direct (lead 0, lao thẳng), 5 Hunt-short (lead 0.7 ~ RBS),
    //   6 Hunt-long (lead 1.3, cắt góc), 7 Hunt-ambush (lead 2.0, đón sâu).
    // 4 kiểu săn = SUPERSET của RBS (RBS chỉ dùng lead 0.7 cố định) -> trần RL > trần RBS.
    public static final int NUM_ACTIONS = 8;
    private static final double[] HUNT_LEADS = {0.0, HunterStrategy.DEFAULT_LEAD_TIME, 1.3, 2.0};

    private static final double THIRST_HIGH = 60.0;     // khát tới đây -> mục tiêu ưu tiên là nước
    private static final double THIRST_SATED = 25.0;    // hysteresis: uống tới khi tụt dưới mức này
    private static final double HUNGER_SEEK = 60.0;     // thỏ coi cỏ là mục tiêu khi đói > ngưỡng này
    private static final double HUNT_HUNGER = 50.0;     // sói coi mồi là mục tiêu khi đói > ngưỡng này
    private static final double THIRST_PENALTY = 0.2;   // phạt/bước tối đa khi khát chạm 100 (vùng >THIRST_HIGH)

    private final QTable q;
    private final Role role;
    private final double alpha;
    private final double gamma;
    private double epsilon;
    private final boolean training;
    private final Random rng;

    // 5 strategy luật, khởi tạo lười theo owner (SeekWater/Wander cần wanderDistance/radius).
    private MoveStrategy[] options;

    // Bộ nhớ chuyển trạng thái (s, a) của bước trước để cập nhật Q ở bước sau.
    private String prevState;
    private int prevAction = -1;
    private double prevTargetDist = -1;
    private double prevEnemyDist = -1;
    private int prevTargetType = 0;

    // Cờ sự kiện do bước trước đặt (qua delta hunger/thirst), dùng cho phần thưởng bước sau.
    private boolean pendingCaught;
    private boolean pendingAte;
    private boolean pendingDrank;

    // true = đang trong "chế độ uống" (hysteresis 2 mức THIRST_HIGH/THIRST_SATED).
    private boolean thirstCommit;

    // Kết quả phụ của encode(), tái dùng cho hàm thưởng.
    private double curTargetDist = -1;
    private double curEnemyDist = -1;
    private double curVision = 1.0;
    private int curTargetType = 0;
    private boolean curThirsty;
    private double curThirstLevel;            // mức khát hiện tại (cho phạt khát)

    public QLearningStrategy(QTable q, Role role, double alpha, double gamma,
                             double epsilon, boolean training, Random rng) {
        this.q = q;
        this.role = role;
        this.alpha = alpha;
        this.gamma = gamma;
        this.epsilon = epsilon;
        this.training = training;
        this.rng = rng != null ? rng : new Random();
    }

    /**
     * Tạo agent chỉ để chơi (khai thác bảng đã học, không cập nhật Q). Giữ epsilon nhỏ
     * thay vì 0: policy tham lam thuần tất định có thể kẹt vòng lặp; 5% bước ngẫu nhiên đủ
     * phá kẹt mà không làm hỏng hành vi đã học.
     */
    public static QLearningStrategy play(QTable q, Role role) {
        return play(q, role, new Random());
    }

    /**
     * Như {@link #play(QTable, Role)} nhưng nhận RNG có seed cho 5% bước epsilon. Dùng ở
     * harness so sánh (BrainCompare) để chạy lại CÙNG seed ra CÙNG kết quả.
     */
    public static QLearningStrategy play(QTable q, Role role, Random rng) {
        // epsilon = 0 lúc đánh giá: RBS tất định nên RL cũng phải tất định mới công bằng
        // (bỏ handicap 5% bước ngẫu nhiên). Macro-action ít kẹt vòng lặp hơn 8-hướng cũ.
        return new QLearningStrategy(q, role, 0.0, 0.97, 0.0, false, rng);
    }

    public void setEpsilon(double epsilon) { this.epsilon = epsilon; }

    @Override
    public void updateVelocity(LivingEntity owner, List<Entity> neighbors, double dt, WorldMap world) {
        if (!owner.isAlive()) return;

        String state = encode(owner, neighbors, world);   // đặt cur* fields

        // Học từ chuyển trạng thái của bước trước (giờ đã thấy hệ quả).
        if (training && prevState != null) {
            double reward = stepReward();
            q.update(prevState, prevAction, reward, state, alpha, gamma);
        }

        // Chọn macro-action rồi ỦY QUYỀN cho strategy luật (nó tự A* + ăn/uống/đẻ).
        int action = q.selectAction(state, epsilon, rng);
        double hungerBefore = owner.getHunger();
        double thirstBefore = owner.getThirst();
        options(owner)[action].updateVelocity(owner, neighbors, dt, world);

        // Phát hiện sự kiện qua delta (đặt cờ cho phần thưởng của bước kế tiếp).
        double hungerDrop = hungerBefore - owner.getHunger();
        double thirstDrop = thirstBefore - owner.getThirst();
        pendingCaught = role == Role.PREDATOR && hungerDrop > 1.0;   // Carnivore.eat đặt hunger=0 khi giết
        pendingAte    = role == Role.PREY && hungerDrop > 0.01;
        pendingDrank  = thirstDrop > 0.01;

        prevState = state;
        prevAction = action;
        prevTargetDist = curTargetDist;
        prevEnemyDist = curEnemyDist;
        prevTargetType = curTargetType;
    }

    // 8 strategy luật (khởi tạo lười, giữ state nội bộ qua các bước như RBS).
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

    /** Gọi khi cá thể chết (trạng thái kết thúc): áp phần thưởng âm cho hành động cuối. */
    public void learnTerminal() {
        if (!training || prevState == null || prevAction < 0) return;
        // Chết phải đắt hơn hẳn một lần bắt mồi (+10), nếu không sói học "liều ăn nhiều".
        q.update(prevState, prevAction, -10.0, null, alpha, gamma);
        prevState = null;
        prevAction = -1;
    }

    // ----------------------------------------------------------------- phần thưởng (OUTCOME-BASED)
    // Thiết kế cho tầng macro-action: thưởng theo KẾT QUẢ của option (bắt/ăn/uống/sống), KHÔNG
    // dùng shaping khoảng cách dày như bản 8-hướng. Shaping cũ (+0.05·Δdist, +0.4 áp sát,
    // -0.5 mất dấu) phạt oan các kiểu săn trễ (ambush/lead-long) vì chúng tạm thời không tiến
    // gần -> RL không dám chọn -> không khai thác được superset action. Thay bằng thưởng
    // tiến-gần BẤT ĐỐI XỨNG: thưởng khi lại gần, KHÔNG phạt khi tạm lùi (đón đầu/mai phục).
    private double stepReward() {
        double r;
        if (role == Role.PREDATOR) {
            r = -0.02;                          // sức ép thời gian: bắt nhanh thì lời
            if (pendingCaught) r += 10.0;       // KẾT QUẢ: bắt được mồi
        } else {
            r = 0.05;                           // còn sống thêm 1 bước
            if (pendingAte) r += 1.0;
        }
        // PHẠT KHÁT (thay thưởng uống): chỉ trong vùng nguy hiểm (> THIRST_HIGH), tăng tuyến
        // tính tới khi chết khát. KHÔNG farm được như thưởng uống/bước -> sói chỉ uống đủ để
        // tránh phạt rồi quay lại săn. Vùng <THIRST_HIGH không phạt -> săn bình thường không bị động.
        if (curThirstLevel > THIRST_HIGH) {
            r -= THIRST_PENALTY * (curThirstLevel - THIRST_HIGH) / (100.0 - THIRST_HIGH);
        }
        // Thưởng tiến-gần mục tiêu (chỉ khi cùng loại mục tiêu 2 bước) — BẤT ĐỐI XỨNG.
        if (prevTargetType == curTargetType && prevTargetDist >= 0 && curTargetDist >= 0) {
            double closed = prevTargetDist - curTargetDist;
            if (closed > 0) r += 0.03 * closed;     // chỉ thưởng khi lại gần; lùi -> 0 (không phạt)
        }
        // Thỏ: thưởng khi tăng khoảng cách với sói (giữ — chạy trốn không phải tactic trễ).
        if (role == Role.PREY && prevEnemyDist >= 0 && curEnemyDist >= 0) {
            r += 0.06 * (curEnemyDist - prevEnemyDist);
        }
        return r;
    }

    // -------------------------------------------------------------- rời rạc hoá trạng thái
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
        Entity preyEntity = null;          // giữ lại để đọc vận tốc mồi (đón đầu)
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
        // Mới ở rl_v03: bit "có thể đẻ + có bạn tình kề bên" để RL học khi nào chọn Mate.
        int mateBin = (owner.canReproduce() && owner.hasMateNearby()) ? 1 : 0;

        return (role == Role.PREDATOR ? "P" : "R")
                + "|e" + enemyDir + ',' + enemyBin
                + "|t" + targetType + ',' + targetDir + ',' + targetBin
                + "|m" + preyMoveDir
                + "|h" + hungerBin
                + "|w" + thirstBin
                + "|p" + mateBin;
    }

    /** Sector hướng di chuyển của mồi (0-7); 8 nếu mồi đứng yên hoặc không phải vật thể động. */
    private int preyMovementSector(Entity prey) {
        if (prey instanceof LivingEntity le) {
            Vector2D v = le.getVelocity();
            if (v != null && v.magnitude() > 1.0) return sector(v);
        }
        return 8;
    }

    private int sector(Vector2D d) {
        double angle = Math.atan2(d.y, d.x);              // -pi..pi
        int s = (int) Math.round(angle / (Math.PI / 4));  // -4..4
        return ((s % 8) + 8) % 8;                          // 0..7
    }

    private int distBin(double dist, double vision) {
        if (dist < 0) return 0;                            // không có
        if (dist < vision / 3) return 1;                   // gần
        if (dist < 2 * vision / 3) return 2;               // vừa
        return 3;                                          // xa
    }

    private Entity nearestPreyEntity(LivingEntity owner, List<Entity> neighbors, WorldMap world) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity n : neighbors) {
            if (n == null || !RelationManager.isPrey(n.getType(), owner.getType())) continue;
            if (n instanceof Plant p && !p.isAlive()) continue;
            if (world.getTerrainAt(n.getPosition()) == TerrainType.BUSH) continue;  // mồi núp trong bụi
            double d = owner.getPosition().distance(n.getPosition());
            if (d < bestDist) { bestDist = d; best = n; }
        }
        return best;
    }

    private Vector2D nearestScaredOf(LivingEntity owner, List<Entity> neighbors) {
        Vector2D best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity n : neighbors) {
            if (n == null || !RelationManager.isScaredOf(owner.getType(), n.getType())) continue;
            double d = owner.getPosition().distance(n.getPosition());
            if (d < bestDist) { bestDist = d; best = n.getPosition(); }
        }
        return best;
    }
}
