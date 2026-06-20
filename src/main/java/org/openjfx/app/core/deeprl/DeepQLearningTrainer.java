package org.openjfx.app.core.deeprl;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.openjfx.app.core.DeathCause;
import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.strategies.DeepQLearningStrategy;
import org.openjfx.app.core.strategies.DeepQLearningStrategy.Role;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.movable.Rabbit;
import org.openjfx.app.entities.movable.Wolf;
import org.openjfx.app.entities.staticobjs.Grass;

/**
 * Bộ huấn luyện Deep Q-Network chạy HEADLESS — CHỈ huấn luyện SÓI, giống tinh thần của
 * {@code QLearningTrainer} nhưng dùng mạng nơ-ron ({@link DQNAgent}) thay cho bảng tabular.
 *
 * <p>Mỗi episode thả vài con sói (điều khiển bằng {@link DeepQLearningStrategy}, training=true)
 * đi săn đàn thỏ dùng BỘ CHIẾN LƯỢC LUẬT GỐC (RBS) — thỏ không học, là đối thủ cố định. Đàn
 * thỏ được bù lại khi bị ăn để sói luôn có mồi luyện. Mạng sói lưu ra {@code qtables/wolf.dqn}
 * và được NẠP lại để train tiếp ở lần chạy sau.</p>
 *
 * <p>Chạy: {@code java -cp <cp> org.openjfx.app.core.deeprl.DeepQLearningTrainer [episodes] [maxSteps]}</p>
 */
public class DeepQLearningTrainer {

    private static final double SOURCE = 1248.0;
    private static final double W = 576.0;
    private static final double H = 576.0;
    private static final String TMX = "/org/openjfx/app/all.tmx";
    private static final int TILE = 32;

    private static final double DT = 0.1;
    private static final int GRASS_PER_EPISODE = 50;
    private static final int RABBITS_PER_EPISODE = 6;
    private static final int WOLVES_PER_EPISODE = 3;

    private static final Path WOLF_NET = Paths.get("qtables", "wolf.dqn");

    // Siêu tham số DQN.
    private static final int[] HIDDEN = {64, 64};
    private static final double LR = 5e-4;
    // gamma 0.97: horizon hiệu dụng ~33 bước (3.3s mô phỏng) — đủ "nhìn xa" hết pha rượt mồi.
    private static final double GAMMA = 0.97;
    private static final int BUFFER_CAP = 50_000;
    private static final int BATCH = 32;
    private static final int LEARN_START = 1_000;
    private static final int TARGET_SYNC = 500;

    public static void main(String[] args) {
        int episodes = intArg(args, 0, 1500);
        int maxSteps = intArg(args, 1, 600);

        DQNAgent wolf = new DQNAgent(
                DeepQLearningStrategy.NUM_FEATURES, DeepQLearningStrategy.NUM_ACTIONS,
                HIDDEN, LR, GAMMA, BUFFER_CAP, BATCH, LEARN_START, TARGET_SYNC, 42L);
        if (DQNAgent.exists(WOLF_NET)) {
            wolf = continueFrom(WOLF_NET);
            System.out.println("Nap mang soi cu de train tiep: " + WOLF_NET.toAbsolutePath());
        }
        Random rng = new Random(42);

        System.out.printf("Train DQN cho SOI (tho dung RBS): %d episode, toi da %d step/episode%n",
                episodes, maxSteps);
        long t0 = System.currentTimeMillis();

        int logEvery = Math.max(1, episodes / 50);
        int windowCatches = 0, windowEpisodes = 0;

        for (int ep = 0; ep < episodes; ep++) {
            double epsilon = Math.max(0.05, 1.0 - (double) ep / (episodes * 0.8));
            int catches = runEpisode(wolf, epsilon, maxSteps, rng);
            windowCatches += catches;
            windowEpisodes++;
            if ((ep + 1) % logEvery == 0) {
                System.out.printf("ep %6d | eps %.2f | catches/ep %.2f%n",
                        ep + 1, epsilon, windowCatches / (double) windowEpisodes);
                windowCatches = 0; windowEpisodes = 0;
            }
        }

        wolf.save(WOLF_NET);
        System.out.printf("Xong sau %.1fs. Da luu mang soi: %s%n",
                (System.currentTimeMillis() - t0) / 1000.0, WOLF_NET.toAbsolutePath());
    }

    private static int runEpisode(DQNAgent wolfAgent, double epsilon, int maxSteps, Random rng) {
        WorldMap world = new WorldMap(W, H);
        world.setObjectZonesFromTmxResource(TMX, TILE, W / SOURCE, H / SOURCE);

        for (int i = 0; i < GRASS_PER_EPISODE; i++) {
            Vector2D p = randomLand(world, rng);
            if (p != null) world.addEntity(new Grass(p));
        }

        List<LivingEntity> rabbits = new ArrayList<>();
        for (int i = 0; i < RABBITS_PER_EPISODE; i++) spawnRabbit(world, rabbits, rng);

        List<WolfAgent> wolves = new ArrayList<>();
        for (int i = 0; i < WOLVES_PER_EPISODE; i++) spawnWolf(world, wolves, wolfAgent, epsilon, rng);

        for (int step = 0; step < maxSteps; step++) {
            world.update(DT);

            int aliveWolves = 0;
            for (WolfAgent w : wolves) {
                if (w.dead) continue;
                if (!w.entity.isAlive()) {
                    w.strategy.learnTerminal();
                    w.dead = true;
                    continue;
                }
                aliveWolves++;
            }
            if (aliveWolves == 0) break;

            int aliveRabbits = 0;
            for (LivingEntity r : rabbits) if (r.isAlive()) aliveRabbits++;
            while (aliveRabbits < RABBITS_PER_EPISODE && spawnRabbit(world, rabbits, rng)) aliveRabbits++;
        }

        return world.getDeathCount(EntityType.RABBIT, DeathCause.PREDATION);
    }

    private static void spawnWolf(WorldMap world, List<WolfAgent> wolves, DQNAgent agent,
                                  double epsilon, Random rng) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Vector2D p = randomLand(world, rng);
            if (p == null) return;
            Wolf e = new Wolf(p);
            if (!world.canStandOn(e, p)) continue;
            DeepQLearningStrategy s = new DeepQLearningStrategy(agent, Role.PREDATOR, epsilon, true, rng);
            e.setFixedStrategy(s);
            world.addEntity(e);
            wolves.add(new WolfAgent(e, s));
            return;
        }
    }

    private static boolean spawnRabbit(WorldMap world, List<LivingEntity> rabbits, Random rng) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Vector2D p = randomLand(world, rng);
            if (p == null) return false;
            Rabbit e = new Rabbit(p);
            if (!world.canStandOn(e, p)) continue;
            world.addEntity(e);
            rabbits.add(e);
            return true;
        }
        return false;
    }

    // Nạp mạng cũ vào một agent HỌC mới (giữ replay/siêu tham số mới, chỉ kế thừa trọng số).
    private static DQNAgent continueFrom(Path netPath) {
        DQNAgent fresh = new DQNAgent(
                DeepQLearningStrategy.NUM_FEATURES, DeepQLearningStrategy.NUM_ACTIONS,
                HIDDEN, LR, GAMMA, BUFFER_CAP, BATCH, LEARN_START, TARGET_SYNC, 42L);
        fresh.loadWeights(netPath);
        return fresh;
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

    private static final class WolfAgent {
        final LivingEntity entity;
        final DeepQLearningStrategy strategy;
        boolean dead;

        WolfAgent(LivingEntity entity, DeepQLearningStrategy strategy) {
            this.entity = entity;
            this.strategy = strategy;
        }
    }
}
