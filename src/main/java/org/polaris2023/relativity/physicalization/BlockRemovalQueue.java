package org.polaris2023.relativity.physicalization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;

public final class BlockRemovalQueue {
    private static final BlockRemovalQueue GLOBAL = new BlockRemovalQueue();
    private static final int REMOVE_FLAGS = Block.UPDATE_CLIENTS
            | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    private final Map<String, ArrayDeque<BlockRemovalJob>> jobsByDimension = new Object2ObjectOpenHashMap<>();

    private BlockRemovalQueue() {
    }

    public static BlockRemovalQueue global() {
        return GLOBAL;
    }

    public void enqueue(String dimensionId, BlockBox box) {
        enqueue(dimensionId, box, null);
    }

    public void enqueue(String dimensionId, BlockBox box, UUID virtualAirMaskId) {
        jobsByDimension.computeIfAbsent(dimensionId, ignored -> new ArrayDeque<>())
                .add(new BlockRemovalJob(box, virtualAirMaskId));
    }

    public int drain(ServerLevel level, long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        String dimensionId = level.dimension().identifier().toString();
        ArrayDeque<BlockRemovalJob> jobs = jobsByDimension.get(dimensionId);
        if (jobs == null) {
            return 0;
        }

        int completed = 0;

        while (System.nanoTime() < deadline) {
            BlockRemovalJob job = jobs.peek();
            if (job == null) {
                jobsByDimension.remove(dimensionId);
                break;
            }
            if (job.step(level, deadline)) {
                jobs.remove();
                completed++;
            } else {
                break;
            }
        }
        if (jobs.isEmpty()) {
            jobsByDimension.remove(dimensionId);
        }
        return completed;
    }

    private static final class BlockRemovalJob {
        private final BlockBox box;
        private final UUID virtualAirMaskId;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private final long sizeXZ;
        private final long volume;
        private long cursor;

        BlockRemovalJob(BlockBox box, UUID virtualAirMaskId) {
            this.box = box;
            this.virtualAirMaskId = virtualAirMaskId;
            this.sizeXZ = (long) box.sizeX() * box.sizeZ();
            this.volume = box.volume();
        }

        boolean step(ServerLevel level, long deadlineNanos) {
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
