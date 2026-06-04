package org.polaris2023.relativity.selection;

import org.polaris2023.relativity.physicalization.BlockBox;
import net.minecraft.core.BlockPos;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import java.util.UUID;

public final class SelectionManager {
    private static final SelectionManager GLOBAL = new SelectionManager();

    private final Map<UUID, SelectionState> selections = new Object2ObjectOpenHashMap<>();

    public static SelectionManager global() {
        return GLOBAL;
    }

    public void setFirstCorner(UUID playerId, BlockPos pos) {
        selections.compute(playerId, (ignored, existing) -> {
            SelectionState state = existing == null ? new SelectionState() : existing;
            state.first = pos.immutable();
            return state;
        });
    }

    public void setSecondCorner(UUID playerId, BlockPos pos) {
        selections.compute(playerId, (ignored, existing) -> {
            SelectionState state = existing == null ? new SelectionState() : existing;
            state.second = pos.immutable();
            return state;
        });
    }

    public BlockBox selectionFor(UUID playerId) {
        SelectionState state = selections.get(playerId);
        if (state == null || state.first == null || state.second == null) {
            return null;
        }
        return BlockBox.of(
                state.first.getX(), state.first.getY(), state.first.getZ(),
                state.second.getX(), state.second.getY(), state.second.getZ()
        );
    }

    private static final class SelectionState {
        private BlockPos first;
        private BlockPos second;
    }
}
