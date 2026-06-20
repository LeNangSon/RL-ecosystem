# COMPARE RL vs RBS — Phương pháp so sánh

> Mô tả phương pháp đo & so sánh ba "não" điều khiển SÓI: RBS / QL / MC. Tài liệu nêu rõ
> **môi trường khởi tạo** và **các metric**. CHƯA có kết quả — tham số RL vẫn đang được tinh
> chỉnh, sẽ điền số sau khi train ổn định.
>
> Công cụ: `core/BrainCompare.java` (chạy headless). Liên quan: `RLworkflow.md`, `RBSworkflow.md`.

---

## 1. Mục tiêu & nguyên tắc so sánh

So **ba bộ điều khiển SÓI** trên CÙNG điều kiện thí nghiệm, **so theo cặp** (paired) trên cùng seed:

| Não | Mô tả |
|-----|-------|
| **RBS** | sói không gắn `fixedStrategy` → `selectBest` + A* (tất định, không epsilon) |
| **QL**  | `QLearningStrategy.play(wolf.qtable)` — tabular Q-learning |
| **MC**  | `MonteCarloStrategy.play(mc_wolf.qtable)` — Monte Carlo Control |

**Biến kiểm soát (giữ cố định giữa 3 nhánh):** map, vị trí spawn, số lượng, seed, con mồi.
**Biến thay đổi (cái duy nhất khác nhau):** bộ điều khiển của sói.

Vì RL và RBS dùng **chung tập strategy + chung A*** (rl_v03), phép so chỉ phản ánh khác biệt
ở **bộ chọn strategy** — không lẫn khác biệt điều hướng.

---

## 2. Môi trường khởi tạo (mỗi seed)

| Tham số | Giá trị | Ghi chú |
|---------|---------|---------|
| Kích thước map | 576 × 576 px | scale từ `all.tmx` (SOURCE 1248, TILE 32) |
| Cỏ (GRASS) | 50 | rải trên ô LAND ngẫu nhiên |
| Thỏ (RABBITS) | 10 (sàn) | **luôn RBS** ở mọi nhánh |
| Sói (WOLVES) | 3 | gắn não theo nhánh đang đo |
| DT | 0.1 s/step | |
| Mặc định | 20 seed × 3000 step | ≈ 300 s mô phỏng/seed; đối số CLI ghi đè |

### Điều kiện đặc biệt (để công bằng & ổn định áp lực)

- **Thế giới cô lập**: chỉ sói + thỏ + cỏ. Nhờ vậy `getDeathCount(RABBIT, PREDATION)` = **đúng
  số mồi do SÓI bắt**, không lẫn gấu/voi.
- **Sói thả đói sẵn `hunger = 55`** (ngưỡng săn 50) → cả 3 não khởi động cùng điều kiện, tránh
  episode đầu sói no không săn.
- **Bù thỏ về sàn 10** mỗi bước (áp lực mồi không tắt); **KHÔNG bù sói** → đo được tuổi thọ &
  chết đói/khát của sói.
- **Cùng seed → cùng vị trí spawn** giữa 3 nhánh. RNG epsilon của RL lấy **seed riêng tất định**
  theo `(seed × hằng + chỉ số sói)`, KHÔNG lấy từ RNG thế giới (nếu không sẽ lệch vị trí spawn
  giữa các nhánh). Mỗi sói một dòng RNG riêng để 3 sói không hành xử epsilon giống hệt.
- **Eval epsilon = 0** (`play(...)` đặt epsilon 0.0): RL tất định như RBS để so công bằng (bỏ
  handicap bước ngẫu nhiên). Macro-action ít kẹt vòng lặp hơn bản 8-hướng nên ε=0 an toàn.

---

## 3. Các METRIC

In ra dạng `trung bình ± độ lệch chuẩn` trên các seed (`printReport`). Reset bộ đếm A* ngay
trước vòng mô phỏng (spawn không tính vào A*).

| Metric | Hướng tốt | Ý nghĩa |
|--------|-----------|---------|
| **Bắt/1000 step** | cao | hiệu quả săn = `catches·1000/steps`. **Chỉ số chính.** |
| **Tuổi thọ TB sói (step)** | cao | trung bình `(deathStep − birthStep)`; sống tới hết = maxSteps |
| **Sói chết đói** | thấp | `getDeathCount(WOLF, HUNGER)` |
| **Sói chết khát** | thấp | `getDeathCount(WOLF, THIRST)` |
| **Step tới lần bắt đầu tiên** | thấp | bước đầu tiên có mồi bị bắt; = maxStep nghĩa là **không bắt được** (bị kiểm duyệt) |
| **CPU ms/step** | thấp | chi phí tính toán (đại diện) |
| **A* gọi/step** | (tham khảo) | tải tìm đường — RL & RBS chung A* nên so công bằng |
| **A* node/step** | (tham khảo) | số node A* duyệt/step |

> Lưu ý phương pháp: "Step tới lần bắt đầu tiên" = maxStep ⇒ trong seed đó sói **không** bắt
> được con nào. CPU/step là đại diện chi phí, không phải đo path-length tuyệt đối.

---

## 4. Cách chạy

```bash
# Cần qtables/wolf.qtable (QL) và qtables/mc_wolf.qtable (MC). Thiếu bảng nào -> bỏ qua nhánh đó.
rm -rf target/classes && mvn -o compile -q          # (KHÔNG dùng mvn clean — xem RULES.md)

CP="target/classes"
for jar in $(find "$HOME/.m2/repository/org/openjfx" -name "*.jar" | grep -vE "sources|javadoc"); do
  CP="$CP:$jar"
done

# Mặc định 20 seed × 3000 step:
java -cp "$CP" org.openjfx.app.core.BrainCompare
# Hoặc tùy chỉnh [seeds] [steps]:
java -cp "$CP" org.openjfx.app.core.BrainCompare 20 3000
java -cp "$CP" org.openjfx.app.core.BrainCompare 8  2000   # nhanh, để tinh chỉnh
```

Quy trình tinh chỉnh đề xuất (ablation): đổi **một** yếu tố (reward / γ / action) → train lại →
BrainCompare → so với baseline. Mỗi lần đổi đúng một thứ để biết cái gì gây hiệu ứng.

---

## 5. Bảng kết quả (CHƯA điền — đang tinh chỉnh tham số)

> Điền sau khi train ổn định. Định dạng: `mean ± sd` trên 20 seed × 3000 step.

| Metric | RBS | QL | MC |
|--------|-----|----|----|
| Bắt/1000 step (cao=tốt) | _ | _ | _ |
| Tuổi thọ TB sói (step) | _ | _ | _ |
| Sói chết đói | _ | _ | _ |
| Sói chết khát | _ | _ | _ |
| Step tới lần bắt đầu tiên | _ | _ | _ |
| CPU ms/step (thấp=tốt) | _ | _ | _ |
| A* gọi/step | _ | _ | _ |
| A* node/step | _ | _ | _ |

**Tiêu chí "lật dấu" RL > RBS:** `Bắt/1000 step` của QL hoặc MC ≥ RBS, đồng thời không tệ hơn
rõ rệt ở tuổi thọ / chết đói-khát. Kỳ vọng đòn bẩy: 4 kiểu săn (lead 0/0.7/1.3/2.0) cho RL chọn
chế độ săn theo tình huống mà RBS (1 kiểu cố định) không làm được.

---

## 6. Cấu hình hằng trong BrainCompare

| Hằng | Giá trị |
|------|---------|
| `W, H` | 576, 576 |
| `GRASS` | 50 |
| `RABBITS` | 10 |
| `WOLVES` | 3 |
| `DT` | 0.1 |
| seeds (mặc định) | 20 |
| steps (mặc định) | 3000 |
| qtable QL | `qtables/wolf.qtable` |
| qtable MC | `qtables/mc_wolf.qtable` |

> File tên gốc yêu cầu là "CompareRL&RBS"; đặt thành `CompareRL_vs_RBS.md` để tránh ký tự `&`
> gây phiền trong shell/đường dẫn.
