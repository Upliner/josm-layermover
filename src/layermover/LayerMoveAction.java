package layermover;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.PasteAction;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveDeepCopy;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.visitor.AbstractVisitor;
import org.openstreetmap.josm.data.osm.visitor.paint.MapPaintSettings;
import org.openstreetmap.josm.data.osm.visitor.paint.PaintColors;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Predicate;
import org.openstreetmap.josm.tools.Shortcut;

public class LayerMoveAction extends MapMode implements MapViewPaintable {
    DataSet sourceLayer;
    private OsmPrimitive highlighted = null;
    private boolean highlightChanged;
   
    public LayerMoveAction(MapFrame mapFrame) {
        super(tr("Calque"), "calque", tr("Move objects from inactive layer to active"),
                Shortcut.registerShortcut("mapmode:calque",
                        tr("Mode: {0}", tr("Calque")),
                        KeyEvent.VK_K, Shortcut.GROUP_EDIT),
                mapFrame, getCursor());
    }

    private static Cursor getCursor() {
        try {
            return ImageProvider.getCursor("crosshair", null);
        } catch (Exception e) {
        }
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    @Override
    public void enterMode() {
        super.enterMode();
        List<OsmDataLayer> layers = Main.map.mapView.getLayersOfType(OsmDataLayer.class);
        for (OsmDataLayer layer : layers) {
            if (layer.isVisible() && layer != Main.map.mapView.getEditLayer()) {
                sourceLayer = layer.data;
                break;
            }
        }
        Main.map.mapView.addMouseMotionListener(this);
        Main.map.mapView.addMouseListener(this);
        Main.map.mapView.addTemporaryLayer(this);
    }

    @Override
    public void exitMode() {
        super.exitMode();
        Main.map.mapView.removeTemporaryLayer(this);
        Main.map.mapView.removeMouseListener(this);
        Main.map.mapView.removeMouseMotionListener(this);
        sourceLayer = null;
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        super.mouseMoved(e);
        if (sourceLayer == null) return;
        updateHighlighting(e.getPoint());
        if (highlightChanged) Main.map.repaint();
    }
    

    @Override
    public void mouseClicked(MouseEvent e) {
        super.mouseClicked(e);
        if (sourceLayer == null) return;
        DataSet destLayer = Main.main.getCurrentDataSet();
        if (destLayer == null) return;
        updateHighlighting(e.getPoint());
        if (highlighted == null) return;
        
        PrimitiveDeepCopy copy = new PrimitiveDeepCopy(Collections.singleton(highlighted));
        new PasteAction().pasteData(copy, (Layer)null, new ActionEvent(this, 0, ""));
    }

    @Override
    public boolean layerIsSupported(Layer l) {
        return l instanceof OsmDataLayer;
    }
    private BBox getBBox(Point p, int snapDistance) {
        return new BBox(Main.map.mapView.getLatLon(p.x - snapDistance, p.y - snapDistance),
                Main.map.mapView.getLatLon(p.x + snapDistance, p.y + snapDistance));
    }
    private OsmPrimitive getNearestSourceLayerPrimitive(Point p, Predicate<OsmPrimitive> predicate) {
        OsmPrimitive result = null;
        int snapDistance = NavigatableComponent.PROP_SNAP_DISTANCE.get();
        double nearestDistanceSq = snapDistance*snapDistance;
        
        MapView mv = Main.map.mapView;
        for (Node n : sourceLayer.searchNodes(getBBox(p, snapDistance))) {
            if (predicate.evaluate(n) && mv.getPoint(n).distanceSq(p) < nearestDistanceSq){
                result = n;
                nearestDistanceSq = mv.getPoint(n).distanceSq(p);
            }
        }
        if (result != null) return result;

        for (Way w : sourceLayer.searchWays(getBBox(p, NavigatableComponent.PROP_SNAP_DISTANCE.get()))) {
            if (!predicate.evaluate(w)) continue;
            Node lastN = null;
            for (Node n : w.getNodes()) {
                if (n.isDeleted() || n.isIncomplete()) { //FIXME: This shouldn't happen, raise exception?
                    continue;
                }
                if (lastN == null) {
                    lastN = n;
                    continue;
                }

                Point2D A = mv.getPoint2D(lastN);
                Point2D B = mv.getPoint2D(n);
                double c = A.distanceSq(B);
                double a = p.distanceSq(B);
                double b = p.distanceSq(A);

                double perDistSq = a - (a - b + c) * (a - b + c) / 4 / c;

                if (perDistSq < nearestDistanceSq && a < c + nearestDistanceSq && b < c + nearestDistanceSq) {
                    result = w;
                    nearestDistanceSq = perDistSq; 
                    break;
                }
                lastN = n;
            }
        }

        return result;
    }

    private void updateHighlighting(Point point) {
        OsmPrimitive newHighlight = getNearestSourceLayerPrimitive(point, OsmPrimitive.isUsablePredicate);
        if (highlighted != newHighlight) {
            highlighted = newHighlight;
            highlightChanged = true;
        }
    }
    
    @Override
    public void paint(final Graphics2D g, final MapView mv, final Bounds bbox) {
        if (highlighted == null) {
            highlightChanged = false;
            return;
        }
        g.setColor(PaintColors.HIGHLIGHT.get());
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        final int nodeSize = MapPaintSettings.INSTANCE.getSelectedNodeSize();
        highlighted.visit(new AbstractVisitor() {
            @Override
            public void visit(Node n) {
                Point p = mv.getPoint(n);
                g.fillRect(p.x - nodeSize/2, p.y - nodeSize/2, nodeSize, nodeSize);
            }
            @Override
            public void visit(Way w) {
                List<Node> nodes = w.getNodes();
                if (nodes.isEmpty()) return;
                GeneralPath path = new GeneralPath();
                Iterator<Node> iterator = nodes.iterator();
                Point2D point = mv.getPoint2D(iterator.next());
                path.moveTo(point.getX(), point.getY());
                while (iterator.hasNext()) {
                    point = mv.getPoint2D(iterator.next());
                    path.lineTo(point.getX(), point.getY());
                }
                g.draw(path);
            }
            @Override
            public void visit(Relation e) {
            }
        });
        highlightChanged = false;
    }
}
