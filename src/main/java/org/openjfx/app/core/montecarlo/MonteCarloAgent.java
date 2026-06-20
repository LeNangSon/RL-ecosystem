package org.openjfx.app.core.montecarlo;

import java.util.ArrayList;
import java.util.List;

import org.openjfx.app.core.qlearning.QTable;

// Lưu episode 
public class MonteCarloAgent {
    // r, s, a
    private static final class Step {
        final String state;
        final int    action;
        double       reward;   
    // s, a
        Step(String state, int action) {
            this.state  = state;
            this.action = action;
        }
    }

    // Experience
    private final List<Step> trajectory = new ArrayList<>();

    public void record(String state, int action) {
        trajectory.add(new Step(state, action));
    }

    public void setLastReward(double reward) {
        if (!trajectory.isEmpty()) {
            trajectory.getLast().reward = reward;
        }
    }
    // Số step trong episode hiện tại
    public int size() { return trajectory.size(); }

    public void finishEpisode(QTable q, double gamma, double alpha) {
        double G = 0;
        int step = trajectory.size();
        for(int i = step-1;i >= 0; i--){
            Step s = trajectory.get(i);
            G = (s.reward + gamma * G);
            q.q(s.state)[s.action] = (1-alpha) * q.q(s.state)[s.action] + alpha*G;
        }
    }
    public void clear() {
        trajectory.clear();
    }
}
