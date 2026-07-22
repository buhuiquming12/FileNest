package com.filenest.ui;

import com.filenest.core.storage.CleanupCandidate;
import com.filenest.core.storage.DiskCleanupService;
import com.filenest.core.storage.FolderSizeService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Modal C-drive cleanup preview. Nothing is removed until rows are selected and confirmed. */
public final class CleanupDialog {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Stage window = new Stage();
    private final DiskCleanupService service = new DiskCleanupService();
    private final ObservableList<CleanupRow> rows = FXCollections.observableArrayList();
    private final TableView<CleanupRow> table = new TableView<>(rows);
    private final Label driveLabel = new Label("尚未扫描");
    private final Label statusLabel = new Label("扫描只读取信息，不会删除文件。");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button scanButton = new Button("扫描 C 盘可清理项");
    private final Button cleanButton = new Button("清理选中项…");

    public CleanupDialog(Stage owner) {
        window.initOwner(owner);
        window.initModality(Modality.WINDOW_MODAL);
        window.setTitle("FileNest · C 盘安全清理");
        window.setMinWidth(780);
        window.setMinHeight(500);
        window.setScene(new Scene(build(), 900, 600));
    }

    public void show() {
        window.show();
        if (rows.isEmpty()) {
            scan();
        }
    }

    private BorderPane build() {
        Label warning = new Label("仅扫描临时文件、崩溃转储和 Windows 更新下载缓存；不会扫描或删除个人文档。"
                + " 最近 24 小时内的项目默认不勾选。");
        warning.setWrapText(true);
        warning.setStyle("-fx-text-fill: #7a4d00; -fx-background-color: #fff8e1; -fx-padding: 8;");

        scanButton.setOnAction(e -> scan());
        cleanButton.setOnAction(e -> clean());
        cleanButton.setDisable(true);
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        HBox top = new HBox(10, driveLabel, topSpacer, scanButton);
        top.setAlignment(Pos.CENTER_LEFT);

        BorderPane root = new BorderPane();
        root.setTop(new VBox(8, warning, top));
        root.setCenter(buildTable());
        root.setBottom(buildBottom());
        root.setPadding(new Insets(12));
        BorderPane.setMargin(table, new Insets(10, 0, 8, 0));
        return root;
    }

    private TableView<CleanupRow> buildTable() {
        table.setEditable(true);
        table.setPlaceholder(new Label("点击扫描后显示可清理项"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<CleanupRow, Boolean> selected = new TableColumn<>("清理");
        selected.setCellValueFactory(cd -> cd.getValue().selectedProperty());
        selected.setCellFactory(CheckBoxTableCell.forTableColumn(selected));
        selected.setMaxWidth(60);
        selected.setSortable(false);

        TableColumn<CleanupRow, String> category = new TableColumn<>("类型");
        category.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().category()));
        category.setPrefWidth(150);

        TableColumn<CleanupRow, String> name = new TableColumn<>("项目");
        name.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().name()));
        name.setPrefWidth(220);

        TableColumn<CleanupRow, String> size = new TableColumn<>("大小");
        size.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                FolderSizeService.formatBytes(cd.getValue().size())));
        size.setPrefWidth(90);

        TableColumn<CleanupRow, String> modified = new TableColumn<>("最后修改");
        modified.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                DATE.format(cd.getValue().candidate().lastModified())));
        modified.setPrefWidth(140);

        TableColumn<CleanupRow, String> path = new TableColumn<>("路径");
        path.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().path()));
        path.setPrefWidth(300);

        table.getColumns().setAll(List.of(selected, category, name, size, modified, path));
        return table;
    }

    private HBox buildBottom() {
        progress.setVisible(false);
        progress.setPrefSize(22, 22);
        CheckBox selectAll = new CheckBox("全选");
        selectAll.setOnAction(e -> {
            rows.forEach(row -> row.setSelected(selectAll.isSelected()));
            table.refresh();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(9, progress, statusLabel, spacer, selectAll, cleanButton);
        bottom.setAlignment(Pos.CENTER_LEFT);
        return bottom;
    }

    private void scan() {
        runAsync("正在扫描安全清理位置…", service::scan, preview -> {
            rows.setAll(preview.candidates().stream().map(CleanupRow::new).toList());
            driveLabel.setText(String.format("%s  总容量 %s · 可用 %s · 可清理约 %s",
                    preview.drive(), FolderSizeService.formatBytes(preview.totalSpace()),
                    FolderSizeService.formatBytes(preview.freeSpace()),
                    FolderSizeService.formatBytes(preview.reclaimableBytes())));
            cleanButton.setDisable(rows.isEmpty());
            statusLabel.setText(String.format("找到 %,d 项；已默认勾选 24 小时前的项目%s。",
                    rows.size(), preview.inaccessibleCount() > 0
                            ? "；" + preview.inaccessibleCount() + " 处无权读取" : ""));
        });
    }

    private void clean() {
        List<CleanupCandidate> selected = rows.stream().filter(CleanupRow::isSelected)
                .map(CleanupRow::candidate).toList();
        if (selected.isEmpty()) {
            info("请先勾选要清理的项目。");
            return;
        }
        long bytes = selected.stream().mapToLong(CleanupCandidate::size).sum();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将永久删除 " + selected.size() + " 个临时/缓存项目，预计释放 "
                        + FolderSizeService.formatBytes(bytes) + "。\n此操作不可通过整理记录撤销，是否继续？",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("确认清理 C 盘");
        confirm.setHeaderText("永久删除选中的清理项？");
        confirm.initOwner(window);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runAsync("正在清理…", () -> service.clean(selected), result -> {
            String message = String.format("已删除 %d 项，失败 %d 项，预计释放 %s。",
                    result.deleted(), result.failed(), FolderSizeService.formatBytes(result.reclaimedBytes()));
            if (!result.messages().isEmpty()) {
                message += "\n\n" + String.join("\n", result.messages().stream().limit(15).toList());
            }
            info(message);
            scan();
        });
    }

    private <T> void runAsync(String message, Callable<T> work, Consumer<T> success) {
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return work.call(); }
        };
        task.setOnSucceeded(e -> {
            setBusy(false, "");
            success.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            setBusy(false, "");
            Throwable error = task.getException();
            alert(Alert.AlertType.ERROR, error == null ? "未知错误" : error.getMessage());
        });
        setBusy(true, message);
        Thread thread = new Thread(task, "filenest-cleanup");
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean busy, String message) {
        Platform.runLater(() -> {
            progress.setVisible(busy);
            scanButton.setDisable(busy);
            cleanButton.setDisable(busy || rows.isEmpty());
            if (busy) statusLabel.setText(message);
        });
    }

    private void info(String message) { alert(Alert.AlertType.INFORMATION, message); }

    private void alert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.initOwner(window);
        alert.showAndWait();
    }
}
