package org.polaris2023.relativity.interaction;

import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.physicalization.PhysicalizedBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PhysicalizedMinecartRailMapping {
    private static final double BLOCK_QUERY_INFLATE = 0.35;
    private static final double CART_HORIZONTAL_QUERY = 1.25;
    private static final double CART_BELOW_QUERY = 1.5;
    private static final double CART_ABOVE_QUERY = 0.75;
    private static final double LOOKUP_INFLATE = 1.0;
    private static final double EPSILON = 1.0E-6;
    private static final double MAX_CARRIED_RAIL_SHIFT_SQR = 64.0;
    private static final Map<UUID, RailAttachment> ATTACHMENTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> INSIDE_LOCAL_MINECART_TICK = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<LocalMinecartContext> LOCAL_MINECART_CONTEXT = new ThreadLocal<>();

    private PhysicalizedMinecartRailMapping() {
    }

    public static BlockState blockStateForMinecart(Level level, BlockPos pos) {
        LocalMinecartContext localContext = LOCAL_MINECART_CONTEXT.get();
        if (localContext != null) {
            return localContext.stateAt(pos);
        }

        BlockState worldState = level.getBlockState(pos);
        if (!worldState.isAir()) {
            return worldState;
        }
        return mappedCellAt(level, pos)
                .map(MappedCell::state)
                .orElse(worldState);
    }

    public static void carryMinecartWithPhysicalizedRail(AbstractMinecart minecart) {
        if (minecart.level().isClientSide()) {
            return;
        }

        RailAttachment attachment = ATTACHMENTS.get(minecart.getUUID());
        if (attachment == null) {
            return;
        }

        if (!(minecart.level().getEntity(attachment.entityId()) instanceof PhysicalizedVolumeEntity volume)
                || volume.isRemoved()) {
            ATTACHMENTS.remove(minecart.getUUID());
            return;
        }

        PhysicalizedBlockSnapshot cell = volume.snapshot()
                .cellAt(attachment.localX(), attachment.localY(), attachment.localZ())
                .orElse(null);
        if (cell == null || !cell.state().is(BlockTags.RAILS)) {
            ATTACHMENTS.remove(minecart.getUUID());
            return;
        }

        Vec3 anchor = railAnchor(PhysicalizedVolumeMapping.current(volume), cell);
        Vec3 shift = anchor.subtract(attachment.anchor());
        if (shift.lengthSqr() <= EPSILON) {
            ATTACHMENTS.put(minecart.getUUID(), attachment.withAnchor(anchor));
            return;
        }
        if (shift.lengthSqr() > MAX_CARRIED_RAIL_SHIFT_SQR) {
            ATTACHMENTS.remove(minecart.getUUID());
            return;
        }

        minecart.setPos(minecart.position().add(shift));
        ATTACHMENTS.put(minecart.getUUID(), attachment.withAnchor(anchor));
    }

    public static boolean beginMappedMinecartTick(AbstractMinecart minecart) {
        if (minecart.level().isClientSide() || Boolean.TRUE.equals(INSIDE_LOCAL_MINECART_TICK.get())) {
            return false;
        }

        RailAttachment attachment = attachmentFor(minecart);
        if (attachment == null) {
            return false;
        }

        if (!(minecart.level().getEntity(attachment.entityId()) instanceof PhysicalizedVolumeEntity volume)
                || volume.isRemoved()) {
            ATTACHMENTS.remove(minecart.getUUID());
            return false;
        }

        PhysicalizedBlockSnapshot cell = volume.snapshot()
                .cellAt(attachment.localX(), attachment.localY(), attachment.localZ())
                .orElse(null);
        if (cell == null || !cell.state().is(BlockTags.RAILS)) {
            ATTACHMENTS.remove(minecart.getUUID());
            return false;
        }

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        Vec3 localPosition = mapping.worldToLocal(minecart.position());
        Vec3 localVelocity = mapping.worldNormalToLocal(minecart.getDeltaMovement());
        ATTACHMENTS.put(minecart.getUUID(), attachment.withWorldPose(minecart.position(), minecart.getDeltaMovement(), minecart.getYRot(), minecart.getXRot()));
        LOCAL_MINECART_CONTEXT.set(new LocalMinecartContext(volume, mapping, minecart.getUUID()));
        minecart.setPos(localPosition.x, localPosition.y, localPosition.z);
        minecart.setDeltaMovement(localVelocity);
        INSIDE_LOCAL_MINECART_TICK.set(true);
        return true;
    }

    public static void finishMappedMinecartTick(AbstractMinecart minecart) {
        if (!Boolean.TRUE.equals(INSIDE_LOCAL_MINECART_TICK.get())) {
            return;
        }

        INSIDE_LOCAL_MINECART_TICK.set(false);
        LOCAL_MINECART_CONTEXT.remove();
        RailAttachment attachment = ATTACHMENTS.get(minecart.getUUID());
        if (attachment == null) {
            return;
        }
        if (!(minecart.level().getEntity(attachment.entityId()) instanceof PhysicalizedVolumeEntity volume)
                || volume.isRemoved()) {
            ATTACHMENTS.remove(minecart.getUUID());
            return;
        }

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        Vec3 localPosition = minecart.position();
        Vec3 localVelocity = minecart.getDeltaMovement();
        Vec3 worldPosition = mapping.localToWorld(localPosition);
        Vec3 worldVelocity = mapping.localNormalToWorld(localVelocity);
        minecart.setPos(worldPosition.x, worldPosition.y, worldPosition.z);
        minecart.setDeltaMovement(worldVelocity);
        applyMappedRotation(minecart, mapping, localVelocity);
        ATTACHMENTS.put(minecart.getUUID(), attachment.withAnchor(railAnchor(mapping, attachment.localX(), attachment.localY(), attachment.localZ()))
                .withLocalPose(localPosition, localVelocity)
                .withWorldPose(worldPosition, worldVelocity, minecart.getYRot(), minecart.getXRot()));
    }

    public static void cancelMappedMinecartTick(AbstractMinecart minecart) {
        if (!Boolean.TRUE.equals(INSIDE_LOCAL_MINECART_TICK.get())) {
            return;
        }
        INSIDE_LOCAL_MINECART_TICK.set(false);
        LOCAL_MINECART_CONTEXT.remove();
        RailAttachment attachment = ATTACHMENTS.get(minecart.getUUID());
        if (attachment != null && attachment.worldPosition() != null) {
            minecart.setPos(attachment.worldPosition());
            minecart.setDeltaMovement(attachment.worldVelocity() == null ? Vec3.ZERO : attachment.worldVelocity());
            minecart.setYRot(attachment.worldYRot());
            minecart.setXRot(attachment.worldXRot());
        }
    }

    public static Optional<MinecartRailPose> renderPose(AbstractMinecart minecart) {
        RailAttachment attachment = ATTACHMENTS.get(minecart.getUUID());
        if (attachment == null || attachment.localPosition() == null) {
            return Optional.empty();
        }
        if (!(minecart.level().getEntity(attachment.entityId()) instanceof PhysicalizedVolumeEntity volume)
                || volume.isRemoved()) {
            ATTACHMENTS.remove(minecart.getUUID());
            return Optional.empty();
        }

        PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
        Vec3 forward = attachment.localVelocity() == null || attachment.localVelocity().lengthSqr() < EPSILON
                ? railForward(mapping, attachment.localX(), attachment.localY(), attachment.localZ())
                : mapping.localNormalToWorld(attachment.localVelocity()).normalize();
        Vec3 up = mapping.localNormalToWorld(new Vec3(0.0, 1.0, 0.0)).normalize();
        Vec3 right = forward.cross(up);
        if (right.lengthSqr() < EPSILON) {
            right = mapping.localNormalToWorld(new Vec3(1.0, 0.0, 0.0)).normalize();
        } else {
            right = right.normalize();
        }
        up = right.cross(forward).normalize();
        return Optional.of(new MinecartRailPose(forward, up, right, rollDegrees(forward, up)));
    }

    public static BlockPos railPosForMinecart(AbstractMinecart minecart, BlockPos vanillaPos) {
        if (Boolean.TRUE.equals(INSIDE_LOCAL_MINECART_TICK.get())) {
            LocalMinecartContext localContext = LOCAL_MINECART_CONTEXT.get();
            if (localContext != null) {
                BlockPos localRail = localContext.nearestRail(vanillaPos).orElse(vanillaPos);
                localContext.remember(minecart, localRail);
                return localRail;
            }
        }

        Level level = minecart.level();
        if (level.getBlockState(vanillaPos).is(BlockTags.RAILS)) {
            ATTACHMENTS.remove(minecart.getUUID());
            return vanillaPos;
        }

        MappedCell exact = mappedRailAt(level, vanillaPos).orElse(null);
        if (exact != null) {
            rememberAttachment(minecart, exact);
            return exact.worldPos();
        }

        Vec3 cartPosition = minecart.position();
        AABB cartRailQuery = new AABB(
                cartPosition.x - CART_HORIZONTAL_QUERY,
                cartPosition.y - CART_BELOW_QUERY,
                cartPosition.z - CART_HORIZONTAL_QUERY,
                cartPosition.x + CART_HORIZONTAL_QUERY,
                cartPosition.y + CART_ABOVE_QUERY,
                cartPosition.z + CART_HORIZONTAL_QUERY
        );
        MappedCell nearest = nearestMappedRail(level, cartRailQuery, cartPosition).orElse(null);
        if (nearest == null) {
            ATTACHMENTS.remove(minecart.getUUID());
            return vanillaPos;
        }
        rememberAttachment(minecart, nearest);
        return nearest.worldPos();
    }

    private static Optional<MappedCell> mappedRailAt(Level level, BlockPos pos) {
        if (Boolean.TRUE.equals(INSIDE_LOCAL_MINECART_TICK.get())) {
            return Optional.empty();
        }
        return mappedCellAt(level, pos).filter(cell -> cell.state().is(BlockTags.RAILS));
    }

    private static Optional<MappedCell> mappedCellAt(Level level, BlockPos pos) {
        AABB query = new AABB(pos).inflate(BLOCK_QUERY_INFLATE);
        Vec3 queryCenter = Vec3.atCenterOf(pos);
        MappedCell direct = null;
        double directDistance = Double.POSITIVE_INFINITY;
        MappedCell nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;

        for (PhysicalizedVolumeEntity volume : PhysicalizedVolumeLookup.loadedVolumes(level, query, LOOKUP_INFLATE)) {
            PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
            PhysicalizedBlockSnapshot directCell = mapping.cellAtWorldBlock(pos).orElse(null);
            if (directCell != null && !directCell.state().isAir()) {
                double distance = mapping.cellWorldCenter(directCell).distanceToSqr(queryCenter);
                if (distance < directDistance) {
                    directDistance = distance;
                    direct = mappedCell(volume, mapping, directCell, distance);
                }
            }

            MappedCell candidate = nearestMappedCell(volume, mapping, query, queryCenter, false);
            if (candidate != null && candidate.distanceSqr() < nearestDistance) {
                nearestDistance = candidate.distanceSqr();
                nearest = candidate;
            }
        }

        return Optional.ofNullable(direct == null ? nearest : direct);
    }

    private static Optional<MappedCell> nearestMappedRail(Level level, AABB query, Vec3 point) {
        MappedCell nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (PhysicalizedVolumeEntity volume : PhysicalizedVolumeLookup.loadedVolumes(level, query, LOOKUP_INFLATE)) {
            PhysicalizedVolumeMapping mapping = PhysicalizedVolumeMapping.current(volume);
            MappedCell candidate = nearestMappedCell(volume, mapping, query, point, true);
            if (candidate != null && candidate.distanceSqr() < nearestDistance) {
                nearestDistance = candidate.distanceSqr();
                nearest = candidate;
            }
        }
        return Optional.ofNullable(nearest);
    }

    private static MappedCell nearestMappedCell(
            PhysicalizedVolumeEntity volume,
            PhysicalizedVolumeMapping mapping,
            AABB worldQuery,
            Vec3 point,
            boolean railsOnly
    ) {
        AABB localQuery = mapping.localAabbOfWorld(worldQuery.inflate(EPSILON));
        int minX = Mth.clamp(Mth.floor(localQuery.minX) - 1, 0, volume.snapshot().sizeX() - 1);
        int minY = Mth.clamp(Mth.floor(localQuery.minY) - 1, 0, volume.snapshot().sizeY() - 1);
        int minZ = Mth.clamp(Mth.floor(localQuery.minZ) - 1, 0, volume.snapshot().sizeZ() - 1);
        int maxX = Mth.clamp(Mth.ceil(localQuery.maxX) + 1, 0, volume.snapshot().sizeX() - 1);
        int maxY = Mth.clamp(Mth.ceil(localQuery.maxY) + 1, 0, volume.snapshot().sizeY() - 1);
        int maxZ = Mth.clamp(Mth.ceil(localQuery.maxZ) + 1, 0, volume.snapshot().sizeZ() - 1);

        MappedCell nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    PhysicalizedBlockSnapshot cell = volume.snapshot().cellAtOrNull(x, y, z);
                    if (cell == null || cell.state().isAir() || railsOnly && !cell.state().is(BlockTags.RAILS)) {
                        continue;
                    }

                    AABB worldCellBox = mapping.worldAabbOfLocal(new AABB(x, y, z, x + 1, y + 1, z + 1));
                    if (!worldCellBox.inflate(BLOCK_QUERY_INFLATE).intersects(worldQuery)) {
                        continue;
                    }

                    Vec3 center = mapping.cellWorldCenter(cell);
                    double distance = center.distanceToSqr(point);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = mappedCell(volume, mapping, cell, distance);
                    }
                }
            }
        }
        return nearest;
    }

    private static MappedCell mappedCell(
            PhysicalizedVolumeEntity volume,
            PhysicalizedVolumeMapping mapping,
            PhysicalizedBlockSnapshot cell,
            double distanceSqr
    ) {
        return new MappedCell(
                minecartWorldState(mapping, cell.state()),
                mapping.visualBlockPos(cell),
                distanceSqr,
                volume.getId(),
                cell.localX(),
                cell.localY(),
                cell.localZ(),
                railAnchor(mapping, cell)
        );
    }

    private static BlockState minecartWorldState(PhysicalizedVolumeMapping mapping, BlockState state) {
        if (!(state.getBlock() instanceof BaseRailBlock railBlock)) {
            return state;
        }

        Property<RailShape> shapeProperty = railBlock.getShapeProperty();
        if (!state.hasProperty(shapeProperty)) {
            return state;
        }

        RailShape localShape = state.getValue(shapeProperty);
        RailShape worldShape = worldRailShape(mapping, localShape);
        return shapeProperty.getPossibleValues().contains(worldShape)
                ? state.setValue(shapeProperty, worldShape)
                : state;
    }

    private static RailShape worldRailShape(PhysicalizedVolumeMapping mapping, RailShape shape) {
        return switch (shape) {
            case EAST_WEST -> horizontalAxisShape(worldHorizontalDirection(mapping, Direction.EAST));
            case NORTH_SOUTH -> horizontalAxisShape(worldHorizontalDirection(mapping, Direction.SOUTH));
            case ASCENDING_EAST -> ascendingShape(worldHorizontalDirection(mapping, Direction.EAST));
            case ASCENDING_WEST -> ascendingShape(worldHorizontalDirection(mapping, Direction.WEST));
            case ASCENDING_NORTH -> ascendingShape(worldHorizontalDirection(mapping, Direction.NORTH));
            case ASCENDING_SOUTH -> ascendingShape(worldHorizontalDirection(mapping, Direction.SOUTH));
            case SOUTH_EAST -> cornerShape(worldHorizontalDirection(mapping, Direction.SOUTH), worldHorizontalDirection(mapping, Direction.EAST));
            case SOUTH_WEST -> cornerShape(worldHorizontalDirection(mapping, Direction.SOUTH), worldHorizontalDirection(mapping, Direction.WEST));
            case NORTH_WEST -> cornerShape(worldHorizontalDirection(mapping, Direction.NORTH), worldHorizontalDirection(mapping, Direction.WEST));
            case NORTH_EAST -> cornerShape(worldHorizontalDirection(mapping, Direction.NORTH), worldHorizontalDirection(mapping, Direction.EAST));
        };
    }

    private static Direction worldHorizontalDirection(PhysicalizedVolumeMapping mapping, Direction localDirection) {
        Vec3 world = mapping.localNormalToWorld(new Vec3(
                localDirection.getStepX(),
                localDirection.getStepY(),
                localDirection.getStepZ()
        ));
        if (Math.abs(world.x) >= Math.abs(world.z)) {
            return world.x >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        return world.z >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static RailShape horizontalAxisShape(Direction direction) {
        return direction.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
    }

    private static RailShape ascendingShape(Direction direction) {
        return switch (direction) {
            case EAST -> RailShape.ASCENDING_EAST;
            case WEST -> RailShape.ASCENDING_WEST;
            case NORTH -> RailShape.ASCENDING_NORTH;
            case SOUTH -> RailShape.ASCENDING_SOUTH;
            default -> RailShape.NORTH_SOUTH;
        };
    }

    private static RailShape cornerShape(Direction first, Direction second) {
        boolean north = first == Direction.NORTH || second == Direction.NORTH;
        boolean south = first == Direction.SOUTH || second == Direction.SOUTH;
        boolean west = first == Direction.WEST || second == Direction.WEST;
        boolean east = first == Direction.EAST || second == Direction.EAST;
        if (south && east) {
            return RailShape.SOUTH_EAST;
        }
        if (south && west) {
            return RailShape.SOUTH_WEST;
        }
        if (north && west) {
            return RailShape.NORTH_WEST;
        }
        if (north && east) {
            return RailShape.NORTH_EAST;
        }
        return first.getAxis() == Direction.Axis.X || second.getAxis() == Direction.Axis.X
                ? RailShape.EAST_WEST
                : RailShape.NORTH_SOUTH;
    }

    private static Vec3 railAnchor(PhysicalizedVolumeMapping mapping, PhysicalizedBlockSnapshot cell) {
        return railAnchor(mapping, cell.localX(), cell.localY(), cell.localZ());
    }

    private static Vec3 railAnchor(PhysicalizedVolumeMapping mapping, int localX, int localY, int localZ) {
        return mapping.localToWorld(new Vec3(localX + 0.5, localY + 0.0625, localZ + 0.5));
    }

    private static Vec3 railForward(PhysicalizedVolumeMapping mapping, int localX, int localY, int localZ) {
        return mapping.localNormalToWorld(new Vec3(1.0, 0.0, 0.0)).normalize();
    }

    private static void applyMappedRotation(AbstractMinecart minecart, PhysicalizedVolumeMapping mapping, Vec3 localVelocity) {
        Vec3 worldVelocity = mapping.localNormalToWorld(localVelocity);
        Vec3 forward = worldVelocity.lengthSqr() > EPSILON ? worldVelocity.normalize() : mapping.localNormalToWorld(new Vec3(1.0, 0.0, 0.0)).normalize();
        float yRot = 180.0F - (float) (Math.atan2(forward.z, forward.x) * 180.0 / Math.PI);
        float xRot = (float) (Math.atan2(forward.y, forward.horizontalDistance()) * 180.0 / Math.PI);
        minecart.setYRot(yRot % 360.0F);
        minecart.setXRot(Math.clamp(xRot, -90.0F, 90.0F));
    }

    private static float rollDegrees(Vec3 forward, Vec3 desiredUp) {
        Vec3 defaultUp = new Vec3(0.0, 1.0, 0.0).subtract(forward.scale(forward.y));
        if (defaultUp.lengthSqr() < EPSILON) {
            defaultUp = new Vec3(0.0, 0.0, 1.0).subtract(forward.scale(forward.z));
        }
        defaultUp = defaultUp.normalize();
        Vec3 up = desiredUp.subtract(forward.scale(desiredUp.dot(forward)));
        if (up.lengthSqr() < EPSILON) {
            return 0.0F;
        }
        up = up.normalize();
        double signed = forward.dot(defaultUp.cross(up));
        double cosine = Math.clamp(defaultUp.dot(up), -1.0, 1.0);
        return (float) (Math.atan2(signed, cosine) * 180.0 / Math.PI);
    }

    private static RailAttachment attachmentFor(AbstractMinecart minecart) {
        RailAttachment attachment = ATTACHMENTS.get(minecart.getUUID());
        if (attachment != null) {
            return attachment;
        }

        BlockPos railPos = railPosForMinecart(minecart, minecart.getCurrentBlockPosOrRailBelow());
        attachment = ATTACHMENTS.get(minecart.getUUID());
        return attachment;
    }

    private static void rememberAttachment(AbstractMinecart minecart, MappedCell cell) {
        ATTACHMENTS.put(
                minecart.getUUID(),
                new RailAttachment(cell.entityId(), cell.localX(), cell.localY(), cell.localZ(), cell.anchor())
        );
    }

    private record MappedCell(
            BlockState state,
            BlockPos worldPos,
            double distanceSqr,
            int entityId,
            int localX,
            int localY,
            int localZ,
            Vec3 anchor
    ) {
        MappedCell {
            if (state == null) {
                state = Blocks.AIR.defaultBlockState();
            }
        }
    }

    private record LocalMinecartContext(PhysicalizedVolumeEntity volume, PhysicalizedVolumeMapping mapping, UUID minecartId) {
        BlockState stateAt(BlockPos pos) {
            PhysicalizedBlockSnapshot cell = volume.snapshot().cellAtOrNull(pos.getX(), pos.getY(), pos.getZ());
            return cell == null ? Blocks.AIR.defaultBlockState() : cell.state();
        }

        Optional<BlockPos> nearestRail(BlockPos pos) {
            if (stateAt(pos).is(BlockTags.RAILS)) {
                return Optional.of(pos);
            }
            BlockPos below = pos.below();
            if (stateAt(below).is(BlockTags.RAILS)) {
                return Optional.of(below);
            }

            BlockPos best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            Vec3 localCart = volume.level().getEntity(minecartId) instanceof AbstractMinecart minecart
                    ? minecart.position()
                    : Vec3.atCenterOf(pos);
            for (int y = pos.getY() - 2; y <= pos.getY() + 1; y++) {
                for (int z = pos.getZ() - 1; z <= pos.getZ() + 1; z++) {
                    for (int x = pos.getX() - 1; x <= pos.getX() + 1; x++) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (!stateAt(candidate).is(BlockTags.RAILS)) {
                            continue;
                        }
                        double distance = Vec3.atCenterOf(candidate).distanceToSqr(localCart);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = candidate;
                        }
                    }
                }
            }
            return Optional.ofNullable(best);
        }

        void remember(AbstractMinecart minecart, BlockPos localRail) {
            BlockState state = stateAt(localRail);
            if (!state.is(BlockTags.RAILS)) {
                return;
            }
            Vec3 anchor = railAnchor(mapping, localRail.getX(), localRail.getY(), localRail.getZ());
            RailAttachment previous = ATTACHMENTS.get(minecartId);
            ATTACHMENTS.put(
                    minecartId,
                    new RailAttachment(
                            volume.getId(),
                            localRail.getX(),
                            localRail.getY(),
                            localRail.getZ(),
                            anchor,
                            previous == null ? null : previous.localPosition(),
                            previous == null ? null : previous.localVelocity(),
                            previous == null ? null : previous.worldPosition(),
                            previous == null ? null : previous.worldVelocity(),
                            previous == null ? 0.0F : previous.worldYRot(),
                            previous == null ? 0.0F : previous.worldXRot()
                    )
            );
        }
    }

    public record MinecartRailPose(Vec3 forward, Vec3 up, Vec3 right, float rollDegrees) {
    }

    private record RailAttachment(
            int entityId,
            int localX,
            int localY,
            int localZ,
            Vec3 anchor,
            Vec3 localPosition,
            Vec3 localVelocity,
            Vec3 worldPosition,
            Vec3 worldVelocity,
            float worldYRot,
            float worldXRot
    ) {
        RailAttachment(int entityId, int localX, int localY, int localZ, Vec3 anchor) {
            this(entityId, localX, localY, localZ, anchor, null, null, null, null, 0.0F, 0.0F);
        }

        RailAttachment withAnchor(Vec3 nextAnchor) {
            return new RailAttachment(entityId, localX, localY, localZ, nextAnchor, localPosition, localVelocity, worldPosition, worldVelocity, worldYRot, worldXRot);
        }

        RailAttachment withLocalPose(Vec3 nextPosition, Vec3 nextVelocity) {
            return new RailAttachment(entityId, localX, localY, localZ, anchor, nextPosition, nextVelocity, worldPosition, worldVelocity, worldYRot, worldXRot);
        }

        RailAttachment withWorldPose(Vec3 nextPosition, Vec3 nextVelocity, float nextYRot, float nextXRot) {
            return new RailAttachment(entityId, localX, localY, localZ, anchor, localPosition, localVelocity, nextPosition, nextVelocity, nextYRot, nextXRot);
        }
    }
}
