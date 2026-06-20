package org.openjfx.app.entities.staticobjs;

import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.entities.base.StaticEntity;

public abstract class Plant extends StaticEntity {
    private static final double DEFAULT_NUTRITION = 30.0;

    protected boolean alive;
    protected double nutrition;

    protected double reproduceTime;
    protected double reproduceTimer;

    // 0 = không mọc lại (bị xóa vĩnh viễn); > 0 = giây chờ để hồi sinh
    protected double regrowTime = 0;
    private double regrowTimer = 0;

    public Plant(Vector2D position, double size, String shape, double reproduceTime) {
        this(position, size, shape, reproduceTime, DEFAULT_NUTRITION);
    }

    public Plant(Vector2D position, double size, String shape,
                 double reproduceTime, double nutrition) {
        super(position, size, shape);
        this.reproduceTime = reproduceTime;
        // Lệch pha ngẫu nhiên: nếu mọi cây cùng đếm từ 0 (vd 35 cỏ seed cùng t=0) thì
        // chúng sinh sản đúng CÙNG MỘT frame mỗi `reproduceTime` giây -> dồn O(n^2) vào
        // một frame -> giật đều đặn. Rải pha để công việc trải ra nhiều frame.
        this.reproduceTimer = Math.random() * reproduceTime;
        this.nutrition = nutrition;
        this.alive = true;
    }

    public boolean isAlive() {
        return alive;
    }

    public boolean isRegrowing() {
        return !alive && regrowTimer > 0;
    }

    // True khi cần xóa khỏi map (chết hẳn, không hồi sinh).
    public boolean shouldBeRemoved() {
        return !alive && regrowTimer <= 0;
    }

    // Ăn 1 nhát: nếu có regrowTime thì bắt đầu đếm ngược hồi sinh, ngược lại chết hẳn.
    public double consume() {
        if (!alive) {
            return 0;
        }
        alive = false;
        if (regrowTime > 0) {
            regrowTimer = regrowTime;
        }
        reproduceTimer = 0;
        return nutrition;
    }

    @Override
    public void update(double dt, WorldMap world) {
        if (!alive) {
            if (regrowTimer > 0) {
                regrowTimer -= dt;
                if (regrowTimer <= 0) {
                    alive = true;
                }
            }
            return;
        }
        reproduceTimer += dt;
        if (reproduceTimer >= reproduceTime) {
            reproduce(world);
            reproduceTimer = 0;
        }
    }

    protected void reproduce(WorldMap world) {
        // Thử vài vị trí ngẫu nhiên để chọn được ô hợp lệ (theo canReproduceAt).
        for (int attempt = 0; attempt < 8; attempt++) {
            double newX = position.x + (Math.random() * 60 - 30);
            double newY = position.y + (Math.random() * 60 - 30);
            Vector2D candidate = new Vector2D(newX, newY);
            // BẮT BUỘC trong map: getTerrainAt mặc định trả LAND cho điểm NGOÀI bản đồ,
            // không chặn ở đây thì cỏ lan vô hạn ra ngoài màn hình (population bùng nổ
            // vượt trần mật độ + tụt FPS dần theo thời gian).
            if (world.isInside(candidate) && canReproduceAt(world, candidate)) {
                world.addEntity(createNewPlant(candidate));
                return;
            }
        }
    }

    // Mặc định: cây nào cũng có thể mọc bất kỳ đâu (kể cả ngoài địa hình mong muốn).
    // Lớp con override để giới hạn (ví dụ Algae chỉ mọc trên WATER).
    protected boolean canReproduceAt(WorldMap world, Vector2D position) {
        return true;
    }

    protected abstract Plant createNewPlant(Vector2D position);
}
