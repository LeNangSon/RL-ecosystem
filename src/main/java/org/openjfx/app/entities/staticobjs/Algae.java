package org.openjfx.app.entities.staticobjs;

import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;

public class Algae extends Plant {

    // Fish hungerRate = 0.5/giây -> hunger 0->70 = 140 giây.
    // Tốc độ sinh sản tảo khớp đúng nhịp đó để cá luôn có đủ thức ăn vừa phải.
    private static final double REPRODUCE_TIME_SECONDS = 140.0;
    private static final double MIN_DISTANCE_FROM_ALGAE = 18.0;

    public Algae(Vector2D position) {
        super(position, 10, "Algae", REPRODUCE_TIME_SECONDS);
        this.type = EntityType.ALGAE;
    }

    @Override
    protected Plant createNewPlant(Vector2D position) {
        return new Algae(position);
    }

    @Override
    protected boolean canReproduceAt(WorldMap world, Vector2D position) {
        if (world.getTerrainAt(position) != TerrainType.WATER) {
            return false;
        }
        double minSq = MIN_DISTANCE_FROM_ALGAE * MIN_DISTANCE_FROM_ALGAE;
        for (Entity e : world.getEntities()) {
            if (!(e instanceof Algae) || !((Algae) e).isAlive()) {
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

    @Override
    public String toString() {
        return "org/openjfx/app/algea.png";
    }
}
