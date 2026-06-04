package common.cn.kafei.simukraft.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

@SuppressWarnings("null")
public final class IndustrialBoxData {
    private final BlockPos boxPos;
    private String buildingId = "";
    private String definitionId = "";
    private String selectedRecipeId = "";
    private boolean running;
    private boolean spawnEntityDone;
    private int currentStep;
    private String statusKey = "";
    private String statusText = "";
    private String machineState = "";
    private long updatedAt;

    public IndustrialBoxData(BlockPos boxPos) {
        this.boxPos = boxPos.immutable();
    }

    public BlockPos boxPos() {
        return boxPos;
    }

    public String buildingId() {
        return buildingId;
    }

    public void setBuildingId(String buildingId) {
        this.buildingId = buildingId != null ? buildingId : "";
    }

    public String definitionId() {
        return definitionId;
    }

    public void setDefinitionId(String definitionId) {
        this.definitionId = definitionId != null ? definitionId : "";
    }

    public String selectedRecipeId() {
        return selectedRecipeId;
    }

    public void setSelectedRecipeId(String selectedRecipeId) {
        this.selectedRecipeId = selectedRecipeId != null ? selectedRecipeId : "";
    }

    public boolean running() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean spawnEntityDone() {
        return spawnEntityDone;
    }

    public void setSpawnEntityDone(boolean spawnEntityDone) {
        this.spawnEntityDone = spawnEntityDone;
    }

    public int currentStep() {
        return currentStep;
    }

    public void setCurrentStep(int currentStep) {
        this.currentStep = Math.max(0, currentStep);
    }

    public String statusKey() {
        return statusKey;
    }

    public void setStatusKey(String statusKey) {
        this.statusKey = statusKey != null ? statusKey : "";
    }

    public String statusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText != null ? statusText : "";
    }

    public String machineState() {
        return machineState;
    }

    public void setMachineState(String machineState) {
        this.machineState = machineState != null ? machineState : "";
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("BoxPos", boxPos.asLong());
        tag.putString("BuildingId", buildingId);
        tag.putString("DefinitionId", definitionId);
        tag.putString("SelectedRecipeId", selectedRecipeId);
        tag.putBoolean("Running", running);
        tag.putBoolean("SpawnEntityDone", spawnEntityDone);
        tag.putInt("CurrentStep", currentStep);
        tag.putString("StatusKey", statusKey);
        tag.putString("StatusText", statusText);
        tag.putString("MachineState", machineState);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static IndustrialBoxData fromTag(CompoundTag tag) {
        IndustrialBoxData data = new IndustrialBoxData(BlockPos.of(tag.getLong("BoxPos")));
        data.buildingId = tag.getString("BuildingId");
        data.definitionId = tag.getString("DefinitionId");
        data.selectedRecipeId = tag.getString("SelectedRecipeId");
        data.running = tag.getBoolean("Running");
        data.spawnEntityDone = tag.getBoolean("SpawnEntityDone");
        data.currentStep = Math.max(0, tag.getInt("CurrentStep"));
        data.statusKey = tag.getString("StatusKey");
        data.statusText = tag.getString("StatusText");
        data.machineState = tag.getString("MachineState");
        data.updatedAt = tag.getLong("UpdatedAt");
        return data;
    }
}
