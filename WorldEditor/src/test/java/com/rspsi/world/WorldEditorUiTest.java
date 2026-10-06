package com.rspsi.world;

import com.google.gson.*;
import org.junit.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;
import static org.junit.Assert.*;

/** Real desktop controls and backend; enabled explicitly on an X display. */
public class WorldEditorUiTest {
    private WorldEditor editor;
    private Path workspace;
    private JFrame frame;
    private String previousFiles, previousBackend;
    private static Object field(Object owner,String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+90_000_000_000L;
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>deadline)throw new AssertionError("Desktop condition did not complete");
            Thread.sleep(100);
        }
    }
    private boolean ready() {
        try { return field(editor,"state")!=null && !(Boolean)field(editor,"busy"); }
        catch(Exception error) { throw new RuntimeException(error); }
    }
    private void idle() throws Exception {
        edt(() -> {});
        waitFor(this::ready);
        edt(() -> {});
    }
    private static <T extends Component> T component(Container parent,Class<T> type,String text) {
        for(Component child:parent.getComponents()) {
            if(type.isInstance(child) && (text==null || child instanceof AbstractButton && ((AbstractButton)child).getText().equals(text))) return type.cast(child);
            if(child instanceof Container) { T found=component((Container)child,type,text);if(found!=null)return found; }
        }
        return null;
    }
    private void click(String text) throws Exception {
        JButton button=component(frame,JButton.class,text);assertNotNull(text,button);
        assertTrue(text+" is enabled",button.isEnabled());
        SwingUtilities.invokeLater(button::doClick);
        // A modal chooser runs a nested event loop, so this barrier works for
        // both ordinary actions and actions that open a dialog.
        edt(() -> {});
    }
    private static JDialog dialog() {
        for(Window window:Window.getWindows())if(window instanceof JDialog && window.isShowing())return (JDialog)window;
        return null;
    }
    private void newProject(String name) throws Exception {
        click("New");waitFor(() -> dialog()!=null);
        JDialog dialog=dialog();
        edt(() -> component(dialog,JTextField.class,null).setText(name));
        SwingUtilities.invokeLater(() -> component(dialog,JButton.class,"OK").doClick());
        waitFor(() -> dialog()==null);idle();
        waitFor(() -> { try { return field(editor,"project")!=null; } catch(Exception e) {return false;} });
        idle();
    }
    private JsonObject project() throws Exception { return (JsonObject)field(editor,"project"); }

    @Test public void selectsCopiesPastesUndoesSavesAndImportsThroughTheDesktop() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("xyren.ui.test") && !GraphicsEnvironment.isHeadless());
        workspace=Files.createTempDirectory("rspsi-ui-test-");
        Preferences preferences=Preferences.userNodeForPackage(WorldEditor.class);
        previousFiles=preferences.get("files",null);
        previousBackend=System.getProperty("xyren.backend.command");
        String root=System.getProperty("xyren.editor.home");
        JsonArray command=new JsonArray();command.add("python3");command.add("-u");command.add(root+"/tools/xyren_bridge.py");
        command.add("--workspace");command.add(workspace.toString());
        System.setProperty("xyren.backend.command",command.toString());
        edt(() -> {
            try { editor=new WorldEditor();frame=(JFrame)field(editor,"frame");
                java.lang.reflect.Method show=WorldEditor.class.getDeclaredMethod("show");show.setAccessible(true);show.invoke(editor);
            } catch(Exception error) {throw new RuntimeException(error);}
        });
        idle();newProject("Desktop round trip");
        Object donor=field(editor,"donor"), destination=field(editor,"destination");
        JComboBox<?> sources=(JComboBox<?>)field(donor,"source");
        edt(() -> sources.setSelectedIndex(0)); // The retained Emps cache.
        JPanel header=(JPanel)field(donor,"header");
        SwingUtilities.invokeLater(() -> component(header,JButton.class,"Load").doClick());
        edt(() -> {});
        idle();
        MapCanvas sourceMap=(MapCanvas)field(donor,"canvas"), targetMap=(MapCanvas)field(destination,"canvas");
        waitFor(() -> sourceMap.tile(3212,3215)!=null);
        Robot robot=new Robot();robot.setAutoDelay(60);
        Point origin=sourceMap.getLocationOnScreen(), a=sourceMap.screen(3212,3215), b=sourceMap.screen(3213,3216);
        robot.mouseMove(origin.x+a.x+3,origin.y+a.y+3);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseMove(origin.x+b.x+3,origin.y+b.y+3);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);robot.waitForIdle();
        assertEquals(new Rectangle(3212,3215,2,2),sourceMap.selection);
        click("Copy");idle();waitFor(() -> {try{return !project().get("clipboard").isJsonNull();}catch(Exception e){return false;}});
        edt(() -> {try {((JSpinner)field(editor,"pasteX")).setValue(12223);((JSpinner)field(editor,"pasteY")).setValue(12223);}catch(Exception e){throw new RuntimeException(e);}});
        click("Paste");idle();waitFor(() -> targetMap.tile(12223,12223)!=null);
        assertEquals("reference",targetMap.tile(12223,12223).get(5).getAsString());
        click("Undo");idle();waitFor(() -> targetMap.tile(12223,12223)==null);
        click("Redo");idle();waitFor(() -> targetMap.tile(12223,12223)!=null);
        Path save=workspace.resolve("desktop.xyren-map.json");
        click("Save project");waitFor(() -> dialog()!=null);
        JDialog chooserDialog=dialog();JFileChooser chooser=component(chooserDialog,JFileChooser.class,null);
        edt(() -> {chooser.setSelectedFile(save.toFile());chooser.approveSelection();});
        waitFor(() -> Files.isRegularFile(save));idle();
        String first=project().get("id").getAsString();
        click("Import project");waitFor(() -> dialog()!=null);
        JDialog importDialog=dialog();JFileChooser importChooser=component(importDialog,JFileChooser.class,null);
        edt(() -> {importChooser.setSelectedFile(save.toFile());importChooser.approveSelection();});
        idle();waitFor(() -> {try{return !project().get("id").getAsString().equals(first);}catch(Exception e){return false;}});
        idle();
        assertEquals("reference",targetMap.tile(12223,12223).get(5).getAsString());
        Path screenshot=Paths.get(root,"docs/desktop-round-trip.png");Files.createDirectories(screenshot.getParent());
        ImageIO.write(robot.createScreenCapture(frame.getBounds()),"png",screenshot.toFile());
    }

    @After public void cleanup() throws Exception {
        if(workspace==null)return;
        if(previousBackend==null)System.clearProperty("xyren.backend.command");
        else System.setProperty("xyren.backend.command",previousBackend);
        Preferences preferences=Preferences.userNodeForPackage(WorldEditor.class);
        if(previousFiles==null)preferences.remove("files");else preferences.put("files",previousFiles);
        if(editor!=null) {
            BridgeClient bridge=(BridgeClient)field(editor,"bridge");if(bridge!=null)bridge.close();
            ((java.util.concurrent.ExecutorService)field(editor,"worker")).shutdownNow();
            edt(() -> {for(Window window:Window.getWindows())window.dispose();});
        }
        if(workspace!=null) {
            try(java.util.stream.Stream<Path> paths=Files.walk(workspace)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {try{Files.delete(path);}catch(IOException e){throw new java.io.UncheckedIOException(e);}});
            }
        }
    }
}
