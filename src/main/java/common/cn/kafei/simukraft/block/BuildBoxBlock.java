package common.cn.kafei.simukraft.block;

import common.cn.kafei.simukraft.building.BuilderConstructionService;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenManager;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.job.CityJobMobilityService;
import common.cn.kafei.simukraft.registry.ModSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@SuppressWarnings("null")
public class BuildBoxBlock extends Block {
    public BuildBoxBlock() {
        super(BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(SoundType.METAL).requiresCorrectToolForDrops());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            ClientOpener.open(pos);
        } else {
            level.playSound(null, pos, ModSoundEvents.BUILD_BOX_OPEN.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && !state.is(oldState.getBlock())) {
            level.playSound(null, pos, ModSoundEvents.BUILD_BOX_PLACE.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.is(newState.getBlock())) {
            super.onRemove(state, level, pos, newState, movedByPiston);
            return;
        }
        if (!level.isClientSide() && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            level.playSound(null, pos, ModSoundEvents.BUILD_BOX_BREAK.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
            BuilderConstructionService.interruptTasksByBuildBox(serverLevel, pos, "build_box_removed");
            releaseAssignedCitizen(serverLevel, pos, "builder");
            releaseAssignedCitizen(serverLevel, pos, "planner");
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static void releaseAssignedCitizen(net.minecraft.server.level.ServerLevel level, BlockPos pos, String role) {
        UUID workplaceId = workplaceId(pos, role);
        CitizenManager.get(level).allCitizens().stream()
                .filter(citizen -> !citizen.dead())
                .filter(citizen -> workplaceId.equals(citizen.workplaceId()))
                .map(CitizenData::uuid)
                .toList()
                .forEach(citizenId -> {
                    CitizenService.clearEmployment(level, citizenId);
                    CityJobMobilityService.resetCitizenAfterFire(level, citizenId);
                });
    }

    private static UUID workplaceId(BlockPos pos, String role) {
        return UUID.nameUUIDFromBytes(("build_box:" + role + "@" + pos.toShortString()).getBytes(StandardCharsets.UTF_8));
    }

    private static final class ClientOpener {
        static void open(BlockPos pos) {
            client.cn.kafei.simukraft.client.buildbox.BuildBoxScreenOpener.open(pos);
        }
    }
}
