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
                    verifyIndependentThreeDimensionalViews();
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

    private void verifyIndependentThreeDimensionalViews() throws Exception {
        javafx.stage.Stage window=new javafx.stage.Stage();
        com.rspsi.source.SourceEditor editor=new com.rspsi.source.SourceEditor(window);
        try {
            assertTrue(editor.root().getCenter() instanceof javafx.scene.control.SplitPane);
            com.rspsi.source.SourceViewport destination=editor.destinationViewport(),donor=editor.donorViewport();
            destination.show(fixture("xyren",0x398f43),0,3232,3232,true);
            donor.show(fixture("emps",0x6371b6),0,3232,3232,true);
            window.show();editor.root().applyCss();editor.root().layout();
            assertNotSame(destination.subScene().getCamera(),donor.subScene().getCamera());
            assertTrue(destination.subScene().isDepthBuffer());assertTrue(donor.subScene().isDepthBuffer());
            destination.showScenery(modelPacket(0xffc26b24));donor.showScenery(modelPacket(0xff58bede));
            assertEquals(1,destination.sceneryCount());assertEquals(1,donor.sceneryCount());
            javafx.scene.image.WritableImage rendered=window.getScene().snapshot(null);
            java.nio.file.Path output=java.nio.file.Paths.get("build/test-preview/rspsi-two-views.png");
            java.nio.file.Files.createDirectories(output.getParent());
            javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(rendered,null),"png",output.toFile());
            int changed=0;
            for(int y=180;y<rendered.getHeight()-70;y+=8)for(int x=40;x<rendered.getWidth()-40;x+=8)
                if((rendered.getPixelReader().getArgb(x,y)&0xffffff)!=0x17202c)changed++;
            assertTrue("3D terrain and scenery must draw visible pixels",changed>100);
            assertEquals("xyren",destination.data().getAsJsonObject("palettes").keySet().iterator().next());
            assertEquals("emps",donor.data().getAsJsonObject("palettes").keySet().iterator().next());
        } finally {window.close();editor.close();}
    }

    private com.google.gson.JsonObject fixture(String source,int rgb) {
        com.google.gson.JsonObject palette=com.rspsi.world.BridgeClient.object("underlays",new com.google.gson.JsonArray(),"overlays",new com.google.gson.JsonArray());
        com.google.gson.JsonArray color=new com.google.gson.JsonArray();color.add(rgb>>16&255);color.add(rgb>>8&255);color.add(rgb&255);
        palette.getAsJsonArray("underlays").add(com.rspsi.world.BridgeClient.object("id",1,"color",color,"rgb",rgb));
        palette.getAsJsonArray("overlays").add(com.rspsi.world.BridgeClient.object("id",1,"color",color,"rgb",rgb));
        com.google.gson.JsonArray cells=new com.google.gson.JsonArray();
        for(int p=0;p<4;p++)for(int x=0;x<64;x++)for(int y=0;y<64;y++) {
            com.google.gson.JsonArray cell=new com.google.gson.JsonArray();
            cell.add("edited");cell.add(1);cell.add((x+y)%3==0?1:0);cell.add((x*64+y)%96);cell.add(0);cell.add(source);cell.add(source);cell.add(p*240+x*4);
            cells.add(cell);
        }
        return com.rspsi.world.BridgeClient.object("regions",com.rspsi.world.BridgeClient.object("12850",com.rspsi.world.BridgeClient.object("tiles",cells,"objects",new com.google.gson.JsonArray())),"palettes",com.rspsi.world.BridgeClient.object(source,palette));
    }

    private com.google.gson.JsonObject modelPacket(int argb) {
        return com.google.gson.JsonParser.parseString("{\"schema\":\"rspsi-source-preview-1\",\"textures\":{\"7\":{\"width\":1,\"height\":1,\"pixels\":["+argb+"]}},\"meshes\":[{\"plane\":0,\"tile_x\":3232,\"tile_y\":3232,\"x\":413696,\"y\":413696,\"height\":-128,\"vertices\":[[-120,0,0],[120,0,0],[120,-400,0],[-120,-400,0]],\"faces\":[[0,1,2,7,16777215,0,0,0,1,0,1,1],[0,2,3,7,16777215,0,0,0,1,1,0,1]]}],\"failures\":[]}").getAsJsonObject();
    }
}
