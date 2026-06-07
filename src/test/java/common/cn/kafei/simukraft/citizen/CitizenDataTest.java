package common.cn.kafei.simukraft.citizen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

@SuppressWarnings("null")
class CitizenDataTest {
    @Test
    void deadWorkStatusClearsHomeOnLoad() {
        UUID citizenId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        CompoundTag tag = baseCitizenTag(citizenId);
        tag.putString("WorkStatus", "work_status.dead");
        tag.putUUID("HomeId", homeId);

        CitizenData data = CitizenData.fromTag(tag);

        assertTrue(data.dead());
        assertEquals(CitizenWorkStatus.DEAD, data.workStatusType());
        assertNull(data.homeId());
    }

    @Test
    void markDeadClearsHome() {
        CitizenData data = new CitizenData(UUID.randomUUID());
        data.setHomeId(UUID.randomUUID());

        data.markDead(7L);

        assertTrue(data.dead());
        assertNull(data.homeId());
    }

    @Test
    void selfFeedingStatusIsTemporaryOverlay() {
        assertTrue(CitizenSelfFeedingService.isSelfFeedingStatusLabel(CitizenSelfFeedingService.GOING_TO_BUY_FOOD_STATUS));
        assertTrue(CitizenSelfFeedingService.isSelfFeedingStatusLabel(CitizenSelfFeedingService.BUYING_FOOD_STATUS));
        assertFalse(CitizenSelfFeedingService.isSelfFeedingStatusLabel(CitizenWorkStatus.WORKING.translationKey()));
    }

    private static CompoundTag baseCitizenTag(UUID citizenId) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Uuid", citizenId);
        tag.putString("Name", "Test Citizen");
        tag.putString("Gender", "male");
        tag.putInt("Age", 20);
        tag.putInt("Lifespan", 80);
        tag.putString("JobType", "UNEMPLOYED");
        tag.putString("JobId", "UNEMPLOYED");
        tag.putString("Status", "idle");
        tag.putString("WorkStatus", "work_status.idle");
        tag.putString("WorkNeedDetail", "");
        tag.putString("StatusLabel", "");
        tag.putString("SkinPath", "");
        tag.putDouble("Health", 20.0D);
        tag.putDouble("Hunger", 20.0D);
        tag.putDouble("Happiness", 50.0D);
        tag.put("Skills", new CompoundTag());
        return tag;
    }
}
