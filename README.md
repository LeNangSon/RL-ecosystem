# RL-ecosystem — Học tăng cường trong mô phỏng hệ sinh thái săn mồi–con mồi

Mô phỏng **hệ sinh thái sói (predator) – thỏ (prey) – cỏ** bằng JavaFX, nơi các loài
học hành vi sinh tồn bằng **Reinforcement Learning** thay vì luật cứng. Dự án so sánh ba
hướng RL với một baseline luật thủ công (rule-based) trên cùng một thế giới mô phỏng.

> Dự án phát triển từ một bài tập lớn OOP, ở đây được tách riêng và định hướng lại quanh
> phần **học tăng cường**.

## Các hướng tiếp cận

| Hướng | Loài | Thành phần chính | Mô hình lưu |
|------|------|------------------|-------------|
| **Tabular Q-learning** (TD) | sói + thỏ | `core/qlearning/` (`QTable`, `QLearningTrainer`), `core/strategies/QLearningStrategy` | `qtables/wolf.qtable`, `qtables/rabbit.qtable` |
| **Monte Carlo Control** | sói + thỏ | `core/montecarlo/` (`MonteCarloTrainer`, `MonteCarloAgent`), `core/strategies/MonteCarloStrategy` | `qtables/mc_wolf.qtable`, `qtables/mc_rabbit.qtable` |
| **Deep Q-Network (DQN)** | sói | `core/deeprl/` (mạng nơ-ron tự cài + experience replay + target network) | `qtables/wolf.dqn` |
| **Rule-Based (RBS)** — baseline | mọi loài | `core/strategies/` (`HunterStrategy`, `FleeStrategy`, `SeekWaterStrategy`, `MateStrategy`, `WanderStrategy`) | — |

## Ý tưởng thiết kế đáng chú ý

- **Huấn luyện headless:** trainer tái dùng `WorldMap` thật nhưng không vẽ giao diện, chạy
  hàng nghìn episode trong vài chục giây.
- **Best-response xen kẽ, không co-evolution:** train theo pha `sói → thỏ → sói tinh chỉnh`,
  mỗi pha một bên học còn bên kia **đóng băng**. Cho cả hai cùng học đồng thời thì policy
  không hội tụ (đã thử).
- **Hybrid RL + luật:** RL điều khiển phần "thấy địch/mồi từ xa"; các phản xạ tới hạn
  (né cận chiến, uống khi khát, sinh sản khi chạm) vẫn dùng luật để hệ sinh thái không sụp.
  Các guard này được rút ra từ đo đạc cân bằng quần thể — xem [`QLEARNING.md`](QLEARNING.md).
- **So sánh công bằng RL vs RBS:** cùng môi trường, cùng seed — xem
  [`CompareRL_vs_RBS.md`](CompareRL_vs_RBS.md).

## Bắt đầu nhanh

Yêu cầu: **JDK 17+** (JavaFX kéo qua Maven). Dùng `./mvnw` đi kèm, không cần cài Maven.

### Huấn luyện (headless)

```bash
./train.sh         # Q-learning: pipeline xen kẽ sói(800) → thỏ(800) → sói tinh chỉnh(400)
./train_mc.sh      # Monte Carlo: pipeline xen kẽ tương tự
./train_dqn.sh     # DQN cho sói

# Hoặc chạy một pha thủ công:
./train.sh 800 600 wolf      # chỉ train sói Q-learning
./train_mc.sh 200 400 rbs    # đo baseline RBS, không học
```

Chạy lại script sẽ **nạp bảng cũ và train tiếp** (huấn luyện tăng dần). Hai pipeline
Q-learning và Monte Carlo ghi ra file khác nhau nên **có thể train song song**.

### Chạy mô phỏng (UI JavaFX)

```bash
./mvnw -o javafx:run -Dql=true     # bật não Q-learning (nạp qtables/*.qtable)
./mvnw -o javafx:run -Ddqn=true    # bật não DQN cho sói
./mvnw -o javafx:run               # baseline luật (RBS)
```

## Cấu trúc

```
src/main/java/org/openjfx/app/
├── core/
│   ├── qlearning/      # Q-learning dạng bảng: QTable, QLearningTrainer
│   ├── montecarlo/     # Monte Carlo Control: trainer + agent
│   ├── deeprl/         # DQN: mạng nơ-ron, replay, target network
│   ├── strategies/     # MoveStrategy + RBS + cầu nối RL (QLearning/MonteCarlo/DeepQ Strategy)
│   └── ...             # WorldMap, terrain, BalanceCheck (đo cân bằng quần thể)
└── entities/           # Wolf, Rabbit, Grass, ...
```

## Tài liệu chi tiết

- [`QLEARNING.md`](QLEARNING.md) — thiết kế Q-learning: state/action/reward, các guard cân bằng.
- [`RLworkflow.md`](RLworkflow.md) — quy trình RL tổng thể.
- [`CompareRL_vs_RBS.md`](CompareRL_vs_RBS.md) — phương pháp & kết quả so sánh RL với baseline.
- [`ARCHITECTURE.md`](ARCHITECTURE.md), [`RULES.md`](RULES.md) — kiến trúc & luật mô phỏng.

## Công nghệ

Java 17 · JavaFX · Maven · Reinforcement Learning (tabular Q-learning, Monte Carlo Control,
Deep Q-Network) — mạng nơ-ron và toàn bộ vòng lặp RL **tự cài**, không dùng thư viện ML ngoài.
