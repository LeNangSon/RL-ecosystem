#!/usr/bin/env bash
# ============================================================================
#  Train Deep Q-Network (DQN) cho SÓI ở chế độ HEADLESS (không cần JavaFX UI).
#  Thỏ KHÔNG học: dùng bộ chiến lược luật gốc (RBS) làm đối thủ cố định.
#
#  Khác bản tabular (./train.sh): trạng thái là VECTOR LIÊN TỤC, hàm Q xấp xỉ bằng
#  mạng nơ-ron tự cài (NeuralNetwork) + experience replay + target network.
#
#  Cách dùng:
#      ./train_dqn.sh [episodes] [maxSteps]
#  Ví dụ:
#      ./train_dqn.sh                 # mặc định 1500 episode, 600 step/episode
#      ./train_dqn.sh 5000 800        # train lâu hơn
#
#  Kết quả: mạng sói lưu/đắp thêm vào  qtables/wolf.dqn
#  Chạy app với  -Ddqn=true  để sói dùng não DQN này.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

echo ">> Biên dịch project..."
./mvnw -o compile -q

CP="target/classes"
for jar in $(find "$HOME/.m2/repository/org/openjfx" -name "*.jar" 2>/dev/null | grep -vE "sources|javadoc"); do
  CP="$CP:$jar"
done

echo ">> Bắt đầu huấn luyện DQN (headless)..."
exec java -cp "$CP" org.openjfx.app.core.deeprl.DeepQLearningTrainer "${1:-1500}" "${2:-600}"
