package org.polaris2023.relativity.network;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.interaction.PhysicalizedRaycaster;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeMapping;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PhysicalizedInteractionHints {
    private static final PhysicalizedInteractionHints GLOBAL = new PhysicalizedInteractionHints();
    private static final int MAX_HINT_AGE_TICKS = 3;
    private static final double EVENT_MATCH_EPSILON = 1.0E-3;

    private final Map<UUID, RememberedHit> hits = new ConcurrentHashMap<>();

    private PhysicalizedInteractionHints() {
    }

    public static PhysicalizedInteractionHints global() {
        return GLOBAL;
    }

    public void remember(ServerPlayer player, PhysicalizedInteractionNetwork.HitContextPayload payload) {
        hits.put(player.getUUID(), new RememberedHit(payload, player.level().getGameTime()));
    }

    public Optional<PhysicalizedHit> recentHit(ServerPlayer player, BlockPos eventPos, PhysicalizedInteractionNetwork.HitIntent intent) {
        RememberedHit remembered = hits.get(player.getUUID());
        if (remembered == null || player.level().getGameTime() - remembered.gameTime() > MAX_HINT_AGE_TICKS) {
            return Optional.empty();
        }

        PhysicalizedInteractionNetwork.HitContextPayload payload = remembered.payload();
        if (PhysicalizedInteractionNetwork.HitIntent.byId(payload.intent()) != intent) {
            return Optional.empty();
        }

        Entity entity = player.level().getEntity(payload.entityId());
        if (!(entity instanceof PhysicalizedVolumeEntity volume) || volume.isRemoved() || volume.level() != player.level()) {
            return Optional.empty();
        }
        if (payload.blockCount() >= 0 && volume.snapshot().blockCount() != payload.blockCount()) {
            return Optional.empty();
        }

        PhysicalizedBlockSnapshot cell = volume.snapshot().cellAt(payload.localX(), payload.localY(), payload.localZ()).orElse(null);
        if (cell == null || cell.state().isAir() || cell.stateId() != payload.stateId()) {
            return Optional.empty();
        }

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        Vec3 localHit = new Vec3(payload.localHitX(), payload.localHitY(), payload.localHitZ());
        Vec3 worldLocation = mapping.centeredLocalToWorld(localHit);
        double distance = player.getEyePosition().distanceTo(worldLocation);
        double reach = PhysicalizedRaycaster.interactionReach(player) + 1.5;
        if (distance > reach && !matchesAuthoritativeRay(player, volume, cell, reach)) {
            return Optional.empty();
        }
        if (!matchesEventPos(player, payload.visualBlockPos(), eventPos, distance)) {
            return Optional.empty();
        }

        Direction localFace = Direction.from3DDataValue(payload.localFace());
        return Optional.of(new PhysicalizedHit(
                volume,
                cell,
                worldLocation,
                localHit,
                mapping.localFaceToWorld(localFace),
                localFace,
                mapping.visualBlockPos(cell),
                distance
        ));
    }

    private static boolean matchesEventPos(ServerPlayer player, BlockPos visualBlockPos, BlockPos eventPos, double hitDistance) {
        if (visualBlockPos.equals(eventPos)) {
            return true;
        }
        Vec3 origin = player.getEyePosition();
        return hitDistance * hitDistance + EVENT_MATCH_EPSILON < origin.distanceToSqr(Vec3.atCenterOf(eventPos));
    }

    private static boolean matchesAuthoritativeRay(ServerPlayer player, PhysicalizedVolumeEntity volume, PhysicalizedBlockSnapshot cell, double reach) {
        return PhysicalizedRaycaster.raycastInteractionEntity(volume, player.getEyePosition(), player.getLookAngle().normalize(), reach)
                .map(PhysicalizedHit::cell)
                .filter(hitCell -> sameCell(hitCell, cell))
                .isPresent();
    }

    private static boolean sameCell(PhysicalizedBlockSnapshot left, PhysicalizedBlockSnapshot right) {
        return left.localX() == right.localX()
                && left.localY() == right.localY()
                && left.localZ() == right.localZ()
                && left.stateId() == right.stateId();
    }

    private record RememberedHit(PhysicalizedInteractionNetwork.HitContextPayload payload, long gameTime) {
    }
}
