package common.cn.kafei.simukraft.commercial;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CommercialLegacyDefinitionParser {
    private static final long LEGACY_RESTOCK_INTERVAL_TICKS = 12000L;
    private static final int LEGACY_GROUP_COUNT = 64;

    private CommercialLegacyDefinitionParser() {
    }

    /** parse: 将旧版商业 JSON 的 trades/buyTrades 转换为新版报价。 */
    static List<CommercialOffer> parse(JsonObject root, List<String> errors, int maxOffers, int maxResources) {
        if (root == null) {
            return List.of();
        }
        List<CommercialOffer> offers = new ArrayList<>();
        CommercialVisibility visibility = legacyVisibility(string(root, "shopMode", "PLAYER_SELL"));
        JsonArray trades = root.getAsJsonArray("trades");
        if (trades != null) {
            int limit = Math.min(trades.size(), maxOffers);
            for (int i = 0; i < limit; i++) {
                JsonObject object = asObject(trades.get(i));
                if (object == null) {
                    errors.add("invalid_legacy_trade:" + i);
                    continue;
                }
                CommercialOffer offer = legacySellOffer(root, object, visibility, i, maxResources);
                if (offer.valid()) {
                    offers.add(offer);
                } else {
                    errors.add("invalid_legacy_trade:" + i);
                }
            }
        }
        JsonArray buyTrades = root.getAsJsonArray("buyTrades");
        if (buyTrades != null) {
            int remaining = Math.max(0, maxOffers - offers.size());
            int limit = Math.min(buyTrades.size(), remaining);
            for (int i = 0; i < limit; i++) {
                JsonObject object = asObject(buyTrades.get(i));
                if (object == null) {
                    errors.add("invalid_legacy_buy_trade:" + i);
                    continue;
                }
                CommercialOffer offer = legacyBuyOffer(object, visibility, i);
                if (offer.valid()) {
                    offers.add(offer);
                } else {
                    errors.add("invalid_legacy_buy_trade:" + i);
                }
            }
        }
        return List.copyOf(offers);
    }

    private static CommercialOffer legacySellOffer(JsonObject root, JsonObject object, CommercialVisibility visibility, int index, int maxResources) {
        String itemId = string(object, "item", "");
        double price = decimal(object, "sellPrice", decimal(object, "price", 0.0D));
        boolean retail = bool(object, "retail", false);
        int resultCount = retail ? 1 : LEGACY_GROUP_COUNT;
        int maxStock = Math.max(0, integerAny(object, 0, "maxStock", "max_stock", "max"));
        int restockAmount = Math.max(0, integerAny(object, 0, "restockAmount", "restock_amount"));
        List<CommercialOffer.MaterialRequirement> materials = legacyMaterials(root, object, itemId, maxResources);
        CommercialOffer.StockRule stock = maxStock > 0 || !materials.isEmpty()
                ? new CommercialOffer.StockRule(itemId, maxStock, maxStock, restockAmount, LEGACY_RESTOCK_INTERVAL_TICKS, materials)
                : null;
        return new CommercialOffer(
                legacyOfferId("sell", itemId, index),
                visibility,
                List.of(CommercialResource.money(price)),
                List.of(CommercialResource.item(itemId, resultCount)),
                stock
        );
    }

    private static CommercialOffer legacyBuyOffer(JsonObject object, CommercialVisibility visibility, int index) {
        String itemId = string(object, "item", "");
        double price = decimal(object, "buyPrice", decimal(object, "price", 0.0D));
        int maxGroups = Math.max(0, integerAny(object, 0, "maxBuyAmount", "max_buy_amount", "max"));
        int maxStock = (int) Math.min(Integer.MAX_VALUE, (long) maxGroups * LEGACY_GROUP_COUNT);
        CommercialOffer.StockRule stock = maxStock > 0
                ? new CommercialOffer.StockRule(itemId, maxStock, 0, 0, 0L, List.of())
                : null;
        return new CommercialOffer(
                legacyOfferId("buy", itemId, index),
                visibility,
                List.of(CommercialResource.item(itemId, LEGACY_GROUP_COUNT)),
                List.of(CommercialResource.money(price)),
                stock
        );
    }

    private static CommercialVisibility legacyVisibility(String shopMode) {
        String normalized = shopMode != null ? shopMode.trim().toUpperCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "NPC_SELL" -> CommercialVisibility.NPC;
            case "MIXED" -> CommercialVisibility.MIXED;
            case "PLAYER_SELL" -> CommercialVisibility.PLAYER;
            default -> CommercialVisibility.PLAYER;
        };
    }

    private static List<CommercialOffer.MaterialRequirement> legacyMaterials(JsonObject root, JsonObject trade, String resultItemId, int maxResources) {
        List<CommercialOffer.MaterialRequirement> materials = parseMaterials(arrayAny(trade, "requiredMaterials", "required_materials"), maxResources);
        if (!materials.isEmpty()) {
            return materials;
        }
        String singleItem = stringAny(trade, "", "requiredMaterial", "required_material");
        int singleCount = Math.max(0, integerAny(trade, 0, "requiredMaterialCount", "required_material_count"));
        if (!singleItem.isBlank() && singleCount > 0) {
            return List.of(new CommercialOffer.MaterialRequirement(singleItem, singleCount));
        }
        if (!bool(root, "requireMaterialsForSale", false)) {
            return List.of();
        }
        List<CommercialOffer.MaterialRequirement> global = parseMaterials(root.getAsJsonArray("materials"), maxResources);
        return inferLegacyMaterials(global, resultItemId);
    }

    private static List<CommercialOffer.MaterialRequirement> inferLegacyMaterials(List<CommercialOffer.MaterialRequirement> global, String resultItemId) {
        if (global.isEmpty()) {
            return List.of();
        }
        if (global.size() == 1) {
            return global;
        }
        String resultPath = pathPart(resultItemId);
        String rawPath = resultPath.startsWith("cooked_") ? resultPath.substring("cooked_".length()) : resultPath;
        for (CommercialOffer.MaterialRequirement requirement : global) {
            String materialPath = pathPart(requirement.itemId());
            if (materialPath.equals(rawPath) || rawPath.endsWith(materialPath) || materialPath.endsWith(rawPath)) {
                return List.of(requirement);
            }
        }
        return List.of();
    }

    private static List<CommercialOffer.MaterialRequirement> parseMaterials(@Nullable JsonArray array, int maxResources) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<CommercialOffer.MaterialRequirement> materials = new ArrayList<>();
        int limit = Math.min(array.size(), maxResources);
        for (int i = 0; i < limit; i++) {
            JsonObject object = asObject(array.get(i));
            if (object == null) {
                continue;
            }
            CommercialOffer.MaterialRequirement requirement = new CommercialOffer.MaterialRequirement(
                    string(object, "item", ""),
                    Math.max(1, integer(object, "count", integer(object, "amount", 1)))
            );
            if (requirement.valid()) {
                materials.add(requirement);
            }
        }
        return List.copyOf(materials);
    }

    private static String legacyOfferId(String prefix, String itemId, int index) {
        String path = pathPart(itemId).replaceAll("[^a-zA-Z0-9_./-]", "_").replace('/', '_');
        return prefix + "_" + (!path.isBlank() ? path : "item") + "_" + index;
    }

    @Nullable
    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    @Nullable
    private static JsonArray arrayAny(JsonObject object, String... keys) {
        if (object == null) {
            return null;
        }
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonArray()) {
                return object.getAsJsonArray(key);
            }
        }
        return null;
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

    private static int integerAny(JsonObject object, int fallback, String... keys) {
        for (String key : keys) {
            if (object != null && object.has(key)) {
                return integer(object, key, fallback);
            }
        }
        return fallback;
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

    private static String pathPart(String itemId) {
        String safe = itemId != null ? itemId.trim() : "";
        int split = safe.indexOf(':');
        return split >= 0 && split + 1 < safe.length() ? safe.substring(split + 1) : safe;
    }
}
