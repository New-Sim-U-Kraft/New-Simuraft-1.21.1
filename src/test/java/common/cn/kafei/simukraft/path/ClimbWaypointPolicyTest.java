package common.cn.kafei.simukraft.path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ClimbWaypointPolicyTest {
    @Test
    void ladderEntryMustReachCenterBeforeAdvancing() {
        List<PathWaypoint> waypoints = List.of(
                waypoint(0, 65, 0, MovementMode.WALK),
                waypoint(1, 65, 0, MovementMode.CLIMB),
                waypoint(1, 64, 0, MovementMode.CLIMB)
        );

        assertFalse(ClimbWaypointPolicy.isReached(new Vec3(0.90D, 65.0D, 0.5D), waypoints, 1),
                "站在梯子格边缘时不能提前切到下一个爬梯点");
        assertTrue(ClimbWaypointPolicy.isReached(new Vec3(1.50D, 65.0D, 0.5D), waypoints, 1),
                "贴近梯子中心后才允许进入垂直爬梯段");
    }

    @Test
    void upwardClimbMustPassTargetHeightClosely() {
        List<PathWaypoint> waypoints = List.of(
                waypoint(1, 65, 0, MovementMode.CLIMB),
                waypoint(1, 66, 0, MovementMode.CLIMB)
        );

        assertFalse(ClimbWaypointPolicy.isReached(new Vec3(1.50D, 65.70D, 0.5D), waypoints, 1),
                "向上爬梯不能在离目标高度过远时提前到达");
        assertTrue(ClimbWaypointPolicy.isReached(new Vec3(1.50D, 65.82D, 0.5D), waypoints, 1),
                "越过接近目标高度的阈值后可以切到下一点");
    }

    @Test
    void climbCommandCentersBeforeVerticalMotion() {
        List<PathWaypoint> waypoints = List.of(
                waypoint(0, 65, 0, MovementMode.WALK),
                waypoint(1, 65, 0, MovementMode.CLIMB),
                waypoint(1, 66, 0, MovementMode.CLIMB)
        );
        Vec3 edgePosition = new Vec3(0.95D, 65.0D, 0.5D);
        Vec3 centeredPosition = new Vec3(1.48D, 65.0D, 0.5D);

        Vec3 edgeTarget = ClimbWaypointPolicy.commandTarget(edgePosition, waypoints, 1);
        assertTrue(edgeTarget.y == edgePosition.y && edgeTarget.x == 1.5D && edgeTarget.z == 0.5D,
                "未贴中时只应水平对准梯子，不应提前给垂直爬升目标");
        assertSame(waypoints.get(1).position(), ClimbWaypointPolicy.commandTarget(centeredPosition, waypoints, 1),
                "贴中后应使用原始爬梯 waypoint，允许垂直辅助生效");
    }

    @Test
    void landingAfterClimbMustTouchGround() {
        List<PathWaypoint> waypoints = List.of(
                waypoint(1, 64, 0, MovementMode.CLIMB),
                waypoint(2, 64, 0, MovementMode.WALK)
        );

        assertTrue(ClimbWaypointPolicy.isLandingAfterClimb(waypoints, 1),
                "离开梯子后的第一个地面 waypoint 必须使用严格落地判定");
        assertFalse(ClimbWaypointPolicy.isLandingReached(new Vec3(2.50D, 64.32D, 0.5D), waypoints, 1, false),
                "脚还没挨地时不能把离梯落地点判定为到达");
        assertTrue(ClimbWaypointPolicy.isLandingReached(new Vec3(2.50D, 64.04D, 0.5D), waypoints, 1, true),
                "脚部贴近目标高度并且落地后才能完成离梯段");
    }

    @Test
    void detachOnlyAppliesAfterDescendingClimb() {
        List<PathWaypoint> descending = List.of(
                waypoint(0, 65, 0, MovementMode.WALK),
                waypoint(1, 65, 0, MovementMode.CLIMB),
                waypoint(1, 64, 0, MovementMode.CLIMB),
                waypoint(2, 64, 0, MovementMode.WALK)
        );
        List<PathWaypoint> ascending = List.of(
                waypoint(0, 64, 0, MovementMode.WALK),
                waypoint(1, 65, 0, MovementMode.CLIMB),
                waypoint(1, 66, 0, MovementMode.CLIMB),
                waypoint(2, 66, 0, MovementMode.WALK)
        );

        assertTrue(ClimbWaypointPolicy.isLandingAfterDescendingClimb(descending, 3),
                "downward ladder exit should enable detach/drop");
        assertFalse(ClimbWaypointPolicy.isLandingAfterDescendingClimb(ascending, 3),
                "upward ladder exit must keep normal movement control");
    }

    @Test
    void landingDetachDirectionFollowsLandingOffsetBeforeFallback() {
        List<PathWaypoint> waypoints = List.of(
                waypoint(1, 65, 0, MovementMode.CLIMB),
                waypoint(1, 64, 0, MovementMode.CLIMB),
                waypoint(2, 64, 0, MovementMode.WALK),
                waypoint(2, 64, 1, MovementMode.WALK)
        );

        Vec3 direction = ClimbWaypointPolicy.landingDetachDirection(waypoints, 2, new Vec3(0.0D, 0.0D, -1.0D));

        assertDirection(direction, 1.0D, 0.0D, "离梯落点已有水平偏移时，应顺着落点方向切入路径");
    }

    @Test
    void landingDetachDirectionFollowsNextWaypointWhenLandingStaysOnLadderColumn() {
        List<PathWaypoint> waypoints = List.of(
                waypoint(1, 65, 0, MovementMode.CLIMB),
                waypoint(1, 64, 0, MovementMode.CLIMB),
                waypoint(1, 64, 0, MovementMode.WALK),
                waypoint(1, 64, 1, MovementMode.WALK)
        );

        Vec3 direction = ClimbWaypointPolicy.landingDetachDirection(waypoints, 2, new Vec3(1.0D, 0.0D, 0.0D));

        assertDirection(direction, 0.0D, 1.0D, "落点和梯子同列时，应顺着下一节点方向脱离");
    }

    private static PathWaypoint waypoint(int x, int y, int z, MovementMode mode) {
        return new PathWaypoint(new BlockPos(x, y, z), new Vec3(x + 0.5D, y, z + 0.5D), mode);
    }

    private static void assertDirection(Vec3 actual, double expectedX, double expectedZ, String message) {
        assertEquals(expectedX, actual.x, 1.0E-6D, message);
        assertEquals(0.0D, actual.y, 1.0E-6D, message);
        assertEquals(expectedZ, actual.z, 1.0E-6D, message);
    }
}
