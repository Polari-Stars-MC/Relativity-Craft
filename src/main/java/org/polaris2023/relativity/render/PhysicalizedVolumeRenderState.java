package org.polaris2023.relativity.render;

import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

import java.util.List;

public final class PhysicalizedVolumeRenderState extends EntityRenderState {
    public float sizeX = 1.0F;
    public float sizeY = 1.0F;
    public float sizeZ = 1.0F;
    public float localOriginX = 0.5F;
    public float localOriginY = 0.5F;
    public float localOriginZ = 0.5F;
    public float qx = 0.0F;
    public float qy = 0.0F;
    public float qz = 0.0F;
    public float qw = 1.0F;
    public float previousQx = 0.0F;
    public float previousQy = 0.0F;
    public float previousQz = 0.0F;
    public float previousQw = 1.0F;
    public int blockCount;
    public String volumeId = "";
    public List<PhysicalizedBlockSnapshot> cells = List.of();
    public ClientLevel clientLevel;
    public int breakLocalX = -1;
    public int breakLocalY = -1;
    public int breakLocalZ = -1;
    public int breakProgress = -1;
}
