package org.polaris2023.relativity.network;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
import org.polaris2023.relativity.interaction.PhysicalizedRaycaster;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeMapping;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

public final class PhysicalizedInteractionNetwork {
    private PhysicalizedInteractionNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(SnapshotPayload.TYPE, SnapshotPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleSnapshot)
                .playToClient(BreakOverlayPayload.TYPE, BreakOverlayPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleBreakOverlay)
                .playToServer(HitContextPayload.TYPE, HitContextPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleHitContext)
                .playToServer(UseCommandPayload.TYPE, UseCommandPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleUseCommand)
                .playToServer(BreakCommandPayload.TYPE, BreakCommandPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleBreakCommand);
    }

    public static void sendSnapshot(PhysicalizedVolumeEntity entity) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new SnapshotPayload(
                entity.getId(),
                entity.snapshot(),
                entity.getX(),
                entity.physicsCenterY(),
                entity.getZ(),
                entity.rotationQx(),
                entity.rotationQy(),
                entity.rotationQz(),
                entity.rotationQw()
        ));
    }

    public static void sendBreakOverlay(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell, int progress) {
        sendBreakOverlay(entity, cell.localX(), cell.localY(), cell.localZ(), progress);
    }

    public static void sendBreakOverlay(PhysicalizedVolumeEntity entity, int localX, int localY, int localZ, int progress) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                entity,
                new BreakOverlayPayload(entity.getId(), localX, localY, localZ, progress)
        );
    }

    private static void handleSnapshot(SnapshotPayload payload, IPayloadContext context) {
        Entity entity = context.player().level().getEntity(payload.entityId());
        if (entity instanceof PhysicalizedVolumeEntity volume) {
            volume.receiveSnapshot(payload.snapshot());
            volume.snapNativeSnapshot(
                    payload.centerX(),
                    payload.centerY(),
                    payload.centerZ(),
                    payload.qx(),
                    payload.qy(),
                    payload.qz(),
                    payload.qw()
            );
        }
    }

    private static void handleBreakOverlay(BreakOverlayPayload payload, IPayloadContext context) {
        Entity entity = context.player().level().getEntity(payload.entityId());
        if (entity instanceof PhysicalizedVolumeEntity volume) {
            volume.setBreakOverlay(payload.localX(), payload.localY(), payload.localZ(), payload.progress());
        }
    }

    private static void handleHitContext(HitContextPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            PhysicalizedInteractionHints.global().remember(player, payload);
        }
    }

    private static void handleBreakCommand(BreakCommandPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Entity entity = player.level().getEntity(payload.entityId());
        if (!(entity instanceof PhysicalizedVolumeEntity volume)) {
            return;
        }
        BreakAction action = BreakAction.byId(payload.action());
        if (action == BreakAction.STOP) {
            PhysicalizedInteractionHandler.stopBreaking(player, volume);
        } else {
            hitFromPayload(player, volume, payload.localX(), payload.localY(), payload.localZ(), payload.stateId(), payload.blockCount(), payload.localHitX(), payload.localHitY(), payload.localHitZ(), payload.localFace())
                    .ifPresent(hit -> PhysicalizedInteractionHandler.continueBreakingHit(player, hit));
        }
    }

    private static void handleUseCommand(UseCommandPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Entity entity = player.level().getEntity(payload.entityId());
        if (entity instanceof PhysicalizedVolumeEntity volume) {
            hitFromPayload(player, volume, payload.localX(), payload.localY(), payload.localZ(), payload.stateId(), payload.blockCount(), payload.localHitX(), payload.localHitY(), payload.localHitZ(), payload.localFace())
                    .ifPresentOrElse(
                            hit -> PhysicalizedInteractionHandler.useHit(player, handById(payload.hand()), hit),
                            () -> PhysicalizedInteractionHandler.use(player, handById(payload.hand()), volume)
                    );
        }
    }

    private static Optional<PhysicalizedHit> hitFromPayload(
            ServerPlayer player,
            PhysicalizedVolumeEntity volume,
            int localX,
            int localY,
            int localZ,
            int stateId,
            int blockCount,
            double localHitX,
            double localHitY,
            double localHitZ,
            int localFaceId
    ) {
        if (localX < 0 || localY < 0 || localZ < 0 || volume.isRemoved() || volume.level() != player.level()) {
            return Optional.empty();
        }

        PhysicalizedBlockSnapshot cell = volume.snapshot().cellAt(localX, localY, localZ).orElse(null);
        if (cell == null || cell.state().isAir()) {
            return Optional.empty();
        }
        if (stateId >= 0 && cell.stateId() != stateId) {
            return Optional.empty();
        }
        if (blockCount >= 0 && volume.snapshot().blockCount() != blockCount) {
            return Optional.empty();
        }

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        Vec3 localHit = new Vec3(localHitX, localHitY, localHitZ);
        Vec3 worldLocation = mapping.centeredLocalToWorld(localHit);
        double distance = player.getEyePosition().distanceTo(worldLocation);
        if (distance > PhysicalizedRaycaster.interactionReach(player) + 1.0) {
            return Optional.empty();
        }

        Direction localFace = Direction.from3DDataValue(localFaceId);
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

    private static InteractionHand handById(int id) {
        return id == InteractionHand.OFF_HAND.ordinal() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    public enum HitIntent {
        USE,
        BREAK;

        static HitIntent byId(int id) {
            return id == BREAK.ordinal() ? BREAK : USE;
        }
    }

    public enum BreakAction {
        CONTINUE,
        STOP;

        static BreakAction byId(int id) {
            return id == STOP.ordinal() ? STOP : CONTINUE;
        }
    }

    public record SnapshotPayload(
            int entityId,
            PhysicalizedVolumeSnapshot snapshot,
            double centerX,
            double centerY,
            double centerZ,
            float qx,
            float qy,
            float qz,
            float qw
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "physicalized_snapshot")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> payload.write(buffer),
                SnapshotPayload::read
        );

        private static SnapshotPayload read(RegistryFriendlyByteBuf buffer) {
            int entityId = buffer.readVarInt();
            PhysicalizedVolumeSnapshot snapshot = PhysicalizedVolumeSnapshot.read(buffer);
            return new SnapshotPayload(
                    entityId,
                    snapshot,
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            snapshot.write(buffer);
            buffer.writeDouble(centerX);
            buffer.writeDouble(centerY);
            buffer.writeDouble(centerZ);
            buffer.writeFloat(qx);
            buffer.writeFloat(qy);
            buffer.writeFloat(qz);
            buffer.writeFloat(qw);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BreakOverlayPayload(int entityId, int localX, int localY, int localZ, int progress) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BreakOverlayPayload> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "physicalized_break_overlay")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, BreakOverlayPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> payload.write(buffer),
                BreakOverlayPayload::read
        );

        private static BreakOverlayPayload read(RegistryFriendlyByteBuf buffer) {
            return new BreakOverlayPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeVarInt(localX);
            buffer.writeVarInt(localY);
            buffer.writeVarInt(localZ);
            buffer.writeVarInt(progress);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record HitContextPayload(
            int entityId,
            int intent,
            int hand,
            BlockPos visualBlockPos,
            int localX,
            int localY,
            int localZ,
            int stateId,
            int blockCount,
            double localHitX,
            double localHitY,
            double localHitZ,
            int localFace
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HitContextPayload> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "physicalized_hit_context")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, HitContextPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> payload.write(buffer),
                HitContextPayload::read
        );

        private static HitContextPayload read(RegistryFriendlyByteBuf buffer) {
            return new HitContextPayload(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBlockPos(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readVarInt()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeVarInt(intent);
            buffer.writeVarInt(hand);
            buffer.writeBlockPos(visualBlockPos);
            buffer.writeVarInt(localX);
            buffer.writeVarInt(localY);
            buffer.writeVarInt(localZ);
            buffer.writeVarInt(stateId);
            buffer.writeVarInt(blockCount);
            buffer.writeDouble(localHitX);
            buffer.writeDouble(localHitY);
            buffer.writeDouble(localHitZ);
            buffer.writeVarInt(localFace);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record UseCommandPayload(
            int entityId,
            int hand,
            int localX,
            int localY,
            int localZ,
            int stateId,
            int blockCount,
            double localHitX,
            double localHitY,
            double localHitZ,
            int localFace
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UseCommandPayload> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "physicalized_use_command")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, UseCommandPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> payload.write(buffer),
                UseCommandPayload::read
        );

        private static UseCommandPayload read(RegistryFriendlyByteBuf buffer) {
            return new UseCommandPayload(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readVarInt()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeVarInt(hand);
            buffer.writeVarInt(localX);
            buffer.writeVarInt(localY);
            buffer.writeVarInt(localZ);
            buffer.writeVarInt(stateId);
            buffer.writeVarInt(blockCount);
            buffer.writeDouble(localHitX);
            buffer.writeDouble(localHitY);
            buffer.writeDouble(localHitZ);
            buffer.writeVarInt(localFace);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BreakCommandPayload(
            int entityId,
            int action,
            int localX,
            int localY,
            int localZ,
            int stateId,
            int blockCount,
            double localHitX,
            double localHitY,
            double localHitZ,
            int localFace
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BreakCommandPayload> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "physicalized_break_command")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, BreakCommandPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> payload.write(buffer),
                BreakCommandPayload::read
        );

        private static BreakCommandPayload read(RegistryFriendlyByteBuf buffer) {
            return new BreakCommandPayload(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readVarInt()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeVarInt(action);
            buffer.writeVarInt(localX);
            buffer.writeVarInt(localY);
            buffer.writeVarInt(localZ);
            buffer.writeVarInt(stateId);
            buffer.writeVarInt(blockCount);
            buffer.writeDouble(localHitX);
            buffer.writeDouble(localHitY);
            buffer.writeDouble(localHitZ);
            buffer.writeVarInt(localFace);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
