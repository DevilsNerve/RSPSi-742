package com.rspsi;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.*;
import java.awt.GraphicsEnvironment;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Loads the real launcher and FXML with the packaged JavaFX dependencies. */
public class DesktopLauncherTest {
    @Test public void modernJavaLaunchesTheExistingEditorAndOffersTheWorldWorkspace() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("xyren.ui.test") && !GraphicsEnvironment.isHeadless());
        Path temporary=Files.createTempDirectory("rspsi-launcher-test-");
        String previousHome=System.getProperty("user.home"), previousPlugins=System.getProperty("rspsi.plugins");
        try {
            System.setProperty("user.home",temporary.toString());
            System.setProperty("rspsi.plugins",temporary.resolve("plugins").toString());
            CompletableFuture<Void> result=new CompletableFuture<>();
            Platform.startup(() -> {
                Stage stage=new Stage();
                try {
                    new LauncherWindow().start(stage);
                    assertTrue(stage.isShowing());
                    Button button=(Button)stage.getScene().lookup("#worldWorkspaceButton");
                    assertNotNull(button);assertNotNull(button.getOnAction());
                    assertEquals("Xyren / Emps / Near Reality",button.getText());
                    result.complete(null);
                } catch(Throwable error) {result.completeExceptionally(error);}
                finally {stage.close();}
            });
            result.get(30,TimeUnit.SECONDS);
        } finally {
            Platform.exit();System.setProperty("user.home",previousHome);
            if(previousPlugins==null)System.clearProperty("rspsi.plugins");else System.setProperty("rspsi.plugins",previousPlugins);
            try(java.util.stream.Stream<Path> paths=Files.walk(temporary)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
}
