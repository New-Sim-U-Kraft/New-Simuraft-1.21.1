package common.cn.kafei.simukraft.path;

public enum MovementIntent {
    WALK,
    RUN,
    WORK,
    // SELF_FEEDING：自动买饭流程独占导航意图，防止普通工作移动抢占。
    SELF_FEEDING,
    RETURN_HOME
}
