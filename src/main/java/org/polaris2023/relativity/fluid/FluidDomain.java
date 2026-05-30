package org.polaris2023.relativity.fluid;

import org.polaris2023.relativity.physicalization.ChunkSectionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

public final class FluidDomain {
    private static final int SECTION_SIZE = 16;
    private static final int CELL_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final float FLOW_RESPONSE = 0.09F;
    private static final float VERTICAL_RESPONSE = 0.14F;
    private static final float FLOW_DAMPING = 0.86F;
    private static final float WAVE_RESPONSE = 0.12F;
    private static final float WAVE_DAMPING = 0.90F;
    private static final float MAX_FLOW_SPEED = 4.0F;
    private static final float MAX_WAVE_HEIGHT = 0.35F;

    private final ChunkSectionKey key;
    private final long nativeHandle;
    private final float[] waterLevel = new float[CELL_COUNT];
    private final float[] waveHeight = new float[CELL_COUNT];
    private final float[] flowX = new float[CELL_COUNT];
    private final float[] flowY = new float[CELL_COUNT];
    private final float[] flowZ = new float[CELL_COUNT];
    private long syncedGameTime = Long.MIN_VALUE;
    private int waterCells;

    public FluidDomain(ChunkSectionKey key, long nativeHandle) {
        this.key = key;
        this.nativeHandle = nativeHandle;
    }

    public ChunkSectionKey key() {
        return key;
    }

    public long nativeHandle() {
        return nativeHandle;
    }

    public boolean hasWater(ServerLevel level, long gameTime) {
        sync(level, gameTime);
        return waterCells > 0;
    }

    public WaterCell sample(ServerLevel level, BlockPos pos, long gameTime) {
        sync(level, gameTime);
        int localX = ChunkSectionKey.local(pos.getX());
        int localY = ChunkSectionKey.local(pos.getY());
        int localZ = ChunkSectionKey.local(pos.getZ());
        int index = index(localX, localY, localZ);
        float levelHeight = waterLevel[index];
        if (levelHeight <= 0.0F) {
            return null;
        }

        int worldX = (key.sectionX() << 4) + localX;
        int worldY = key.sectionY() * SECTION_SIZE + localY;
        int worldZ = (key.sectionZ() << 4) + localZ;
        double baseSurfaceY = worldY + levelHeight;
        double oceanWeight = clamp((float) (0.20 + Math.min(1.0, waterCells / 96.0)), 0.20F, 1.0F);
        SimulatedWaterSolver.OceanSurfaceSample simulatedSurface = SimulatedWaterSolver.sample(
                baseSurfaceY,
                worldX + 0.5,
                worldZ + 0.5,
                gameTime,
                oceanWeight,
                waveHeight[index],
                flowX[index] * 0.018F,
                flowZ[index] * 0.018F,
                Math.abs(waveHeight[index]) * 2.2F,
                0.0,
                0.0,
                0.0
        );
        float surfaceHeight = clamp((float) (simulatedSurface.surfaceY() - worldY), 0.0F, 2.25F);
        return new WaterCell(
                worldY + surfaceHeight,
                new Vec3(flowX[index], flowY[index], flowZ[index]),
                surfaceHeight
        );
    }

    public void disturb(BlockPos pos, Vec3 velocity, double displacedVolume) {
        int centerX = ChunkSectionKey.local(pos.getX());
        int centerY = ChunkSectionKey.local(pos.getY());
        int centerZ = ChunkSectionKey.local(pos.getZ());
        float strength = clamp((float) displacedVolume, 0.0F, 6.0F);
        if (strength <= 0.0F) {
            return;
        }

        for (int z = centerZ - 1; z <= centerZ + 1; z++) {
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                if (x < 0 || z < 0 || x >= SECTION_SIZE || z >= SECTION_SIZE || centerY < 0 || centerY >= SECTION_SIZE) {
                    continue;
                }
                int index = index(x, centerY, z);
                if (waterLevel[index] <= 0.0F) {
                    continue;
                }

                float distanceWeight = x == centerX && z == centerZ ? 1.0F : 0.45F;
                flowX[index] = clamp(flowX[index] + (float) velocity.x * 0.045F * strength * distanceWeight, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                flowY[index] = clamp(flowY[index] + (float) velocity.y * 0.025F * strength * distanceWeight, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                flowZ[index] = clamp(flowZ[index] + (float) velocity.z * 0.045F * strength * distanceWeight, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                float verticalImpulse = clamp((float) (-velocity.y * 0.035F * strength), -MAX_WAVE_HEIGHT, MAX_WAVE_HEIGHT);
                waveHeight[index] = clamp(waveHeight[index] + verticalImpulse * distanceWeight, -MAX_WAVE_HEIGHT, MAX_WAVE_HEIGHT);
            }
        }
    }

    private void sync(ServerLevel level, long gameTime) {
        if (syncedGameTime == gameTime) {
            return;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        waterCells = 0;
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int index = index(x, y, z);
                    pos.set(
                            (key.sectionX() << 4) + x,
                            (key.sectionY() << 4) + y,
                            (key.sectionZ() << 4) + z
                    );
                    FluidState fluid = level.getFluidState(pos);
                    if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
                        waterLevel[index] = clamp((float) fluid.getHeight(level, pos), 0.0F, 1.0F);
                        Vec3 flow = fluid.getFlow(level, pos);
                        flowX[index] = clamp(flowX[index] * 0.82F + (float) flow.x * 0.18F, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                        flowY[index] = clamp(flowY[index] * 0.82F + (float) flow.y * 0.18F, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                        flowZ[index] = clamp(flowZ[index] * 0.82F + (float) flow.z * 0.18F, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                        waveHeight[index] = clamp(waveHeight[index] * WAVE_DAMPING, -MAX_WAVE_HEIGHT, MAX_WAVE_HEIGHT);
                        waterCells++;
                    } else {
                        waterLevel[index] = 0.0F;
                        waveHeight[index] = 0.0F;
                        flowX[index] = 0.0F;
                        flowY[index] = 0.0F;
                        flowZ[index] = 0.0F;
                    }
                }
            }
        }

        relaxFlowField();
        syncedGameTime = gameTime;
    }

    private void relaxFlowField() {
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int index = index(x, y, z);
                    float current = waterLevel[index];
                    if (current <= 0.0F) {
                        continue;
                    }

                    float left = waterAt(x - 1, y, z, current);
                    float right = waterAt(x + 1, y, z, current);
                    float down = waterAt(x, y - 1, z, current);
                    float up = waterAt(x, y + 1, z, current);
                    float north = waterAt(x, y, z - 1, current);
                    float south = waterAt(x, y, z + 1, current);
                    float averageWave = (
                            waveAt(x - 1, y, z)
                                    + waveAt(x + 1, y, z)
                                    + waveAt(x, y, z - 1)
                                    + waveAt(x, y, z + 1)
                    ) * 0.25F;

                    flowX[index] = clamp(flowX[index] + (left - right) * FLOW_RESPONSE, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                    flowY[index] = clamp(flowY[index] + (down - up) * VERTICAL_RESPONSE, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                    flowZ[index] = clamp(flowZ[index] + (north - south) * FLOW_RESPONSE, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                    waveHeight[index] = clamp(waveHeight[index] + (averageWave - waveHeight[index]) * WAVE_RESPONSE, -MAX_WAVE_HEIGHT, MAX_WAVE_HEIGHT);

                    flowX[index] *= FLOW_DAMPING;
                    flowY[index] *= FLOW_DAMPING;
                    flowZ[index] *= FLOW_DAMPING;
                }
            }
        }
    }

    private float waterAt(int x, int y, int z, float fallback) {
        if (x < 0 || y < 0 || z < 0 || x >= SECTION_SIZE || y >= SECTION_SIZE || z >= SECTION_SIZE) {
            return fallback;
        }
        return waterLevel[index(x, y, z)];
    }

    private float waveAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= SECTION_SIZE || y >= SECTION_SIZE || z >= SECTION_SIZE) {
            return 0.0F;
        }
        return waveHeight[index(x, y, z)];
    }

    private static int index(int x, int y, int z) {
        return x + z * SECTION_SIZE + y * SECTION_SIZE * SECTION_SIZE;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public record WaterCell(double surfaceY, Vec3 flow, float fillLevel) {
    }
}
