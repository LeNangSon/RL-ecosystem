package org.openjfx.app.core.qlearning;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Bảng Q tabular: ánh xạ trạng thái (chuỗi đã rời rạc hoá) -> mảng giá trị Q cho từng
 * hành động. Hỗ trợ chọn hành động epsilon-greedy, cập nhật theo công thức Q-learning,
 * và lưu/đọc ra file text để tái sử dụng (train ở chế độ headless rồi nạp vào UI).
 */
public class QTable {

    private final Map<String, double[]> table = new HashMap<>();
    private final int numActions;

    public QTable(int numActions) {
        this.numActions = numActions;
    }

    public int numActions() { return numActions; }
    public int size() { return table.size(); }

    /** Lấy (hoặc tạo mới = 0) mảng Q cho một trạng thái. */
    public double[] q(String state) {
        return table.computeIfAbsent(state, k -> new double[numActions]);
    }

    /** Chọn hành động: epsilon-greedy (epsilon=0 -> luôn tham lam / khai thác). */
    public int selectAction(String state, double epsilon, Random rng) {
        if (epsilon > 0 && rng.nextDouble() < epsilon) {
            return rng.nextInt(numActions);
        }
        double[] qs = q(state);
        double max = Double.NEGATIVE_INFINITY;
        for (double v : qs) if (v > max) max = v;
        // Hoà điểm -> chọn ngẫu nhiên trong số tốt nhất (tránh thiên vị hành động 0).
        int count = 0;
        for (double v : qs) if (v == max) count++;
        int pick = rng.nextInt(count);
        for (int a = 0; a < numActions; a++) {
            if (qs[a] == max && pick-- == 0) return a;
        }
        return 0;
    }

    /**
     * Cập nhật Q-learning: Q(s,a) += alpha * (r + gamma * max_a' Q(s',a') - Q(s,a)).
     * nextState == null nghĩa là trạng thái kết thúc (chết) -> không cộng phần tương lai.
     */
    public void update(String state, int action, double reward, String nextState,
                       double alpha, double gamma) {
        double[] qs = q(state);
        double target = reward;
        if (nextState != null) {
            double[] qn = q(nextState);
            double maxNext = Double.NEGATIVE_INFINITY;
            for (double v : qn) if (v > maxNext) maxNext = v;
            target += gamma * maxNext;
        }
        qs[action] += alpha * (target - qs[action]);
    }

    public void save(Path path) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(path)) {
                w.write("# numActions=" + numActions);
                w.newLine();
                for (Map.Entry<String, double[]> e : table.entrySet()) {
                    StringBuilder sb = new StringBuilder(e.getKey());
                    for (double v : e.getValue()) sb.append('\t').append(v);
                    w.write(sb.toString());
                    w.newLine();
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static QTable load(Path path, int numActions) {
        QTable t = new QTable(numActions);
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length < numActions + 1) continue;
                double[] qs = new double[numActions];
                int offset = parts.length - numActions;
                for (int a = 0; a < numActions; a++) qs[a] = Double.parseDouble(parts[offset + a]);
                t.table.put(parts[0], qs);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return t;
    }

    public static QTable loadOrNew(Path path, int numActions) {
        return Files.exists(path) ? load(path, numActions) : new QTable(numActions);
    }
}
