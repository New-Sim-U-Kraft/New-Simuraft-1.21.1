package common.cn.kafei.simukraft.citizen;

public enum CitizenWorkStatus {
    WORKING("work_status.working", "working"),
    IDLE("work_status.idle", "idle"),
    RESTING("work_status.resting", "resting"),
    DEAD("work_status.dead", "dead");

    private final String translationKey;
    private final String legacyStatus;

    CitizenWorkStatus(String translationKey, String legacyStatus) {
        this.translationKey = translationKey;
        this.legacyStatus = legacyStatus;
    }

    public String translationKey() {
        return translationKey;
    }

    public String legacyStatus() {
        return legacyStatus;
    }

    public static CitizenWorkStatus fromName(String value) {
        if (value == null || value.isBlank()) {
            return IDLE;
        }
        for (CitizenWorkStatus status : values()) {
            if (status.name().equalsIgnoreCase(value) || status.translationKey.equalsIgnoreCase(value) || status.legacyStatus.equalsIgnoreCase(value)) {
                return status;
            }
        }
        if ("work_sub_state.resting".equals(value)) {
            return RESTING;
        }
        if ("work_sub_state.working".equals(value)) {
            return WORKING;
        }
        return IDLE;
    }
}
