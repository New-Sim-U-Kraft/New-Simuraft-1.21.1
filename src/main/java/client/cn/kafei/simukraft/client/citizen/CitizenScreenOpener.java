package client.cn.kafei.simukraft.client.citizen;

import client.cn.kafei.simukraft.client.renderer.CitizenWorkStatusDisplayRegistry;
import client.cn.kafei.simukraft.client.ui.SimuKraftFlexLayout;
import client.cn.kafei.simukraft.client.ui.SimuKraftUiTheme;
import client.cn.kafei.simukraft.client.ui.SimuKraftWindowFrame;
import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor.ui.ViewContainer;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Tab;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import common.cn.kafei.simukraft.citizen.CitizenLevelService;
import common.cn.kafei.simukraft.citizen.CitizenSkillSnapshot;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.network.citizen.info.CitizenInfoResponsePacket;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public final class CitizenScreenOpener {
    private CitizenScreenOpener() {
    }

    public static void open(CitizenInfoResponsePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> minecraft.setScreen(new com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen(createUi(packet), Component.empty())));
    }

    private static ModularUI createUi(CitizenInfoResponsePacket packet) {
        SimuKraftFlexLayout.ScreenSize screenSize = SimuKraftFlexLayout.screenSize();
        CitizenInfoWindow window = new CitizenInfoWindow(packet);
        UIElement root = SimuKraftWindowFrame.create(
                screenSize,
                Component.translatable("screen.simukraft.citizen_info.title", packet.name()),
                workspace(packet, window),
                CitizenScreenOpener::close);
        return new ModularUI(SimuKraftUiTheme.createUi(root))
                .shouldCloseOnEsc(true)
                .shouldCloseOnKeyInventory(false);
    }

    private static UIElement workspace(CitizenInfoResponsePacket packet, CitizenInfoWindow window) {
        UIElement body = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.paddingAll(8);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.STRETCH);
        });
        body.addChild(window.rightTabs);
        return body;
    }

    private static ScrollerView scrollable(UIElement child) {
        ScrollerView scroller = new ScrollerView();
        scroller.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL));
        scroller.layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.flex(1);
        });
        scroller.addScrollViewChild(child);
        return scroller;
    }

    private static UIElement identityPanel(CitizenInfoResponsePacket packet) {
        UIElement panel = basePanel();
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.title", packet.name())));
        panel.addChild(contentSpacerSmall());
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.name", packet.name())));
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.gender", Component.translatable("gui.npc.gender." + genderKey(packet.gender())).getString())));
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.age", packet.age(), packet.lifespan())));
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.health", healthText(packet))));
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.hunger", hungerText(packet.hunger()))));
        return panel;
    }

    private static UIElement workPanel(CitizenInfoResponsePacket packet) {
        UIElement panel = basePanel();
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.menu.work")));
        panel.addChild(contentSpacerSmall());
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.work_status", workStatusText(packet))));
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.job", jobText(packet))));
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.skill_level", skillText(packet))));
        return panel;
    }

    private static UIElement residencePanel(CitizenInfoResponsePacket packet) {
        UIElement panel = basePanel();
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.menu.residence")));
        panel.addChild(contentSpacerSmall());
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.city", blankFallback(packet.cityName()))));
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.home", blankFallback(packet.homeName()))));
        panel.addChild(line(Component.translatable("screen.simukraft.citizen_info.workplace", blankFallback(packet.workplaceName()))));
        return panel;
    }

    private static UIElement basePanel() {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.paddingAll(10);
            layout.gapAll(5);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        });
    }

    private static UIElement contentSpacerSmall() {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.height(6);
        });
    }

    private static UIElement line(Component component) {
        UIElement wrapper = new UIElement();
        wrapper.layout(layout -> {
            layout.height(13);
            layout.widthPercent(100);
        });
        wrapper.style(style -> style.backgroundTexture(new TextTexture(component.getString())
                .setType(TextTexture.TextType.LEFT)
                .setWidth(200)));
        return wrapper;
    }

    private static String genderKey(String gender) {
        return "female".equalsIgnoreCase(gender) ? "female" : "male";
    }

    private static String blankFallback(String value) {
        return value == null || value.isBlank() ? Component.translatable("screen.simukraft.citizen_info.none").getString() : value;
    }

    private static String workStatusText(CitizenInfoResponsePacket packet) {
        return CitizenWorkStatusDisplayRegistry.resolve(packet.workStatus(), packet.statusLabel()).getString();
    }

    private static String jobText(CitizenInfoResponsePacket packet) {
        if (packet.jobName() != null && !packet.jobName().isBlank()) {
            return packet.jobName();
        }
        String jobId = packet.jobId();
        CityJobType packetJobType = CityJobType.fromName(packet.jobType());
        if (packetJobType == CityJobType.INDUSTRIAL_WORKER && jobId != null && !jobId.isBlank() && CityJobType.fromName(jobId) != CityJobType.INDUSTRIAL_WORKER) {
            return jobId;
        }
        return Component.translatable(packetJobType.translationKey()).getString();
    }

    private static String skillText(CitizenInfoResponsePacket packet) {
        CitizenSkillSnapshot skill = new CitizenSkillSnapshot(CityJobType.fromName(packet.jobType()), Math.max(1, packet.skillLevel()), Math.max(0, packet.skillXp()), Math.max(1, packet.skillMaxLevel()));
        if (skill.maxLevelReached()) {
            return "Lv." + skill.level() + " MAX";
        }
        return "Lv." + skill.level() + " " + CitizenLevelService.xpInCurrentLevel(skill) + "/" + CitizenLevelService.xpNeededForCurrentLevel(skill);
    }

    private static String healthText(CitizenInfoResponsePacket packet) {
        String health = String.format(Locale.ROOT, "%.1f/20.0", packet.health());
        return packet.sick() ? health + " " + Component.translatable("gui.npc_interaction.sick").getString() : health + " " + Component.translatable("gui.npc_interaction.healthy").getString();
    }

    private static String hungerText(double hunger) {
        String key;
        if (hunger <= 4.0D) {
            key = "gui.npc.hunger.level.starving";
        } else if (hunger <= 8.0D) {
            key = "gui.npc.hunger.level.very_hungry";
        } else if (hunger <= 14.0D) {
            key = "gui.npc.hunger.level.bit_hungry";
        } else {
            key = "gui.npc.hunger.level.full";
        }
        return String.format(Locale.ROOT, "%.1f/20.0 %s", hunger, Component.translatable(key).getString());
    }

    private static void close() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    private static final class CitizenInfoWindow {
        private final CitizenInfoResponsePacket packet;
        private final ViewContainer rightTabs = new ViewContainer();
        private final Map<String, View> openedTabs = new ConcurrentHashMap<>();

        private CitizenInfoWindow(CitizenInfoResponsePacket packet) {
            this.packet = packet;
            rightTabs.layout(layout -> {
                layout.flex(1);
                layout.heightPercent(100);
                layout.widthPercent(100);
            });
            openDefaultTabs();
        }

        private void openDefaultTabs() {
            openTab("identity", "screen.simukraft.citizen_info.menu.identity", scrollable(identityPanel(packet)));
            openTab("work", "screen.simukraft.citizen_info.menu.work", scrollable(workPanel(packet)));
            openTab("residence", "screen.simukraft.citizen_info.menu.residence", scrollable(residencePanel(packet)));
            View identity = openedTabs.get("identity");
            if (identity != null) {
                rightTabs.selectView(identity);
            }
        }

        private void openTab(String id, String titleKey, UIElement content) {
            View existing = openedTabs.get(id);
            if (existing != null && rightTabs.hasView(existing)) {
                rightTabs.selectView(existing);
                return;
            }
            View view = new StaticTabView(titleKey);
            view.addChild(content);
            openedTabs.put(id, view);
            rightTabs.addView(view);
        }
    }

    private static final class StaticTabView extends View {
        private StaticTabView(String name) {
            super(name, IGuiTexture.EMPTY);
            setCanRemove(false);
        }

        @Override
        public Tab createTab() {
            Tab tab = new Tab();
            tab.setText(Component.translatable(getName()));
            return tab;
        }
    }
}
