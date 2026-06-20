package org.openjfx.app.entities.staticobjs;

import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.terrain.TerrainType;

public class Rock extends Obstacle {

    public Rock(Vector2D position) {
        super(position, 15, "Rock", TerrainType.ROCK);
    }

    @Override
    public String toString() {
        return "org/openjfx/app/rock.png";
    }
}
