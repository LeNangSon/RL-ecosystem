package org.openjfx.app.core;

import javafx.application.Platform;
import javafx.collections.ObservableList;

/**
 * Lớp này triển khai GameObserver để nhận các sự kiện từ WorldMap
 * và hiển thị chúng lên ListView trong JavaFX.
 */
public class TerminalLogger implements GameObserver {
    private ObservableList<String> logData;

    // --- PHẦN THÊM MỚI: Constructor không tham số để fix lỗi ở MainApp ---
    public TerminalLogger() {
        this.logData = null;
    }

    // --- GIỮ NGUYÊN: Constructor cũ dành cho giao diện ListView ---
    public TerminalLogger(ObservableList<String> logData) {
        this.logData = logData;
    }

    @Override
    public void onEntityDeath(String message) {
        addLog(message);
    }

    @Override
    public void onActionOccurred(String actor, String action, String target) {
        addLog(actor + " " + action + " " + target);
    }

    private void addLog(String msg) {
        // LUÔN LUÔN in ra Terminal bên dưới để theo dõi nhanh
        System.out.println(msg);

        // Giữ nguyên logic cập nhật UI cũ của Tuấn
        if (logData != null) {
            Platform.runLater(() -> {
                logData.add(msg);
                if (logData.size() > 20) {
                    logData.remove(0);
                }
            });
        }
    }
}