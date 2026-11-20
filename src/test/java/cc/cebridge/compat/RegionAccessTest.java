package cc.cebridge.compat;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegionAccessTest {
    @Test void refusesForeignRegionBeforeReadingChunksOrRayTracing() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(player.getEyeLocation()).thenReturn(new Location(world, 15, 64, 15));
        try (var ownership = mockStatic(Schedulers.class)) {
            ownership.when(() -> Schedulers.owns(world, 0, 0)).thenReturn(false);
            assertNull(RegionAccess.targetBlock(player, 8));
            verifyNoInteractions(world);
            verify(player, never()).getTargetBlockExact(anyInt());
        }
    }

    @Test void refusesUnloadedChunkWithoutLoadingIt() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(player.getEyeLocation()).thenReturn(new Location(world, 15, 64, 15));
        try (var ownership = mockStatic(Schedulers.class)) {
            ownership.when(() -> Schedulers.owns(world, 0, 0)).thenReturn(true);
            assertNull(RegionAccess.targetBlock(player, 8));
            verify(world).isChunkLoaded(0, 0);
            verifyNoMoreInteractions(world);
            verify(player, never()).getTargetBlockExact(anyInt());
        }
    }
}
