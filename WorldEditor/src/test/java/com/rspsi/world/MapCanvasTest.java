package com.rspsi.world;
import org.junit.Test;
import java.awt.*;
import javax.swing.*;
import static org.junit.Assert.*;
import static com.rspsi.world.BridgeClient.object;

public class MapCanvasTest {
    @Test public void picksWorldCoordinatesWithNorthAtTheTopAtEveryZoom() {
        MapCanvas canvas = new MapCanvas(); canvas.setSize(500,500); canvas.baseX=3260; canvas.baseY=3260;
        for (int zoom : new int[]{2,7,28}) {
            canvas.tilePixels=zoom;
            Point screen=canvas.screen(3263,3265);
            assertEquals(new Point(3263,3265),canvas.world(new Point(screen.x+zoom/2,screen.y+zoom/2)));
        }
        assertEquals(new Point(3259,3259),canvas.world(new Point(-1,500)));
    }
    @Test public void rotatedFurnitureKeepsItsWholeFootprint() {
        assertEquals(new Rectangle(100,200,3,2),MapCanvas.footprint(object("x",100,"y",200,"type",10,"rotation",1,"size_x",2,"size_y",3)));
        assertEquals(new Rectangle(100,200,1,1),MapCanvas.footprint(object("x",100,"y",200,"type",0,"rotation",1,"size_x",2,"size_y",3)));
    }
    @Test public void commandKeepsPathsAndArgumentsLiteral() {
        assertEquals("/folder with spaces/bridge.py",BridgeClient.command("[\"python3\",\"/folder with spaces/bridge.py\"]").get(1));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsAnEmptyCommand() { BridgeClient.command("[]"); }
    @Test public void narrowToolbarsReserveSpaceForEveryWrappedRow() {
        JPanel toolbar=new JPanel(new WorldEditor.WrapLayout());
        for(int i=0;i<4;i++) {
            JButton button=new JButton("Action");button.setPreferredSize(new Dimension(100,25));toolbar.add(button);
        }
        toolbar.setSize(230,100);
        assertEquals(62,toolbar.getPreferredSize().height);
        toolbar.setSize(450,100);
        assertEquals(33,toolbar.getPreferredSize().height);
    }
}
