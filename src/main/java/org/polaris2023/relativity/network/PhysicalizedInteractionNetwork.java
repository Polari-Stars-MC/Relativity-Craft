package org.polaris2023.relativity.network;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeMapping;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
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
                .playToServer(UseCommandPayload.TYPE, UseCommandPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleUseCommand)
                .playToServer(BreakCommandPayload.TYPE, BreakCommandPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleBreakCommand);
    }

    public static void sendSnapshot(PhysicalizedVolumeEntity entity) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new SnapshotPayload(entity.getId(), entity.snapshot()));
    }

    public static void sendBreakOverlay(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell, int progress) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                entity,
                new BreakOverlayPayload(entity.getId(), cell.localX(), cell.localY(), cell.localZ(), progress)
        );
    }

    private static void handleSnapshot(SnapshotPayload payload, IPayloadContext context) {
        Entity entity = context.player().level().getEntity(payload.entityId());
        if (entity instanceof PhysicalizedVolumeEntity volume) {
            volume.receiveSnapshot(payload.snapshot());
        }
    }

    private static void handleBreakOverlay(BreakOverlayPayload payload, IPayloadContext context) {
        Entity entity = context.player().level().getEntity(payload.entityId());
        if (entity instanceof PhysicalizedVolumeEntity volume) {
            volume.setBreakOverlay(payload.localX(), payload.localY(), payload.localZ(), payload.progress());
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
            Optional<PhysicalizedHit> hit = hitFromPayload(
                    player,
                    volume,
                    payload.localX(),
                    payload.localY(),
                    payload.localZ(),
                    payload.localHitX(),
                    payload.localHitY(),
                    payload.localHitZ(),
                    payload.localFace()
            );
            if (hit.isPresent()) {
                PhysicalizedInteractionHandler.continueBreakingHit(player, hit.get());
            } else if (payload.localX() < 0 || payload.localY() < 0 || payload.localZ() < 0) {
                PhysicalizedInteractionHandler.continueBreaking(player, volume);
            }
        }
    }

    private static void handleUseCommand(UseCommandPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Entity entity = player.level().getEntity(payload.entityId());
        if (entity instanceof PhysicalizedVolumeEntity volume) {
            Optional<PhysicalizedHit> hit = hitFromPayload(
                    player,
                    volume,
                    payload.localX(),
                    payload.localY(),
                    payload.localZ(),
                    payload.localHitX(),
                    payload.localHitY(),
                    payload.localHitZ(),
                    payload.localFace()
            );
            if (hit.isPresent()) {
                PhysicalizedInteractionHandler.useHit(player, handById(payload.hand()), hit.get());
            } else if (payload.localX() < 0 || payload.localY() < 0 || payload.localZ() < 0) {
                PhysicalizedInteractionHandler.use(player, handById(payload.hand()), volume);
            }
        }
    }

    private static Optional<PhysicalizedHit> hitFromPayload(
            ServerPlayer player,
            PhysicalizedVolumeEntity volume,
            int localX,
            int localY,
            int localZ,
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

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        Vec3 localHit = new Vec3(localHitX, localHitY, localHitZ);
        Vec3 worldLocation = mapping.centeredLocalToWorld(localHit);
        double distance = player.getEyePosition().distanceTo(worldLocation);
        if (distance > Math.max(4.5, player.blockInteractionRange()) + 1.0) {
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

    public enum BreakAction {
        CONTINUE,
        STOP;

        static BreakAction byId(int id) {
            return id == STOP.ordinal() ? STOP : CONTINUE;
        }
    }

    public record SnapshotPayload(int entityId, PhysicalizedVolumeSnapshot snapshot) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "physicalized_snapshot")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> payload.write(buffer),
                SnapshotPayload::read
        );

        private static SnapshotPayload read(RegistryFriendlyByteBuf buffer) {
            return new SnapshotPayload(buffer.readVarInt(), PhysicalizedVolumeSnapshot.read(buffer));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            snapshot.write(buffer);
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

    public record UseCommandPayload(
            int entityId,
            int hand,
            int localX,
            int localY,
            int localZ,
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
