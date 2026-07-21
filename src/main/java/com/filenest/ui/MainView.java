package com.filenest.ui;

import com.filenest.app.OrganizeService;
import com.filenest.core.executor.ConflictPolicy;
import com.filenest.model.FileAction;
import com.filenest.model.OrganizeContext;
import com.filenest.model.OrganizePlan;
import com.filenest.model.OrganizeResult;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * The main window. It only knows the {@link OrganizeService} façade and the
 * {@link OrganizePlan} data structure — it contains no scanning, classification or file
 * logic (separation of concerns). Its whole job: pick a folder, show the proposed plan,
 * let the user confirm a selection, and trigger execute/undo.
 */
public final class MainView {

    private final OrganizeService service;
    private final Stage stage;
    private final BorderPane root = new BorderPane();

    private final ObservableList<ActionRow> rows = FXCollections.observableArrayList();
    private final TableView<ActionRow> table = new TableView<>(rows);

    private final Label pathLabel = new Label("尚未选择目录");
    private final ComboBox<OrganizeContext.Scheme> schemeBox = new ComboBox<>();
    private final ComboBox<ConflictPolicy> conflictBox = new ComboBox<>();
    private final Label statusLabel = new Label();
    private final Label advisorLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    private final Button scanButton = new Button("扫描并生成计划");
    private final Button executeButton = new Button("执行选中…");
    private final Button undoButton = new Button("撤销上次整理");
    private final Button selectAllButton = new Button("全选");
    private final Button selectNoneButton = new Button("全不选");

    private Path currentDir;

    public MainView(OrganizeService service, Stage stage) {
        this.service = service;
        this.stage = stage;
        build();
    }

    public BorderPane getRoot() {
        return root;
    }

    // ---- layout -----------------------------------------------------------------------

    private void build() {
        root.setTop(buildTopBar());
        root.setCenter(buildTable());
        root.setBottom(buildBottomArea());
        root.setPadding(new Insets(10));

        progress.setVisible(false);
        progress.setPrefSize(22, 22);

        executeButton.setDefaultButton(true);
        executeButton.setStyle("-fx-font-weight: bold;");
        scanButton.setDisable(true);
        executeButton.setDisable(true);

        advisorLabel.setText("AI: " + service.advisorName()
                + (service.advisorAvailable() ? "" : "（当前不可用，仅用规则）"));
        undoButton.setDisable(!service.canUndo());

        wireActions();
    }

    private HBox buildTopBar() {
        Button chooseButton = new Button("选择目录…");
        chooseButton.setOnAction(e -> chooseDirectory());

        schemeBox.getItems().setAll(OrganizeContext.Scheme.values());
        schemeBox.getSelectionModel().select(OrganizeContext.Scheme.BY_TYPE);
        schemeBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(OrganizeContext.Scheme s) {
                return s == null ? "" : s.label();
            }

            @Override
            public OrganizeContext.Scheme fromString(String s) {
                return null;
            }
        });

        pathLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(pathLabel, Priority.ALWAYS);
        pathLabel.setStyle("-fx-text-fill: #444; -fx-border-color: #ddd; -fx-border-radius: 4; "
                + "-fx-padding: 4 8; -fx-background-color: #fafafa;");

        HBox bar = new HBox(8, chooseButton, pathLabel, new Label("整理方式:"), schemeBox, scanButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 10, 0));
        return bar;
    }

    private TableView<ActionRow> buildTable() {
        table.setEditable(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("选择一个目录并点击“扫描并生成计划”"));

        TableColumn<ActionRow, Boolean> selectCol = new TableColumn<>("整理");
        selectCol.setCellValueFactory(cd -> cd.getValue().selectedProperty());
        selectCol.setCellFactory(CheckBoxTableCell.forTableColumn(selectCol));
        selectCol.setEditable(true);
        selectCol.setMaxWidth(60);
        selectCol.setMinWidth(50);
        selectCol.setSortable(false);

        TableColumn<ActionRow, String> fileCol = new TableColumn<>("文件");
        fileCol.setCellValueFactory(new PropertyValueFactory<>("fileName"));

        TableColumn<ActionRow, String> targetCol = new TableColumn<>("建议去向");
        targetCol.setCellValueFactory(new PropertyValueFactory<>("target"));

        TableColumn<ActionRow, String> reasonCol = new TableColumn<>("依据");
        reasonCol.setCellValueFactory(new PropertyValueFactory<>("reason"));
        reasonCol.setCellFactory(col -> wrappingCell());

        TableColumn<ActionRow, String> sourceCol = new TableColumn<>("来源");
        sourceCol.setCellValueFactory(new PropertyValueFactory<>("source"));
        sourceCol.setMaxWidth(70);
        sourceCol.setCellFactory(col -> sourceCell());

        TableColumn<ActionRow, Number> confCol = new TableColumn<>("置信度");
        confCol.setCellValueFactory(cd -> new javafx.beans.property.SimpleDoubleProperty(
                cd.getValue().getConfidence()));
        confCol.setMaxWidth(90);
        confCol.setCellFactory(col -> confidenceCell());

        table.getColumns().setAll(List.of(selectCol, fileCol, targetCol, reasonCol, sourceCol, confCol));
        // Relative widths.
        fileCol.setPrefWidth(200);
        targetCol.setPrefWidth(200);
        reasonCol.setPrefWidth(320);
        return table;
    }

    private VBox buildBottomArea() {
        conflictBox.getItems().setAll(ConflictPolicy.values());
        conflictBox.getSelectionModel().select(ConflictPolicy.RENAME);
        conflictBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ConflictPolicy p) {
                if (p == ConflictPolicy.RENAME) return "重命名（推荐）";
                if (p == ConflictPolicy.SKIP) return "跳过";
                return String.valueOf(p);
            }

            @Override
            public ConflictPolicy fromString(String s) {
                return null;
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actionBar = new HBox(8, selectAllButton, selectNoneButton, spacer,
                new Label("冲突处理:"), conflictBox, undoButton, executeButton);
        actionBar.setAlignment(Pos.CENTER_LEFT);
        actionBar.setPadding(new Insets(10, 0, 6, 0));

        statusLabel.setStyle("-fx-text-fill: #333;");
        HBox statusBar = new HBox(8, progress, statusLabel, new Region(), advisorLabel);
        HBox.setHgrow(statusBar.getChildren().get(2), Priority.ALWAYS);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        advisorLabel.setStyle("-fx-text-fill: #777; -fx-font-size: 11;");

        return new VBox(actionBar, statusBar);
    }

    private void wireActions() {
        scanButton.setOnAction(e -> scan());
        executeButton.setOnAction(e -> execute());
        undoButton.setOnAction(e -> undo());
        selectAllButton.setOnAction(e -> setAll(true));
        selectNoneButton.setOnAction(e -> setAll(false));
    }

    // ---- actions ----------------------------------------------------------------------

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择要整理的文件夹");
        if (currentDir != null) {
            File cur = currentDir.toFile();
            if (cur.isDirectory()) {
                chooser.setInitialDirectory(cur);
            }
        }
        File chosen = chooser.showDialog(stage);
        if (chosen != null) {
            currentDir = chosen.toPath();
            pathLabel.setText(currentDir.toString());
            scanButton.setDisable(false);
            rows.clear();
            executeButton.setDisable(true);
            statusLabel.setText("已选择目录，点击“扫描并生成计划”。");
        }
    }

    private void scan() {
        if (currentDir == null) {
            return;
        }
        OrganizeContext ctx = new OrganizeContext(currentDir, schemeBox.getValue());
        Path dir = currentDir;
        runAsync("正在扫描并生成计划…",
                () -> service.plan(ctx),
                (OrganizePlan plan) -> {
                    rows.clear();
                    for (FileAction action : plan.actions()) {
                        rows.add(new ActionRow(action, dir));
                    }
                    long effective = plan.effectiveActions().size();
                    long preChecked = rows.stream().filter(ActionRow::isSelected).count();
                    executeButton.setDisable(rows.isEmpty());
                    statusLabel.setText(String.format(
                            "共 %d 项建议，其中 %d 项可整理，已默认勾选 %d 项（需确认的项默认不勾选）。",
                            rows.size(), effective, preChecked));
                });
    }

    private void execute() {
        List<FileAction> selected = rows.stream()
                .filter(ActionRow::isSelected)
                .map(ActionRow::action)
                .filter(FileAction::isEffective)
                .toList();

        if (selected.isEmpty()) {
            info("没有勾选任何可整理的项。");
            return;
        }

        long needConfirm = rows.stream()
                .filter(ActionRow::isSelected)
                .filter(r -> r.action().isEffective() && r.requiresConfirm())
                .count();

        StringBuilder msg = new StringBuilder("即将移动 " + selected.size() + " 个文件。");
        if (needConfirm > 0) {
            msg.append("\n其中 ").append(needConfirm).append(" 项来自 AI 建议或重复检测，请再次确认。");
        }
        msg.append("\n\n冲突处理策略：").append(conflictBox.getValue() == ConflictPolicy.RENAME
                ? "自动重命名（不会覆盖已有文件）" : "跳过冲突项");
        msg.append("\n操作可随时通过“撤销上次整理”回退。");

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg.toString(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("确认整理");
        confirm.setHeaderText("确认执行整理？");
        confirm.initOwner(stage);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        ConflictPolicy policy = conflictBox.getValue();
        runAsync("正在整理文件…",
                () -> service.execute(selected, policy),
                (OrganizeResult result) -> {
                    rows.clear();
                    executeButton.setDisable(true);
                    undoButton.setDisable(!service.canUndo());
                    statusLabel.setText(String.format("整理完成：成功 %d，跳过 %d，失败 %d。",
                            result.succeeded(), result.skipped(), result.failed()));
                    showResult("整理结果", result);
                });
    }

    private void undo() {
        if (!service.canUndo()) {
            info("没有可撤销的整理记录。");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将把上一次整理移动过的文件全部移回原位置。是否继续？",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("撤销整理");
        confirm.setHeaderText("撤销上次整理？");
        confirm.initOwner(stage);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        ConflictPolicy policy = conflictBox.getValue();
        runAsync("正在撤销…",
                () -> service.undoLast(policy),
                (OrganizeResult result) -> {
                    undoButton.setDisable(!service.canUndo());
                    statusLabel.setText(String.format("撤销完成：成功 %d，跳过 %d，失败 %d。",
                            result.succeeded(), result.skipped(), result.failed()));
                    showResult("撤销结果", result);
                });
    }

    private void setAll(boolean value) {
        for (ActionRow row : rows) {
            // Only ever auto-select rows that would actually do something.
            row.setSelected(value && row.isEffective());
        }
        table.refresh();
    }

    // ---- async plumbing ---------------------------------------------------------------

    private <T> void runAsync(String busyMsg, Callable<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false, "");
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            setBusy(false, "");
            Throwable ex = task.getException();
            error(ex == null ? "未知错误" : ex.getMessage());
        });
        setBusy(true, busyMsg);
        Thread t = new Thread(task, "filenest-task");
        t.setDaemon(true);
        t.start();
    }

    private void setBusy(boolean busy, String message) {
        Platform.runLater(() -> {
            progress.setVisible(busy);
            scanButton.setDisable(busy || currentDir == null);
            executeButton.setDisable(busy || rows.isEmpty());
            undoButton.setDisable(busy || !service.canUndo());
            selectAllButton.setDisable(busy);
            selectNoneButton.setDisable(busy);
            if (busy) {
                statusLabel.setText(message);
            }
        });
    }

    // ---- table cell factories ---------------------------------------------------------

    private TableCell<ActionRow, String> wrappingCell() {
        return new TableCell<>() {
            private final Text text = new Text();

            {
                text.wrappingWidthProperty().bind(widthProperty().subtract(10));
                setGraphic(text);
                setPrefHeight(Region.USE_COMPUTED_SIZE);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                text.setText(empty || value == null ? "" : value);
            }
        };
    }

    private TableCell<ActionRow, String> sourceCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(value);
                if ("AI".equals(value)) {
                    setStyle("-fx-text-fill: #6a1b9a; -fx-font-weight: bold;");
                } else {
                    setStyle("-fx-text-fill: #1565c0;");
                }
            }
        };
    }

    private TableCell<ActionRow, Number> confidenceCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    setStyle("");
                    setTooltip(null);
                    return;
                }
                double c = value.doubleValue();
                setText(Math.round(c * 100) + "%");
                String color = c >= 0.9 ? "#2e7d32" : (c >= 0.6 ? "#ef6c00" : "#c62828");
                setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
                setTooltip(new Tooltip(c >= 0.9 ? "高置信度（规则/精确匹配）"
                        : c >= 0.6 ? "中等置信度（建议确认）" : "低置信度（务必人工确认）"));
            }
        };
    }

    // ---- dialogs ----------------------------------------------------------------------

    private void showResult(String title, OrganizeResult result) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String m : result.messages()) {
            sb.append(m).append('\n');
            if (++shown >= 40) {
                sb.append("… 其余 ").append(result.messages().size() - shown).append(" 条省略");
                break;
            }
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(String.format("成功 %d，跳过 %d，失败 %d",
                result.succeeded(), result.skipped(), result.failed()));
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(sb.toString());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefSize(560, 320);
        alert.getDialogPane().setContent(area);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private void info(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private void error(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle("出错了");
        alert.setHeaderText("操作失败");
        alert.initOwner(stage);
        alert.showAndWait();
    }
}
