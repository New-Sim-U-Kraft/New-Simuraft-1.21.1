package common.cn.kafei.simukraft.path;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * ClimbWaypointPolicy: 集中管理梯子/藤蔓/脚手架 waypoint 的精确到达规则。
 */
final class ClimbWaypointPolicy {
    private static final double CLIMB_CENTER_DISTANCE = 0.34D;
    private static final double CLIMB_VERTICAL_PASS_DISTANCE = 0.20D;
    private static final double CLIMB_LANDING_HORIZONTAL_DISTANCE = 0.42D;
    private static final double CLIMB_LANDING_VERTICAL_DISTANCE = 0.12D;
    private static final double CLIMB_WATER_LANDING_VERTICAL_DISTANCE = 0.35D;
    private static final double HORIZONTAL_OFFSET_EPSILON = 0.04D;

    private ClimbWaypointPolicy() {
    }

    /**
     * commandTarget: 垂直爬梯前先把实体水平推到梯子格中心，避免在边缘提前上/下梯。
     */
    static Vec3 commandTarget(Vec3 position, List<PathWaypoint> waypoints, int index) {
        PathWaypoint waypoint = waypoints.get(index);
        if (waypoint.mode() != MovementMode.CLIMB || !requiresClimbCentering(waypoints, index)) {
            return waypoint.position();
        }
        if (horizontalDistanceSqr(position, waypoint.position()) <= CLIMB_CENTER_DISTANCE * CLIMB_CENTER_DISTANCE) {
            return waypoint.position();
        }
        return new Vec3(waypoint.position().x, position.y, waypoint.position().z);
    }

    /**
     * isReached: 判断 CLIMB waypoint 是否真的到达，垂直通过也必须先贴近梯子中心。
     */
    static boolean isReached(Vec3 position, List<PathWaypoint> waypoints, int index) {
        PathWaypoint waypoint = waypoints.get(index);
        if (waypoint.mode() != MovementMode.CLIMB) {
            return false;
        }
        if (horizontalDistanceSqr(position, waypoint.position()) > CLIMB_CENTER_DISTANCE * CLIMB_CENTER_DISTANCE) {
            return false;
        }
        if (index <= 0) {
            return Math.abs(position.y - waypoint.position().y) <= CLIMB_VERTICAL_PASS_DISTANCE;
        }
        double previousY = waypoints.get(index - 1).position().y;
        if (waypoint.position().y > previousY + 0.25D) {
            return position.y >= waypoint.position().y - CLIMB_VERTICAL_PASS_DISTANCE;
        }
        if (waypoint.position().y < previousY - 0.25D) {
            return position.y <= waypoint.position().y + CLIMB_VERTICAL_PASS_DISTANCE;
        }
        return Math.abs(position.y - waypoint.position().y) <= CLIMB_VERTICAL_PASS_DISTANCE;
    }

    /**
     * hasHorizontalOffset: 判断两点之间是否存在需要横向离开梯子的位移。
     */
    private static boolean hasHorizontalOffset(Vec3 from, Vec3 to) {
        return horizontalDistanceSqr(from, to) > HORIZONTAL_OFFSET_EPSILON;
    }

    /**
     * isLandingAfterClimb: 判断当前 waypoint 是否是离开梯子后的第一个落地点。
     */
    static boolean isLandingAfterClimb(List<PathWaypoint> waypoints, int index) {
        return index > 0
                && waypoints.get(index - 1).mode() == MovementMode.CLIMB
                && waypoints.get(index).mode() != MovementMode.CLIMB;
    }

    /**
     * isLandingAfterDescendingClimb: 只把向下爬梯后的第一个非 CLIMB 点视为需要脱离下落的落点。
     */
    static boolean isLandingAfterDescendingClimb(List<PathWaypoint> waypoints, int index) {
        if (!isLandingAfterClimb(waypoints, index)) {
            return false;
        }
        PathWaypoint lastClimb = waypoints.get(index - 1);
        PathWaypoint landing = waypoints.get(index);
        if (landing.position().y < lastClimb.position().y - 0.25D) {
            return true;
        }
        if (index < 2) {
            return false;
        }
        PathWaypoint beforeLastClimb = waypoints.get(index - 2);
        return lastClimb.position().y < beforeLastClimb.position().y - 0.25D;
    }

    /**
     * landingDetachDirection: 下梯脱离优先沿路径方向，避免固定离墙力把实体推离下一节点。
     */
    static Vec3 landingDetachDirection(List<PathWaypoint> waypoints, int landingIndex, Vec3 fallbackDirection) {
        if (landingIndex > 0) {
            Vec3 fromClimb = horizontalDirection(waypoints.get(landingIndex - 1).position(), waypoints.get(landingIndex).position());
            if (fromClimb != Vec3.ZERO) {
                return fromClimb;
            }
        }
        if (landingIndex + 1 < waypoints.size()) {
            Vec3 toNext = horizontalDirection(waypoints.get(landingIndex).position(), waypoints.get(landingIndex + 1).position());
            if (toNext != Vec3.ZERO) {
                return toNext;
            }
        }
        return normalizeHorizontal(fallbackDirection);
    }

    /**
     * isLandingReached: 离梯后的陆地落点必须真正落地，不能在半空提前完成导航。
     */
    static boolean isLandingReached(Vec3 position, List<PathWaypoint> waypoints, int index, boolean onGround) {
        PathWaypoint waypoint = waypoints.get(index);
        if (!isLandingAfterClimb(waypoints, index)) {
            return false;
        }
        if (horizontalDistanceSqr(position, waypoint.position()) > CLIMB_LANDING_HORIZONTAL_DISTANCE * CLIMB_LANDING_HORIZONTAL_DISTANCE) {
            return false;
        }
        double verticalDistance = Math.abs(position.y - waypoint.position().y);
        if (waypoint.mode() == MovementMode.SWIM) {
            return verticalDistance <= CLIMB_WATER_LANDING_VERTICAL_DISTANCE;
        }
        return onGround && verticalDistance <= CLIMB_LANDING_VERTICAL_DISTANCE;
    }

    /**
     * requiresClimbCentering: 判断当前 CLIMB 点是否连接了进梯、垂直爬梯或离梯动作。
     */
    private static boolean requiresClimbCentering(List<PathWaypoint> waypoints, int index) {
        PathWaypoint current = waypoints.get(index);
        return isClimbEntry(waypoints, index, current)
                || hasVerticalClimbNeighbor(waypoints, index, current)
                || isClimbExit(waypoints, index, current);
    }

    /**
     * isClimbEntry: 判断当前点是否从非梯子格横向进入梯子。
     */
    private static boolean isClimbEntry(List<PathWaypoint> waypoints, int index, PathWaypoint current) {
        if (index <= 0) {
            return false;
        }
        PathWaypoint previous = waypoints.get(index - 1);
        return previous.mode() != MovementMode.CLIMB && hasHorizontalOffset(previous.position(), current.position());
    }

    /**
     * isClimbExit: 判断当前点之后是否要横向离开梯子。
     */
    private static boolean isClimbExit(List<PathWaypoint> waypoints, int index, PathWaypoint current) {
        if (index >= waypoints.size() - 1) {
            return false;
        }
        PathWaypoint next = waypoints.get(index + 1);
        return next.mode() != MovementMode.CLIMB && hasHorizontalOffset(current.position(), next.position());
    }

    /**
     * hasVerticalClimbNeighbor: 判断相邻 waypoint 是否是同列上下爬梯。
     */
    private static boolean hasVerticalClimbNeighbor(List<PathWaypoint> waypoints, int index, PathWaypoint current) {
        return isVerticalClimbNeighbor(waypoints, index - 1, current)
                || isVerticalClimbNeighbor(waypoints, index + 1, current);
    }

    /**
     * isVerticalClimbNeighbor: 判断指定相邻点是否为同一梯子列的上下移动。
     */
    private static boolean isVerticalClimbNeighbor(List<PathWaypoint> waypoints, int neighborIndex, PathWaypoint current) {
        if (neighborIndex < 0 || neighborIndex >= waypoints.size()) {
            return false;
        }
        PathWaypoint neighbor = waypoints.get(neighborIndex);
        return neighbor.mode() == MovementMode.CLIMB
                && current.blockPos().getX() == neighbor.blockPos().getX()
                && current.blockPos().getZ() == neighbor.blockPos().getZ()
                && current.blockPos().getY() != neighbor.blockPos().getY();
    }

    /**
     * horizontalDirection: 计算 from -> to 的 XZ 单位方向。
     */
    private static Vec3 horizontalDirection(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return normalizeHorizontal(new Vec3(dx, 0.0D, dz));
    }

    /**
     * normalizeHorizontal: 只保留 XZ 平面方向，过短时返回零向量。
     */
    private static Vec3 normalizeHorizontal(Vec3 direction) {
        double horizontalLength = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontalLength <= 1.0E-4D) {
            return Vec3.ZERO;
        }
        return new Vec3(direction.x / horizontalLength, 0.0D, direction.z / horizontalLength);
    }

    /**
     * horizontalDistanceSqr: 计算 XZ 平面距离平方，避免频繁开方。
     */
    private static double horizontalDistanceSqr(Vec3 from, Vec3 to) {
        double dx = from.x - to.x;
        double dz = from.z - to.z;
        return dx * dx + dz * dz;
    }
}
