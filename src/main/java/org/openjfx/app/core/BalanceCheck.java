package org.openjfx.app.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.movable.Bear;
import org.openjfx.app.entities.movable.Elephant;
import org.openjfx.app.entities.movable.Fish;
import org.openjfx.app.entities.movable.Rabbit;
import org.openjfx.app.entities.movable.Wolf;
import org.openjfx.app.entities.staticobjs.Algae;
import org.openjfx.app.entities.staticobjs.Grass;

/**
 * Đo CÂN BẰNG hệ sinh thái ở chế độ headless: thả đúng quần thể khởi đầu như MainApp
 * (3 thỏ, 1 sói, 1 gấu, 1 voi, 1 cá, 35 cỏ, 10 tảo — tất cả dùng RBS, không RL) rồi
 * chạy nhanh N giây mô phỏng và in dân số + nguyên nhân chết mỗi phút.
 *
 * <p>Chạy: {@code java -cp <classpath> org.openjfx.app.core.BalanceCheck [giây mô phỏng]}
 * (mặc định 600). Hệ "cân" khi: không loài nào tuyệt chủng sớm vì đói/khát, thỏ không
 * bùng nổ vô hạn, sói/gấu thỉnh thoảng bắt được mồi.</p>
 */
public final class BalanceCheck {

    // Map & tỉ lệ giống MainApp/QLearningTrainer (logic địa hình từ TMX, không vẽ).
    private static final double SOURCE = 1248.0;
    private static final double W = 576.0;
    private static final double H = 576.0;
    private static final String TMX = "/org/openjfx/app/all.tmx";
    private static final int TILE = 32;
    private static final double DT = 0.1;

    // Não RL tuỳ chọn (chỉ nạp khi chạy với tham số "ql" hoặc "mc").
    private static org.openjfx.app.core.qlearning.QTable wolfQ;
    private static org.openjfx.app.core.qlearning.QTable rabbitQ;
    // true = bảng nạp ở trên là của Monte Carlo (dùng MonteCarloStrategy thay vì QLearningStrategy).
    private static boolean useMc;

    private BalanceCheck() {
    }

    public static void main(String[] args) {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 600;
        // Tham số thứ 2 = "ql": gắn não RL (qtables/wolf.qtable + rabbit.qtable nếu có)
        // cho sói/thỏ THẢ BAN ĐẦU — giống hệt cách MainApp -Dql=true hoạt động (con đẻ
        // ra sau vẫn RBS) — để kiểm chứng não đã train không phá cân bằng sinh thái.
        // Tham số thứ 2 = "mc": tương tự nhưng nạp bảng Monte Carlo (mc_wolf/mc_rabbit.qtable).
        boolean ql = args.length > 1 && "ql".equalsIgnoreCase(args[1]);
        useMc = args.length > 1 && "mc".equalsIgnoreCase(args[1]);
        if (ql || useMc) {
            String wolfFile = useMc ? "mc_wolf.qtable" : "wolf.qtable";
            String rabbitFile = useMc ? "mc_rabbit.qtable" : "rabbit.qtable";
            java.nio.file.Path wolfPath = java.nio.file.Paths.get("qtables", wolfFile);
            java.nio.file.Path rabbitPath = java.nio.file.Paths.get("qtables", rabbitFile);
            if (java.nio.file.Files.exists(wolfPath)) {
                wolfQ = org.openjfx.app.core.qlearning.QTable.load(wolfPath,
                        org.openjfx.app.core.strategies.QLearningStrategy.NUM_ACTIONS);
            }
            if (java.nio.file.Files.exists(rabbitPath)) {
                rabbitQ = org.openjfx.app.core.qlearning.QTable.load(rabbitPath,
                        org.openjfx.app.core.strategies.QLearningStrategy.NUM_ACTIONS);
            }
            System.out.printf("Nao %s: soi=%s, tho=%s%n",
                    useMc ? "Monte Carlo" : "RL",
                    wolfQ != null ? wolfQ.size() + " trang thai" : "RBS (thieu bang)",
                    rabbitQ != null ? rabbitQ.size() + " trang thai" : "RBS (thieu bang)");
        }
        Random rng = new Random(7);

        WorldMap world = new WorldMap(W, H);
        world.setObjectZonesFromTmxResource(TMX, TILE, W / SOURCE, H / SOURCE);

        // Giống MainApp: thỏ & cá thả theo CỤM để tìm được bạn tình ngay từ đầu.
        Vector2D rabbitBase = randomTerrain(world, rng, TerrainType.LAND);
        for (int i = 0; i < 8; i++) seedNear(world, rng, Rabbit.class, rabbitBase, TerrainType.LAND);
        Vector2D wolfBase = randomTerrain(world, rng, TerrainType.LAND);
        for (int i = 0; i < 2; i++) seedNear(world, rng, Wolf.class, wolfBase, TerrainType.LAND);
        Vector2D bearBase = randomTerrain(world, rng, TerrainType.LAND);
        for (int i = 0; i < 2; i++) seedNear(world, rng, Bear.class, bearBase, TerrainType.LAND);
        Vector2D elephantBase = randomTerrain(world, rng, TerrainType.LAND);
        for (int i = 0; i < 2; i++) seedNear(world, rng, Elephant.class, elephantBase, TerrainType.LAND);
        Vector2D fishBase = randomTerrain(world, rng, TerrainType.WATER);
        for (int i = 0; i < 3; i++) seedNear(world, rng, Fish.class, fishBase, TerrainType.WATER);
        for (int i = 0; i < 35; i++) {
            Vector2D p = randomTerrain(world, rng, TerrainType.LAND);
            if (p != null) world.addEntity(new Grass(p));
        }
        for (int i = 0; i < 10; i++) {
            Vector2D p = randomTerrain(world, rng, TerrainType.WATER);
            if (p != null) world.addEntity(new Algae(p));
        }

        System.out.printf("Mo phong %ds (dt=%.1fs)...%n", seconds, DT);
        System.out.println("  t | tho soi gau voi ca | co tao | chet (doi/khat/bi san)");
        int steps = (int) Math.round(seconds / DT);
        for (int step = 1; step <= steps; step++) {
            world.update(DT);
            if (step % (int) Math.round(60 / DT) == 0) {
                printSnapshot(world, step * DT);
            }
        }
        printGrassSpacing(world);

        System.out.println("Tu vong theo loai (doi/khat/bi san):");
        for (Map.Entry<EntityType, EnumMap<DeathCause, Integer>> entry
                : world.getDeathCountsByType().entrySet()) {
            EnumMap<DeathCause, Integer> c = entry.getValue();
            System.out.printf("  %-9s %d/%d/%d%n", entry.getKey(),
                    c.getOrDefault(DeathCause.HUNGER, 0),
                    c.getOrDefault(DeathCause.THIRST, 0),
                    c.getOrDefault(DeathCause.PREDATION, 0));
        }
    }

    // Khoảng cách gần nhất giữa các cụm cỏ SỐNG (lấy mẫu) — để xác minh giới hạn
    // MIN_DISTANCE_FROM_GRASS có thực sự giữ được mật độ hay không.
    private static void printGrassSpacing(WorldMap world) {
        java.util.List<Vector2D> grass = new java.util.ArrayList<>();
        for (Entity e : world.getEntities()) {
            if (e instanceof Grass g && g.isAlive()) grass.add(e.getPosition());
            if (grass.size() >= 1500) break;
        }
        double min = Double.MAX_VALUE;
        for (int i = 0; i < grass.size(); i++) {
            for (int j = i + 1; j < grass.size(); j++) {
                min = Math.min(min, grass.get(i).distance(grass.get(j)));
            }
        }
        System.out.printf("Khoang cach co gan nhat (mau %d cay): %.1f px%n", grass.size(), min);
    }

    private static void printSnapshot(WorldMap world, double t) {
        Map<EntityType, Integer> counts = new EnumMap<>(EntityType.class);
        for (Entity e : world.getEntities()) {
            boolean alive = !(e instanceof LivingEntity le) || le.isAlive();
            if (e instanceof org.openjfx.app.entities.staticobjs.Plant p) alive = p.isAlive();
            if (alive) counts.merge(e.getType(), 1, Integer::sum);
        }
        int hunger = 0, thirst = 0, predation = 0;
        for (EnumMap<DeathCause, Integer> byCause : world.getDeathCountsByType().values()) {
            hunger += byCause.getOrDefault(DeathCause.HUNGER, 0);
            thirst += byCause.getOrDefault(DeathCause.THIRST, 0);
            predation += byCause.getOrDefault(DeathCause.PREDATION, 0);
        }
        System.out.printf("%3.0fs | %3d %3d %3d %3d %2d | %3d %3d | %d/%d/%d%n",
                t,
                counts.getOrDefault(EntityType.RABBIT, 0),
                counts.getOrDefault(EntityType.WOLF, 0),
                counts.getOrDefault(EntityType.BEAR, 0),
                counts.getOrDefault(EntityType.ELEPHANT, 0),
                counts.getOrDefault(EntityType.FISH, 0),
                counts.getOrDefault(EntityType.GRASS, 0),
                counts.getOrDefault(EntityType.ALGAE, 0),
                hunger, thirst, predation);
    }

    // Thả con vật quanh một điểm gốc (bán kính ~50px) trên đúng loại địa hình.
    private static void seedNear(WorldMap world, Random rng, Class<?> kind,
                                 Vector2D base, TerrainType terrain) {
        if (base == null) return;
        for (int attempt = 0; attempt < 60; attempt++) {
            Vector2D p = base.add(new Vector2D(rng.nextDouble() * 100 - 50, rng.nextDouble() * 100 - 50));
            if (!world.isInside(p) || world.getTerrainAt(p) != terrain) continue;
            LivingEntity e = create(kind, p);
            if (e == null || !world.canStandOn(e, p)) continue;
            world.addEntity(e);
            return;
        }
    }

    private static LivingEntity create(Class<?> kind, Vector2D p) {
        if (kind == Wolf.class) {
            Wolf w = new Wolf(p);
            if (wolfQ != null) {
                w.setFixedStrategy(useMc
                        ? org.openjfx.app.core.strategies.MonteCarloStrategy
                                .play(wolfQ, org.openjfx.app.core.strategies.MonteCarloStrategy.Role.PREDATOR)
                        : org.openjfx.app.core.strategies.QLearningStrategy
                                .play(wolfQ, org.openjfx.app.core.strategies.QLearningStrategy.Role.PREDATOR));
            }
            return w;
        }
        if (kind == Bear.class) return new Bear(p);
        if (kind == Elephant.class) return new Elephant(p);
        if (kind == Fish.class) return new Fish(p);
        Rabbit r = new Rabbit(p);
        if (rabbitQ != null) {
            r.setFixedStrategy(useMc
                    ? org.openjfx.app.core.strategies.MonteCarloStrategy
                            .play(rabbitQ, org.openjfx.app.core.strategies.MonteCarloStrategy.Role.PREY)
                    : org.openjfx.app.core.strategies.QLearningStrategy
                            .play(rabbitQ, org.openjfx.app.core.strategies.QLearningStrategy.Role.PREY));
        }
        return r;
    }

    private static Vector2D randomTerrain(WorldMap world, Random rng, TerrainType type) {
        for (int i = 0; i < 120; i++) {
            Vector2D p = new Vector2D(rng.nextDouble() * W, rng.nextDouble() * H);
            if (world.getTerrainAt(p) == type) return p;
        }
        return null;
    }
}
