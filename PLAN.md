# PLAN — OOP-PRJ Ecosystem Simulation

> Trạng thái dự án, những gì đã xong, và hướng phát triển tiếp theo.
> Cập nhật file này sau mỗi milestone lớn.

---

## Trạng thái hiện tại (2026-06-15)

Branch đang làm việc: `rl_v01`

### Đã hoàn thành

- [x] **Nền tảng simulation**: Entity hierarchy, WorldMap, game loop 60FPS, terrain, collision
- [x] **RBS (Rule-Based System)**: Tất cả strategy cho sói, thỏ, gấu, voi, cá — HunterStrategy (A* + leading), FleeStrategy, SeekWaterStrategy, MateStrategy, WanderStrategy
- [x] **RelationManager**: Quan hệ ăn / sợ giữa các loài
- [x] **Tabular Q-learning**: Wolf + Rabbit, train headless (`train.sh`), lưu/nạp `qtables/`
- [x] **DQN (Deep Q-Network)**: Wolf only, `train_dqn.sh`, `qtables/wolf.dqn`
- [x] **Alternating training pipeline**: Xen kẽ wolf↔rabbit, không co-evolution
- [x] **BalanceCheck harness**: Headless, kiểm tra balance 900s, output stats
- [x] **Hybrid RL+RBS guards**: 6 guard quan trọng (xem RULES.md)
- [x] **Balance đã chốt**: Bộ thông số sinh thái ổn định (2026-06-11)
- [x] **Map TMX migration**: CHANGELOG_MAP_MIGRATION.md

---

## Hướng phát triển tiếp theo (chưa làm)

### Ngắn hạn

- [ ] **Monte Carlo Control** (đang cân nhắc)
  - Thay thế hoặc so sánh với tabular Q-learning
  - Phù hợp vì episode simulation có điểm kết thúc rõ ràng
  - Ưu điểm: không cần reward shaping step-by-step, chỉ cần return cuối episode
  - Cần thiết kế: episode boundary là gì? (death của entity? số step cố định?)
  - File mới cần: `MonteCarloAgent.java`, `MonteCarloStrategy.java`, `MonteCarloTrainer.java`
  - So sánh với QTable bằng BalanceCheck

- [ ] **Population graph UI**: Vẽ số lượng từng loài theo thời gian trong game

- [ ] **Chiều sâu sinh thái**: Mùa ảnh hưởng đến cỏ (đã có mùa, nhưng chưa tác động mạnh)

### Trung hạn

- [ ] **Kế thừa não (RL inheritance)**: Con sinh ra có Q-table từ cha mẹ (copy + noise)
- [ ] **Multi-agent RL chính thức**: Ổn định co-evolution bằng self-play hoặc population-based training
- [ ] **Thêm loài mới**: Cần cân nhắc kỹ RelationManager + BalanceCheck

### Ý tưởng chưa quyết định

- Curriculum learning cho RL (bắt đầu với env đơn giản hơn)
- Policy gradient thay vì value-based (PPO cho con sói?)
- Visualization training curve realtime

---

## Các file quan trọng để đọc nhanh

| Mục tiêu | Đọc file nào |
|----------|-------------|
| Hiểu toàn bộ kiến trúc | `ARCHITECTURE.md` (file này) |
| Các ràng buộc & bẫy | `RULES.md` |
| Q-learning chi tiết | `QLEARNING.md` |
| Game loop entry | `src/main/java/org/openjfx/app/MainApp.java` |
| Strategy selection | `src/main/java/org/openjfx/app/entities/base/Carnivore.java` |
| RL strategy (core logic) | `src/main/java/org/openjfx/app/core/strategies/QLearningStrategy.java` |
| Q-table impl | `src/main/java/org/openjfx/app/core/qlearning/QTable.java` |
| Balance test | `src/main/java/org/openjfx/app/core/BalanceCheck.java` |

---

## Lịch sử quyết định lớn

| Ngày | Quyết định | Lý do |
|------|-----------|-------|
| 2026-06 | Xen kẽ train, không co-evolution | Co-evolution không hội tụ trong env này |
| 2026-06 | Thỏ HUNGER_SEEK = 60 (không phải 40) | Để 40 thỏ không bao giờ sinh sản |
| 2026-06 | Panic flee cho thỏ khi địch gần | Q-table 8 hướng kém với né tránh liên tục |
| 2026-06-11 | Chốt bộ thông số balance | BalanceCheck 900s ổn định |
| 2026-06 | Con đẻ ra dùng RBS thuần | Đơn giản hóa, tránh copy Q-table state |

---

## Cách chạy nhanh để verify

```bash
# Kiểm tra balance sau khi thay đổi thông số
java -cp <CP> org.openjfx.app.core.BalanceCheck 900

# Kiểm tra balance với RL
java -cp <CP> org.openjfx.app.core.BalanceCheck 900 ql

# Train nhanh 200 episode để test pipeline
./train.sh wolf   # rồi check qtables/wolf.qtable tồn tại

# Chạy app với RL
mvn javafx:run -Dql=true
```
