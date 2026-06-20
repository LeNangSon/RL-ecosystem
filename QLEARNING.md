# Q-learning cho Sói & Thỏ (huấn luyện headless)

Tính năng cho phép **sói** và **thỏ** tự học hành vi bằng **Q-learning** (reinforcement
learning dạng bảng), huấn luyện ở chế độ **headless** — mô phỏng nhanh hàng nghìn lượt
chơi mà **không cần mở giao diện JavaFX**.

## Thành phần

| File | Vai trò |
|------|---------|
| `core/qlearning/QTable.java` | Bảng Q (trạng thái → giá trị từng hành động) + ε-greedy + lưu/đọc file |
| `core/strategies/QLearningStrategy.java` | `MoveStrategy` do Q-learning điều khiển (mã hoá trạng thái, chọn hành động, thưởng/phạt) |
| `core/qlearning/QLearningTrainer.java` | `main` huấn luyện headless (tái dùng `WorldMap` thật, không vẽ) |
| `train.sh` | Script tiện lợi: biên dịch + dựng classpath + chạy trainer |

Một thay đổi nhỏ hỗ trợ: `LivingEntity.setFixedStrategy(...)` để một cá thể **bỏ qua** cơ
chế chọn chiến lược theo điểm số và dùng cố định một chiến lược (ở đây là Q-learning).

## Huấn luyện

```bash
./train.sh                # mặc định: 2000 episode, 600 step/episode
./train.sh 5000 800       # train lâu hơn
```

Kết quả lưu vào `qtables/wolf.qtable` (chỉ **sói** học; thỏ dùng bộ chiến lược luật cố
định làm đối thủ). Chạy lại sẽ **nạp bảng cũ và train tiếp** — khi đó epsilon khởi đầu
chỉ 0.3 (thăm dò nhẹ quanh policy đã có) thay vì 1.0. Mỗi vài chục episode in một dòng
tiến độ:

```
ep    300 | eps 0.05 | catches/ep 1.50 | wolfStates 98
```

> Chạy tay không cần script:
> ```bash
> ./mvnw -o compile
> CP="target/classes"; for j in $(find ~/.m2/repository/org/openjfx -name '*.jar' | grep -vE 'sources|javadoc'); do CP="$CP:$j"; done
> java -cp "$CP" org.openjfx.app.core.qlearning.QLearningTrainer 2000 600
> ```

## Thiết kế MDP

- **Hành động (8):** 8 hướng la bàn. Strategy lái vận tốc về hướng được chọn.
- **Trạng thái (rời rạc hoá):** hướng + bin khoảng cách tới (a) **kẻ thù gần nhất**
  (con vật mình sợ) và (b) **mục tiêu ưu tiên** — nước (khi khát ≥60), nếu không thì mồi
  (sói luôn coi thỏ là mồi; thỏ coi cỏ là mồi khi đói ≥40) — kèm loại mục tiêu, hướng mồi
  đang chạy, bin đói và bin khát.
- **Hybrid RL + luật:** khi **không thấy** mục tiêu lẫn kẻ thù trong tầm nhìn, trạng thái
  không có thông tin để học → giao cho luật: khát thì `SeekWaterStrategy` (biết đường tới
  nước ngoài tầm nhìn), không thì `WanderStrategy`. RL chỉ học pha có mục tiêu, và con vật
  không bao giờ kẹt góc map vì một ô Q rỗng.
- **Phần thưởng (reward shaping):**
  - Sói: `-0.02`/bước (sức ép thời gian) · `+10` bắt được thỏ · `+0.3` uống nước ·
    `+` tiến lại gần mục tiêu · thưởng **áp sát** khi mồi vào 1/3 tầm nhìn · `-0.5` để
    mồi thoát khỏi tầm.
  - Thỏ: `+0.05`/bước (sống sót) · `+1` ăn cỏ · `+0.3` uống nước · `+` tiến tới mục
    tiêu · `+` khi **tăng** khoảng cách với sói.
  - Shaping theo khoảng cách chỉ áp khi mục tiêu hai bước **cùng loại** (đổi mồi → nước
    lúc khát thì hiệu khoảng cách vô nghĩa).
  - Chết (bị ăn / đói / khát): phạt cuối `-10` (cả hai vai) — chết phải đắt hơn một lần
    bắt mồi.
- **Chiết khấu `gamma = 0.97`** (horizon ~33 bước ≈ 3.3s mô phỏng, đủ trọn pha rượt mồi).
- **Ăn/uống khi chạm** được xử lý tự động (cơ chế thế giới), agent chỉ học **đi đâu**.
- **Khi chơi** (`QLearningStrategy.play`): không học thêm, epsilon = **0.05** (một chút
  ngẫu nhiên để policy tất định không kẹt vòng lặp).

Thỏ dùng luật cố định ("ngu" và dễ đoán) nên `catches/ep` **tăng dần rồi bão hoà** — đó
là tín hiệu sói đang học được.

## Dùng não đã học trong giao diện (UI)

`MainApp` **đã nối sẵn** sau một cờ JVM. Chạy với **`-Dql=true`** thì sói & thỏ (cả con seed
lẫn con đặt tay) dùng não RL; không có cờ thì dùng AI cũ. Nếu chưa có `qtables/*.qtable`,
app tự quay về AI cũ kèm cảnh báo.

```bash
./mvnw -o javafx:run -Dql=true      # bật RL
./mvnw -o javafx:run                # AI cũ (mặc định)
```

Trong IntelliJ: thêm `-Dql=true` vào **VM options** của Run configuration.

Bên dưới là cách nối tay (nếu muốn tự tuỳ biến thay vì dùng cờ): nạp bảng rồi
`setFixedStrategy` + `QLearningStrategy.play` (ε=0.05, không học thêm):

```java
import java.nio.file.Paths;
import org.openjfx.app.core.qlearning.QTable;
import org.openjfx.app.core.strategies.QLearningStrategy;
import org.openjfx.app.core.strategies.QLearningStrategy.Role;

// Nạp một lần khi khởi động:
QTable wolfQ   = QTable.loadOrNew(Paths.get("qtables", "wolf.qtable"),   QLearningStrategy.NUM_ACTIONS);
QTable rabbitQ = QTable.loadOrNew(Paths.get("qtables", "rabbit.qtable"), QLearningStrategy.NUM_ACTIONS);

// Khi tạo con vật:
Rabbit r = new Rabbit(pos);
r.setFixedStrategy(QLearningStrategy.play(rabbitQ, Role.PREY));
worldMap.addEntity(r);

Wolf w = new Wolf(pos);
w.setFixedStrategy(QLearningStrategy.play(wolfQ, Role.PREDATOR));
worldMap.addEntity(w);
```

## Tinh chỉnh

Trong `QLearningTrainer`: `alpha` (tốc độ học), `gamma` (chiết khấu), số sói/thỏ/cỏ mỗi
episode, `DT`. Lịch giảm `epsilon` nằm trong vòng lặp `main`. Cấu trúc trạng thái và phần
thưởng nằm trong `QLearningStrategy` (`encode`, `stepReward`).
