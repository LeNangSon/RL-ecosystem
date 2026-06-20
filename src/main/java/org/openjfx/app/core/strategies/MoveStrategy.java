package org.openjfx.app.core.strategies;

import java.util.List;

import org.openjfx.app.core.WorldMap;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;

/**
 * Interface định nghĩa cách một thực thể di chuyển.
 * Mỗi loại AI (Flee, Hunt, Wander) sẽ triển khai (implement) logic riêng ở hàm updateVelocity.
 */
public interface MoveStrategy {

    /**
     * Tính toán và cập nhật vận tốc cho thực thể dựa trên môi trường xung quanh.
     * * @param owner: Con thú đang thực hiện hành vi (ví dụ: con thỏ của Phương).
     * @param neighbors: Danh sách các thực thể xung quanh mà World quét được.
     * @param dt: Khoảng thời gian giữa 2 khung hình (delta time).
     */
    void updateVelocity(LivingEntity owner, List<Entity> neighbors, double dt, WorldMap world);
}