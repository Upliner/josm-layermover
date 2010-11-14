package layermover;

import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;


public class LayerMoverPlugin extends Plugin {

    public LayerMoverPlugin(PluginInformation info) {
        super(info);
    }

    @Override public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame!=null) {
            newFrame.addMapMode(new IconToggleButton(new LayerMoveAction(newFrame)));
        }
    }

}
