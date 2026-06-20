# ARCHITECTURE — OOP-PRJ Ecosystem Simulation

> Tài liệu tham chiếu kiến trúc cho AI session mới. Đọc file này trước khi đọc code.

---

## Công nghệ & Build

| Thứ | Giá trị |
|-----|---------|
| Java | 25 |
| JavaFX | 21.0.6 (`javafx-controls`, `fxml`, `media`) |
| Build | Maven + `javafx-maven-plugin 0.0.8` |
| Test | JUnit 5 (Jupiter) 5.12.1 |
| Entry point | `org.openjfx.app.Launcher` |

**Chạy app:**
```bash
# KHÔNG dùng mvn clean (xóa dependencies offline)
rm -rf target/classes && mvn javafx:run          # RBS thuần
mvn javafx:run -Dql=true                          # Q-learning
mvn javafx:run -Ddqn=true                         # DQN (ưu tiên hơn ql)
```

**Headless balance check:**
```bash
java -cp <CP> org.openjfx.app.core.BalanceCheck 900
java -cp <CP> org.openjfx.app.core.BalanceCheck 900 ql
```

---

## Package Map

```
org.openjfx.app/
├── Launcher.java              ← main()
├── MainApp.java               ← JavaFX Application, AnimationTimer, khởi tạo RL
├── EntityStatusPanel.java     ← UI panel bên phải
│
├── core/
│   ├── WorldMap.java          ← danh sách entity, update loop, pending spawns
│   ├── RelationManager.java   ← quan hệ ăn / sợ giữa các loài
│   ├── BalanceCheck.java      ← headless simulation runner (balance testing)
│   ├── Vector2D.java          ← math helper
│   ├── GameObserver.java      ← event logging
│   ├── DeathCause.java        ← enum: HUNGER | THIRST | PREDATION
│   ├── EntityType.java        ← enum: WOLF | RABBIT | BEAR | ELEPHANT | FISH | GRASS | ALGAE | BUSH | ROCK
│   ├── TerminalLogger.java
│   ├── TmxObjectZones.java    ← đọc zones từ TMX map
│   │
│   ├── strategies/
│   │   ├── MoveStrategy.java           ← interface
│   │   ├── WanderStrategy.java         ← đi lang thang (Reynolds steering)
│   │   ├── HunterStrategy.java         ← A* pathfind + leading prey
│   │   ├── FleeStrategy.java           ← chạy trốn, pathfind vào terrain an toàn
│   │   ├── SeekWaterStrategy.java      ← tìm nước, hysteresis
│   │   ├── MateStrategy.java           ← tìm bạn đời gần
│   │   ├── QLearningStrategy.java      ← tabular Q + fallback RBS
│   │   ├── DeepQLearningStrategy.java  ← DQN + fallback RBS
│   │   └── StrategyCandidate.java      ← wrapper score + hysteresis selection
│   │
│   ├── qlearning/
│   │   ├── QTable.java                 ← state→double[8], save/load .qtable
│   │   └── QLearningTrainer.java       ← headless trainer (4 modes: wolf/rabbit/wolfql/rbs)
│   │
│   ├── deeprl/
│   │   ├── DQNAgent.java               ← online + target network, ReplayBuffer
│   │   ├── NeuralNetwork.java          ← fully-connected net, gradient clip
│   │   └── ReplayBuffer.java
│   │
│   └── terrain/
│       ├── TerrainType.java            ← GRASS | WATER | BUSH | DEEP_WATER | ...
│       └── TerrainProvider.java
│
├── entities/
│   ├── base/
│   │   ├── Entity.java                 ← id, position, size, shape, EntityType
│   │   ├── StaticEntity.java
│   │   ├── MovableEntity.java          ← velocity, acceleration, maxSpeed, mass, maxForce
│   │   ├── LivingEntity.java           ← hunger, thirst, health, age, reproduce*, moveStrategy, fixedStrategy
│   │   ├── Carnivore.java              ← strategy scoring, HUNT_HUNGER_START=50
│   │   └── Herbivore.java             ← strategy scoring, herbivore thresholds
│   │
│   ├── movable/
│   │   ├── Wolf.java                   ← hungerRate=0.65/s, visionRadius=70, maxSpeed=40
│   │   ├── Rabbit.java                 ← hungerRate=1.2/s, visionRadius=50, maxSpeed=32
│   │   ├── Bear.java
│   │   ├── Elephant.java
│   │   └── Fish.java                   ← aquatic herbivore
│   │
│   └── staticobjs/
│       ├── Grass.java
│       ├── Algae.java
│       ├── Bush.java
│       └── Rock.java
```

---

## Class Hierarchy

```
Entity
└── StaticEntity  ←  Grass, Algae, Bush, Rock
└── MovableEntity
    └── LivingEntity
        ├── Carnivore  ←  Wolf, Bear, Elephant
        └── Herbivore  ←  Rabbit, Fish
```

---

## Game Loop (per frame, 60 FPS)

```
AnimationTimer.handle()
  ├── dt = clamp(now - lastNow, 0, 0.05s)
  ├── worldMap.update(dt)
  │     ├── for each entity (backward iteration — safe removal)
  │     │     entity.update(dt, world)
  │     │       ├── age++, reproduceCooldown--
  │     │       ├── hunger += hungerRate*dt
  │     │       ├── thirst += thirstRate*dt
  │     │       ├── die if hunger≥100 || thirst≥100
  │     │       ├── health regen if fed & hydrated
  │     │       ├── strategy selection (RBS scoring | fixedStrategy)
  │     │       ├── strategy.updateVelocity()
  │     │       ├── nextPos = pos + velocity*dt
  │     │       ├── collision-and-slide
  │     │       └── boundary clamp (bounce off edges)
  │     └── process pendingSpawns (offspring queued during mating)
  └── worldMap.render(gc)
```

---

## Strategy Pattern

`MoveStrategy` interface: `updateVelocity(owner, neighbors, dt, world)`

### Strategy Selection (RBS — Carnivore)
`StrategyCandidate` list, mỗi cái có `scoreFn(owner, neighbors) → double`.
Dùng **hysteresis** (ε ≈ 0.02): giữ strategy hiện tại nếu score gần bằng best → tránh flicker.

| Strategy | Kích hoạt khi | Score |
|----------|--------------|-------|
| FleeStrategy | có mối đe dọa | 100.0 (luôn thắng) |
| SeekWaterStrategy | thirst > 70 | 50.0 (emergency > 75) |
| HunterStrategy | hunger ≥ 50 và có con mồi | ~0.5–2.0 |
| MateStrategy | canReproduce() và có bạn đời gần | trung bình |
| WanderStrategy | fallback | thấp |

### RL override
Nếu `fixedStrategy != null` (entity được gắn não RL), **bỏ qua toàn bộ RBS scoring**, dùng thẳng `fixedStrategy`.

---

## Q-Learning Integration

### Kích hoạt
- `-Dql=true` → load `qtables/wolf.qtable` + (tuỳ chọn) `qtables/rabbit.qtable`
- `-Ddqn=true` → load `qtables/wolf.dqn` (ưu tiên, bỏ qua `-Dql`)
- Não chỉ gắn cho con thả ban đầu; con đẻ ra sau dùng RBS

### State encoding (QTable key string)
```
"P|e3,2|t1,4,1|m5|h1|w0"
 ^  ^       ^   ^  ^  ^
 |  enemy   target move hunger thirst
 role(P=pred,R=prey)
```
- `e{dir},{distBin}`: enemy direction (0-7), distance bin (0=none/far, 1=near, 2=mid, 3=far)
- `t{type},{dir},{distBin}`: target (0=none, 1=food, 2=water)
- `m{sector}`: hướng di chuyển của prey (0-7, 8=đứng yên)
- `h{bin}`: hunger (0=<50, 1=≥50)
- `w{bin}`: đang seek water (0/1)

### Actions
8 hướng compass (0=N, 1=NE, ... 7=NW)

### Rewards
| Sự kiện | Sói | Thỏ |
|---------|-----|-----|
| Mỗi step | -0.02 | +0.05 |
| Bắt mồi | +10.0 | — |
| Ăn cỏ | — | +1.0 |
| Uống nước | +0.3 | +0.3 |
| Tiến gần target | +0.05×Δdist | — |
| Tăng cách sói | — | +0.06×Δdist |
| Mất mồi | -0.5 | — |
| Chết | -10.0 | -10.0 |

### Fallback trong QLearningStrategy (không thấy target)
1. SeekWaterStrategy (nếu khát + hysteresis)
2. MateStrategy (nếu canReproduce + mate gần)
3. WanderStrategy
4. FleeStrategy (thỏ nếu địch trong visionRadius/2 — phản xạ panic)

---

## RelationManager

```java
// Ai ăn ai
threat: RABBIT ← [WOLF, BEAR]
        FISH   ← [BEAR]
        GRASS  ← [RABBIT, ELEPHANT]
        ALGAE  ← [FISH]

// Ai sợ ai (flee)
moveAway: RABBIT    ← [WOLF, BEAR, ELEPHANT]
          FISH      ← [BEAR, WOLF, ELEPHANT]
          WOLF      ← [ELEPHANT, BEAR]
          BEAR      ← [ELEPHANT]
```

---

## Các hằng số quan trọng

### LivingEntity
| Hằng | Giá trị | Ý nghĩa |
|------|---------|---------|
| THIRST_SEEK_START | 70.0 | Bắt đầu tìm nước |
| THIRST_SATED | 25.0 | Ngừng tìm nước (hysteresis) |
| THIRST_CRITICAL | 75.0 | Emergency override |
| reproduceMinHealth | 50.0 | Cần đủ HP mới đẻ |

### Carnivore
| Hằng | Giá trị |
|------|---------|
| HUNT_HUNGER_START | 50.0 |
| POUNCE_CLOSENESS | 0.5 × visionRadius |
| POUNCE_WHEN_FULL_CLOSENESS | 0.85 × visionRadius |

### Wolf
| Field | Giá trị |
|-------|---------|
| hungerRate | 0.65/s |
| thirstRate | 1.5/s |
| maxSpeed | 40 |
| visionRadius | 70 |
| matureAge | 15s |
| reproduceCooldownMax | 50s |

### Rabbit
| Field | Giá trị |
|-------|---------|
| hungerRate | 1.2/s |
| thirstRate | 1.8/s |
| maxSpeed | 32 |
| visionRadius | 50 |
| matureAge | 6s |
| reproduceCooldownMax | 22s |

### QLearningStrategy
| Hằng | Giá trị | Lý do |
|------|---------|-------|
| THIRST_HIGH | 60.0 | Bắt đầu seek water trong RL |
| THIRST_SATED | 25.0 | Hysteresis |
| HUNGER_SEEK (thỏ) | 60.0 | **Phải khớp RBS Herbivore** |
| HUNT_HUNGER (sói) | 50.0 | **Phải khớp RBS Carnivore** |
| alpha | 0.1 | Learning rate |
| gamma | 0.97 | Discount |
| epsilon (train) | 0.1 | Exploration |
| epsilon (play) | 0.05 | Exploitation |

---

## Map & Rendering

- Canvas: **576×576 px**
- Tile map: TMX format (scale 0.462 từ source 1248.0)
- DT max: **0.05s** (clamp để tránh entity teleport khi lag)
- Panel refresh: ~6.7 Hz (mỗi 0.15s), không refresh mỗi frame

---

## Training Pipeline

```bash
./train.sh           # Pipeline xen kẽ: wolf 800ep → rabbit 800ep → wolfql 400ep
./train.sh wolf      # Chỉ train sói
./train.sh rabbit    # Chỉ train thỏ
./train_dqn.sh       # Train DQN sói
```

**BalanceCheck init (headless):** 8 thỏ, 2 sói, 2 gấu, 2 voi, 3 cá, 35 cỏ, 10 tảo, map 576².
