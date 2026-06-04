package common.cn.kafei.simukraft.industrial;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import common.cn.kafei.simukraft.SimuKraft;
import common.cn.kafei.simukraft.building.BuildingCatalog;
import common.cn.kafei.simukraft.building.PlacedBuildingRecord;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class IndustrialDefinitionLoader {
    private static final int MAX_POSITIONS = 64;
    private static final int MAX_RECIPES = 64;
    private static final int MAX_STEPS = 256;

    private IndustrialDefinitionLoader() {
    }

    public static LoadResult loadForBuilding(PlacedBuildingRecord building) {
        if (building == null) {
            return LoadResult.missing("missing_building");
        }
        Optional<BuildingCatalog.BuildingDefinition> definition = BuildingCatalog.findBuilding(building.category(), building.buildingFileName());
        if (definition.isEmpty()) {
            return LoadResult.missing("missing_building_definition");
        }
        Path industrialPath = resolveIndustrialPath(definition.get());
        if (industrialPath != null && Files.isRegularFile(industrialPath)) {
            return load(industrialPath);
        }
        LoadResult bundled = loadBundled(stripExtension(definition.get().metaFileName()));
        return bundled.definition() != null ? bundled : LoadResult.missing("missing_industrial_json");
    }

    public static LoadResult load(Path path) {
        try {
            return loadText(Files.readString(path, StandardCharsets.UTF_8), stripExtension(path.getFileName().toString()), path);
        } catch (Exception exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to load industrial definition {}", path, exception);
            return LoadResult.missing("invalid_industrial_json");
        }
    }

    private static LoadResult loadBundled(String baseName) {
        String resourcePath = "/assets/" + SimuKraft.MOD_ID + "/industrial/" + baseName + ".json";
        try (var input = IndustrialDefinitionLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                return LoadResult.missing("missing_industrial_json");
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return loadText(text, baseName, null);
        } catch (Exception exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to load bundled industrial definition {}", resourcePath, exception);
            return LoadResult.missing("invalid_industrial_json");
        }
    }

    private static LoadResult loadText(String text, String fallbackId, Path path) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        List<String> errors = new ArrayList<>();
        String id = string(root, "id", fallbackId);
        String name = string(root, "name", id);
        String jobType = stringAny(root, "industrial_worker", "jobType", "JobType", "job_type");
        String jobName = stringAny(root, name, "jobName", "JobName", "job_name");
        String heldItem = string(root, "heldItem", "");
        Map<String, IndustrialDefinition.PointDefinition> points = parsePoints(root.getAsJsonObject("points"), errors);
        Map<String, IndustrialDefinition.ContainerDefinition> containers = parseContainers(root.getAsJsonObject("containers"), errors);
        List<IndustrialDefinition.RecipeDefinition> recipes = parseRecipes(root.getAsJsonArray("recipes"), errors);
        IndustrialDefinition.SpawnEntityDefinition spawnEntity = parseSpawnEntity(root.getAsJsonObject("spawnEntity"));
        if (recipes.isEmpty()) {
            errors.add("missing_recipes");
        }
        IndustrialDefinition definition = new IndustrialDefinition(id, name, jobType, jobName, heldItem, Map.copyOf(points), Map.copyOf(containers), List.copyOf(recipes), spawnEntity, path);
        return new LoadResult(definition, List.copyOf(errors), path);
    }

    private static Path resolveIndustrialPath(BuildingCatalog.BuildingDefinition definition) {
        Path explicit = explicitIndustrialPath(definition.metaPath());
        if (explicit != null) {
            return explicit;
        }
        return definition.metaPath().getParent().resolve(stripExtension(definition.metaFileName()) + ".json");
    }

    private static Path explicitIndustrialPath(Path metaPath) {
        if (!Files.isRegularFile(metaPath)) {
            return null;
        }
        try {
            for (String rawLine : Files.readAllLines(metaPath, StandardCharsets.UTF_8)) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (!line.regionMatches(true, 0, "industrial:", 0, "industrial:".length())) {
                    continue;
                }
                String fileName = line.substring("industrial:".length()).trim();
                if (!fileName.isBlank()) {
                    return metaPath.getParent().resolve(fileName);
                }
            }
        } catch (Exception exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to read industrial entry from {}", metaPath, exception);
        }
        return null;
    }

    private static Map<String, IndustrialDefinition.PointDefinition> parsePoints(JsonObject object, List<String> errors) {
        Map<String, IndustrialDefinition.PointDefinition> points = new LinkedHashMap<>();
        if (object == null) {
            return points;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String id = entry.getKey();
            JsonObject pointObject = asObject(entry.getValue());
            if (pointObject == null) {
                errors.add("invalid_point:" + id);
                continue;
            }
            List<BlockPos> positions = parsePositions(pointObject, errors, "point:" + id);
            if (positions.isEmpty()) {
                continue;
            }
            points.put(id, new IndustrialDefinition.PointDefinition(id, string(pointObject, "type", "structure_pos"), positions,
                    IndustrialDefinition.SelectionMode.fromName(string(pointObject, "select", "nearest"))));
        }
        return points;
    }

    private static Map<String, IndustrialDefinition.ContainerDefinition> parseContainers(JsonObject object, List<String> errors) {
        Map<String, IndustrialDefinition.ContainerDefinition> containers = new LinkedHashMap<>();
        if (object == null) {
            return containers;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String id = entry.getKey();
            JsonObject containerObject = asObject(entry.getValue());
            if (containerObject == null) {
                errors.add("invalid_container:" + id);
                continue;
            }
            List<BlockPos> positions = parsePositions(containerObject, errors, "container:" + id);
            if (positions.isEmpty()) {
                continue;
            }
            containers.put(id, new IndustrialDefinition.ContainerDefinition(id, string(containerObject, "type", "structure_pos"), positions));
        }
        return containers;
    }

    private static List<IndustrialDefinition.RecipeDefinition> parseRecipes(JsonArray array, List<String> errors) {
        List<IndustrialDefinition.RecipeDefinition> recipes = new ArrayList<>();
        if (array == null) {
            return recipes;
        }
        int limit = Math.min(array.size(), MAX_RECIPES);
        for (int i = 0; i < limit; i++) {
            JsonObject object = asObject(array.get(i));
            if (object == null) {
                errors.add("invalid_recipe:" + i);
                continue;
            }
            String id = string(object, "id", "recipe_" + i);
            recipes.add(new IndustrialDefinition.RecipeDefinition(
                    id,
                    string(object, "name", id),
                    string(object, "heldItem", ""),
                    parseInputs(object.getAsJsonArray("inputs")),
                    parseOutputs(object.getAsJsonArray("outputs")),
                    parseSteps(object.getAsJsonArray("steps"), errors, id)
            ));
        }
        return recipes;
    }

    private static List<IndustrialDefinition.ItemRequirement> parseInputs(JsonArray array) {
        List<IndustrialDefinition.ItemRequirement> inputs = new ArrayList<>();
        if (array == null) {
            return inputs;
        }
        for (JsonElement element : array) {
            JsonObject object = asObject(element);
            if (object == null) {
                continue;
            }
            String item = string(object, "item", "");
            int count = Math.max(1, integer(object, "count", integer(object, "amount", 1)));
            boolean consume = bool(object, "consume", true);
            String potion = string(object, "potion", "");
            if (!item.isBlank()) {
                inputs.add(new IndustrialDefinition.ItemRequirement(item, count, consume, potion));
            }
        }
        return List.copyOf(inputs);
    }

    private static List<IndustrialDefinition.ProductOutput> parseOutputs(JsonArray array) {
        List<IndustrialDefinition.ProductOutput> outputs = new ArrayList<>();
        if (array == null) {
            return outputs;
        }
        for (JsonElement element : array) {
            JsonObject object = asObject(element);
            if (object == null) {
                continue;
            }
            String item = string(object, "item", "");
            int baseAmount = Math.max(1, integer(object, "baseAmount", integer(object, "count", 1)));
            int randomRange = Math.max(0, integer(object, "randomRange", 0));
            double probability = Math.max(0.0D, Math.min(1.0D, decimal(object, "probability", 1.0D)));
            boolean ignoreMultiplier = bool(object, "ignoreMultiplier", false);
            String potion = string(object, "potion", "");
            if (!item.isBlank()) {
                outputs.add(new IndustrialDefinition.ProductOutput(item, potion, baseAmount, randomRange, probability, ignoreMultiplier));
            }
        }
        return List.copyOf(outputs);
    }

    private static List<IndustrialDefinition.StepDefinition> parseSteps(JsonArray array, List<String> errors, String recipeId) {
        List<IndustrialDefinition.StepDefinition> steps = new ArrayList<>();
        if (array == null) {
            return steps;
        }
        int limit = Math.min(array.size(), MAX_STEPS);
        for (int i = 0; i < limit; i++) {
            JsonObject object = asObject(array.get(i));
            if (object == null) {
                errors.add("invalid_step:" + recipeId + ":" + i);
                continue;
            }
            appendStepDefinitions(steps, object, errors, recipeId, i);
            if (steps.size() >= MAX_STEPS) {
                errors.add("steps_truncated:" + recipeId);
                break;
            }
        }
        return List.copyOf(steps);
    }

    private static void appendStepDefinitions(List<IndustrialDefinition.StepDefinition> steps,
                                              JsonObject object,
                                              List<String> errors,
                                              String recipeId,
                                              int index) {
        String type = string(object, "type", "");
        if ("repeat".equalsIgnoreCase(type) || "loop".equalsIgnoreCase(type)) {
            appendRepeatedSteps(steps, object, errors, recipeId, index);
            return;
        }
        steps.add(parseStepDefinition(object));
    }

    private static void appendRepeatedSteps(List<IndustrialDefinition.StepDefinition> steps,
                                            JsonObject object,
                                            List<String> errors,
                                            String recipeId,
                                            int index) {
        JsonArray nestedSteps = object.getAsJsonArray("steps");
        if (nestedSteps == null || nestedSteps.isEmpty()) {
            errors.add("missing_repeat_steps:" + recipeId + ":" + index);
            return;
        }
        List<BlockPos> positions = parseOptionalPositions(object);
        int repetitions = !positions.isEmpty() ? positions.size() : Math.max(0, integer(object, "count", 0));
        if (repetitions <= 0) {
            errors.add("missing_repeat_count:" + recipeId + ":" + index);
            return;
        }
        for (int repeatIndex = 0; repeatIndex < repetitions && steps.size() < MAX_STEPS; repeatIndex++) {
            BlockPos position = positions.isEmpty() ? null : positions.get(repeatIndex);
            for (int nestedIndex = 0; nestedIndex < nestedSteps.size() && steps.size() < MAX_STEPS; nestedIndex++) {
                JsonObject nestedObject = asObject(nestedSteps.get(nestedIndex));
                if (nestedObject == null) {
                    errors.add("invalid_repeat_step:" + recipeId + ":" + index + ":" + nestedIndex);
                    continue;
                }
                JsonObject expanded = nestedObject.deepCopy();
                if (position != null && !expanded.has("pos") && !expanded.has("positions")) {
                    expanded.add("pos", positionArray(position));
                }
                appendStepDefinitions(steps, expanded, errors, recipeId, nestedIndex);
            }
        }
    }

    private static IndustrialDefinition.StepDefinition parseStepDefinition(JsonObject object) {
        return new IndustrialDefinition.StepDefinition(
                string(object, "type", ""),
                string(object, "point", ""),
                string(object, "container", ""),
                string(object, "input", ""),
                string(object, "output", ""),
                string(object, "item", ""),
                Math.max(1, integer(object, "ticks", 1)),
                bool(object, "swing", false),
                Math.max(0.1D, decimal(object, "range", 1.5D)),
                string(object, "statusKey", ""),
                string(object, "statusText", ""),
                stringAny(object, "", "entityType", "entity"),
                Math.max(0, integer(object, "count", 0)),
                Math.max(0.5D, decimal(object, "radius", 6.0D)),
                bool(object, "requireFood", bool(object, "requiresFood", true)),
                parseOptionalPositions(object),
                string(object, "block", ""),
                stringAny(object, "", "fluid", "liquid"),
                bool(object, "consume", false),
                bool(object, "replace", false),
                bool(object, "dropItems", bool(object, "drop", false)),
                stringAny(object, "extract_to_output", "outputPolicy", "output_policy"),
                Math.max(1, integer(object, "timeoutTicks", integer(object, "timeout", 12000))),
                Math.max(1, integer(object, "pollTicks", integer(object, "poll", 20))),
                integer(object, "slot", -1),
                Math.max(0, integer(object, "targetCount", integer(object, "target", integer(object, "fillTo", 0)))),
                integer(object, "thresholdCount", integer(object, "threshold", -1)),
                object.has("inputs"),
                object.has("outputs"),
                parseInputs(object.getAsJsonArray("inputs")),
                parseOutputs(object.getAsJsonArray("outputs"))
        );
    }

    private static JsonArray positionArray(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    private static IndustrialDefinition.SpawnEntityDefinition parseSpawnEntity(JsonObject object) {
        if (object == null) {
            return new IndustrialDefinition.SpawnEntityDefinition(false, "", 0);
        }
        boolean enabled = bool(object, "enabled", false);
        String type = string(object, "type", "");
        int count = Math.max(0, integer(object, "count", 0));
        return new IndustrialDefinition.SpawnEntityDefinition(enabled, type, count);
    }

    private static List<BlockPos> parsePositions(JsonObject object, List<String> errors, String context) {
        List<BlockPos> positions = new ArrayList<>();
        if (object.has("pos")) {
            BlockPos pos = parsePositionArray(object.get("pos"));
            if (pos != null) {
                positions.add(pos);
            }
        }
        if (object.has("positions") && object.get("positions").isJsonArray()) {
            JsonArray array = object.getAsJsonArray("positions");
            int limit = Math.min(array.size(), MAX_POSITIONS);
            for (int i = 0; i < limit; i++) {
                BlockPos pos = parsePositionArray(array.get(i));
                if (pos != null) {
                    positions.add(pos);
                }
            }
        }
        if (positions.isEmpty()) {
            errors.add("missing_positions:" + context);
        }
        return List.copyOf(positions);
    }

    private static List<BlockPos> parseOptionalPositions(JsonObject object) {
        if (object == null) {
            return List.of();
        }
        List<BlockPos> positions = new ArrayList<>();
        if (object.has("pos")) {
            BlockPos pos = parsePositionArray(object.get("pos"));
            if (pos != null) {
                positions.add(pos);
            }
        }
        if (object.has("positions") && object.get("positions").isJsonArray()) {
            JsonArray array = object.getAsJsonArray("positions");
            int limit = Math.min(array.size(), MAX_POSITIONS);
            for (int i = 0; i < limit; i++) {
                BlockPos pos = parsePositionArray(array.get(i));
                if (pos != null) {
                    positions.add(pos);
                }
            }
        }
        return List.copyOf(positions);
    }

    private static BlockPos parsePositionArray(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() < 3) {
            return null;
        }
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static String stringAny(JsonObject object, String fallback, String... keys) {
        for (String key : keys) {
            String value = string(object, key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static double decimal(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName.toLowerCase(Locale.ROOT);
    }

    public record LoadResult(IndustrialDefinition definition, List<String> errors, Path path) {
        public static LoadResult missing(String error) {
            return new LoadResult(null, List.of(error), null);
        }

        public boolean valid() {
            return definition != null && errors.isEmpty();
        }
    }
}
