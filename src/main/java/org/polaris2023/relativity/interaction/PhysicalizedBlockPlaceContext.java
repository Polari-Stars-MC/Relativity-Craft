package org.polaris2023.relativity.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class PhysicalizedBlockPlaceContext extends BlockPlaceContext {
    private final BlockPos targetPos;
    private final Direction localFace;
    private final PhysicalizedVolumeMapping mapping;

    public PhysicalizedBlockPlaceContext(Player player, InteractionHand hand, ItemStack stack, BlockPos targetPos, Direction clickedFace, Vec3 clickLocation) {
        this(player, hand, stack, targetPos, targetPos, clickedFace, clickedFace, clickLocation, null);
    }

    public PhysicalizedBlockPlaceContext(Player player, InteractionHand hand, ItemStack stack, PhysicalizedHit hit, BlockPos targetPos) {
        this(
                player,
                hand,
                stack,
                hit.visualBlockPos(),
                targetPos,
                hit.worldFace(),
                hit.localFace(),
                placementClickLocation(hit, targetPos),
                PhysicalizedVolumeMapping.current(hit.entity())
        );
    }

    private static Vec3 placementClickLocation(PhysicalizedHit hit, BlockPos targetPos) {
        Vec3 local = hit.localLocation().add(
                hit.entity().sizeX() * 0.5,
                hit.entity().sizeY() * 0.5,
                hit.entity().sizeZ() * 0.5
        );
        BlockPos targetLocalPos = PhysicalizedInteractionHandler.placementLocalPos(hit);
        return new Vec3(
                targetPos.getX() + localFraction(local.x - targetLocalPos.getX()),
                targetPos.getY() + localFraction(local.y - targetLocalPos.getY()),
                targetPos.getZ() + localFraction(local.z - targetLocalPos.getZ())
        );
    }

    private static double localFraction(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private PhysicalizedBlockPlaceContext(
            Player player,
            InteractionHand hand,
            ItemStack stack,
            BlockPos clickedPos,
            BlockPos targetPos,
            Direction worldFace,
            Direction localFace,
            Vec3 clickLocation,
            PhysicalizedVolumeMapping mapping
    ) {
        super(player, hand, stack, new BlockHitResult(clickLocation, worldFace, clickedPos, false));
        this.targetPos = targetPos;
        this.localFace = localFace;
        this.mapping = mapping;
        this.replaceClicked = false;
    }

    @Override
    public BlockPos getClickedPos() {
        return targetPos;
    }

    @Override
    public Direction getClickedFace() {
        return localFace;
    }

    @Override
    public Direction getHorizontalDirection() {
        Player player = getPlayer();
        if (mapping == null || player == null) {
            return super.getHorizontalDirection();
        }
        return mapping.nearestLocalHorizontalDirection(player.getLookAngle(), player.getDirection());
    }

    @Override
    public float getRotation() {
        Player player = getPlayer();
        if (mapping == null || player == null) {
            return super.getRotation();
        }
        return mapping.localYRot(player.getLookAngle(), player.getDirection());
    }

    @Override
    public Direction getNearestLookingDirection() {
        Player player = getPlayer();
        if (mapping == null || player == null) {
            return player == null ? Direction.NORTH : super.getNearestLookingDirection();
        }
        return mapping.nearestLocalDirection(player.getLookAngle());
    }

    @Override
    public Direction getNearestLookingVerticalDirection() {
        Player player = getPlayer();
        if (mapping == null || player == null) {
            return player == null ? Direction.UP : super.getNearestLookingVerticalDirection();
        }
        return mapping.nearestLocalVerticalDirection(player.getLookAngle());
    }

    @Override
    public Direction[] getNearestLookingDirections() {
        Player player = getPlayer();
        if (mapping == null || player == null) {
            return player == null ? new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN, Direction.WEST, Direction.EAST}
                    : super.getNearestLookingDirections();
        }
        return mapping.orderedLocalDirections(player.getLookAngle(), localFace, this.replaceClicked);
    }

    @Override
    public boolean canPlace() {
        return this.getLevel().getBlockState(targetPos).canBeReplaced(this);
    }

    @Override
    public boolean replacingClickedOnBlock() {
        return false;
    }
}
