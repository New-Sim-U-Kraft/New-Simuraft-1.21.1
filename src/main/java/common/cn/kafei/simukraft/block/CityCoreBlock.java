package common.cn.kafei.simukraft.block;

import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.CityManager;
import common.cn.kafei.simukraft.network.city.core.CityCoreOpenRequestPacket;
import common.cn.kafei.simukraft.network.toast.InfoToastService;
import common.cn.kafei.simukraft.registry.ModBlocks;
import common.cn.kafei.simukraft.registry.ModSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

@SuppressWarnings("null")
public final class CityCoreBlock extends Block {
    public CityCoreBlock() {
        super(BlockBehaviour.Properties.of().strength(2.0F, 3600000.0F).sound(SoundType.METAL).requiresCorrectToolForDrops());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            level.playSound(null, pos, ModSoundEvents.CITY_CORE_OPEN.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
            CityCoreOpenRequestPacket.openFor(serverLevel, serverPlayer, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && !state.is(oldState.getBlock())) {
            level.playSound(null, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!(level instanceof ServerLevel serverLevel) || newState.is(state.getBlock())) {
            return;
        }
        CityManager manager = CityManager.get(serverLevel);
        CityData city = manager.getCityByCorePos(pos).orElse(null);
        if (city == null) {
            return;
        }
        level.setBlock(pos, ModBlocks.CITY_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.players().forEach(player -> {
            if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) < 100.0D) {
                if (player instanceof ServerPlayer serverPlayer) {
                    InfoToastService.warning(serverPlayer, Component.translatable("message.simukraft.city_core.protected"));
                }
            }
        });
    }
}
