package common.cn.kafei.simukraft.path;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public record PathRequest(UUID citizenId, ResourceLocation dimensionId, BlockPos startPos, Vec3 target, MovementIntent intent, long requestedAt) {
    public BlockPos targetBlockPos() {
        return BlockPos.containing(target.x, target.y, target.z);
    }
}
