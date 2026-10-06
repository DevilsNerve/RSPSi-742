package com.rspsi.world;

import com.google.gson.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/** Real four-plane terrain and object footprints, with world-coordinate picking. */
public final class MapCanvas extends JPanel {
    private final Map<Integer, JsonObject> regions = new HashMap<>();
    private final Map<String, Map<Integer, Color>> underlays = new HashMap<>(), overlays = new HashMap<>();
    private JsonArray shapeMasks;
    private final List<List<JsonObject>> planeObjects = new ArrayList<>();
    public int plane, baseX = 3200, baseY = 3200, tilePixels = 7;
    public Rectangle selection;
    public Point anchor;
    public Consumer<Point> picked = point -> {};
    public Consumer<Rectangle> selected = rect -> {};
    public Consumer<String> hovered = text -> {};
    private Point dragging, lastPan;
    private Rectangle ghost;
    private int ghostWidth, ghostHeight;
    private boolean pastePreview;

    public MapCanvas() {
        for(int p=0;p<4;p++)planeObjects.add(new ArrayList<>());
        setBackground(new Color(24, 29, 34)); setPreferredSize(new Dimension(560, 540));
        setToolTipText("Drag a rectangle; right-click an anchor; middle-drag to pan; wheel to zoom");
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                Point point = world(event.getPoint());
                if (SwingUtilities.isMiddleMouseButton(event)) { lastPan = event.getPoint(); return; }
                if (SwingUtilities.isRightMouseButton(event)) { anchor = point; picked.accept(point); repaint(); return; }
                dragging = point; selection = new Rectangle(point.x, point.y, 1, 1); repaint();
            }
            @Override public void mouseDragged(MouseEvent event) {
                if (lastPan != null) {
                    baseX -= (event.getX() - lastPan.x) / tilePixels;
                    baseY += (event.getY() - lastPan.y) / tilePixels;
                    lastPan = event.getPoint(); repaint(); return;
                }
                if (dragging == null) return;
                Point point = world(event.getPoint());
                selection = new Rectangle(Math.min(dragging.x, point.x), Math.min(dragging.y, point.y),
                        Math.abs(point.x - dragging.x) + 1, Math.abs(point.y - dragging.y) + 1);
                repaint();
            }
            @Override public void mouseReleased(MouseEvent event) {
                lastPan = null;
                if (dragging != null) { dragging = null; selected.accept(selection); }
            }
            @Override public void mouseMoved(MouseEvent event) {
                Point point = world(event.getPoint());
                StringBuilder text = new StringBuilder("X "+point.x+"  Y "+point.y+"  Plane "+plane);
                JsonArray cell = tile(point.x, point.y);
                if (cell != null) text.append("  Height ").append(cell.get(7).getAsInt())
                        .append("  Flags ").append(cell.get(4).getAsInt());
                List<JsonObject> objects = objectsAt(point.x, point.y);
                if (!objects.isEmpty()) text.append("  ").append(objects.get(0).get("name").getAsString());
                hovered.accept(text.toString());
                if (pastePreview) { ghost = new Rectangle(point.x, point.y, ghostWidth, ghostHeight); repaint(); }
            }
            @Override public void mouseWheelMoved(MouseWheelEvent event) {
                Point before = world(event.getPoint());
                tilePixels = Math.max(2, Math.min(28, tilePixels - event.getWheelRotation()));
                baseX = before.x - event.getX()/tilePixels;
                baseY = before.y - (getHeight()-event.getY()-1)/tilePixels;
                repaint();
            }
        };
        addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse);
    }

    public void load(JsonObject response) {
        regions.clear(); underlays.clear(); overlays.clear();
        for(List<JsonObject> rows:planeObjects)rows.clear();
        shapeMasks = response.has("shape_masks") ? response.getAsJsonArray("shape_masks") : null;
        for (Map.Entry<String, JsonElement> row : response.getAsJsonObject("regions").entrySet())
            regions.put(Integer.parseInt(row.getKey()), row.getValue().getAsJsonObject());
        for(JsonObject region:regions.values())for(JsonElement row:region.getAsJsonArray("objects")) {
            JsonObject object=row.getAsJsonObject();planeObjects.get(object.get("plane").getAsInt()).add(object);
        }
        for(List<JsonObject> rows:planeObjects)rows.sort(Comparator.comparingInt(o -> o.get("id").getAsInt()));
        if (response.has("palettes")) {
            for (Map.Entry<String, JsonElement> row : response.getAsJsonObject("palettes").entrySet()) {
                JsonObject palette = row.getValue().getAsJsonObject();
                underlays.put(row.getKey(), colors(palette.getAsJsonArray("underlays")));
                overlays.put(row.getKey(), colors(palette.getAsJsonArray("overlays")));
            }
        }
        repaint();
    }

    private static Map<Integer, Color> colors(JsonArray palette) {
        Map<Integer, Color> result = new HashMap<>(); result.put(0, new Color(46, 49, 42));
        for (JsonElement e : palette) {
            JsonObject row = e.getAsJsonObject(); Color color;
            JsonElement value = row.get("color");
            if (value != null && value.isJsonArray() && value.getAsJsonArray().size() >= 3) {
                JsonArray c = value.getAsJsonArray();
                color = new Color(clamp(c.get(0).getAsInt()), clamp(c.get(1).getAsInt()), clamp(c.get(2).getAsInt()));
            } else color = new Color(row.get("rgb").getAsInt() & 0xffffff);
            result.put(row.get("id").getAsInt(), color);
        }
        return result;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
    public Point world(Point pixel) {
        return new Point(baseX + Math.floorDiv(pixel.x, tilePixels),
                baseY + Math.floorDiv(getHeight()-pixel.y-1, tilePixels));
    }
    public Point screen(int x, int y) { return new Point((x-baseX)*tilePixels, getHeight()-(y-baseY+1)*tilePixels); }

    public JsonArray tile(int x, int y) {
        if (x < 0 || y < 0 || x >= 16384 || y >= 16384) return null;
        JsonObject region = regions.get(((x >> 6) << 8) | (y >> 6));
        return region == null ? null : region.getAsJsonArray("tiles").get(plane*4096+(x & 63)*64+(y & 63)).getAsJsonArray();
    }

    public List<JsonObject> objects() {
        return Collections.unmodifiableList(planeObjects.get(plane));
    }

    public List<JsonObject> objectsAt(int x, int y) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonObject object : objects()) {
            Rectangle footprint = footprint(object);
            if (footprint.contains(x, y)) result.add(object);
        }
        return result;
    }

    public static Rectangle footprint(JsonObject object) {
        int type = object.get("type").getAsInt();
        int width = (type == 9 || type == 10 || type == 11) ? object.get("size_x").getAsInt() : 1;
        int height = (type == 9 || type == 10 || type == 11) ? object.get("size_y").getAsInt() : 1;
        if ((object.get("rotation").getAsInt() & 1) != 0) { int temp = width; width = height; height = temp; }
        return new Rectangle(object.get("x").getAsInt(), object.get("y").getAsInt(), width, height);
    }

    public void preview(int width, int height) { ghostWidth = width; ghostHeight = height; pastePreview = true; repaint(); }
    public void clearPreview() { pastePreview = false; ghost = null; repaint(); }
    public void center(int x, int y) { baseX = x-32; baseY = y-32; anchor = new Point(x,y); repaint(); }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        int nx = getWidth()/tilePixels+1, ny = getHeight()/tilePixels+1;
        for (int dx = 0; dx < nx; dx++) for (int dy = 0; dy < ny; dy++) {
            int x = baseX+dx, y = baseY+dy; JsonArray tile = tile(x,y);
            if (tile == null) continue;
            Color color = floor(underlays, tile.get(5).getAsString(), tile.get(1).getAsInt());
            Point at = screen(x,y); g.setColor(color); g.fillRect(at.x, at.y, tilePixels, tilePixels);
            int overlay = tile.get(2).getAsInt();
            if (overlay != 0) {
                g.setColor(floor(overlays, tile.get(6).getAsString(), overlay));
                // The preview mask uses the same source-owned shaped overlay
                // mask sent by the maintained codec, including reflections.
                int shape = tile.get(3).getAsInt();
                if (shape == 0) g.fillRect(at.x, at.y, tilePixels, tilePixels);
                else if (shapeMasks != null && shape < shapeMasks.size()) {
                    JsonArray mask = shapeMasks.get(shape).getAsJsonArray();
                    for (int i=0;i<16;i++) if (mask.get(i).getAsInt() != 0) {
                        int mx = i & 3, my = i >> 2;
                        int px = at.x+mx*tilePixels/4, py = at.y+my*tilePixels/4;
                        g.fillRect(px,py,(mx+1)*tilePixels/4-mx*tilePixels/4,(my+1)*tilePixels/4-my*tilePixels/4);
                    }
                }
            }
            if ((tile.get(4).getAsInt() & 1) != 0 && tilePixels >= 6) {
                g.setColor(new Color(255,90,65,110)); g.drawLine(at.x,at.y,at.x+tilePixels-1,at.y+tilePixels-1);
            }
        }
        for (JsonObject object : objects()) {
            Rectangle footprint = footprint(object); Point at = screen(footprint.x, footprint.y+footprint.height-1);
            if (at.x > getWidth() || at.y > getHeight() || at.x+footprint.width*tilePixels < 0 || at.y+footprint.height*tilePixels < 0) continue;
            int hash = object.get("source").getAsString().hashCode();
            g.setColor(Color.getHSBColor((hash & 65535)/65536f, 0.45f, 1f));
            g.drawRect(at.x,at.y,footprint.width*tilePixels-1,footprint.height*tilePixels-1);
            int face = object.get("rotation").getAsInt();
            int[] fx = {-1,0,1,0}, fy = {0,-1,0,1};
            int cx = at.x+tilePixels/2, cy = at.y+tilePixels/2;
            g.drawLine(cx,cy,cx+fx[face]*tilePixels/2,cy+fy[face]*tilePixels/2);
        }
        g.setColor(new Color(235,235,240,90));
        for (int x = ((baseX+63)/64)*64; x < baseX+nx; x += 64) {
            int px = screen(x,baseY).x; g.drawLine(px,0,px,getHeight()); g.drawString("X "+x,px+4,14);
        }
        for (int y = ((baseY+63)/64)*64; y < baseY+ny; y += 64) {
            int py = screen(baseX,y).y; g.drawLine(0,py,getWidth(),py); g.drawString("Y "+y,4,py-4);
        }
        drawRectangle(g, selection, new Color(252,209,93));
        drawRectangle(g, ghost, new Color(72,240,158));
        if (anchor != null) {
            Point p = screen(anchor.x,anchor.y); g.setColor(new Color(72,240,158));
            g.drawOval(p.x-4,p.y-4,tilePixels+8,tilePixels+8);
        }
        if (regions.isEmpty()) {
            g.setColor(new Color(192,200,211));
            g.drawString("Choose coordinates and Load to open the map",24,getHeight()/2);
        }
        g.dispose();
    }

    private Color floor(Map<String, Map<Integer, Color>> colors, String source, int id) {
        Map<Integer, Color> rows = colors.get(source);
        return rows == null ? Color.MAGENTA : rows.getOrDefault(id, id == 0 ? new Color(46,49,42) : Color.MAGENTA);
    }
    private void drawRectangle(Graphics2D g, Rectangle rect, Color color) {
        if (rect == null) return;
        Point p = screen(rect.x, rect.y+rect.height-1);
        g.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),45));
        g.fillRect(p.x,p.y,rect.width*tilePixels,rect.height*tilePixels);
        g.setColor(color); g.setStroke(new BasicStroke(2));
        g.drawRect(p.x,p.y,rect.width*tilePixels,rect.height*tilePixels);
    }
}
