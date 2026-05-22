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

    public PhysicalizedBlockPlaceContext(Player player, InteractionHand hand, ItemStack stack, BlockPos targetPos, Direction clickedFace, Vec3 clickLocation) {
        super(player, hand, stack, new BlockHitResult(clickLocation, clickedFace, targetPos.relative(clickedFace.getOpposite()), false));
        this.targetPos = targetPos;
        this.replaceClicked = false;
    }

    @Override
    public BlockPos getClickedPos() {
        return targetPos;
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
