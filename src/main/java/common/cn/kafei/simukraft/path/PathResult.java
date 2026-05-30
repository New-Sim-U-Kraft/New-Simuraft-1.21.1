package common.cn.kafei.simukraft.path;

import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

public record PathResult(UUID citizenId, Vec3 target, MovementIntent intent, boolean success, List<PathWaypoint> waypoints, String reason) {
    public static PathResult success(PathRequest request, List<PathWaypoint> waypoints) {
        return new PathResult(request.citizenId(), request.target(), request.intent(), true, List.copyOf(waypoints), "");
    }

    public static PathResult failed(PathRequest request, String reason) {
        return new PathResult(request.citizenId(), request.target(), request.intent(), false, List.of(), reason != null ? reason : "unknown");
    }
}
