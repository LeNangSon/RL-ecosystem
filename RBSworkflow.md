# RBS WORKFLOW — Luồng hoạt động Rule-Based System

> RBS = "não luật" mặc định của các loài, KHÔNG học. Mỗi bước, entity chấm điểm tất cả
> chiến lược ứng viên (`StrategyCandidate`) rồi chọn cái điểm cao nhất (có giảm chấn chống
> flicker). Đây là baseline để so với RL (xem `CompareRL_vs_RBS.md`).
>
> So với RL (`RLworkflow.md`): RBS và RL dùng CHUNG bộ strategy + chung A*. Khác biệt duy nhất
> là **bộ chọn strategy**: RBS = công thức chấm điểm tay; RL = Q-table học được.

---

## 0. Ý tưởng tổng quát

```
            ┌──────────────────────────────────────────────┐
 entity ───►│ với mỗi StrategyCandidate:                   │
 + neighbors│   score = scorer(entity, neighbors)          │
            │   nếu là strategy đang chạy: +COMMIT_BONUS·. │   chọn max
            └──────────────────────────────────────────────┘ ─────────► moveStrategy
                                                                          .updateVelocity()
```

Mỗi `StrategyCandidate` = cặp `(factory tạo strategy, scorer chấm điểm)`. `selectBest` so điểm,
cộng bonus "cam kết" cho strategy hiện hành để chống nhảy qua lại, rồi trả strategy thắng.

---

## 1. Bộ chấm điểm — `StrategyCandidate` & `selectBest`

File: `core/strategies/StrategyCandidate.java`.

```
selectBest(candidates, current, dt, entity, neighbors):
    for c in candidates:
        score = c.scorer(entity, neighbors)
        if c.instance == current:                       // strategy đang chạy
            c.activeSeconds += dt
            score += COMMIT_BONUS · exp(−activeSeconds / COMMIT_DECAY_SECONDS)
        track winner = argmax(score)
    if winner != current: winner.activeSeconds = 0      // bắt đầu việc mới -> reset đồng hồ
    return winner.getStrategy()
```

- `COMMIT_BONUS = 0.25`, `COMMIT_DECAY_SECONDS = 2.0`.
- **Bonus cam kết**: cao ngay sau khi đổi việc (chống giật/flicker giữa 2 strategy điểm xấp xỉ),
  loãng dần theo thời gian → việc mới được giữ ổn định nhưng vẫn nhường được cho nhu cầu cấp
  thiết hơn nếu kéo dài. KHÔNG khóa cứng kiểu "đang làm gì phải làm cho xong".

---

## 2. Bảng chấm điểm — SÓI (Carnivore)

File: `entities/base/Carnivore.java` → `buildCandidates()`.

| Strategy | Điều kiện & điểm |
|----------|------------------|
| **Flee** | `hasThreat ? 100.0 : 0.0` — có mối đe dọa thì luôn thắng |
| **SeekWater** | `thirst ≥ THIRST_CRITICAL` → `THIRST_EMERGENCY_SCORE`; đang uống dở & `thirst > THIRST_SATED` → `DRINK_COMMIT_SCORE`; chưa uống & `thirst > THIRST_SEEK_START` → `thirst/100 + 0.2`; ngược lại 0 |
| **Hunter** | `closeness ≥ POUNCE_WHEN_FULL_CLOSENESS` → `POUNCE_HUNT_SCORE` (đớp miếng ăn miễn phí dù chưa đói); `hunger < HUNT_HUNGER_START (50)` → 0 (chưa đói thì kệ); `closeness ≤ 0` → 0 (không thấy mồi); `closeness ≥ POUNCE_CLOSENESS` → `POUNCE_HUNT_SCORE`; ngược lại → `max(0.75, hunger/100) + 0.5·closeness` |
| **Mate** | `canReproduce() && hasMateNearby() ? 0.45 : 0.0` |
| **Wander** | `0.3` (nền cố định, fallback) |

- `closeness` = `nearestPreyCloseness()`: `1 − dist/vision` của mồi gần nhất trong tầm; `0` nếu
  không có. Lọc giống `HunterStrategy.findClosestPrey` (bỏ xác chết, bỏ mồi núp bụi).
- Hunter của RBS dùng `HunterStrategy::new` = **lead 0.7 cố định** (chỉ 1 kiểu săn). Đây là điểm
  RL có cơ vượt: RL tách Hunter thành 4 kiểu lead (xem `RLworkflow.md` mục 1).

---

## 3. Bảng chấm điểm — THỎ (Herbivore)

File: `entities/base/Herbivore.java` → `buildCandidates()`.

| Strategy | Điều kiện & điểm |
|----------|------------------|
| **Flee** | `hasThreat ? 100.0 : 0.0` |
| **SeekWater** | giống Carnivore (emergency / commit / seek theo thirst) |
| **Hunter** (= đi tìm cỏ) | `hunger < 5.0` → 0 (no thì khỏi tìm); `hunger > 60.0` hoặc đang là HunterStrategy → `hunger/100`; ngược lại 0 |
| **Mate** | `canReproduce() && hasMateNearby() ? 0.45 : 0.0` |
| **Wander** | `0.3` |

> Thỏ "săn" cỏ bằng cùng `HunterStrategy` (mồi của thỏ là Grass theo `RelationManager`). `eat()`
> của Herbivore tiêu thụ Plant: `hunger -= plant.consume()`.

---

## 4. Vòng chạy mỗi frame — `LivingEntity.update(dt, world)` (Carnivore/Herbivore)

```
1. setDrinking(false)
2. neighbors = world.getNeighbors(this, visionRadius)
3. NẾU fixedStrategy != null:  moveStrategy = fixedStrategy        // RL mode — bỏ qua RBS
   NGƯỢC LẠI:                  moveStrategy = selectBest(candidates, moveStrategy, dt, this, neighbors)
4. NẾU moveStrategy != null & !isAvoidingBlockedPath():
        moveStrategy.updateVelocity(this, neighbors, dt, world)
5. super.update(dt, world)   // age, hunger/thirst tăng, chết nếu ≥100, health regen, di chuyển, va chạm, clamp biên
```

`candidates` khởi tạo lười (`buildCandidates()` lần đầu). `selectBest` giữ state `activeSeconds`
của từng candidate qua các frame nên hysteresis hoạt động xuyên thời gian.

---

## 5. Eat / Drink / Reproduce nằm Ở ĐÂU

**Nguyên tắc Strategy Pattern (RULES.md):** `MoveStrategy` chỉ cập nhật `velocity`, KHÔNG sửa
trực tiếp state entity. Việc ăn/uống/đẻ xảy ra:

- **Ăn mồi (sói):** trong `HunterStrategy.updateVelocity` — khi `range < interactionDistance`:
  gọi `owner.eat(prey, dt)`, và nếu mồi chuyển sống→chết thì `world.recordDeath(type, PREDATION)`.
  → đây là nguồn đếm "catch" chính xác (RL suy ra qua delta hunger, nhưng số đếm vẫn từ đây).
- **Ăn cỏ (thỏ):** `Herbivore.eat()` tiêu thụ Plant.
- **Uống:** trong `SeekWaterStrategy` (RBS) / qua delta thirst.
- **Sinh sản:** `MateStrategy` + cơ chế `pendingSpawns` của `WorldMap`.

---

## 6. Các hằng số sinh thái (ngưỡng RBS)

| Hằng (LivingEntity / Carnivore) | Ý nghĩa |
|---------------------------------|---------|
| `THIRST_SEEK_START = 70` | bắt đầu tìm nước |
| `THIRST_SATED = 25` | ngừng tìm nước (hysteresis) |
| `THIRST_CRITICAL = 75` | emergency override |
| `HUNT_HUNGER_START = 50` | sói bắt đầu chủ động săn |
| `POUNCE_CLOSENESS = 0.5·vision` | ngưỡng "vồ" khi đói |
| `POUNCE_WHEN_FULL_CLOSENESS = 0.85·vision` | vồ miếng ăn free dù chưa đói |
| `reproduceMinHealth = 50` | cần đủ HP mới đẻ |

> Các ngưỡng RL (THIRST_HIGH=60, HUNT_HUNGER=50, HUNGER_SEEK=60) được căn để **khớp RBS** —
> xem `RULES.md`. Sai khớp → RL lệch cửa sổ sinh sản / săn 24/7 → mất cân bằng.

---

## 7. RBS vs RL — tách biệt

| | RBS | RL |
|--|-----|----|
| Bộ chọn strategy | `StrategyCandidate.selectBest` (chấm điểm tay) | Q-table (`selectAction` ε-greedy) |
| Học? | Không | Có (QL/MC) |
| Kiểu săn | 1 (Hunter lead 0.7) | 4 (lead 0/0.7/1.3/2.0) |
| Kích hoạt | `fixedStrategy == null` | `fixedStrategy != null` |
| Tập strategy & A* | **dùng chung** | **dùng chung** |

Đây chính là cơ sở để so sánh công bằng: chỉ thay bộ chọn, mọi thứ khác giữ nguyên.
