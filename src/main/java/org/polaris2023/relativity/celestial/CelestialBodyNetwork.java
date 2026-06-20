package org.polaris2023.relativity.celestial;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.polaris2023.relativity.RelativityCraft;
import org.polaris2023.relativity.enclave.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Network protocol for {@link CelestialBody} synchronization.
 *
 * <p>Since CelestialBody is not a Minecraft Entity, it doesn't get automatic
 * entity tracking. Instead, we send explicit payloads to players within range.</p>
 */
public final class CelestialBodyNetwork {

    /** Tracking range: players within 64 blocks receive updates. */
    private static final double TRACKING_RANGE_SQR = 64.0 * 64.0;

    private CelestialBodyNetwork() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("3") // version 3 — celestial body protocol
                .playToClient(InitPayload.TYPE, InitPayload.STREAM_CODEC, CelestialBodyNetwork::handleInit)
                .playToClient(SectionPayload.TYPE, SectionPayload.STREAM_CODEC, CelestialBodyNetwork::handleSection)
                .playToClient(RemoveSectionPayload.TYPE, RemoveSectionPayload.STREAM_CODEC, CelestialBodyNetwork::handleRemoveSection)
                .playToClient(PosePayload.TYPE, PosePayload.STREAM_CODEC, CelestialBodyNetwork::handlePose)
                .playToClient(CrackPayload.TYPE, CrackPayload.STREAM_CODEC, CelestialBodyNetwork::handleCrack)
                .playToClient(RemovePayload.TYPE, RemovePayload.STREAM_CODEC, CelestialBodyNetwork::handleRemove);
    }

    // ---- send helpers ----

    /**
     * Send init to all players tracking this body.
     * Players are considered "tracking" if they are within range (64 blocks).
     */
    public static void sendInitToTracking(ServerLevel level, CelestialBody body) {
        if (body.enclave() == null) return;
        List<BlockStorage.SectionEntry> sections = new ArrayList<>();
        for (var entry : body.enclave().storage()) {
            sections.add(entry);
        }
        Vec3 center = body.entityCenter();
        InitPayload payload = new InitPayload(
                body.id(), center.x, center.y, center.z,
                body.rotQx(), body.rotQy(), body.rotQz(), body.rotQw(),
                body.sizeX(), body.sizeY(), body.sizeZ(),
                body.originX(), body.originY(), body.originZ(),
                sections);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center.x, center.y, center.z) < TRACKING_RANGE_SQR) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /**
     * Send init to a specific player.
     */
    public static void sendInit(ServerPlayer player, CelestialBody body) {
        if (body.enclave() == null) return;
        List<BlockStorage.SectionEntry> sections = new ArrayList<>();
        for (var entry : body.enclave().storage()) {
            sections.add(entry);
        }
        Vec3 center = body.entityCenter();
        PacketDistributor.sendToPlayer(player, new InitPayload(
                body.id(), center.x, center.y, center.z,
                body.rotQx(), body.rotQy(), body.rotQz(), body.rotQw(),
                body.sizeX(), body.sizeY(), body.sizeZ(),
                body.originX(), body.originY(), body.originZ(),
                sections));
    }

    /**
     * Send pose to all players tracking this body.
     * Uses a simple distance check — players within 64 blocks get updates.
     * Moving bodies get updates every tick; stationary bodies every 4 ticks.
     */
    public static void sendPoseToTracking(ServerLevel level, CelestialBody body, boolean moved) {
        Vec3 center = body.entityCenter();
        PosePayload payload = new PosePayload(
                body.id(), center.x, center.y, center.z,
                body.rotQx(), body.rotQy(), body.rotQz(), body.rotQw());
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center.x, center.y, center.z) < TRACKING_RANGE_SQR) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /**
     * Send remove to all players tracking this body.
     */
    public static void sendRemoveToTracking(ServerLevel level, CelestialBody body) {
        RemovePayload payload = new RemovePayload(body.id());
        Vec3 center = body.entityCenter();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center.x, center.y, center.z) < TRACKING_RANGE_SQR) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
        // Also send to all players (stale state cleanup)
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public static void sendSection(ServerPlayer player, int celestialId,
                                    int sx, int sy, int sz, BlockSection section) {
        PacketDistributor.sendToPlayer(player,
                new SectionPayload(celestialId, sx, sy, sz, section));
    }

    public static void sendRemoveSection(ServerPlayer player, int celestialId,
                                          int sx, int sy, int sz) {
        PacketDistributor.sendToPlayer(player,
                new RemoveSectionPayload(celestialId, sx, sy, sz));
    }

    public static void sendPose(ServerPlayer player, CelestialBody body) {
        Vec3 center = body.entityCenter();
        PacketDistributor.sendToPlayer(player, new PosePayload(
                body.id(), center.x, center.y, center.z,
                body.rotQx(), body.rotQy(), body.rotQz(), body.rotQw()));
    }

    public static void sendCrack(ServerPlayer player, int celestialId,
                                  int x, int y, int z, int progress) {
        PacketDistributor.sendToPlayer(player,
                new CrackPayload(celestialId, x, y, z, progress));
    }

    public static void sendRemove(ServerPlayer player, int celestialId) {
        PacketDistributor.sendToPlayer(player, new RemovePayload(celestialId));
    }

    // ---- client handlers ----

    private static void handleInit(InitPayload payload, IPayloadContext ctx) {
        CelestialBodyRenderState state = CelestialBodyRenderer.getOrCreateState(payload.celestialId);
        if (state.mirror == null) {
            state.mirror = new EnclaveMirror(payload.celestialId,
                    0, 0, 0,
                    payload.sizeX - 1, payload.sizeY - 1, payload.sizeZ - 1);
        }
        state.mirror.applyFullInit(0, 0, 0,
                payload.sizeX - 1, payload.sizeY - 1, payload.sizeZ - 1,
                payload.sections);
        state.sizeX = payload.sizeX;
        state.sizeY = payload.sizeY;
        state.sizeZ = payload.sizeZ;
        state.originX = payload.originX;
        state.originY = payload.originY;
        state.originZ = payload.originZ;
        state.x = payload.posX - payload.sizeY * 0.5;
        state.y = payload.posY - payload.sizeY * 0.5;
        state.z = payload.posZ - payload.sizeY * 0.5;
        state.qx = payload.rotQx;
        state.qy = payload.rotQy;
        state.qz = payload.rotQz;
        state.qw = payload.rotQw;
        state.celestialId = payload.celestialId;
    }

    private static void handleSection(SectionPayload payload, IPayloadContext ctx) {
        CelestialBodyRenderState state = CelestialBodyRenderer.getOrCreateState(payload.celestialId);
        if (state.mirror != null) {
            state.mirror.applySection(payload.sx, payload.sy, payload.sz, payload.section);
        }
    }

    private static void handleRemoveSection(RemoveSectionPayload payload, IPayloadContext ctx) {
        CelestialBodyRenderState state = CelestialBodyRenderer.getOrCreateState(payload.celestialId);
        if (state.mirror != null) {
            state.mirror.removeSection(payload.sx, payload.sy, payload.sz);
        }
    }

    private static void handlePose(PosePayload payload, IPayloadContext ctx) {
        CelestialBodyRenderState state = CelestialBodyRenderer.getOrCreateState(payload.celestialId);
        state.x = payload.posX - state.sizeY * 0.5;
        state.y = payload.posY - state.sizeY * 0.5;
        state.z = payload.posZ - state.sizeY * 0.5;
        state.prevQx = state.qx;
        state.prevQy = state.qy;
        state.prevQz = state.qz;
        state.prevQw = state.qw;
        state.qx = payload.rotQx;
        state.qy = payload.rotQy;
        state.qz = payload.rotQz;
        state.qw = payload.rotQw;
    }

    private static void handleCrack(CrackPayload payload, IPayloadContext ctx) {
        CelestialBodyRenderState state = CelestialBodyRenderer.getOrCreateState(payload.celestialId);
        state.crackX = payload.x;
        state.crackY = payload.y;
        state.crackZ = payload.z;
        state.crackProgress = payload.progress;
    }

    private static void handleRemove(RemovePayload payload, IPayloadContext ctx) {
        CelestialBodyRenderer.removeState(payload.celestialId);
    }

    // ---- payload types ----

    public record InitPayload(
            int celestialId,
            double posX, double posY, double posZ,
            float rotQx, float rotQy, float rotQz, float rotQw,
            int sizeX, int sizeY, int sizeZ,
            float originX, float originY, float originZ,
            List<BlockStorage.SectionEntry> sections
    ) implements CustomPacketPayload {
        public static final Type<InitPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "celestial_init"));

        public static final StreamCodec<RegistryFriendlyByteBuf, InitPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.celestialId);
                    buf.writeDouble(p.posX); buf.writeDouble(p.posY); buf.writeDouble(p.posZ);
                    buf.writeFloat(p.rotQx); buf.writeFloat(p.rotQy); buf.writeFloat(p.rotQz); buf.writeFloat(p.rotQw);
                    buf.writeVarInt(p.sizeX); buf.writeVarInt(p.sizeY); buf.writeVarInt(p.sizeZ);
                    buf.writeFloat(p.originX); buf.writeFloat(p.originY); buf.writeFloat(p.originZ);
                    buf.writeVarInt(p.sections.size());
                    for (var sec : p.sections) {
                        BlockStorageCodec.writeSection(buf, sec.sx(), sec.sy(), sec.sz(), sec.section());
                    }
                },
                buf -> {
                    int cid = buf.readVarInt();
                    double px = buf.readDouble(), py = buf.readDouble(), pz = buf.readDouble();
                    float qx = buf.readFloat(), qy = buf.readFloat(), qz = buf.readFloat(), qw = buf.readFloat();
                    int sx = buf.readVarInt(), sy = buf.readVarInt(), sz = buf.readVarInt();
                    float ox = buf.readFloat(), oy = buf.readFloat(), oz = buf.readFloat();
                    int count = buf.readVarInt();
                    List<BlockStorage.SectionEntry> secs = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        secs.add(BlockStorageCodec.readSection(buf));
                    }
                    return new InitPayload(cid, px, py, pz, qx, qy, qz, qw, sx, sy, sz, ox, oy, oz, secs);
                }
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SectionPayload(
            int celestialId, int sx, int sy, int sz, BlockSection section
    ) implements CustomPacketPayload {
        public static final Type<SectionPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "celestial_section"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SectionPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.celestialId);
                    BlockStorageCodec.writeSection(buf, p.sx, p.sy, p.sz, p.section);
                },
                buf -> {
                    int cid = buf.readVarInt();
                    var sec = BlockStorageCodec.readSection(buf);
                    return new SectionPayload(cid, sec.sx(), sec.sy(), sec.sz(), sec.section());
                }
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RemoveSectionPayload(
            int celestialId, int sx, int sy, int sz
    ) implements CustomPacketPayload {
        public static final Type<RemoveSectionPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "celestial_remove_section"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RemoveSectionPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.celestialId);
                    buf.writeVarInt(p.sx); buf.writeVarInt(p.sy); buf.writeVarInt(p.sz);
                },
                buf -> new RemoveSectionPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PosePayload(
            int celestialId,
            double posX, double posY, double posZ,
            float rotQx, float rotQy, float rotQz, float rotQw
    ) implements CustomPacketPayload {
        public static final Type<PosePayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "celestial_pose"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PosePayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.celestialId);
                    buf.writeDouble(p.posX); buf.writeDouble(p.posY); buf.writeDouble(p.posZ);
                    buf.writeFloat(p.rotQx); buf.writeFloat(p.rotQy); buf.writeFloat(p.rotQz); buf.writeFloat(p.rotQw);
                },
                buf -> new PosePayload(buf.readVarInt(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat())
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record CrackPayload(
            int celestialId, int x, int y, int z, int progress
    ) implements CustomPacketPayload {
        public static final Type<CrackPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "celestial_crack"));

        public static final StreamCodec<RegistryFriendlyByteBuf, CrackPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.celestialId);
                    buf.writeVarInt(p.x); buf.writeVarInt(p.y); buf.writeVarInt(p.z);
                    buf.writeVarInt(p.progress);
                },
                buf -> new CrackPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RemovePayload(int celestialId) implements CustomPacketPayload {
        public static final Type<RemovePayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(RelativityCraft.MOD_ID, "celestial_remove"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RemovePayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.celestialId),
                buf -> new RemovePayload(buf.readVarInt())
        );

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
