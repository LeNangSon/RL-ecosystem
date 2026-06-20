package org.openjfx.app.entities.staticobjs;

import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;

public class Grass extends Plant {

    private static final double MIN_DISTANCE_FROM_GRASS = 22.0;
    private static final double REGROW_TIME = 30.0;
    // 20s/lần lan + cách nhau 22px: map phủ dần trong vài phút thay vì thảm cỏ kín
    // sau 3 phút như bộ cũ (10s/18px), đủ dư cho thỏ + voi ăn.
    private static final double REPRODUCE_TIME_SECONDS = 20.0;

    public Grass(Vector2D position) {
        super(position, 10, "Grass", REPRODUCE_TIME_SECONDS);
        this.type = EntityType.GRASS;
        this.regrowTime = REGROW_TIME;
    }

    @Override
    protected Plant createNewPlant(Vector2D position) {
        return new Grass(position);
    }

    @Override
    protected boolean canReproduceAt(WorldMap world, Vector2D position) {
        if (!isOnlyLand(world, position)) {
            return false;
        }
        double minSq = MIN_DISTANCE_FROM_GRASS * MIN_DISTANCE_FROM_GRASS;
        for (Entity e : world.getEntities()) {
            // Cỏ ĐANG MỌC LẠI (bị ăn, chờ hồi sinh) cũng chiếm chỗ: nếu bỏ qua nó thì cỏ
            // mới mọc đè lên, lúc cây cũ hồi sinh sẽ thành 2 cụm chồng nhau (phá trần mật độ).
            if (!(e instanceof Grass g) || (!g.isAlive() && !g.isRegrowing())) {
                continue;
            }
            double dx = e.getPosition().x - position.x;
            double dy = e.getPosition().y - position.y;
            if (dx * dx + dy * dy < minSq) {
                return false;
            }
        }
        return true;
    }

    /**
     * Kiểm tra tâm + 8 điểm gần (bán kính nhỏ) — tất cả phải là LAND.
     * Dùng nhiều điểm thay vì 1 để tránh miss edge của polygon nước trong TMX.
     */
    private boolean isOnlyLand(WorldMap world, Vector2D p) {
        double r = size / 2.0 + 4.0;
        double d = r * 0.7;
        Vector2D[] probes = {
            p,
            new Vector2D(p.x + r, p.y),  new Vector2D(p.x - r, p.y),
            new Vector2D(p.x, p.y + r),  new Vector2D(p.x, p.y - r),
            new Vector2D(p.x + d, p.y + d), new Vector2D(p.x + d, p.y - d),
            new Vector2D(p.x - d, p.y + d), new Vector2D(p.x - d, p.y - d),
        };
        for (Vector2D probe : probes) {
            if (world.getTerrainAt(probe) != TerrainType.LAND) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "org/openjfx/app/grass.png";
    }

}