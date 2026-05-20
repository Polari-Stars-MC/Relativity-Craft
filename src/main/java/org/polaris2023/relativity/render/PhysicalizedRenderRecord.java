package org.polaris2023.relativity.render;

import org.polaris2023.relativity.physicalization.BlockBox;

import java.util.UUID;

public record PhysicalizedRenderRecord(UUID volumeId, String blockStateId, BlockBox sourceBox, long meshGeneration) {
}
