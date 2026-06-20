package org.openjfx.app.entities.base;

import org.openjfx.app.core.Vector2D;

public abstract class StaticEntity extends Entity {

    public StaticEntity(Vector2D position, double size, String shape) {
        super(position, size, shape);
    }

    // Các lớp con (như Grass, Rock) tự định nghĩa hàm update().
    // Ví dụ: Grass có thể dùng update() để đếm thời gian mọc lại sau khi bị ăn.
}