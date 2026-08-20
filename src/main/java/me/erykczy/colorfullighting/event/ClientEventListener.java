package me.erykczy.colorfullighting.event;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.EntityLightManager;
import me.erykczy.colorfullighting.common.ViewArea;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

public class ClientEventListener {
    @SubscribeEvent
    private void onTick(ClientTickEvent.Post event) {
        if(ColorfulLighting.clientAccessor == null) return; // ticks can fire before FMLLoadComplete
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        if(engine == null) return;
        var player = ColorfulLighting.clientAccessor.getPlayer();
        if(player == null) return;
        ChunkPos pos = player.getChunkPos();
        int renderDistance = ColorfulLighting.clientAccessor.getRenderDistance();
        ViewArea viewArea = new ViewArea(
                pos.x - renderDistance,
                pos.z - renderDistance,
                pos.x + renderDistance,
                pos.z + renderDistance
        );
        engine.updateViewArea(viewArea);

        // track entity light sources: diffs positions/colors and enqueues updates
        EntityLightManager.tick(engine);

        // apply finished propagation results and mark render sections dirty; driven from
        // the tick instead of LevelLightEngine.runLightUpdates so it keeps working when
        // a light-engine replacement (e.g. ScalableLux/Starlight) overrides that method
        engine.onLightUpdate();

    }

    @SubscribeEvent
    private void onLevelUnload(LevelEvent.Unload event) {
        if(!event.getLevel().isClientSide()) return;
        if(ColorfulLighting.clientAccessor == null) return;
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        if(engine == null) return;
        engine.reset();
    }
}
