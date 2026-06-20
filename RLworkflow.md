# RL WORKFLOW — Luồng hoạt động Reinforcement Learning (rl_v03)

> Tài liệu mô tả ĐẦY ĐỦ luồng RL: action, encode, stepReward, công thức cập nhật,
> vòng học mỗi bước, kết thúc episode, và pipeline train. Áp dụng cho cả hai "não" học:
> `QLearningStrategy` (TD) và `MonteCarloStrategy` (MC) — hai class này song song,
> dùng CHUNG encode / action / reward, chỉ khác **công thức cập nhật**.
>
> ⚠️ Một số hệ số reward đang trong quá trình tinh chỉnh (xem mục 4) — giá trị ghi ở đây
> là trạng thái hiện tại của code, không phải chốt cuối.

---

## 0. Ý tưởng tổng quát — Hierarchical RL / Options
 RL học
**chọn macro-action** = chọn 1 trong 8 chiến lược luật; chiến lược được chọn tự lo điều
hướng (A*) và ăn/uống/đẻ.

```
        ┌─────────────┐   chọn action    ┌──────────────────────────────┐
 state ─►   Q-table   ├─────────────────►│  options[action]             │
        └─────────────┘   (ε-greedy)     │  .updateVelocity(...)        │
                                         │  (A* + eat/drink/reproduce)  │
                                         └──────────────────────────────┘
```

Hệ quả thiết kế: **RL và RBS dùng chung bộ primitive + chung A***; chỉ khác **bộ chọn** —
RBS chấm điểm tay (`StrategyCandidate.selectBest`), RL học bằng Q-table. Nhờ đó so sánh
RL vs RBS là công bằng (xem `CompareRL_vs_RBS.md`).

---

## 1. Không gian ACTION (8 macro-action)

`NUM_ACTIONS = 8`. Index PHẢI khớp mảng `options(owner)` trong cả hai strategy:

| Index | Strategy | Ý nghĩa |
|-------|----------|---------|
| 0 | `FleeStrategy` | chạy trốn kẻ thù |
| 1 | `SeekWaterStrategy` | tìm & uống nước (hysteresis) |
| 2 | `MateStrategy` | tìm bạn tình để sinh sản |
| 3 | `WanderStrategy` | lang thang (fallback) |
| 4 | `HunterStrategy(lead 0.0)` | **Hunt-direct** — lao thẳng vào mồi |
| 5 | `HunterStrategy(lead 0.7)` | **Hunt-short** — đón đầu ngắn (≡ RBS mặc định) |
| 6 | `HunterStrategy(lead 1.3)` | **Hunt-long** — cắt góc, đón xa hơn |
| 7 | `HunterStrategy(lead 2.0)` | **Hunt-ambush** — đón sâu / mai phục |

`HUNT_LEADS = {0.0, 0.7, 1.3, 2.0}` (0.7 = `HunterStrategy.DEFAULT_LEAD_TIME`).

**Vì sao 4 kiểu săn:** RBS chỉ dùng 1 `HunterStrategy` cố định (lead 0.7). 4 kiểu săn của RL
là **SUPERSET** của RBS → về lý thuyết trần RL ≥ trần RBS. `leadTimeCap` quyết định điểm
đón đầu: `aim = preyPos + preyVel * min(dist/speed, leadTimeCap)` (xem `HunterStrategy.leadAimPoint`).
Ở cự ly gần, các kiểu săn hội tụ về nhau (vì `dist/speed < leadTimeCap`); chỉ khác biệt ở
cự ly trung-xa.

Khởi tạo lười (`options == null`) vì SeekWater/Wander cần `wanderDistance/wanderRadius` của owner.

---

## 2. STATE ENCODING — `encode(owner, neighbors, world)`

Rời rạc hoá quan sát thành **chuỗi key** cho Q-table. Định dạng:

```
P|e3,2|t1,5,1|m4|h1|w0|p0
^  ^      ^     ^  ^  ^  ^
|  |      |     |  |  |  └ p = mateBin (có thể đẻ + có bạn tình kề)  0/1
|  |      |     |  |  └─── w = thirstBin (đang ở chế độ uống)         0/1
|  |      |     |  └────── h = hungerBin (hunger ≥ 50?)               0/1
|  |      |     └───────── m = preyMoveDir (hướng mồi đang chạy 0-7, 8=đứng yên)
|  |      └─────────────── t = targetType,targetDir,targetBin
|  └────────────────────── e = enemyDir,enemyBin
└───────────────────────── role: P=PREDATOR (sói), R=PREY (thỏ)
```

### Các trường

- **enemyDir** `0-7` = sector hướng tới kẻ thù gần nhất (`nearestScaredOf`); `8` nếu không thấy.
- **enemyBin** = `distBin` khoảng cách kẻ thù.
- **targetType** `0` = không có, `1` = mồi/cỏ, `2` = nước.
- **targetDir** `0-7` = sector hướng tới mục tiêu; `8` nếu không có.
- **targetBin** = `distBin` khoảng cách mục tiêu.
- **preyMoveDir** = sector vận tốc mồi (để sói "đoán" hướng mồi mà cắt góc); `8` nếu mồi đứng
  yên / không phải vật động.
- **hungerBin** = `1` nếu `hunger ≥ 50`.
- **thirstBin** = `1` nếu đang trong chế độ uống (hysteresis).
- **mateBin** (mới ở rl_v03) = `1` nếu `canReproduce() && hasMateNearby()` → để RL học khi nào chọn Mate.

### Hàm bin khoảng cách — `distBin(dist, vision)`

| dist | bin |
|------|-----|
| `< 0` (không có) | 0 |
| `< vision/3` | 1 (gần) |
| `< 2·vision/3` | 2 (vừa) |
| ngược lại | 3 (xa) |

### Logic chọn mục tiêu (target) trong encode

1. **Hysteresis nước (2 mức):** `thirstCommit` bật khi `thirst ≥ THIRST_HIGH (60)`, tắt khi
   `thirst ≤ THIRST_SATED (25)`. Đang khát → mục tiêu là **nước** (targetType 2).
2. Không khát → tìm **mồi/cỏ** gần nhất (`nearestPreyEntity`, loại mồi núp bụi & xác chết).
   Chỉ coi là mục tiêu (targetType 1) khi đủ đói:
   - Sói: `hunger ≥ HUNT_HUNGER (50)`
   - Thỏ: `hunger ≥ HUNGER_SEEK (60)`
3. Không khát, không đủ đói/không thấy mồi → targetType 0.

> ⚠️ Đổi format key = phải **xóa qtable cũ** (key cũ vô nghĩa). Xem `RULES.md`.

### Side-effects của encode (dùng lại trong stepReward)
`curVision, curEnemyDist, curTargetDist, curTargetType, curThirsty, thirstCommit`.

---

## 3. VÒNG HỌC MỖI BƯỚC — `updateVelocity(owner, neighbors, dt, world)`

Giống hệt nhau ở 2 strategy, chỉ khác dòng "học" (★):

```
1. state = encode(...)                         // đặt cur* fields
2. NẾU training & có prevState:                // chấm điểm hành động BƯỚC TRƯỚC (1-step lag)
     ★ QL:  q.update(prevState, prevAction, stepReward(), state, alpha, gamma)   // học NGAY
     ★ MC:  agent.setLastReward(stepReward())                                    // ghi vào buffer
3. action = q.selectAction(state, epsilon, rng)        // ε-greedy
4. hungerBefore, thirstBefore = đo trước
5. options(owner)[action].updateVelocity(...)          // ỦY QUYỀN strategy luật (A*+ăn/uống/đẻ)
6. (MC) agent.record(state, action)                    // ghi (s,a); reward điền ở bước sau
7. Phát hiện sự kiện qua DELTA:
     hungerDrop = hungerBefore - hunger;  thirstDrop = thirstBefore - thirst
     pendingCaught = PREDATOR && hungerDrop > 1.0    // Carnivore.eat đặt hunger=0 khi giết
     pendingAte    = PREY     && hungerDrop > 0.01
     pendingDrank  = thirstDrop > 0.01
8. Lưu prevState/prevAction/prevTargetDist/prevEnemyDist/prevTargetType
```

**1-step lag:** reward của `(prevState, prevAction)` chỉ tính được ở bước sau, vì lúc đó mới
quan sát được hệ quả (đã lại gần mồi chưa, hunger/thirst có giảm không).

**Phát hiện sự kiện qua delta:** vì việc ăn/uống/giết nằm trong các strategy (không còn
`autoInteract`), ta suy sự kiện từ chênh lệch hunger/thirst trước–sau. `Carnivore.eat` đặt
`hunger=0` khi giết mồi → drop lớn → `pendingCaught`. (Lưu ý: ngưỡng `1.0` là proxy, có thể
nhiễu nếu sói gặm xác nhiều frame — điểm cần cải thiện.)

---

## 4. HÀM THƯỞNG — `stepReward()` (OUTCOME-BASED)

Thiết kế cho tầng macro-action: thưởng theo **KẾT QUẢ** của option (bắt/ăn/uống/sống), KHÔNG
dùng shaping khoảng cách dày như bản 8-hướng. Shaping cũ (`+0.05·Δdist`, `+0.4` áp sát,
`-0.5` mất dấu) **phạt oan** các kiểu săn trễ (ambush/lead-long) vì chúng tạm thời không tiến
gần → RL không dám chọn → không khai thác được superset action.

```java
double r;
if (role == PREDATOR) {
    r = -0.02;                       // sức ép thời gian: bắt nhanh thì lời
    if (pendingCaught) r += 10.0;    // KẾT QUẢ: bắt được mồi
    if (pendingDrank)  r += 4;       // [đang chỉnh] chống chết khát
} else { // PREY
    r = 0.05;                        // còn sống thêm 1 bước
    if (pendingAte)   r += 1.0;
    if (pendingDrank) r += 4;        // [đang chỉnh]
}
// Tiến-gần mục tiêu — BẤT ĐỐI XỨNG (chỉ thưởng khi lại gần; lùi -> 0, KHÔNG phạt).
// Nhờ "không phạt lùi", ambush/lead-long (tạm lùi để đón) không bị trừng phạt.
if (prevTargetType == curTargetType && prevTargetDist >= 0 && curTargetDist >= 0) {
    double closed = prevTargetDist - curTargetDist;
    if (closed > 0) r += 0.03 * closed;
}
// Thỏ: thưởng khi tăng khoảng cách với sói (chạy trốn không phải tactic trễ -> giữ 2 chiều).
if (role == PREY && prevEnemyDist >= 0 && curEnemyDist >= 0) {
    r += 0.06 * (curEnemyDist - prevEnemyDist);
}
return r;
```

### Bảng tổng hợp reward

| Sự kiện | Sói (PREDATOR) | Thỏ (PREY) |
|---------|----------------|------------|
| Mỗi bước (nền) | −0.02 | +0.05 |
| Bắt được mồi | +10.0 | — |
| Ăn cỏ | — | +1.0 |
| Uống nước | +4 *(đang chỉnh)* | +4 *(đang chỉnh)* |
| Lại gần mục tiêu | +0.03·closed (chỉ khi closed>0) | +0.03·closed |
| Tăng cách với sói | — | +0.06·Δ |
| Chết (terminal) | −10.0 | −10.0 |

**Nguyên tắc thang bậc:** sự kiện mục tiêu (±10) ≫ shaping (~0.03–0.06/bước) → tín hiệu nhỏ
không bao giờ lấn át mục tiêu lớn.

---

## 5. CÔNG THỨC CẬP NHẬT — điểm KHÁC BIỆT DUY NHẤT giữa QL và MC

Cả hai đều có dạng `Q(s,a) += α·(target − Q(s,a))`. Chỉ khác **target**.

### Q-learning (TD, bootstrap) — `QTable.update`, gọi MỖI BƯỚC

```
target = r + γ · max_a' Q(s', a')        // nextState != null
target = r                               // nextState == null (terminal)
Q(s,a) += α · (target − Q(s,a))
```
- **Bootstrap**: mượn chính ước lượng Q của trạng thái kế làm "phần thưởng tương lai".
- **Off-policy** (dùng `max`), **online** (học ngay), variance thấp / có bias.
- Terminal (chết): `learnTerminal()` gọi `q.update(prev, prevAction, -10.0, null, ...)`.

### Monte Carlo (return thật) — `MonteCarloAgent.finishEpisode`, gọi CUỐI EPISODE

```
G = 0
for i từ cuối trajectory về đầu:
    G = reward_i + γ · G
    Q(s_i, a_i) = (1-α)·Q(s_i,a_i) + α·G        // ≡ Q += α·(G − Q)
```
- **Return thật** `G` (không bootstrap), **on-policy**, **offline** (đợi episode kết thúc).
- Cần buffer trajectory → có lớp `MonteCarloAgent` (`record` / `setLastReward` / `finishEpisode`).
- Unbiased nhưng variance cao.

### Kết thúc episode (chỉ MC cần phân biệt)

- `learnTerminal()` — entity chết: reward cuối `-10`, rồi `finishEpisode`.
- `learnEpisodeEnd()` — hết maxSteps mà còn sống: reward cuối `0`, rồi `finishEpisode`.

> Tương quan file: lõi cập nhật QL nằm ở `QTable.update()`; QL **không có** lớp "agent" vì học
> online. MC có `MonteCarloAgent` để gom trajectory.

---

## 6. Tham số học

| Tham số | Train | Play (eval) | Ghi chú |
|---------|-------|-------------|---------|
| alpha (α) | 0.1 | — (không cập nhật) | learning rate |
| gamma (γ) | 0.97 | 0.97 | discount; horizon hiệu dụng ≈ 1/(1−γ) ≈ 33 bước |
| epsilon (ε) | lịch giảm (xem dưới) | **0.0** | eval tất định để công bằng với RBS |

**Lịch epsilon khi train** (MonteCarloTrainer / QLearningTrainer):
```
epsilonStart = (bảng đã có state) ? 0.3 : 1.0
epsilon(ep)  = max(0.05, epsilonStart · (1 − ep/(episodes·0.8)))
```
→ giảm tuyến tính tới 0.05, đạt sàn ở ~80% số episode.

---

## 7. PIPELINE TRAIN (headless, xen kẽ — KHÔNG co-evolution)

Cả hai bên cùng học đồng thời → môi trường non-stationary, không hội tụ. Giải pháp: mỗi pha
một bên học, bên kia ĐÓNG BĂNG.

**Q-learning** — `./train.sh` (TRAINER = `QLearningTrainer`):
```
pha 1: sói học  vs thỏ RBS              -> qtables/wolf.qtable    (800 ep, 600 step)
pha 2: thỏ học né vs sói đóng băng       -> qtables/rabbit.qtable  (800 ep, 600 step)
pha 3: sói tinh chỉnh vs thỏ RL đóng     -> qtables/wolf.qtable    (400 ep, 600 step)
```

**Monte Carlo** — `./train_mc.sh` (TRAINER = `MonteCarloTrainer`):
```
pha 1: sói MC học  vs thỏ RBS           -> qtables/mc_wolf.qtable   (800 ep, 400 step)
pha 2: thỏ MC học né vs sói đóng băng    -> qtables/mc_rabbit.qtable (800 ep, 400 step)
pha 3: sói MC tinh chỉnh vs thỏ đóng     -> qtables/mc_wolf.qtable   (400 ep, 400 step)
```
> MC dùng `maxSteps=400` (ngắn hơn QL 600) vì phải giữ toàn bộ trajectory trong RAM.

**Các mode** (`MonteCarloTrainer`/`QLearningTrainer` đối số thứ 3): `wolf | rabbit | wolfql | rbs`.

**Khởi tạo mỗi episode** (trainer): map 576×576 từ `all.tmx`; 50 cỏ, 10 thỏ, 3 sói. Sói thả
**đói sẵn `hunger=55`** (ngưỡng săn 50) để khởi động cùng điều kiện. Pha thỏ học: bù sói về
sàn để áp lực săn không tắt. `DT=0.1`. RNG seed cố định 42.

**Cập nhật cuối episode:** trainer gọi `learnTerminal()` khi entity chết (qua `reapDead`), và
`learnEpisodeEnd()` cho entity còn sống khi hết maxSteps.

---

## 8. Gắn não RL vào entity (runtime / app)

`LivingEntity.fixedStrategy`:
- `fixedStrategy != null` → RL mode: `moveStrategy = fixedStrategy`, **bỏ qua toàn bộ RBS scoring**.
- `fixedStrategy == null` → RBS mode (xem `RBSworkflow.md`).

Não chỉ gắn cho con **thả ban đầu** (`MainApp.initQLearning()`); con đẻ ra sau dùng RBS thuần
(thiết kế có chủ ý, tránh copy state Q-table).

Kích hoạt app: `-Dql=true` (Q-learning), `-Ddqn=true` (DQN). Eval headless: `BrainCompare`.

---

## 9. Tóm tắt tương quan file

| Vai trò | File |
|---------|------|
| Q-table (lưu + công thức update) | `core/qlearning/QTable.java` |
| MC trajectory buffer + finishEpisode | `core/montecarlo/MonteCarloAgent.java` |
| Strategy QL (encode/stepReward/options) | `core/strategies/QLearningStrategy.java` |
| Strategy MC (encode/stepReward/options) | `core/strategies/MonteCarloStrategy.java` |
| Trainer QL | `core/qlearning/QLearningTrainer.java` (`./train.sh`) |
| Trainer MC | `core/montecarlo/MonteCarloTrainer.java` (`./train_mc.sh`) |
| Các option strategy | `core/strategies/{Flee,SeekWater,Mate,Wander,Hunter}Strategy.java` |
