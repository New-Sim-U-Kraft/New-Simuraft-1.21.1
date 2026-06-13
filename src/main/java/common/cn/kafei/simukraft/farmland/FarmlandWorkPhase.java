package common.cn.kafei.simukraft.farmland;

enum FarmlandWorkPhase {
    DIG_WATER("dig_water", "挖水槽"),
    TILL("till", "耕地中"),
    PLANT("plant", "播种中"),
    BONEMEAL("bonemeal", "施骨粉"),
    HARVEST("harvest", "收割中");

    static final FarmlandWorkPhase[] ORDERED = {DIG_WATER, TILL, PLANT, BONEMEAL, HARVEST};
    private final String id;
    private final String label;

    FarmlandWorkPhase(String id, String label) {
        this.id = id;
        this.label = label;
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }
}
