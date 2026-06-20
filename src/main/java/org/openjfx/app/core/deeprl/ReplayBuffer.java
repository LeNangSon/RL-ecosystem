package org.openjfx.app.core.deeprl;

import java.util.Random;

/**
 * Bộ nhớ phát lại kinh nghiệm (experience replay) cho DQN: lưu các chuyển tiếp
 * (state, action, reward, nextState, done) trong vòng đệm tròn và lấy mẫu ngẫu nhiên một
 * minibatch để học. Mục đích: phá tương quan thời gian giữa các bước liên tiếp -> ổn định
 * và tận dụng lại dữ liệu nhiều lần.
 */
public class ReplayBuffer {

    /** Một chuyển tiếp. {@code nextState == null} khi là trạng thái kết thúc (done). */
    public static final class Transition {
        public final double[] state;
        public final int action;
        public final double reward;
        public final double[] nextState;
        public final boolean done;

        public Transition(double[] state, int action, double reward, double[] nextState, boolean done) {
            this.state = state;
            this.action = action;
            this.reward = reward;
            this.nextState = nextState;
            this.done = done;
        }
    }

    private final Transition[] buf;
    private int size = 0;
    private int next = 0;

    public ReplayBuffer(int capacity) {
        this.buf = new Transition[capacity];
    }

    public int size() { return size; }

    public void push(double[] state, int action, double reward, double[] nextState, boolean done) {
        buf[next] = new Transition(state, action, reward, nextState, done);
        next = (next + 1) % buf.length;
        if (size < buf.length) size++;
    }

    /** Lấy ngẫu nhiên {@code n} chuyển tiếp (có thể trùng lặp — sampling with replacement). */
    public Transition[] sample(int n, Random rng) {
        Transition[] out = new Transition[n];
        for (int i = 0; i < n; i++) out[i] = buf[rng.nextInt(size)];
        return out;
    }
}
