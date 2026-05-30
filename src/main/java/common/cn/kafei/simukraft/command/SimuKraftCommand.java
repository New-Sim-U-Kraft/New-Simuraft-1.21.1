package common.cn.kafei.simukraft.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.citizen.CitizenTeleportService;
import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.CityService;
import common.cn.kafei.simukraft.economy.EconomyService;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import common.cn.kafei.simukraft.network.building.BuildingCacheReloadPacket;
import common.cn.kafei.simukraft.network.hud.HudSyncService;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.path.CitizenWanderService;
import common.cn.kafei.simukraft.path.MovementIntent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@SuppressWarnings("null")
public final class SimuKraftCommand {
    private static final double GOLDEN_ANGLE = Math.PI * (3.0D - Math.sqrt(5.0D));

    private SimuKraftCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("simukraft")
                .requires(source -> source.hasPermission(2));
        root.then(Commands.literal("reload")
                .executes(context -> reload(context.getSource())));
        root.then(Commands.literal("city")
                .then(Commands.literal("funds")
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01D))
                                        .executes(context -> addFundsToSelfCity(
                                                context.getSource(),
                                                DoubleArgumentType.getDouble(context, "amount")))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(context -> addFundsToPlayerCity(
                                                        context.getSource(),
                                                        DoubleArgumentType.getDouble(context, "amount"),
                                                        EntityArgument.getPlayer(context, "player")))))))
                .then(Commands.literal("citizens")
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
                                        .executes(context -> spawnCitizensInSelfCity(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "count")))))));
        root.then(Commands.literal("path")
                .then(Commands.literal("test")
                        .then(Commands.argument("target", Vec3Argument.vec3())
                                .executes(context -> testNearestCitizenPath(
                                        context.getSource(),
                                        Vec3Argument.getVec3(context, "target"))))
                        .then(Commands.argument("citizen", EntityArgument.entity())
                                .then(Commands.argument("target", Vec3Argument.vec3())
                                        .executes(context -> testSelectedCitizenPath(
                                                context.getSource(),
                                                EntityArgument.getEntity(context, "citizen"),
                                                Vec3Argument.getVec3(context, "target"))))))
                .then(Commands.literal("stress")
                        .then(Commands.argument("target", Vec3Argument.vec3())
                                .executes(context -> stressCityPath(
                                        context.getSource(),
                                        Vec3Argument.getVec3(context, "target"),
                                        -1))
                                .then(Commands.argument("spreadRadius", IntegerArgumentType.integer(0, 128))
                                        .executes(context -> stressCityPath(
                                                context.getSource(),
                                                Vec3Argument.getVec3(context, "target"),
                                                IntegerArgumentType.getInteger(context, "spreadRadius"))))))
                .then(Commands.literal("wander")
                        .executes(context -> wanderCityCitizens(context.getSource(), 12))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 64))
                                .executes(context -> wanderCityCitizens(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("status")
                        .executes(context -> pathStatus(context.getSource())))
                .then(Commands.literal("clear")
                        .executes(context -> clearPathDebug(context.getSource()))));
        dispatcher.register(root);
    }

    private static int reload(CommandSourceStack source) {
        int count = 0;
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, new BuildingCacheReloadPacket());
            count++;
        }
        final int reloadedPlayerCount = count;
        source.sendSuccess(() -> Component.translatable("message.simukraft.reload.success", reloadedPlayerCount), true);
        return 1;
    }

    private static int testNearestCitizenPath(CommandSourceStack source, Vec3 target) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("message.simukraft.path_debug.player_required"));
            return 0;
        }
        return CitizenNavigationService.debugPathTo(player, target) ? Command.SINGLE_SUCCESS : 0;
    }

    private static int testSelectedCitizenPath(CommandSourceStack source, Entity entity, Vec3 target) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("message.simukraft.path_debug.player_required"));
            return 0;
        }
        if (!(entity instanceof CitizenEntity citizen)) {
            source.sendFailure(Component.translatable("message.simukraft.path_debug.selected_not_citizen"));
            return 0;
        }
        return CitizenNavigationService.debugPathTo(player, citizen, target) ? Command.SINGLE_SUCCESS : 0;
    }

    private static int clearPathDebug(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("message.simukraft.path_debug.player_required"));
            return 0;
        }
        CitizenNavigationService.clearDebugPath(player);
        return Command.SINGLE_SUCCESS;
    }

    private static int pathStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("message.simukraft.path_debug.player_required"));
            return 0;
        }
        return CitizenNavigationService.sendStatus(player) ? Command.SINGLE_SUCCESS : 0;
    }

    private static int spawnCitizensInSelfCity(CommandSourceStack source, int count) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("message.simukraft.path_debug.player_required"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        Optional<CityData> city = CityService.findPlayerCity(level, player.getUUID());
        if (city.isEmpty()) {
            source.sendFailure(Component.translatable("message.simukraft.command.city_required"));
            return 0;
        }
        int spawned = 0;
        for (int index = 0; index < count; index++) {
            BlockPos spawnGround = CitizenWanderService.randomSpawnGround(level, player.blockPosition().below(), 10);
            if (spawnGround != null && CitizenService.spawnCitizen(level, spawnGround, city.get().cityId(), true).isPresent()) {
                spawned++;
            }
        }
        final int spawnedCount = spawned;
        source.sendSuccess(() -> Component.translatable("message.simukraft.command.city_citizens.spawned", spawnedCount, city.get().cityName()), true);
        return spawned > 0 ? Command.SINGLE_SUCCESS : 0;
    }

    private static int stressCityPath(CommandSourceStack source, Vec3 target, int spreadRadius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("message.simukraft.path_debug.player_required"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        Optional<CityData> city = CityService.findPlayerCity(level, player.getUUID());
        if (city.isEmpty()) {
            source.sendFailure(Component.translatable("message.simukraft.command.city_required"));
            return 0;
        }
        List<CitizenData> citizens = CitizenService.listCitizensByCity(level, city.get().cityId());
        int resolvedSpreadRadius = resolveStressSpreadRadius(spreadRadius, citizens.size());
        int total = 0;
        int loaded = 0;
        int requested = 0;
        for (CitizenData citizen : citizens) {
            total++;
            if (CitizenTeleportService.findCitizenEntity(level, citizen.uuid()) == null) {
                continue;
            }
            Vec3 assignedTarget = stressTarget(level, target, loaded, citizens.size(), resolvedSpreadRadius);
            loaded++;
            if (CitizenNavigationService.requestTestMove(level, citizen.uuid(), assignedTarget, MovementIntent.RUN)) {
                requested++;
            }
        }
        final int totalCount = total;
        final int loadedCount = loaded;
        final int requestedCount = requested;
        final int spreadCount = resolvedSpreadRadius;
        source.sendSuccess(() -> Component.translatable("message.simukraft.command.path_stress.started", requestedCount, loadedCount, totalCount, spreadCount), true);
        return requested > 0 ? Command.SINGLE_SUCCESS : 0;
    }

    private static int resolveStressSpreadRadius(int configuredRadius, int citizenCount) {
        if (configuredRadius >= 0) {
            return configuredRadius;
        }
        return Math.clamp((int) Math.ceil(Math.sqrt(Math.max(1, citizenCount)) * 0.9D), 8, 64);
    }

    private static Vec3 stressTarget(ServerLevel level, Vec3 center, int index, int total, int spreadRadius) {
        if (spreadRadius <= 0) {
            return center;
        }
        double progress = Math.sqrt((index + 0.5D) / Math.max(1.0D, total));
        double radius = Math.max(1.0D, spreadRadius * progress);
        double angle = index * GOLDEN_ANGLE;
        int baseX = (int) Math.round(center.x + Math.cos(angle) * radius);
        int baseZ = (int) Math.round(center.z + Math.sin(angle) * radius);
        Vec3 openTarget = findOpenGroundTarget(level, baseX, baseZ);
        if (openTarget != null) {
            return openTarget;
        }
        Vec3 fallback = CitizenWanderService.randomTarget(level, center, spreadRadius);
        return fallback != null ? fallback : center;
    }

    private static Vec3 findOpenGroundTarget(ServerLevel level, int baseX, int baseZ) {
        for (int radius = 0; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
                        continue;
                    }
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos feet = new BlockPos(x, y, z);
                    if (isOpenForStressTarget(level, feet)) {
                        return new Vec3(x + 0.5D, y, z + 0.5D);
                    }
                }
            }
        }
        return null;
    }

    private static boolean isOpenForStressTarget(ServerLevel level, BlockPos feet) {
        return feet != null
                && level.isEmptyBlock(feet)
                && level.isEmptyBlock(feet.above())
                && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
    }

    private static int wanderCityCitizens(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("message.simukraft.path_debug.player_required"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        Optional<CityData> city = CityService.findPlayerCity(level, player.getUUID());
        if (city.isEmpty()) {
            source.sendFailure(Component.translatable("message.simukraft.command.city_required"));
            return 0;
        }
        int requested = CitizenWanderService.requestCityWander(level, city.get().cityId(), radius, 500);
        final int requestedCount = requested;
        source.sendSuccess(() -> Component.translatable("message.simukraft.command.path_wander.started", requestedCount, radius), true);
        return requested > 0 ? Command.SINGLE_SUCCESS : 0;
    }

    // 给命令执行者所属城市加款；控制台必须显式指定玩家。
    private static int addFundsToSelfCity(CommandSourceStack source, double amount) {
        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer == null) {
            source.sendFailure(Component.translatable("message.simukraft.command.city_funds.player_required"));
            return 0;
        }
        return addFundsToPlayerCity(source, amount, sourcePlayer);
    }

    // 给指定在线玩家所属城市加款，并记录财政流水。
    private static int addFundsToPlayerCity(CommandSourceStack source, double amount, ServerPlayer targetPlayer) {
        if (targetPlayer == null) {
            source.sendFailure(Component.translatable("message.simukraft.command.city_funds.player_required"));
            return 0;
        }
        double normalizedAmount = EconomyService.normalizeAmount(amount);
        if (normalizedAmount <= 0.0D) {
            source.sendFailure(Component.translatable("message.simukraft.command.city_funds.invalid_amount"));
            return 0;
        }
        ServerLevel level = targetPlayer.serverLevel();
        Optional<CityData> city = CityService.findPlayerCity(level, targetPlayer.getUUID());
        if (city.isEmpty()) {
            source.sendFailure(Component.translatable("message.simukraft.command.city_funds.no_city", targetPlayer.getGameProfile().getName()));
            return 0;
        }
        ServerPlayer actor = source.getPlayer();
        if (!EconomyService.depositCityFunds(level, city.get().cityId(), actor, normalizedAmount, "command_add_funds")) {
            source.sendFailure(Component.translatable("message.simukraft.command.city_funds.failed"));
            return 0;
        }
        syncCityMembersHud(level, city.get());
        double balance = EconomyService.getCityBalance(level, city.get().cityId());
        String amountText = String.format(Locale.ROOT, "%.2f", normalizedAmount);
        String balanceText = String.format(Locale.ROOT, "%.2f", balance);
        source.sendSuccess(() -> Component.translatable(
                "message.simukraft.command.city_funds.added",
                amountText,
                city.get().cityName(),
                targetPlayer.getGameProfile().getName(),
                balanceText
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    // 同一城市的在线成员都需要马上看到新余额。
    private static void syncCityMembersHud(ServerLevel level, CityData city) {
        if (level == null || city == null) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (city.member(player.getUUID()).isPresent()) {
                HudSyncService.syncToPlayer(player, true);
            }
        }
    }
}
