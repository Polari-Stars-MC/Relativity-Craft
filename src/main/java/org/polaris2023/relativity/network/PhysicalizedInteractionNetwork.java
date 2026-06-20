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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PhysicalizedInteractionNetwork {
    private PhysicalizedInteractionNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(SnapshotPayload.TYPE, SnapshotPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleSnapshot)
                .playToClient(SnapshotChunkPayload.TYPE, SnapshotChunkPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleSnapshotChunk)
                .playToClient(CellUpdatesPayload.TYPE, CellUpdatesPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleCellUpdates)
                .playToClient(BreakOverlayPayload.TYPE, BreakOverlayPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleBreakOverlay)
                .playToClient(ContainerOpenPayload.TYPE, ContainerOpenPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleContainerOpen)
                .playToServer(UseCommandPayload.TYPE, UseCommandPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleUseCommand)
                .playToServer(BreakCommandPayload.TYPE, BreakCommandPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handleBreakCommand)
                .playToServer(PickCommandPayload.TYPE, PickCommandPayload.STREAM_CODEC, PhysicalizedInteractionNetwork::handlePickCommand);
    }

    // Maximum cells per snapshot packet. For 100k blocks, we split into ~50 packets
    // of 2000 cells each (~40KB per packet, well under the 2MB limit).
    private static final int MAX_CELLS_PER_SNAPSHOT_PACKET = 2000;

    public static void sendSnapshot(PhysicalizedVolumeEntity entity) {
        PhysicalizedVolumeSnapshot snapshot = entity.snapshot();
        Vec3 entityCenter = entity.entityCenter();
        int totalCells = snapshot.blockCount();

        // For large snapshots, split into multiple packets to avoid exceeding
        // Minecraft's ~2MB packet size limit. Each chunk carries a subset of cells.
        if (totalCells > MAX_CELLS_PER_SNAPSHOT_PACKET) {
            List<PhysicalizedBlockSnapshot> allCells = snapshot.cells();
            int totalChunks = (totalCells + MAX_CELLS_PER_SNAPSHOT_PACKET - 1) / MAX_CELLS_PER_SNAPSHOT_PACKET;
            int sizeX = snapshot.sizeX();
            int sizeY = snapshot.sizeY();
            int sizeZ = snapshot.sizeZ();

            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                int fromIndex = chunkIndex * MAX_CELLS_PER_SNAPSHOT_PACKET;
                int toIndex = Math.min(fromIndex + MAX_CELLS_PER_SNAPSHOT_PACKET, totalCells);
                List<PhysicalizedBlockSnapshot> chunkCells = allCells.subList(fromIndex, toIndex);

                PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                        entity,
                        new SnapshotChunkPayload(
                                entity.getId(),
                                chunkIndex,
                                totalChunks,
                                sizeX, sizeY, sizeZ,
                                List.copyOf(chunkCells),
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
        } else {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                    entity,
                    new SnapshotPayload(
                            entity.getId(),
                            snapshot,
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
    }

    public static void sendCellUpdates(PhysicalizedVolumeEntity entity, List<PhysicalizedBlockSnapshot> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                entity,
                new CellUpdatesPayload(entity.getId(), List.copyOf(updates))
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

    // Per-entity buffer for reassembling chunked snapshots on the client.
    // Key: entityId, Value: list of cell batches received so far.
    private static final java.util.Map<Integer, java.util.List<List<PhysicalizedBlockSnapshot>>> snapshotChunkBuffers = new java.util.HashMap<>();

    private static void handleSnapshotChunk(SnapshotChunkPayload payload, IPayloadContext context) {
        Entity entity = context.player().level().getEntity(payload.entityId());
        if (!(entity instanceof PhysicalizedVolumeEntity volume)) {
            return;
        }

        int entityId = payload.entityId();
        java.util.List<List<PhysicalizedBlockSnapshot>> buffer = snapshotChunkBuffers.computeIfAbsent(
                entityId, k -> new java.util.ArrayList<>(payload.totalChunks()));
        // Ensure buffer is large enough
        while (buffer.size() <= payload.chunkIndex()) {
            buffer.add(null);
        }
        buffer.set(payload.chunkIndex(), payload.cells());

        // Check if all chunks have arrived
        if (buffer.size() == payload.totalChunks() && buffer.stream().allMatch(c -> c != null)) {
            snapshotChunkBuffers.remove(entityId);
            // Reassemble all cells into a single list
            List<PhysicalizedBlockSnapshot> allCells = new java.util.ArrayList<>(payload.totalChunks() * 2000);
            for (List<PhysicalizedBlockSnapshot> chunk : buffer) {
                allCells.addAll(chunk);
            }
            PhysicalizedVolumeSnapshot snapshot = new PhysicalizedVolumeSnapshot(
                    payload.sizeX(), payload.sizeY(), payload.sizeZ(), allCells);
            volume.receiveSnapshotAtPose(
                    snapshot,
                    new Vec3(payload.localOriginX(), payload.localOriginY(), payload.localOriginZ()),
                    new Vec3(payload.entityCenterX(), payload.entityCenterY(), payload.entityCenterZ()),
                    payload.qx(), payload.qy(), payload.qz(), payload.qw()
            );
        }
    }

    private static void handleCellUpdates(CellUpdatesPayload payload, IPayloadContext context) {
        Entity entity = context.player().level().getEntity(payload.entityId());
        if (entity instanceof PhysicalizedVolumeEntity volume) {
            volume.receiveSnapshot(volume.snapshot().withCellsUpdated(payload.updates()));
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
                if (action == BreakAction.FINISH) {
                    PhysicalizedInteractionHandler.finishBreakingHit(player, hit.get());
                } else {
                    PhysicalizedInteractionHandler.continueBreakingHit(player, hit.get());
                }
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
        FINISH,
        STOP;

        static BreakAction byId(int id) {
            if (id == STOP.ordinal()) {
                return STOP;
            }
            return id == FINISH.ordinal() ? FINISH : CONTINUE;
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

    /**
     * Chunked snapshot payload for large entities (>2000 cells).
     * The full snapshot is split across multiple packets, each carrying
     * a subset of cells. The client reassembles them into the full snapshot.
     */
    public record SnapshotChunkPayload(
            int entityId,
            int chunkIndex,
            int totalChunks,
            int sizeX,
            int sizeY,
            int sizeZ,
            List<PhysicalizedBlockSnapshot> cells,
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
        public static final CustomPacketPayload.Type<SnapshotChunkPayload> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "physicalized_snapshot_chunk")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotChunkPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> payload.write(buffer),
                SnapshotChunkPayload::read
        );

        private static SnapshotChunkPayload read(RegistryFriendlyByteBuf buffer) {
            int entityId = buffer.readVarInt();
            int chunkIndex = buffer.readVarInt();
            int totalChunks = buffer.readVarInt();
            int sizeX = buffer.readVarInt();
            int sizeY = buffer.readVarInt();
            int sizeZ = buffer.readVarInt();
            int count = buffer.readVarInt();
            List<PhysicalizedBlockSnapshot> cells = new ArrayList<>(Math.min(count, 4096));
            for (int i = 0; i < count; i++) {
                cells.add(new PhysicalizedBlockSnapshot(
                        buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readNbt()));
            }
            return new SnapshotChunkPayload(
                    entityId, chunkIndex, totalChunks, sizeX, sizeY, sizeZ, cells,
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat()
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeVarInt(chunkIndex);
            buffer.writeVarInt(totalChunks);
            buffer.writeVarInt(sizeX);
            buffer.writeVarInt(sizeY);
            buffer.writeVarInt(sizeZ);
            buffer.writeVarInt(cells.size());
            for (PhysicalizedBlockSnapshot cell : cells) {
                buffer.writeVarInt(cell.localX());
                buffer.writeVarInt(cell.localY());
                buffer.writeVarInt(cell.localZ());
                buffer.writeVarInt(cell.stateId());
                buffer.writeNbt(cell.blockEntityNbt());
            }
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

    public record CellUpdatesPayload(int entityId, List<PhysicalizedBlockSnapshot> updates) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CellUpdatesPayload> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "physicalized_cell_updates")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, CellUpdatesPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> payload.write(buffer),
                CellUpdatesPayload::read
        );

        private static CellUpdatesPayload read(RegistryFriendlyByteBuf buffer) {
            int entityId = buffer.readVarInt();
            int count = buffer.readVarInt();
            List<PhysicalizedBlockSnapshot> updates = new ArrayList<>(Math.min(count, 256));
            for (int i = 0; i < count; i++) {
                updates.add(new PhysicalizedBlockSnapshot(
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readNbt()
                ));
            }
            return new CellUpdatesPayload(entityId, updates);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeVarInt(updates.size());
            for (PhysicalizedBlockSnapshot update : updates) {
                buffer.writeVarInt(update.localX());
                buffer.writeVarInt(update.localY());
                buffer.writeVarInt(update.localZ());
                buffer.writeVarInt(update.stateId());
                buffer.writeNbt(update.blockEntityNbt());
            }
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
