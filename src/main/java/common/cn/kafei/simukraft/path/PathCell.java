package common.cn.kafei.simukraft.path;

import net.minecraft.core.BlockPos;

public record PathCell(BlockPos pos, int x, int y, int z, double standY, boolean water, boolean climbable, boolean woodenDoor, double cost) {
    public long key() {
        return key(x, y, z);
    }

    public MovementMode defaultMode(MovementIntent intent) {
        if (water) {
            return MovementMode.SWIM;
        }
        if (climbable) {
            return MovementMode.CLIMB;
        }
        return intent == MovementIntent.RUN ? MovementMode.RUN : MovementMode.WALK;
    }

    public static long key(BlockPos pos) {
        return key(pos.getX(), pos.getY(), pos.getZ());
    }

    public static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }
}
