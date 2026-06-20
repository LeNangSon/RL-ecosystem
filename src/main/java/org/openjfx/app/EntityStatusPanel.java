package org.openjfx.app;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.openjfx.app.core.DeathCause;
import org.openjfx.app.core.EntityType;
import org.openjfx.app.core.WorldMap;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class EntityStatusPanel extends VBox {

    // ── Species colors & emojis ───────────────────────────────────────────────
    private static String speciesColor(EntityType t) {
        if (t == null) return "#666666";
        return switch (t) {
            case RABBIT   -> "#5a9a5a";
            case WOLF     -> "#b04040";
            case BEAR     -> "#996633";
            case ELEPHANT -> "#4a7a9a";
            case FISH     -> "#3a6ab0";
            case GRASS    -> "#3a7a3a";
            case ALGAE    -> "#2a8080";
            default       -> "#5a5a5a";
        };
    }

    private static String speciesEmoji(EntityType t) {
        if (t == null) return "●";
        return switch (t) {
            case RABBIT   -> "🐰";
            case WOLF     -> "🐺";
            case BEAR     -> "🐻";
            case ELEPHANT -> "🐘";
            case FISH     -> "🐟";
            case GRASS    -> "🌿";
            case ALGAE    -> "🌊";
            default       -> "●";
        };
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    private final WorldMap worldMap;

    private final ListView<SummaryRow>  summaryList  = new ListView<>();
    private final ObservableList<SummaryRow> summaryRows = FXCollections.observableArrayList();

    private final TableView<DetailRow>  detailTable  = new TableView<>();
    private final ObservableList<DetailRow> detailRows  = FXCollections.observableArrayList();

    private final Button backButton    = new Button("← Quay lại");
    private final Label  titleLabel    = new Label("TỔNG QUAN HỆ SINH THÁI");
    private final Label  subtitleLabel = new Label();
    private final Label  footerLabel   = new Label();

    private EntityType selectedType;

    // ── Constructor ───────────────────────────────────────────────────────────
    public EntityStatusPanel(WorldMap worldMap) {
        this.worldMap = worldMap;
        setPrefWidth(400);
        setFillWidth(true);
        setStyle("-fx-background-color: #151d24;");

        buildSummaryList();
        buildDetailTable();

        getChildren().addAll(buildHeader(), summaryList, detailTable, buildFooter());
        VBox.setVgrow(summaryList,  Priority.ALWAYS);
        VBox.setVgrow(detailTable,  Priority.ALWAYS);

        showSummary();
    }

    // ── Header ────────────────────────────────────────────────────────────────
    private VBox buildHeader() {
        backButton.setStyle(
            "-fx-background-color: #1b242e; -fx-text-fill: #8294a5; -fx-font-size: 11px;"
            + " -fx-cursor: hand; -fx-padding: 4 10; -fx-background-radius: 8;");
        backButton.setOnAction(e -> showSummary());
        backButton.setVisible(false);
        backButton.setManaged(false);

        titleLabel.setStyle(
            "-fx-text-fill: #dbe5ee; -fx-font-size: 13px; -fx-font-weight: bold;"
            + " -fx-font-family: 'Menlo', 'Consolas', 'Monospaced';");

        subtitleLabel.setStyle(
            "-fx-text-fill: #8294a5; -fx-font-size: 10.5px;"
            + " -fx-font-family: 'Menlo', 'Consolas', 'Monospaced';");

        HBox titleRow = new HBox(8, backButton, titleLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(4, titleRow, subtitleLabel);
        header.setPadding(new Insets(10, 14, 10, 14));
        header.setStyle("-fx-background-color: #10161b;"
            + " -fx-border-color: #263240; -fx-border-width: 0 0 1 0;");
        return header;
    }

    // ── Footer ────────────────────────────────────────────────────────────────
    private HBox buildFooter() {
        footerLabel.setStyle(
            "-fx-text-fill: #8294a5; -fx-font-size: 10px;"
            + " -fx-font-family: 'Menlo', 'Consolas', 'Monospaced';");

        HBox footer = new HBox(footerLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(6, 14, 6, 14));
        footer.setStyle("-fx-background-color: #10161b;"
            + " -fx-border-color: #263240; -fx-border-width: 1 0 0 0;");
        return footer;
    }

    // ── Summary ListView ──────────────────────────────────────────────────────
    private void buildSummaryList() {
        summaryList.setItems(summaryRows);
        summaryList.setFocusTraversable(false);
        summaryList.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        summaryList.setCellFactory(lv -> new SummaryCell());
    }

    // ── Detail TableView ──────────────────────────────────────────────────────
    private void buildDetailTable() {
        detailTable.setItems(detailRows);
        detailTable.setFocusTraversable(false);
        detailTable.setStyle(
            "-fx-control-inner-background: #151d24;"
            + " -fx-font-family: 'Menlo', 'Consolas', 'Monospaced';"
            + " -fx-font-size: 12px;");
        detailTable.setPlaceholder(new Label("Không có cá thể nào"));

        TableColumn<DetailRow, String> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().id));
        idCol.setPrefWidth(48);

        TableColumn<DetailRow, Double> hpCol = new TableColumn<>("♥ HP");
        hpCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().health));
        hpCol.setCellFactory(c -> new BarCell(false));
        hpCol.setPrefWidth(108);

        TableColumn<DetailRow, Double> hungerCol = new TableColumn<>("🍖 Đói");
        hungerCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().hunger));
        hungerCol.setCellFactory(c -> new BarCell(true));
        hungerCol.setPrefWidth(108);

        TableColumn<DetailRow, Double> thirstCol = new TableColumn<>("💧 Khát");
        thirstCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().thirst));
        thirstCol.setCellFactory(c -> new BarCell(true));
        thirstCol.setPrefWidth(108);

        detailTable.getColumns().add(idCol);
        detailTable.getColumns().add(hpCol);
        detailTable.getColumns().add(hungerCol);
        detailTable.getColumns().add(thirstCol);

        detailTable.setRowFactory(tv -> new TableRow<DetailRow>() {
            @Override
            protected void updateItem(DetailRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.health < 0) { setStyle(""); return; }
                if (item.health < 30) {
                    setStyle("-fx-background-color: rgba(248,113,113,0.10);");
                } else if (item.hunger > 70 || item.thirst > 70) {
                    setStyle("-fx-background-color: rgba(251,191,36,0.08);");
                } else {
                    setStyle("");
                }
            }
        });
    }

    // ── Refresh ───────────────────────────────────────────────────────────────
    public void refreshData() {
        if (selectedType == null) refreshSummary();
        else refreshDetail();
    }

    private void refreshSummary() {
        Map<EntityType, Integer> aliveCounts = new EnumMap<>(EntityType.class);
        for (Entity entity : worldMap.getEntities()) {
            EntityType type = entity.getType();
            if (type != null) aliveCounts.merge(type, 1, Integer::sum);
        }

        Set<EntityType> allTypes = new HashSet<>(aliveCounts.keySet());
        allTypes.addAll(worldMap.getDeathCountsByType().keySet());

        summaryRows.clear();
        for (EntityType type : allTypes) {
            summaryRows.add(new SummaryRow(
                type,
                aliveCounts.getOrDefault(type, 0),
                worldMap.getDeathCount(type, DeathCause.HUNGER),
                worldMap.getDeathCount(type, DeathCause.THIRST),
                worldMap.getDeathCount(type, DeathCause.PREDATION)));
        }
        summaryRows.sort((a, b) -> {
            int c = Integer.compare(b.alive, a.alive);
            return c != 0 ? c : a.type.name().compareTo(b.type.name());
        });

        int totalAlive   = summaryRows.stream().mapToInt(r -> r.alive).sum();
        long aliveSpecies = summaryRows.stream().filter(r -> r.alive > 0).count();
        subtitleLabel.setText(aliveSpecies + " loài · " + totalAlive + " cá thể đang sống");

        int td = summaryRows.stream().mapToInt(r -> r.diedHunger + r.diedThirst + r.diedPredation).sum();
        int th = summaryRows.stream().mapToInt(r -> r.diedHunger).sum();
        int tk = summaryRows.stream().mapToInt(r -> r.diedThirst).sum();
        int ts = summaryRows.stream().mapToInt(r -> r.diedPredation).sum();
        footerLabel.setText("Tử vong: " + td
            + "  (đói: " + th + "  khát: " + tk + "  bị săn: " + ts + ")");
    }

    private void refreshDetail() {
        detailRows.clear();
        if (selectedType == null) return;

        for (Entity entity : worldMap.getEntities()) {
            if (entity.getType() != selectedType) continue;
            if (entity instanceof LivingEntity living) {
                detailRows.add(new DetailRow(
                    String.valueOf(living.getId()),
                    living.getHealth(), living.getHunger(), living.getThirst()));
            } else {
                detailRows.add(new DetailRow(String.valueOf(entity.getId()), -1, -1, -1));
            }
        }
        detailRows.sort((a, b) -> {
            try { return Integer.compare(Integer.parseInt(a.id), Integer.parseInt(b.id)); }
            catch (NumberFormatException ex) { return a.id.compareTo(b.id); }
        });

        int total    = detailRows.size();
        long critical = detailRows.stream().filter(r -> r.health >= 0 && r.health < 30).count();
        subtitleLabel.setText(speciesEmoji(selectedType) + " " + selectedType
            + " · " + total + " cá thể");
        String foot = "HP tốt: " + (total - critical);
        if (critical > 0) foot += "  · nguy kịch: " + critical;
        footerLabel.setText(foot);
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    private void showSummary() {
        selectedType = null;
        titleLabel.setText("TỔNG QUAN HỆ SINH THÁI");
        backButton.setVisible(false);
        backButton.setManaged(false);
        summaryList.setVisible(true);
        summaryList.setManaged(true);
        detailTable.setVisible(false);
        detailTable.setManaged(false);
        refreshSummary();
    }

    private void showDetail(EntityType type) {
        selectedType = type;
        titleLabel.setText(speciesEmoji(type) + "  CHI TIẾT: " + type);
        backButton.setVisible(true);
        backButton.setManaged(true);
        summaryList.setVisible(false);
        summaryList.setManaged(false);
        detailTable.setVisible(true);
        detailTable.setManaged(true);
        refreshDetail();
    }

    // ── Summary card cell ─────────────────────────────────────────────────────
    private class SummaryCell extends ListCell<SummaryRow> {
        private final Region accentBar  = new Region();
        private final Label  nameLabel  = new Label();
        private final Label  aliveLabel = new Label();
        private final Label  deathLabel = new Label();
        private final Region spacer     = new Region();
        private final VBox   content;
        private final HBox   root;

        SummaryCell() {
            accentBar.setPrefWidth(5);
            accentBar.setMinWidth(5);
            accentBar.setMaxHeight(Double.MAX_VALUE);

            HBox.setHgrow(spacer, Priority.ALWAYS);

            HBox topRow = new HBox(8, nameLabel, spacer, aliveLabel);
            topRow.setAlignment(Pos.CENTER_LEFT);

            content = new VBox(4, topRow, deathLabel);
            content.setPadding(new Insets(9, 12, 9, 10));
            content.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(content, Priority.ALWAYS);

            root = new HBox(accentBar, content);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setMaxWidth(Double.MAX_VALUE);

            setPadding(new Insets(1, 0, 1, 0));
            setStyle("-fx-background-color: transparent; -fx-padding: 2 8 2 8;");
            setCursor(Cursor.HAND);

            setOnMouseEntered(e -> {
                if (getItem() != null)
                    content.setStyle("-fx-background-color: #232f3b; -fx-background-radius: 0 8 8 0;");
            });
            setOnMouseExited(e -> {
                if (getItem() != null)
                    content.setStyle("-fx-background-color: "
                        + (getItem().alive == 0 ? "#161d24" : "#1b242e")
                        + "; -fx-background-radius: 0 8 8 0;");
            });
            setOnMouseClicked(e -> {
                if (!isEmpty() && getItem() != null) showDetail(getItem().type);
            });
        }

        @Override
        protected void updateItem(SummaryRow item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setGraphic(null); return; }

            boolean extinct = item.alive == 0;
            String color = speciesColor(item.type);
            accentBar.setStyle("-fx-background-color: " + (extinct ? "#2a3540" : color)
                + "; -fx-background-radius: 8 0 0 8;");

            nameLabel.setText(speciesEmoji(item.type) + "  " + item.type);
            nameLabel.setStyle("-fx-text-fill: " + (extinct ? "#5a6b7c" : "#dbe5ee") + ";"
                + " -fx-font-size: 12.5px; -fx-font-weight: bold;"
                + " -fx-font-family: 'Menlo', 'Consolas', 'Monospaced';");

            if (!extinct) {
                aliveLabel.setText("● " + item.alive);
                aliveLabel.setStyle("-fx-text-fill: #34d399; -fx-font-size: 12px;"
                    + " -fx-font-weight: bold; -fx-font-family: 'Menlo', 'Consolas', 'Monospaced';");
            } else {
                aliveLabel.setText("tuyệt chủng");
                aliveLabel.setStyle("-fx-text-fill: #5a6b7c; -fx-font-size: 11px;");
            }

            boolean anyDeath = item.diedHunger > 0 || item.diedThirst > 0 || item.diedPredation > 0;
            if (anyDeath) {
                StringBuilder sb = new StringBuilder("☠  ");
                if (item.diedHunger    > 0) sb.append("đói: ").append(item.diedHunger).append("  ");
                if (item.diedThirst    > 0) sb.append("khát: ").append(item.diedThirst).append("  ");
                if (item.diedPredation > 0) sb.append("bị săn: ").append(item.diedPredation);
                deathLabel.setText(sb.toString().strip());
                deathLabel.setStyle("-fx-text-fill: #b08968; -fx-font-size: 10.5px;"
                    + " -fx-font-family: 'Menlo', 'Consolas', 'Monospaced';");
            } else {
                deathLabel.setText("chưa có tử vong");
                deathLabel.setStyle("-fx-text-fill: #46586a; -fx-font-size: 10.5px;");
            }

            content.setStyle("-fx-background-color: " + (extinct ? "#161d24" : "#1b242e")
                + "; -fx-background-radius: 0 8 8 0;");
            setGraphic(root);
        }
    }

    // ── Progress-bar cell ─────────────────────────────────────────────────────
    private static final class BarCell extends TableCell<DetailRow, Double> {
        private final ProgressBar pb   = new ProgressBar(0);
        private final Label       text = new Label();
        private final StackPane   container;
        private final boolean     inverted;

        BarCell(boolean inverted) {
            this.inverted = inverted;
            pb.setMaxWidth(Double.MAX_VALUE);
            pb.setPrefHeight(15);
            text.setStyle("-fx-text-fill: #dbe5ee; -fx-font-size: 10px; -fx-font-weight: bold;");
            container = new StackPane(pb, text);
            container.setMaxWidth(Double.MAX_VALUE);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPadding(new Insets(3, 6, 3, 6));
        }

        @Override
        protected void updateItem(Double v, boolean empty) {
            super.updateItem(v, empty);
            if (empty || v == null || v < 0) { setGraphic(null); return; }
            pb.setProgress(v / 100.0);
            String accent = inverted
                ? (v < 40 ? "#34d399" : v < 70 ? "#fbbf24" : "#f87171")
                : (v > 60 ? "#34d399" : v > 30 ? "#fbbf24" : "#f87171");
            pb.setStyle("-fx-accent: " + accent + "; -fx-control-inner-background: #232c36;");
            text.setText(String.format("%.0f", v));
            setGraphic(container);
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────
    public static class SummaryRow {
        public final EntityType type;
        public final int alive, diedHunger, diedThirst, diedPredation;

        public SummaryRow(EntityType type, int alive,
                          int diedHunger, int diedThirst, int diedPredation) {
            this.type = type;
            this.alive = alive;
            this.diedHunger = diedHunger;
            this.diedThirst = diedThirst;
            this.diedPredation = diedPredation;
        }
    }

    public static class DetailRow {
        public final String id;
        public final double health, hunger, thirst;

        public DetailRow(String id, double health, double hunger, double thirst) {
            this.id = id;
            this.health = health;
            this.hunger = hunger;
            this.thirst = thirst;
        }
    }
}
