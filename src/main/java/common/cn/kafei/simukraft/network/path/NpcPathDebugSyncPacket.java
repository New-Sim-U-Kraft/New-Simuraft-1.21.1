package common.cn.kafei.simukraft.network.path;

import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.path.PathResult;
import common.cn.kafei.simukraft.path.PathWaypoint;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("null")
public record NpcPathDebugSyncPacket(UUID citizenId, boolean success, String reason, String status, List<PathPoint> points) implements CustomPacketPayload {
    public static final Type<NpcPathDebugSyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, "npc_path_debug_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NpcPathDebugSyncPacket> STREAM_CODEC = StreamCodec.of(NpcPathDebugSyncPacket::encode, NpcPathDebugSyncPacket::decode);
    private static final UUID EMPTY_CITIZEN_ID = new UUID(0L, 0L);
    private static final int MAX_POINTS = 2048;

    public NpcPathDebugSyncPacket {
        citizenId = citizenId != null ? citizenId : EMPTY_CITIZEN_ID;
        reason = reason != null ? reason : "";
        status = status != null ? status : "";
        points = points == null ? List.of() : List.copyOf(points.size() > MAX_POINTS ? points.subList(0, MAX_POINTS) : points);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static NpcPathDebugSyncPacket fromResult(PathResult result) {
        if (result == null) {
            return failure(EMPTY_CITIZEN_ID, "path_result_missing");
        }
        List<PathPoint> debugPoints = new ArrayList<>(Math.min(result.waypoints().size(), MAX_POINTS));
        for (PathWaypoint waypoint : result.waypoints()) {
            if (debugPoints.size() >= MAX_POINTS) {
                break;
            }
            debugPoints.add(new PathPoint(
                    waypoint.position().x,
                    waypoint.position().y,
                    waypoint.position().z,
                    waypoint.mode().name()
            ));
        }
        return new NpcPathDebugSyncPacket(result.citizenId(), result.success(), result.reason(), "debug", debugPoints);
    }

    public static NpcPathDebugSyncPacket fromWaypoints(UUID citizenId, List<PathWaypoint> waypoints, String status) {
        List<PathPoint> debugPoints = new ArrayList<>(Math.min(waypoints != null ? waypoints.size() : 0, MAX_POINTS));
        if (waypoints != null) {
            for (PathWaypoint waypoint : waypoints) {
                if (debugPoints.size() >= MAX_POINTS) {
                    break;
                }
                debugPoints.add(new PathPoint(
                        waypoint.position().x,
                        waypoint.position().y,
                        waypoint.position().z,
                        waypoint.mode().name()
                ));
            }
        }
        return new NpcPathDebugSyncPacket(citizenId, true, "", status, debugPoints);
    }

    public static NpcPathDebugSyncPacket failure(UUID citizenId, String reason) {
        return new NpcPathDebugSyncPacket(citizenId, false, reason, "failed", List.of());
    }

    public static NpcPathDebugSyncPacket clear() {
        return new NpcPathDebugSyncPacket(EMPTY_CITIZEN_ID, true, "cleared", "clear", List.of());
    }

    public static void encode(RegistryFriendlyByteBuf buffer, NpcPathDebugSyncPacket packet) {
        buffer.writeUUID(packet.citizenId());
        buffer.writeBoolean(packet.success());
        buffer.writeUtf(packet.reason(), 128);
        buffer.writeUtf(packet.status(), 32);
        buffer.writeVarInt(packet.points().size());
        for (PathPoint point : packet.points()) {
            buffer.writeDouble(point.x());
            buffer.writeDouble(point.y());
            buffer.writeDouble(point.z());
            buffer.writeUtf(point.mode(), 16);
        }
    }

    public static NpcPathDebugSyncPacket decode(RegistryFriendlyByteBuf buffer) {
        UUID citizenId = buffer.readUUID();
        boolean success = buffer.readBoolean();
        String reason = buffer.readUtf(128);
        String status = buffer.readUtf(32);
        int encodedSize = Math.max(0, buffer.readVarInt());
        int acceptedSize = Math.min(encodedSize, MAX_POINTS);
        List<PathPoint> points = new ArrayList<>(acceptedSize);
        for (int index = 0; index < encodedSize; index++) {
            PathPoint point = new PathPoint(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readUtf(16));
            if (index < MAX_POINTS) {
                points.add(point);
            }
        }
        return new NpcPathDebugSyncPacket(citizenId, success, reason, status, points);
    }

    public static void handle(NpcPathDebugSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> client.cn.kafei.simukraft.client.path.NpcPathDebugRenderer.update(packet));
    }

    public record PathPoint(double x, double y, double z, String mode) {
    }
}
