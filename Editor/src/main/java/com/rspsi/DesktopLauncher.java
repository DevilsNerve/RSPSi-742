package com.rspsi;

/** Classpath entry point for the explicitly packaged JavaFX runtime. */
public final class DesktopLauncher {
    public static void main(String[] args) {
        java.nio.file.Path home=com.rspsi.world.BridgeClient.home();
        java.nio.file.Path plugins=home.resolve("Editor/plugins");
        if(!java.nio.file.Files.isDirectory(plugins))plugins=home.resolve("plugins");
        System.setProperty("rspsi.plugins",plugins.toString());
        javafx.application.Application.launch(LauncherWindow.class, args);
    }
}
