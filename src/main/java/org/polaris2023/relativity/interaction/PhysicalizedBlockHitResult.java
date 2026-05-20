package org.polaris2023.relativity.interaction;

import net.minecraft.world.phys.BlockHitResult;

public final class PhysicalizedBlockHitResult extends BlockHitResult {
    private final PhysicalizedHit physicalizedHit;

    public PhysicalizedBlockHitResult(PhysicalizedHit hit) {
        super(hit.worldLocation(), hit.worldFace(), hit.visualBlockPos(), false);
        this.physicalizedHit = hit;
    }

    public PhysicalizedHit physicalizedHit() {
        return physicalizedHit;
    }
}
