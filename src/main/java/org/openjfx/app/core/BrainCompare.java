package org.openjfx.app.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.openjfx.app.core.qlearning.QTable;
import org.openjfx.app.core.strategies.MonteCarloStrategy;
import org.openjfx.app.core.strategies.QLearningStrategy;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.movable.Rabbit;
import org.openjfx.app.entities.movable.Wolf;
import org.openjfx.app.entities.staticobjs.Grass;

/**
 * So sánh ba "não" điều khiển SÓI trên cùng điều kiện thí nghiệm (headless).
 *
 * <p>Đơn vị so sánh: thế giới CÔ LẬP chỉ gồm sói + thỏ + cỏ. Con mồi (thỏ) luôn dùng RBS
 * ở cả ba nhánh, chỉ thay bộ điều khiển của sói:</p>
 * <ul>
 *   <li>RBS — sói không gắn fixedStrategy (selectBest + A* hiện trạng).</li>
 *   <li>QL  — {@link QLearningStrategy#play} với qtables/wolf.qtable.</li>
 *   <li>MC  — {@link MonteCarloStrategy#play} với qtables/mc_wolf.qtable.</li>
 * </ul>
 *
 * <p>Mỗi (não × seed) chạy lại trên CÙNG seed → so theo cặp. Vì thế giới chỉ có sói săn thỏ
 * nên {@code getDeathCount(RABBIT, PREDATION)} = đúng số mồi do SÓI bắt (không lẫn gấu/voi).
 * Thỏ được bù về sàn {@code RABBITS} mỗi bước để áp lực mồi không tắt; sói KHÔNG bù để đo
 * được tuổi thọ và chết đói/khát.</p>
 *
 * <p>Lưu ý: {@code play()} giữ epsilon 0.05 (5% bước ngẫu nhiên, không seed) nên RL có nhiễu
 * nhỏ không tất định — trung bình trên nhiều seed khử bớt. Path-length/A*-node không được
 * đo ở đây (chưa có instrumentation); CPU/step là đại diện cho chi phí tính toán.</p>
 *
 * <p>Chạy: {@code java -cp <classpath> org.openjfx.app.core.BrainCompare [seeds] [steps]}
 * (mặc định 20 seed × 3000 step ≈ 300s mô phỏng/seed).</p>
 */
public final class BrainCompare {

    private static final double SOURCE = 1248.0;
    private static final double W      = 576.0;
    private static final double H      = 576.0;
    private static final String TMX    = "/org/openjfx/app/all.tmx";
    private static final int    TILE   = 32;
    private static final double DT     = 0.1;

    private static final int GRASS   = 50;
    private static final int RABBITS = 10;
    private static final int WOLVES  = 3;

    private enum Brain { RBS, QL, MC }

    private BrainCompare() {
    }

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 3000;

        QTable wolfQ   = loadTable("wolf.qtable");
        QTable mcWolfQ = loadTable("mc_wolf.qtable");
        if (wolfQ == null)   System.out.println("CANH BAO: thieu qtables/wolf.qtable -> nhanh QL bo qua.");
        if (mcWolfQ == null) System.out.println("CANH BAO: thieu qtables/mc_wolf.qtable -> nhanh MC bo qua.");

        System.out.printf("So sanh nao SOI: %d seed x %d step (dt=%.1f, ~%.0fs mo phong/seed)%n",
                seeds, steps, DT, steps * DT);
        System.out.printf("The gioi co lap: %d soi + >=%d tho (RBS) + %d co.%n%n", WOLVES, RABBITS, GRASS);

        List<Brain>  brains = new ArrayList<>();
        List<Metric[]> all  = new ArrayList<>();
        for (Brain b : Brain.values()) {
            if (b == Brain.QL && wolfQ == null) continue;
            if (b == Brain.MC && mcWolfQ == null) continue;
            Metric[] perSeed = new Metric[seeds];
            for (int s = 0; s < seeds; s++) {
                perSeed[s] = runOne(b, s, steps, wolfQ, mcWolfQ);
            }
            brains.add(b);
            all.add(perSeed);
            System.out.printf("  [%s] xong %d seed.%n", b, seeds);
        }

        printReport(brains, all);
    }

    // ============================================================= 1 lần chạy

    private static Metric runOne(Brain brain, int seed, int steps, QTable wolfQ, QTable mcWolfQ) {
        Random rng = new Random(seed);
        WorldMap world = new WorldMap(W, H);
        world.setObjectZonesFromTmxResource(TMX, TILE, W / SOURCE, H / SOURCE);

        for (int i = 0; i < GRASS; i++) {
            Vector2D p = randomLand(world, rng);
            if (p != null) world.addEntity(new Grass(p));
        }

        List<WolfRec> wolfRecs = new ArrayList<>();
        for (int i = 0; i < WOLVES; i++) spawnWolf(brain, world, rng, wolfQ, mcWolfQ, wolfRecs, 0, seed);
        for (int i = 0; i < RABBITS; i++) spawnRabbit(world, rng);

        // Reset bộ đếm A* NGAY trước vòng mô phỏng (spawn ở trên không gọi A*).
        WorldMap.resetAStarCounters();

        int firstCatchStep = -1;
        long cpuNanos = 0;
        for (int step = 1; step <= steps; step++) {
            long t0 = System.nanoTime();
            world.update(DT);
            cpuNanos += System.nanoTime() - t0;

            // Ghi nhận sói vừa chết ở bước này.
            for (WolfRec wr : wolfRecs) {
                if (wr.deathStep < 0 && !wr.wolf.isAlive()) wr.deathStep = step;
            }

            if (firstCatchStep < 0
                    && world.getDeathCount(EntityType.RABBIT, DeathCause.PREDATION) > 0) {
                firstCatchStep = step;
            }

            // Bù thỏ về sàn để áp lực mồi không tắt (không bù sói).
            int alive = countAlive(world, EntityType.RABBIT);
            while (alive < RABBITS && spawnRabbit(world, rng)) alive++;
        }

        Metric m = new Metric();
        int catches = world.getDeathCount(EntityType.RABBIT, DeathCause.PREDATION);
        m.catchesPer1000 = catches * 1000.0 / steps;
        m.hungerDeaths   = world.getDeathCount(EntityType.WOLF, DeathCause.HUNGER);
        m.thirstDeaths   = world.getDeathCount(EntityType.WOLF, DeathCause.THIRST);
        m.firstCatchStep = firstCatchStep < 0 ? steps : firstCatchStep;   // không bắt được = bị kiểm duyệt ở maxSteps
        m.cpuMsPerStep   = cpuNanos / 1e6 / steps;

        double sumLife = 0;
        for (WolfRec wr : wolfRecs) {
            int death = wr.deathStep < 0 ? steps : wr.deathStep;
            sumLife += (death - wr.birthStep);
        }
        m.avgLifespanSteps = wolfRecs.isEmpty() ? 0 : sumLife / wolfRecs.size();

        // Instrumentation: chi phí A* (rl_v03: cả RBS lẫn RL đều dùng A* qua strategy nên
        // đây là so sánh tải tìm đường của "bộ chọn học" vs "bộ chọn chấm tay").
        m.aStarCallsPerStep = WorldMap.getAStarCalls() / (double) steps;
        m.aStarNodesPerStep = WorldMap.getAStarNodes() / (double) steps;
        return m;
    }

    private static void spawnWolf(Brain brain, WorldMap world, Random rng,
                                  QTable wolfQ, QTable mcWolfQ,
                                  List<WolfRec> recs, int birthStep, int seed) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Vector2D p = randomLand(world, rng);
            if (p == null) return;
            Wolf w = new Wolf(p);
            if (!world.canStandOn(w, p)) continue;
            w.setHunger(55.0);   // ngưỡng săn = 50: thả đói sẵn để khởi động cùng điều kiện
            // RNG epsilon SEED tất định theo (seed × chỉ số sói) — KHÔNG lấy từ rng thế giới
            // dùng chung, nếu không sẽ làm lệch vị trí spawn giữa các nhánh não. Mỗi sói một
            // dòng riêng (recs.size() = 0,1,2) để 3 sói không hành xử epsilon giống hệt nhau.
            Random eps = new Random(seed * 1_000_003L + recs.size());
            switch (brain) {
                case RBS -> { /* không gắn strategy: selectBest + A* (tất định, không epsilon) */ }
                case QL  -> w.setFixedStrategy(QLearningStrategy.play(wolfQ, QLearningStrategy.Role.PREDATOR, eps));
                case MC  -> w.setFixedStrategy(MonteCarloStrategy.play(mcWolfQ, MonteCarloStrategy.Role.PREDATOR, eps));
            }
            world.addEntity(w);
            recs.add(new WolfRec(w, birthStep));
            return;
        }
    }

    private static boolean spawnRabbit(WorldMap world, Random rng) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Vector2D p = randomLand(world, rng);
            if (p == null) return false;
            Rabbit r = new Rabbit(p);          // thỏ luôn RBS ở mọi nhánh
            if (!world.canStandOn(r, p)) continue;
            world.addEntity(r);
            return true;
        }
        return false;
    }

    private static int countAlive(WorldMap world, EntityType type) {
        int n = 0;
        for (Entity e : world.getEntities()) {
            if (e.getType() == type && e instanceof LivingEntity le && le.isAlive()) n++;
        }
        return n;
    }

    private static Vector2D randomLand(WorldMap world, Random rng) {
        for (int i = 0; i < 80; i++) {
            Vector2D p = new Vector2D(rng.nextDouble() * W, rng.nextDouble() * H);
            if (world.getTerrainAt(p) == TerrainType.LAND) return p;
        }
        return null;
    }

    private static QTable loadTable(String file) {
        Path path = Paths.get("qtables", file);
        if (!Files.exists(path)) return null;
        return QTable.load(path, QLearningStrategy.NUM_ACTIONS);
    }

    // ================================================================= báo cáo

    private static void printReport(List<Brain> brains, List<Metric[]> all) {
        System.out.println();
        System.out.println("=== KET QUA (trung binh +/- do lech chuan tren cac seed) ===");
        row("Metric", brains, "%-26s");
        line(brains.size());
        printMetric("Bat/1000 step (cao=tot)", brains, all, m -> m.catchesPer1000, "%.2f");
        printMetric("Tuoi tho TB soi (step)",  brains, all, m -> m.avgLifespanSteps, "%.0f");
        printMetric("Soi chet doi",            brains, all, m -> (double) m.hungerDeaths, "%.2f");
        printMetric("Soi chet khat",           brains, all, m -> (double) m.thirstDeaths, "%.2f");
        printMetric("Step toi lan bat dau",    brains, all, m -> (double) m.firstCatchStep, "%.0f");
        printMetric("CPU ms/step (thap=tot)",  brains, all, m -> m.cpuMsPerStep, "%.4f");
        printMetric("A* goi/step",              brains, all, m -> m.aStarCallsPerStep, "%.2f");
        printMetric("A* node/step",             brains, all, m -> m.aStarNodesPerStep, "%.1f");
        System.out.println();
        System.out.println("Ghi chu: 'Step toi lan bat dau' = maxStep nghia la khong bat duoc (bi kiem duyet).");
        System.out.println("         rl_v03: RL hoc CHON strategy (Flee/SeekWater/Hunter/Mate/Wander), A* lo dieu huong");
        System.out.println("         -> ca 3 nao dung chung A*; 'A* goi/step','A* node/step' so tai tim duong cong bang.");
    }

    private interface Field { double get(Metric m); }

    private static void printMetric(String name, List<Brain> brains, List<Metric[]> all,
                                    Field f, String numFmt) {
        StringBuilder sb = new StringBuilder(String.format("%-26s", name));
        for (int i = 0; i < brains.size(); i++) {
            Metric[] ms = all.get(i);
            double[] xs = new double[ms.length];
            for (int j = 0; j < ms.length; j++) xs[j] = f.get(ms[j]);
            double mean = mean(xs), sd = std(xs, mean);
            sb.append(String.format(" | " + numFmt + " +/- " + numFmt, mean, sd));
        }
        System.out.println(sb);
    }

    private static void row(String first, List<Brain> brains, String fmt) {
        StringBuilder sb = new StringBuilder(String.format(fmt, first));
        for (Brain b : brains) sb.append(String.format(" | %15s", b));
        System.out.println(sb);
    }

    private static void line(int n) {
        StringBuilder sb = new StringBuilder("-".repeat(26));
        for (int i = 0; i < n; i++) sb.append("-+").append("-".repeat(16));
        System.out.println(sb);
    }

    private static double mean(double[] xs) {
        double s = 0; for (double x : xs) s += x; return xs.length == 0 ? 0 : s / xs.length;
    }

    private static double std(double[] xs, double mean) {
        if (xs.length < 2) return 0;
        double s = 0; for (double x : xs) s += (x - mean) * (x - mean);
        return Math.sqrt(s / (xs.length - 1));
    }

    // ================================================================= inner

    private static final class WolfRec {
        final Wolf wolf;
        final int  birthStep;
        int        deathStep = -1;
        WolfRec(Wolf wolf, int birthStep) { this.wolf = wolf; this.birthStep = birthStep; }
    }

    private static final class Metric {
        double catchesPer1000;
        double avgLifespanSteps;
        int    hungerDeaths;
        int    thirstDeaths;
        int    firstCatchStep;
        double cpuMsPerStep;
        double aStarCallsPerStep;
        double aStarNodesPerStep;
    }
}
