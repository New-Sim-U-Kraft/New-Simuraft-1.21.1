package common.cn.kafei.simukraft.building;

import common.cn.kafei.simukraft.SimuKraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class BuildingCatalog {
    private static final String ROOT_DIR = "simukraftbuilding";
    private static final ConcurrentMap<String, List<BuildingDefinition>> CATALOG_CACHE = new ConcurrentHashMap<>();

    private BuildingCatalog() {
    }

    public static Optional<BuildingDefinition> findBuilding(String category, String buildingFileName) {
        if (buildingFileName == null || buildingFileName.isBlank()) {
            return Optional.empty();
        }
        String normalizedName = stripExtension(buildingFileName);
        return listBuildings(category).stream()
                .filter(candidate -> stripExtension(candidate.metaFileName()).equalsIgnoreCase(normalizedName))
                .findFirst();
    }

    public static List<BuildingDefinition> listBuildings(String category) {
        String key = normalizeCategory(category);
        List<BuildingDefinition> cached = CATALOG_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        BuildingBuiltinResourceService.ensureCopied(rootDirectory());
        Path categoryDir = categoryDirectory(category);
        if (!Files.isDirectory(categoryDir)) {
            return List.of();
        }
        List<BuildingDefinition> buildings = new ArrayList<>();
        try (var stream = Files.list(categoryDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(BuildingCatalog::isMetaFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> {
                        BuildingDefinition definition = readDefinition(key, path);
                        if (definition != null) {
                            buildings.add(definition);
                        }
                    });
        } catch (IOException exception) {
            SimuKraft.LOGGER.error("Simukraft: Failed to scan building category {}", category, exception);
            return List.of();
        }
        List<BuildingDefinition> result = List.copyOf(buildings);
        CATALOG_CACHE.put(key, result);
        return result;
    }

    public static void clearCache() {
        CATALOG_CACHE.clear();
    }

    public static Path rootDirectory() {
        return FMLPaths.GAMEDIR.get().resolve(ROOT_DIR);
    }

    public static Path categoryDirectory(String category) {
        return rootDirectory().resolve(normalizeCategory(category));
    }

    private static BuildingDefinition readDefinition(String category, Path metaPath) {
        try {
            String fileName = metaPath.getFileName().toString();
            String text = Files.readString(metaPath, StandardCharsets.UTF_8);
            String baseName = stripExtension(fileName);
            String displayName = findValue(text, "name", baseName);
            String author = findValue(text, "author", "External");
            String size = findValue(text, "size", "-");
            String amount = findValue(text, "amount", findValue(text, "price", "-"));
            String structureFile = findValue(text, "structure", findValue(text, "file", ""));
            if (structureFile.isBlank()) {
                structureFile = baseName + ".nbt";
            }
            Path structurePath = categoryDirectory(category).resolve(structureFile);
            return new BuildingDefinition(category, displayName, size, amount, author, fileName, structureFile, metaPath, structurePath);
        } catch (IOException exception) {
            SimuKraft.LOGGER.error("Simukraft: Failed to read building meta file {}", metaPath, exception);
            return null;
        }
    }

    private static boolean isMetaFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".sk");
    }

    private static String findValue(String text, String key, String fallback) {
        String prefix = key + ":";
        int start = 0;
        int len = text.length();
        while (start < len) {
            int nl = text.indexOf('\n', start);
            int lineEnd = nl < 0 ? len : (nl > start && text.charAt(nl - 1) == '\r' ? nl - 1 : nl);
            int s = start;
            while (s < lineEnd && text.charAt(s) <= ' ') s++;
            if (lineEnd - s >= prefix.length() && text.regionMatches(true, s, prefix, 0, prefix.length())) {
                int vs = s + prefix.length();
                while (vs < lineEnd && text.charAt(vs) <= ' ') vs++;
                int ve = lineEnd;
                while (ve > vs && text.charAt(ve - 1) <= ' ') ve--;
                return ve > vs ? text.substring(vs, ve) : fallback;
            }
            start = nl < 0 ? len : nl + 1;
        }
        return fallback;
    }

    private static String normalizeCategory(String category) {
        return category == null ? "other" : category.toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    public record BuildingDefinition(String category,
                                     String displayName,
                                     String size,
                                     String amount,
                                     String author,
                                     String metaFileName,
                                     String structureFileName,
                                     Path metaPath,
                                     Path structurePath) {
    }
}
