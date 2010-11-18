package layermover;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.actions.mapmode.SelectAction;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapFrame.MapModeChangeListener;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;


public class LayerMoverPlugin extends Plugin implements MapModeChangeListener  {
    SelectMapModeHook hook = new SelectMapModeHook();
    public LayerMoverPlugin(PluginInformation info) {
        super(info);
        MapFrame.addMapModeChangeListener(this);
    }

    @Override
    public void mapModeChange(MapMode oldMapMode, MapMode newMapMode) {
        if (newMapMode instanceof SelectAction)
            hook.setupHook();
        else
            hook.unsetupHook();
    }

}
