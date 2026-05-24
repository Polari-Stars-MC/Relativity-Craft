package org.polaris2023.relativity;

import com.mojang.logging.LogUtils;
import org.polaris2023.relativity.command.RelativityCommands;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.interaction.PhysicalizedCollisionShapes;
import org.polaris2023.relativity.interaction.PhysicalizedRedstoneMapping;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.physicalization.BlockRemovalQueue;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeManager;
import org.polaris2023.relativity.registry.ModCreativeTabs;
import org.polaris2023.relativity.registry.ModEntityTypes;
import org.polaris2023.relativity.registry.ModItems;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.polaris2023.rn.rapier.nativebridge.RcNative;
import org.slf4j.Logger;

@Mod(RelativityCraft.MOD_ID)
public final class RelativityCraft {
    public static final String MOD_ID = "relativity_craft";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean RAPIER_AVAILABLE = loadRapier();

    public RelativityCraft(IEventBus modBus) {
        ModEntityTypes.register(modBus);
        ModItems.register(modBus);
        ModCreativeTabs.register(modBus);
        modBus.addListener(this::addCreativeTabItems);
        modBus.addListener(PhysicalizedInteractionNetwork::registerPayloads);
        NeoForge.EVENT_BUS.register(this);
    }

    public static boolean isRapierAvailable() {
        return RAPIER_AVAILABLE;
    }

    private static boolean loadRapier() {
        try {
            Class.forName(RcNative.class.getName());
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Rapier native backend is unavailable; Java fallback systems remain active.", t);
            return false;
        }
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (CreativeModeTabs.TOOLS_AND_UTILITIES.equals(event.getTabKey())) {
            event.accept(ModItems.SELECTION_WAND.get(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        RelativityCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void afterLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PhysicalizedVolumeManager.global().drainJobsFor(500_000L);
            BlockRemovalQueue.global().drain(level, 2_000_000L);
            PhysicsWorldManager.global().tick(level);
            PhysicalizedCollisionShapes.pushIntersectingEntities(level);
            PhysicalizedRedstoneMapping.global().tick(level);
        }
    }

    @SubscribeEvent
    public void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof PhysicalizedVolumeEntity entity) {
            PhysicsWorldManager.global().unregister(entity);
        }
    }

    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        markPhysicsTerrainDirty(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public void onBlocksPlaced(BlockEvent.EntityMultiPlaceEvent event) {
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            markPhysicsTerrainDirty(snapshot.getLevel(), snapshot.getPos());
        }
    }

    @SubscribeEvent
    public void onBlockBroken(BreakBlockEvent event) {
        markPhysicsTerrainDirty(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public void onFluidPlacesBlock(BlockEvent.FluidPlaceBlockEvent event) {
        markPhysicsTerrainDirty(event.getLevel(), event.getPos());
        markPhysicsTerrainDirty(event.getLevel(), event.getLiquidPos());
    }

    @SubscribeEvent
    public void onToolChangesBlock(BlockEvent.BlockToolModificationEvent event) {
        if (!event.isSimulated()) {
            markPhysicsTerrainDirty(event.getLevel(), event.getPos());
        }
    }

    @SubscribeEvent
    public void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        markPhysicsTerrainDirty(event.getLevel(), event.getPos());
        for (Direction direction : event.getNotifiedSides()) {
            markPhysicsTerrainDirty(event.getLevel(), event.getPos().relative(direction));
        }
    }

    @SubscribeEvent
    public void beforePistonMove(PistonEvent.Pre event) {
        pushPistonBodies(event);
        markPistonTerrainDirty(event);
    }

    @SubscribeEvent
    public void afterPistonMove(PistonEvent.Post event) {
        markPistonTerrainDirty(event);
    }

    @SubscribeEvent
    public void onExplosionDetonates(ExplosionEvent.Detonate event) {
        for (BlockPos pos : event.getAffectedBlocks()) {
            markPhysicsTerrainDirty(event.getLevel(), pos);
        }
    }

    @SubscribeEvent
    public void onChunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PhysicsWorldManager.global().unloadChunk(level, event.getChunk().getPos().x(), event.getChunk().getPos().z());
        }
    }

    private static void markPistonTerrainDirty(PistonEvent event) {
        LevelAccessor level = event.getLevel();
        markPhysicsTerrainDirty(level, event.getPos());
        markPhysicsTerrainDirty(level, event.getFaceOffsetPos());

        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }

        Direction pushDirection = resolver.getPushDirection();
        for (BlockPos pos : resolver.getToPush()) {
            markPhysicsTerrainDirty(level, pos);
            markPhysicsTerrainDirty(level, pos.relative(pushDirection));
        }
        for (BlockPos pos : resolver.getToDestroy()) {
            markPhysicsTerrainDirty(level, pos);
        }
    }

    private static void pushPistonBodies(PistonEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }

        Direction pushDirection = resolver.getPushDirection();
        AABB sweptBox = sweptBlockBox(event.getFaceOffsetPos(), pushDirection);
        for (BlockPos pos : resolver.getToPush()) {
            sweptBox = union(sweptBox, sweptBlockBox(pos, pushDirection));
        }
        for (BlockPos pos : resolver.getToDestroy()) {
            sweptBox = union(sweptBox, unitBlockBox(pos));
        }

        PhysicsWorldManager.global().pushBodies(level, sweptBox, pushDirection, 1.0);
    }

    private static void markPhysicsTerrainDirty(LevelAccessor level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            PhysicsWorldManager.global().markBlockNeighborhoodChanged(serverLevel, pos);
        }
    }

    private static AABB sweptBlockBox(BlockPos pos, Direction direction) {
        return union(unitBlockBox(pos), unitBlockBox(pos.relative(direction)));
    }

    private static AABB unitBlockBox(BlockPos pos) {
        return new AABB(
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                pos.getX() + 1.0,
                pos.getY() + 1.0,
                pos.getZ() + 1.0
        );
    }

    private static AABB union(AABB first, AABB second) {
        return new AABB(
                Math.min(first.minX, second.minX),
                Math.min(first.minY, second.minY),
                Math.min(first.minZ, second.minZ),
                Math.max(first.maxX, second.maxX),
                Math.max(first.maxY, second.maxY),
                Math.max(first.maxZ, second.maxZ)
        );
    }
}
