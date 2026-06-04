package common.cn.kafei.simukraft.industrial;

import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

import javax.annotation.Nullable;
import java.util.List;

public abstract class IndustrialMachineOperationEvent extends Event {
    private final IndustrialMachineOperationContext context;

    protected IndustrialMachineOperationEvent(IndustrialMachineOperationContext context) {
        this.context = context;
    }

    public IndustrialMachineOperationContext context() {
        return context;
    }

    public static final class Start extends IndustrialMachineOperationEvent {
        private IndustrialMachineAdapter adapter;

        public Start(IndustrialMachineOperationContext context) {
            super(context);
        }

        @Nullable
        public IndustrialMachineAdapter adapter() {
            return adapter;
        }

        public void setAdapter(@Nullable IndustrialMachineAdapter adapter) {
            this.adapter = adapter;
        }
    }

    public static final class Tick extends IndustrialMachineOperationEvent {
        private TickDecision decision = TickDecision.PASS;
        private String statusKey = "";
        private String statusText = "";

        public Tick(IndustrialMachineOperationContext context) {
            super(context);
        }

        public TickDecision decision() {
            return decision;
        }

        public String statusKey() {
            return statusKey;
        }

        public String statusText() {
            return statusText;
        }

        public void complete() {
            this.decision = TickDecision.COMPLETE;
            this.statusKey = "";
            this.statusText = "";
        }

        public void waitRetry(String statusKey, String statusText) {
            this.decision = TickDecision.WAIT_RETRY;
            this.statusKey = statusKey != null ? statusKey : "";
            this.statusText = statusText != null ? statusText : "";
        }
    }

    public static final class Complete extends IndustrialMachineOperationEvent {
        private final List<ItemStack> outputs;

        public Complete(IndustrialMachineOperationContext context, List<ItemStack> outputs) {
            super(context);
            this.outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }

        public List<ItemStack> outputs() {
            return outputs;
        }
    }

    public static final class Abort extends IndustrialMachineOperationEvent {
        private final String reason;

        public Abort(IndustrialMachineOperationContext context, String reason) {
            super(context);
            this.reason = reason != null ? reason : "";
        }

        public String reason() {
            return reason;
        }
    }

    public enum TickDecision {
        PASS,
        COMPLETE,
        WAIT_RETRY
    }
}
