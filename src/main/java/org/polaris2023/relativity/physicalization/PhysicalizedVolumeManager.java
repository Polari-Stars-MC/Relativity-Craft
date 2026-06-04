package org.polaris2023.relativity.physicalization;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

public final class PhysicalizedVolumeManager {
    private static final PhysicalizedVolumeManager GLOBAL = new PhysicalizedVolumeManager(new ChunkOccupancyIndex(), new VirtualAirMask());

    private final ChunkOccupancyIndex occupancyIndex;
    private final VirtualAirMask virtualAirMask;
    private final ArrayDeque<PhysicalizationJob> jobs = new ArrayDeque<>();
    private long nextHandleId = 1L;

    public PhysicalizedVolumeManager(ChunkOccupancyIndex occupancyIndex, VirtualAirMask virtualAirMask) {
        this.occupancyIndex = occupancyIndex;
        this.virtualAirMask = virtualAirMask;
    }

    public static PhysicalizedVolumeManager global() {
        return GLOBAL;
    }

    public ChunkOccupancyIndex occupancyIndex() {
        return occupancyIndex;
    }

    public VirtualAirMask virtualAirMask() {
        return virtualAirMask;
    }

    public PhysicalizedVolumeHandle submit(String dimensionId, BlockBox box, long submittedNanos) {
        List<ChunkOccupancyIndex.SectionPlan> plans = occupancyIndex.plan(dimensionId, box);
        int estimatedBlocks = 0;
        for (ChunkOccupancyIndex.SectionPlan plan : plans) {
            estimatedBlocks += plan.nonAirCount();
        }
        PhysicalizedVolumeHandle handle = new PhysicalizedVolumeHandle(new UUID(0L, nextHandleId++), dimensionId, box, submittedNanos, plans, estimatedBlocks);
        virtualAirMask.add(handle);
        handle.status(PhysicalizedVolumeHandle.Status.AGGREGATE_READY);
        jobs.add(new PhysicalizationJob(handle));
        return handle;
    }

    public int drainJobsFor(long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        int completed = 0;
        while (System.nanoTime() < deadline) {
            PhysicalizationJob job = jobs.peek();
            if (job == null) {
                break;
            }
            if (job.step(deadline)) {
                jobs.remove();
                completed++;
            } else {
                break;
            }
        }
        return completed;
    }
}
