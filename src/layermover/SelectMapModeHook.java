package layermover;

import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.PasteAction;
import org.openstreetmap.josm.actions.mapmode.SelectAction;
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
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Predicate;

public class SelectMapModeHook implements MapViewPaintable, AWTEventListener, MouseMotionListener, MouseListener {
    DataSet sourceLayer;
    private OsmPrimitive highlighted = null;
    private boolean highlightChanged;
    private boolean hookset = false;
    private boolean hookActive = false;
    private MapView mv;
    private SelectAction hookedMapMode;
    private Cursor oldCursor;
    private final Cursor cursor;

    public SelectMapModeHook() {
        cursor = getCursor();
    }

    public void setupHook() {
        if (hookset) return;
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.KEY_EVENT_MASK);
        } catch (SecurityException ex) {
        }
        hookset = true;
    }

    public void unsetupHook() {
        if (!hookset) return;
        deactivateHook();
        ctrlShiftDown = false;
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        } catch (SecurityException ex) {
        }
        hookset = false;
    }

    private static Cursor getCursor() {
        try {
            return ImageProvider.getCursor("crosshair", null);
        } catch (Exception e) {
        }
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    public void activateHook() {
        // sanity checks
        if (hookActive) return;
        if (Main.map == null || Main.map.mapView == null) return;
        if (!(Main.map.mapMode instanceof SelectAction)) return;
        mv = Main.map.mapView;

        List<OsmDataLayer> layers = mv.getLayersOfType(OsmDataLayer.class);
        for (OsmDataLayer layer : layers) {
            if (layer.isVisible() && layer != mv.getEditLayer()) {
                sourceLayer = layer.data;
                break;
            }
        }
        if (sourceLayer == null) {
            mv = null;
            return;
        }
        hookedMapMode = (SelectAction)Main.map.mapMode;
        mv.removeMouseMotionListener(hookedMapMode);
        mv.removeMouseListener(hookedMapMode);
        oldCursor = mv.getCursor();
        mv.setCursor(cursor);

        mv.addMouseMotionListener(this);
        mv.addMouseListener(this);
        mv.addTemporaryLayer(this);
        hookActive = true;
    }

    public void deactivateHook() {
        if (!hookActive) return;

        mv.removeTemporaryLayer(this);
        mv.removeMouseListener(this);
        mv.removeMouseMotionListener(this);

        if (Main.map != null && Main.map.mapView == mv && Main.map.mapMode == hookedMapMode) {
            mv.addMouseMotionListener(hookedMapMode);
            mv.addMouseListener(hookedMapMode);
            mv.setCursor(oldCursor);
        }
        if (highlighted != null) {
            highlighted = null;
            if (Main.map != null) Main.map.repaint();
        }

        mv = null;
        oldCursor = null;
        hookedMapMode = null;
        sourceLayer = null;
        hookActive = false;
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (sourceLayer == null) return;
        updateHighlighting(e.getPoint());
        if (highlightChanged) Main.map.repaint();
    }


    @Override
    public void mouseClicked(MouseEvent e) {
        if (sourceLayer == null) return;
        DataSet destLayer = Main.main.getCurrentDataSet();
        if (destLayer == null) return;
        updateHighlighting(e.getPoint());
        if (highlighted == null) return;

        PrimitiveDeepCopy copy = new PrimitiveDeepCopy(Collections.singleton(highlighted));
        new PasteAction().pasteData(copy, (Layer)null, new ActionEvent(this, 0, ""));
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

    private boolean ctrlShiftDown = false;
    @Override
    public void eventDispatched(AWTEvent event) {
        if (!(event instanceof KeyEvent))
            return;
        KeyEvent ev = (KeyEvent)event;
        int modifiers = ev.getModifiersEx();
        boolean ctrlShiftDown = (modifiers & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK)) != 0;
        if (ctrlShiftDown && !this.ctrlShiftDown) {
            activateHook();
        } else if (this.ctrlShiftDown && !ctrlShiftDown) {
            deactivateHook();
        }
        this.ctrlShiftDown = ctrlShiftDown;
    }

    @Override
    public void mousePressed(MouseEvent e) {
    }

    @Override
    public void mouseReleased(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {

    }
    @Override
    public void mouseDragged(MouseEvent e) {
    }
}
