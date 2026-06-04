package common.cn.kafei.simukraft.industrial;

import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record IndustrialDefinition(String id,
                                   String name,
                                   String jobType,
                                   String jobName,
                                   String heldItem,
                                   Map<String, PointDefinition> points,
                                   Map<String, ContainerDefinition> containers,
                                   List<RecipeDefinition> recipes,
                                   SpawnEntityDefinition spawnEntity,
                                   Path sourcePath) {
    public RecipeDefinition recipeById(String recipeId) {
        if (recipeId != null && !recipeId.isBlank()) {
            for (RecipeDefinition recipe : recipes) {
                if (recipe.id().equals(recipeId)) {
                    return recipe;
                }
            }
        }
        return recipes.isEmpty() ? null : recipes.getFirst();
    }

    public String defaultRecipeId() {
        return recipes.isEmpty() ? "" : recipes.getFirst().id();
    }

    public record PointDefinition(String id, String type, List<BlockPos> positions, SelectionMode selectionMode) {
    }

    public record ContainerDefinition(String id, String type, List<BlockPos> positions) {
    }

    public record RecipeDefinition(String id,
                                   String name,
                                   String heldItem,
                                   List<ItemRequirement> inputs,
                                   List<ProductOutput> outputs,
                                   List<StepDefinition> steps) {
        public String effectiveHeldItem(String fallback) {
            return heldItem != null && !heldItem.isBlank() ? heldItem : fallback;
        }
    }

    public record ItemRequirement(String itemId, int count, boolean consume, String potionId) {
    }

    public record ProductOutput(String itemId, String potionId, int baseAmount, int randomRange, double probability, boolean ignoreMultiplier) {
    }

    public record SpawnEntityDefinition(boolean enabled, String entityType, int count) {
    }

    public record StepDefinition(String type,
                                 String point,
                                 String container,
                                 String input,
                                 String output,
                                 String item,
                                 int ticks,
                                 boolean swing,
                                 double range,
                                 String statusKey,
                                 String statusText,
                                 String entityType,
                                 int count,
                                 double radius,
                                 boolean requireFood,
                                 List<BlockPos> positions,
                                 String block,
                                 String fluid,
                                 boolean consume,
                                 boolean replace,
                                 boolean dropItems,
                                 String outputPolicy,
                                 int timeoutTicks,
                                 int pollTicks,
                                 int slot,
                                 int targetCount,
                                 int thresholdCount,
                                 boolean inputsOverride,
                                 boolean outputsOverride,
                                 List<ItemRequirement> inputs,
                                 List<ProductOutput> outputs) {
    }

    public enum SelectionMode {
        NEAREST,
        ORDERED;

        public static SelectionMode fromName(String name) {
            return "ordered".equalsIgnoreCase(name) ? ORDERED : NEAREST;
        }
    }
}
