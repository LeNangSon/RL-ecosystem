package org.openjfx.app.entities.movable;

import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.entities.base.Herbivore;
import org.openjfx.app.entities.base.LivingEntity;

public class Rabbit extends Herbivore {

    public Rabbit(Vector2D position) {
        // hungerRate 1.2/s: 1 cụm cỏ (30 dinh dưỡng) nuôi ~25s; thirstRate 1.8/s: uống mỗi ~39s.
        super(position, 10.0, "circle", 100.0, 1.2, 1.8,
                32.0, 38.0, 0.5, 35.0, 12.0);
        setVisionRadius(50.0);
        type = EntityType.RABBIT;
        // Thỏ sinh nhanh nhất đàn (mồi phải đẻ nhanh hơn thú săn ăn). Đo 600s: 15s/lứa
        // bùng nổ >600 con rồi chết đói hàng loạt; 25s/lứa lại không hồi kịp sau đợt săn
        // đầu -> 18s/lứa + tốn 20 đói (phải ăn cỏ giữa 2 lứa) là điểm giữa.
        matureAge = 6.0;
        reproduceCooldownMax = 22.0;
        reproduceHungerCost = 20.0;
    }

    @Override
    protected LivingEntity createOffspring(Vector2D spawnPos) {
        return new Rabbit(spawnPos);
    }

    @Override
    public void update(double dt, WorldMap world) {
        super.update(dt, world);
    }

    @Override
    public String toString() {
        return "Rabbit#" + getId();
    }
}
