package common.cn.kafei.simukraft.industrial;

import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public record IndustrialMachineOperationContext(ServerLevel level,
                                                IndustrialBoxData box,
                                                PlacedBuildingRecord building,
                                                IndustrialDefinition definition,
                                                IndustrialDefinition.RecipeDefinition recipe,
                                                IndustrialDefinition.StepDefinition step,
                                                CitizenData worker,
                                                CitizenEntity entity,
                                                BlockPos machinePos,
                                                List<BlockPos> inputContainers,
                                                List<BlockPos> outputContainers,
                                                String machineState) {
    public IndustrialMachineOperationContext {
        machinePos = machinePos != null ? machinePos.immutable() : BlockPos.ZERO;
        inputContainers = inputContainers == null ? List.of() : List.copyOf(inputContainers);
        outputContainers = outputContainers == null ? List.of() : List.copyOf(outputContainers);
        machineState = machineState != null ? machineState : "";
    }
}
