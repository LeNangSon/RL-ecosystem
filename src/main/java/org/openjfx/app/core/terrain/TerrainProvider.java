package org.openjfx.app.core.terrain;

import org.openjfx.app.core.Vector2D;

/**
 * Objects placed on the map that define walkable terrain at their footprint.
 */
public interface TerrainProvider {

    TerrainType getTerrainType();

    Vector2D getPosition();

    double getSize();

    default boolean covers(Vector2D point) {
        if (point == null) return false;
        double radius = getSize() * 0.5;
        return getPosition().distance(point) <= radius;
    }
}
