package org.polaris2023.relativity;

import com.mojang.logging.LogUtils;
import org.polaris2023.relativity.celestial.CelestialBodyNetwork;
import org.polaris2023.relativity.command.RelativityCommands;
import org.polaris2023.relativity.entity.EnclaveEntity;
import org.polaris2023.relativity.entity.PhysicalizedVolumeEntity;
import org.polaris2023.relativity.enclave.Enclave;
import org.polaris2023.relativity.fluid.WpoFiniteWaterPhysics;
import org.polaris2023.relativity.interaction.PhysicalizedCollisionShapes;
import org.polaris2023.relativity.interaction.PhysicalizedRedstoneMapping;
import org.polaris2023.relativity.nativeaccess.RelativityCraftRapier;
import org.polaris2023.relativity.network.EnclaveNetwork;
import org.polaris2023.relativity.network.PhysicalizedInteractionNetwork;
import org.polaris2023.relativity.physicalization.BlockRemovalQueue;
import org.polaris2023.relativity.physicalization.PhysicalizedVolumeManager;
import org.polaris2023.relativity.registry.ModAttachments;
import org.polaris2023.relativity.registry.ModCreativeTabs;
import org.polaris2023.relativity.registry.ModEntityTypes;
import org.polaris2023.relativity.registry.ModItems;
import org.polaris2023.relativity.world.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
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
import net.neoforged.neoforge.event.level.block.CreateFluidSourceEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

@Mod(RelativityCraft.MOD_ID)
public final class RelativityCraft {
    public static final String MOD_ID = "relativity_craft";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean RAPIER_AVAILABLE = loadRapier();

    public RelativityCraft(IEventBus modBus) {
        ModAttachments.register(modBus);
        ModEntityTypes.register(modBus);
        ModItems.register(modBus);
        ModCreativeTabs.register(modBus);
        modBus.addListener(this::addCreativeTabItems);
        modBus.addListener(PhysicalizedInteractionNetwork::registerPayloads);
        modBus.addListener(EnclaveNetwork::registerPayloads);
        modBus.addListener(CelestialBodyNetwork::registerPayloads);
        NeoForge.EVENT_BUS.register(this);
    }

    public static boolean isRapierAvailable() {
        return RAPIER_AVAILABLE;
    }

    private static boolean loadRapier() {
        prepareJnaTempDirectory();
        try {
            RelativityCraftRapier.ensureLoaded();
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Rapier native backend is unavailable; physicalized volume simulation is disabled.", t);
            return false;
        }
    }

    private static void prepareJnaTempDirectory() {
        try {
            Path tempDir = FMLPaths.GAMEDIR.get()
                    .resolve(MOD_ID)
                    .resolve("native-temp")
                    .resolve("libraries");
            Files.createDirectories(tempDir);
            String tempPath = tempDir.toAbsolutePath().toString();
            System.setProperty("java.io.tmpdir", tempPath);
            System.setProperty("jna.tmpdir", tempPath);
            System.setProperty("io.netty.tmpdir", tempPath);
            System.setProperty("io.netty.native.workdir", tempPath);
            System.setProperty("org.lwjgl.system.SharedLibraryExtractPath", tempPath);
        } catch (Throwable t) {
            LOGGER.warn("Could not prepare the local native temp directory; native loading will use the JVM default. Cause: {}", t.toString());
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
            if (PhysicalizedRedstoneMapping.global().isLogicBodyLevel(level)) {
                return;
            }
            WpoFiniteWaterPhysics.drainQueuedTicks(level);
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
            if (event.getLevel() instanceof ServerLevel level) {
                PhysicalizedRedstoneMapping.global().removeBody(level, entity);
            }
        }
        if (event.getEntity() instanceof EnclaveEntity entity) {
            PhysicsWorldManager.global().unregisterEnclave(entity);
        }
    }

    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        WpoFiniteWaterPhysics.onBlockPlaced(event);
        markPhysicsTerrainDirty(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public void onBlocksPlaced(BlockEvent.EntityMultiPlaceEvent event) {
        WpoFiniteWaterPhysics.onBlocksPlaced(event);
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            markPhysicsTerrainDirty(snapshot.getLevel(), snapshot.getPos());
        }
    }

    @SubscribeEvent
    public void onBlockBroken(BreakBlockEvent event) {
        WpoFiniteWaterPhysics.settleAround(event.getLevel(), event.getPos());
        markPhysicsTerrainDirty(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public void onFluidPlacesBlock(BlockEvent.FluidPlaceBlockEvent event) {
        WpoFiniteWaterPhysics.settleAround(event.getLevel(), event.getPos());
        WpoFiniteWaterPhysics.settleAround(event.getLevel(), event.getLiquidPos());
        markPhysicsTerrainDirty(event.getLevel(), event.getPos());
        markPhysicsTerrainDirty(event.getLevel(), event.getLiquidPos());
    }

    @SubscribeEvent
    public void onCreateFluidSource(CreateFluidSourceEvent event) {
        if (event.getFluidState().is(FluidTags.WATER)) {
            event.setCanConvert(false);
        }
    }

    @SubscribeEvent
    public void onToolChangesBlock(BlockEvent.BlockToolModificationEvent event) {
        if (!event.isSimulated()) {
            WpoFiniteWaterPhysics.settleAroundIfTouchingWater(event.getLevel(), event.getPos());
            markPhysicsTerrainDirty(event.getLevel(), event.getPos());
        }
    }

    @SubscribeEvent
    public void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        WpoFiniteWaterPhysics.settleNeighborNotify(event);
        markPhysicsTerrainDirty(event.getLevel(), event.getPos());
        for (Direction direction : event.getNotifiedSides()) {
            markPhysicsTerrainDirty(event.getLevel(), event.getPos().relative(direction));
        }
    }

    @SubscribeEvent
    public void beforePistonMove(PistonEvent.Pre event) {
        WpoFiniteWaterPhysics.beforePistonMove(event);
        pushPistonBodies(event);
        markPistonTerrainDirty(event);
    }

    @SubscribeEvent
    public void afterPistonMove(PistonEvent.Post event) {
        WpoFiniteWaterPhysics.settlePiston(event);
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
