package org.openjfx.app.core.montecarlo;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.openjfx.app.core.DeathCause;
import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.qlearning.QTable;
import org.openjfx.app.core.strategies.MonteCarloStrategy;
import org.openjfx.app.core.strategies.MonteCarloStrategy.Role;
import org.openjfx.app.core.strategies.QLearningStrategy;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.movable.Rabbit;
import org.openjfx.app.entities.movable.Wolf;
import org.openjfx.app.entities.staticobjs.Grass;

/**
 * Bộ huấn luyện Monte Carlo Control chạy HEADLESS cho SÓI và THỎ.
 *
 * <p>Khác với {@link org.openjfx.app.core.qlearning.QLearningTrainer} (TD — cập nhật Q
 * từng bước), trainer này chạy trọn episode rồi mới cập nhật QTable một lần duy nhất
 * thông qua {@link MonteCarloStrategy#learnTerminal()} (entity chết) hoặc
 * {@link MonteCarloStrategy#learnEpisodeEnd()} (hết maxSteps, entity còn sống).</p>
 *
 * <p><b>Episode boundary:</b> cố định {@code maxSteps} bước (mặc định 400 bước = 40s
 * mô phỏng ở DT=0.1). Entity chết giữa episode vẫn được cập nhật ngay (terminal reward
 * -10), không cần chờ hết episode.</p>
 *
 * <p>Chế độ train giống QLearningTrainer (xen kẽ best-response, không co-evolution):</p>
 * <ul>
 *   <li>{@code wolf}   : SÓI MC học, thỏ RBS. Lưu {@code qtables/mc_wolf.qtable}.</li>
 *   <li>{@code rabbit} : THỎ MC học né, sói ĐÓNG BĂNG theo mc_wolf.qtable.</li>
 *   <li>{@code wolfql} : SÓI MC tinh chỉnh vs thỏ RL đóng băng (mc_rabbit.qtable).</li>
 *   <li>{@code rbs}    : Đo chuẩn, không học, không lưu.</li>
 * </ul>
 *
 * <p>Chạy: {@code ./train_mc.sh} hoặc {@code java -cp <cp> ...MonteCarloTrainer [ep] [steps] [mode]}.</p>
 */
public class MonteCarloTrainer {

    private static final double SOURCE = 1248.0;
    private static final double W      = 576.0;
    private static final double H      = 576.0;
    private static final String TMX    = "/org/openjfx/app/all.tmx";
    private static final int    TILE   = 32;

    private static final int    NUM_ACTIONS          = MonteCarloStrategy.NUM_ACTIONS;
    private static final double DT                   = 0.1;

    private static final int GRASS_PER_EPISODE   = 50;
    private static final int RABBITS_PER_EPISODE = 10;
    private static final int WOLVES_PER_EPISODE  = 3;

    private static final Path WOLF_TABLE   = Paths.get("qtables", "mc_wolf.qtable");
    private static final Path RABBIT_TABLE = Paths.get("qtables", "mc_rabbit.qtable");

    private enum Mode { WOLF, RABBIT, WOLF_VS_QL, RBS }

    public static void main(String[] args) {
        int    episodes = intArg(args, 0, 2000);
        int    maxSteps = intArg(args, 1, 400);   // MC: episode ngắn hơn QL (buffer nhỏ hơn)
        Mode   mode     = parseMode(args);
        double alpha    = 0.1;
        double gamma    = 0.97;

        QTable wolfQ   = QTable.loadOrNew(WOLF_TABLE,   NUM_ACTIONS);
        QTable rabbitQ = QTable.loadOrNew(RABBIT_TABLE, NUM_ACTIONS);

        QTable trainedTable = switch (mode) {
            case RABBIT -> rabbitQ;
            case RBS    -> null;
            default     -> wolfQ;
        };
        double epsilonStart = (trainedTable != null && trainedTable.size() > 0) ? 0.3 : 1.0;
        Random rng = new Random(42);

        switch (mode) {
            case RBS -> System.out.printf(
                    "DO CHUAN MC: ca hai RBS, %d episode, %d step/ep%n", episodes, maxSteps);
            case WOLF -> System.out.printf(
                    "MC Train SOI (tho RBS): %d ep, %d step/ep. Bang soi: %d trang thai%n",
                    episodes, maxSteps, wolfQ.size());
            case RABBIT -> System.out.printf(
                    "MC Train THO (soi dong bang %s): %d ep, %d step/ep. Bang tho: %d trang thai%n",
                    wolfQ.size() > 0 ? "mc_wolf.qtable" : "RBS (chua co!)",
                    episodes, maxSteps, rabbitQ.size());
            case WOLF_VS_QL -> System.out.printf(
                    "MC Tinh chinh SOI vs tho RL dong bang %s: %d ep, %d step/ep%n",
                    rabbitQ.size() > 0 ? "mc_rabbit.qtable" : "RBS (chua co!)",
                    episodes, maxSteps, wolfQ.size());
        }
        long t0 = System.currentTimeMillis();

        int  logEvery      = Math.max(1, episodes / 50);
        int  windowCatches = 0;
        int  windowEp      = 0;
        long totalCatches  = 0;

        for (int ep = 0; ep < episodes; ep++) {
            double epsilon = Math.max(0.05, epsilonStart * (1.0 - (double) ep / (episodes * 0.8)));
            int catches = runEpisode(mode, wolfQ, rabbitQ, alpha, gamma, epsilon, maxSteps, rng);

            windowCatches += catches;
            windowEp++;
            totalCatches += catches;

            if ((ep + 1) % logEvery == 0) {
                System.out.printf("ep %6d | eps %.2f | catches/ep %.2f | wolfStates %d | rabbitStates %d%n",
                        ep + 1, epsilon, windowCatches / (double) windowEp,
                        wolfQ.size(), rabbitQ.size());
                windowCatches = 0;
                windowEp      = 0;
            }
        }

        double avg = totalCatches / (double) episodes;
        switch (mode) {
            case RBS -> System.out.printf(
                    "DO CHUAN xong %.1fs: catches/ep %.2f (khong luu)%n",
                    (System.currentTimeMillis() - t0) / 1000.0, avg);
            case RABBIT -> {
                rabbitQ.save(RABBIT_TABLE);
                System.out.printf("Xong %.1fs (catches/ep %.2f). Luu %s (%d trang thai)%n",
                        (System.currentTimeMillis() - t0) / 1000.0, avg,
                        RABBIT_TABLE.toAbsolutePath(), rabbitQ.size());
            }
            default -> {
                wolfQ.save(WOLF_TABLE);
                System.out.printf("Xong %.1fs (catches/ep %.2f). Luu %s (%d trang thai)%n",
                        (System.currentTimeMillis() - t0) / 1000.0, avg,
                        WOLF_TABLE.toAbsolutePath(), wolfQ.size());
            }
        }
    }

    // ================================================================= episode

    private static int runEpisode(Mode mode, QTable wolfQ, QTable rabbitQ,
                                   double alpha, double gamma, double epsilon,
                                   int maxSteps, Random rng) {
        WorldMap world = new WorldMap(W, H);
        world.setObjectZonesFromTmxResource(TMX, TILE, W / SOURCE, H / SOURCE);

        for (int i = 0; i < GRASS_PER_EPISODE; i++) {
            Vector2D p = randomLand(world, rng);
            if (p != null) world.addEntity(new Grass(p));
        }

        List<Agent> rabbits = new ArrayList<>();
        List<Agent> wolves  = new ArrayList<>();

        for (int i = 0; i < RABBITS_PER_EPISODE; i++)
            spawnRabbit(mode, world, rabbits, rabbitQ, alpha, gamma, epsilon, rng);
        for (int i = 0; i < WOLVES_PER_EPISODE; i++)
            spawnWolf(mode, world, wolves, wolfQ, rabbitQ, alpha, gamma, epsilon, rng);

        for (int step = 0; step < maxSteps; step++) {
            world.update(DT);

            int aliveWolves = reapDead(wolves);
            if (mode == Mode.RABBIT) {
                // Pha thỏ học: bù sói để áp lực săn không tắt giữa episode.
                while (aliveWolves < WOLVES_PER_EPISODE
                        && spawnWolf(mode, world, wolves, wolfQ, rabbitQ, alpha, gamma, epsilon, rng)) {
                    aliveWolves++;
                }
            } else if (aliveWolves == 0) {
                break;   // pha sói học: sạch sói -> kết thúc episode sớm
            }

            int aliveRabbits = reapDead(rabbits);
            while (aliveRabbits < RABBITS_PER_EPISODE
                    && spawnRabbit(mode, world, rabbits, rabbitQ, alpha, gamma, epsilon, rng)) {
                aliveRabbits++;
            }
        }

        // Entity còn sống khi hết maxSteps: cập nhật Q dựa trên trajectory tích lũy.
        for (Agent a : wolves)  if (!a.dead && a.mcStrategy != null) a.mcStrategy.learnEpisodeEnd();
        for (Agent a : rabbits) if (!a.dead && a.mcStrategy != null) a.mcStrategy.learnEpisodeEnd();

        return world.getDeathCount(EntityType.RABBIT, DeathCause.PREDATION);
    }

    // ================================================================= spawn

    private static boolean spawnWolf(Mode mode, WorldMap world, List<Agent> wolves,
                                      QTable wolfQ, QTable rabbitQ,
                                      double alpha, double gamma, double epsilon, Random rng) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Vector2D p = randomLand(world, rng);
            if (p == null) return false;
            Wolf e = new Wolf(p);
            if (!world.canStandOn(e, p)) continue;
            e.setHunger(55.0);   // thả sói đói sẵn (ngưỡng săn = 50)

            MonteCarloStrategy mcStrategy = null;
            switch (mode) {
                case WOLF, WOLF_VS_QL -> {
                    mcStrategy = new MonteCarloStrategy(wolfQ, Role.PREDATOR,
                            alpha, gamma, epsilon, true, rng);
                    e.setFixedStrategy(mcStrategy);
                }
                case RABBIT -> {
                    // Sói đóng băng: dùng QLearningStrategy (hoặc RBS nếu chưa có bảng).
                    if (wolfQ.size() > 0) {
                        e.setFixedStrategy(QLearningStrategy.play(wolfQ, QLearningStrategy.Role.PREDATOR));
                    }
                    // mcStrategy = null -> không gọi learnEpisodeEnd
                }
                case RBS -> { /* RBS thuần, không gắn strategy */ }
            }

            world.addEntity(e);
            wolves.add(new Agent(e, mcStrategy));
            return true;
        }
        return false;
    }

    private static boolean spawnRabbit(Mode mode, WorldMap world, List<Agent> rabbits,
                                        QTable rabbitQ,
                                        double alpha, double gamma, double epsilon, Random rng) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Vector2D p = randomLand(world, rng);
            if (p == null) return false;
            Rabbit e = new Rabbit(p);
            if (!world.canStandOn(e, p)) continue;

            MonteCarloStrategy mcStrategy = null;
            switch (mode) {
                case RABBIT -> {
                    mcStrategy = new MonteCarloStrategy(rabbitQ, Role.PREY,
                            alpha, gamma, epsilon, true, rng);
                    e.setFixedStrategy(mcStrategy);
                }
                case WOLF_VS_QL -> {
                    if (rabbitQ.size() > 0) {
                        e.setFixedStrategy(QLearningStrategy.play(rabbitQ, QLearningStrategy.Role.PREY));
                    }
                }
                default -> { /* WOLF mode: thỏ dùng RBS */ }
            }

            world.addEntity(e);
            rabbits.add(new Agent(e, mcStrategy));
            return true;
        }
        return false;
    }

    // ================================================================= helpers

    /**
     * Đánh dấu agent chết, gọi learnTerminal() để cập nhật Q (MC: flush trajectory).
     * Trả về số agent còn sống.
     */
    private static int reapDead(List<Agent> agents) {
        int alive = 0;
        for (Agent a : agents) {
            if (a.dead) continue;
            if (!a.entity.isAlive()) {
                if (a.mcStrategy != null) a.mcStrategy.learnTerminal();
                a.dead = true;
            } else {
                alive++;
            }
        }
        return alive;
    }

    private static Vector2D randomLand(WorldMap world, Random rng) {
        for (int i = 0; i < 80; i++) {
            Vector2D p = new Vector2D(rng.nextDouble() * W, rng.nextDouble() * H);
            if (world.getTerrainAt(p) == TerrainType.LAND) return p;
        }
        return null;
    }

    private static Mode parseMode(String[] args) {
        if (args.length <= 2) return Mode.WOLF;
        return switch (args[2].toLowerCase()) {
            case "rbs"              -> Mode.RBS;
            case "rabbit", "tho"   -> Mode.RABBIT;
            case "wolfql"           -> Mode.WOLF_VS_QL;
            default                 -> Mode.WOLF;
        };
    }

    private static int intArg(String[] args, int index, int defaultValue) {
        try {
            return index < args.length ? Integer.parseInt(args[index]) : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    // ================================================================= inner

    private static final class Agent {
        final LivingEntity      entity;
        final MonteCarloStrategy mcStrategy;   // null nếu frozen/RBS
        boolean dead;

        Agent(LivingEntity entity, MonteCarloStrategy mcStrategy) {
            this.entity     = entity;
            this.mcStrategy = mcStrategy;
        }
    }
}
