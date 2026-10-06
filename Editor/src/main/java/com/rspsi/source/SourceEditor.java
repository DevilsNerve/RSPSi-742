package com.rspsi.source;

import com.google.gson.*;
import com.rspsi.world.BridgeClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.zip.ZipInputStream;
import static com.rspsi.world.BridgeClient.object;

/** Source-aware editing inside RSPSi's JavaFX editor, with two independent 3D views. */
public final class SourceEditor implements AutoCloseable {
    private record Source(String key,String label,Set<Integer> regions) {
        public String toString() { return label; }
    }
    private final Stage stage;
    private final BorderPane root=new BorderPane();
    private final Label status=new Label("Connecting to your map sources…");
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"rspsi-source-editor");t.setDaemon(true);return t;});
    private final Preferences preferences=Preferences.userRoot().node("/com/rspsi/world");
    private final View destination=new View(true),donor=new View(false);
    private final CheckBox terrain=new CheckBox("Terrain"),heights=new CheckBox("Heights"),flags=new CheckBox("Flags"),objects=new CheckBox("Objects"),allPlanes=new CheckBox("All planes");
    private final ComboBox<String> turns=new ComboBox<>(),pasteMode=new ComboBox<>(),heightMode=new ComboBox<>();
    private final CheckBox mirrorX=new CheckBox("Mirror X"),mirrorY=new CheckBox("Mirror Y");
    private final TextField pasteX=new TextField("3222"),pasteY=new TextField("3222"),heightOffset=new TextField("0");
    private BridgeClient bridge;
    private JsonObject project;
    private boolean assetsAvailable,closed,busy,connecting;
    private View active=donor;
    public SourceEditor(Stage stage) {
        this.stage=stage;stage.setTitle("RSPSi — Xyren 3D Map Editor");
        for(CheckBox check:List.of(terrain,heights,flags,objects,allPlanes))check.setSelected(true);
        turns.getItems().addAll("0°","90°","180°","270°");turns.getSelectionModel().select(0);
        pasteMode.getItems().addAll("Replace","Merge");pasteMode.getSelectionModel().select(0);
        heightMode.getItems().addAll("Keep source heights","Match destination height");heightMode.getSelectionModel().select(0);
        for(TextField field:List.of(pasteX,pasteY,heightOffset))field.setPrefColumnCount(6);
        ToolBar projects=new ToolBar(button("New project",this::createProject),button("Open project",this::openProject),button("Save project",this::saveProject),button("Import project",this::importProject),button("Export Xyren cache",this::exportCache),button("Connection",this::connection));
        FlowPane tools=new FlowPane(8,8,button("Copy",() -> copy(false)),button("Cut",() -> copy(true)),button("Paste",this::paste),button("Undo",() -> edit(object("kind","undo"))),button("Redo",() -> edit(object("kind","redo"))),button("Delete",this::delete),button("Set height",this::setHeight),button("Paint floor",this::paint),button("Place object",this::place),terrain,heights,flags,objects,allPlanes);
        tools.setPadding(new Insets(8));
        FlowPane transforms=new FlowPane(8,8,new Label("Paste at X"),pasteX,new Label("Y"),pasteY,turns,mirrorX,mirrorY,pasteMode,heightMode,new Label("Height offset"),heightOffset);
        transforms.setPadding(new Insets(0,8,8,8)); root.setTop(new VBox(projects,tools,transforms));
        SplitPane split=new SplitPane(destination.panel,donor.panel);split.setDividerPositions(.5);root.setCenter(split);
        VBox footer=new VBox(5,new Label("Left drag: select tiles    Right drag: orbit    Middle drag: pan    Wheel: zoom"),status);footer.setPadding(new Insets(8));root.setBottom(footer);
        Scene scene=new Scene(root,1440,900);
        scene.getStylesheets().add(SourceEditor.class.getResource("/css/modena_dark.css").toExternalForm());
        stage.setScene(scene);stage.setOnHidden(e -> close());
        scene.setOnKeyPressed(e -> {if(e.isControlDown()&&!(scene.getFocusOwner() instanceof TextInputControl))switch(e.getCode()) {
            case C -> guarded(() -> copy(false));case X -> guarded(() -> copy(true));case V -> guarded(this::paste);case Z -> guarded(() -> edit(object("kind","undo")));case Y -> guarded(() -> edit(object("kind","redo")));case S -> guarded(this::saveProject);default -> {}
        }});
    }
    public void show() { stage.show();connect(); }
    public BorderPane root() { return root; }
    public SourceViewport destinationViewport() { return destination.viewport; }
    public SourceViewport donorViewport() { return donor.viewport; }
    private Button button(String label,Runnable action) {Button b=new Button(label);b.setOnAction(e -> guarded(action));return b;}
    private void guarded(Runnable action) {if(busy){status.setText("Wait for the current map operation to finish.");return;}try{action.run();}catch(Exception error){error(error);}}
    private <T> void submit(String label,Callable<T> operation,Consumer<T> complete) {
        if(closed)return;busy=true;root.getTop().setDisable(true);status.setText(label);
        worker.submit(() -> {try{T result=operation.call();Platform.runLater(() -> {if(closed)return;busy=false;root.getTop().setDisable(false);try{complete.accept(result);}catch(Exception e){error(e);}});}catch(Exception error){Platform.runLater(() -> {if(closed)return;busy=false;root.getTop().setDisable(false);error(error);});}});
    }
    private JsonObject rpc(String action,JsonObject body) throws IOException {return bridge.call(action,body,message -> Platform.runLater(() -> status.setText(message)));}
    private void connect() {
        connecting=true;
        String command=System.getProperty("xyren.backend.command",preferences.get("backend",BridgeClient.defaultCommand()));
        submit("Connecting to Xyren, Emps World and Near Reality",() -> {
            if(bridge!=null)bridge.close();bridge=new BridgeClient(BridgeClient.command(command));return rpc("hello",new JsonObject());
        },hello -> {
            project=null; destination.base=null; donor.source.getItems().clear();
            for(JsonElement e:hello.getAsJsonArray("sources")) {
                JsonObject row=e.getAsJsonObject();if(row.has("error"))continue;Set<Integer> regions=new HashSet<>();for(JsonElement r:row.getAsJsonArray("regions"))regions.add(r.getAsInt());
                Source source=new Source(row.get("key").getAsString(),row.get("label").getAsString(),regions);donor.source.getItems().add(source);if(source.key.equals("xyren-world"))destination.base=source;
            }
            if(destination.base==null)throw new IllegalStateException("The Xyren cache is unavailable");
            assetsAvailable=hello.has("capabilities")&&hello.getAsJsonObject("capabilities").has("preview_3d");
            donor.source.getSelectionModel().select(donor.source.getItems().stream().filter(s -> s.label.toLowerCase(Locale.ROOT).contains("emps")).findFirst().orElse(donor.source.getItems().get(0)));
            connecting=false;destination.load(true);
        });
    }
    private void connection() {
        TextInputDialog dialog=new TextInputDialog(preferences.get("backend",BridgeClient.defaultCommand()));dialog.initOwner(stage);dialog.setTitle("Map backend command");dialog.setHeaderText("JSON array of command arguments");
        dialog.showAndWait().ifPresent(value -> {BridgeClient.command(value);preferences.put("backend",value);connect();});
    }
    private void requireProject() {if(project==null)throw new IllegalStateException("Create or open a Xyren project first.");}
    private String revision() {requireProject();return project.get("revision").getAsString();}
    private void setProject(JsonObject value) {project=value;stage.setTitle("RSPSi — Xyren 3D Map Editor — "+value.get("name").getAsString());}
    private void createProject() {
        TextInputDialog dialog=new TextInputDialog("Combined Xyren map");dialog.initOwner(stage);dialog.setTitle("New Xyren project");dialog.setHeaderText("Project name");
        dialog.showAndWait().filter(s -> !s.isBlank()).ifPresent(name -> submit("Creating your Xyren draft",() -> {rpc("base",object("source","xyren-world"));return rpc("create",object("name",name));},value -> {setProject(value);destination.load(true); }));
    }
    private void openProject() {
        submit("Reading saved Xyren projects",() -> rpc("projects",new JsonObject()),result -> {
            Map<String,String> names=new LinkedHashMap<>();for(JsonElement e:result.getAsJsonArray("projects")){JsonObject p=e.getAsJsonObject();names.put(p.get("name").getAsString()+" ["+p.get("id").getAsString()+"]",p.get("id").getAsString());}
            if(names.isEmpty()){status.setText("No saved projects. Create a new Xyren project.");return;}
            ChoiceDialog<String> dialog=new ChoiceDialog<>(names.keySet().iterator().next(),names.keySet());dialog.initOwner(stage);dialog.setTitle("Open Xyren project");dialog.setHeaderText("Select a project");
            dialog.showAndWait().ifPresent(name -> submit("Opening Xyren project",() -> rpc("open",object("project",names.get(name))),value -> {setProject(value);destination.load(true);}));
        });
    }
    private JsonArray planes(int plane) {JsonArray a=new JsonArray();if(allPlanes.isSelected())for(int p=0;p<4;p++)a.add(p);else a.add(plane);return a;}
    private JsonObject layers() {return object("terrain",terrain.isSelected(),"heights",heights.isSelected(),"flags",flags.isSelected(),"objects",objects.isSelected());}
    private SourceViewport.Selection selection(View view) {SourceViewport.Selection value=view.viewport.selection();if(value==null)throw new IllegalStateException("Drag across the tiles you want to edit.");return value;}
    private void edit(JsonObject operation) {
        String expected=revision();submit("Saving map edit",() -> {rpc("mutate",object("revision",expected,"operation",operation));return rpc("summary",new JsonObject());},value -> {
            setProject(value);boolean recenter=false;
            if(operation.get("kind").getAsString().equals("paste")) {
                int x=operation.get("x").getAsInt(),y=operation.get("y").getAsInt();
                recenter=(number(destination.x)>>6)!=(x>>6)||(number(destination.y)>>6)!=(y>>6);
                destination.x.setText(Integer.toString(x));destination.y.setText(Integer.toString(y));
            }
            destination.load(recenter);
        });
    }
    private void copy(boolean cut) {requireProject();if(cut&&active!=destination)throw new IllegalStateException("Cut is available in the Xyren draft.");edit(object("kind",cut?"cut":"copy","source",active.key(),"rect",selection(active).json(),"planes",planes(active.viewport.plane()),"layers",layers()));}
    private int number(TextField input) {return Integer.parseInt(input.getText().trim());}
    private void paste() {edit(object("kind","paste","x",number(pasteX),"y",number(pasteY),"plane",destination.viewport.plane(),"turns",turns.getSelectionModel().getSelectedIndex(),"mirror_x",mirrorX.isSelected(),"mirror_y",mirrorY.isSelected(),"mode",pasteMode.getValue().toLowerCase(Locale.ROOT),"height_mode",heightMode.getSelectionModel().getSelectedIndex()==0?"absolute":"relative","height_offset",number(heightOffset),"create_regions",true));}
    private void delete() {edit(object("kind","delete","rect",selection(destination).json(),"planes",planes(destination.viewport.plane()),"layers",layers()));}
    private void fill(JsonObject values) {edit(object("kind","fill","rect",selection(destination).json(),"planes",planes(destination.viewport.plane()),"values",values));}
    private void setHeight() {TextInputDialog dialog=new TextInputDialog("240");dialog.initOwner(stage);dialog.setHeaderText("Tile elevation (positive height)");dialog.showAndWait().ifPresent(v -> fill(object("height",Integer.parseInt(v))));}
    private void paint() {
        Source source=donor.source.getValue();TextInputDialog dialog=new TextInputDialog("underlay: 1");dialog.initOwner(stage);dialog.setHeaderText("Floor from "+source.label+" — enter underlay: ID or overlay: ID");
        dialog.showAndWait().ifPresent(v -> {String[] parts=v.split(":",2);if(parts.length!=2||!Set.of("underlay","overlay").contains(parts[0].trim()))throw new IllegalArgumentException("Enter underlay: ID or overlay: ID");fill(object(parts[0].trim(),object("source",source.key,"id",Integer.parseInt(parts[1].trim()))));});
    }
    private void place() {
        requireProject();Source source=donor.source.getValue();SourceViewport.Selection s=selection(destination);
        TextInputDialog dialog=new TextInputDialog("1,10,0");dialog.initOwner(stage);dialog.setHeaderText("Object from "+source.label+" — ID, placement type, rotation");
        dialog.showAndWait().ifPresent(v -> {String[] values=v.split(",");if(values.length!=3)throw new IllegalArgumentException("Enter ID,type,rotation");edit(object("kind","object_place","object",object("source",source.key,"id",Integer.parseInt(values[0].trim()),"type",Integer.parseInt(values[1].trim()),"rotation",Integer.parseInt(values[2].trim()),"x",s.x0(),"y",s.y0(),"plane",destination.viewport.plane())));});
    }
    private Path file(boolean save,String title,String name) {FileChooser chooser=new FileChooser();chooser.setTitle(title);if(name!=null)chooser.setInitialFileName(name);File value=save?chooser.showSaveDialog(stage):chooser.showOpenDialog(stage);return value==null?null:value.toPath();}
    private void saveProject() {requireProject();Path file=file(true,"Save Xyren project","xyren-map-project.json");if(file==null)return;submit("Saving portable map project",() -> {JsonObject result=rpc("save",new JsonObject());writeAtomic(file,(result.get("document").toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));return file;},p -> status.setText("Saved "+p));}
    private void importProject() {Path file=file(false,"Import map project",null);if(file==null)return;submit("Importing project",() -> {if(Files.size(file)>48L*1024*1024)throw new IOException("Project exceeds 48 MiB");return rpc("import",object("document",JsonParser.parseString(Files.readString(file))));},value -> {setProject(value);destination.load(true);});}
    private void exportCache() {String expected=revision();Path file=file(true,"Export complete Xyren cache","xyren-combined-cache.zip");if(file==null)return;submit("Compiling maps, assets and collision",() -> {JsonObject export=rpc("export_cache",object("revision",expected,"full_cache",true));download(export,file);return file;},p -> status.setText("Exported "+p));}
    private void download(JsonObject metadata,Path destination) throws Exception {
        String token=metadata.get("token").getAsString();long length=metadata.get("bytes").getAsLong();Path partial=Files.createTempFile(destination.toAbsolutePath().getParent(),".rspsi-",".part");
        try {MessageDigest hash=MessageDigest.getInstance("SHA-256");long offset=0;try(OutputStream out=Files.newOutputStream(partial)) {
            while(offset<length){JsonObject chunk=rpc("download",object("token",token,"offset",offset));byte[] bytes=Base64.getDecoder().decode(chunk.get("data").getAsString());if(chunk.get("offset").getAsLong()!=offset||bytes.length==0||offset+bytes.length>length)throw new IOException("Incomplete download");out.write(bytes);hash.update(bytes);offset+=bytes.length;}
        }if(!HexFormat.of().formatHex(hash.digest()).equals(metadata.get("sha256").getAsString()))throw new IOException("Download checksum differs");move(partial,destination);
        } finally {Files.deleteIfExists(partial);rpc("release_export",object("token",token));}
    }
    private static void writeAtomic(Path file,byte[] bytes) throws IOException {Path temp=Files.createTempFile(file.toAbsolutePath().getParent(),".rspsi-",".part");try{Files.write(temp,bytes);move(temp,file);}finally{Files.deleteIfExists(temp);}}
    private static void move(Path from,Path to) throws IOException {try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}}
    private JsonObject preview(String source,JsonArray regions,int x,int y,int plane) throws Exception {
        JsonObject export=rpc("preview_3d",object("source",source,"regions",regions,"x",x,"y",y,"plane",plane,"revision",revision()));
        Path file=Files.createTempFile("rspsi-preview-",".zip");try {download(export,file);try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(file))) {
            var entry=zip.getNextEntry();if(entry==null||!entry.getName().equals("preview.json")||entry.getSize()>256L*1024*1024)throw new IOException("Invalid 3D packet");
            byte[] bytes=zip.readNBytes(256*1024*1024+1);if(bytes.length>256*1024*1024)throw new IOException("3D packet exceeds capacity");return JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }}finally{Files.deleteIfExists(file);}
    }
    private void error(Exception error) {status.setText(error.getMessage()==null?error.toString():error.getMessage());Alert alert=new Alert(Alert.AlertType.ERROR,status.getText(),ButtonType.OK);alert.initOwner(stage);alert.setHeaderText("Map operation could not finish");alert.show();}
    @Override public void close() {closed=true;worker.shutdownNow();if(bridge!=null)bridge.close();}
    private final class View {
        final boolean draft;final VBox panel=new VBox(6);final SourceViewport viewport=new SourceViewport();
        final ComboBox<Source> source=new ComboBox<>();final TextField x=new TextField("3222"),y=new TextField("3222");
        final ComboBox<Integer> plane=new ComboBox<>();final Label info=new Label("Drag to select a section");Source base;boolean sceneryLoaded;
        final CheckBox neighbors=new CheckBox("3×3 regions");
        View(boolean draft) {
            this.draft=draft;x.setPrefColumnCount(5);y.setPrefColumnCount(5);plane.getItems().addAll(0,1,2,3);plane.getSelectionModel().select(0);
            FlowPane header=new FlowPane(6,6,new Label(draft?"Xyren destination":"Donor map"));if(!draft)header.getChildren().add(source);
            header.getChildren().addAll(new Label("X"),x,new Label("Y"),y,new Label("Plane"),plane,neighbors,button("Load 3D",() -> load(true)));
            panel.setPadding(new Insets(8));panel.getChildren().addAll(header,viewport,info);VBox.setVgrow(viewport,Priority.ALWAYS);
            viewport.onActivate=() -> active=this;
            viewport.onTile=tile -> {if(draft){pasteX.setText(Integer.toString(tile.x()));pasteY.setText(Integer.toString(tile.y()));}};
            viewport.onSelection=s -> info.setText("Selected "+s.x0()+", "+s.y0()+" to "+s.x1()+", "+s.y1()+" ("+(s.x1()-s.x0()+1)+" × "+(s.y1()-s.y0()+1)+")");
            source.setOnAction(e -> {if(!busy&&!connecting&&source.getValue()!=null)load(true);});
            plane.setOnAction(e -> {if(!busy&&viewport.data()!=null)load(false);});
        }
        String key() {return draft?(project==null?base.key:"draft"):Objects.requireNonNull(source.getValue(),"Choose a donor source").key;}
        void load(boolean recenter) {
            int cx=number(x),cy=number(y),p=plane.getValue();if(cx<0||cy<0||cx>16383||cy>16383)throw new IllegalArgumentException("Map coordinates must be 0–16383");
            String key=key();Source selected=draft?base:source.getValue();int region=(cx>>6)<<8|(cy>>6);if(!draft&&!selected.regions.contains(region))throw new IllegalArgumentException("This source has no region at those coordinates.");
            JsonArray regions=new JsonArray();
            if(neighbors.isSelected())for(int rx=Math.max(0,(cx>>6)-1);rx<=Math.min(255,(cx>>6)+1);rx++)for(int ry=Math.max(0,(cy>>6)-1);ry<=Math.min(255,(cy>>6)+1);ry++) {
                int id=rx<<8|ry;if(id==region||selected.regions.contains(id))regions.add(id);
            }
            else regions.add(region);
            submit("Loading "+(draft?"Xyren draft":selected.label)+" terrain",() -> rpc("view",object("source",key,"regions",regions)),value -> {
                viewport.show(value,p,cx,cy,recenter);sceneryLoaded=false;info.setText("Region "+region+" · Plane "+p);
                if(assetsAvailable&&project!=null)submit("Loading source models and textures",() -> preview(key,regions,cx,cy,p),packet -> {
                    viewport.showScenery(packet);sceneryLoaded=true;int failures=packet.has("failures")?packet.getAsJsonArray("failures").size():0;
                    if(failures>0)info.setText("Region "+region+" · "+failures+" scenery failures");
                    status.setText(failures==0?"3D map loaded. Select an area to copy or edit.":"3D preview: "+failures+" scenery items could not be decoded; see backend diagnostics.");
                    finishLoad();
                });else {
                    status.setText(assetsAvailable?"Terrain loaded. Create or open a project to edit and load scenery.":"Terrain loaded in 3D. Model preview is waiting for the prepared bridge update.");
                    finishLoad();
                }
            });
        }
        private void finishLoad() {
            if(draft&&donor.source.getValue()!=null&&(donor.viewport.data()==null||(assetsAvailable&&project!=null&&!donor.sceneryLoaded)))donor.load(donor.viewport.data()==null);
        }
    }
}
