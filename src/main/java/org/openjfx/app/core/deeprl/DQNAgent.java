package org.openjfx.app.core.deeprl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.openjfx.app.core.deeprl.ReplayBuffer.Transition;

/**
 * Tác nhân Deep Q-Network (DQN). Gói gọn:
 * <ul>
 *   <li>mạng <b>online</b> ước lượng Q(s,·) và được học mỗi bước;</li>
 *   <li>mạng <b>target</b> (bản sao trễ của online) để tính mục tiêu TD cho ổn định;</li>
 *   <li>{@link ReplayBuffer} để học từ kinh nghiệm cũ theo minibatch.</li>
 * </ul>
 *
 * <p>Mục tiêu TD: {@code y = r + gamma * max_a' Q_target(s', a')} (bỏ phần tương lai nếu
 * done). Mất mát là sai số bình phương trên hành động đã chọn; gradient lớp ra = (Q(s,a) - y)
 * được cắt về [-1,1] (kiểu Huber) trước khi lan ngược. Cứ {@code targetSyncEvery} lần học thì
 * sao chép online -> target.</p>
 *
 * <p>Một tác nhân được CHIA SẺ giữa nhiều cá thể cùng loài (như QTable ở bản tabular): mỗi
 * con vật có một strategy riêng nhưng cùng trỏ về một DQNAgent.</p>
 */
public class DQNAgent {

    private final NeuralNetwork online;
    private final NeuralNetwork target;
    private final ReplayBuffer buffer;
    private final int numActions;

    private final double gamma;
    private final int batchSize;
    private final int learnStart;       // số mẫu tối thiểu trong buffer trước khi bắt đầu học
    private final int targetSyncEvery;  // số bước học giữa 2 lần đồng bộ target
    private int learnSteps = 0;

    public DQNAgent(int numFeatures, int numActions, int[] hidden, double lr, double gamma,
                    int bufferCap, int batchSize, int learnStart, int targetSyncEvery, long seed) {
        this.numActions = numActions;
        this.gamma = gamma;
        this.batchSize = batchSize;
        this.learnStart = learnStart;
        this.targetSyncEvery = targetSyncEvery;

        int[] sizes = new int[hidden.length + 2];
        sizes[0] = numFeatures;
        System.arraycopy(hidden, 0, sizes, 1, hidden.length);
        sizes[sizes.length - 1] = numActions;

        this.online = new NeuralNetwork(sizes, lr, seed);
        this.target = new NeuralNetwork(sizes, lr, seed + 1);
        this.target.copyWeightsFrom(online);
        this.buffer = new ReplayBuffer(bufferCap);
    }

    /** Bọc một mạng đã nạp sẵn (để chơi/khai thác). */
    private DQNAgent(NeuralNetwork net) {
        this.online = net;
        this.target = net;
        this.buffer = null;
        this.numActions = net.outputSize();
        this.gamma = 0.9;
        this.batchSize = 0;
        this.learnStart = 0;
        this.targetSyncEvery = 0;
    }

    public int numActions() { return numActions; }

    /** Chọn hành động epsilon-greedy theo mạng online (epsilon=0 -> tham lam). */
    public int selectAction(double[] state, double epsilon, Random rng) {
        if (epsilon > 0 && rng.nextDouble() < epsilon) return rng.nextInt(numActions);
        return argmax(online.predict(state));
    }

    public void remember(double[] s, int a, double r, double[] sNext, boolean done) {
        if (buffer != null) buffer.push(s, a, r, sNext, done);
    }

    /** Một bước học: lấy minibatch, cập nhật online, định kỳ đồng bộ target. */
    public void learn(Random rng) {
        if (buffer == null || buffer.size() < learnStart) return;
        Transition[] batch = buffer.sample(batchSize, rng);
        for (Transition tr : batch) {
            double y = tr.reward;
            if (!tr.done && tr.nextState != null) {
                y += gamma * max(target.predict(tr.nextState));
            }
            double[] q = online.forward(tr.state);          // lưu cache cho backprop
            double[] grad = new double[numActions];
            grad[tr.action] = q[tr.action] - y;             // dL/dQ_a (sẽ được cắt trong backprop)
            online.backprop(grad);
        }
        if (++learnSteps % targetSyncEvery == 0) target.copyWeightsFrom(online);
    }

    public void save(Path path) {
        online.save(path);
    }

    /** Nạp trọng số đã lưu vào cả online lẫn target (để TRAIN TIẾP từ mạng cũ). */
    public void loadWeights(Path path) {
        NeuralNetwork loaded = NeuralNetwork.load(path);
        online.copyWeightsFrom(loaded);
        target.copyWeightsFrom(loaded);
    }

    /** Nạp tác nhân CHỈ ĐỂ CHƠI (khai thác mạng đã học, không học tiếp). */
    public static DQNAgent loadForPlay(Path path) {
        return new DQNAgent(NeuralNetwork.load(path));
    }

    public static boolean exists(Path path) {
        return Files.exists(path);
    }

    private int argmax(double[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }

    private double max(double[] v) {
        double m = v[0];
        for (int i = 1; i < v.length; i++) if (v[i] > m) m = v[i];
        return m;
    }
}
