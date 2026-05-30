package common.cn.kafei.simukraft.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record PathWaypoint(BlockPos blockPos, Vec3 position, MovementMode mode) {
    public static PathWaypoint of(PathCell cell, MovementMode mode) {
        return new PathWaypoint(cell.pos(), new Vec3(cell.x() + 0.5D, cell.standY(), cell.z() + 0.5D), mode);
    }
}
