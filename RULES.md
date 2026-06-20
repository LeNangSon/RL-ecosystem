# RULES — OOP-PRJ Ecosystem Simulation

> Quy tắc thiết kế & các bẫy nguy hiểm. Đọc trước khi chỉnh bất cứ thứ gì.

---

## Build Rules

- **KHÔNG** dùng `mvn clean` — project dùng dependencies offline, `clean` xóa cached jars → build fail.
- Xóa compiled output bằng: `rm -rf target/classes`
- Headless run (không GUI): dùng classpath thủ công từ `~/.m2` openjfx jars, KHÔNG dùng `javafx:run`

---

## Không được phá vỡ những thứ này

### 1. Hysteresis uống nước (SeekWaterStrategy + QLearningStrategy)
`THIRST_HIGH=70 (RBS) / 60 (RL)` vào, `THIRST_SATED=25` ra.
Nếu bỏ hysteresis: con RL dao động ở thirst ~55-75, chết khát trong pha rượt đuổi dài.

### 2. Sinh sản trong `autoInteract()` của QLearningStrategy
`fixedStrategy` chặn `MateStrategy` thường → sinh sản phải xảy ra trong `autoInteract()` khi chạm.
Nếu xóa: đàn RL TUYỆT CHỦNG vì không bao giờ đẻ con.

### 3. Sói gate đói (HUNT_HUNGER = 50)
RL sói chỉ được phép săn khi `hunger ≥ 50`, khớp với RBS Carnivore.
Nếu bỏ gate: sói RL săn 24/7 → diệt chủng toàn bộ thỏ.

### 4. Thỏ phản xạ panic (`FleeStrategy` khi địch < `visionRadius/2`)
Bảng Q 8 hướng kém với né tránh cận chiến liên tục → cần fallback luật cho vùng gần.
Nếu xóa: thỏ RL bị ăn nhiều gấp đôi trong thử nghiệm, đàn teo dần.

### 5. `HUNGER_SEEK` thỏ RL = 60 (khớp RBS Herbivore)
Nếu để 40: thỏ RL tìm cỏ đúng trong cửa sổ sinh sản (`canReproduce` cần `hunger < 50`) → không bao giờ sinh sản → đàn chết mòn.

### 6. Train XEN KẼ, không co-evolution
Khi cả hai bên cùng học đồng thời → không hội tụ (non-stationary environment).
Giải pháp hiện tại: mỗi pha một bên học, bên kia đóng băng (`epsilon = 0.05`, không cập nhật weights).

### 7. Trainer thả sói ĐÓI SẴN (`hunger = 55`)
Vì sói gated chỉ săn khi `hunger ≥ 50`. Nếu thả sói no (hunger = 0): toàn bộ episode đầu sói không học săn gì cả.

---

## Design Constraints

### Strategy Pattern — nguyên tắc
- `MoveStrategy` KHÔNG được modify entity state trực tiếp (không gọi `entity.setHunger()`).
- Strategy chỉ cập nhật `velocity` (thông qua `updateVelocity()`).
- Eat/drink/reproduce xảy ra trong `Entity.update()` hoặc `autoInteract()`, KHÔNG trong strategy.

### RL vs RBS — tách biệt rõ ràng
- `fixedStrategy != null` → RL mode: bỏ qua toàn bộ scoring của RBS.
- `fixedStrategy == null` → RBS mode: chạy `StrategyCandidate` scoring bình thường.
- Không trộn logic RL vào các class Carnivore/Herbivore — RL sống trong `QLearningStrategy` / `DeepQLearningStrategy`.

### Con đẻ ra sau KHÔNG kế thừa não RL
Con được spawn trong `pendingSpawns` luôn dùng RBS thuần.
Đây là thiết kế có chủ ý (đơn giản hóa, tránh copy state Q-table).

### State encoding — đổi format = PHẢI xóa qtable cũ
Format key string của `QTable` là `"P|e3,2|t1,4,1|m5|h1|w0"`.
Nếu thêm/bớt field: qtable cũ **vô nghĩa** (sẽ dùng giá trị sai).
→ Phải `rm qtables/wolf.qtable qtables/rabbit.qtable` trước khi train lại.

---

## Balance Guards (từ thực nghiệm BalanceCheck)

| Vấn đề | Triệu chứng | Fix đã áp dụng |
|--------|------------|----------------|
| Cỏ lan ra ngoài map | Grass spawn tại vị trí âm / > 576 | Clamp position trong spawn |
| Loài 1 con còn lại | Dân số drop đột ngột rồi không hồi phục | Tăng initial spawn count |
| Predator đẻ quá chậm | Sói/gấu 2 con cả game | Giảm `reproduceCooldownMax` hoặc giảm `reproduceHungerCost` |
| RL sói tuyệt chủng thỏ | Thỏ = 0 sau 200s | Kiểm tra gate đói + POUNCE thresholds |

**Threshold pass/fail BalanceCheck:**
- Không loài nào bị RL ép tuyệt chủng có hệ thống
- Số deaths predation nằm trong biên độ ngẫu nhiên của run RBS thuần

---

## Code Style (dự án này)

- **Không** comment giải thích WHAT (tên field/method đã nói lên điều đó).
- Comment chỉ khi WHY không hiển nhiên: constraint ẩn, workaround bug cụ thể.
- Không dùng emoji trong code/comment.
- Java 25 features được phép (records, sealed, pattern matching).
- Không thêm abstraction nếu chỉ có 1-2 use case — 3 chỗ tương đồng mới xem xét extract.

---

## Các thứ hay bị nhầm

| Nhầm | Đúng |
|------|------|
| `mvn clean javafx:run` | `rm -rf target/classes && mvn javafx:run` |
| Đổi state format → train tiếp | Xóa qtable → train lại từ đầu |
| Gắn não RL cho con đẻ ra | Chỉ gắn cho con thả ban đầu trong `MainApp.initQLearning()` |
| `co-evolution` cả hai bên cùng học | Xen kẽ: một bên học, một bên đóng băng |
| Test balance bằng mắt thường | Dùng `BalanceCheck 900 ql` và đọc output |
