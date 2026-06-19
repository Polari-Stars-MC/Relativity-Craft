package org.polaris2023.relativity.enclave;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Adapts {@link BlockStorage} to Minecraft's {@link BlockAndTintGetter}.
 *
 * <p>Provides block state queries from palette-compressed sections so the
 * vanilla {@code ModelBlockRenderer} can tessellate blocks for rendering.
 * Lighting uses a simplified model where sky light is always max and block
 * light comes from emissive blocks only.</p>
 */
public final class EnclaveBlockView implements BlockAndTintGetter {

    private final BlockStorage storage;
    private final int originX, originY, originZ;
    private final int maxX, maxY, maxZ;

    public EnclaveBlockView(BlockStorage storage) {
        this.storage = storage;
        this.originX = storage.originX();
        this.originY = storage.originY();
        this.originZ = storage.originZ();
        this.maxX = storage.boundX();
        this.maxY = storage.boundY();
        this.maxZ = storage.boundZ();
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return CardinalLighting.DEFAULT;
    }

    public float getShade(Direction direction, boolean shade) {
        return direction == Direction.UP ? 1.0F : 0.8F;
    }

    @Override
    public @Nullable LevelLightEngine getLightEngine() {
        return null;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null; // Enclaves don't provide block entities to the mesh builder
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return -1; // no tint
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (x < originX || x > maxX || y < originY || y > maxY || z < originZ || z > maxZ) {
            return Blocks.AIR.defaultBlockState();
        }
        return storage.get(x, y, z);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        if (layer == LightLayer.SKY) return 15;
        BlockState st = getBlockState(pos);
        return st.getLightEmission();
    }

    @Override
    public int getRawBrightness(BlockPos pos, int skyDarken) {
        int sky = 15 - skyDarken;
        int block = getBlockState(pos).getLightEmission();
        return Math.max(sky, block);
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return true;
    }

    @Override
    public int getHeight() {
        return maxY - originY + 1;
    }

    @Override
    public int getMinY() {
        return originY;
    }
}
