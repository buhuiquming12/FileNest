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

        Scene scene = new Scene(view.getRoot(), 1180, 720);
        stage.setTitle("FileNest · 文件夹占用与 AI 清理建议");
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(600);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
