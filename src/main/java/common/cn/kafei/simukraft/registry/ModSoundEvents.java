package common.cn.kafei.simukraft.registry;

import common.cn.kafei.simukraft.SimuKraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@SuppressWarnings("null")
public final class ModSoundEvents {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, SimuKraft.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> BUILD_BOX_PLACE = registerSound("block.build_box.place");
    public static final DeferredHolder<SoundEvent, SoundEvent> BUILD_BOX_BREAK = registerSound("block.build_box.break");
    public static final DeferredHolder<SoundEvent, SoundEvent> BUILD_BOX_OPEN = registerSound("ui.build_box.open");
    public static final DeferredHolder<SoundEvent, SoundEvent> CITY_CORE_OPEN = registerSound("ui.city_core.open");
    public static final DeferredHolder<SoundEvent, SoundEvent> FARMLAND_BOX_PLACE = registerSound("block.farmland_box.place");
    public static final DeferredHolder<SoundEvent, SoundEvent> FARMLAND_BOX_BREAK = registerSound("block.farmland_box.break");
    public static final DeferredHolder<SoundEvent, SoundEvent> PLAYER_WAKE_UP = registerSound("player.wake_up");
    public static final DeferredHolder<SoundEvent, SoundEvent> MONEY_COLLECT = registerSound("money.collect");
    public static final DeferredHolder<SoundEvent, SoundEvent> CONSTRUCTION_COMPLETE = registerSound("construction.complete");

    private ModSoundEvents() {
    }

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }

    private static DeferredHolder<SoundEvent, SoundEvent> registerSound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(SimuKraft.MOD_ID, name)));
    }
}
