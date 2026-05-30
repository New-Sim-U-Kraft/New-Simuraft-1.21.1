package common.cn.kafei.simukraft.path;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

final class HybridPathfinder {
    private static final int MAX_ITERATIONS = 20000;
    private static final int NEAREST_RANGE = 5;
    private static final int SMOOTH_LOOKAHEAD = 24;
    private static final double WALK_CORRIDOR_HALF_WIDTH = 0.36D;
    private static final double WALK_CORRIDOR_SAMPLE_STEP = 0.20D;

    private HybridPathfinder() {
    }

    static PathResult find(PathRequest request, PathSnapshot snapshot) {
        PathCell start = nearestCell(snapshot, request.startPos(), NEAREST_RANGE);
        PathCell target = nearestCell(snapshot, request.targetBlockPos(), NEAREST_RANGE);
        if (start == null || target == null) {
            return PathResult.failed(request, "missing_start_or_target_cell");
        }
        if (start.key() == target.key()) {
            return PathResult.success(request, List.of(PathWaypoint.of(target, target.defaultMode(request.intent()))));
        }

        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.fCost));
        Map<Long, SearchNode> bestNodes = new HashMap<>();
        SearchNode startNode = new SearchNode(start, null, start.defaultMode(request.intent()), 0.0D, heuristic(start, target));
        open.add(startNode);
        bestNodes.put(start.key(), startNode);

        int iterations = 0;
        while (!open.isEmpty() && iterations++ < MAX_ITERATIONS) {
            SearchNode current = open.poll();
            if (bestNodes.get(current.cell.key()) != current) {
                continue;
            }
            if (current.cell.key() == target.key()) {
                return PathResult.success(request, smooth(snapshot, reconstruct(current)));
            }
            for (Neighbor neighbor : neighbors(snapshot, current.cell, target, request.intent())) {
                double nextCost = current.gCost + neighbor.cost;
                SearchNode existing = bestNodes.get(neighbor.cell.key());
                if (existing != null && existing.gCost <= nextCost) {
                    continue;
                }
                SearchNode next = new SearchNode(neighbor.cell, current, neighbor.mode, nextCost, nextCost + heuristic(neighbor.cell, target));
                bestNodes.put(neighbor.cell.key(), next);
                open.add(next);
            }
        }
        return PathResult.failed(request, "path_not_found");
    }

    private static List<Neighbor> neighbors(PathSnapshot snapshot, PathCell current, PathCell target, MovementIntent intent) {
        List<Neighbor> neighbors = new ArrayList<>(16);
        if (current.water()) {
            addWaterNeighbors(snapshot, current, intent, neighbors);
            return neighbors;
        }
        if (current.climbable()) {
            addClimbNeighbors(snapshot, current, intent, neighbors);
        }
        addWalkNeighbors(snapshot, current, intent, neighbors);
        addSpecialEntryNeighbors(snapshot, current, neighbors);
        addVerticalTransitions(snapshot, current, intent, neighbors);
        return neighbors;
    }

    private static void addWalkNeighbors(PathSnapshot snapshot, PathCell current, MovementIntent intent, List<Neighbor> output) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                PathCell next = snapshot.cell(current.x() + dx, current.y(), current.z() + dz);
                if (next != null && canWalkOnSameLayer(current, next)) {
                    if (dx != 0 && dz != 0 && !hasClearWalkLine(snapshot, current.pos(), next.pos())) {
                        continue;
                    }
                    output.add(new Neighbor(next, walkMode(intent), distance(current, next) * next.cost()));
                }
            }
        }
    }

    private static void addSpecialEntryNeighbors(PathSnapshot snapshot, PathCell current, List<Neighbor> output) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx != 0 && dz != 0 && !diagonalClear(snapshot, current, dx, dz)) {
                    continue;
                }
                PathCell next = snapshot.cell(current.x() + dx, current.y(), current.z() + dz);
                if (next == null) {
                    continue;
                }
                if (next.water()) {
                    output.add(new Neighbor(next, MovementMode.SWIM, 1.0D + distance(current, next) * next.cost()));
                } else if (next.climbable()) {
                    output.add(new Neighbor(next, MovementMode.CLIMB, 1.0D + distance(current, next) * next.cost()));
                }
            }
        }
    }

    private static void addVerticalTransitions(PathSnapshot snapshot, PathCell current, MovementIntent intent, List<Neighbor> output) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx != 0 && dz != 0 && !diagonalClear(snapshot, current, dx, dz)) {
                    continue;
                }
                PathCell up = snapshot.cell(current.x() + dx, current.y() + 1, current.z() + dz);
                if (up != null && !up.water() && up.standY() - current.standY() <= 1.25D) {
                    output.add(new Neighbor(up, MovementMode.JUMP, 2.5D + distance(current, up)));
                }
                for (int fall = 1; fall <= 3; fall++) {
                    PathCell down = snapshot.cell(current.x() + dx, current.y() - fall, current.z() + dz);
                    if (down != null && current.standY() - down.standY() <= 3.5D) {
                        output.add(new Neighbor(down, down.water() ? MovementMode.SWIM : MovementMode.FALL, 1.2D + fall));
                        break;
                    }
                }
            }
        }
        PathCell waterBelow = snapshot.cell(current.x(), current.y() - 1, current.z());
        if (waterBelow != null && waterBelow.water()) {
            output.add(new Neighbor(waterBelow, MovementMode.SWIM, 1.8D));
        }
    }

    private static void addWaterNeighbors(PathSnapshot snapshot, PathCell current, MovementIntent intent, List<Neighbor> output) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    PathCell next = snapshot.cell(current.x() + dx, current.y() + dy, current.z() + dz);
                    if (next == null) {
                        continue;
                    }
                    MovementMode mode = next.water() ? MovementMode.SWIM : walkMode(intent);
                    output.add(new Neighbor(next, mode, distance(current, next) * next.cost()));
                }
            }
        }
    }

    private static void addClimbNeighbors(PathSnapshot snapshot, PathCell current, MovementIntent intent, List<Neighbor> output) {
        PathCell up = snapshot.cell(current.x(), current.y() + 1, current.z());
        if (up != null && (up.climbable() || up.water())) {
            output.add(new Neighbor(up, up.water() ? MovementMode.SWIM : MovementMode.CLIMB, 2.0D));
        }
        PathCell down = snapshot.cell(current.x(), current.y() - 1, current.z());
        if (down != null) {
            output.add(new Neighbor(down, down.climbable() ? MovementMode.CLIMB : walkMode(intent), 2.0D));
        }
    }

    private static boolean diagonalClear(PathSnapshot snapshot, PathCell current, int dx, int dz) {
        return snapshot.cell(current.x() + dx, current.y(), current.z()) != null
                && snapshot.cell(current.x(), current.y(), current.z() + dz) != null;
    }

    private static boolean canWalkOnSameLayer(PathCell from, PathCell to) {
        return to != null
                && !to.water()
                && !to.climbable()
                && Math.abs(from.standY() - to.standY()) <= 0.75D;
    }

    private static List<PathWaypoint> reconstruct(SearchNode end) {
        List<SearchNode> nodes = new ArrayList<>();
        SearchNode current = end;
        while (current != null) {
            nodes.add(current);
            current = current.parent;
        }
        List<PathWaypoint> waypoints = new ArrayList<>(nodes.size());
        for (int i = nodes.size() - 1; i >= 0; i--) {
            SearchNode node = nodes.get(i);
            waypoints.add(PathWaypoint.of(node.cell, node.mode));
        }
        return waypoints;
    }

    private static List<PathWaypoint> smooth(PathSnapshot snapshot, List<PathWaypoint> rawWaypoints) {
        if (rawWaypoints.size() <= 2) {
            return rawWaypoints;
        }
        List<PathWaypoint> smoothed = new ArrayList<>(rawWaypoints.size());
        int anchor = 0;
        smoothed.add(rawWaypoints.get(anchor));
        while (anchor < rawWaypoints.size() - 1) {
            int best = anchor + 1;
            int maxCandidate = Math.min(rawWaypoints.size() - 1, anchor + SMOOTH_LOOKAHEAD);
            for (int candidate = maxCandidate; candidate > anchor + 1; candidate--) {
                if (canSmoothSegment(snapshot, rawWaypoints, anchor, candidate)) {
                    best = candidate;
                    break;
                }
            }
            smoothed.add(rawWaypoints.get(best));
            anchor = best;
        }
        return smoothed;
    }

    private static boolean canSmoothSegment(PathSnapshot snapshot, List<PathWaypoint> waypoints, int fromIndex, int toIndex) {
        PathWaypoint from = waypoints.get(fromIndex);
        PathWaypoint to = waypoints.get(toIndex);
        MovementMode mode = from.mode();
        if (mode != to.mode()
                || isActionMode(mode)
                || from.blockPos().getY() != to.blockPos().getY()
                || isWoodenDoorWaypoint(snapshot, from)
                || isWoodenDoorWaypoint(snapshot, to)) {
            return false;
        }
        for (int index = fromIndex + 1; index < toIndex; index++) {
            PathWaypoint waypoint = waypoints.get(index);
            if (waypoint.mode() != mode
                    || isActionMode(waypoint.mode())
                    || waypoint.blockPos().getY() != from.blockPos().getY()
                    || isWoodenDoorWaypoint(snapshot, waypoint)) {
                return false;
            }
        }
        return hasClearWalkLine(snapshot, from.blockPos(), to.blockPos());
    }

    private static boolean isWoodenDoorWaypoint(PathSnapshot snapshot, PathWaypoint waypoint) {
        PathCell cell = snapshot.cell(waypoint.blockPos());
        return cell != null && cell.woodenDoor();
    }

    private static boolean hasClearWalkLine(PathSnapshot snapshot, BlockPos from, BlockPos to) {
        PathCell previous = snapshot.cell(from);
        PathCell target = snapshot.cell(to);
        if (previous == null || target == null || !canWalkOnSameLayer(previous, target)) {
            return false;
        }

        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        int footprintSteps = Math.max(1, (int) Math.ceil(horizontalDistance / WALK_CORRIDOR_SAMPLE_STEP));

        int lastX = from.getX();
        int lastZ = from.getZ();
        for (int step = 1; step <= footprintSteps; step++) {
            double ratio = (double) step / (double) footprintSteps;
            double sampleX = from.getX() + 0.5D + dx * ratio;
            double sampleZ = from.getZ() + 0.5D + dz * ratio;
            if (!hasFootprintClearance(snapshot, previous, sampleX, sampleZ)) {
                return false;
            }
            int x = (int) Math.floor(sampleX);
            int z = (int) Math.floor(sampleZ);
            if (x == lastX && z == lastZ) {
                continue;
            }
            int stepX = Integer.compare(x - lastX, 0);
            int stepZ = Integer.compare(z - lastZ, 0);
            if (Math.abs(x - lastX) > 1 || Math.abs(z - lastZ) > 1) {
                return false;
            }
            PathCell current = snapshot.cell(x, from.getY(), z);
            if (current == null || !canWalkOnSameLayer(previous, current)) {
                return false;
            }
            if (stepX != 0 && stepZ != 0 && !diagonalClear(snapshot, previous, stepX, stepZ)) {
                return false;
            }
            previous = current;
            lastX = x;
            lastZ = z;
        }
        return true;
    }

    private static boolean hasFootprintClearance(PathSnapshot snapshot, PathCell reference, double centerX, double centerZ) {
        return hasPointClearance(snapshot, reference, centerX - WALK_CORRIDOR_HALF_WIDTH, centerZ - WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(snapshot, reference, centerX - WALK_CORRIDOR_HALF_WIDTH, centerZ + WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(snapshot, reference, centerX + WALK_CORRIDOR_HALF_WIDTH, centerZ - WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(snapshot, reference, centerX + WALK_CORRIDOR_HALF_WIDTH, centerZ + WALK_CORRIDOR_HALF_WIDTH);
    }

    private static boolean hasPointClearance(PathSnapshot snapshot, PathCell reference, double x, double z) {
        PathCell cell = snapshot.cell((int) Math.floor(x), reference.y(), (int) Math.floor(z));
        return cell != null && canWalkOnSameLayer(reference, cell);
    }

    private static PathCell nearestCell(PathSnapshot snapshot, BlockPos target, int range) {
        PathCell direct = snapshot.cell(target);
        if (direct != null) {
            return direct;
        }
        PathCell best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PathCell cell : snapshot.allCells()) {
            int dx = Math.abs(cell.x() - target.getX());
            int dy = Math.abs(cell.y() - target.getY());
            int dz = Math.abs(cell.z() - target.getZ());
            if (dx > range || dy > range || dz > range) {
                continue;
            }
            double distance = cell.pos().distSqr(target);
            if (distance < bestDistance) {
                best = cell;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static MovementMode walkMode(MovementIntent intent) {
        return intent == MovementIntent.RUN ? MovementMode.RUN : MovementMode.WALK;
    }

    private static boolean isActionMode(MovementMode mode) {
        return mode == MovementMode.JUMP || mode == MovementMode.SWIM || mode == MovementMode.CLIMB || mode == MovementMode.FALL;
    }

    private static double heuristic(PathCell from, PathCell to) {
        return distance(from, to);
    }

    private static double distance(PathCell from, PathCell to) {
        double dx = from.x() - to.x();
        double dy = from.standY() - to.standY();
        double dz = from.z() - to.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record Neighbor(PathCell cell, MovementMode mode, double cost) {
    }

    private static final class SearchNode {
        private final PathCell cell;
        private final SearchNode parent;
        private final MovementMode mode;
        private final double gCost;
        private final double fCost;

        private SearchNode(PathCell cell, SearchNode parent, MovementMode mode, double gCost, double fCost) {
            this.cell = cell;
            this.parent = parent;
            this.mode = mode;
            this.gCost = gCost;
            this.fCost = fCost;
        }
    }
}
