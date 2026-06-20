package org.openjfx.app.entities.movable;

import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.entities.base.Carnivore;
import org.openjfx.app.entities.base.LivingEntity;

public class Bear extends Carnivore {

    public Bear(Vector2D position) {
        // Gấu trao đổi chất chậm hơn sói: đói săn (50) sau ~83s, chết đói sau ~206s,
        // uống mỗi ~58s. Gấu chậm (36 px/s) nên săn trượt nhiều, cần trữ đói lớn.
        super(position, 30, "circle", 200.0, 0.6, 1.2,
                36.0, 44.0, 12.0, 70.0, 35.0);
        setVisionRadius(80.0);
        type = EntityType.BEAR;
        // Gấu đứng đầu chuỗi thức ăn, không thiên địch -> đẻ chậm để không phình vô hạn.
        // NHƯNG đừng hãm quá tay: đo 900s với 180s/lứa, đàn thú săn không tăng kịp theo
        // boom thỏ -> 269 thỏ, cỏ sụp, chết đói hàng loạt. Thú săn tăng theo đàn mồi
        // chính là cơ chế tự cân bằng (Lotka-Volterra).
        matureAge = 35.0;
        reproduceCooldownMax = 110.0;
        reproduceHungerCost = 50.0;
    }

    public void setVisionRadius(double visionRadius) {
        this.visionRadius = visionRadius;
    }
    @Override
    protected LivingEntity createOffspring(Vector2D spawnPos) {
        return new Bear(spawnPos);
    }

    @Override
    public void update(double dt, WorldMap world) {
        super.update(dt, world);
    }

    @Override
    public String toString() {
        return "Bear#" + getId();
    }
}
