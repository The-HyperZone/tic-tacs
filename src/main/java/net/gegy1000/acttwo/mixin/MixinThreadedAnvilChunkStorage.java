package net.gegy1000.acttwo.mixin;

import com.mojang.datafixers.util.Either;
import net.gegy1000.acttwo.VoidActor;
import net.gegy1000.acttwo.chunk.AsyncChunkState;
import net.gegy1000.acttwo.chunk.ChunkContext;
import net.gegy1000.acttwo.chunk.ChunkHolderExt;
import net.gegy1000.acttwo.chunk.TacsExt;
import net.gegy1000.acttwo.chunk.future.GetChunkContext;
import net.gegy1000.acttwo.chunk.future.UnloadedChunk;
import net.gegy1000.acttwo.chunk.future.UpgradeChunk;
import net.gegy1000.acttwo.chunk.future.VanillaChunkFuture;
import net.gegy1000.acttwo.chunk.worker.ChunkGenWorker;
import net.gegy1000.justnow.future.Future;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class MixinThreadedAnvilChunkStorage implements TacsExt {
    @Shadow
    protected abstract ChunkHolder getCurrentChunkHolder(long pos);

    @Shadow
    protected abstract CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> convertToFullChunk(ChunkHolder chunkHolder);

    @Shadow
    protected abstract CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> loadChunk(ChunkPos pos);

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/thread/TaskExecutor;create(Ljava/util/concurrent/Executor;Ljava/lang/String;)Lnet/minecraft/util/thread/TaskExecutor;",
                    ordinal = 0
            )
    )
    private TaskExecutor<Runnable> createWorldgenActor(Executor executor, String name) {
        return VoidActor.INSTANCE;
    }

    private ThreadedAnvilChunkStorage self() {
        return (ThreadedAnvilChunkStorage) (Object) this;
    }

    @Override
    public Future<Chunk> getChunk(int chunkX, int chunkZ, ChunkStatus targetStatus) {
        ChunkHolder holder = this.getCurrentChunkHolder(ChunkPos.toLong(chunkX, chunkZ));
        if (holder == null) {
            return UnloadedChunk.INSTANCE;
        }

        return this.getChunk(holder, targetStatus);
    }

    @Override
    public Future<Chunk> getChunk(ChunkHolder holder, ChunkStatus targetStatus) {
        AsyncChunkState asyncState = ((ChunkHolderExt) holder).getAsyncState();
        asyncState.upgradeTo(this.self(), targetStatus, this::spawnUpgradeFrom);

        return asyncState.getListenerFor(targetStatus);
    }

    @Override
    public void spawnUpgradeFrom(
            Future<Chunk> fromFuture, ChunkHolder holder,
            ChunkStatus fromStatus, ChunkStatus toStatus
    ) {
        if (fromStatus == toStatus) {
            return;
        }

        ChunkStatus[] upgrades = UpgradeChunk.upgradesBetween(fromStatus, toStatus);

        // enqueue the complete context over all the upgrades right away
        ChunkContext.forRange(upgrades).spawn(this.self(), holder.getPos());

        ChunkGenWorker.INSTANCE.spawn(holder, fromFuture.andThen(chunk -> {
            return new UpgradeChunk(this.self(), holder, upgrades);
        }));
    }

    @Override
    public GetChunkContext getChunkContext(ChunkPos pos, ChunkStatus[] statuses) {
        return new GetChunkContext(this.self(), pos, statuses);
    }

    @Override
    public Future<Chunk> spawnLoadChunk(ChunkHolder holder) {
        CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future = this.loadChunk(holder.getPos())
                .handle((result, throwable) -> {
                    if (result != null) {
                        ChunkHolderExt holderExt = (ChunkHolderExt) holder;
                        holderExt.getAsyncState().complete(ChunkStatus.EMPTY, result);
                    }
                    return result;
                });

        return new VanillaChunkFuture(future);
    }

    @Override
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> accessConvertToFullChunk(ChunkHolder holder) {
        return this.convertToFullChunk(holder);
    }

    /**
     * @reason redirect to ChunkHolder implementation
     * @author gegy1000
     */
    @Overwrite
    public CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> createChunkFuture(ChunkHolder holder, ChunkStatus toStatus) {
        return holder.createFuture(toStatus, this.self());
    }
}
