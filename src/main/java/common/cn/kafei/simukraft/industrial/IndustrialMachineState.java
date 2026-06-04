package common.cn.kafei.simukraft.industrial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IndustrialMachineState: 保存真实机器步骤的可恢复运行状态。
 */
record IndustrialMachineState(String stepKey,
                              String adapterId,
                              long machinePosLong,
                              String outputPolicy,
                              long startedAt,
                              long lastPollAt,
                              int timeoutTicks,
                              int pollTicks,
                              Map<String, Integer> baseline) {
    static IndustrialMachineState parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonObject baselineObject = root.has("baseline") && root.get("baseline").isJsonObject()
                    ? root.getAsJsonObject("baseline")
                    : new JsonObject();
            Map<String, Integer> baseline = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : baselineObject.entrySet()) {
                baseline.put(entry.getKey(), Math.max(0, entry.getValue().getAsInt()));
            }
            return new IndustrialMachineState(
                    string(root, "stepKey"),
                    string(root, "adapterId"),
                    root.has("machinePos") ? root.get("machinePos").getAsLong() : 0L,
                    string(root, "outputPolicy"),
                    root.has("startedAt") ? root.get("startedAt").getAsLong() : 0L,
                    root.has("lastPollAt") ? root.get("lastPollAt").getAsLong() : 0L,
                    root.has("timeoutTicks") ? root.get("timeoutTicks").getAsInt() : 12000,
                    root.has("pollTicks") ? root.get("pollTicks").getAsInt() : 20,
                    Map.copyOf(baseline)
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    IndustrialMachineState withLastPollAt(long gameTime) {
        return new IndustrialMachineState(stepKey, adapterId, machinePosLong, outputPolicy, startedAt, gameTime, timeoutTicks, pollTicks, baseline);
    }

    String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("stepKey", stepKey);
        root.addProperty("adapterId", adapterId);
        root.addProperty("machinePos", machinePosLong);
        root.addProperty("outputPolicy", outputPolicy);
        root.addProperty("startedAt", startedAt);
        root.addProperty("lastPollAt", lastPollAt);
        root.addProperty("timeoutTicks", timeoutTicks);
        root.addProperty("pollTicks", pollTicks);
        JsonObject baselineObject = new JsonObject();
        baseline.forEach(baselineObject::addProperty);
        root.add("baseline", baselineObject);
        return root.toString();
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
