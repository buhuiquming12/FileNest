package com.filenest;

import com.filenest.app.OrganizeService;
import com.filenest.ui.MainView;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX entry point. Wires the default {@link OrganizeService} and shows the main window.
 *
 * <p>Run with: {@code mvn javafx:run}
 */
public class App extends Application {

    @Override
    public void start(Stage stage) {
        OrganizeService service = OrganizeService.createDefault();
        MainView view = new MainView(service, stage);

        Scene scene = new Scene(view.getRoot(), 1000, 660);
        stage.setTitle("FileNest · 桌面文件整理工具（AI 建议）");
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(520);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
