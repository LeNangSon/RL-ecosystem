package org.openjfx.app.core.deeprl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Mạng nơ-ron truyền thẳng (MLP) tự cài thuần Java — KHÔNG phụ thuộc thư viện deep learning
 * (để build offline được). Dùng làm bộ xấp xỉ hàm Q cho DQN: ánh xạ một vector trạng thái
 * liên tục -> giá trị Q cho từng hành động.
 *
 * <p>Kiến trúc: các lớp fully-connected, kích hoạt ReLU ở lớp ẩn, tuyến tính ở lớp ra
 * (giá trị Q không bị chặn). Khởi tạo trọng số kiểu He (hợp với ReLU). Tối ưu bằng Adam
 * kèm cắt gradient (giữ ổn định cho DQN). Hỗ trợ lưu/đọc trọng số ra file text.</p>
 */
public class NeuralNetwork {

    private final int[] sizes;          // ví dụ {12, 64, 64, 8}: vào=12, 2 lớp ẩn 64, ra=8
    private final int layers;           // số "khe" trọng số = sizes.length - 1

    private final double[][][] w;       // w[l][j][i]: trọng số từ nơ-ron i (lớp l) -> j (lớp l+1)
    private final double[][] b;         // b[l][j]: bias của nơ-ron j ở lớp l+1

    // Mômen Adam (cùng hình dạng với w, b).
    private final double[][][] mw, vw;
    private final double[][] mb, vb;

    private double lr;
    private final double beta1 = 0.9, beta2 = 0.999, eps = 1e-8;
    private double gradClip = 1.0;      // cắt gradient theo trị tuyệt đối
    private long t = 0;                 // bước Adam (cho hiệu chỉnh bias mômen)

    // Bộ nhớ đệm của lần forward gần nhất (phục vụ backprop).
    private double[][] a;               // a[l]: kích hoạt của lớp l (a[0] = đầu vào)
    private double[][] z;               // z[l] (l>=1): tổng tuyến tính trước kích hoạt

    public NeuralNetwork(int[] sizes, double lr, long seed) {
        this.sizes = sizes.clone();
        this.layers = sizes.length - 1;
        this.lr = lr;
        this.w = new double[layers][][];
        this.b = new double[layers][];
        this.mw = new double[layers][][];
        this.vw = new double[layers][][];
        this.mb = new double[layers][];
        this.vb = new double[layers][];

        Random rng = new Random(seed);
        for (int l = 0; l < layers; l++) {
            int in = sizes[l], out = sizes[l + 1];
            w[l] = new double[out][in];
            b[l] = new double[out];
            mw[l] = new double[out][in];
            vw[l] = new double[out][in];
            mb[l] = new double[out];
            vb[l] = new double[out];
            double std = Math.sqrt(2.0 / in);          // He init
            for (int j = 0; j < out; j++) {
                for (int i = 0; i < in; i++) {
                    w[l][j][i] = rng.nextGaussian() * std;
                }
            }
        }
    }

    public void setLearningRate(double lr) { this.lr = lr; }
    public int inputSize() { return sizes[0]; }
    public int outputSize() { return sizes[sizes.length - 1]; }

    /** Truyền thẳng; lưu lại kích hoạt cho backprop. Trả về vector Q của lớp ra. */
    public double[] forward(double[] input) {
        a = new double[layers + 1][];
        z = new double[layers + 1][];
        a[0] = input;
        for (int l = 0; l < layers; l++) {
            int out = sizes[l + 1];
            double[] zl = new double[out];
            double[] al = new double[out];
            boolean output = (l == layers - 1);
            for (int j = 0; j < out; j++) {
                double s = b[l][j];
                double[] wlj = w[l][j];
                double[] prev = a[l];
                for (int i = 0; i < prev.length; i++) s += wlj[i] * prev[i];
                zl[j] = s;
                al[j] = output ? s : Math.max(0.0, s);   // linear ở lớp ra, ReLU ở lớp ẩn
            }
            z[l + 1] = zl;
            a[l + 1] = al;
        }
        return a[layers];
    }

    public double[] predict(double[] input) {
        double[] cur = input;
        for (int l = 0; l < layers; l++) {
            int out = sizes[l + 1];
            double[] next = new double[out];
            boolean output = (l == layers - 1);
            for (int j = 0; j < out; j++) {
                double s = b[l][j];
                double[] wlj = w[l][j];
                for (int i = 0; i < cur.length; i++) s += wlj[i] * cur[i];
                next[j] = output ? s : Math.max(0.0, s);
            }
            cur = next;
        }
        return cur;
    }

    /**
     * Lan truyền ngược + cập nhật Adam cho MỘT mẫu. {@code outputGrad} là dL/d(đầu ra) cho
     * từng nơ-ron lớp ra (với DQN: chỉ hành động đã chọn khác 0). Phải gọi sau {@link #forward}.
     */
    public void backprop(double[] outputGrad) {
        t++;
        double bc1 = 1.0 - Math.pow(beta1, t);
        double bc2 = 1.0 - Math.pow(beta2, t);

        double[] delta = outputGrad.clone();           // delta lớp hiện tại (bắt đầu từ lớp ra)
        for (int l = layers - 1; l >= 0; l--) {
            double[] prev = a[l];                       // kích hoạt lớp đầu vào của khe l
            int out = sizes[l + 1];
            for (int j = 0; j < out; j++) {
                double d = delta[j];
                if (d > gradClip) d = gradClip; else if (d < -gradClip) d = -gradClip;
                // bias (gradient = d)
                double mbv = beta1 * mb[l][j] + (1 - beta1) * d;
                double vbv = beta2 * vb[l][j] + (1 - beta2) * d * d;
                mb[l][j] = mbv; vb[l][j] = vbv;
                b[l][j] -= lr * (mbv / bc1) / (Math.sqrt(vbv / bc2) + eps);
                // trọng số (gradient = d * activation đầu vào)
                double[] wlj = w[l][j], mwlj = mw[l][j], vwlj = vw[l][j];
                for (int i = 0; i < prev.length; i++) {
                    double g = d * prev[i];
                    double m = beta1 * mwlj[i] + (1 - beta1) * g;
                    double v = beta2 * vwlj[i] + (1 - beta2) * g * g;
                    mwlj[i] = m; vwlj[i] = v;
                    wlj[i] -= lr * (m / bc1) / (Math.sqrt(v / bc2) + eps);
                }
            }
            // Lan delta về lớp trước (qua đạo hàm ReLU của lớp l).
            if (l > 0) {
                double[] prevDelta = new double[sizes[l]];
                double[] zl = z[l];                     // pre-activation của lớp l (lớp ẩn)
                for (int i = 0; i < sizes[l]; i++) {
                    if (zl[i] <= 0) { prevDelta[i] = 0; continue; } // ReLU'(z)=0 khi z<=0
                    double sum = 0;
                    for (int j = 0; j < out; j++) sum += w[l][j][i] * delta[j];
                    prevDelta[i] = sum;
                }
                delta = prevDelta;
            }
        }
    }

    /** Sao chép trọng số từ mạng khác (đồng bộ mạng target). Cùng kiến trúc. */
    public void copyWeightsFrom(NeuralNetwork src) {
        for (int l = 0; l < layers; l++) {
            for (int j = 0; j < w[l].length; j++) {
                System.arraycopy(src.w[l][j], 0, w[l][j], 0, w[l][j].length);
            }
            System.arraycopy(src.b[l], 0, b[l], 0, b[l].length);
        }
    }

    // ----------------------------------------------------------------- lưu / đọc
    public void save(Path path) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            try (BufferedWriter wr = Files.newBufferedWriter(path)) {
                StringBuilder head = new StringBuilder("# sizes");
                for (int s : sizes) head.append(' ').append(s);
                wr.write(head.toString()); wr.newLine();
                wr.write("# lr " + lr); wr.newLine();
                for (int l = 0; l < layers; l++) {
                    for (int j = 0; j < w[l].length; j++) {
                        StringBuilder sb = new StringBuilder("w\t").append(l).append('\t').append(j);
                        for (double val : w[l][j]) sb.append('\t').append(val);
                        wr.write(sb.toString()); wr.newLine();
                    }
                    StringBuilder sb = new StringBuilder("b\t").append(l);
                    for (double val : b[l]) sb.append('\t').append(val);
                    wr.write(sb.toString()); wr.newLine();
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static NeuralNetwork load(Path path) {
        try (BufferedReader r = Files.newBufferedReader(path)) {
            int[] sizes = null;
            double lr = 0.001;
            // Đọc trước phần header để biết kiến trúc.
            r.mark(1 << 20);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("# sizes")) {
                    String[] p = line.substring(7).trim().split("\\s+");
                    sizes = new int[p.length];
                    for (int i = 0; i < p.length; i++) sizes[i] = Integer.parseInt(p[i]);
                } else if (line.startsWith("# lr")) {
                    lr = Double.parseDouble(line.substring(4).trim());
                } else if (!line.startsWith("#")) {
                    break;
                }
            }
            if (sizes == null) throw new IOException("Thieu header '# sizes' trong " + path);
            NeuralNetwork net = new NeuralNetwork(sizes, lr, 0);
            // Đọc lại từ đầu, nạp trọng số.
            r.reset();
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t");
                if (p[0].equals("w")) {
                    int l = Integer.parseInt(p[1]);
                    int j = Integer.parseInt(p[2]);
                    for (int i = 0; i < net.w[l][j].length; i++) net.w[l][j][i] = Double.parseDouble(p[3 + i]);
                } else if (p[0].equals("b")) {
                    int l = Integer.parseInt(p[1]);
                    for (int k = 0; k < net.b[l].length; k++) net.b[l][k] = Double.parseDouble(p[2 + k]);
                }
            }
            return net;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
