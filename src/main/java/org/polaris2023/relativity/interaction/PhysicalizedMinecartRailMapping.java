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

    private PhysicalizedMinecartRailMapping() {
    }

    public static BlockState blockStateForMinecart(Level level, BlockPos pos) {
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

    public static BlockPos railPosForMinecart(AbstractMinecart minecart, BlockPos vanillaPos) {
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
        return mapping.localToWorld(new Vec3(cell.localX() + 0.5, cell.localY() + 0.0625, cell.localZ() + 0.5));
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

    private record RailAttachment(int entityId, int localX, int localY, int localZ, Vec3 anchor) {
        RailAttachment withAnchor(Vec3 nextAnchor) {
            return new RailAttachment(entityId, localX, localY, localZ, nextAnchor);
        }
    }
}
