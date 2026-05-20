package org.polaris2023.relativity.physicalization;

import java.util.Iterator;

public final class PhysicalizationJob {
    private final PhysicalizedVolumeHandle handle;
    private final Iterator<ChunkOccupancyIndex.SectionPlan> sections;
    private int generatedBodies;

    public PhysicalizationJob(PhysicalizedVolumeHandle handle) {
        this.handle = handle;
        this.sections = handle.sectionPlans().iterator();
    }

    public PhysicalizedVolumeHandle handle() {
        return handle;
    }

    public int generatedBodies() {
        return generatedBodies;
    }

    public boolean step(long deadlineNanos) {
        handle.status(PhysicalizedVolumeHandle.Status.GENERATING_BODIES);
        while (System.nanoTime() < deadlineNanos && sections.hasNext()) {
            ChunkOccupancyIndex.SectionPlan section = sections.next();
            generatedBodies += section.nonAirCount();
        }

        if (!sections.hasNext()) {
            handle.status(PhysicalizedVolumeHandle.Status.COMPLETE);
            return true;
        }
        return false;
    }
}
