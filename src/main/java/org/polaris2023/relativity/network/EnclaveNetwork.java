package org.polaris2023.relativity.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.enclave.*;
import org.polaris2023.relativity.entity.EnclaveEntity;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedInteractionHandler;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeSnapshot;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Network payloads for enclave entity synchronization.
 *
 * <p>Unlike the old snapshot-based sync (which sent per-block updates),
 * enclave sync sends section-level updates. A single section update covers
 * up to 4096 blocks in one packet. For a 100k-block volume (~25 sections),
 * the initial sync is ~200KB compressed instead of ~8MB for per-block data.</p>
 */
public final class EnclaveNetwork {
    private EnclaveNetwork() {}

    public static void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        event.registrar("2") // version 2 — separate from the snapshot protocol
                .playToClient(EnclaveInitPayload.TYPE, EnclaveInitPayload.STREAM_CODEC, EnclaveNetwork::handleInit)
                .playToClient(EnclaveSectionPayload.TYPE, EnclaveSectionPayload.STREAM_CODEC, EnclaveNetwork::handleSection)
                .playToClient(EnclaveRemoveSectionPayload.TYPE, EnclaveRemoveSectionPayload.STREAM_CODEC, EnclaveNetwork::handleRemoveSection)
                .playToClient(EnclaveCrackPayload.TYPE, EnclaveCrackPayload.STREAM_CODEC, EnclaveNetwork::handleCrack);
    }

    // ---- send helpers ----

    /**
     * Send the full initial state of an enclave entity to tracking players.
     */
    public static void sendInit(EnclaveEntity entity) {
        Enclave enclave = entity.enclave();
        if (enclave == null) return;

        List<BlockStorage.SectionEntry> entries = new ArrayList<>();
        for (var e : enclave.storage()) {
            entries.add(e);
        }

        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity,
                new EnclaveInitPayload(
                        entity.getId(),
                        enclave.originX(), enclave.originY(), enclave.originZ(),
                        enclave.boundX(),  enclave.boundY(),  enclave.boundZ(),
                        entries
                ));
    }

    /**
     * Send a single section update (block placed or broken).
     */
    public static void sendSection(EnclaveEntity entity, int sx, int sy, int sz) {
        Enclave enclave = entity.enclave();
        if (enclave == null) return;

        BlockSection section = enclave.storage().section(sx, sy, sz);
        if (section == null) {
            // Section became empty — tell clients to remove it
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity,
                    new EnclaveRemoveSectionPayload(entity.getId(), sx, sy, sz));
        } else {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity,
                    new EnclaveSectionPayload(entity.getId(), sx, sy, sz, section));
        }
    }

    /**
     * Send crack overlay progress.
     */
    public static void sendCrack(EnclaveEntity entity, int x, int y, int z, int progress) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity,
                new EnclaveCrackPayload(entity.getId(), x, y, z, progress));
    }

    // ---- client handlers ----

    private static void handleInit(EnclaveInitPayload payload, IPayloadContext ctx) {
        Entity e = ctx.player().level().getEntity(payload.entityId);
        if (e instanceof EnclaveEntity enclave) {
            EnclaveMirror mirror = new EnclaveMirror(
                    payload.entityId,
                    payload.originX, payload.originY, payload.originZ,
                    payload.boundX,  payload.boundY,  payload.boundZ
            );
            mirror.applyFullInit(
                    payload.originX, payload.originY, payload.originZ,
                    payload.boundX,  payload.boundY,  payload.boundZ,
                    payload.sections
            );
            enclave.setMirror(mirror);
        }
    }

    private static void handleSection(EnclaveSectionPayload payload, IPayloadContext ctx) {
        Entity e = ctx.player().level().getEntity(payload.entityId);
        if (e instanceof EnclaveEntity enclave) {
            EnclaveMirror mirror = enclave.mirror();
            if (mirror != null) {
                mirror.applySection(payload.sx, payload.sy, payload.sz, payload.section);
            }
        }
    }

    private static void handleRemoveSection(EnclaveRemoveSectionPayload payload, IPayloadContext ctx) {
        Entity e = ctx.player().level().getEntity(payload.entityId);
        if (e instanceof EnclaveEntity enclave) {
            EnclaveMirror mirror = enclave.mirror();
            if (mirror != null) {
                mirror.removeSection(payload.sx, payload.sy, payload.sz);
            }
        }
    }

    private static void handleCrack(EnclaveCrackPayload payload, IPayloadContext ctx) {
        Entity e = ctx.player().level().getEntity(payload.entityId);
        if (e instanceof EnclaveEntity enclave) {
            enclave.setCrackOverlay(payload.x, payload.y, payload.z, payload.progress);
        }
    }

    // ---- payload types ----

    public record EnclaveInitPayload(
            int entityId,
            int originX, int originY, int originZ,
            int boundX,  int boundY,  int boundZ,
            List<BlockStorage.SectionEntry> sections
    ) implements CustomPacketPayload {
        public static final Type<EnclaveInitPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "enclave_init"));

        public static final StreamCodec<RegistryFriendlyByteBuf, EnclaveInitPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.entityId);
                    buf.writeVarInt(p.originX); buf.writeVarInt(p.originY); buf.writeVarInt(p.originZ);
                    buf.writeVarInt(p.boundX);  buf.writeVarInt(p.boundY);  buf.writeVarInt(p.boundZ);
                    buf.writeVarInt(p.sections.size());
                    for (var sec : p.sections) {
                        BlockStorageCodec.writeSection(buf, sec.sx(), sec.sy(), sec.sz(), sec.section());
                    }
                },
                buf -> {
                    int eid = buf.readVarInt();
                    int ox = buf.readVarInt(), oy = buf.readVarInt(), oz = buf.readVarInt();
                    int bx = buf.readVarInt(), by = buf.readVarInt(), bz = buf.readVarInt();
                    int count = buf.readVarInt();
                    List<BlockStorage.SectionEntry> secs = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        secs.add(BlockStorageCodec.readSection(buf));
                    }
                    return new EnclaveInitPayload(eid, ox, oy, oz, bx, by, bz, secs);
                }
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record EnclaveSectionPayload(
            int entityId, int sx, int sy, int sz, BlockSection section
    ) implements CustomPacketPayload {
        public static final Type<EnclaveSectionPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "enclave_section"));

        public static final StreamCodec<RegistryFriendlyByteBuf, EnclaveSectionPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.entityId);
                    BlockStorageCodec.writeSection(buf, p.sx, p.sy, p.sz, p.section);
                },
                buf -> {
                    int eid = buf.readVarInt();
                    var sec = BlockStorageCodec.readSection(buf);
                    return new EnclaveSectionPayload(eid, sec.sx(), sec.sy(), sec.sz(), sec.section());
                }
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record EnclaveRemoveSectionPayload(
            int entityId, int sx, int sy, int sz
    ) implements CustomPacketPayload {
        public static final Type<EnclaveRemoveSectionPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "enclave_remove_section"));

        public static final StreamCodec<RegistryFriendlyByteBuf, EnclaveRemoveSectionPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.entityId);
                    buf.writeVarInt(p.sx); buf.writeVarInt(p.sy); buf.writeVarInt(p.sz);
                },
                buf -> new EnclaveRemoveSectionPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record EnclaveCrackPayload(
            int entityId, int x, int y, int z, int progress
    ) implements CustomPacketPayload {
        public static final Type<EnclaveCrackPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "enclave_crack"));

        public static final StreamCodec<RegistryFriendlyByteBuf, EnclaveCrackPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.entityId);
                    buf.writeVarInt(p.x); buf.writeVarInt(p.y); buf.writeVarInt(p.z);
                    buf.writeVarInt(p.progress);
                },
                buf -> new EnclaveCrackPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
