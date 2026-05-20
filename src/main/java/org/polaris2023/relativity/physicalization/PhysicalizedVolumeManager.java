package org.polaris2023.relativity.physicalization;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class PhysicalizedVolumeManager {
    private static final PhysicalizedVolumeManager GLOBAL = new PhysicalizedVolumeManager(new ChunkOccupancyIndex(), new VirtualAirMask());

    private final ChunkOccupancyIndex occupancyIndex;
    private final VirtualAirMask virtualAirMask;
    private final Queue<PhysicalizationJob> jobs = new ConcurrentLinkedQueue<>();
    private final AtomicLong nextHandleId = new AtomicLong(1L);

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
        int estimatedBlocks = plans.stream().mapToInt(ChunkOccupancyIndex.SectionPlan::nonAirCount).sum();
        PhysicalizedVolumeHandle handle = new PhysicalizedVolumeHandle(new UUID(0L, nextHandleId.getAndIncrement()), dimensionId, box, submittedNanos, plans, estimatedBlocks);
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
                jobs.poll();
                completed++;
            } else {
                break;
            }
        }
        return completed;
    }
}
