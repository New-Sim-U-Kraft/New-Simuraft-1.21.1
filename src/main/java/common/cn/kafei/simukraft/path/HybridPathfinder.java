package common.cn.kafei.simukraft.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Grid based A* pathfinder that operates exclusively on an immutable {@link PathSnapshot}.
 *
 * <p>The search never touches the live world: every traversability decision is made from the
 * pre-sampled {@link PathCell} grid produced on the server thread. The resulting waypoint list is
 * geometrically smoothed so the follower can drive long straight segments, while keeping every
 * emitted edge provably walkable so the citizen never cuts a corner or crosses a gap it should
 * have routed around.
 */
final class HybridPathfinder {
    private static final int MAX_ITERATIONS = 20000;
    private static final int NEAREST_RANGE = 5;
    private static final int SMOOTH_LOOKAHEAD = 24;
    private static final double WALK_CORRIDOR_HALF_WIDTH = 0.36D;
    private static final double WALK_CORRIDOR_SAMPLE_STEP = 0.20D;
    private static final double STEP_CLEARANCE = 0.55D;
    private static final ThreadLocal<List<Neighbor>> NEIGHBOR_SCRATCH =
            ThreadLocal.withInitial(() -> new ArrayList<>(32));

    private HybridPathfinder() {
    }

    /**
     * Computes a path from the request start to its target over the given snapshot.
     *
     * @param request the movement request describing start, target and intent
     * @param snapshot the immutable world sample the search runs on
     * @return a successful result with smoothed waypoints, or a failed result with a reason code
     */
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
        Long2ObjectOpenHashMap<SearchNode> bestNodes = new Long2ObjectOpenHashMap<>();
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

    /**
     * Expands the traversable neighbours of a cell into a reusable scratch list.
     *
     * <p>The returned list is thread-local scratch storage that is cleared on every call, so a
     * caller must finish iterating it before invoking this method again. Because the search runs on
     * a fixed worker pool this avoids one list allocation per expanded node.
     */
    private static List<Neighbor> neighbors(PathSnapshot snapshot, PathCell current, PathCell target, MovementIntent intent) {
        List<Neighbor> neighbors = NEIGHBOR_SCRATCH.get();
        neighbors.clear();
        if (current.water()) {
            addWaterNeighbors(snapshot, current, intent, neighbors);
            return neighbors;
        }
        if (current.climbable()) {
            addClimbNeighbors(snapshot, current, intent, neighbors);
            addClimbTopExitNeighbors(snapshot, current, neighbors);
            addClimbDropOffNeighbors(snapshot, current, neighbors);
        }
        addWalkNeighbors(snapshot, current, intent, neighbors);
        addSpecialEntryNeighbors(snapshot, current, neighbors);
        addVerticalTransitions(snapshot, current, intent, neighbors);
        return neighbors;
    }

    /**
     * Adds 8-connected same-layer walk edges, validating each diagonal with a footprint corridor so
     * the body cannot clip the solid corner between two orthogonal blocks.
     */
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

    /**
     * Adds same-layer entries into water or climbable cells, guarding diagonals with the corner
     * test so the entry does not pass through a solid corner block.
     */
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

    /**
     * Adds vertical step-up ({@link MovementMode#JUMP}) and drop ({@link MovementMode#FALL})
     * transitions.
     *
     * <p>Only orthogonal directions are emitted. A diagonal jump or fall would sweep the body
     * through the shared corner column at the destination level, which the snapshot's per-column
     * clearance never validates, so such edges let the citizen visibly cut across a corner or pit
     * while changing height. Diagonal elevation changes remain reachable as an L-shaped composition
     * of an orthogonal walk followed by an orthogonal jump or fall, where each composing edge is
     * individually corner-safe.
     *
     * <p>This generator also mounts a ladder/vine/scaffold one level above or below an adjacent
     * floor: a citizen standing on the rim of a shaft must be able to step sideways-and-down onto
     * the first rung to descend (the common "hole in the floor with a ladder below the rim" build),
     * or sideways-and-up onto a rung whose lowest cell sits one block above the floor. These
     * {@code CLIMB} entries are orthogonal-only for the same corner-safety reason, and once the
     * citizen is on the ladder {@link #addClimbNeighbors} drives the vertical travel.
     */
    private static void addVerticalTransitions(PathSnapshot snapshot, PathCell current, MovementIntent intent, List<Neighbor> output) {
        if (current.climbable()) {
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx != 0 && dz != 0) {
                    continue;
                }
                PathCell up = snapshot.cell(current.x() + dx, current.y() + 1, current.z() + dz);
                if (up != null
                        && !up.water()
                        && !up.climbable()
                        && up.standY() - current.standY() <= 1.25D
                        && hasVerticalPassage(snapshot, current, up)) {
                    output.add(new Neighbor(up, MovementMode.JUMP, 2.5D + distance(current, up)));
                }
                if (up != null
                        && up.climbable()
                        && up.standY() - current.standY() <= 1.25D
                        && hasVerticalPassage(snapshot, current, up)) {
                    output.add(new Neighbor(up, MovementMode.CLIMB, 2.0D + distance(current, up)));
                }
                PathCell ladderBelow = snapshot.cell(current.x() + dx, current.y() - 1, current.z() + dz);
                if (ladderBelow != null
                        && ladderBelow.climbable()
                        && current.standY() - ladderBelow.standY() <= 1.25D
                        && hasVerticalPassage(snapshot, current, ladderBelow)) {
                    output.add(new Neighbor(ladderBelow, MovementMode.CLIMB, 2.0D + distance(current, ladderBelow)));
                }
                for (int fall = 1; fall <= 3; fall++) {
                    PathCell down = snapshot.cell(current.x() + dx, current.y() - fall, current.z() + dz);
                    if (down != null
                            && !down.climbable()
                            && current.standY() - down.standY() <= 3.5D
                            && hasVerticalPassage(snapshot, current, down)) {
                        output.add(new Neighbor(down, down.water() ? MovementMode.SWIM : MovementMode.FALL, 1.2D + fall));
                        break;
                    }
                }
            }
        }
        PathCell waterBelow = snapshot.cell(current.x(), current.y() - 1, current.z());
        if (waterBelow != null && waterBelow.water() && hasVerticalPassage(snapshot, current, waterBelow)) {
            output.add(new Neighbor(waterBelow, MovementMode.SWIM, 1.8D));
        }
    }

    /**
     * Adds movement edges that leave or traverse a water cell.
     *
     * <p>The {@link #neighbors} dispatcher returns early for water cells, so this generator is the
     * sole source of water-exit edges and must apply the same guards the land generators do.
     * Water-to-water moves stay permissive so narrow canals remain navigable, but a diagonal still
     * requires both orthogonal corner columns. A same-layer walk exit reuses the corridor check, an
     * adjacent ladder/vine/scaffold is entered with a {@code CLIMB} edge so the swimmer can climb
     * out of a pool, and vertical (jump/fall) exits are emitted for orthogonal directions only,
     * mirroring {@link #addVerticalTransitions} so the body never cuts the destination-level corner.
     */
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
                    boolean diagonal = dx != 0 && dz != 0;
                    if (diagonal && !diagonalClear(snapshot, current, dx, dz)) {
                        continue;
                    }
                    if (next.water()) {
                        output.add(new Neighbor(next, MovementMode.SWIM, distance(current, next) * next.cost()));
                        continue;
                    }
                    if (next.climbable()) {
                        // Grab an adjacent ladder/vine/scaffold so the swimmer can climb out; the
                        // climb generator then drives the ascent. A pure 3D diagonal is skipped
                        // because its destination-level corner cannot be validated.
                        if (dy == 0 || !diagonal) {
                            output.add(new Neighbor(next, MovementMode.CLIMB, 1.0D + distance(current, next) * next.cost()));
                        }
                        continue;
                    }
                    if (dy == 0) {
                        if (!canWalkOnSameLayer(current, next)) {
                            continue;
                        }
                        if (diagonal && !hasClearWalkLine(snapshot, current.pos(), next.pos())) {
                            continue;
                        }
                        output.add(new Neighbor(next, walkMode(intent), distance(current, next) * next.cost()));
                    } else if (dy == 1) {
                        // Orthogonal only, matching addVerticalTransitions: a diagonal jump would
                        // sweep the body through the destination-level corner column.
                        if (!diagonal
                                && next.standY() - current.standY() <= 1.25D
                                && hasVerticalPassage(snapshot, current, next)) {
                            output.add(new Neighbor(next, MovementMode.JUMP, 2.5D + distance(current, next)));
                        }
                    } else {
                        if (!diagonal
                                && current.standY() - next.standY() <= 3.5D
                                && hasVerticalPassage(snapshot, current, next)) {
                            output.add(new Neighbor(next, MovementMode.FALL, 1.2D + distance(current, next)));
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds the straight up and down transitions available while climbing a ladder, vine or
     * scaffold.
     *
     * <p>The downward edge mirrors the upward edge's water handling: descending off the lowest rung
     * into a water column is a {@link MovementMode#SWIM}, not a {@code WALK} the follower would drive
     * as a flat land step into the pool.
     */
    private static void addClimbNeighbors(PathSnapshot snapshot, PathCell current, MovementIntent intent, List<Neighbor> output) {
        PathCell up = snapshot.cell(current.x(), current.y() + 1, current.z());
        if (up != null && (up.climbable() || up.water())) {
            output.add(new Neighbor(up, up.water() ? MovementMode.SWIM : MovementMode.CLIMB, 2.0D));
        }
        PathCell down = snapshot.cell(current.x(), current.y() - 1, current.z());
        if (down != null) {
            MovementMode downMode = down.water() ? MovementMode.SWIM : down.climbable() ? MovementMode.CLIMB : walkMode(intent);
            output.add(new Neighbor(down, downMode, 2.0D));
        }
    }

    /**
     * Adds a virtual climb point above the top rung when the ladder stops one block below a floor.
     */
    private static void addClimbTopExitNeighbors(PathSnapshot snapshot, PathCell current, List<Neighbor> output) {
        PathCell sampledCurrent = snapshot.cell(current.pos());
        if (sampledCurrent == null || !sampledCurrent.climbable()) {
            return;
        }
        int exitY = current.y() + 1;
        PathCell sampledTop = snapshot.cell(current.x(), exitY, current.z());
        if (sampledTop != null && (sampledTop.climbable() || sampledTop.water())) {
            return;
        }
        if (sampledTop == null && !snapshot.bodyPassage(current.x(), exitY, current.z())) {
            return;
        }
        PathCell topExit = sampledTop != null
                ? sampledTop
                : new PathCell(new BlockPos(current.x(), exitY, current.z()),
                current.x(), exitY, current.z(), exitY, false, true, false, 2.0D);
        if (hasUpperFloorExit(snapshot, topExit)) {
            output.add(new Neighbor(topExit, MovementMode.CLIMB, 2.0D));
        }
    }

    /**
     * hasUpperFloorExit: 判断虚拟梯顶旁边是否存在可走上层地板。
     */
    private static boolean hasUpperFloorExit(PathSnapshot snapshot, PathCell virtualTop) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || dx != 0 && dz != 0) {
                    continue;
                }
                PathCell exit = snapshot.cell(virtualTop.x() + dx, virtualTop.y(), virtualTop.z() + dz);
                if (canWalkOnSameLayer(virtualTop, exit)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds the controlled drop a citizen can take when stepping OFF a ladder/vine/scaffold onto a
     * floor that is one to three blocks lower and orthogonally adjacent.
     *
     * <p>{@link #addVerticalTransitions} early-returns for climbable cells and {@link
     * #addClimbNeighbors} only travels straight up/down the shaft, so without this edge a ladder
     * whose base sits above an offset ledge (the directly-below column blocked) is a dead end. Like
     * the rest of the vertical generators it is orthogonal-only so the body never cuts the
     * destination-level corner, it reuses {@link #hasVerticalPassage} so it cannot drill through a
     * solid floor, and a landing in water is a {@link MovementMode#SWIM}.
     */
    private static void addClimbDropOffNeighbors(PathSnapshot snapshot, PathCell current, List<Neighbor> output) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || dx != 0 && dz != 0) {
                    continue;
                }
                for (int fall = 1; fall <= 3; fall++) {
                    PathCell down = snapshot.cell(current.x() + dx, current.y() - fall, current.z() + dz);
                    if (down != null
                            && !down.climbable()
                            && current.standY() - down.standY() <= 3.5D
                            && hasVerticalPassage(snapshot, current, down)) {
                        output.add(new Neighbor(down, down.water() ? MovementMode.SWIM : MovementMode.FALL, 1.2D + fall));
                        break;
                    }
                }
            }
        }
    }

    /**
     * Returns whether both orthogonal columns flanking a diagonal step exist at the current level,
     * i.e. the diagonal does not clip the solid corner block between them.
     */
    private static boolean diagonalClear(PathSnapshot snapshot, PathCell current, int dx, int dz) {
        return snapshot.cell(current.x() + dx, current.y(), current.z()) != null
                && snapshot.cell(current.x(), current.y(), current.z() + dz) != null;
    }

    /**
     * Returns whether the target column has an actual open shaft for a vertical transition.
     */
    private static boolean hasVerticalPassage(PathSnapshot snapshot, PathCell from, PathCell to) {
        int minY = Math.min(from.y(), to.y()) + 1;
        int maxY = Math.max(from.y(), to.y());
        for (int y = minY; y <= maxY; y++) {
            if (!snapshot.bodyPassage(to.x(), y, to.z())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether {@code to} is a solid-floored cell the citizen can step onto from {@code from}
     * without changing layer, i.e. it is not water or climbable and the stand height differs by at
     * most a single step.
     */
    private static boolean canWalkOnSameLayer(PathCell from, PathCell to) {
        return to != null
                && !to.water()
                && !to.climbable()
                && Math.abs(from.standY() - to.standY()) <= 0.75D;
    }

    /**
     * Rebuilds the ordered waypoint list by walking parent links back from the goal node.
     */
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

    /**
     * Collapses runs of collinear, same-mode waypoints into straight segments the follower can
     * drive directly, while preserving every waypoint a merge would unsafely skip.
     */
    private static List<PathWaypoint> smooth(PathSnapshot snapshot, List<PathWaypoint> rawWaypoints) {
        if (rawWaypoints.size() <= 2) {
            return rawWaypoints;
        }
        boolean[] woodenDoor = new boolean[rawWaypoints.size()];
        for (int index = 0; index < woodenDoor.length; index++) {
            woodenDoor[index] = isWoodenDoorWaypoint(snapshot, rawWaypoints.get(index));
        }
        List<PathWaypoint> smoothed = new ArrayList<>(rawWaypoints.size());
        int anchor = 0;
        smoothed.add(rawWaypoints.get(anchor));
        while (anchor < rawWaypoints.size() - 1) {
            int runCap = contiguousRunCap(rawWaypoints, woodenDoor, anchor);
            int maxCandidate = Math.min(runCap, anchor + SMOOTH_LOOKAHEAD);
            int best = anchor + 1;
            for (int candidate = maxCandidate; candidate > anchor + 1; candidate--) {
                if (canSmoothSegment(snapshot, rawWaypoints, anchor, candidate, woodenDoor)) {
                    best = candidate;
                    break;
                }
            }
            smoothed.add(rawWaypoints.get(best));
            anchor = best;
        }
        return smoothed;
    }

    /**
     * Returns the furthest waypoint index reachable from {@code anchor} for which every waypoint in
     * between shares the anchor's mode and feet level and is neither an action waypoint nor a wooden
     * door. Candidates past this index can never merge, so the smoothing probe stops there instead
     * of re-running the cheap checks for each candidate.
     */
    private static int contiguousRunCap(List<PathWaypoint> waypoints, boolean[] woodenDoor, int anchor) {
        PathWaypoint from = waypoints.get(anchor);
        if (isActionMode(from.mode()) || woodenDoor[anchor]) {
            return anchor + 1;
        }
        MovementMode mode = from.mode();
        int feetY = from.blockPos().getY();
        int cap = anchor;
        while (cap + 1 <= waypoints.size() - 1) {
            int candidate = cap + 1;
            PathWaypoint waypoint = waypoints.get(candidate);
            if (waypoint.mode() != mode
                    || isActionMode(waypoint.mode())
                    || waypoint.blockPos().getY() != feetY
                    || woodenDoor[candidate]) {
                break;
            }
            cap = candidate;
        }
        return Math.max(anchor + 1, cap);
    }

    /**
     * Determines whether the waypoints in {@code [fromIndex, toIndex]} can be merged into a single
     * straight segment.
     *
     * <p>A merge is allowed only when every intermediate waypoint shares the endpoints' movement
     * mode and feet level, is not an action waypoint or wooden door, stays within
     * {@link #STEP_CLEARANCE} of the interpolated endpoint chord, and the straight corridor between
     * the endpoints is clear. The chord check is essential: {@link #hasClearWalkLine} only bounds
     * the height change between consecutive cells, so without it a 64.0 to 64.75 to 64.0 run would
     * merge and the follower would drive a flat line straight through the 0.75 lip.
     */
    private static boolean canSmoothSegment(PathSnapshot snapshot, List<PathWaypoint> waypoints, int fromIndex, int toIndex, boolean[] woodenDoor) {
        PathWaypoint from = waypoints.get(fromIndex);
        PathWaypoint to = waypoints.get(toIndex);
        MovementMode mode = from.mode();
        if (mode != to.mode()
                || isActionMode(mode)
                || from.blockPos().getY() != to.blockPos().getY()
                || woodenDoor[fromIndex]
                || woodenDoor[toIndex]) {
            return false;
        }
        Vec3 fromPosition = from.position();
        Vec3 toPosition = to.position();
        double segmentX = toPosition.x - fromPosition.x;
        double segmentZ = toPosition.z - fromPosition.z;
        for (int index = fromIndex + 1; index < toIndex; index++) {
            PathWaypoint waypoint = waypoints.get(index);
            if (waypoint.mode() != mode
                    || isActionMode(waypoint.mode())
                    || waypoint.blockPos().getY() != from.blockPos().getY()
                    || woodenDoor[index]) {
                return false;
            }
            if (exceedsChordStep(fromPosition, toPosition, waypoint.position(), segmentX, segmentZ)) {
                return false;
            }
        }
        return hasClearWalkLine(snapshot, from.blockPos(), to.blockPos());
    }

    /**
     * Returns whether the stand height of {@code mid} rises more than {@link #STEP_CLEARANCE} above
     * the height the follower would interpolate at that point along the {@code from}-to-{@code to}
     * chord. Progress is measured on the dominant horizontal axis to stay stable for axis-aligned
     * segments.
     */
    private static boolean exceedsChordStep(Vec3 from, Vec3 to, Vec3 mid, double segmentX, double segmentZ) {
        double progress;
        if (Math.abs(segmentX) >= Math.abs(segmentZ)) {
            progress = segmentX != 0.0D ? (mid.x - from.x) / segmentX : 0.0D;
        } else {
            progress = segmentZ != 0.0D ? (mid.z - from.z) / segmentZ : 0.0D;
        }
        progress = Math.max(0.0D, Math.min(1.0D, progress));
        double chordY = from.y + (to.y - from.y) * progress;
        return mid.y - chordY > STEP_CLEARANCE;
    }

    /**
     * Returns whether the waypoint stands on a wooden door cell, which must never be merged away
     * because the follower opens the door as it arrives.
     */
    private static boolean isWoodenDoorWaypoint(PathSnapshot snapshot, PathWaypoint waypoint) {
        PathCell cell = snapshot.cell(waypoint.blockPos());
        return cell != null && cell.woodenDoor();
    }

    /**
     * Returns whether a citizen can walk the straight line from {@code from} to {@code to} on a
     * single layer without leaving the walkable floor.
     *
     * <p>The corridor is sampled at sub-block resolution; at each sample the full footprint corners
     * must rest on a same-layer cell, every entered cell must be walkable from the previous one, no
     * sample may jump more than one cell, and diagonal transitions must pass the corner test.
     */
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

    /**
     * Returns whether the citizen's square footprint, centred at the sample point, rests entirely
     * on cells that are walkable on the reference cell's layer.
     */
    private static boolean hasFootprintClearance(PathSnapshot snapshot, PathCell reference, double centerX, double centerZ) {
        return hasPointClearance(snapshot, reference, centerX - WALK_CORRIDOR_HALF_WIDTH, centerZ - WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(snapshot, reference, centerX - WALK_CORRIDOR_HALF_WIDTH, centerZ + WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(snapshot, reference, centerX + WALK_CORRIDOR_HALF_WIDTH, centerZ - WALK_CORRIDOR_HALF_WIDTH)
                && hasPointClearance(snapshot, reference, centerX + WALK_CORRIDOR_HALF_WIDTH, centerZ + WALK_CORRIDOR_HALF_WIDTH);
    }

    /**
     * Returns whether the cell under a single footprint corner exists and is walkable on the
     * reference cell's layer.
     */
    private static boolean hasPointClearance(PathSnapshot snapshot, PathCell reference, double x, double z) {
        PathCell cell = snapshot.cell((int) Math.floor(x), reference.y(), (int) Math.floor(z));
        return cell != null && canWalkOnSameLayer(reference, cell);
    }

    /**
     * Returns the snapshot cell at {@code target}, or the closest cell within {@code range} when the
     * exact block is not part of the grid.
     */
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

    /**
     * Maps a movement intent to the walking mode used for ground edges.
     */
    private static MovementMode walkMode(MovementIntent intent) {
        return intent == MovementIntent.RUN ? MovementMode.RUN : MovementMode.WALK;
    }

    /**
     * Returns whether the mode performs a discrete action that must not be smoothed away.
     */
    private static boolean isActionMode(MovementMode mode) {
        return mode == MovementMode.JUMP || mode == MovementMode.SWIM || mode == MovementMode.CLIMB || mode == MovementMode.FALL;
    }

    /**
     * Admissible heuristic: the straight-line distance between two cells.
     */
    private static double heuristic(PathCell from, PathCell to) {
        return distance(from, to);
    }

    /**
     * Returns the Euclidean distance between two cells using their stand heights.
     */
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
