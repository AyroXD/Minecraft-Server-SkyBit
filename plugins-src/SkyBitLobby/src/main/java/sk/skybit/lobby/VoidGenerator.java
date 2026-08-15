package sk.skybit.lobby;

import java.util.Random;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;

final class VoidGenerator extends ChunkGenerator {
    @Override
    public @NotNull ChunkData generateChunkData(
        @NotNull World world,
        @NotNull Random random,
        int chunkX,
        int chunkZ,
        @NotNull BiomeGrid biome
    ) {
        return createChunkData(world);
    }
}
