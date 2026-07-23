package com.filenest.ui;

import com.filenest.app.OrganizeService;
import com.filenest.app.StorageAnalysisService;
import com.filenest.core.executor.AutoOrganizeSafetyPolicy;
import com.filenest.core.executor.ConflictPolicy;
import com.filenest.core.storage.CleanupSuggestion;
import com.filenest.core.storage.FolderSizeService;
import com.filenest.core.storage.StorageScanResult;
import com.filenest.model.FileAction;
import com.filenest.model.OrganizeContext;
import com.filenest.model.OrganizePlan;
import com.filenest.model.OrganizeResult;
import com.filenest.model.OrganizeScan;

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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
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
    private final StorageAnalysisService storageService;
    private final AutoOrganizeSafetyPolicy autoSafety = new AutoOrganizeSafetyPolicy();
    private final FileLocationOpener locationOpener = new FileLocationOpener();
    private final Stage stage;
    private final BorderPane root = new BorderPane();

    private final ObservableList<ActionRow> rows = FXCollections.observableArrayList();
    private final TableView<ActionRow> table = new TableView<>(rows);
    private final ObservableList<FolderUsageRow> folderRows = FXCollections.observableArrayList();
    private final TableView<FolderUsageRow> folderTable = new TableView<>(folderRows);
    private final ObservableList<FileInventoryRow> fileRows = FXCollections.observableArrayList();
    private final TableView<FileInventoryRow> fileTable = new TableView<>(fileRows);
    private final ObservableList<CleanupAdviceRow> cleanupAdviceRows = FXCollections.observableArrayList();
    private final TableView<CleanupAdviceRow> cleanupAdviceTable = new TableView<>(cleanupAdviceRows);
    private final TabPane tabs = new TabPane();

    private final Label pathLabel = new Label("尚未选择目录");
    private final ComboBox<OrganizeContext.Scheme> schemeBox = new ComboBox<>();
    private final ComboBox<ConflictPolicy> conflictBox = new ComboBox<>();
    private final Label statusLabel = new Label();
    private final Label advisorLabel = new Label();
    private final Label folderSizeLabel = new Label("文件夹大小：—");
    private final TextField aiUrlField = new TextField(env("FILENEST_LLM_ENDPOINT"));
    private final PasswordField aiKeyField = new PasswordField();
    private final ComboBox<String> aiModelBox = new ComboBox<>();
    private final ProgressBar progress = new ProgressBar();

    private final Button scanButton = new Button("扫描文件夹占用");
    private final Button suggestButton = new Button("生成整理建议");
    private final Button cleanupAdviceButton = new Button("AI 清理建议");
    private final Button executeButton = new Button("执行选中…");
    private final Button ruleAutoButton = new Button("规则安全自动整理");
    private final Button aiAutoButton = new Button("AI 建议自动整理");
    private final Button undoButton = new Button("撤销上次整理");
    private final Button selectAllButton = new Button("全选");
    private final Button selectNoneButton = new Button("全不选");

    private final Button applyAiButton = new Button("应用 AI API");
    private final Button fetchModelsButton = new Button("获取模型");
    private final Button cleanupButton = new Button("C 盘清理…");

    private Path currentDir;
    private OrganizeScan currentOrganizeScan;
    private StorageScanResult currentStorageScan;

    public MainView(OrganizeService service, Stage stage) {
        this(service, new StorageAnalysisService(), stage);
    }

    public MainView(OrganizeService service, StorageAnalysisService storageService, Stage stage) {
        this.service = service;
        this.storageService = storageService;
        this.stage = stage;
        build();
    }

    public BorderPane getRoot() {
        return root;
    }

    // ---- layout -----------------------------------------------------------------------

    private void build() {
        root.setTop(new VBox(7, buildTopBar(), buildAiBar()));
        root.setCenter(buildTabs());
        root.setBottom(buildBottomArea());
        root.setPadding(new Insets(10));

        progress.setVisible(false);
        progress.setPrefWidth(180);
        progress.setProgress(-1);

        executeButton.setDefaultButton(true);
        executeButton.setStyle("-fx-font-weight: bold;");
        scanButton.setDisable(true);
        suggestButton.setDisable(true);
        cleanupAdviceButton.setDisable(true);
        executeButton.setDisable(true);
        ruleAutoButton.setDisable(true);
        aiAutoButton.setDisable(true);
        ruleAutoButton.setTooltip(new Tooltip("仅执行已知类型、无需额外确认的规则建议"));
        aiAutoButton.setTooltip(new Tooltip("仅执行置信度不低于 80% 且通过安全检查的 AI 建议"));

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

        folderSizeLabel.setStyle("-fx-text-fill: #1565c0; -fx-font-size: 11;");
        HBox bar = new HBox(8, chooseButton, pathLabel, folderSizeLabel,
                new Label("整理方式:"), schemeBox, scanButton, suggestButton, cleanupAdviceButton, cleanupButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private HBox buildAiBar() {
        aiUrlField.setPromptText("例如 https://api.example.com（自动补 /v1/chat/completions；留空使用本地 AI）");
        aiKeyField.setPromptText("可选，本地 API 可留空");
        aiKeyField.setText(env("FILENEST_LLM_API_KEY"));
        String initialModel = envOr("FILENEST_LLM_MODEL", "gpt-4o-mini");
        aiModelBox.setEditable(true);
        aiModelBox.getItems().setAll(initialModel);
        aiModelBox.getSelectionModel().select(initialModel);
        aiModelBox.getEditor().setText(initialModel);
        aiModelBox.setPromptText("模型名");
        aiUrlField.setPrefWidth(340);
        aiKeyField.setPrefWidth(150);
        aiModelBox.setPrefWidth(165);
        HBox.setHgrow(aiUrlField, Priority.ALWAYS);

        HBox bar = new HBox(7, new Label("AI API 地址:"), aiUrlField,
                new Label("Key:"), aiKeyField, new Label("模型:"), aiModelBox,
                fetchModelsButton, applyAiButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 9, 0));
        return bar;
    }

    private TabPane buildTabs() {
        Tab usageTab = new Tab("文件夹占用", buildFolderTable());
        Tab inventoryTab = new Tab("全部文件（当前目录）", buildFileInventoryTable());
        Tab organizeTab = new Tab("整理建议", buildTable());
        Tab cleanupTab = new Tab("清理建议（仅预览）", buildCleanupAdviceTable());
        usageTab.setClosable(false);
        inventoryTab.setClosable(false);
        organizeTab.setClosable(false);
        cleanupTab.setClosable(false);
        tabs.getTabs().setAll(usageTab, inventoryTab, organizeTab, cleanupTab);
        return tabs;
    }

    private TableView<FolderUsageRow> buildFolderTable() {
        folderTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        folderTable.setPlaceholder(new Label("点击“扫描文件夹占用”查看各子文件夹大小"));
        TableColumn<FolderUsageRow, String> folder = new TableColumn<>("文件夹");
        folder.setCellValueFactory(new PropertyValueFactory<>("folder"));
        TableColumn<FolderUsageRow, String> size = new TableColumn<>("占用大小");
        size.setCellValueFactory(new PropertyValueFactory<>("size"));
        TableColumn<FolderUsageRow, Number> percent = new TableColumn<>("占比");
        percent.setCellValueFactory(new PropertyValueFactory<>("percent"));
        percent.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : String.format("%.1f%%", value.doubleValue()));
            }
        });
        TableColumn<FolderUsageRow, Number> files = new TableColumn<>("文件数");
        files.setCellValueFactory(new PropertyValueFactory<>("fileCount"));
        TableColumn<FolderUsageRow, Number> folders = new TableColumn<>("子文件夹数");
        folders.setCellValueFactory(new PropertyValueFactory<>("folderCount"));
        TableColumn<FolderUsageRow, String> path = new TableColumn<>("完整路径");
        path.setCellValueFactory(new PropertyValueFactory<>("path"));
        folderTable.getColumns().setAll(List.of(folder, size, percent, files, folders, path));
        folder.setPrefWidth(180); path.setPrefWidth(380);
        return folderTable;
    }


    private TableView<FileInventoryRow> buildFileInventoryTable() {
        fileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        fileTable.setPlaceholder(new Label("扫描后显示当前目录顶层的全部文件，包括未知类型和隐藏文件"));
        TableColumn<FileInventoryRow, String> name = new TableColumn<>("文件名");
        name.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        TableColumn<FileInventoryRow, String> type = new TableColumn<>("扩展名/类型");
        type.setCellValueFactory(new PropertyValueFactory<>("type"));
        TableColumn<FileInventoryRow, String> category = new TableColumn<>("分类");
        category.setCellValueFactory(new PropertyValueFactory<>("category"));
        TableColumn<FileInventoryRow, String> size = new TableColumn<>("大小");
        size.setCellValueFactory(new PropertyValueFactory<>("size"));
        TableColumn<FileInventoryRow, String> modified = new TableColumn<>("修改时间");
        modified.setCellValueFactory(new PropertyValueFactory<>("modified"));
        TableColumn<FileInventoryRow, String> safety = new TableColumn<>("安全状态");
        safety.setCellValueFactory(new PropertyValueFactory<>("safety"));
        fileTable.getColumns().setAll(List.of(name, type, category, size, modified, safety));
        name.setPrefWidth(260);
        safety.setPrefWidth(220);
        return fileTable;
    }

    private TableView<CleanupAdviceRow> buildCleanupAdviceTable() {
        cleanupAdviceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        cleanupAdviceTable.setPlaceholder(new Label("先扫描，再点击“AI 清理建议”；建议不会自动删除文件"));
        TableColumn<CleanupAdviceRow, String> path = new TableColumn<>("完整路径");
        path.setCellValueFactory(new PropertyValueFactory<>("path"));
        path.setCellFactory(col -> cleanupPathCell());
        TableColumn<CleanupAdviceRow, String> size = new TableColumn<>("大小");
        size.setCellValueFactory(new PropertyValueFactory<>("size"));
        TableColumn<CleanupAdviceRow, String> decision = new TableColumn<>("结论");
        decision.setCellValueFactory(new PropertyValueFactory<>("decision"));
        decision.setCellFactory(col -> cleanupDecisionCell());
        TableColumn<CleanupAdviceRow, String> reason = new TableColumn<>("建议与风险");
        reason.setCellValueFactory(new PropertyValueFactory<>("reason"));
        reason.setCellFactory(col -> cleanupWrappingCell());
        TableColumn<CleanupAdviceRow, Number> confidence = new TableColumn<>("置信度");
        confidence.setCellValueFactory(new PropertyValueFactory<>("confidence"));
        confidence.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value.intValue() + "%");
            }
        });
        TableColumn<CleanupAdviceRow, String> source = new TableColumn<>("来源");
        source.setCellValueFactory(new PropertyValueFactory<>("source"));
        TableColumn<CleanupAdviceRow, Void> location = new TableColumn<>("打开位置");
        location.setCellFactory(col -> cleanupLocationCell());
        cleanupAdviceTable.getColumns().setAll(
                List.of(path, size, decision, reason, confidence, source, location));
        path.setPrefWidth(350);
        reason.setPrefWidth(340);
        location.setPrefWidth(92);
        return cleanupAdviceTable;
    }

    private TableView<ActionRow> buildTable() {
        table.setEditable(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("先扫描文件夹，再点击“生成整理建议”"));

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

        HBox actionBar = new HBox(8, selectAllButton, selectNoneButton,
                ruleAutoButton, aiAutoButton, spacer,
                new Label("冲突处理:"), conflictBox, undoButton, executeButton);
        actionBar.setAlignment(Pos.CENTER_LEFT);
        actionBar.setPadding(new Insets(10, 0, 6, 0));

        statusLabel.setStyle("-fx-text-fill: #333;");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        Tooltip statusTooltip = new Tooltip();
        statusTooltip.textProperty().bind(statusLabel.textProperty());
        statusTooltip.setWrapText(true);
        statusTooltip.setMaxWidth(760);
        statusLabel.setTooltip(statusTooltip);
        statusLabel.setOnMouseClicked(event -> {
            String message = statusLabel.getText();
            if (message != null && message.contains("URL AI 调用失败")) error(message);
        });
        HBox statusBar = new HBox(8, progress, statusLabel, new Region(), advisorLabel);
        HBox.setHgrow(statusBar.getChildren().get(2), Priority.ALWAYS);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        advisorLabel.setStyle("-fx-text-fill: #777; -fx-font-size: 11;");

        return new VBox(actionBar, statusBar);
    }

    private void wireActions() {
        scanButton.setOnAction(e -> scan());
        suggestButton.setOnAction(e -> generateOrganizeSuggestions());
        cleanupAdviceButton.setOnAction(e -> generateCleanupAdvice());
        executeButton.setOnAction(e -> execute());
        ruleAutoButton.setOnAction(e -> autoOrganize(AutoOrganizeSafetyPolicy.Mode.RULES));
        aiAutoButton.setOnAction(e -> autoOrganize(AutoOrganizeSafetyPolicy.Mode.AI));
        undoButton.setOnAction(e -> undo());
        selectAllButton.setOnAction(e -> setAll(true));
        selectNoneButton.setOnAction(e -> setAll(false));
        fetchModelsButton.setOnAction(e -> fetchModels());
        applyAiButton.setOnAction(e -> applyAiConfiguration());
        cleanupButton.setOnAction(e -> new CleanupDialog(stage).show());
    }

    // ---- actions ----------------------------------------------------------------------

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择要分析的文件夹");
        if (currentDir != null) {
            File cur = currentDir.toFile();
            if (cur.isDirectory()) chooser.setInitialDirectory(cur);
        }
        File chosen = chooser.showDialog(stage);
        if (chosen != null) {
            currentDir = chosen.toPath().toAbsolutePath().normalize();
            currentOrganizeScan = null;
            currentStorageScan = null;
            pathLabel.setText(currentDir.toString());
            folderSizeLabel.setText("文件夹大小：—");
            rows.clear();
            folderRows.clear();
            fileRows.clear();
            cleanupAdviceRows.clear();
            scanButton.setDisable(false);
            suggestButton.setDisable(true);
            cleanupAdviceButton.setDisable(true);
            executeButton.setDisable(true);
            ruleAutoButton.setDisable(true);
            aiAutoButton.setDisable(true);
            statusLabel.setText("已选择目录。先扫描占用，再按需获取整理建议或 AI 清理建议。");
            tabs.getSelectionModel().select(0);
        }
    }

    private String selectedModel() {
        String editorText = aiModelBox.getEditor().getText();
        if (editorText != null && !editorText.isBlank()) return editorText.trim();
        String value = aiModelBox.getValue();
        return value == null || value.isBlank() ? "gpt-4o-mini" : value.trim();
    }

    private void fetchModels() {
        String endpoint = aiUrlField.getText();
        String key = aiKeyField.getText();
        runAsync("正在从 URL 获取模型列表…",
                () -> storageService.fetchModels(endpoint, key), models -> {
                    String previous = selectedModel();
                    aiModelBox.getItems().setAll(models);
                    String selected = models.contains(previous) ? previous : models.get(0);
                    aiModelBox.getSelectionModel().select(selected);
                    aiModelBox.getEditor().setText(selected);
                    statusLabel.setText("已获取 " + models.size() + " 个模型，请选择后点击“应用 AI API”。");
                });
    }

    private void applyAiConfiguration() {
        try {
            String model = selectedModel();
            service.configureAiApi(aiUrlField.getText(), aiKeyField.getText(), model);
            storageService.configureAi(aiUrlField.getText(), aiKeyField.getText(), model);
            advisorLabel.setText("AI: " + service.advisorName()
                    + (service.advisorAvailable() ? "" : "（当前不可用，仅用规则）"));
            statusLabel.setText(aiUrlField.getText() == null || aiUrlField.getText().isBlank()
                    ? "已切换为本地离线建议。"
                    : "已应用 URL AI；整理建议与清理建议会独立调用，异常时自动降级。");
        } catch (IllegalArgumentException ex) {
            error(ex.getMessage());
        }
    }

    /** Scans facts only. It does not run classification or any AI advisor. */
    private void scan() {
        if (currentDir == null) return;
        OrganizeContext ctx = new OrganizeContext(currentDir, schemeBox.getValue());
        Path dir = currentDir;
        currentOrganizeScan = null;
        currentStorageScan = null;
        rows.clear();
        folderRows.clear();
        fileRows.clear();
        cleanupAdviceRows.clear();
        executeButton.setDisable(true);
        ruleAutoButton.setDisable(true);
        aiAutoButton.setDisable(true);
        folderSizeLabel.setText("文件夹大小：扫描中…");
        Task<MainScan> scanTask = new Task<>() {
            @Override
            protected MainScan call() throws Exception {
                updateProgress(0, 1);
                OrganizeScan organizeScan = service.scan(ctx);
                updateProgress(0.03, 1);
                StorageScanResult storageScan = storageService.scan(dir, fraction ->
                        updateProgress(0.03 + fraction * 0.97, 1));
                updateProgress(1, 1);
                return new MainScan(organizeScan, storageScan);
            }
        };
        runTask("正在并行扫描文件与各文件夹占用…", scanTask, result -> {
                    if (!dir.equals(currentDir)) return;
                    currentOrganizeScan = result.organizeScan();
                    currentStorageScan = result.storageScan();
                    fileRows.setAll(result.organizeScan().files().stream().map(FileInventoryRow::new).toList());
                    folderRows.setAll(result.storageScan().folders().stream()
                            .map(usage -> new FolderUsageRow(usage, result.storageScan())).toList());
                    folderSizeLabel.setText("文件夹大小：" + result.storageScan().total().displayText());
                    suggestButton.setDisable(false);
                    cleanupAdviceButton.setDisable(false);
                    tabs.getSelectionModel().select(0);
                    statusLabel.setText(String.format(
                            "扫描完成：%,d 个文件、%,d 个子文件夹；现在可分别生成整理建议或 AI 清理建议。",
                            result.storageScan().total().fileCount(), result.storageScan().total().folderCount()));
                });
    }

    /** Uses the prior scan; no filesystem scan is hidden inside this action. */
    private void generateOrganizeSuggestions() {
        if (currentDir == null || currentOrganizeScan == null) {
            info("请先扫描当前文件夹。");
            return;
        }
        OrganizeContext ctx = new OrganizeContext(currentDir, schemeBox.getValue());
        Path dir = currentDir;
        OrganizeScan scan = currentOrganizeScan;
        runAsync("正在生成整理建议…", () -> service.suggest(scan, ctx), plan -> {
            rows.clear();
            for (FileAction action : plan.actions()) rows.add(new ActionRow(action, dir));
            long effective = plan.effectiveActions().size();
            long preChecked = rows.stream().filter(ActionRow::isSelected).count();
            executeButton.setDisable(rows.isEmpty());
            ruleAutoButton.setDisable(rows.isEmpty());
            aiAutoButton.setDisable(rows.isEmpty());
            tabs.getSelectionModel().select(2);
            OrganizeService.AiRun aiRun = service.lastAiRun();
            String remoteStatus = !aiRun.remoteConfigured()
                    ? ""
                    : aiRun.remoteSucceeded()
                    ? "；URL AI 调用成功"
                    : "；URL AI 调用失败，已回退本地建议：" + aiRun.error();
            statusLabel.setText(String.format(
                    "共 %d 项整理建议，其中 %d 项可整理，已默认勾选 %d 项%s。",
                    rows.size(), effective, preChecked, remoteStatus));
        });
    }

    /** Generates read-only cleanup advice from prior scan data; it never deletes anything. */
    private void generateCleanupAdvice() {
        if (currentStorageScan == null) {
            info("请先扫描当前文件夹。");
            return;
        }
        StorageScanResult scan = currentStorageScan;
        runAsync("正在生成 AI 清理建议…", () -> storageService.advise(scan), suggestions -> {
            cleanupAdviceRows.setAll(suggestions.stream()
                    .map(item -> new CleanupAdviceRow(item)).toList());
            tabs.getSelectionModel().select(3);
            long deletableCount = suggestions.stream()
                    .filter(item -> item.decision() == CleanupSuggestion.Decision.DELETE).count();
            long deletableBytes = suggestions.stream()
                    .filter(item -> item.decision() == CleanupSuggestion.Decision.DELETE)
                    .mapToLong(CleanupSuggestion::bytes).sum();
            long reviewCount = suggestions.stream()
                    .filter(item -> item.decision() == CleanupSuggestion.Decision.REVIEW).count();
            StorageAnalysisService.AdviceRun run = storageService.lastAdviceRun();
            String remoteStatus = !run.remoteConfigured()
                    ? ""
                    : run.remoteSucceeded()
                    ? String.format("；URL AI 返回 %d 条，已合并显示 API 来源", run.remoteCount())
                    : "；URL AI 调用失败，已回退本地建议：" + run.error();
            statusLabel.setText(suggestions.isEmpty()
                    ? "未发现明确的清理候选；这通常意味着当前目录无需清理。" + remoteStatus
                    : String.format("已生成 %d 条：可删除 %d 项（约 %s），建议检查 %d 项；仅预览，不会自动删除。%s",
                    suggestions.size(), deletableCount, FolderSizeService.formatBytes(deletableBytes), reviewCount,
                    remoteStatus));
        });
    }
    private void autoOrganize(AutoOrganizeSafetyPolicy.Mode mode) {
        if (currentDir == null || rows.isEmpty()) {
            info("请先扫描并生成整理建议。");
            return;
        }
        AutoOrganizeSafetyPolicy.Selection selection = autoSafety.select(
                rows.stream().map(ActionRow::action).toList(), currentDir, mode);
        if (selection.actions().isEmpty()) {
            info(mode == AutoOrganizeSafetyPolicy.Mode.AI
                    ? "没有置信度达到 80% 且通过安全检查的 AI 整理建议。"
                    : "没有已知类型且通过安全检查的规则整理建议。");
            return;
        }

        String summary = String.format(
                "将安全移动 %d 个文件，另有 %d 项因来源、置信度或安全规则被排除。\n"
                        + "目标冲突会自动改名，绝不覆盖；本批操作会记录并可撤销。",
                selection.actions().size(), selection.rejectedCount());
        if (mode == AutoOrganizeSafetyPolicy.Mode.AI) {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("确认 AI 自动整理");
            dialog.setHeaderText("AI 可能判断错误，请核对“整理建议”后再继续");
            dialog.setContentText(summary + "\n\n请输入“确认AI整理”：");
            dialog.initOwner(stage);
            String entered = dialog.showAndWait().orElse("");
            if (!"确认AI整理".equals(entered.trim())) {
                statusLabel.setText("已取消 AI 自动整理：确认短语不匹配。");
                return;
            }
        } else {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, summary,
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setTitle("规则安全自动整理");
            confirm.setHeaderText("执行通过安全检查的规则建议？");
            confirm.initOwner(stage);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        runExecution(selection.actions(), ConflictPolicy.RENAME,
                mode == AutoOrganizeSafetyPolicy.Mode.AI ? "正在执行 AI 安全整理…" : "正在执行规则安全整理…",
                mode == AutoOrganizeSafetyPolicy.Mode.AI ? "AI 安全整理结果" : "规则安全整理结果");
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
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        runExecution(selected, conflictBox.getValue(), "正在整理文件…", "整理结果");
    }

    private void runExecution(List<FileAction> actions, ConflictPolicy policy, String busyMessage, String title) {
        runAsync(busyMessage, () -> service.execute(actions, policy), (OrganizeResult result) -> {
            rows.clear();
            folderRows.clear();
            fileRows.clear();
            cleanupAdviceRows.clear();
            currentOrganizeScan = null;
            currentStorageScan = null;
            folderSizeLabel.setText("文件夹大小：—");
            executeButton.setDisable(true);
            ruleAutoButton.setDisable(true);
            aiAutoButton.setDisable(true);
            suggestButton.setDisable(true);
            cleanupAdviceButton.setDisable(true);
            undoButton.setDisable(!service.canUndo());
            statusLabel.setText(String.format("整理完成：成功 %d，跳过 %d，失败 %d；请重新扫描更新占用。",
                    result.succeeded(), result.skipped(), result.failed()));
            showResult(title, result);
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
                    currentOrganizeScan = null;
                    currentStorageScan = null;
                    rows.clear();
                    folderRows.clear();
                    fileRows.clear();
                    cleanupAdviceRows.clear();
                    folderSizeLabel.setText("文件夹大小：—");
                    suggestButton.setDisable(true);
                    cleanupAdviceButton.setDisable(true);
                    executeButton.setDisable(true);
                    ruleAutoButton.setDisable(true);
                    aiAutoButton.setDisable(true);
                    undoButton.setDisable(!service.canUndo());
                    statusLabel.setText(String.format("撤销完成：成功 %d，跳过 %d，失败 %d；请重新扫描更新占用。",
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
        runTask(busyMsg, task, onSuccess);
    }

    private <T> void runTask(String busyMsg, Task<T> task, Consumer<T> onSuccess) {
        task.setOnSucceeded(e -> {
            finishTask();
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            finishTask();
            Throwable ex = task.getException();
            error(ex == null ? "未知错误" : ex.getMessage());
        });
        progress.progressProperty().bind(task.progressProperty());
        setBusy(true, busyMsg);
        Thread t = new Thread(task, "filenest-task");
        t.setDaemon(true);
        t.start();
    }

    private void finishTask() {
        progress.progressProperty().unbind();
        progress.setProgress(-1);
        setBusy(false, "");
    }

    private void setBusy(boolean busy, String message) {
        Platform.runLater(() -> {
            progress.setVisible(busy);
            scanButton.setDisable(busy || currentDir == null);
            suggestButton.setDisable(busy || currentOrganizeScan == null);
            cleanupAdviceButton.setDisable(busy || currentStorageScan == null);
            executeButton.setDisable(busy || rows.isEmpty());
            ruleAutoButton.setDisable(busy || rows.isEmpty());
            aiAutoButton.setDisable(busy || rows.isEmpty());
            undoButton.setDisable(busy || !service.canUndo());
            selectAllButton.setDisable(busy);
            selectNoneButton.setDisable(busy);
            fetchModelsButton.setDisable(busy);
            applyAiButton.setDisable(busy);
            cleanupButton.setDisable(busy);
            if (busy) {
                statusLabel.setText(message);
            }
        });
    }

    // ---- table cell factories ---------------------------------------------------------

    private TableCell<CleanupAdviceRow, Void> cleanupLocationCell() {
        return new TableCell<>() {
            private final Button button = new Button("打开");
            {
                button.setOnAction(event -> {
                    int index = getIndex();
                    if (index < 0 || index >= getTableView().getItems().size()) return;
                    CleanupAdviceRow row = getTableView().getItems().get(index);
                    try {
                        locationOpener.open(row.getTargetPath());
                    } catch (Exception ex) {
                        error("无法打开位置：" + ex.getMessage());
                    }
                });
            }
            @Override protected void updateItem(Void value, boolean empty) {
                super.updateItem(value, empty);
                setGraphic(empty ? null : button);
            }
        };
    }

    private TableCell<CleanupAdviceRow, String> cleanupPathCell() {
        return new TableCell<>() {
            private final Text text = new Text();
            private final Tooltip tooltip = new Tooltip();
            {
                text.wrappingWidthProperty().bind(widthProperty().subtract(10));
                setGraphic(text);
                setPrefHeight(Region.USE_COMPUTED_SIZE);
                tooltip.setWrapText(true);
                tooltip.setMaxWidth(720);
            }
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                String shown = empty || value == null ? "" : value;
                text.setText(shown);
                if (shown.isEmpty()) {
                    setTooltip(null);
                } else {
                    tooltip.setText(shown);
                    setTooltip(tooltip);
                }
            }
        };
    }

    private TableCell<CleanupAdviceRow, String> cleanupWrappingCell() {
        return new TableCell<>() {
            private final Text text = new Text();
            {
                text.wrappingWidthProperty().bind(widthProperty().subtract(10));
                setGraphic(text);
                setPrefHeight(Region.USE_COMPUTED_SIZE);
            }
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                text.setText(empty || value == null ? "" : value);
            }
        };
    }

    private TableCell<CleanupAdviceRow, String> cleanupDecisionCell() {
        return new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) { setText(null); setStyle(""); return; }
                setText(value);
                String color = "可删除".equals(value) ? "#c62828"
                        : ("建议保留".equals(value) ? "#2e7d32" : "#ef6c00");
                setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
            }
        };
    }
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

    private record MainScan(OrganizeScan organizeScan, StorageScanResult storageScan) { }
    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static String envOr(String name, String fallback) {
        String value = env(name);
        return value.isBlank() ? fallback : value;
    }

}
