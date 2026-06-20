package org.openjfx.app.entities.staticobjs;

import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.terrain.TerrainType;

public class Bush extends Obstacle {

    public Bush(Vector2D position) {
        super(position, 15, "Bush", TerrainType.BUSH);
    }

    @Override
    public String toString() {
        return "org/openjfx/app/bush.png";
    }
}