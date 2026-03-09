package com.weakest.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {
    @Override
    public void start(Stage stage) {
        MainController controller = new MainController();
        Scene scene = new Scene(controller.getRoot(), 370, 800);
        stage.setTitle("WEAKEST Execution Visualiser");
        stage.setScene(scene);
        stage.setX(0);
        stage.setY(0);
        stage.setWidth(370);
        stage.setHeight(830);
        stage.setResizable(false);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}