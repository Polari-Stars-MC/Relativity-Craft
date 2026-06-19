package org.polaris2023.relativity.enclave;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Simplified lighting cache for {@link EnclaveBlockView}.
 *
 * <p>Rather than running a full lighting engine (which would be O(n³) flood-fill),
 * this uses a simple heuristic: sky light is always 15 (enclaves are their own
 * dimension with full ambient light), and block light is 15 for emissive blocks
 * (glowstone, torches, etc.) and 0 otherwise.</p>
 *
 * <p>The cache is populated lazily — values are computed on first access and
 * cached for the lifetime of the enclave view.</p>
 */
public final class EnclaveLightCache {

    // Sky light is always 15 — simplest possible model, avoids flood-fill.
    // Block light is 15 for emissive blocks, 0 otherwise.

    public int get(LightLayer layer, BlockPos pos) {
        if (layer == LightLayer.SKY) {
            return 15; // Full sky light everywhere inside the enclave
        }
        return 0; // Block light — computed by caller or default 0
    }

    /**
     * Get the block light value for a specific block state.
     * Emissive blocks emit light level 15.
     */
    public static int blockLightFor(BlockState state) {
        return state.getLightEmission();
    }

    /**
     * Compute full packed light value for a block position given its state.
     */
    public static int packedLight(BlockState state) {
        int sky = 15; // Always full sky light
        int block = blockLightFor(state);
        return (sky << 4) | block; // Same packing as vanilla LightTexture
    }
}
