package org.polaris2023.relativity.physicalization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class PhysicalizedVolumeHandle {
    private final UUID id;
    private final String dimensionId;
    private final BlockBox box;
    private final long submittedNanos;
    private final List<ChunkOccupancyIndex.SectionPlan> sectionPlans;
    private final int estimatedBlockCount;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.SUBMITTED);

    public PhysicalizedVolumeHandle(
            UUID id,
            String dimensionId,
            BlockBox box,
            long submittedNanos,
            List<ChunkOccupancyIndex.SectionPlan> sectionPlans,
            int estimatedBlockCount
    ) {
        this.id = id;
        this.dimensionId = dimensionId;
        this.box = box;
        this.submittedNanos = submittedNanos;
        this.sectionPlans = List.copyOf(sectionPlans);
        this.estimatedBlockCount = estimatedBlockCount;
    }

    public UUID id() {
        return id;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public BlockBox box() {
        return box;
    }

    public long submittedNanos() {
        return submittedNanos;
    }

    public List<ChunkOccupancyIndex.SectionPlan> sectionPlans() {
        return sectionPlans;
    }

    public int estimatedBlockCount() {
        return estimatedBlockCount;
    }

    public Status status() {
        return status.get();
    }

    public void status(Status next) {
        status.set(next);
    }

    public enum Status {
        SUBMITTED,
        AGGREGATE_READY,
        GENERATING_BODIES,
        COMPLETE,
        FAILED
    }
}
