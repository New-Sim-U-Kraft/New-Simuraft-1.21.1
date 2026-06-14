package common.cn.kafei.simukraft.commercial;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CommercialStockService {
    private CommercialStockService() {
    }

    /** restock: 按服务器运行 tick 间隔补货，单次事务批量持久化所有变更。 */
    public static void restock(ServerLevel level, BlockPos boxPos, CommercialDefinition definition) {
        if (level == null || boxPos == null || definition == null) {
            return;
        }
        CommercialStockManager manager = CommercialStockManager.get(level);
        long gameTime = level.getGameTime();
        List<CommercialStockData> dirty = new ArrayList<>();
        for (CommercialOffer.StockRule rule : uniqueStockRules(definition).values()) {
            CommercialStockData stock = manager.getOrCreate(boxPos, rule, gameTime);
            boolean changed = false;
            if (stock.maxStock() != rule.max()) {
                stock.setMaxStock(rule.max());
                changed = true;
            }
            if (stock.lastRestockGameTime() <= 0L) {
                stock.setLastRestockGameTime(gameTime);
                changed = true;
            }
            if (rule.restockEnabled()) {
                long lastRestock = stock.lastRestockGameTime();
                if (gameTime < lastRestock) {
                    stock.setLastRestockGameTime(gameTime);
                    changed = true;
                } else {
                    long elapsed = gameTime - lastRestock;
                    long passed = elapsed / rule.restockInterval();
                    if (passed > 0L) {
                        int added = stock.add(safeRestockAmount(passed, rule.restockAmount()));
                        stock.setLastRestockGameTime(lastRestock + passed * rule.restockInterval());
                        if (added > 0 || passed > 0) {
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                dirty.add(stock);
            }
        }
        if (!dirty.isEmpty()) {
            manager.persistBatch(dirty);
        }
    }

    /** snapshot: 获取当前商业箱库存快照。 */
    public static Map<String, CommercialStockData> snapshot(ServerLevel level, BlockPos boxPos) {
        return level == null || boxPos == null ? Map.of() : CommercialStockManager.get(level).allAt(boxPos);
    }

    /** removeBox: 删除商业箱全部库存。 */
    public static void removeBox(ServerLevel level, BlockPos boxPos) {
        if (level != null && boxPos != null) {
            CommercialStockManager.get(level).removeBox(boxPos);
        }
    }

    private static Map<String, CommercialOffer.StockRule> uniqueStockRules(CommercialDefinition definition) {
        Map<String, CommercialOffer.StockRule> rules = new LinkedHashMap<>();
        for (CommercialOffer offer : definition.offers()) {
            CommercialOffer.StockRule rule = offer.stock();
            if (rule != null && rule.sqliteBacked()) {
                rules.putIfAbsent(rule.itemId(), rule);
            }
        }
        return rules;
    }

    private static int safeRestockAmount(long passed, int amount) {
        long total = passed * (long) amount;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}
