package com.rspsi.world;

import com.google.gson.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import static com.rspsi.world.BridgeClient.object;

/** Standalone source-aware authoring mode, also opened by the RSPSi launcher. */
public final class WorldEditor {
    private final JFrame frame = new JFrame("Xyren Map Editor — RSPSi");
    private final Preferences preferences = Preferences.userNodeForPackage(WorldEditor.class);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "map-editor-requests"); t.setDaemon(true); return t;
    });
    private BridgeClient bridge;
    private boolean busy;
    private JsonObject state, project;
    private final JLabel status = new JLabel("Connect to open Xyren, Emps World and Near Reality maps");
    private final JLabel coordinate = new JLabel(" ");
    private final JPanel controls = new JPanel();
    private final JComboBox<Source> base = new JComboBox<>();
    private final JComboBox<Project> projects = new JComboBox<>();
    private final Pane donor = new Pane(false), destination = new Pane(true);
    private Pane active = donor;
    private final JCheckBox allPlanes = new JCheckBox("All four planes", true);
    private final JCheckBox terrain = new JCheckBox("Terrain", true), heights = new JCheckBox("Heights", true);
    private final JCheckBox flags = new JCheckBox("Flags", true), objects = new JCheckBox("Objects", true);
    private final JComboBox<String> turns = new JComboBox<>(new String[]{"0°", "90°", "180°", "270°"});
    private final JCheckBox mirrorX = new JCheckBox("Mirror X"), mirrorY = new JCheckBox("Mirror Y");
    private final JComboBox<String> pasteMode = new JComboBox<>(new String[]{"Replace objects", "Merge objects"});
    private final JComboBox<String> heightMode = new JComboBox<>(new String[]{"Keep source heights", "Match destination height"});
    private final JSpinner pasteX = number(3220,0,16383,1), pasteY = number(3220,0,16383,1);
    private final JSpinner heightOffset = number(0,-65536,65536,8);
    private final JButton undo = new JButton("Undo"), redo = new JButton("Redo"), paste = new JButton("Paste");
    private final DefaultTableModel objectRows = new DefaultTableModel(new String[]{"Object", "Source", "ID", "X", "Y", "Type", "Facing"},0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable objectTable = new JTable(objectRows);
    private List<JsonObject> visibleObjects = new ArrayList<>();

    private static final class Source {
        final String key, label;
        final JsonObject data;
        Source(JsonObject data) { this.data = data; key = data.get("key").getAsString(); label = data.get("label").getAsString(); }
        @Override public String toString() { return label + (data.has("error") ? " (unavailable)" : ""); }
        boolean hasRegion(int region) {
            if (!data.has("regions")) return false;
            for (JsonElement row : data.getAsJsonArray("regions")) if (row.getAsInt() == region) return true;
            return false;
        }
    }
    private static final class Project {
        final String id, name;
        Project(JsonObject data) { id = data.get("id").getAsString(); name = data.get("name").getAsString(); }
        @Override public String toString() { return name; }
    }

    public static void main(String[] args) { open(); }
    public static void open() { SwingUtilities.invokeLater(() -> new WorldEditor().show()); }

    private void show() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (busy) { JOptionPane.showMessageDialog(frame,"Wait for the current operation to finish before closing."); return; }
                if (bridge != null) bridge.close(); worker.shutdown(); frame.dispose();
            }
        });
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        JPanel projectBar = row();
        button(projectBar,"Connection",this::connection);
        projectBar.add(new JLabel("Destination base:")); projectBar.add(base);
        button(projectBar,"Use base",() -> {
            Source selected = (Source) base.getSelectedItem();
            if (selected != null) {
                JsonObject body = object("source",selected.key);
                if (selected.data.get("kind").getAsString().equals("js5")) {
                    int region = ((value(donor.x) >> 6) << 8) | (value(donor.y) >> 6);
                    if (JOptionPane.showConfirmDialog(frame,"Open region "+region+" of "+selected.label+" as a destination?\nThe editor converts this area and its assets once, then edits it in Xyren format.",
                            "Open map destination",JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                    JsonArray regions = new JsonArray(); regions.add(region); body.add("regions",regions);
                }
                submit("Opening destination base", () -> rpc("base",body), this::setState);
            }
        });
        projects.setPreferredSize(new Dimension(170,26)); projectBar.add(projects);
        button(projectBar,"Open",() -> {
            Project p = (Project) projects.getSelectedItem();
            if (p != null) submit("Opening project",() -> rpc("open",object("project",p.id)), result -> { setProject(result); destination.load(); });
        });
        button(projectBar,"New",this::newProject);
        button(projectBar,"Import project",this::importProject);
        button(projectBar,"Save project",this::saveProject);
        button(projectBar,"Export cache",this::exportCache);
        controls.add(projectBar);
        JPanel copyBar = row();
        copyBar.add(new JLabel("Copy selected area:"));
        for (JCheckBox box : new JCheckBox[]{terrain,heights,flags,objects,allPlanes}) copyBar.add(box);
        button(copyBar,"Copy",() -> copy(false)); button(copyBar,"Cut",() -> copy(true));
        button(copyBar,"Delete",this::deleteSelection);
        copyBar.add(undo); copyBar.add(redo);
        undo.addActionListener(e -> edit(object("kind","undo")));
        redo.addActionListener(e -> edit(object("kind","redo")));
        controls.add(copyBar);
        JPanel pasteBar = row();
        pasteBar.add(new JLabel("Paste at X:")); pasteBar.add(pasteX); pasteBar.add(new JLabel("Y:")); pasteBar.add(pasteY);
        pasteBar.add(turns); pasteBar.add(mirrorX); pasteBar.add(mirrorY); pasteBar.add(pasteMode);
        pasteBar.add(heightMode); pasteBar.add(new JLabel("Height offset:")); pasteBar.add(heightOffset); pasteBar.add(paste);
        paste.addActionListener(e -> pasteSelection());
        turns.addActionListener(e -> updateGhost());
        controls.add(pasteBar);
        JPanel editBar = row();
        button(editBar,"Fill terrain / water",this::fillTerrain);
        button(editBar,"Raise +8",() -> fill(object("delta",8)));
        button(editBar,"Lower −8",() -> fill(object("delta",-8)));
        button(editBar,"Smooth",() -> fill(object("smooth",true)));
        button(editBar,"Add object",this::objectCatalog);
        button(editBar,"Edit object",() -> objectAction("object_update"));
        button(editBar,"Duplicate object",() -> objectAction("object_duplicate"));
        button(editBar,"Remove object",() -> objectAction("object_delete"));
        button(editBar,"New region",this::createRegion);
        button(editBar,"Update base",this::updateBase);
        editBar.add(new JLabel("Edits save automatically. Select a pane to copy; right-click the destination to place."));
        controls.add(editBar);
        JSplitPane maps = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,donor.panel,destination.panel);
        maps.setResizeWeight(0.5); maps.setDividerLocation(0.5);
        objectTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane objectScroll = new JScrollPane(objectTable); objectScroll.setPreferredSize(new Dimension(900,135));
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT,maps,objectScroll);
        center.setResizeWeight(1); center.setDividerLocation(570);
        JPanel footer = new JPanel(new BorderLayout(12,0)); footer.setBorder(new EmptyBorder(5,10,5,10));
        footer.add(status,BorderLayout.CENTER); footer.add(coordinate,BorderLayout.EAST);
        frame.add(controls,BorderLayout.NORTH); frame.add(center,BorderLayout.CENTER); frame.add(footer,BorderLayout.SOUTH);
        frame.setSize(1480,870); frame.setMinimumSize(new Dimension(1100,650)); frame.setLocationRelativeTo(null);
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { controls.revalidate(); donor.header.revalidate(); destination.header.revalidate(); }
        });
        frame.setVisible(true); shortcuts(); connect();
    }

    /** FlowLayout already wraps children; account for those rows in its height. */
    static final class WrapLayout extends FlowLayout {
        WrapLayout() { super(FlowLayout.LEFT,7,4); }
        @Override public Dimension preferredLayoutSize(Container parent) {
            synchronized(parent.getTreeLock()) {
                Insets insets=parent.getInsets(); int available=parent.getWidth()-insets.left-insets.right-getHgap()*2;
                if(available<=0)return super.preferredLayoutSize(parent);
                int width=0,height=0,rowWidth=0,rowHeight=0;
                for(Component child:parent.getComponents()) if(child.isVisible()) {
                    Dimension size=child.getPreferredSize(); int next=rowWidth==0 ? size.width : rowWidth+getHgap()+size.width;
                    if(next>available && rowWidth>0) {
                        width=Math.max(width,rowWidth);height+=rowHeight+getVgap();rowWidth=0;rowHeight=0;
                    }
                    rowWidth+=(rowWidth==0?0:getHgap())+size.width;rowHeight=Math.max(rowHeight,size.height);
                }
                return new Dimension(Math.max(width,rowWidth)+insets.left+insets.right+getHgap()*2,
                        height+rowHeight+insets.top+insets.bottom+getVgap()*2);
            }
        }
    }
    private static JPanel row() { return new JPanel(new WrapLayout()); }
    private static JButton button(JPanel parent,String name,Runnable action) {
        JButton button = new JButton(name); parent.add(button); button.addActionListener(e -> action.run()); return button;
    }
    private static JSpinner number(int value,int low,int high,int step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value,low,high,step));
        ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(5); return spinner;
    }
    private static int value(JSpinner spinner) {
        try { spinner.commitEdit(); } catch (java.text.ParseException error) { throw new IllegalArgumentException("Enter a valid number"); }
        return ((Number)spinner.getValue()).intValue();
    }
    private JsonObject rpc(String action,JsonObject body) throws IOException {
        if (bridge == null) throw new IOException("Connect to a map backend first");
        return bridge.call(action,body,message -> SwingUtilities.invokeLater(() -> status.setText(message)));
    }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> void submit(String message,Work<T> work,Consumer<T> success) {
        if (busy) return;
        setBusy(true); status.setText(message);
        worker.submit(() -> {
            try {
                T result = work.run();
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    try { success.accept(result); } catch (RuntimeException error) { showError(error); }
                });
            } catch (Exception error) { SwingUtilities.invokeLater(() -> { setBusy(false); showError(error); }); }
        });
    }
    private void showError(Exception error) {
        status.setText("Operation stopped: "+error.getMessage());
        JTextArea text = new JTextArea(error.getMessage(),8,65); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        JOptionPane.showMessageDialog(frame,new JScrollPane(text),"Map editor",JOptionPane.ERROR_MESSAGE);
    }
    private void setBusy(boolean value) {
        busy = value; enable(controls,!value); enable(donor.header,!value); enable(destination.header,!value);
        if (!value) updateHistory();
        frame.setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }
    private void enable(Component component,boolean value) {
        component.setEnabled(value);
        if (component instanceof Container) for (Component child : ((Container)component).getComponents()) enable(child,value);
    }
    private void updateHistory() {
        undo.setEnabled(project != null && project.get("can_undo").getAsBoolean());
        redo.setEnabled(project != null && project.get("can_redo").getAsBoolean());
        paste.setEnabled(project != null && project.has("clipboard") && !project.get("clipboard").isJsonNull());
    }

    private void connect() {
        String command = System.getProperty("xyren.backend.command",preferences.get("backend",BridgeClient.defaultCommand()));
        submit("Connecting to map sources",() -> {
            if (bridge != null) bridge.close();
            bridge = new BridgeClient(BridgeClient.command(command));
            return rpc("hello",new JsonObject());
        }, result -> { setState(result); status.setText("Connected. Open or create a destination project."); });
    }
    private void connection() {
        JTextArea command = new JTextArea(preferences.get("backend",BridgeClient.defaultCommand()),5,85);
        command.setLineWrap(true); command.setWrapStyleWord(true);
        JPanel box = new JPanel(new BorderLayout(0,8));
        box.add(new JLabel("Backend command as JSON arguments (local Python or ssh -T). See docs/xyren-workspace.md."),BorderLayout.NORTH);
        box.add(new JScrollPane(command),BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(frame,box,"Map connection",JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try { BridgeClient.command(command.getText()); preferences.put("backend",command.getText()); connect(); }
        catch (RuntimeException error) { showError(error); }
    }
    private void setState(JsonObject result) {
        state = result; project = null; base.removeAllItems(); donor.source.removeAllItems();
        String baseKey = result.get("base").getAsString();
        for (JsonElement row : result.getAsJsonArray("sources")) {
            Source s = new Source(row.getAsJsonObject()); donor.source.addItem(s);
            if (s.data.get("base").getAsBoolean()) { base.addItem(s); if (s.key.equals(baseKey)) base.setSelectedItem(s); }
        }
        projects.removeAllItems();
        for (JsonElement row : result.getAsJsonArray("projects")) projects.addItem(new Project(row.getAsJsonObject()));
        destination.canvas.load(object("regions",new JsonObject())); destination.regionLabel.setText("Create or open a project");
        updateHistory();
    }
    private void setProject(JsonObject result) {
        if (result.has("clipboard")) project = result;
        else {
            JsonObject next = result.deepCopy();
            if (project != null && project.has("clipboard")) next.add("clipboard",project.get("clipboard"));
            project = next;
        }
        frame.setTitle("Xyren Map Editor — "+project.get("name").getAsString());
        boolean found = false;
        for (int i=0; i<projects.getItemCount(); i++) if (projects.getItemAt(i).id.equals(project.get("id").getAsString())) {
            projects.setSelectedIndex(i); found = true; break;
        }
        if (!found) { Project p = new Project(project); projects.addItem(p); projects.setSelectedItem(p); }
        updateHistory(); updateGhost();
    }
    private void newProject() {
        String name = JOptionPane.showInputDialog(frame,"Project name:","New map");
        if (name != null) submit("Creating map project",() -> rpc("create",object("name",name)), result -> {
            setProject(result); destination.load();
        });
    }
    private void requireProject() { if (project == null) throw new IllegalArgumentException("Create or open a destination project first"); }
    private String revision() { requireProject(); return project.get("revision").getAsString(); }
    private JsonObject rectangle(Rectangle rect) {
        if (rect == null) throw new IllegalArgumentException("Drag an area on the map first");
        return object("x0",rect.x,"y0",rect.y,"x1",rect.x+rect.width-1,"y1",rect.y+rect.height-1);
    }
    private JsonArray planes(int current) {
        JsonArray result = new JsonArray();
        if (allPlanes.isSelected()) for (int i=0;i<4;i++) result.add(i); else result.add(current);
        return result;
    }
    private JsonObject layers() { return object("terrain",terrain.isSelected(),"heights",heights.isSelected(),"flags",flags.isSelected(),"objects",objects.isSelected()); }
    private void edit(JsonObject operation) {
        try {
            String expected = revision();
            submit("Saving map edit",() -> {
                rpc("mutate",object("revision",expected,"operation",operation));
                return rpc("summary",new JsonObject());
            }, result -> {
                setProject(result);
                if (operation.get("kind").getAsString().equals("paste")) {
                    destination.x.setValue(operation.get("x").getAsInt()); destination.y.setValue(operation.get("y").getAsInt());
                } else if (operation.get("kind").getAsString().equals("create_region")) {
                    int r = operation.get("region").getAsInt(); destination.x.setValue((r >> 8)*64+32); destination.y.setValue((r & 255)*64+32);
                }
                destination.load();
            });
        } catch (RuntimeException error) { showError(error); }
    }
    private void copy(boolean cut) {
        try {
            requireProject();
            if (cut && active != destination) throw new IllegalArgumentException("Cut is available in the destination project");
            JsonObject operation = object("kind",cut ? "cut" : "copy","source",active.key(),
                    "rect",rectangle(active.canvas.selection),"planes",planes(active.canvas.plane),"layers",layers());
            edit(operation);
        } catch (RuntimeException error) { showError(error); }
    }
    private void pasteSelection() {
        try {
            JsonObject operation = object("kind","paste","x",value(pasteX),"y",value(pasteY),"plane",destination.canvas.plane,
                    "turns",turns.getSelectedIndex(),"mirror_x",mirrorX.isSelected(),"mirror_y",mirrorY.isSelected(),
                    "mode",pasteMode.getSelectedIndex() == 0 ? "replace" : "merge",
                    "height_mode",heightMode.getSelectedIndex() == 0 ? "absolute" : "relative",
                    "height_offset",value(heightOffset),"create_regions",true);
            edit(operation);
        } catch (RuntimeException error) { showError(error); }
    }
    private void updateGhost() {
        if (project == null || !project.has("clipboard") || project.get("clipboard").isJsonNull()) { destination.canvas.clearPreview(); return; }
        JsonObject clip = project.getAsJsonObject("clipboard");
        int width = clip.get("width").getAsInt(), height = clip.get("height").getAsInt();
        destination.canvas.preview((turns.getSelectedIndex() & 1) == 0 ? width : height,
                (turns.getSelectedIndex() & 1) == 0 ? height : width);
    }
    private void deleteSelection() {
        try { edit(object("kind","delete","rect",rectangle(destination.canvas.selection),"planes",planes(destination.canvas.plane),"layers",layers())); }
        catch (RuntimeException error) { showError(error); }
    }
    private void fill(JsonObject values) {
        try { edit(object("kind","fill","rect",rectangle(destination.canvas.selection),"planes",planes(destination.canvas.plane),"values",values)); }
        catch (RuntimeException error) { showError(error); }
    }
    private void fillTerrain() {
        try {
            requireProject(); rectangle(destination.canvas.selection);
            JComboBox<Source> source = new JComboBox<>();
            for (int i=0;i<donor.source.getItemCount();i++) source.addItem(donor.source.getItemAt(i));
            JTextField underlay = new JTextField("",6), overlay = new JTextField("",6), shape = new JTextField("",6);
            JTextField elevation = new JTextField("",6), tileFlags = new JTextField("",6);
            JTextArea palette = new JTextArea(9,52); palette.setEditable(false);
            JButton browse = new JButton("Show floor IDs");
            browse.addActionListener(e -> {
                Source s = (Source)source.getSelectedItem(); if (s == null) return;
                submit("Reading floor palette",() -> rpc("palette",object("source",s.key)), result -> {
                    StringBuilder text = new StringBuilder();
                    for (String kind : new String[]{"underlays","overlays"}) {
                        text.append(kind).append(":\n");
                        for (JsonElement row : result.getAsJsonArray(kind)) {
                            JsonObject p = row.getAsJsonObject(); text.append(p.get("id").getAsInt());
                            if (p.get("liquid").getAsBoolean()) text.append(" ").append(p.get("liquid_kinds"));
                            text.append("   ");
                        }
                        text.append("\n");
                    }
                    palette.setText(text.toString());
                });
            });
            JPanel fields = new JPanel(new GridLayout(0,2,8,6));
            fields.add(new JLabel("Floor source:")); fields.add(source);
            fields.add(new JLabel("Underlay ID (blank keeps existing):")); fields.add(underlay);
            fields.add(new JLabel("Overlay / water ID (0 clears):")); fields.add(overlay);
            fields.add(new JLabel("Overlay shape 0–95:")); fields.add(shape);
            fields.add(new JLabel("Height (multiple of 8):")); fields.add(elevation);
            fields.add(new JLabel("Tile flags 0–32:")); fields.add(tileFlags);
            fields.add(browse);
            JPanel box = new JPanel(new BorderLayout(0,10)); box.add(fields,BorderLayout.NORTH); box.add(new JScrollPane(palette),BorderLayout.CENTER);
            if (JOptionPane.showConfirmDialog(frame,box,"Fill selected terrain on the selected planes",JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            Source s = (Source)source.getSelectedItem(); JsonObject values = new JsonObject();
            for (String field : new String[]{"underlay","overlay"}) {
                JTextField input = field.equals("underlay") ? underlay : overlay;
                if (!input.getText().trim().isEmpty()) values.add(field,object("source",s.key,"id",Integer.parseInt(input.getText().trim())));
            }
            if (!shape.getText().trim().isEmpty()) values.addProperty("shape",Integer.parseInt(shape.getText().trim()));
            if (!elevation.getText().trim().isEmpty()) values.addProperty("height",Integer.parseInt(elevation.getText().trim()));
            if (!tileFlags.getText().trim().isEmpty()) values.addProperty("flags",Integer.parseInt(tileFlags.getText().trim()));
            fill(values);
        } catch (RuntimeException error) { showError(error); }
    }
    private void createRegion() {
        try {
            int x = value(pasteX), y = value(pasteY);
            edit(object("kind","create_region","region",((x >> 6) << 8) | (y >> 6)));
        } catch (RuntimeException error) { showError(error); }
    }

    private void updateBase() {
        Project selected=(Project)projects.getSelectedItem();
        if(selected==null) { showError(new IllegalArgumentException("Select a project first"));return; }
        submit("Reviewing changes to the destination base",() -> rpc("rebase",object("project",selected.id)), preview -> {
            if(!preview.get("changed").getAsBoolean()) { status.setText("This project already uses the current destination base.");return; }
            JsonObject resolutions=new JsonObject();
            DefaultTableModel model=new DefaultTableModel(new String[]{"Conflict","Previous base","Draft","Current base","Keep"},0) {
                @Override public boolean isCellEditable(int row,int column) {return column==4;}
            };
            for(JsonElement row:preview.getAsJsonArray("conflicts")) {
                JsonObject conflict=row.getAsJsonObject();
                model.addRow(new Object[]{conflict.get("id").getAsString(),conflict.get("baseline").toString(),
                        conflict.get("draft").toString(),conflict.get("world").toString(),"Choose…"});
            }
            JTable table=new JTable(model);table.setRowHeight(25);
            table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"Choose…","Draft","Current base"})));
            JPanel review=new JPanel(new BorderLayout(0,10));
            review.add(new JLabel("Review each conflict. Applying the update retains a previous checkpoint and resets undo history."),BorderLayout.NORTH);
            if(model.getRowCount()>0) {
                JScrollPane scroll=new JScrollPane(table);scroll.setPreferredSize(new Dimension(1050,350));review.add(scroll,BorderLayout.CENTER);
            }
            if(JOptionPane.showConfirmDialog(frame,review,"Update destination base",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            if(table.isEditing())table.getCellEditor().stopCellEditing();
            for(int row=0;row<model.getRowCount();row++) {
                String choice=model.getValueAt(row,4).toString();
                if(choice.equals("Choose…")) {showError(new IllegalArgumentException("Choose a value for every conflict, then review the update again"));return;}
                resolutions.addProperty(model.getValueAt(row,0).toString(),choice.equals("Draft")?"draft":"world");
            }
            submit("Updating the project base",() -> {
                rpc("rebase",object("project",selected.id,"revision",preview.get("revision"),"apply",true,
                        "target_identity",preview.get("target_identity"),"resolutions",resolutions));
                return rpc("hello",new JsonObject());
            }, result -> {
                setState(result);
                submit("Reopening the updated project",() -> rpc("open",object("project",selected.id)), opened -> {setProject(opened);destination.load();});
            });
        });
    }

    private void refreshObjects() {
        visibleObjects = destination.canvas.objects(); objectRows.setRowCount(0);
        for (JsonObject row : visibleObjects) objectRows.addRow(new Object[]{row.get("name").getAsString(),row.get("source").getAsString(),
                row.get("id").getAsInt(),row.get("x").getAsInt(),row.get("y").getAsInt(),row.get("type").getAsInt(),row.get("rotation").getAsInt()});
    }
    private void objectAction(String kind) {
        try {
            requireProject(); int index = objectTable.getSelectedRow();
            if (index < 0) throw new IllegalArgumentException("Select a destination object in the table");
            JsonObject row = visibleObjects.get(index);
            if (kind.equals("object_delete")) { edit(object("kind",kind,"key",row.get("key"))); return; }
            JSpinner x = number(row.get("x").getAsInt(),0,16383,1), y = number(row.get("y").getAsInt(),0,16383,1);
            JSpinner plane = number(row.get("plane").getAsInt(),0,3,1), type = number(row.get("type").getAsInt(),0,22,1);
            JSpinner facing = number(row.get("rotation").getAsInt(),0,3,1);
            JCheckBox mirror = new JCheckBox("Reflect geometry",row.has("mirrored") && row.get("mirrored").getAsBoolean());
            JPanel form = new JPanel(new GridLayout(0,2,8,6));
            for (Object[] field : new Object[][]{{"World X",x},{"World Y",y},{"Plane",plane},{"Type",type},{"Facing",facing}}) {
                form.add(new JLabel((String)field[0])); form.add((Component)field[1]);
            }
            form.add(mirror);
            if (JOptionPane.showConfirmDialog(frame,form,row.get("name").getAsString(),JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            edit(object("kind",kind,"key",row.get("key"),"changes",object("x",value(x),"y",value(y),"plane",value(plane),
                    "type",value(type),"rotation",value(facing),"mirrored",mirror.isSelected())));
        } catch (RuntimeException error) { showError(error); }
    }
    private void objectCatalog() {
        try {
            requireProject();
            JDialog dialog = new JDialog(frame,"Object palette",false); dialog.setSize(780,500); dialog.setLocationRelativeTo(frame);
            JComboBox<Source> source = new JComboBox<>();
            for (int i=0;i<donor.source.getItemCount();i++) source.addItem(donor.source.getItemAt(i));
            JTextField query = new JTextField(20); JSpinner page = number(1,1,100000,1);
            JSpinner type = number(10,0,22,1), facing = number(0,0,3,1);
            DefaultTableModel model = new DefaultTableModel(new String[]{"ID","Name","Width","Length"},0) {
                @Override public boolean isCellEditable(int row,int col) { return false; }
            };
            JTable table = new JTable(model); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            JLabel count = new JLabel("Search by name or ID");
            source.addActionListener(e -> {model.setRowCount(0);count.setText("Search the selected source");});
            JPanel search = row(); search.add(source); search.add(query); search.add(new JLabel("Page")); search.add(page);
            Runnable searchAction = () -> {
                Source s = (Source)source.getSelectedItem(); if (s == null) return;
                String q = query.getText(); int p = value(page);
                submit("Reading source object catalog",() -> rpc("catalog",object("source",s.key,"query",q,"page",p)), result -> {
                    if(source.getSelectedItem()!=s)return;
                    model.setRowCount(0);
                    for (JsonElement row : result.getAsJsonArray("objects")) {
                        JsonObject obj = row.getAsJsonObject();
                        model.addRow(new Object[]{obj.get("id").getAsInt(),obj.get("name").getAsString(),obj.get("size_x").getAsInt(),obj.get("size_y").getAsInt()});
                    }
                    count.setText(result.get("total").getAsInt()+" matches; 100 per page");
                });
            };
            button(search,"Search",searchAction); query.addActionListener(e -> searchAction.run());
            JPanel actions = row(); actions.add(count); actions.add(new JLabel("Type:")); actions.add(type); actions.add(new JLabel("Facing:")); actions.add(facing);
            button(actions,"Place at destination anchor",() -> {
                int index = table.getSelectedRow();
                if (index < 0) { JOptionPane.showMessageDialog(dialog,"Select an object first"); return; }
                Source s = (Source)source.getSelectedItem();
                edit(object("kind","object_place","object",object("source",s.key,"id",model.getValueAt(index,0),
                        "x",value(pasteX),"y",value(pasteY),"plane",destination.canvas.plane,"type",value(type),"rotation",value(facing))));
            });
            dialog.add(search,BorderLayout.NORTH); dialog.add(new JScrollPane(table),BorderLayout.CENTER); dialog.add(actions,BorderLayout.SOUTH); dialog.setVisible(true);
            searchAction.run();
        } catch (RuntimeException error) { showError(error); }
    }

    private void saveProject() {
        try {
            requireProject(); Path file = chooseFile(true,"Save project","map.xyren-map.json"); if (file == null) return;
            submit("Saving portable map project",() -> {
                JsonObject result = rpc("save",new JsonObject());
                atomicWrite(file,(result.get("document").toString()+"\n").getBytes(StandardCharsets.UTF_8)); return file;
            }, p -> status.setText("Saved "+p));
        } catch (RuntimeException error) { showError(error); }
    }
    private void importProject() {
        Path file = chooseFile(false,"Import map project",null); if (file == null) return;
        submit("Importing map project",() -> {
            if (Files.size(file) > 48L*1024*1024) throw new IOException("Project transfer exceeds 48 MiB");
            JsonElement document = JsonParser.parseString(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            return rpc("import",object("document",document));
        }, result -> { setProject(result); destination.load(); });
    }
    private void exportCache() {
        try {
            requireProject(); String expected = revision();
            JCheckBox full = new JCheckBox("Include the complete base cache (larger, independently reopenable)",true);
            JPanel choices = new JPanel(new BorderLayout(0,8));
            choices.add(new JLabel("Export compiled Xyren maps, asset dependencies and verified collision."),BorderLayout.NORTH); choices.add(full);
            if (JOptionPane.showConfirmDialog(frame,choices,"Export native cache",JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            Path file = chooseFile(true,"Export Xyren cache","xyren-map.zip"); if (file == null) return;
            boolean complete = full.isSelected();
            submit("Compiling native map export",() -> {
                JsonObject result = rpc("export_cache",object("revision",expected,"full_cache",complete));
                String token = result.get("token").getAsString(); long size = result.get("bytes").getAsLong();
                Path partial = Files.createTempFile(file.toAbsolutePath().getParent(),".xyren-map-",".part");
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256"); long offset = 0;
                    try (OutputStream out = Files.newOutputStream(partial)) {
                        while (offset < size) {
                            JsonObject chunk = rpc("download",object("token",token,"offset",offset));
                            if (chunk.get("offset").getAsLong() != offset) throw new IOException("Export download offset changed");
                            byte[] bytes = Base64.getDecoder().decode(chunk.get("data").getAsString());
                            if (bytes.length == 0 || offset+bytes.length > size) throw new IOException("Incomplete export download");
                            out.write(bytes); digest.update(bytes); offset += bytes.length;
                            final long done = offset;
                            SwingUtilities.invokeLater(() -> status.setText("Downloading export "+(done*100/size)+"%"));
                        }
                    }
                    if (!hex(digest.digest()).equals(result.get("sha256").getAsString())) throw new IOException("Export checksum differs");
                    move(partial,file); rpc("release_export",object("token",token)); return result;
                } finally { Files.deleteIfExists(partial); }
            }, result -> status.setText("Exported "+file+" · "+result.getAsJsonObject("manifest").getAsJsonArray("changed_regions").size()+" changed regions"));
        } catch (RuntimeException error) { showError(error); }
    }
    private Path chooseFile(boolean save,String title,String defaultName) {
        JFileChooser chooser = new JFileChooser(preferences.get("files",System.getProperty("user.home")));
        chooser.setDialogTitle(title); if (defaultName != null) chooser.setSelectedFile(new File(defaultName));
        int response = save ? chooser.showSaveDialog(frame) : chooser.showOpenDialog(frame);
        if (response != JFileChooser.APPROVE_OPTION) return null;
        Path file = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (save && Files.exists(file) && JOptionPane.showConfirmDialog(frame,"Replace "+file.getFileName()+"?","Save",JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null;
        preferences.put("files",file.getParent().toString()); return file;
    }
    private static void atomicWrite(Path file,byte[] data) throws IOException {
        Path temp = Files.createTempFile(file.getParent(),".xyren-project-",".part");
        try { Files.write(temp,data); move(temp,file); } finally { Files.deleteIfExists(temp); }
    }
    private static void move(Path source,Path dest) throws IOException {
        try { Files.move(source,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(source,dest,StandardCopyOption.REPLACE_EXISTING); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format("%02x",b & 255)); return value.toString();
    }
    private void shortcuts() {
        JPanel root = (JPanel)frame.getContentPane(); int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        for (Object[] row : new Object[][]{{KeyEvent.VK_C,"copy",(Runnable)() -> copy(false)},
                {KeyEvent.VK_X,"cut",(Runnable)() -> copy(true)},{KeyEvent.VK_V,"paste",(Runnable)this::pasteSelection},
                {KeyEvent.VK_Z,"undo",(Runnable)() -> edit(object("kind","undo"))},{KeyEvent.VK_Y,"redo",(Runnable)() -> edit(object("kind","redo"))},
                {KeyEvent.VK_S,"save",(Runnable)this::saveProject}}) {
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke((Integer)row[0],mask),row[1]);
            root.getActionMap().put(row[1],new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { if (!busy) ((Runnable)row[2]).run(); }
            });
        }
    }

    private final class Pane {
        final boolean draft;
        final JPanel panel = new JPanel(new BorderLayout()), header = row();
        final MapCanvas canvas = new MapCanvas();
        final JComboBox<Source> source = new JComboBox<>();
        final JSpinner x = number(3220,0,16383,1), y = number(3220,0,16383,1), plane = number(0,0,3,1);
        final JLabel regionLabel = new JLabel(" ");
        Pane(boolean draft) {
            this.draft = draft;
            header.add(new JLabel(draft ? "Destination project" : "Donor map"));
            if (!draft) header.add(source);
            header.add(new JLabel("X:")); header.add(x); header.add(new JLabel("Y:")); header.add(y);
            header.add(new JLabel("Plane:")); header.add(plane); button(header,"Load",this::load);
            if (!draft) button(header,"Add source",WorldEditor.this::addSource);
            JPanel top = new JPanel(new BorderLayout()); top.add(header,BorderLayout.NORTH); top.add(regionLabel,BorderLayout.SOUTH);
            regionLabel.setBorder(new EmptyBorder(3,8,3,8)); panel.add(top,BorderLayout.NORTH); panel.add(canvas);
            panel.setBorder(BorderFactory.createLineBorder(new Color(80,86,93),2));
            canvas.hovered = text -> coordinate.setText(text);
            canvas.selected = rect -> {
                active = this; highlight();
                status.setText((draft ? "Destination" : "Donor")+" selection: "+rect.x+", "+rect.y+" · "+rect.width+" × "+rect.height+" tiles");
            };
            canvas.picked = point -> {
                active = this; highlight();
                if (draft) {
                    if (point.x < 0 || point.x > 16383 || point.y < 0 || point.y > 16383) return;
                    pasteX.setValue(point.x); pasteY.setValue(point.y);
                    List<JsonObject> at = canvas.objectsAt(point.x,point.y);
                    if (!at.isEmpty()) for (int i=0;i<visibleObjects.size();i++) if (visibleObjects.get(i).get("key").equals(at.get(0).get("key"))) {
                        objectTable.getSelectionModel().setSelectionInterval(i,i); objectTable.scrollRectToVisible(objectTable.getCellRect(i,0,true)); break;
                    }
                }
            };
            plane.addChangeListener(event -> { canvas.plane = value(plane); canvas.repaint(); if (draft) refreshObjects(); });
        }
        String key() {
            if (draft) return "draft";
            Source selected = (Source)source.getSelectedItem();
            if (selected == null) throw new IllegalArgumentException("Choose a donor map"); return selected.key;
        }
        void load() {
            try {
                if (state == null) throw new IllegalArgumentException("Connect to the map backend first");
                if (draft) requireProject();
                int wx = value(x), wy = value(y); canvas.plane = value(plane);
                canvas.center(wx,wy);
                String key = key(); JsonArray regions = new JsonArray();
                Source current = draft ? (Source)base.getSelectedItem() : (Source)source.getSelectedItem();
                int rx = wx >> 6, ry = wy >> 6;
                for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++) {
                    int nx = rx+dx, ny = ry+dy; if (nx < 0 || ny < 0 || nx > 255 || ny > 255) continue;
                    int region = nx*256+ny;
                    boolean authored = false;
                    if (draft && project.has("loaded_regions")) for (JsonElement row : project.getAsJsonArray("loaded_regions"))
                        if (row.getAsInt() == region) authored = true;
                    if (current != null && current.hasRegion(region) || authored) regions.add(region);
                }
                if (regions.size() == 0) {
                    if (!draft) throw new IllegalArgumentException("The selected region is absent from this donor map");
                    canvas.load(object("regions",new JsonObject())); refreshObjects();
                    regionLabel.setText("Region "+(rx*256+ry)+" is empty; paste here or create a new region");
                    status.setText("Project saved. This destination square is empty."); return;
                }
                submit("Reading "+key+" terrain and scenery",() -> rpc("view",object("source",key,"regions",regions)), result -> {
                    canvas.load(result); regionLabel.setText("Region "+(rx*256+ry)+" · "+regions.size()+" map squares · plane "+canvas.plane);
                    if (draft) { setProject(result); refreshObjects(); }
                    status.setText("Loaded "+key+". Drag to select an area; right-click the destination anchor.");
                });
            } catch (RuntimeException error) { showError(error); }
        }
    }

    private void highlight() {
        donor.panel.setBorder(BorderFactory.createLineBorder(active == donor ? new Color(230,184,75) : new Color(80,86,93),2));
        destination.panel.setBorder(BorderFactory.createLineBorder(active == destination ? new Color(230,184,75) : new Color(80,86,93),2));
    }
    private void addSource() {
        JTextField path = new JTextField(45), label = new JTextField(45), keys = new JTextField(45);
        JComboBox<Integer> revision = new JComboBox<>(new Integer[]{235,211});
        JPanel form = new JPanel(new GridLayout(0,2,8,8));
        form.add(new JLabel("Cache folder or ZIP (on backend host):")); form.add(path);
        form.add(new JLabel("Map name:")); form.add(label);
        form.add(new JLabel("JS5 revision:")); form.add(revision);
        form.add(new JLabel("XTEA JSON path (if encrypted):")); form.add(keys);
        if (JOptionPane.showConfirmDialog(frame,form,"Add read-only source map",JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        JsonObject body = object("path",path.getText(),"label",label.getText(),"revision",revision.getSelectedItem());
        if (!keys.getText().trim().isEmpty()) body.addProperty("keys",keys.getText().trim());
        submit("Opening source cache",() -> rpc("add_source",body), result -> {
            // Adding a donor preserves the active destination and its revision.
            JsonObject saved = project; setState(result); if (saved != null) { setProject(saved); destination.load(); }
        });
    }
}
