package org.openjfx.app.core.qlearning;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.openjfx.app.core.DeathCause;
import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.strategies.QLearningStrategy;
import org.openjfx.app.core.strategies.QLearningStrategy.Role;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.movable.Rabbit;
import org.openjfx.app.entities.movable.Wolf;
import org.openjfx.app.entities.staticobjs.Grass;

/**
 * Bộ huấn luyện Q-learning chạy HEADLESS (không cần JavaFX UI) cho SÓI và THỎ.
 *
 * <p>Tái dùng {@link WorldMap} thật (địa hình lấy từ {@code all.tmx}, chỉ không vẽ và
 * không nạp ảnh nền) để mô phỏng nhanh nhiều episode.</p>
 *
 * <p><b>Vì sao KHÔNG cho hai bên cùng học một lúc:</b> mỗi bên là "môi trường" của bên
 * kia; cả hai cùng đổi policy mỗi episode thì không bên nào có mục tiêu đứng yên để hội
 * tụ (đã thử trước đây — catches/ep nằm ngang). Thay vào đó train XEN KẼ kiểu best-response:
 * mỗi pha chỉ MỘT bên học, bên kia bị đóng băng (chơi tham lam theo bảng đã lưu).</p>
 *
 * <p>Chế độ (tham số thứ 3):</p>
 * <ul>
 *   <li>{@code wolf} (mặc định): SÓI học, thỏ dùng luật gốc RBS. Lưu {@code qtables/wolf.qtable}.</li>
 *   <li>{@code rabbit}: THỎ học né, sói ĐÓNG BĂNG chơi theo wolf.qtable (epsilon 0.05,
 *       không học). Lưu {@code qtables/rabbit.qtable}. Catches/ep GIẢM dần = thỏ đang khá lên.</li>
 *   <li>{@code wolfql}: SÓI học tiếp nhưng đối thủ là thỏ RL đóng băng (rabbit.qtable)
 *       — vòng tinh chỉnh sau khi thỏ đã biết né.</li>
 *   <li>{@code rbs}: ĐO CHUẨN — cả hai dùng luật gốc, không học, không lưu.</li>
 * </ul>
 *
 * <p>Chạy: {@code java -cp <classpath> ...QLearningTrainer [episodes] [maxSteps] [mode]}.
 * {@code ./train.sh} không tham số sẽ chạy trọn pipeline wolf -> rabbit -> wolfql.</p>
 */
public class QLearningTrainer {

    // Kích thước & map giống MainApp (chỉ lấy logic địa hình từ TMX, không vẽ).
    private static final double SOURCE = 1248.0;
    private static final double W = 576.0;
    private static final double H = 576.0;
    private static final String TMX = "/org/openjfx/app/all.tmx";
    private static final int TILE = 32;

    private static final int NUM_ACTIONS = QLearningStrategy.NUM_ACTIONS;
    private static final double DT = 0.1;          // bước thời gian mô phỏng (giây)

    private static final int GRASS_PER_EPISODE = 50;
    // 10 thỏ (tăng từ 6): đo chuẩn cho thấy môi trường cũ bị giới hạn bởi tần suất CHẠM
    // MẶT mồi (RBS 1.34 ~ random 1.38) chứ không phải kỹ năng đuổi -> tăng mật độ mồi
    // để mỗi episode có nhiều pha rượt hơn, tín hiệu học rõ hơn.
    private static final int RABBITS_PER_EPISODE = 10;
    private static final int WOLVES_PER_EPISODE = 3;

    private static final Path WOLF_TABLE = Paths.get("qtables", "wolf.qtable");
    private static final Path RABBIT_TABLE = Paths.get("qtables", "rabbit.qtable");

    private enum Mode { WOLF, RABBIT, WOLF_VS_QL, RBS }

    public static void main(String[] args) {
        int episodes = intArg(args, 0, 2000);
        int maxSteps = intArg(args, 1, 600);
        Mode mode = parseMode(args);
        double alpha = 0.2;
        // gamma 0.97: horizon hiệu dụng ~33 bước (3.3s mô phỏng) — đủ "nhìn xa" hết một
        // pha rượt mồi. 0.9 cũ chỉ ~1s nên phần thưởng bắt mồi gần như không lan ngược.
        double gamma = 0.97;

        QTable wolfQ = QTable.loadOrNew(WOLF_TABLE, NUM_ACTIONS);
        QTable rabbitQ = QTable.loadOrNew(RABBIT_TABLE, NUM_ACTIONS);
        // Train tiếp trên bảng đã học thì đừng quay lại khám phá toàn ngẫu nhiên (epsilon
        // ~1.0 + alpha 0.2 sẽ ghi đè lên policy cũ); chỉ thăm dò nhẹ quanh policy hiện có.
        QTable trainedTable = switch (mode) {
            case RABBIT -> rabbitQ;
            case RBS -> null;
            default -> wolfQ;
        };
        double epsilonStart = (trainedTable != null && trainedTable.size() > 0) ? 0.3 : 1.0;
        Random rng = new Random(42);

        switch (mode) {
            case RBS -> System.out.printf(
                    "DO CHUAN: ca hai dung RBS goc (khong hoc), %d episode, %d step/episode%n",
                    episodes, maxSteps);
            case WOLF -> System.out.printf(
                    "Train SOI (tho RBS co dinh): %d ep, %d step/ep. Bang soi: %d trang thai%n",
                    episodes, maxSteps, wolfQ.size());
            case RABBIT -> System.out.printf(
                    "Train THO ne soi (soi DONG BANG %s): %d ep, %d step/ep. Bang tho: %d trang thai."
                            + " catches/ep GIAM = tho kha len%n",
                    wolfQ.size() > 0 ? "theo wolf.qtable" : "RBS (CHUA co wolf.qtable!)",
                    episodes, maxSteps, rabbitQ.size());
            case WOLF_VS_QL -> System.out.printf(
                    "Tinh chinh SOI vs tho RL dong bang %s: %d ep, %d step/ep. Bang soi: %d trang thai%n",
                    rabbitQ.size() > 0 ? "(rabbit.qtable)" : "(CHUA co rabbit.qtable -> tho RBS)",
                    episodes, maxSteps, wolfQ.size());
        }
        long t0 = System.currentTimeMillis();

        int logEvery = Math.max(1, episodes / 50);
        int windowCatches = 0;
        int windowEpisodes = 0;
        long totalCatches = 0;

        for (int ep = 0; ep < episodes; ep++) {
            // epsilon giảm tuyến tính từ epsilonStart -> 0.05 trong 80% số episode đầu.
            double epsilon = Math.max(0.05, epsilonStart * (1.0 - (double) ep / (episodes * 0.8)));
            int catches = runEpisode(mode, wolfQ, rabbitQ, alpha, gamma, epsilon, maxSteps, rng);

            windowCatches += catches;
            windowEpisodes++;
            totalCatches += catches;
            if ((ep + 1) % logEvery == 0) {
                System.out.printf("ep %6d | eps %.2f | catches/ep %.2f | wolfStates %d | rabbitStates %d%n",
                        ep + 1, epsilon, windowCatches / (double) windowEpisodes,
                        wolfQ.size(), rabbitQ.size());
                windowCatches = 0;
                windowEpisodes = 0;
            }
        }

        double avg = totalCatches / (double) episodes;
        switch (mode) {
            case RBS -> System.out.printf(
                    "DO CHUAN xong sau %.1fs: catches/ep trung binh = %.2f (khong luu bang)%n",
                    (System.currentTimeMillis() - t0) / 1000.0, avg);
            case RABBIT -> {
                rabbitQ.save(RABBIT_TABLE);
                System.out.printf("Xong sau %.1fs (catches/ep %.2f). Da luu %s (%d trang thai)%n",
                        (System.currentTimeMillis() - t0) / 1000.0, avg,
                        RABBIT_TABLE.toAbsolutePath(), rabbitQ.size());
            }
            default -> {
                wolfQ.save(WOLF_TABLE);
                System.out.printf("Xong sau %.1fs (catches/ep %.2f). Da luu %s (%d trang thai)%n",
                        (System.currentTimeMillis() - t0) / 1000.0, avg,
                        WOLF_TABLE.toAbsolutePath(), wolfQ.size());
            }
        }
    }

    /** Chạy 1 episode, trả về số thỏ bị sói bắt trong episode đó. */
    private static int runEpisode(Mode mode, QTable wolfQ, QTable rabbitQ, double alpha,
                                  double gamma, double epsilon, int maxSteps, Random rng) {
        WorldMap world = new WorldMap(W, H);
        world.setObjectZonesFromTmxResource(TMX, TILE, W / SOURCE, H / SOURCE);

        for (int i = 0; i < GRASS_PER_EPISODE; i++) {
            Vector2D p = randomLand(world, rng);
            if (p != null) world.addEntity(new Grass(p));
        }

        List<Agent> rabbits = new ArrayList<>();
        List<Agent> wolves = new ArrayList<>();
        for (int i = 0; i < RABBITS_PER_EPISODE; i++) {
            spawnRabbit(mode, world, rabbits, rabbitQ, alpha, gamma, epsilon, rng);
        }
        for (int i = 0; i < WOLVES_PER_EPISODE; i++) {
            spawnWolf(mode, world, wolves, wolfQ, alpha, gamma, epsilon, rng);
        }

        for (int step = 0; step < maxSteps; step++) {
            world.update(DT);

            int aliveWolves = reapDead(wolves);
            if (mode == Mode.RABBIT) {
                // Pha thỏ học: sói chỉ là "môi trường" -> bù đàn sói để áp lực săn không
                // tắt giữa chừng (sói đóng băng vẫn chết khát/đói được).
                while (aliveWolves < WOLVES_PER_EPISODE
                        && spawnWolf(mode, world, wolves, wolfQ, alpha, gamma, epsilon, rng)) {
                    aliveWolves++;
                }
            } else if (aliveWolves == 0) {
                break;          // pha sói học: sạch sói -> kết thúc episode
            }

            // Bù đàn thỏ để sói luôn có mồi (và thỏ học luôn có mạng mới để thử).
            int aliveRabbits = reapDead(rabbits);
            while (aliveRabbits < RABBITS_PER_EPISODE
                    && spawnRabbit(mode, world, rabbits, rabbitQ, alpha, gamma, epsilon, rng)) {
                aliveRabbits++;
            }
        }

        return world.getDeathCount(EntityType.RABBIT, DeathCause.PREDATION);
    }

    /** Đánh dấu agent chết (phạt hành động cuối nếu đang học) và đếm số còn sống. */
    private static int reapDead(List<Agent> agents) {
        int alive = 0;
        for (Agent a : agents) {
            if (a.dead) continue;
            if (!a.entity.isAlive()) {
                if (a.strategy != null) a.strategy.learnTerminal(); // no-op nếu không học
                a.dead = true;
            } else {
                alive++;
            }
        }
        return alive;
    }

    /** Thả 1 con sói: học / đóng băng / RBS tuỳ chế độ. Trả về true nếu đặt được. */
    private static boolean spawnWolf(Mode mode, WorldMap world, List<Agent> wolves, QTable wolfQ,
                                     double alpha, double gamma, double epsilon, Random rng) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Vector2D p = randomLand(world, rng);
            if (p == null) return false;
            Wolf e = new Wolf(p);
            if (!world.canStandOn(e, p)) continue;
            // Thả sói ĐÓI SẴN (quá ngưỡng săn 50): sói RL giờ chỉ rượt mồi khi đói như
            // RBS; hunger 0.65/s thì 60s episode không kịp đói -> không có pha rượt để học.
            e.setHunger(55.0);
            QLearningStrategy strategy = switch (mode) {
                case WOLF, WOLF_VS_QL ->
                        new QLearningStrategy(wolfQ, Role.PREDATOR, alpha, gamma, epsilon, true, rng);
                case RABBIT -> wolfQ.size() > 0 ? QLearningStrategy.play(wolfQ, Role.PREDATOR) : null;
                case RBS -> null;
            };
            if (strategy != null) e.setFixedStrategy(strategy);
            world.addEntity(e);
            wolves.add(new Agent(e, strategy));
            return true;
        }
        return false;
    }

    /** Thả 1 con thỏ: học / đóng băng / RBS tuỳ chế độ. Trả về true nếu đặt được. */
    private static boolean spawnRabbit(Mode mode, WorldMap world, List<Agent> rabbits, QTable rabbitQ,
                                       double alpha, double gamma, double epsilon, Random rng) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Vector2D p = randomLand(world, rng);
            if (p == null) return false;
            Rabbit e = new Rabbit(p);
            if (!world.canStandOn(e, p)) continue;
            QLearningStrategy strategy = switch (mode) {
                case RABBIT ->
                        new QLearningStrategy(rabbitQ, Role.PREY, alpha, gamma, epsilon, true, rng);
                case WOLF_VS_QL -> rabbitQ.size() > 0 ? QLearningStrategy.play(rabbitQ, Role.PREY) : null;
                case WOLF, RBS -> null;
            };
            if (strategy != null) e.setFixedStrategy(strategy);
            world.addEntity(e);
            rabbits.add(new Agent(e, strategy));
            return true;
        }
        return false;
    }

    private static Mode parseMode(String[] args) {
        if (args.length <= 2) return Mode.WOLF;
        return switch (args[2].toLowerCase()) {
            case "rbs" -> Mode.RBS;
            case "rabbit", "tho" -> Mode.RABBIT;
            case "wolfql" -> Mode.WOLF_VS_QL;
            default -> Mode.WOLF;
        };
    }

    private static Vector2D randomLand(WorldMap world, Random rng) {
        for (int i = 0; i < 80; i++) {
            Vector2D p = new Vector2D(rng.nextDouble() * W, rng.nextDouble() * H);
            if (world.getTerrainAt(p) == TerrainType.LAND) return p;
        }
        return null;
    }

    private static int intArg(String[] args, int index, int defaultValue) {
        try {
            return index < args.length ? Integer.parseInt(args[index]) : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static final class Agent {
        final LivingEntity entity;
        final QLearningStrategy strategy;
        boolean dead;

        Agent(LivingEntity entity, QLearningStrategy strategy) {
            this.entity = entity;
            this.strategy = strategy;
        }
    }
}
