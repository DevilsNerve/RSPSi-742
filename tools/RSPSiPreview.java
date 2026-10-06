import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.io.*;

/** Uses the matching maintained client in an isolated process; starts no game. */
public final class RSPSiPreview {
    private static final String ROOT="com.lakesidegamers.xyren.client.";
    private static final Gson GSON=new Gson();
    private static Class<?> cls(String name) throws Exception {return Class.forName(ROOT+name);}
    private static Object field(Object object,String name) throws Exception {Field f=object.getClass().getField(name);return f.get(object);}
    private static int integer(Object object,String name) throws Exception {return ((Number)field(object,name)).intValue();}
    private static Path asset(JsonObject job,String name) {
        Path overlay=Path.of(job.get("overlay").getAsString()).resolve(name);
        return Files.isRegularFile(overlay)?overlay:Path.of(job.get("cache").getAsString()).resolve(name);
    }
    private static byte[] inflate(Path file) throws Exception {try(InputStream in=new GZIPInputStream(Files.newInputStream(file))){return in.readAllBytes();}}
    private static int number(JsonObject row,String field) {return row.get(field).getAsInt();}
    private static int height(JsonObject job,int x,int y,int plane) {
        JsonArray heights=job.getAsJsonObject("heights").getAsJsonArray(Integer.toString((x>>6)<<8|(y>>6)));
        if(heights==null)return 0;return -heights.get(plane*4096+(x&63)*64+(y&63)).getAsInt();
    }
    private static double vertex(Object model,String axis,int index) throws Exception {return ((Number)model.getClass().getMethod("getVertex"+axis,int.class).invoke(model,index)).doubleValue();}
    private static int rgb(int hsl) {
        double h=(hsl>>10&63)/64.,s=(hsl>>7&7)/8.,l=(hsl&127)/128.;
        double c=(1-Math.abs(2*l-1))*s,x=c*(1-Math.abs(h*6%2-1)),m=l-c/2;
        double r=0,g=0,b=0;switch((int)(h*6)){case 0:r=c;g=x;break;case 1:r=x;g=c;break;case 2:g=c;b=x;break;case 3:g=x;b=c;break;case 4:r=x;b=c;break;default:r=c;b=x;}
        return (int)((r+m)*255)<<16|(int)((g+m)*255)<<8|(int)((b+m)*255);
    }
    private static JsonObject mesh(Object model,JsonObject row,int x,int y,int elevation,int sizeX,int sizeY,Set<Integer> textures) throws Exception {
        int vertices=integer(model,"vertexCount"),count=integer(model,"triangleCount");
        if(vertices<0||vertices>1000000||count<0||count>2000000)throw new IOException("Model exceeds capacity");
        JsonObject result=new JsonObject();JsonArray points=new JsonArray(),faces=new JsonArray();
        for(int v=0;v<vertices;v++){JsonArray p=new JsonArray();p.add(vertex(model,"X",v));p.add(vertex(model,"Y",v));p.add(vertex(model,"Z",v));points.add(p);}
        int[] a=(int[])field(model,"triangleViewSpaceX"),b=(int[])field(model,"triangleViewSpaceY"),c=(int[])field(model,"triangleViewSpaceZ");
        int[] colors=(int[])field(model,"colorValues"),alpha=(int[])field(model,"triangleAlphaValues");float[][] uv=(float[][])field(model,"uv");
        Method getTexture=model.getClass().getMethod("getTexture",int.class,boolean.class);
        for(int f=0;f<count;f++) {
            int texture=((Number)getTexture.invoke(model,f,true)).intValue();if(texture< -1)texture=-texture;
            if(texture>=0)textures.add(texture);JsonArray face=new JsonArray();
            face.add(a[f]);face.add(b[f]);face.add(c[f]);face.add(texture);face.add(rgb(colors==null?0:colors[f]&65535));face.add(alpha==null?0:alpha[f]&255);
            for(int i=0;i<6;i++){float value=uv!=null&&uv[f]!=null&&uv[f].length>i?uv[f][i]:0;face.add(Float.isFinite(value)?value:0);}
            faces.add(face);
        }
        result.add("vertices",points);result.add("faces",faces);result.addProperty("key",row.get("key").getAsString());
        result.addProperty("tile_x",x);result.addProperty("tile_y",y);result.addProperty("plane",number(row,"plane"));
        int type=number(row,"type");result.addProperty("x",x*128+(type==10||type==11?sizeX*64:64));result.addProperty("y",y*128+(type==10||type==11?sizeY*64:64));result.addProperty("height",elevation);
        return result;
    }
    public static void main(String[] args) throws Exception {
        JsonObject job=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        Class<?> fetcher=cls("io.cache.OnDemandFetcher"),template=cls("model.texture.TextureTemplate"),raster=cls("view.Rasterizer"),model=cls("model.Model"),definition=cls("model.definition.ObjectDef");
        Object manager=fetcher.getConstructor().newInstance();template.getMethod("init",int.class,fetcher).invoke(null,((Number)raster.getField("textureCount").get(null)).intValue(),manager);
        Class<?> stream=cls("io.StreamLoader");Object config=stream.getConstructor(byte[].class,String.class).newInstance(Files.readAllBytes(asset(job,"0/2")),"config");
        cls("model.texture.TextureDef").getMethod("unpackConfig",stream).invoke(null,config);cls("model.animation.Animation").getMethod("unpackConfig",stream).invoke(null,config);definition.getMethod("unpackConfig",stream).invoke(null,config);
        model.getField("usePooledModelLoading").setBoolean(null,false);model.getMethod("prepareRasterProductModelData",int.class,fetcher).invoke(null,131070,manager);
        raster.getField("modelsTextured").setBoolean(null,true);
        Class.forName("com.badlogic.gdx.utils.GdxNativesLoader").getMethod("load").invoke(null);cls("model.Client").getField("newRendering").setBoolean(null,true);
        int pool=((Number)model.getField("DEFAULT_POOL").get(null)).intValue();Set<Integer> loaded=new HashSet<>(),textures=new TreeSet<>();
        for(JsonElement region:job.getAsJsonObject("surfaces").asMap().values())for(JsonElement texture:region.getAsJsonArray())if(texture.getAsInt()>=0)textures.add(texture.getAsInt());
        JsonArray meshes=new JsonArray(),failures=new JsonArray();
        for(JsonElement element:job.getAsJsonArray("objects")) {
            JsonObject row=element.getAsJsonObject();
            try {
                Object def=definition.getMethod("forID",int.class).invoke(null,number(row,"native_id"));if(def==null)continue;
                int[] ids=(int[])field(def,"modelIds");if(ids==null)continue;
                for(int id:ids)if(loaded.add(id))model.getMethod("readModel",byte[].class,int.class,boolean.class).invoke(null,inflate(asset(job,"1/"+(id+1))),id,true);
                int x=number(row,"x"),y=number(row,"y"),plane=number(row,"plane"),type=number(row,"type"),rotation=number(row,"rotation");
                int sizeX=integer(def,"sizeX"),sizeY=integer(def,"sizeY");if((rotation&1)!=0){int n=sizeX;sizeX=sizeY;sizeY=n;}
                int h00=height(job,x,y,plane),h10=height(job,x+1,y,plane),h11=height(job,x+1,y+1,plane),h01=height(job,x,y+1,plane),mean=(h00+h10+h11+h01)>>2;
                int normalized=type==11?10:type>=4&&type<=8?4:type;
                // L walls own two independently oriented wall legs.
                int[] facings=type==2?new int[]{rotation+4,(rotation+1)&3}:new int[]{rotation};
                for(int facing:facings) {
                    Object mesh=definition.getMethod("generateModel",int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class).invoke(def,normalized,facing,h00,h10,h11,h01,-1,pool);
                    if(mesh!=null)meshes.add(mesh(mesh,row,x,y,mean,sizeX,sizeY,textures));
                    else if((boolean)definition.getMethod("supportsPlacementType",int.class).invoke(def,type))throw new IOException("Client could not compose a supported placement type");
                }
            } catch(Exception error) {JsonObject failure=new JsonObject();failure.add("key",row.get("key"));failure.addProperty("error",error.getCause()==null?error.toString():error.getCause().toString());failures.add(failure);}
        }
        JsonObject pixels=new JsonObject();
        for(int id:textures)try {
            template.getMethod("load",int.class,byte[].class).invoke(null,id,inflate(asset(job,"5/"+id)));
            int[] data=(int[])raster.getMethod("getTexturePixels",int.class,boolean.class).invoke(null,id,true);
            if(data==null)throw new IOException("Texture pixels unavailable");int width=(int)Math.sqrt(data.length);if(width*width!=data.length)throw new IOException("Unsupported texture dimensions");
            JsonObject texture=new JsonObject();texture.addProperty("width",width);texture.addProperty("height",width);texture.add("pixels",GSON.toJsonTree(data));pixels.add(Integer.toString(id),texture);
        }catch(Exception error){JsonObject failure=new JsonObject();failure.addProperty("texture",id);failure.addProperty("error",error.toString());failures.add(failure);}
        JsonObject packet=new JsonObject();packet.addProperty("schema","rspsi-source-preview-1");packet.add("meshes",meshes);packet.add("textures",pixels);packet.add("surfaces",job.get("surfaces"));packet.add("failures",failures);
        Files.writeString(Path.of(args[1]),GSON.toJson(packet));
    }
}
