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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.PasteAction;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.actions.mapmode.SelectAction;
import org.openstreetmap.josm.command.PurgeCommand;
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
import org.openstreetmap.josm.gui.MapFrame.MapModeChangeListener;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Predicate;


public class LayerMoverPlugin extends Plugin
implements MapModeChangeListener,  MapViewPaintable, AWTEventListener, MouseMotionListener, MouseListener {
    List<OsmDataLayer> sourceLayers = new ArrayList<OsmDataLayer>(2);
    private OsmPrimitive highlighted = null;
    private boolean highlightChanged;
    private boolean hookset = false;
    private boolean hookActive = false;
    private boolean ctrlShiftDown = false;

    private MapView mv;
    private SelectAction hookedMapMode;
    private Cursor oldCursor;
    private final Cursor cursor;

    public LayerMoverPlugin(PluginInformation info) {
        super(info);
        cursor = getCursor();
        MapFrame.addMapModeChangeListener(this);
    }

    @Override
    public void mapModeChange(MapMode oldMapMode, MapMode newMapMode) {
        if (newMapMode instanceof SelectAction)
            setupHook();
        else
            unsetupHook();
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

    private void updateSourceLayers() {
        sourceLayers.clear();
        List<OsmDataLayer> layers = mv.getLayersOfType(OsmDataLayer.class);
        for (OsmDataLayer layer : layers) {
            if (layer.isVisible() && layer != mv.getEditLayer()) {
                sourceLayers.add(layer);
                break;
            }
        }
    }

    public void activateHook() {
        // sanity checks
        if (hookActive) return;
        if (Main.map == null || Main.map.mapView == null) return;
        if (!(Main.map.mapMode instanceof SelectAction)) return;
        mv = Main.map.mapView;

        updateSourceLayers();
        if (sourceLayers.isEmpty()) {
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
        sourceLayers.clear();
        hookActive = false;
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        updateHighlighting(e.getPoint());
        if (highlightChanged) Main.map.repaint();
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        DataSet destLayer = Main.main.getCurrentDataSet();
        if (destLayer == null) return;
        updateHighlighting(e.getPoint());
        if (highlighted == null) return;

        moveHighlighted();
    }

    private void moveHighlighted() {
        // Paste object into new layer
        PrimitiveDeepCopy copy = new PrimitiveDeepCopy(Collections.singleton(highlighted));
        new PasteAction().pasteData(copy, (Layer)null, new ActionEvent(this, 0, ""));

        // Purge object from old layer
        OsmDataLayer highlightLayer = null;
        for (OsmDataLayer layer : sourceLayers) {
            if (layer.data.getPrimitiveById(highlighted.getPrimitiveId()) == highlighted) {
                highlightLayer = layer;
            }
        }
        if (highlightLayer == null) return;

        Set<OsmPrimitive> toPurge = new HashSet<OsmPrimitive>();
        Set<OsmPrimitive> makeIncomplete = new HashSet<OsmPrimitive>();
        List<OsmPrimitive> referrers = highlighted.getReferrers();
        toPurge.add(highlighted);
        if (!referrers.isEmpty()) makeIncomplete.add(highlighted);
        if (highlighted instanceof Node) {
            for (OsmPrimitive ref : referrers) {
                if (ref instanceof Way || (ref instanceof Relation && highlighted.isNew()))
                    return; // Don't purge if object is node and it has referrers
            }
        } else if (highlighted instanceof Way) {
            // Purge all child nodes that aren't referenced by other ways
            NODE:
                for (Node n : ((Way)highlighted).getNodes()) {
                    for (OsmPrimitive ref : n.getReferrers()) {
                        if (ref == highlighted) continue;
                        if (ref instanceof Way || (ref instanceof Relation && n.isNew())) {
                            makeIncomplete.remove(n);
                            continue NODE;
                        }
                        makeIncomplete.add(n);
                    }
                    toPurge.add(n);
                }
        }
        highlighted = null;
        Main.main.undoRedo.add(new PurgeCommand(highlightLayer, toPurge, makeIncomplete));
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
        for (OsmDataLayer layer : sourceLayers) {
            for (Node n : layer.data.searchNodes(getBBox(p, snapDistance))) {
                if (predicate.evaluate(n) && n.isTagged() && mv.getPoint(n).distanceSq(p) < nearestDistanceSq){
                    result = n;
                    nearestDistanceSq = mv.getPoint(n).distanceSq(p);
                }
            }
        }
        if (result != null) return result;

        for (OsmDataLayer layer : sourceLayers) {
            for (Way w : layer.data.searchWays(getBBox(p, NavigatableComponent.PROP_SNAP_DISTANCE.get()))) {
                if (!predicate.evaluate(w)) continue;
                Node lastN = null;
                for (Node n : w.getNodes()) {
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

    @Override
    public void eventDispatched(AWTEvent event) {
        if (!(event instanceof KeyEvent))
            return;
        KeyEvent ev = (KeyEvent)event;
        int modifiers = ev.getModifiersEx();
        boolean ctrlShiftDown = (~modifiers & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK)) == 0;
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
