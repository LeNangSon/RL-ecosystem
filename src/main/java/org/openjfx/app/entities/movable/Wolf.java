package org.openjfx.app.entities.movable;

import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.strategies.HunterStrategy;
import org.openjfx.app.entities.base.Carnivore;
import org.openjfx.app.entities.base.LivingEntity;

public class Wolf extends Carnivore {

    public Wolf(Vector2D position) {
        // hungerRate 0.65/s: no->đói săn (50) sau ~77s, chết đói sau ~195s nếu không bắt được
        // mồi — thú săn PHẢI chịu đói được lâu vì gặp mồi trên map này là sự kiện hiếm
        // (đo 600s với 1.2/s: sói ăn sạch cụm thỏ đầu rồi cả đàn chết đói dây chuyền).
        // thirstRate 1.5/s: phải uống mỗi ~47s (ngưỡng 70) — đủ áp lực nhưng không chết khát
        // sau 20s như bộ số cũ (5/s) vốn là nguyên nhân chính sói chết hàng loạt.
        super(position, 30.0, "circle", 200.0, 0.5, 1.5,
                40.0, 70.0, 3.0, 50.0, 30.0);
        setVisionRadius(70.0);
        type = EntityType.WOLF;
        moveStrategy = new HunterStrategy();
        // Đẻ chậm để đàn sói không phình theo đỉnh bùng nổ của thỏ rồi chết sạch khi
        // đàn thỏ sụp (đo 900s với 30s/lứa: sói 2->5 con rồi tuyệt chủng ở pha sụp).
        matureAge = 15.0;
        reproduceCooldownMax = 50.0;
        reproduceHungerCost = 35.0;
    }

    @Override
    protected LivingEntity createOffspring(Vector2D spawnPos) {
        return new Wolf(spawnPos);
    }

    @Override
    public void update(double dt, WorldMap world) {
        super.update(dt, world);
    }

    @Override
    public String toString() {
        return "Wolf#" + getId();
    }
}
