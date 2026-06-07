package common.cn.kafei.simukraft.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link HybridPathfinder} that lock in the corner-cutting / gap-crossing fixes
 * ("citizen crosses over block positions more than 2 cells away").
 *
 * <p>Each scenario hand-builds an immutable {@link PathSnapshot}; no live world is needed because the
 * pathfinder reasons purely about the snapshot. The four scenarios mirror the requested cases — a
 * pit, a waterside, a step lip and a wall corner — and every assertion is an independent oracle that
 * fails on the pre-fix behaviour and passes on the fixed behaviour.
 */
@SuppressWarnings("null")
class HybridPathfinderRegressionTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    /**
     * Pit: a floor with a three-wide hole in the middle lane. The citizen must detour around it and
     * no walk segment may pass over the hole.
     */
    @Test
    void pitIsRoutedAroundNeverCrossed() {
        Scene scene = new Scene();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 2; z++) {
                boolean pit = z == 1 && x >= 2 && x <= 4;
                if (!pit) {
                    scene.floor(x, 64, z);
                }
            }
        }
        PathCase result = scene.path(0, 64, 1, 6, 64, 1);
        assertSuccess(result);
        assertWalkSegmentsStayOnFloor(result);
        assertNoDiagonalCornerCut(result);
    }

    /**
     * Wall corner: a one-wide L corridor whose inside corner is solid. The diagonal shortcut would
     * clip the missing corner, so the path must round it.
     */
    @Test
    void wallCornerIsNotCut() {
        Scene scene = new Scene();
        scene.floor(0, 64, 0).floor(0, 64, 1).floor(0, 64, 2).floor(1, 64, 2).floor(2, 64, 2);
        PathCase result = scene.path(0, 64, 0, 2, 64, 2);
        assertSuccess(result);
        assertNoDiagonalCornerCut(result);
        assertWalkSegmentsStayOnFloor(result);
    }

    /**
     * Waterside: a swimmer exits onto land whose diagonal corner block is solid. The exit must not
     * cut the corner and must not be driven as a flat diagonal walk.
     */
    @Test
    void waterExitDoesNotCutCorner() {
        Scene scene = new Scene();
        scene.water(0, 64, 0).water(0, 64, 1).floor(1, 64, 1);
        // The corner column (1, 64, 0) is intentionally absent (solid).
        PathCase result = scene.path(0, 64, 0, 1, 64, 1);
        assertSuccess(result);
        assertNoDiagonalCornerCut(result);
        assertNoDiagonalVerticalTransitions(result);
    }

    /**
     * Step lip: three same-feet cells where the middle stands higher than the chord between the
     * ends. Smoothing must keep the lip waypoint instead of driving a flat line through it.
     */
    @Test
    void smoothingKeepsRaisedStepLip() {
        Scene scene = new Scene();
        scene.cell(0, 64, 0, 63.1D, false, false, false, 1.0D);
        scene.cell(1, 64, 0, 63.8D, false, false, false, 1.0D);
        scene.cell(2, 64, 0, 63.1D, false, false, false, 1.0D);
        PathCase result = scene.path(0, 64, 0, 2, 64, 0);
        assertSuccess(result);
        assertTrue(containsBlock(result.result(), 1, 64, 0),
                "smoothing dropped the raised lip; the follower would walk a flat line through it");
    }

    /**
     * Half slab: a 0.5 block rise on the same feet layer should remain ordinary walking, not a jump
     * action that makes the follower over-centre and spin on the slab.
     */
    @Test
    void halfSlabHeightStaysSameLayerWalk() {
        Scene scene = new Scene();
        scene.floor(0, 64, 0);
        scene.cell(1, 64, 0, 64.5D, false, false, false, 1.0D);
        scene.floor(2, 64, 0);
        PathCase result = scene.path(0, 64, 0, 2, 64, 0);
        assertSuccess(result);
        assertNoActionMode(result);
    }

    /**
     * Wooden door: the door cell must survive smoothing so the follower can open it on arrival.
     */
    @Test
    void smoothingKeepsWoodenDoorWaypoint() {
        Scene scene = new Scene();
        scene.floor(0, 64, 0).woodenDoor(1, 64, 0).floor(2, 64, 0);
        PathCase result = scene.path(0, 64, 0, 2, 64, 0);
        assertSuccess(result);
        assertTrue(containsBlock(result.result(), 1, 64, 0),
                "smoothing dropped the wooden door waypoint; the follower would not open it");
    }

    /**
     * Door policy: door-like blocks are passable only when their current state is already passable.
     */
    @Test
    void doorLikePassageUsesCurrentPassableState() {
        BlockState openWoodenDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN, true);
        BlockState closedWoodenDoor = openWoodenDoor.setValue(DoorBlock.OPEN, false);
        BlockState openIronDoor = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN, true);
        BlockState closedIronDoor = openIronDoor.setValue(DoorBlock.OPEN, false);
        BlockState openFenceGate = Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.OPEN, true);
        BlockState closedFenceGate = openFenceGate.setValue(FenceGateBlock.OPEN, false);
        BlockState openTrapDoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true);
        BlockState closedTrapDoor = openTrapDoor.setValue(TrapDoorBlock.OPEN, false);

        assertTrue(PathSnapshotBuilder.isNpcPassableDoorLikeBlock(openWoodenDoor));
        assertFalse(PathSnapshotBuilder.isNpcPassableDoorLikeBlock(closedWoodenDoor));
        assertTrue(PathSnapshotBuilder.isNpcPassableDoorLikeBlock(openIronDoor));
        assertFalse(PathSnapshotBuilder.isNpcPassableDoorLikeBlock(closedIronDoor));
        assertTrue(PathSnapshotBuilder.isNpcPassableDoorLikeBlock(openFenceGate));
        assertFalse(PathSnapshotBuilder.isNpcPassableDoorLikeBlock(closedFenceGate));
        assertTrue(PathSnapshotBuilder.isNpcPassableDoorLikeBlock(openTrapDoor));
        assertFalse(PathSnapshotBuilder.isNpcPassableDoorLikeBlock(closedTrapDoor));
    }

    /**
     * Door collision: a side-placed closed door can leave the occupied centre slice clear.
     */
    @Test
    void doorLikePassageFallsBackToCollisionSlice() {
        BlockState closedIronDoor = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN, false);
        VoxelShape doorShape = closedIronDoor.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        BlockState closedFenceGate = Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.OPEN, false);
        VoxelShape gateShape = closedFenceGate.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);

        assertTrue(PathSnapshotBuilder.isNpcPassableDoorLikeBlock(closedIronDoor, doorShape, 0.0D, 1.0D),
                "side-placed door collision was treated as blocking the centre slice");
        assertFalse(PathSnapshotBuilder.isNpcPassableDoorLikeBlock(closedFenceGate, gateShape, 0.0D, 1.0D),
                "closed fence gate should still block the centre slice");
    }

    /**
     * Trapdoor collision: a closed top trapdoor above the head does not block the occupied slice.
     */
    @Test
    void trapdoorPassageUsesOccupiedVerticalSlice() {
        BlockState closedTopTrapDoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, false)
                .setValue(TrapDoorBlock.HALF, Half.TOP);
        VoxelShape shape = closedTopTrapDoor.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);

        assertTrue(PathSnapshotBuilder.clearsNpcBodySlice(shape, 0.0D, 0.8D),
                "top trapdoor above the head was treated as a head blocker");
        assertFalse(PathSnapshotBuilder.clearsNpcBodySlice(shape, 0.0D, 1.0D),
                "top trapdoor in the feet block should still block the full foot slice");
    }

    /**
     * Diagonal drop: a one-block drop sits diagonally across an open corner. The descent must be
     * routed as an orthogonal walk-then-fall, never a single diagonal fall.
     */
    @Test
    void diagonalDropIsRoutedOrthogonally() {
        Scene scene = new Scene();
        scene.floor(0, 64, 0).floor(1, 64, 0).floor(0, 64, 1).floor(1, 63, 1);
        scene.passage(1, 64, 1);
        PathCase result = scene.path(0, 64, 0, 1, 63, 1);
        assertSuccess(result);
        assertNoDiagonalVerticalTransitions(result);
    }

    /**
     * Multi-floor drop: a lower floor is reachable only when the target column has a real open
     * shaft through every intermediate layer.
     */
    @Test
    void multiFloorDropRequiresOpenShaft() {
        Scene openShaft = new Scene();
        openShaft.floor(0, 65, 0).floor(1, 62, 0)
                .passage(1, 63, 0).passage(1, 64, 0).passage(1, 65, 0);
        PathCase openResult = openShaft.path(0, 65, 0, 1, 62, 0);
        assertSuccess(openResult);
        assertTrue(openResult.result().waypoints().stream().anyMatch(waypoint -> waypoint.mode() == MovementMode.FALL),
                "open shaft did not produce a controlled fall");

        Scene blockedFloor = new Scene();
        blockedFloor.floor(0, 65, 0).floor(1, 65, 0).floor(1, 62, 0);
        PathCase blockedResult = blockedFloor.path(0, 65, 0, 1, 62, 0);
        assertFalse(blockedResult.result().success(), "path drilled through a floor without an opening");
    }

    /**
     * Waterside, elevation change: a swimmer climbs out onto a ledge one block up whose diagonal
     * corner is solid. The exit must climb orthogonally, never as a single diagonal jump.
     */
    @Test
    void waterExitElevationStaysOrthogonal() {
        Scene scene = new Scene();
        scene.water(0, 64, 0).water(0, 64, 1).water(1, 64, 0);
        scene.cell(1, 65, 1, 65.0D, false, false, false, 1.0D);
        // Destination-level corners (1,65,0) and (0,65,1) are intentionally absent (solid).
        PathCase result = scene.path(0, 64, 0, 1, 65, 1);
        assertSuccess(result);
        assertNoDiagonalVerticalTransitions(result);
    }

    /**
     * Waterside, ladder exit: a swimmer in a one-wide pool whose only way out is a side-wall ladder
     * must be able to grab the ladder and climb out instead of being trapped.
     */
    @Test
    void waterExitReachesSideLadder() {
        Scene scene = new Scene();
        scene.water(0, 64, 0).climb(1, 64, 0).climb(1, 65, 0).floor(2, 65, 0);
        PathCase result = scene.path(0, 64, 0, 2, 65, 0);
        assertSuccess(result);
        assertEquals(MovementMode.WALK, lastWaypoint(result).mode(), "ladder exit did not end on a walkable floor");
    }

    /**
     * Ladder descent: a citizen on an upper floor must keep the lower ladder waypoint instead of
     * treating it as already reached from one block above.
     */
    @Test
    void ladderCanDescendToLowerFloor() {
        Scene scene = new Scene();
        scene.floor(0, 65, 0).climb(1, 65, 0).climb(1, 64, 0).floor(2, 64, 0);
        PathCase result = scene.path(0, 65, 0, 2, 64, 0);
        assertSuccess(result);
        assertTrue(result.result().waypoints().stream()
                        .anyMatch(waypoint -> waypoint.mode() == MovementMode.CLIMB && waypoint.blockPos().getY() == 64),
                "downward ladder waypoint was skipped");
        assertEquals(MovementMode.WALK, lastWaypoint(result).mode(), "downward ladder exit did not end on a walkable floor");
    }

    /**
     * Ladder descent below the floor rim: the ladder's top rung sits one block BELOW the standing
     * floor (no climbable cell at floor level), the common "hole in the floor with a ladder" build.
     * The citizen must step orthogonally down onto the first rung and climb out at the bottom.
     */
    @Test
    void ladderDescentBelowFloorRim() {
        Scene scene = new Scene();
        scene.floor(0, 65, 0).climb(1, 64, 0).climb(1, 63, 0).floor(2, 63, 0);
        scene.passage(1, 65, 0); // open shaft above the first rung so the citizen can step in
        PathCase result = scene.path(0, 65, 0, 2, 63, 0);
        assertSuccess(result);
        assertNoDiagonalClimbElevation(result);
        assertTrue(result.result().waypoints().stream()
                        .anyMatch(waypoint -> waypoint.mode() == MovementMode.CLIMB && waypoint.blockPos().getY() <= 64),
                "below-rim descent never mounted the ladder");
        assertEquals(MovementMode.WALK, lastWaypoint(result).mode(), "descent did not end on a walkable floor");
    }

    /**
     * Ladder base drop-off: the lowest rung sits above an offset ledge whose directly-below column is
     * blocked. The citizen must take a controlled orthogonal fall off the ladder onto the lower floor.
     */
    @Test
    void ladderDropOffBottomOntoLowerFloor() {
        Scene scene = new Scene();
        scene.floor(0, 66, 0).climb(1, 66, 0).climb(1, 65, 0).floor(2, 64, 0);
        // (1,64,0) intentionally absent: the directly-below column is blocked.
        scene.passage(2, 65, 0); // open shaft above the offset landing
        PathCase result = scene.path(0, 66, 0, 2, 64, 0);
        assertSuccess(result);
        assertNoDiagonalVerticalTransitions(result);
        assertTrue(result.result().waypoints().stream().anyMatch(waypoint -> waypoint.mode() == MovementMode.FALL),
                "ladder base did not drop off onto the lower floor");
        assertTrue(containsBlock(result.result(), 2, 64, 0), "drop-off never reached the lower floor");
    }

    /**
     * Ladder descent into water: stepping off the lowest rung into a water column must swim, not be
     * mislabeled as a flat land walk into the pool.
     */
    @Test
    void ladderDescentIntoWaterSwims() {
        Scene scene = new Scene();
        scene.floor(0, 65, 0).climb(1, 65, 0).climb(1, 64, 0).water(1, 63, 0);
        PathCase result = scene.path(0, 65, 0, 1, 63, 0);
        assertSuccess(result);
        assertEquals(MovementMode.SWIM, lastWaypoint(result).mode(),
                "descending off the ladder into water must swim, not walk");
    }

    /**
     * Ladder ascent from the floor onto a rung one block up: mounting a ladder whose lowest cell sits
     * one block above the standing floor must produce an orthogonal CLIMB entry.
     */
    @Test
    void ladderAscentFromFloorToRaisedRung() {
        Scene scene = new Scene();
        scene.floor(0, 64, 0).climb(1, 65, 0).climb(1, 66, 0).floor(2, 66, 0);
        PathCase result = scene.path(0, 64, 0, 2, 66, 0);
        assertSuccess(result);
        assertNoDiagonalClimbElevation(result);
        assertTrue(result.result().waypoints().stream()
                        .anyMatch(waypoint -> waypoint.mode() == MovementMode.CLIMB && waypoint.blockPos().getY() >= 65),
                "citizen never mounted the raised ladder rung");
    }

    /**
     * Ladder top exit: the top rung may stop one block below the upper floor, leaving only an air
     * shaft at floor height. The pathfinder must still climb to the opening and step onto the floor.
     */
    @Test
    void ladderAscentExitsWithoutFloorHeightRung() {
        Scene scene = new Scene();
        scene.floor(0, 63, 0).climb(1, 64, 0).passage(1, 65, 0).floor(2, 65, 0);
        PathCase result = scene.path(0, 63, 0, 2, 65, 0);
        assertSuccess(result);
        assertNoDiagonalClimbElevation(result);
        assertTrue(containsBlock(result.result(), 1, 65, 0),
                "path never climbed through the air opening above the top rung");
        assertEquals(MovementMode.WALK, lastWaypoint(result).mode(), "top ladder exit did not end on the upper floor");
    }

    /**
     * Ladder top exit with sampled opening: live snapshots can classify the air block above the top
     * rung as an ordinary body cell. It still needs a CLIMB edge from the rung into that opening.
     */
    @Test
    void ladderAscentExitsThroughSampledOpeningWithoutFloorHeightRung() {
        Scene scene = new Scene();
        scene.floor(0, 63, 0).climb(1, 64, 0)
                .cell(1, 65, 0, 65.0D, false, false, false, 1.0D)
                .floor(2, 65, 0);
        PathCase result = scene.path(0, 63, 0, 2, 65, 0);
        assertSuccess(result);
        assertNoDiagonalClimbElevation(result);
        assertTrue(result.result().waypoints().stream()
                        .anyMatch(waypoint -> waypoint.mode() == MovementMode.CLIMB && waypoint.blockPos().equals(new BlockPos(1, 65, 0))),
                "sampled top opening was not reached by a climb edge");
        assertEquals(MovementMode.WALK, lastWaypoint(result).mode(), "sampled top opening did not lead onto the upper floor");
    }

    private static void assertSuccess(PathCase pathCase) {
        assertTrue(pathCase.result().success(), () -> "path failed: " + pathCase.result().reason());
        assertFalse(pathCase.result().waypoints().isEmpty(), "path produced no waypoints");
    }

    /**
     * Asserts no jump or fall waypoint is reached by a diagonal step (the orthogonal-only invariant).
     */
    private static void assertNoDiagonalVerticalTransitions(PathCase pathCase) {
        List<PathWaypoint> waypoints = pathCase.result().waypoints();
        for (int index = 1; index < waypoints.size(); index++) {
            MovementMode mode = waypoints.get(index).mode();
            if (mode != MovementMode.JUMP && mode != MovementMode.FALL) {
                continue;
            }
            BlockPos from = waypoints.get(index - 1).blockPos();
            BlockPos to = waypoints.get(index).blockPos();
            boolean diagonal = from.getX() != to.getX() && from.getZ() != to.getZ();
            assertFalse(diagonal, () -> "diagonal " + mode + " transition " + from + " -> " + to);
        }
    }

    /**
     * Asserts no CLIMB waypoint that CHANGES elevation is reached by a diagonal step. Mounting or
     * leaving a ladder one level up or down must stay orthogonal for the same corner-safety reason as
     * JUMP/FALL; a same-level diagonal entry into an adjacent ladder remains allowed because it is
     * corner-guarded by {@code diagonalClear}.
     */
    private static void assertNoDiagonalClimbElevation(PathCase pathCase) {
        List<PathWaypoint> waypoints = pathCase.result().waypoints();
        for (int index = 1; index < waypoints.size(); index++) {
            if (waypoints.get(index).mode() != MovementMode.CLIMB) {
                continue;
            }
            BlockPos from = waypoints.get(index - 1).blockPos();
            BlockPos to = waypoints.get(index).blockPos();
            if (from.getY() == to.getY()) {
                continue;
            }
            boolean diagonal = from.getX() != to.getX() && from.getZ() != to.getZ();
            assertFalse(diagonal, () -> "diagonal elevation CLIMB transition " + from + " -> " + to);
        }
    }

    /**
     * Asserts every single-step diagonal in the path has both orthogonal corner cells present.
     */
    private static void assertNoDiagonalCornerCut(PathCase pathCase) {
        List<PathWaypoint> waypoints = pathCase.result().waypoints();
        for (int index = 1; index < waypoints.size(); index++) {
            BlockPos from = waypoints.get(index - 1).blockPos();
            BlockPos to = waypoints.get(index).blockPos();
            if (Math.abs(to.getX() - from.getX()) != 1 || Math.abs(to.getZ() - from.getZ()) != 1) {
                continue;
            }
            int stepX = Integer.signum(to.getX() - from.getX());
            int stepZ = Integer.signum(to.getZ() - from.getZ());
            boolean cornerClear = pathCase.snapshot().cell(from.getX() + stepX, from.getY(), from.getZ()) != null
                    && pathCase.snapshot().cell(from.getX(), from.getY(), from.getZ() + stepZ) != null;
            assertTrue(cornerClear, () -> "diagonal step " + from + " -> " + to + " cuts a missing corner");
        }
    }

    /**
     * Asserts every same-level walk/run segment stays over an existing walkable cell along its whole
     * straight line, i.e. the citizen never strides across a gap.
     */
    private static void assertWalkSegmentsStayOnFloor(PathCase pathCase) {
        List<PathWaypoint> waypoints = pathCase.result().waypoints();
        for (int index = 1; index < waypoints.size(); index++) {
            PathWaypoint from = waypoints.get(index - 1);
            PathWaypoint to = waypoints.get(index);
            if (isActionMode(to.mode()) || from.blockPos().getY() != to.blockPos().getY()) {
                continue;
            }
            int level = to.blockPos().getY();
            double originX = from.blockPos().getX() + 0.5D;
            double originZ = from.blockPos().getZ() + 0.5D;
            double spanX = to.blockPos().getX() - from.blockPos().getX();
            double spanZ = to.blockPos().getZ() - from.blockPos().getZ();
            int samples = Math.max(1, (int) Math.ceil(Math.sqrt(spanX * spanX + spanZ * spanZ) / 0.25D));
            for (int sample = 0; sample <= samples; sample++) {
                double ratio = (double) sample / samples;
                int cellX = (int) Math.floor(originX + spanX * ratio);
                int cellZ = (int) Math.floor(originZ + spanZ * ratio);
                assertTrue(pathCase.snapshot().cell(cellX, level, cellZ) != null,
                        () -> "walk segment " + from.blockPos() + " -> " + to.blockPos()
                                + " crosses non-floor at " + cellX + "," + cellZ);
            }
        }
    }

    private static boolean isActionMode(MovementMode mode) {
        return mode == MovementMode.JUMP || mode == MovementMode.SWIM || mode == MovementMode.CLIMB || mode == MovementMode.FALL;
    }

    private static void assertNoActionMode(PathCase pathCase) {
        for (PathWaypoint waypoint : pathCase.result().waypoints()) {
            assertFalse(isActionMode(waypoint.mode()), () -> "unexpected action waypoint " + waypoint.mode() + " at " + waypoint.blockPos());
        }
    }

    private static boolean containsBlock(PathResult result, int x, int y, int z) {
        return result.waypoints().stream()
                .anyMatch(waypoint -> waypoint.blockPos().getX() == x
                        && waypoint.blockPos().getY() == y
                        && waypoint.blockPos().getZ() == z);
    }

    private static PathWaypoint lastWaypoint(PathCase pathCase) {
        List<PathWaypoint> waypoints = pathCase.result().waypoints();
        return waypoints.get(waypoints.size() - 1);
    }

    /**
     * In-memory builder for an immutable {@link PathSnapshot} scene.
     */
    private static final class Scene {
        private final Map<Long, PathCell> cells = new HashMap<>();
        private final LongOpenHashSet bodyPassages = new LongOpenHashSet();
        private int minY = Integer.MAX_VALUE;
        private int maxY = Integer.MIN_VALUE;

        private Scene floor(int x, int y, int z) {
            return cell(x, y, z, y, false, false, false, 1.0D);
        }

        private Scene water(int x, int y, int z) {
            return cell(x, y, z, y, true, false, false, 1.8D);
        }

        private Scene climb(int x, int y, int z) {
            return cell(x, y, z, y, false, true, false, 2.0D);
        }

        private Scene woodenDoor(int x, int y, int z) {
            return cell(x, y, z, y, false, false, true, 3.2D);
        }

        private Scene cell(int x, int y, int z, double standY, boolean water, boolean climbable, boolean woodenDoor, double cost) {
            cells.put(PathCell.key(x, y, z), new PathCell(new BlockPos(x, y, z), x, y, z, standY, water, climbable, woodenDoor, cost));
            passage(x, y, z);
            return this;
        }

        private Scene passage(int x, int y, int z) {
            bodyPassages.add(BlockPos.asLong(x, y, z));
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            return this;
        }

        private PathCase path(int startX, int startY, int startZ, int targetX, int targetY, int targetZ) {
            BlockPos start = new BlockPos(startX, startY, startZ);
            BlockPos targetBlock = new BlockPos(targetX, targetY, targetZ);
            PathSnapshot snapshot = new PathSnapshot(DIMENSION, start, targetBlock, Map.copyOf(cells),
                    LongSets.unmodifiable(bodyPassages), minY, maxY, 0L, true);
            Vec3 target = new Vec3(targetX + 0.5D, targetY, targetZ + 0.5D);
            PathRequest request = new PathRequest(UUID.randomUUID(), DIMENSION, start, target, MovementIntent.WALK, 0L);
            return new PathCase(HybridPathfinder.find(request, snapshot), snapshot);
        }
    }

    private record PathCase(PathResult result, PathSnapshot snapshot) {
    }
}
