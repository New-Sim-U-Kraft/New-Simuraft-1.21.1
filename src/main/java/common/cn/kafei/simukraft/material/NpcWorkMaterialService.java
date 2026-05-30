package common.cn.kafei.simukraft.material;

import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenService;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public final class NpcWorkMaterialService {
    private NpcWorkMaterialService() {
    }

    public static WorkMaterialResult tryConsume(ServerLevel level, WorkMaterialCache materialCache, WorkMaterialRequest request) {
        if (level == null || materialCache == null || request == null || request.isEmpty()) {
            return WorkMaterialResult.available(ItemStack.EMPTY);
        }
        if (materialCache.tryConsumeOne(level, request) == WorkMaterialCache.ConsumeResult.CONSUMED) {
            return WorkMaterialResult.available(request.displayStack());
        }
        return WorkMaterialResult.missing(request.displayStack(), request.acceptedItems());
    }

    public static void markMissing(ServerLevel level, CitizenData citizen, String workContext, WorkMaterialResult result) {
        if (level == null || citizen == null || result == null || result.available()) {
            return;
        }
        String materialName = result.materialName();
        String statusLabel = "缺少材料: " + materialName;
        String needDetail = (workContext == null || workContext.isBlank() ? "work" : workContext) + ":missing=" + materialName;
        if (citizen.workStatusType() == CitizenWorkStatus.WORKING
                && statusLabel.equals(citizen.statusLabel())
                && needDetail.equals(citizen.workNeedDetail())) {
            return;
        }
        citizen.setWorkStatus(CitizenWorkStatus.WORKING);
        citizen.setStatusLabel(statusLabel);
        citizen.setWorkNeedDetail(needDetail);
        CitizenService.save(level, citizen.uuid());
    }

    public static String describe(ItemStack stack) {
        return new WorkMaterialResult(false, stack).materialName();
    }
}
