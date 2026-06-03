package org.polaris2023.relativity.network;

import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedHit;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
import org.polaris2023.relativity.interaction.PhysicalizedRaycaster;
import org.polaris2023.relativity.interaction.PhysicalizedVolumeMapping;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.TagValueOutput;
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
                .playToClient(ContainerOpenPayload.TYPE, ContainerOpenPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleContainerOpen)
                .playToServer(UseCommandPayload.TYPE, UseCommandPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleUseCommand)
                .playToServer(BreakCommandPayload.TYPE, BreakCommandPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleBreakCommand)
                .playToServer(PickCommandPayload.TYPE, PickCommandPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handlePickCommand);
    }

    public static void sendSnapshot(PhysicalizedVolumeEntity entity) {
        Vec3 entityCenter = entity.entityCenter();
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                entity,
                new SnapshotPayload(
                        entity.getId(),
                        entity.snapshot(),
                        entity.localOriginX(),
                        entity.localOriginY(),
                        entity.localOriginZ(),
                        entityCenter.x,
                        entityCenter.y,
                        entityCenter.z,
                        entity.rotationQx(),
                        entity.rotationQy(),
                        entity.rotationQz(),
                        entity.rotationQw()
                )
        );
    }

    public static void sendBreakOverlay(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell, int progress) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                entity,
                new BreakOverlayPayload(entity.getId(), cell.localX(), cell.localY(), cell.localZ(), progress)
        );
    }

    public static void sendContainerOpen(PhysicalizedVolumeEntity entity, PhysicalizedBlockSnapshot cell, int openCount) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                entity,
                new ContainerOpenPayload(entity.getId(), cell.localX(), cell.localY(), cell.localZ(), openCount)
        );
    }

    private static void handleSnapshot(SnapshotPayload payload, IPayloadContext context) {
        Entity entity = context.player().level().getEntity(payload.entityId());
        if (entity instanceof PhysicalizedVolumeEntity volume) {
            volume.receiveSnapshotAtPose(
                    payload.snapshot(),
                    new Vec3(payload.localOriginX(), payload.localOriginY(), payload.localOriginZ()),
                    new Vec3(payload.entityCenterX(), payload.entityCenterY(), payload.entityCenterZ()),
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

    private static void handleContainerOpen(ContainerOpenPayload payload, IPayloadContext context) {
        Entity entity = context.player().level().getEntity(payload.entityId());
        if (entity instanceof PhysicalizedVolumeEntity volume) {
            volume.setVirtualContainerOpenCount(payload.localX(), payload.localY(), payload.localZ(), payload.openCount());
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
            ).or(() -> fallbackHit(player, volume));
            if (hit.isPresent()) {
                PhysicalizedInteractionHandler.continueBreakingHit(player, hit.get());
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
            ).or(() -> fallbackHit(player, volume));
            if (hit.isPresent()) {
                if (!PhysicalizedInteractionHandler.useHit(player, handById(payload.hand()), hit.get()).consumesAction()) {
                    sendSnapshot(volume);
                }
            } else {
                sendSnapshot(volume);
            }
        }
    }

    private static void handlePickCommand(PickCommandPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Entity entity = player.level().getEntity(payload.entityId());
        if (!(entity instanceof PhysicalizedVolumeEntity volume)) {
            return;
        }
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
        ).or(() -> fallbackHit(player, volume));
        if (hit.isEmpty()) {
            return;
        }

        ItemStack stack = pickStack(player, hit.get(), payload.includeData());
        tryPickItem(player, stack);
    }

    private static ItemStack pickStack(ServerPlayer player, PhysicalizedHit hit, boolean includeData) {
        boolean includeBlockEntityData = includeData && player.hasInfiniteMaterials();
        ItemStack stack = hit.cell().state().getCloneItemStack(player.level(), hit.visualBlockPos(), includeBlockEntityData);
        if (stack.isEmpty() || !includeBlockEntityData || !hit.cell().hasLoadableBlockEntityNbt()) {
            return stack;
        }

        BlockEntity blockEntity = BlockEntity.loadStatic(
                hit.visualBlockPos(),
                hit.cell().state(),
                hit.cell().blockEntityNbt(),
                player.level().registryAccess()
        );
        if (blockEntity == null || !(stack.getItem() instanceof BlockItem)) {
            return stack;
        }

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.level().registryAccess());
        blockEntity.saveCustomOnly(output);
        BlockItem.setBlockEntityData(stack, blockEntity.getType(), output);
        return stack;
    }

    private static void tryPickItem(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty() || !stack.isItemEnabled(player.level().enabledFeatures())) {
            return;
        }

        Inventory inventory = player.getInventory();
        int slot = inventory.findSlotMatchingItem(stack);
        if (slot != -1) {
            if (Inventory.isHotbarSlot(slot)) {
                inventory.setSelectedSlot(slot);
            } else {
                inventory.pickSlot(slot);
            }
        } else if (player.hasInfiniteMaterials()) {
            inventory.addAndPickItem(stack);
        }

        player.connection.send(new ClientboundSetHeldSlotPacket(inventory.getSelectedSlot()));
        player.inventoryMenu.broadcastChanges();
    }

    private static Optional<PhysicalizedHit> fallbackHit(ServerPlayer player, PhysicalizedVolumeEntity volume) {
        return PhysicalizedRaycaster.raycastEntity(
                volume,
                player.getEyePosition(),
                player.getLookAngle().normalize(),
                Math.max(4.5, player.blockInteractionRange())
        );
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

    public record SnapshotPayload(
            int entityId,
            PhysicalizedVolumeSnapshot snapshot,
            double localOriginX,
            double localOriginY,
            double localOriginZ,
            double entityCenterX,
            double entityCenterY,
            double entityCenterZ,
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
            return new SnapshotPayload(
                    buffer.readVarInt(),
                    PhysicalizedVolumeSnapshot.read(buffer),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
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
            buffer.writeDouble(localOriginX);
            buffer.writeDouble(localOriginY);
            buffer.writeDouble(localOriginZ);
            buffer.writeDouble(entityCenterX);
            buffer.writeDouble(entityCenterY);
            buffer.writeDouble(entityCenterZ);
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

    public record ContainerOpenPayload(int entityId, int localX, int localY, int localZ, int openCount) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ContainerOpenPayload> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "physicalized_container_open")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, ContainerOpenPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> payload.write(buffer),
                ContainerOpenPayload::read
        );

        private static ContainerOpenPayload read(RegistryFriendlyByteBuf buffer) {
            return new ContainerOpenPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeVarInt(localX);
            buffer.writeVarInt(localY);
            buffer.writeVarInt(localZ);
            buffer.writeVarInt(openCount);
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

    public record PickCommandPayload(
            int entityId,
            int localX,
            int localY,
            int localZ,
            double localHitX,
            double localHitY,
            double localHitZ,
            int localFace,
            boolean includeData
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PickCommandPayload> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "physicalized_pick_command")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, PickCommandPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> payload.write(buffer),
                PickCommandPayload::read
        );

        private static PickCommandPayload read(RegistryFriendlyByteBuf buffer) {
            return new PickCommandPayload(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readVarInt(),
                    buffer.readBoolean()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeVarInt(localX);
            buffer.writeVarInt(localY);
            buffer.writeVarInt(localZ);
            buffer.writeDouble(localHitX);
            buffer.writeDouble(localHitY);
            buffer.writeDouble(localHitZ);
            buffer.writeVarInt(localFace);
            buffer.writeBoolean(includeData);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
