#!/usr/bin/env bash
# ============================================================================
#  Train Monte Carlo Control cho SÓI + THỎ ở chế độ HEADLESS.
#
#  KHÔNG tham số: pipeline xen kẽ (giống train.sh nhưng dùng MC):
#      pha 1: sói MC học vs thỏ RBS           -> qtables/mc_wolf.qtable
#      pha 2: thỏ MC học né vs sói đóng băng  -> qtables/mc_rabbit.qtable
#      pha 3: sói MC tinh chỉnh vs thỏ đóng   -> qtables/mc_wolf.qtable
#
#  Có tham số: chạy một pha thủ công:
#      ./train_mc.sh <episodes> <maxSteps> [wolf|rabbit|wolfql|rbs]
#  Ví dụ:
#      ./train_mc.sh 500 400 wolf     # chỉ train sói MC
#      ./train_mc.sh 200 400 rbs      # đo chuẩn không học
#
#  Lưu ý: MC dùng episode ngắn hơn QL (maxSteps=400 thay vì 600) vì phải giữ
#  toàn bộ trajectory trong RAM; episode dài làm buffer lớn và update chậm.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

echo ">> Biên dịch project..."
./mvnw -o compile -q

CP="target/classes"
for jar in $(find "$HOME/.m2/repository/org/openjfx" -name "*.jar" 2>/dev/null | grep -vE "sources|javadoc"); do
  CP="$CP:$jar"
done

TRAINER=org.openjfx.app.core.montecarlo.MonteCarloTrainer

if [ $# -eq 0 ]; then
  echo ">> PIPELINE MC XEN KẼ: sói(800) -> thỏ(800) -> sói tinh chỉnh(400)"
  java -cp "$CP" $TRAINER 800 400 wolf
  java -cp "$CP" $TRAINER 800 400 rabbit
  java -cp "$CP" $TRAINER 400 400 wolfql
  echo ">> Pipeline xong: qtables/mc_wolf.qtable + qtables/mc_rabbit.qtable"
else
  exec java -cp "$CP" $TRAINER "${1:-2000}" "${2:-400}" "${3:-wolf}"
fi
