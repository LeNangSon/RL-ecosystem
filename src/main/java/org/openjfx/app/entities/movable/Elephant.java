package org.openjfx.app.entities.movable;

import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.entities.base.Herbivore;
import org.openjfx.app.entities.base.LivingEntity;

public class Elephant extends Herbivore {

    public Elephant(Vector2D position) {
        // Bộ cũ (đói 5/s, khát 6/s) khiến voi chết liên tục: 1 cụm cỏ chỉ nuôi được 6s.
        // Voi to xác trao đổi chất CHẬM nhất + đi chậm nhất (26 px/s): đói 0.5/s
        // (1 cỏ nuôi 60s), khát 1.5/s.
        super(position, 30.0, "rect", 100.0, 0.5, 1.5,
                26.0, 36.0, 10.0, 100.0, 40.0);
        setVisionRadius(50.0);
        // Khát sẵn 60 (dưới ngưỡng nguy hiểm 75): vẫn ra hồ uống sớm nhưng không cận kề cái chết.
        setThirst(60.0);
        type = EntityType.ELEPHANT;
        setVelocity(new Vector2D(8.0, 0.0));
        // Voi không có thiên địch -> không gì ghìm số lượng ngoài tốc độ đẻ. Đo 600s với
        // 60s/lứa: voi bùng 2->15 con. Voi thật đẻ chậm nhất rừng — ở đây cũng vậy.
        matureAge = 40.0;
        reproduceCooldownMax = 120.0;
        reproduceHungerCost = 60.0;
    }

    @Override
    protected LivingEntity createOffspring(Vector2D spawnPos) {
        return new Elephant(spawnPos);
    }

    @Override
    public void update(double dt, WorldMap world) {
        super.update(dt, world);
    }

    @Override
    public String toString() {
        return "Elephant#" + getId();
    }
}
