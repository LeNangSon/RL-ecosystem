package org.openjfx.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

import org.openjfx.app.core.TerminalLogger;
import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.core.deeprl.DQNAgent;
import org.openjfx.app.core.qlearning.QTable;
import org.openjfx.app.core.strategies.DeepQLearningStrategy;
import org.openjfx.app.core.strategies.MonteCarloStrategy;
import org.openjfx.app.core.strategies.QLearningStrategy;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.movable.Bear;
import org.openjfx.app.entities.movable.Elephant;
import org.openjfx.app.entities.movable.Fish;
import org.openjfx.app.entities.movable.Rabbit;
import org.openjfx.app.entities.movable.Wolf;
import org.openjfx.app.entities.staticobjs.Algae;
import org.openjfx.app.entities.staticobjs.Bush;
import org.openjfx.app.entities.staticobjs.Grass;
import org.openjfx.app.entities.staticobjs.Rock;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

public class MainApp extends Application {
    private static final double SOURCE_MAP_SIZE = 1248.0;
    private static final double WIDTH = 576.0;
    private static final double HEIGHT = 576.0;
    private static final double TERMINAL_WIDTH = 400.0;
    private static final double TOP_BAR_HEIGHT = 48.0;
    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 3.0;
    private static final double SEASON_AUTO_SWITCH_SECONDS = 60.0;
    private static final double SEASON_TRANSITION_SECONDS  = 6.0;

    private static final String SHARED_TMX_RESOURCE = "/org/openjfx/app/all.tmx";
    private static final int SHARED_TILE_SIZE = 32;
    private static final String IMG_SPRING = "/org/openjfx/app/spring.jpg";
    private static final String IMG_SUMMER = "/org/openjfx/app/summer.png";
    private static final String IMG_AUTUMN = "/org/openjfx/app/autumn.png";
    private static final String IMG_WINTER = "/org/openjfx/app/winter.png";

    private enum SpawnKind {
        RABBIT, WOLF, BEAR, ELEPHANT, FISH, GRASS, ALGAE, BUSH, ROCK
    }

    private enum Season {
        SPRING, SUMMER, AUTUMN, WINTER
    }

    private static final double PANEL_REFRESH_INTERVAL = 0.15;
    private double panelRefreshAccum = 0;

    private WorldMap worldMap;
    private Canvas canvas;
    // Q-learning: bật bằng cờ JVM -Dql=true. Khi bật, CHỈ sói dùng não RL đã train;
    // thỏ dùng lại bộ chiến lược luật gốc (RBS) cho "ngu" và dễ đoán để sói săn.
    private boolean useQLearning;
    private QTable wolfQ;
    private QTable rabbitQ;
    // Deep Q-learning: bật bằng cờ JVM -Ddqn=true. Khi bật, sói dùng não DQN (mạng nơ-ron);
    // thỏ vẫn dùng RBS. Ưu tiên hơn -Dql nếu cả hai cùng bật.
    private boolean useDqn;
    private DQNAgent wolfDqn;
    // Monte Carlo: bật bằng cờ JVM -Dmc=true. Nạp bảng từ mc_wolf.qtable (+ tuỳ chọn
    // mc_rabbit.qtable). Bảng MC độc lập với QL; chạy ./train_mc.sh để tạo.
    private boolean useMonteCarlo;
    private QTable mcWolfQ;
    private QTable mcRabbitQ;
    private SpawnKind pendingSpawnKind = SpawnKind.RABBIT;
    private Season currentSeason = Season.SPRING;
    private ToggleGroup spawnGroup;
    private Slider zoomSlider;
    private boolean updatingZoomSlider;
    private final Random random = new Random();
    private Label survivalLabel;
    private MenuButton seasonMenu;
    private ToggleGroup seasonGroup;
    private double seasonElapsedSeconds;
    private boolean isTransitioning    = false;
    private double  transitionElapsed  = 0.0;
    private Season  targetSeason       = null;
    private double survivalTime;
    private boolean survivalEnded;
    // Sidebar phải: 2 tab Nhật ký / Thống kê (TAB cũng chuyển qua lại được).
    private ListView<String> logListView;
    private EntityStatusPanel statusPanel;
    private ToggleButton tabLogButton;
    private ToggleButton tabStatsButton;

    @Override
    public void start(Stage stage) {
        double mapScaleX = WIDTH / SOURCE_MAP_SIZE;
        double mapScaleY = HEIGHT / SOURCE_MAP_SIZE;

        worldMap = new WorldMap(WIDTH, HEIGHT);
        worldMap.setFixedBackgroundImageFromResource(IMG_SPRING);
        worldMap.setObjectZonesFromTmxResource(SHARED_TMX_RESOURCE, SHARED_TILE_SIZE, mapScaleX, mapScaleY);

        Path editableTmx = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "org", "openjfx", "app", "all.tmx");
        if (Files.exists(editableTmx)) {
            startTmxWatcher(editableTmx, mapScaleX, mapScaleY);
        }

        ObservableList<String> logData = FXCollections.observableArrayList();
        logListView = buildLogList(logData);
        worldMap.addObserver(new TerminalLogger(logData));

        initQLearning();
        seedInitialEntities();
        worldMap.notifyAction("Hệ thống", "đã khởi tạo", "map 4 mùa dùng chung all.tmx");

        statusPanel = new EntityStatusPanel(worldMap);
        statusPanel.setVisible(false);
        statusPanel.setManaged(false);

        canvas = new Canvas(WIDTH, HEIGHT);
        canvas.setFocusTraversable(true);
        setupCanvasInput();

        GraphicsContext gc = canvas.getGraphicsContext2D();
        AnimationTimer timer = new AnimationTimer() {
            private long lastNow = 0;

            @Override
            public void handle(long now) {
                // dt theo thời gian thực giữa 2 frame -> chuyển động đều dù FPS dao động.
                double dt = (lastNow == 0) ? 1.0 / 60.0 : (now - lastNow) / 1_000_000_000.0;
                lastNow = now;
                // Kẹp dt để tránh nhảy lớn khi lag/alt-tab gây "teleport" xuyên vật cản.
                dt = Math.min(dt, 0.05);

                gc.clearRect(0, 0, WIDTH, HEIGHT);
                worldMap.update(dt);
                worldMap.render(gc);
                tickSeasonCycle(dt);
                tickSurvivalClock(dt);
                // Refresh panel ~6-7Hz thay vì mỗi frame (rebuild list + sort toàn bộ entity rất nặng).
                panelRefreshAccum += dt;
                if (statusPanel.isVisible() && panelRefreshAccum >= PANEL_REFRESH_INTERVAL) {
                    statusPanel.refreshData();
                    panelRefreshAccum = 0;
                }
            }
        };
        timer.start();

        // Mọi điều khiển dồn lên THANH NGANG phía trên, trải hết bề rộng cửa sổ —
        // map 576x576 bên dưới hoàn toàn trống, không bị bất cứ thứ gì che.
        HBox topBar = buildTopBar();

        StackPane mapPane = new StackPane(canvas);
        mapPane.setStyle("-fx-background-color: black;");

        HBox mainRow = new HBox(mapPane, buildSidebar());
        VBox root = new VBox(topBar, mainRow);
        root.getStyleClass().add("app-root");
        VBox.setVgrow(mainRow, Priority.ALWAYS);
        Scene scene = new Scene(root, WIDTH + TERMINAL_WIDTH, HEIGHT + TOP_BAR_HEIGHT, Color.web("#10161b"));
        scene.getStylesheets().add(getClass().getResource("/org/openjfx/app/ui.css").toExternalForm());
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.TAB) {
                showSidePane(!statusPanel.isVisible());
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                clearPlacementMode();
                event.consume();
            }
        });

        stage.setScene(scene);
        stage.setTitle("🌿 Ecosystem Simulation");
        stage.setResizable(false);
        stage.show();
        canvas.requestFocus();
    }

    /** Nhật ký sự kiện: tô màu theo loại (chết/sinh/cảnh báo) + tự cuộn xuống dòng mới. */
    private ListView<String> buildLogList(ObservableList<String> logData) {
        ListView<String> list = new ListView<>(logData);
        list.getStyleClass().add("log-list");
        list.setFocusTraversable(false);
        list.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String msg, boolean empty) {
                super.updateItem(msg, empty);
                if (empty || msg == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(msg);
                if (msg.contains("đã chết") || msg.contains("kết thúc")) {
                    setStyle("-fx-text-fill: #f87171;");
                } else if (msg.contains("sinh ra")) {
                    setStyle("-fx-text-fill: #34d399;");
                } else if (msg.contains("không thể")) {
                    setStyle("-fx-text-fill: #fbbf24;");
                } else if (msg.startsWith("Hệ thống")) {
                    setStyle("-fx-text-fill: #8294a5;");
                } else {
                    setStyle("");
                }
            }
        });
        logData.addListener((ListChangeListener<String>) c -> list.scrollTo(logData.size() - 1));
        return list;
    }

    /** Sidebar phải: tiêu đề + tab Nhật ký / Thống kê + nội dung tương ứng. */
    private VBox buildSidebar() {
        Label title = new Label("🌿 Hệ Sinh Thái");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("Mô phỏng chuỗi thức ăn · 4 mùa · " + (useDqn ? "DQN" : useQLearning ? "Q-learning" : "luật RBS"));
        subtitle.getStyleClass().add("app-subtitle");

        ToggleGroup tabs = new ToggleGroup();
        tabLogButton = new ToggleButton("📜 Nhật ký");
        tabStatsButton = new ToggleButton("📊 Thống kê");
        for (ToggleButton b : new ToggleButton[] { tabLogButton, tabStatsButton }) {
            b.getStyleClass().add("seg-button");
            b.setToggleGroup(tabs);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setFocusTraversable(false);
        }
        tabLogButton.setSelected(true);
        tabLogButton.setOnAction(e -> { showSidePane(false); canvas.requestFocus(); });
        tabStatsButton.setOnAction(e -> { showSidePane(true); canvas.requestFocus(); });
        // Bấm lại tab đang chọn không được phép "bỏ chọn cả hai".
        tabs.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null && oldT != null) tabs.selectToggle(oldT);
        });
        HBox segBar = new HBox(2, tabLogButton, tabStatsButton);
        segBar.getStyleClass().add("seg-bar");
        HBox.setHgrow(tabLogButton, Priority.ALWAYS);
        HBox.setHgrow(tabStatsButton, Priority.ALWAYS);

        VBox header = new VBox(8, new VBox(2, title, subtitle), segBar);
        header.getStyleClass().add("side-header");

        StackPane content = new StackPane(logListView, statusPanel);
        VBox.setVgrow(content, Priority.ALWAYS);

        Label hint = new Label("🖱 nhấp: thả · kéo: di chuyển · TAB: thống kê · ESC: bỏ chọn");
        hint.getStyleClass().add("side-footer");
        hint.setMaxWidth(Double.MAX_VALUE);

        VBox sidebar = new VBox(header, content, hint);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(TERMINAL_WIDTH);
        sidebar.setMinWidth(TERMINAL_WIDTH);
        sidebar.setMaxWidth(TERMINAL_WIDTH);
        return sidebar;
    }

    private void showSidePane(boolean stats) {
        statusPanel.setVisible(stats);
        statusPanel.setManaged(stats);
        logListView.setVisible(!stats);
        logListView.setManaged(!stats);
        if (stats) {
            statusPanel.refreshData();
            tabStatsButton.setSelected(true);
        } else {
            tabLogButton.setSelected(true);
        }
    }

    /** Thanh điều khiển ngang phía trên cửa sổ: palette thả con vật bên trái,
     *  đồng hồ sinh tồn + mùa + zoom bên phải. Không che map. */
    private HBox buildTopBar() {
        spawnGroup = new ToggleGroup();
        HBox spawnBox = new HBox(2,
                createSpawnToggle("🐰", "Thỏ", SpawnKind.RABBIT),
                createSpawnToggle("🐺", "Sói", SpawnKind.WOLF),
                createSpawnToggle("🐻", "Gấu", SpawnKind.BEAR),
                createSpawnToggle("🐘", "Voi", SpawnKind.ELEPHANT),
                createSpawnToggle("🐟", "Cá", SpawnKind.FISH),
                createSpawnToggle("🌿", "Cỏ", SpawnKind.GRASS),
                createSpawnToggle("🌊", "Tảo", SpawnKind.ALGAE),
                createSpawnToggle("🌳", "Bụi", SpawnKind.BUSH),
                createSpawnToggle("🪨", "Đá", SpawnKind.ROCK));
        spawnBox.setAlignment(Pos.CENTER_LEFT);

        survivalLabel = new Label("00:00");
        survivalLabel.getStyleClass().add("clock-chip");
        survivalLabel.setTooltip(new Tooltip("Thời gian hệ sinh thái sinh tồn"));

        seasonMenu = new MenuButton(seasonLabel(Season.SPRING));
        seasonMenu.setTooltip(new Tooltip("Mùa hiện tại — bấm để đổi"));
        seasonGroup = new ToggleGroup();
        addSeasonItem(seasonMenu, seasonGroup, seasonLabel(Season.SPRING), Season.SPRING, true);
        addSeasonItem(seasonMenu, seasonGroup, seasonLabel(Season.SUMMER), Season.SUMMER, false);
        addSeasonItem(seasonMenu, seasonGroup, seasonLabel(Season.AUTUMN), Season.AUTUMN, false);
        addSeasonItem(seasonMenu, seasonGroup, seasonLabel(Season.WINTER), Season.WINTER, false);

        zoomSlider = new Slider(MIN_ZOOM, MAX_ZOOM, MIN_ZOOM);
        zoomSlider.setPrefWidth(84);
        zoomSlider.setTooltip(new Tooltip("Phóng to / thu nhỏ"));
        zoomSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!updatingZoomSlider) {
                zoomAtPoint(newVal.doubleValue(), worldMap.getScale(), WIDTH / 2, HEIGHT / 2);
            }
        });

        Button btnMinus = createToolButton("−", "Thu nhỏ");
        Button btnPlus = createToolButton("+", "Phóng to");
        Button btnReset = createToolButton("⟲", "Đặt lại khung nhìn");
        btnMinus.setOnAction(e -> applyZoom(worldMap.getScale() - 0.1));
        btnPlus.setOnAction(e -> applyZoom(worldMap.getScale() + 0.1));
        btnReset.setOnAction(e -> {
            worldMap.setScale(MIN_ZOOM);
            worldMap.setOffset(0, 0);
            syncZoomSlider(MIN_ZOOM);
        });
        HBox zoomBox = new HBox(5, btnMinus, zoomSlider, btnPlus, btnReset);
        zoomBox.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, spawnBox, spacer, survivalLabel, seasonMenu, zoomBox);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar");
        bar.setMinHeight(TOP_BAR_HEIGHT);
        bar.setPrefHeight(TOP_BAR_HEIGHT);
        bar.setMaxHeight(TOP_BAR_HEIGHT);
        return bar;
    }

    private ToggleButton createSpawnToggle(String emoji, String label, SpawnKind kind) {
        ToggleButton button = new ToggleButton(emoji + " " + label);
        button.getStyleClass().add("spawn-toggle");
        button.setToggleGroup(spawnGroup);
        button.setSelected(kind == pendingSpawnKind);
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip("Nhấp lên bản đồ để thả " + label));
        button.setOnAction(e -> {
            // Bấm lại nút đang chọn không được "bỏ chọn cả nhóm" — luôn có 1 loài chờ thả.
            if (!button.isSelected()) button.setSelected(true);
            pendingSpawnKind = kind;
            canvas.requestFocus();
        });
        return button;
    }

    private Button createToolButton(String text, String tip) {
        Button button = new Button(text);
        button.getStyleClass().add("tool-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tip));
        return button;
    }

    private void addSeasonItem(MenuButton menu, ToggleGroup group, String label, Season season, boolean selected) {
        RadioMenuItem item = new RadioMenuItem(label);
        item.setToggleGroup(group);
        item.setUserData(season);
        item.setSelected(selected);
        item.setOnAction(e -> {
            switchToSeason(season);
        });
        menu.getItems().add(item);
    }

    private void switchToSeason(Season season) {
        if (currentSeason == season || isTransitioning) return;
        targetSeason        = season;
        isTransitioning     = true;
        transitionElapsed   = 0.0;
        seasonElapsedSeconds = 0.0;
        javafx.scene.image.Image toImage = loadSeasonImage(season);
        worldMap.beginBackgroundTransition(toImage);
        syncSeasonMenu();
    }

    private javafx.scene.image.Image loadSeasonImage(Season season) {
        String path = switch (season) {
            case SPRING -> IMG_SPRING;
            case SUMMER -> IMG_SUMMER;
            case AUTUMN -> IMG_AUTUMN;
            case WINTER -> IMG_WINTER;
        };
        try {
            var stream = getClass().getResourceAsStream(path);
            if (stream == null) return null;
            var img = new javafx.scene.image.Image(stream);
            return img.isError() ? null : img;
        } catch (Exception e) {
            return null;
        }
    }

    private void tickSeasonCycle(double dt) {
        if (isTransitioning) {
            transitionElapsed += dt;
            double alpha = transitionElapsed / SEASON_TRANSITION_SECONDS;
            worldMap.setTransitionAlpha(alpha);
            if (alpha >= 1.0) {
                worldMap.completeBackgroundTransition();
                currentSeason   = targetSeason;
                targetSeason    = null;
                isTransitioning = false;
                syncSeasonMenu();
            }
            return;
        }
        seasonElapsedSeconds += dt;
        if (seasonElapsedSeconds >= SEASON_AUTO_SWITCH_SECONDS) {
            switchToSeason(nextSeason(currentSeason));
        }
    }

    private Season nextSeason(Season season) {
        return switch (season) {
            case SPRING -> Season.SUMMER;
            case SUMMER -> Season.AUTUMN;
            case AUTUMN -> Season.WINTER;
            case WINTER -> Season.SPRING;
        };
    }

    private void syncSeasonMenu() {
        if (seasonMenu != null) {
            seasonMenu.setText(seasonLabel(currentSeason));
        }
        if (seasonGroup != null) {
            for (var toggle : seasonGroup.getToggles()) {
                if (toggle instanceof RadioMenuItem item && item.getUserData() == currentSeason) {
                    seasonGroup.selectToggle(item);
                    break;
                }
            }
        }
    }

    private String seasonLabel(Season season) {
        return switch (season) {
            case SPRING -> "🌸 Xuân";
            case SUMMER -> "☀️ Hạ";
            case AUTUMN -> "🍂 Thu";
            case WINTER -> "❄️ Đông";
        };
    }

    private void setupCanvasInput() {
        final double[] mouseAnchor = new double[2];
        final double[] lastOffset = new double[2];
        canvas.setOnMousePressed(e -> {
            mouseAnchor[0] = e.getX();
            mouseAnchor[1] = e.getY();
            lastOffset[0] = worldMap.getOffsetX();
            lastOffset[1] = worldMap.getOffsetY();
            canvas.requestFocus();
        });
        canvas.setOnMouseDragged(e -> {
            if (worldMap.getScale() > 1.0) {
                worldMap.setOffset(lastOffset[0] + e.getX() - mouseAnchor[0],
                        lastOffset[1] + e.getY() - mouseAnchor[1]);
            }
        });
        canvas.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                addSelectedAt(e.getX(), e.getY());
            }
        });
    }

    private void addSelectedAt(double screenX, double screenY) {
        Vector2D position = screenToWorld(screenX, screenY);
        switch (pendingSpawnKind) {
            case RABBIT, WOLF, BEAR, ELEPHANT, FISH -> spawnLiving(pendingSpawnKind, position);
            case GRASS -> spawnGrass(position);
            case ALGAE -> spawnAlgae(position);
            case BUSH -> spawnTerrainObject(position, new Bush(position));
            case ROCK -> spawnTerrainObject(position, new Rock(position));
        }
    }

    private void spawnLiving(SpawnKind kind, Vector2D position) {
        LivingEntity entity = createLivingByKind(kind, position);
        if (entity == null) return;
        if (!worldMap.canStandOn(entity, position)) {
            worldMap.notifyAction("Hệ thống", "không thể đặt", kind + " tại vị trí này");
            return;
        }
        addEntityAndLog(entity);
    }

    private void spawnGrass(Vector2D position) {
        if (worldMap.getTerrainAt(position) != TerrainType.LAND) {
            worldMap.notifyAction("Hệ thống", "không thể đặt", "Cỏ chỉ đặt trên đất");
            return;
        }
        addEntityAndLog(new Grass(position));
    }

    private void spawnAlgae(Vector2D position) {
        if (worldMap.getTerrainAt(position) != TerrainType.WATER) {
            worldMap.notifyAction("Hệ thống", "không thể đặt", "Tảo chỉ đặt trên nước");
            return;
        }
        addEntityAndLog(new Algae(position));
    }

    private void spawnTerrainObject(Vector2D position, Entity entity) {
        if (!worldMap.isInside(position)) {
            worldMap.notifyAction("Hệ thống", "không thể đặt", entity.getClass().getSimpleName() + " ngoài bản đồ");
            return;
        }
        TerrainType at = worldMap.getTerrainAt(position);
        if (at == TerrainType.WATER) {
            worldMap.notifyAction("Hệ thống", "không thể đặt", entity.getClass().getSimpleName() + " trên nước");
            return;
        }
        if (at == TerrainType.ROCK || at == TerrainType.BUSH) {
            worldMap.notifyAction("Hệ thống", "không thể đặt", entity.getClass().getSimpleName() + " chồng lên vật cản");
            return;
        }
        addEntityAndLog(entity);
    }

    private LivingEntity createLivingByKind(SpawnKind kind, Vector2D position) {
        LivingEntity entity = switch (kind) {
            case RABBIT -> new Rabbit(position);
            case WOLF -> new Wolf(position);
            case BEAR -> new Bear(position);
            case ELEPHANT -> new Elephant(position);
            case FISH -> new Fish(position);
            default -> null;
        };
        applyBrain(entity);   // gắn não RL nếu bật cờ (chỉ sói/thỏ)
        return entity;
    }

    private void addEntityAndLog(Entity entity) {
        worldMap.addEntity(entity);
        worldMap.notifyAction("Hệ thống", "đã thêm", entity.getClass().getSimpleName() + "#" + entity.getId());
    }

    private Vector2D screenToWorld(double screenX, double screenY) {
        return new Vector2D(
                (screenX - worldMap.getOffsetX()) / worldMap.getScale(),
                (screenY - worldMap.getOffsetY()) / worldMap.getScale());
    }

    private Vector2D mapPosition(double sourceX, double sourceY) {
        return new Vector2D(sourceX * WIDTH / SOURCE_MAP_SIZE, sourceY * HEIGHT / SOURCE_MAP_SIZE);
    }

    // Bật Q-learning nếu có cờ JVM -Dql=true VÀ đã có bảng Q của sói (chạy ./train.sh trước).
    private void initQLearning() {
        initDeepQLearning();
        initMonteCarlo();
        useQLearning = Boolean.getBoolean("ql");
        if (!useQLearning) return;
        Path wolfPath = Paths.get("qtables", "wolf.qtable");
        if (!Files.exists(wolfPath)) {
            useQLearning = false;
            System.err.println("[Q-learning] Chua co qtables/wolf.qtable -> chay ./train.sh truoc. Tam dung AI cu.");
            worldMap.notifyAction("Hệ thống", "Q-learning TẮT", "chưa có bảng Q (chạy ./train.sh)");
            return;
        }
        wolfQ = QTable.load(wolfPath, QLearningStrategy.NUM_ACTIONS);
        // Bảng thỏ là TUỲ CHỌN: có thì thỏ cũng dùng não RL (học né sói), không thì RBS.
        Path rabbitPath = Paths.get("qtables", "rabbit.qtable");
        if (Files.exists(rabbitPath)) {
            rabbitQ = QTable.load(rabbitPath, QLearningStrategy.NUM_ACTIONS);
        }
        String rabbitBrain = rabbitQ != null ? "tho RL (" + rabbitQ.size() + " trang thai)" : "tho RBS";
        System.out.println("[Q-learning] BAT: soi RL (" + wolfQ.size() + " trang thai); " + rabbitBrain + ".");
        worldMap.notifyAction("Hệ thống", "Q-learning BẬT",
                "sói RL, " + (rabbitQ != null ? "thỏ RL" : "thỏ RBS (chưa có rabbit.qtable)"));
    }

    // Bật Deep Q-learning nếu có cờ JVM -Ddqn=true VÀ đã có mạng sói qtables/wolf.dqn.
    private void initDeepQLearning() {
        useDqn = Boolean.getBoolean("dqn");
        if (!useDqn) return;
        Path netPath = Paths.get("qtables", "wolf.dqn");
        if (!DQNAgent.exists(netPath)) {
            useDqn = false;
            System.err.println("[DQN] Chua co qtables/wolf.dqn -> chay ./train_dqn.sh truoc. Tam dung AI cu.");
            worldMap.notifyAction("Hệ thống", "DQN TẮT", "chưa có mạng (chạy ./train_dqn.sh)");
            return;
        }
        wolfDqn = DQNAgent.loadForPlay(netPath);
        System.out.println("[DQN] BAT: soi dung nao Deep Q-Network; tho dung RBS.");
        worldMap.notifyAction("Hệ thống", "DQN BẬT", "sói dùng mạng nơ-ron, thỏ dùng RBS");
    }

    // Bật Monte Carlo nếu có cờ JVM -Dmc=true VÀ đã có bảng MC của sói (chạy ./train_mc.sh).
    private void initMonteCarlo() {
        useMonteCarlo = Boolean.getBoolean("mc");
        if (!useMonteCarlo) return;
        Path wolfPath = Paths.get("qtables", "mc_wolf.qtable");
        if (!Files.exists(wolfPath)) {
            useMonteCarlo = false;
            System.err.println("[Monte Carlo] Chua co qtables/mc_wolf.qtable -> chay ./train_mc.sh truoc. Tam dung AI cu.");
            worldMap.notifyAction("Hệ thống", "Monte Carlo TẮT", "chưa có bảng MC (chạy ./train_mc.sh)");
            return;
        }
        mcWolfQ = QTable.load(wolfPath, MonteCarloStrategy.NUM_ACTIONS);
        // Bảng thỏ là TUỲ CHỌN: có thì thỏ cũng dùng não MC (học né sói), không thì RBS.
        Path rabbitPath = Paths.get("qtables", "mc_rabbit.qtable");
        if (Files.exists(rabbitPath)) {
            mcRabbitQ = QTable.load(rabbitPath, MonteCarloStrategy.NUM_ACTIONS);
        }
        String rabbitBrain = mcRabbitQ != null ? "tho MC (" + mcRabbitQ.size() + " trang thai)" : "tho RBS";
        System.out.println("[Monte Carlo] BAT: soi MC (" + mcWolfQ.size() + " trang thai); " + rabbitBrain + ".");
        worldMap.notifyAction("Hệ thống", "Monte Carlo BẬT",
                "sói MC, " + (mcRabbitQ != null ? "thỏ MC" : "thỏ RBS (chưa có mc_rabbit.qtable)"));
    }

    // Gắn não RL cho SÓI khi bật cờ. Ưu tiên DQN > Monte Carlo > tabular Q. Thỏ & loài khác giữ RBS.
    private void applyBrain(LivingEntity entity) {
        if (entity == null) return;
        if (entity instanceof Wolf) {
            if (useDqn) {
                entity.setFixedStrategy(DeepQLearningStrategy.play(wolfDqn, DeepQLearningStrategy.Role.PREDATOR));
            } else if (useMonteCarlo) {
                entity.setFixedStrategy(MonteCarloStrategy.play(mcWolfQ, MonteCarloStrategy.Role.PREDATOR));
            } else if (useQLearning) {
                entity.setFixedStrategy(QLearningStrategy.play(wolfQ, QLearningStrategy.Role.PREDATOR));
            }
        } else if (entity instanceof Rabbit) {
            if (useMonteCarlo && mcRabbitQ != null) {
                entity.setFixedStrategy(MonteCarloStrategy.play(mcRabbitQ, MonteCarloStrategy.Role.PREY));
            } else if (useQLearning && rabbitQ != null) {
                entity.setFixedStrategy(QLearningStrategy.play(rabbitQ, QLearningStrategy.Role.PREY));
            }
        }
    }

    // Thêm một con vật vào map, gắn não RL trước nếu cần.
    private void seedLiving(LivingEntity entity) {
        applyBrain(entity);
        worldMap.addEntity(entity);
    }

    private void seedInitialEntities() {
        // 5 thỏ (đàn mồi phải đông hơn thú săn nhiều lần mới gánh nổi cả sói lẫn gấu),
        // thả theo cụm để chúng tìm được bạn tình ngay từ đầu.
        // Thú săn thả theo CẶP: 1 con đơn độc không bao giờ sinh sản được -> quần thể
        // thú săn không tăng theo đàn mồi -> thỏ bùng nổ rồi chết đói hàng loạt.
        seedLiving(new Wolf(mapPosition(500, 300)));

        // Cá cần ÍT NHẤT 2 con mới sinh sản được (mate-based); 1 con như cũ là chắc chắn
        // tuyệt chủng ngay khi bị gấu ăn



        for (int i = 0; i < 35; i++) {
            Vector2D p = randomLandPosition();
            if (p != null) worldMap.addEntity(new Grass(p));
        }
        for (int i = 0; i < 10; i++) {
            Vector2D p = randomWaterPosition();
            if (p != null) worldMap.addEntity(new Algae(p));
        }
    }

    private Vector2D randomLandPosition() {
        for (int i = 0; i < 80; i++) {
            Vector2D p = new Vector2D(random.nextDouble(WIDTH), random.nextDouble(HEIGHT));
            if (worldMap.getTerrainAt(p) == TerrainType.LAND) return p;
        }
        return null;
    }

    private Vector2D randomWaterPosition() {
        for (int i = 0; i < 120; i++) {
            Vector2D p = new Vector2D(random.nextDouble(WIDTH), random.nextDouble(HEIGHT));
            if (worldMap.getTerrainAt(p) == TerrainType.WATER) return p;
        }
        return null;
    }

    private void tickSurvivalClock(double dt) {
        if (survivalEnded) return;
        if (hasAliveMovable()) {
            survivalTime += dt;
            updateSurvivalLabel(false);
        } else {
            survivalEnded = true;
            updateSurvivalLabel(true);
            worldMap.notifyAction("Hệ thống", "kết thúc", "hệ sinh thái sinh tồn được " + formatTime(survivalTime));
        }
    }

    private boolean hasAliveMovable() {
        for (Entity e : worldMap.getEntities()) {
            if (e instanceof LivingEntity living && living.isAlive()) return true;
        }
        return false;
    }

    private void updateSurvivalLabel(boolean ended) {
        if (survivalLabel == null) return;
        survivalLabel.setText(ended ? "☠ " + formatTime(survivalTime) : formatTime(survivalTime));
    }

    private String formatTime(double seconds) {
        int total = (int) Math.floor(seconds);
        int hh = total / 3600;
        int mm = (total % 3600) / 60;
        int ss = total % 60;
        return hh > 0 ? String.format("%d:%02d:%02d", hh, mm, ss) : String.format("%02d:%02d", mm, ss);
    }

    private void clearPlacementMode() {
        pendingSpawnKind = SpawnKind.RABBIT;
        if (spawnGroup != null && !spawnGroup.getToggles().isEmpty()) {
            spawnGroup.selectToggle(spawnGroup.getToggles().get(0));
        }
    }

    private void applyZoom(double targetScale) {
        double clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, targetScale));
        zoomAtPoint(clamped, worldMap.getScale(), WIDTH / 2, HEIGHT / 2);
    }

    private void zoomAtPoint(double newScale, double oldScale, double cx, double cy) {
        if (oldScale == newScale) return;
        double ox = cx - (cx - worldMap.getOffsetX()) * (newScale / oldScale);
        double oy = cy - (cy - worldMap.getOffsetY()) * (newScale / oldScale);
        worldMap.setScale(newScale);
        worldMap.setOffset(ox, oy);
        syncZoomSlider(newScale);
    }

    private void syncZoomSlider(double scale) {
        if (zoomSlider == null) return;
        updatingZoomSlider = true;
        zoomSlider.setValue(scale);
        updatingZoomSlider = false;
    }

    private void startTmxWatcher(Path path, double scaleX, double scaleY) {
        Thread watcher = new Thread(() -> {
            try {
                long[] lastModified = { Files.getLastModifiedTime(path).toMillis() };
                while (true) {
                    Thread.sleep(1000);
                    long current = Files.getLastModifiedTime(path).toMillis();
                    if (current != lastModified[0]) {
                        lastModified[0] = current;
                        Platform.runLater(() -> {
                            try {
                                worldMap.setObjectZonesFromTmxFile(path.toString(), SHARED_TILE_SIZE, scaleX, scaleY);
                            } catch (Exception e) {
                                System.err.println("TMX reload failed: " + e.getMessage());
                            }
                        });
                    }
                }
            } catch (Exception e) {
                System.err.println("TMX watcher error: " + e.getMessage());
            }
        });
        watcher.setDaemon(true);
        watcher.start();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
