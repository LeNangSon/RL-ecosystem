package org.openjfx.app.core.strategies;

import java.util.List;
import java.util.Random;

import org.openjfx.app.core.DeathCause;
import org.openjfx.app.core.RelationManager;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.deeprl.DQNAgent;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.staticobjs.Plant;

/**
 * Chiến lược di chuyển điều khiển bởi Deep Q-Network ({@link DQNAgent}). Khác bản tabular
 * {@link QLearningStrategy} ở chỗ trạng thái là VECTOR LIÊN TỤC (không rời rạc hoá thành
 * chuỗi), nhờ vậy mạng nơ-ron tự khái quát hoá giữa các tình huống gần giống nhau.
 *
 * <p>Vòng đời mỗi bước: dựng vector đặc trưng -> chọn 1 trong 8 hướng (epsilon-greedy theo
 * mạng) -> đặt vận tốc -> tự ăn/uống khi chạm. Phần thưởng tính kiểu "trễ một bước" rồi đẩy
 * chuyển tiếp (s,a,r,s') vào replay buffer và gọi {@code agent.learn()}.</p>
 */
public class DeepQLearningStrategy implements MoveStrategy {

    public enum Role { PREDATOR, PREY }

    public static final int NUM_ACTIONS = 8;            // 8 hướng la bàn
    public static final int NUM_FEATURES = 12;          // kích thước vector trạng thái
    private static final double[][] DIRS = buildDirs();
    private static final double STEERING_GAIN = 4.0;

    private static final double THIRST_HIGH = 60.0;     // khát tới đây -> mục tiêu ưu tiên là nước
    private static final double HUNGER_SEEK = 40.0;     // thỏ chỉ coi cỏ/mồi là mục tiêu khi đã đói

    private final DQNAgent agent;
    private final Role role;
    private double epsilon;
    private final boolean training;
    private final Random rng;

    private double[] prevState;
    private int prevAction = -1;
    private double prevTargetDist = -1;
    private double prevEnemyDist = -1;
    private int prevTargetType = 0;

    private boolean pendingCaught, pendingAte, pendingDrank;

    // Kết quả phụ của encode(), tái dùng cho hàm thưởng.
    private double curTargetDist = -1;
    private double curEnemyDist = -1;
    private int curTargetType = 0;           // 0=không có, 1=mồi/cỏ, 2=nước
    private boolean curThirsty;              // thirst >= THIRST_HIGH ở bước hiện tại

    // Chiến lược luật dùng khi không thấy gì trong tầm nhìn (khởi tạo lười theo chủ thể).
    private MoveStrategy fallbackSeekWater;
    private MoveStrategy fallbackWander;

    public DeepQLearningStrategy(DQNAgent agent, Role role, double epsilon, boolean training, Random rng) {
        this.agent = agent;
        this.role = role;
        this.epsilon = epsilon;
        this.training = training;
        this.rng = rng != null ? rng : new Random();
    }

    /**
     * Tạo agent chỉ để chơi (khai thác mạng đã học, không học thêm). Giữ epsilon nhỏ
     * thay vì 0 để policy tất định không kẹt vòng lặp giữa hai trạng thái.
     */
    public static DeepQLearningStrategy play(DQNAgent agent, Role role) {
        return new DeepQLearningStrategy(agent, role, 0.05, false, new Random());
    }

    public void setEpsilon(double epsilon) { this.epsilon = epsilon; }

    @Override
    public void updateVelocity(LivingEntity owner, List<Entity> neighbors, double dt, WorldMap world) {
        if (!owner.isAlive()) return;

        double[] state = encode(owner, neighbors, world);   // đặt curTargetDist, curEnemyDist

        if (training && prevState != null) {
            double reward = stepReward();
            agent.remember(prevState, prevAction, reward, state, false);
            agent.learn(rng);
        }

        // Không thấy mục tiêu lẫn kẻ thù -> giao cho luật (tìm nước lúc khát / lang thang),
        // RL chỉ học pha có thông tin. Ngắt chuỗi học để bước RL kế tiếp bắt đầu mới.
        if (curTargetType == 0 && curEnemyDist < 0) {
            fallback(owner, curThirsty).updateVelocity(owner, neighbors, dt, world);
            prevState = null;
            prevAction = -1;
            prevTargetDist = -1;
            prevEnemyDist = -1;
            prevTargetType = 0;
            pendingCaught = false; pendingAte = false; pendingDrank = false;
            return;
        }

        int action = agent.selectAction(state, epsilon, rng);
        applyAction(owner, action, dt);

        pendingCaught = false; pendingAte = false; pendingDrank = false;
        autoInteract(owner, neighbors, world, dt);

        prevState = state;
        prevAction = action;
        prevTargetDist = curTargetDist;
        prevEnemyDist = curEnemyDist;
        prevTargetType = curTargetType;
    }

    /** Chiến lược luật dùng khi "mù": SeekWater lúc khát, Wander lúc bình thường. */
    private MoveStrategy fallback(LivingEntity owner, boolean thirsty) {
        if (thirsty) {
            if (fallbackSeekWater == null) {
                fallbackSeekWater = new SeekWaterStrategy(owner.getWanderDistance(), owner.getWanderRadius());
            }
            return fallbackSeekWater;
        }
        if (fallbackWander == null) {
            fallbackWander = new WanderStrategy(owner.getWanderDistance(), owner.getWanderRadius());
        }
        return fallbackWander;
    }

    /** Gọi khi cá thể chết: đẩy chuyển tiếp kết thúc với phần thưởng âm. */
    public void learnTerminal() {
        if (!training || prevState == null || prevAction < 0) return;
        // Chết phải đắt hơn hẳn một lần bắt mồi (+10), nếu không sói học "liều ăn nhiều".
        agent.remember(prevState, prevAction, -10.0, null, true);
        agent.learn(rng);
        prevState = null;
        prevAction = -1;
    }

    // ----------------------------------------------------------------- phần thưởng
    private double stepReward() {
        double r;
        if (role == Role.PREDATOR) {
            r = -0.02;
            if (pendingCaught) r += 10.0;
            if (pendingDrank) r += 4;         // uống nước cũng là sống còn (chết khát = -10)
        } else {
            r = 0.05;
            if (pendingAte) r += 1.0;
            if (pendingDrank) r += 4;
        }
        // Chỉ shaping khi mục tiêu hai bước cùng loại: đổi loại (mồi -> nước lúc khát)
        // làm hiệu khoảng cách vô nghĩa.
        if (prevTargetType == curTargetType && prevTargetDist >= 0 && curTargetDist >= 0) {
            r += 0.05 * (prevTargetDist - curTargetDist);
        }
        if (role == Role.PREY && prevEnemyDist >= 0 && curEnemyDist >= 0) {
            r += 0.06 * (curEnemyDist - prevEnemyDist);
        }
        return r;
    }

    // -------------------------------------------------------------- dựng vector trạng thái
    private double[] encode(LivingEntity owner, List<Entity> neighbors, WorldMap world) {
        Vector2D pos = owner.getPosition();
        double vision = Math.max(owner.getVisionRadius(), 1.0);

        Vector2D enemy = nearestScaredOf(owner, neighbors);
        curEnemyDist = enemy == null ? -1 : pos.distance(enemy);

        curThirsty = owner.getThirst() >= THIRST_HIGH;

        Vector2D target;
        boolean targetWater;
        if (curThirsty) {
            target = world.findNearestTerrainPositionInRadius(pos, TerrainType.WATER, vision);
            targetWater = target != null;
        } else {
            Entity prey = nearestPreyEntity(owner, neighbors, world);
            boolean wantPrey = role == Role.PREDATOR || owner.getHunger() >= HUNGER_SEEK;
            target = (prey != null && wantPrey) ? prey.getPosition() : null;
            targetWater = false;
        }
        curTargetType = target == null ? 0 : (targetWater ? 2 : 1);
        curTargetDist = target == null ? -1 : pos.distance(target);

        double[] f = new double[NUM_FEATURES];
        f[0] = owner.getHunger() / 100.0;
        f[1] = owner.getThirst() / 100.0;
        if (enemy != null) {
            Vector2D d = enemy.sub(pos);
            double dist = Math.max(d.magnitude(), 1e-6);
            f[2] = 1.0;
            f[3] = d.x / dist;
            f[4] = d.y / dist;
            f[5] = Math.min(curEnemyDist / vision, 1.0);
        } else {
            f[5] = 1.0;             // "không có địch" coi như ở xa nhất
        }
        if (target != null) {
            Vector2D d = target.sub(pos);
            double dist = Math.max(d.magnitude(), 1e-6);
            f[6] = 1.0;
            f[7] = targetWater ? 1.0 : 0.0;
            f[8] = d.x / dist;
            f[9] = d.y / dist;
            f[10] = Math.min(curTargetDist / vision, 1.0);
        } else {
            f[10] = 1.0;
        }
        f[11] = Math.min(owner.getVelocity().magnitude() / Math.max(owner.getMaxSpeed(), 1e-6), 1.0);
        return f;
    }

    // ----------------------------------------------------------------- hành động
    private void applyAction(LivingEntity owner, int action, double dt) {
        Vector2D dir = new Vector2D(DIRS[action][0], DIRS[action][1]);
        Vector2D desired = dir.multiply(owner.getMaxSpeed());
        Vector2D steering = desired.sub(owner.getVelocity());
        Vector2D accel = steering.multiply(STEERING_GAIN).limit(owner.getMaxForce());
        Vector2D v = owner.getVelocity().add(accel.multiply(dt));
        if (v.magnitude() > 1e-6) v = v.normalize().multiply(owner.getMaxSpeed());
        owner.setAcceleration(accel);
        owner.setVelocity(v);
    }

    // ----------------------------------------------------- tương tác khi chạm (cơ chế thế giới)
    private void autoInteract(LivingEntity owner, List<Entity> neighbors, WorldMap world, double dt) {
        Entity prey = nearestPreyEntity(owner, neighbors, world);
        if (prey != null
                && owner.getPosition().distance(prey.getPosition()) < world.getInteractionDistance(owner, prey)) {
            boolean wasAlive = prey instanceof LivingEntity le && le.isAlive();
            double hungerBefore = owner.getHunger();
            owner.eat(prey, dt);
            if (role == Role.PREDATOR) {
                if (wasAlive && prey instanceof LivingEntity le2 && !le2.isAlive()) {
                    world.recordDeath(prey.getType(), DeathCause.PREDATION);
                    pendingCaught = true;
                }
            } else if (owner.getHunger() < hungerBefore) {
                pendingAte = true;
            }
        }

        if (owner.getThirst() > 5.0) {
            double drinkRange = Math.max(10.0, owner.getSize() * 0.5);
            if (world.findNearestTerrainPositionInRadius(owner.getPosition(), TerrainType.WATER, drinkRange) != null) {
                owner.drink(dt);
                pendingDrank = true;
            }
        }
    }

    private Entity nearestPreyEntity(LivingEntity owner, List<Entity> neighbors, WorldMap world) {
        Entity best = null;
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

    private static double[][] buildDirs() {
        double[][] dirs = new double[NUM_ACTIONS][2];
        for (int i = 0; i < NUM_ACTIONS; i++) {
            double a = i * Math.PI / 4;
            dirs[i][0] = Math.cos(a);
            dirs[i][1] = Math.sin(a);
        }
        return dirs;
    }
}
