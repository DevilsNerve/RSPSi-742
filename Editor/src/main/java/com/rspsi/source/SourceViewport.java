package com.rspsi.source;

import com.google.gson.*;
import com.jagex.map.tile.ShapedTile;
import javafx.geometry.Point3D;
import javafx.scene.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.*;
import javafx.scene.shape.*;
import javafx.scene.transform.*;
import java.util.*;
import java.util.function.Consumer;

/** A depth-buffered viewport. Every pane owns its geometry, camera and materials. */
public final class SourceViewport extends StackPane {
    public record Tile(int x, int y, int plane) {}
    public record Selection(int x0, int y0, int x1, int y1) {
        public JsonObject json() { return com.rspsi.world.BridgeClient.object("x0",x0,"y0",y0,"x1",x1,"y1",y1); }
    }
    private final Group terrain = new Group(), scenery = new Group(), selectionNodes = new Group();
    private final Group world = new Group(terrain, scenery, selectionNodes);
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final SubScene scene;
    private final Translate target = new Translate();
    private final Rotate yaw = new Rotate(0,Rotate.Y_AXIS), pitch = new Rotate(-55,Rotate.X_AXIS);
    private final Translate distance = new Translate(0,0,-2800);
    private JsonObject data;
    private JsonObject surfaces=new JsonObject(),texturePixels=new JsonObject();
    private int baseX,baseY,plane;
    private double dragX,dragY;
    private Tile anchor;
    private Selection selection;
    public Consumer<Tile> onTile = tile -> {};
    public Consumer<Selection> onSelection = value -> {};
    public Runnable onActivate = () -> {};

    public SourceViewport() {
        Group root = new Group(world,new AmbientLight(Color.color(.85,.85,.85)));
        scene = new SubScene(root,640,600,true,SceneAntialiasing.BALANCED);
        scene.setFill(Color.web("#17202c"));
        camera.setNearClip(4); camera.setFarClip(100000); camera.setFieldOfView(45);
        camera.getTransforms().addAll(target,yaw,pitch,distance);
        scene.setCamera(camera); getChildren().add(scene);
        scene.widthProperty().bind(widthProperty()); scene.heightProperty().bind(heightProperty());
        setMinSize(260,260);
        scene.setOnScroll(e -> { distance.setZ(Math.max(-40000,Math.min(-180,distance.getZ()*Math.exp(-e.getDeltaY()*.002)))); e.consume(); });
        scene.setOnMousePressed(e -> {
            onActivate.run(); dragX=e.getSceneX(); dragY=e.getSceneY();
            if(e.getButton()==MouseButton.PRIMARY) {
                Tile tile=pick(e.getPickResult());
                if(tile!=null) { anchor=tile; select(tile,tile); onTile.accept(tile); }
            }
        });
        scene.setOnMouseDragged(e -> {
            double dx=e.getSceneX()-dragX,dy=e.getSceneY()-dragY;
            dragX=e.getSceneX(); dragY=e.getSceneY();
            if(e.isSecondaryButtonDown()) {
                yaw.setAngle(yaw.getAngle()+dx*.4); pitch.setAngle(Math.max(-85,Math.min(-12,pitch.getAngle()-dy*.3)));
            } else if(e.isMiddleButtonDown() || e.isAltDown()) {
                double scale=-distance.getZ()/900,angle=Math.toRadians(yaw.getAngle());
                target.setX(target.getX()-(dx*Math.cos(angle)+dy*Math.sin(angle))*scale);
                target.setZ(target.getZ()+(dx*Math.sin(angle)-dy*Math.cos(angle))*scale);
            } else if(e.isPrimaryButtonDown() && anchor!=null) {
                Tile tile=pick(e.getPickResult()); if(tile!=null)select(anchor,tile);
            }
        });
        scene.setOnMouseReleased(e -> anchor=null);
    }
    private Tile pick(javafx.scene.input.PickResult pick) {
        Node node=pick.getIntersectedNode();
        if(node==null)return null;
        if(node.getUserData() instanceof Tile tile)return tile;
        if(node.getUserData() instanceof List<?> tiles && pick.getIntersectedFace()>=0 && pick.getIntersectedFace()<tiles.size())
            return (Tile)tiles.get(pick.getIntersectedFace());
        return null;
    }
    private void select(Tile a,Tile b) {
        selection=new Selection(Math.min(a.x,b.x),Math.min(a.y,b.y),Math.max(a.x,b.x),Math.max(a.y,b.y));
        drawSelection(); onSelection.accept(selection);
    }
    public Selection selection() { return selection; }
    public int plane() { return plane; }
    public JsonObject data() { return data; }
    public void show(JsonObject value,int currentPlane,int x,int y,boolean recenter) {
        data=value; plane=currentPlane; baseX=(x>>6)*64; baseY=(y>>6)*64;surfaces=new JsonObject();texturePixels=new JsonObject();
        terrain.getChildren().clear(); scenery.getChildren().clear(); selectionNodes.getChildren().clear(); selection=null;
        buildTerrain();
        if(recenter) { target.setX((x-baseX)*128+64); target.setZ(-(y-baseY)*128-64); target.setY(-height(x,y)); }
    }
    private double height(int x,int y) {
        if(data==null)return 0;
        JsonObject region=data.getAsJsonObject("regions").getAsJsonObject(Integer.toString((x>>6)<<8|(y>>6)));
        if(region==null)return 0;
        return region.getAsJsonArray("tiles").get(plane*4096+(x&63)*64+(y&63)).getAsJsonArray().get(7).getAsDouble();
    }
    private final Map<Integer,Integer> colorSlots=new LinkedHashMap<>();
    private int colorIndex(int rgb) { return colorSlots.computeIfAbsent(rgb,k -> colorSlots.size()); }
    private int floorColor(JsonArray tile,boolean overlay) {
        String source=tile.get(overlay?6:5).getAsString(); int id=tile.get(overlay?2:1).getAsInt();
        JsonObject palette=data.getAsJsonObject("palettes").getAsJsonObject(source);
        if(palette!=null)for(JsonElement e:palette.getAsJsonArray(overlay?"overlays":"underlays")) {
            JsonObject row=e.getAsJsonObject(); if(row.get("id").getAsInt()!=id)continue;
            if(row.has("color")&&!row.get("color").isJsonNull()) {
                JsonArray c=row.getAsJsonArray("color"); return c.get(0).getAsInt()<<16|c.get(1).getAsInt()<<8|c.get(2).getAsInt();
            }
            if(row.has("color")&&row.get("color").isJsonNull())return -1;
            return row.get("rgb").getAsInt();
        }
        return 0x526652;
    }
    private void buildTerrain() {
        terrain.getChildren().clear();colorSlots.clear(); TriangleMesh mesh=new TriangleMesh(); List<Tile> picks=new ArrayList<>();
        Map<Integer,TriangleMesh> textured=new LinkedHashMap<>();Map<Integer,List<Tile>> texturePicks=new HashMap<>();
        for(var entry:data.getAsJsonObject("regions").entrySet()) {
            int region=Integer.parseInt(entry.getKey()),rx=(region>>8)*64,ry=(region&255)*64;
            JsonArray cells=entry.getValue().getAsJsonObject().getAsJsonArray("tiles");
            for(int x=0;x<64;x++)for(int y=0;y<64;y++) {
                JsonArray cell=cells.get(plane*4096+x*64+y).getAsJsonArray();
                if(cell.get(1).getAsInt()==0&&cell.get(2).getAsInt()==0)continue;
                int gx=rx+x,gy=ry+y,shape=cell.get(3).getAsInt(); boolean overlay=cell.get(2).getAsInt()!=0,mirror=shape>=48;
                int type=overlay?(shape%48)/4+1:0,rotation=shape&3;
                ShapedTile geometry=new ShapedTile(type,mirror?(-rotation&3):rotation);
                int pointOffset=mesh.getPoints().size()/3;
                int[] px=geometry.getOrigVertexX(),py=geometry.getOrigVertexZ();
                double h00=height(gx,gy),h10=heightOrEdge(gx+1,gy,gx,gy),h11=heightOrEdge(gx+1,gy+1,gx,gy),h01=heightOrEdge(gx,gy+1,gx,gy);
                for(int i=0;i<px.length;i++) {
                    double u=(mirror?128-px[i]:px[i])/128.,v=py[i]/128.;
                    double h=(1-u)*(1-v)*h00+u*(1-v)*h10+u*v*h11+(1-u)*v*h01;
                    mesh.getPoints().addAll((float)((gx-baseX+u)*128),(float)-h,(float)(-(gy-baseY+v)*128));
                }
                int[] a=geometry.getTriangleA(),b=geometry.getTriangleB(),c=geometry.getTriangleC(),parts=ShapedTile.shapedTileElementData[type];
                for(int f=0;f<a.length;f++) {
                    int material=parts[f*4];if(material==0&&cell.get(1).getAsInt()==0)continue;
                    int color=floorColor(cell,material==1);int slot=colorIndex(color);
                    JsonArray surface=surfaces.getAsJsonArray(Integer.toString(region));int texture=material==1&&surface!=null?surface.get(x*64+y).getAsInt():-1;
                    if(texture>=0&&texturePixels.has(Integer.toString(texture))) {
                        TriangleMesh batch=textured.computeIfAbsent(texture,k -> new TriangleMesh());
                        for(int vertex:new int[]{a[f],b[f],c[f]}) {
                            int index=batch.getPoints().size()/3,offset=(pointOffset+vertex)*3;
                            batch.getPoints().addAll(mesh.getPoints().get(offset),mesh.getPoints().get(offset+1),mesh.getPoints().get(offset+2));
                            batch.getTexCoords().addAll((mirror?128-px[vertex]:px[vertex])/128f,py[vertex]/128f);batch.getFaces().addAll(index,index);
                        }
                        texturePicks.computeIfAbsent(texture,k -> new ArrayList<>()).add(new Tile(gx,gy,plane));
                    } else {
                        if(color<0)continue;
                        mesh.getFaces().addAll(pointOffset+a[f],slot,pointOffset+b[f],slot,pointOffset+c[f],slot);
                        picks.add(new Tile(gx,gy,plane));
                    }
                }
            }
        }
        int size=Math.max(1,colorSlots.size()); WritableImage palette=new WritableImage(size,1);
        for(var entry:colorSlots.entrySet()) { int rgb=entry.getKey(); palette.getPixelWriter().setArgb(entry.getValue(),0,0xff000000|rgb); mesh.getTexCoords().addAll((entry.getValue()+.5f)/size,.5f); }
        if(colorSlots.isEmpty())mesh.getTexCoords().addAll(0,0);
        MeshView view=new MeshView(mesh); view.setCullFace(CullFace.NONE); view.setUserData(picks);
        PhongMaterial material=new PhongMaterial(Color.WHITE); material.setDiffuseMap(palette); material.setSpecularColor(Color.BLACK); view.setMaterial(material);
        terrain.getChildren().add(view);
        for(var entry:textured.entrySet()) {
            MeshView texturedView=new MeshView(entry.getValue());texturedView.setMaterial(textureMaterial(texturePixels.getAsJsonObject(Integer.toString(entry.getKey()))));
            texturedView.setCullFace(CullFace.NONE);texturedView.setUserData(texturePicks.get(entry.getKey()));terrain.getChildren().add(texturedView);
        }
    }
    private double heightOrEdge(int x,int y,int fallbackX,int fallbackY) {
        return data.getAsJsonObject("regions").has(Integer.toString((x>>6)<<8|(y>>6)))?height(x,y):height(fallbackX,fallbackY);
    }
    private void drawSelection() {
        selectionNodes.getChildren().clear(); if(selection==null)return;
        PhongMaterial material=new PhongMaterial(Color.color(.1,.7,1,.3));
        for(int x=selection.x0;x<=selection.x1;x++)for(int y=selection.y0;y<=selection.y1;y++) {
            Box box=new Box(127,2,127); box.setTranslateX((x-baseX)*128+64); box.setTranslateZ(-(y-baseY)*128-64);
            box.setTranslateY(-height(x,y)-3); box.setMaterial(material);box.setMouseTransparent(true);selectionNodes.getChildren().add(box);
        }
    }
    /** A validated packet contains baked source-owned model geometry and native texture pixels. */
    public void showScenery(JsonObject packet) {
        if(!packet.has("schema")||!packet.get("schema").getAsString().equals("rspsi-source-preview-1"))throw new IllegalArgumentException("Unsupported 3D preview packet");
        texturePixels=packet.getAsJsonObject("textures");surfaces=packet.has("surfaces")?packet.getAsJsonObject("surfaces"):new JsonObject();buildTerrain();
        scenery.getChildren().clear(); Map<String,PhongMaterial> materials=new HashMap<>();
        JsonObject textures=packet.getAsJsonObject("textures");
        for(JsonElement e:packet.getAsJsonArray("meshes")) {
            JsonObject object=e.getAsJsonObject(); if(object.get("plane").getAsInt()!=plane)continue;
            JsonArray vertices=object.getAsJsonArray("vertices"),faces=object.getAsJsonArray("faces");
            Map<String,TriangleMesh> batches=new LinkedHashMap<>();
            for(JsonElement f:faces) {
                JsonArray face=f.getAsJsonArray(); int texture=face.get(3).getAsInt(),rgb=face.get(4).getAsInt(),alpha=face.get(5).getAsInt();
                if(alpha>=255)continue;
                String key=texture>=0?"t"+texture:"c"+rgb+":"+alpha;
                TriangleMesh mesh=batches.computeIfAbsent(key,k -> new TriangleMesh());
                for(int corner=0;corner<3;corner++) {
                    int index=mesh.getPoints().size()/3; JsonArray vertex=vertices.get(face.get(corner).getAsInt()).getAsJsonArray();
                    mesh.getPoints().addAll(vertex.get(0).getAsFloat(),vertex.get(1).getAsFloat(),-vertex.get(2).getAsFloat());
                    mesh.getTexCoords().addAll(face.get(6+corner*2).getAsFloat(),face.get(7+corner*2).getAsFloat());mesh.getFaces().addAll(index,index);
                }
                materials.computeIfAbsent(key,k -> {
                    PhongMaterial m=new PhongMaterial(Color.rgb(rgb>>16&255,rgb>>8&255,rgb&255,1-alpha/255.)); m.setSpecularColor(Color.BLACK);
                    if(texture>=0 && textures.has(Integer.toString(texture))) {
                        return textureMaterial(textures.getAsJsonObject(Integer.toString(texture)));
                    }
                    return m;
                });
            }
            for(var entry:batches.entrySet()) {
                MeshView view=new MeshView(entry.getValue()); view.setMaterial(materials.get(entry.getKey()));view.setCullFace(CullFace.NONE);
                view.setTranslateX(object.get("x").getAsDouble()-baseX*128); view.setTranslateZ(-(object.get("y").getAsDouble()-baseY*128));
                view.setTranslateY(object.get("height").getAsDouble());
                view.setUserData(new Tile(object.get("tile_x").getAsInt(),object.get("tile_y").getAsInt(),plane)); scenery.getChildren().add(view);
            }
        }
    }
    private PhongMaterial textureMaterial(JsonObject t) {
        int w=t.get("width").getAsInt(),h=t.get("height").getAsInt();JsonArray pixels=t.getAsJsonArray("pixels");
        if(w<1||h<1||w>4096||h>4096||pixels.size()!=(long)w*h)throw new IllegalArgumentException("Invalid preview texture dimensions");
        WritableImage image=new WritableImage(w,h);for(int p=0;p<pixels.size();p++)image.getPixelWriter().setArgb(p%w,p/w,pixels.get(p).getAsInt());
        PhongMaterial material=new PhongMaterial(Color.WHITE);material.setDiffuseMap(image);material.setSpecularColor(Color.BLACK);return material;
    }
    public int sceneryCount() { return scenery.getChildren().size(); }
    public SubScene subScene() { return scene; }
}
