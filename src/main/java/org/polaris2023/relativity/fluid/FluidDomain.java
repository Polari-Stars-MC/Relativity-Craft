package org.polaris2023.relativity.fluid;

import org.polaris2023.relativity.physicalization.ChunkSectionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public final class FluidDomain {
    private static final int SECTION_SIZE = 16;
    private static final int CELL_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int DRY_RECHECK_TICKS = 8;
    private static final float VANILLA_FLOW_BLEND = 0.18F;
    private static final float FLOW_PRESSURE_RESPONSE = 0.105F;
    private static final float VERTICAL_PRESSURE_RESPONSE = 0.055F;
    private static final float FLOW_DAMPING = 0.88F;
    private static final float VERTICAL_FLOW_DAMPING = 0.82F;
    private static final float WAVE_NEIGHBOR_RESPONSE = 0.22F;
    private static final float WAVE_RESTORATION = 0.045F;
    private static final float FLOW_TO_WAVE_RESPONSE = 0.018F;
    private static final float WAVE_VELOCITY_DAMPING = 0.92F;
    private static final float WAVE_HEIGHT_DAMPING = 0.985F;
    private static final float ACTIVE_FLOW_EPSILON = 0.018F;
    private static final float ACTIVE_WAVE_EPSILON = 0.006F;
    private static final float MAX_FLOW_SPEED = 4.5F;
    private static final float MAX_WAVE_HEIGHT = 0.55F;
    private static final float MAX_WAVE_VELOCITY = 0.34F;

    private final ChunkSectionKey key;
    private final long nativeHandle;
    private final float[] waterLevel = new float[CELL_COUNT];
    private final float[] waveHeight = new float[CELL_COUNT];
    private final float[] waveVelocity = new float[CELL_COUNT];
    private final float[] nextWaveHeight = new float[CELL_COUNT];
    private final float[] nextWaveVelocity = new float[CELL_COUNT];
    private final float[] flowX = new float[CELL_COUNT];
    private final float[] flowY = new float[CELL_COUNT];
    private final float[] flowZ = new float[CELL_COUNT];
    private final int[] activeIndices = new int[CELL_COUNT];
    private long syncedGameTime = Long.MIN_VALUE;
    private long nextDryScanGameTime = Long.MIN_VALUE;
    private int waterCells;
    private int activeCells;

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

    public void markDirty() {
        syncedGameTime = Long.MIN_VALUE;
        nextDryScanGameTime = Long.MIN_VALUE;
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
        int worldY = (key.sectionY() << 4) + localY;
        int worldZ = (key.sectionZ() << 4) + localZ;
        float localSurface = levelHeight + waveHeight[index];
        float slopeX = (surfacePotentialAt(localX + 1, localY, localZ, localSurface)
                - surfacePotentialAt(localX - 1, localY, localZ, localSurface)) * 0.5F;
        float slopeZ = (surfacePotentialAt(localX, localY, localZ + 1, localSurface)
                - surfacePotentialAt(localX, localY, localZ - 1, localSurface)) * 0.5F;
        float horizontalFlow = (float) Math.sqrt(flowX[index] * flowX[index] + flowZ[index] * flowZ[index]);
        double baseSurfaceY = worldY + levelHeight;
        double oceanWeight = clamp(0.18F + Math.min(0.72F, waterCells / 128.0F) + Math.min(0.18F, activeCells / 512.0F), 0.18F, 1.0F);
        double localFoam = clamp(
                Math.abs(waveHeight[index]) * 1.7F
                        + Math.abs(waveVelocity[index]) * 2.2F
                        + horizontalFlow * 0.055F,
                0.0F,
                1.0F
        );
        SimulatedWaterSolver.OceanSurfaceSample simulatedSurface = SimulatedWaterSolver.sample(
                baseSurfaceY,
                worldX + 0.5,
                worldZ + 0.5,
                gameTime,
                oceanWeight,
                waveHeight[index],
                slopeX,
                slopeZ,
                localFoam,
                clamp(horizontalFlow * 0.045F, 0.0F, 0.75F),
                flowX[index],
                flowZ[index]
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
        float strength = clamp((float) displacedVolume, 0.0F, 8.0F);
        if (strength <= 0.0F) {
            return;
        }

        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float invHorizontalSpeed = horizontalSpeed <= 1.0E-5F ? 0.0F : 1.0F / horizontalSpeed;
        float dirX = (float) velocity.x * invHorizontalSpeed;
        float dirZ = (float) velocity.z * invHorizontalSpeed;
        float radius = clamp(1.0F + (float) Math.sqrt(strength) * 0.55F + horizontalSpeed * 0.18F, 1.0F, 3.25F);
        int reach = (int) Math.ceil(radius);
        float radiusSqr = radius * radius;

        for (int y = centerY - 1; y <= centerY + 1; y++) {
            if (y < 0 || y >= SECTION_SIZE) {
                continue;
            }
            for (int z = centerZ - reach; z <= centerZ + reach; z++) {
                for (int x = centerX - reach; x <= centerX + reach; x++) {
                    if (x < 0 || z < 0 || x >= SECTION_SIZE || z >= SECTION_SIZE) {
                        continue;
                    }
                    int index = index(x, y, z);
                    if (waterLevel[index] <= 0.0F) {
                        continue;
                    }

                    float dx = x - centerX;
                    float dy = (y - centerY) * 1.45F;
                    float dz = z - centerZ;
                    float distanceSqr = dx * dx + dy * dy + dz * dz;
                    if (distanceSqr > radiusSqr) {
                        continue;
                    }

                    float weight = (float) Math.exp(-distanceSqr / Math.max(0.01F, radiusSqr));
                    float directional = horizontalSpeed <= 1.0E-5F ? 0.0F : (dx * dirX + dz * dirZ) / Math.max(1.0F, radius);
                    float bow = clamp(directional, 0.0F, 1.0F);
                    float wake = clamp(-directional, 0.0F, 1.0F);
                    float impulse = strength * weight * (0.72F + bow * 0.38F + wake * 0.18F);

                    flowX[index] = clamp(flowX[index] + (float) velocity.x * 0.047F * impulse, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                    flowY[index] = clamp(flowY[index] + (float) velocity.y * 0.020F * impulse, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
                    flowZ[index] = clamp(flowZ[index] + (float) velocity.z * 0.047F * impulse, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);

                    float verticalImpulse = (float) (-velocity.y * 0.040F + horizontalSpeed * (0.020F + wake * 0.012F)) * impulse;
                    waveVelocity[index] = clamp(waveVelocity[index] + verticalImpulse, -MAX_WAVE_VELOCITY, MAX_WAVE_VELOCITY);
                    waveHeight[index] = clamp(waveHeight[index] + verticalImpulse * 0.35F, -MAX_WAVE_HEIGHT, MAX_WAVE_HEIGHT);
                }
            }
        }
    }

    private void sync(ServerLevel level, long gameTime) {
        if (syncedGameTime == gameTime) {
            return;
        }
        if (waterCells == 0 && syncedGameTime != Long.MIN_VALUE && gameTime < nextDryScanGameTime) {
            syncedGameTime = gameTime;
            return;
        }

        scanVanillaWater(level);
        if (waterCells <= 0) {
            nextDryScanGameTime = gameTime + DRY_RECHECK_TICKS;
            syncedGameTime = gameTime;
            return;
        }

        buildActiveSet();
        integrateActiveCells();
        syncedGameTime = gameTime;
    }

    private void scanVanillaWater(ServerLevel level) {
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
                        waterCells++;
                    } else if (waterLevel[index] != 0.0F
                            || waveHeight[index] != 0.0F
                            || waveVelocity[index] != 0.0F
                            || flowX[index] != 0.0F
                            || flowY[index] != 0.0F
                            || flowZ[index] != 0.0F) {
                        clearCell(index);
                    }
                }
            }
        }
        blendVanillaFlow();
    }

    private void blendVanillaFlow() {
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int index = index(x, y, z);
                    if (waterLevel[index] <= 0.0F) {
                        continue;
                    }

                    float center = waterLevel[index];
                    float estimatedX = waterAt(x - 1, y, z) - waterAt(x + 1, y, z);
                    float estimatedY = waterAt(x, y - 1, z) - waterAt(x, y + 1, z);
                    float estimatedZ = waterAt(x, y, z - 1) - waterAt(x, y, z + 1);
                    if (isOpenAtBoundary(x, Direction.WEST)) {
                        estimatedX += center;
                    }
                    if (isOpenAtBoundary(x, Direction.EAST)) {
                        estimatedX -= center;
                    }
                    if (isOpenAtBoundary(y, Direction.DOWN)) {
                        estimatedY += center;
                    }
                    if (isOpenAtBoundary(y, Direction.UP)) {
                        estimatedY -= center;
                    }
                    if (isOpenAtBoundary(z, Direction.NORTH)) {
                        estimatedZ += center;
                    }
                    if (isOpenAtBoundary(z, Direction.SOUTH)) {
                        estimatedZ -= center;
                    }

                    flowX[index] = blendFlow(flowX[index], estimatedX);
                    flowY[index] = blendFlow(flowY[index], estimatedY);
                    flowZ[index] = blendFlow(flowZ[index], estimatedZ);
                }
            }
        }
    }

    private static boolean isOpenAtBoundary(int coordinate, Direction direction) {
        return coordinate == 0 && direction.getAxisDirection() == Direction.AxisDirection.NEGATIVE
                || coordinate == SECTION_SIZE - 1 && direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
    }

    private static float blendFlow(float current, float estimated) {
        return clamp(current * (1.0F - VANILLA_FLOW_BLEND) + estimated * VANILLA_FLOW_BLEND, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
    }

    private void buildActiveSet() {
        activeCells = 0;
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int index = index(x, y, z);
                    if (waterLevel[index] <= 0.0F) {
                        continue;
                    }
                    if (isSurfaceCell(x, y, z) || hasEnergy(index)) {
                        activeIndices[activeCells++] = index;
                    }
                }
            }
        }
    }

    private void integrateActiveCells() {
        for (int active = 0; active < activeCells; active++) {
            int index = activeIndices[active];
            int y = index / (SECTION_SIZE * SECTION_SIZE);
            int local = index - y * SECTION_SIZE * SECTION_SIZE;
            int z = local / SECTION_SIZE;
            int x = local - z * SECTION_SIZE;
            float surface = waterLevel[index] + waveHeight[index];

            float left = surfacePotentialAt(x - 1, y, z, surface);
            float right = surfacePotentialAt(x + 1, y, z, surface);
            float down = surfacePotentialAt(x, y - 1, z, surface);
            float up = surfacePotentialAt(x, y + 1, z, surface);
            float north = surfacePotentialAt(x, y, z - 1, surface);
            float south = surfacePotentialAt(x, y, z + 1, surface);

            flowX[index] = clamp((flowX[index] + (left - right) * FLOW_PRESSURE_RESPONSE) * FLOW_DAMPING, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
            flowY[index] = clamp((flowY[index] + (down - up) * VERTICAL_PRESSURE_RESPONSE) * VERTICAL_FLOW_DAMPING, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);
            flowZ[index] = clamp((flowZ[index] + (north - south) * FLOW_PRESSURE_RESPONSE) * FLOW_DAMPING, -MAX_FLOW_SPEED, MAX_FLOW_SPEED);

            float waveLaplacian = (waveAt(x - 1, y, z)
                    + waveAt(x + 1, y, z)
                    + waveAt(x, y, z - 1)
                    + waveAt(x, y, z + 1)) * 0.25F - waveHeight[index];
            float flowDivergence = (flowComponentXAt(x - 1, y, z) - flowComponentXAt(x + 1, y, z)
                    + flowComponentZAt(x, y, z - 1) - flowComponentZAt(x, y, z + 1)) * 0.5F;
            float nextVelocity = waveVelocity[index]
                    + waveLaplacian * WAVE_NEIGHBOR_RESPONSE
                    - waveHeight[index] * WAVE_RESTORATION
                    + flowDivergence * FLOW_TO_WAVE_RESPONSE;
            nextVelocity = clamp(nextVelocity * WAVE_VELOCITY_DAMPING, -MAX_WAVE_VELOCITY, MAX_WAVE_VELOCITY);
            nextWaveVelocity[index] = nextVelocity;
            nextWaveHeight[index] = clamp((waveHeight[index] + nextVelocity) * WAVE_HEIGHT_DAMPING, -MAX_WAVE_HEIGHT, MAX_WAVE_HEIGHT);
        }

        for (int active = 0; active < activeCells; active++) {
            int index = activeIndices[active];
            waveVelocity[index] = nextWaveVelocity[index];
            waveHeight[index] = nextWaveHeight[index];
        }
    }

    private boolean isSurfaceCell(int x, int y, int z) {
        return y == SECTION_SIZE - 1 || waterAt(x, y + 1, z) <= 0.0F;
    }

    private boolean hasEnergy(int index) {
        return Math.abs(waveHeight[index]) > ACTIVE_WAVE_EPSILON
                || Math.abs(waveVelocity[index]) > ACTIVE_WAVE_EPSILON
                || Math.abs(flowX[index]) + Math.abs(flowY[index]) + Math.abs(flowZ[index]) > ACTIVE_FLOW_EPSILON;
    }

    private void clearCell(int index) {
        waterLevel[index] = 0.0F;
        waveHeight[index] = 0.0F;
        waveVelocity[index] = 0.0F;
        nextWaveHeight[index] = 0.0F;
        nextWaveVelocity[index] = 0.0F;
        flowX[index] = 0.0F;
        flowY[index] = 0.0F;
        flowZ[index] = 0.0F;
    }

    private float surfacePotentialAt(int x, int y, int z, float fallback) {
        if (x < 0 || y < 0 || z < 0 || x >= SECTION_SIZE || y >= SECTION_SIZE || z >= SECTION_SIZE) {
            return fallback;
        }
        int index = index(x, y, z);
        if (waterLevel[index] <= 0.0F) {
            return fallback;
        }
        return waterLevel[index] + waveHeight[index];
    }

    private float waterAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= SECTION_SIZE || y >= SECTION_SIZE || z >= SECTION_SIZE) {
            return 0.0F;
        }
        return waterLevel[index(x, y, z)];
    }

    private float waveAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= SECTION_SIZE || y >= SECTION_SIZE || z >= SECTION_SIZE) {
            return 0.0F;
        }
        return waveHeight[index(x, y, z)];
    }

    private float flowComponentXAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= SECTION_SIZE || y >= SECTION_SIZE || z >= SECTION_SIZE) {
            return 0.0F;
        }
        return flowX[index(x, y, z)];
    }

    private float flowComponentZAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= SECTION_SIZE || y >= SECTION_SIZE || z >= SECTION_SIZE) {
            return 0.0F;
        }
        return flowZ[index(x, y, z)];
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
