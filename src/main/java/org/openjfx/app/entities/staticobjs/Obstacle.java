package org.openjfx.app.entities.staticobjs;

import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.terrain.TerrainProvider;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.StaticEntity;

public abstract class Obstacle extends StaticEntity implements TerrainProvider {

    private final TerrainType terrainType;

    public Obstacle(Vector2D position, double size, String shape, TerrainType terrainType) {
        super(position, size, shape);
        this.terrainType = terrainType;
    }

    @Override
    public TerrainType getTerrainType() {
        return terrainType;
    }

    @Override
    public void update(double dt, WorldMap world) {
        // Chướng ngại vật không làm gì
    }
}