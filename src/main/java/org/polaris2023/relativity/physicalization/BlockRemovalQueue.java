package org.polaris2023.relativity.physicalization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class BlockRemovalQueue {
    private static final BlockRemovalQueue GLOBAL = new BlockRemovalQueue();
    private static final int REMOVE_FLAGS = Block.UPDATE_CLIENTS
            | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    private final Queue<BlockRemovalJob> jobs = new ConcurrentLinkedQueue<>();

    private BlockRemovalQueue() {
    }

    public static BlockRemovalQueue global() {
        return GLOBAL;
    }

    public void enqueue(String dimensionId, BlockBox box) {
        jobs.add(new BlockRemovalJob(dimensionId, box, null));
    }

    public void enqueue(String dimensionId, BlockBox box, UUID virtualAirMaskId) {
        jobs.add(new BlockRemovalJob(dimensionId, box, virtualAirMaskId));
    }

    public int drain(ServerLevel level, long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        String dimensionId = level.dimension().identifier().toString();
        int completed = 0;

        for (BlockRemovalJob job : jobs) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            if (!job.dimensionId().equals(dimensionId)) {
                continue;
            }
            if (job.step(level, deadline)) {
                jobs.remove(job);
                completed++;
            }
        }
        return completed;
    }

    private static final class BlockRemovalJob {
        private final String dimensionId;
        private final BlockBox box;
        private final UUID virtualAirMaskId;
        private final long sizeXZ;
        private final long volume;
        private long cursor;

        BlockRemovalJob(String dimensionId, BlockBox box, UUID virtualAirMaskId) {
            this.dimensionId = dimensionId;
            this.box = box;
            this.virtualAirMaskId = virtualAirMaskId;
            this.sizeXZ = (long) box.sizeX() * box.sizeZ();
            this.volume = box.volume();
        }

        String dimensionId() {
            return dimensionId;
        }

        boolean step(ServerLevel level, long deadlineNanos) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            while (cursor < volume && System.nanoTime() < deadlineNanos) {
                long index = cursor++;
                int x = box.minX() + (int) (index % box.sizeX());
                int z = box.minZ() + (int) ((index / box.sizeX()) % box.sizeZ());
                int y = box.minY() + (int) (index / sizeXZ);
                pos.set(x, y, z);
                if (!level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS);
                }
            }
            if (cursor < volume) {
                return false;
            }
            if (virtualAirMaskId != null) {
                PhysicalizedVolumeManager.global().virtualAirMask().remove(virtualAirMaskId);
            }
            return true;
        }
    }
}
