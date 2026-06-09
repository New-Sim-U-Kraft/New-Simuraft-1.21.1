package client.cn.kafei.simukraft.client.city;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import client.cn.kafei.simukraft.client.ui.SimuKraftUiTheme;
import client.cn.kafei.simukraft.client.ui.SimuKraftFlexLayout;
import client.cn.kafei.simukraft.client.ui.SimuKraftWindowFrame;
import client.cn.kafei.simukraft.client.city.map.SimuMapManager;
import client.cn.kafei.simukraft.client.city.map.SimuMapRegion;
import common.cn.kafei.simukraft.city.CityPermissionLevel;
import common.cn.kafei.simukraft.network.city.chunk.CityChunkBatchPurchasePacket;
import common.cn.kafei.simukraft.network.city.chunk.CityChunkPurchasePacket;
import common.cn.kafei.simukraft.network.city.core.CityCoreCreateCityPacket;
import common.cn.kafei.simukraft.network.city.core.CityCoreManageCityPacket;
import common.cn.kafei.simukraft.network.city.core.CityCoreOpenResponsePacket;
import common.cn.kafei.simukraft.network.city.map.CityCoreMapRequestPacket;
import common.cn.kafei.simukraft.network.city.map.CityCoreMapResponsePacket;
import common.cn.kafei.simukraft.network.city.member.CityCoreMemberActionPacket;
import common.cn.kafei.simukraft.network.city.member.CityCoreMembersRequestPacket;
import common.cn.kafei.simukraft.network.city.member.CityCoreMembersResponsePacket;

import com.lowdragmc.lowdraglib2.editor.ui.View;
import com.lowdragmc.lowdraglib2.editor.ui.ViewContainer;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Tab;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public final class CityCoreScreenOpener {
    private static final int BUTTON_WIDTH = 120;
    private static final int COLLAPSED_SIDEBAR_WIDTH = 18;
    private static final int BUTTON_HEIGHT = 24;
    private static final int BACK_BUTTON_WIDTH = 52;
    private static final int BACK_BUTTON_HEIGHT = 20;
    private static volatile CityChunkMapElement activeMapElement;

    private CityCoreScreenOpener() {
    }

    public static void open(CityCoreOpenResponsePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> minecraft.setScreen(new com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen(createUi(packet), Component.empty())));
    }

    public static void openMembers(CityCoreMembersResponsePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !(minecraft.screen instanceof com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen)) {
            return;
        }
        minecraft.execute(() -> minecraft.setScreen(new com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen(createUi(packet), Component.empty())));
    }

    public static void openMap(CityCoreMapResponsePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Set<Long> chunks = packet.chunks().stream()
                .map(chunk -> ChunkPos.asLong(chunk.chunkX(), chunk.chunkZ()))
                .collect(Collectors.toUnmodifiableSet());
        minecraft.execute(() -> {
            if (!(minecraft.screen instanceof com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen)) {
                return;
            }
            ClientCityChunkCache.getInstance().updateCurrentCity(packet.cityId(), chunks, packet.pos(), packet.cityName());
            CityChunkMapElement mapElement = activeMapElement;
            if (mapElement != null && mapElement.matches(packet.pos())) {
                mapElement.updatePacket(packet);
                return;
            }
            minecraft.setScreen(new com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen(createUi(packet), Component.empty()));
        });
    }

    private static ModularUI createUi(CityCoreMapResponsePacket packet) {
        CityCoreWindow window = new CityCoreWindow(packet);
        UIElement root = createWindowRoot(window);
        return new ModularUI(SimuKraftUiTheme.createUi(root))
                .shouldCloseOnEsc(true)
                .shouldCloseOnKeyInventory(false);
    }

    private static ModularUI createUi(CityCoreMembersResponsePacket packet) {
        CityCoreWindow window = new CityCoreWindow(packet);
        UIElement root = createWindowRoot(window);
        return new ModularUI(SimuKraftUiTheme.createUi(root))
                .shouldCloseOnEsc(true)
                .shouldCloseOnKeyInventory(false);
    }

    private static ModularUI createUi(CityCoreOpenResponsePacket packet) {
        CityCoreWindow window = new CityCoreWindow(packet);
        UIElement root = createWindowRoot(window);
        return new ModularUI(SimuKraftUiTheme.createUi(root))
                .shouldCloseOnEsc(true)
                .shouldCloseOnKeyInventory(false);
    }

    private static UIElement createWindowRoot(CityCoreWindow window) {
        SimuKraftFlexLayout.ScreenSize screenSize = SimuKraftFlexLayout.screenSize();
        return SimuKraftWindowFrame.create(
                screenSize,
                Component.translatable("screen.simukraft.city_core.title"),
                workspace(window.packet, window),
                CityCoreScreenOpener::close);
    }

    private static UIElement workspace(CityCoreOpenResponsePacket packet, CityCoreWindow window) {
        UIElement body = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.paddingAll(8);
            layout.flexDirection(FlexDirection.ROW);
            layout.gapAll(8);
            layout.alignItems(AlignItems.STRETCH);
        });
        body.addChild(window.sidebarContainer);
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

    private static UIElement menuColumn(CityCoreOpenResponsePacket packet, CityCoreWindow window) {
        UIElement menu = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.gapAll(5);
            layout.alignItems(AlignItems.STRETCH);
        });
        if (!packet.hasCity()) {
            menu.addChild(menuButton("screen.simukraft.city_core.create", () -> window.openTab("create", "screen.simukraft.city_core.create", createPanel(packet))));
        } else if (packet.canManageCity()) {
            menu.addChild(menuButton("screen.simukraft.city_core.menu.info", () -> window.openTab("info", "screen.simukraft.city_core.menu.info", scrollable(contentPanel(packet)))));
            menu.addChild(menuButton("screen.simukraft.city_core.map_title", () -> requestMap(packet)));
            menu.addChild(menuButton("screen.simukraft.city_core.menu.edit", () -> window.openTab("edit", "screen.simukraft.city_core.menu.edit", editPanel(packet))));
            menu.addChild(menuButton("screen.simukraft.city_core.menu.upgrade", () -> window.openTab("upgrade", "screen.simukraft.city_core.menu.upgrade", upgradePanel(packet))));
            menu.addChild(menuButton("screen.simukraft.city_core.menu.citizens", () -> requestMembers(packet)));
            menu.addChild(menuButton("screen.simukraft.city_core.menu.officials", () -> requestMembers(packet)));
            menu.addChild(menuButton("screen.simukraft.city_core.menu.finance", () -> window.openTab("finance", "screen.simukraft.city_core.menu.finance", financePanel(packet))));
        } else {
            menu.addChild(menuButton("screen.simukraft.city_core.menu.info", () -> window.openTab("info", "screen.simukraft.city_core.menu.info", scrollable(contentPanel(packet)))));
            menu.addChild(menuButton("screen.simukraft.city_core.map_title", () -> requestMap(packet)));
        }
        menu.addChild(menuSpacer());
        menu.addChild(closeButton("screen.simukraft.city_core.close", CityCoreScreenOpener::close));
        return menu;
    }

    private static UIElement createPanel(CityCoreOpenResponsePacket packet) {
        UIElement panel = basePanel();
        addCreateContent(panel, packet);
        return scrollable(panel);
    }

    private static UIElement editPanel(CityCoreOpenResponsePacket packet) {
        UIElement panel = basePanel();
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.edit.rename_title")));
        TextField renameField = textField(packet.cityName(), 200);
        panel.addChild(renameField);
        panel.addChild(contentButton("screen.simukraft.city_core.edit.rename", () -> PacketDistributor.sendToServer(new CityCoreManageCityPacket(packet.pos(), CityCoreManageCityPacket.Action.RENAME, renameField.getValue()))));
        panel.addChild(contentSpacer());
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.edit.delete_title")));
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.edit.delete_tip", packet.cityName())));
        TextField deleteField = textField("", 200);
        deleteField.getTextFieldStyle().placeholder(Component.translatable("screen.simukraft.city_core.edit.delete_placeholder"));
        panel.addChild(deleteField);
        panel.addChild(contentButton("screen.simukraft.city_core.edit.delete", () -> PacketDistributor.sendToServer(new CityCoreManageCityPacket(packet.pos(), CityCoreManageCityPacket.Action.DELETE, deleteField.getValue()))));
        return scrollable(panel);
    }

    private static UIElement upgradePanel(CityCoreOpenResponsePacket packet) {
        UIElement panel = basePanel();
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.upgrade.current", packet.cityLevel())));
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.upgrade.next", packet.cityLevel() + 1)));
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.upgrade.pending")));
        return scrollable(panel);
    }

    private static UIElement financePanel(CityCoreOpenResponsePacket packet) {
        UIElement panel = basePanel();
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.finance.title")));
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.funds", String.format(Locale.ROOT, "%.2f", packet.funds()))));
        panel.addChild(contentSpacer());
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.finance.recent")));
        if (packet.financeEntries().isEmpty()) {
            panel.addChild(line(Component.translatable("screen.simukraft.city_core.finance.empty")));
        } else {
            for (CityCoreOpenResponsePacket.FinanceEntry entry : packet.financeEntries()) {
                panel.addChild(line(Component.translatable(
                        "screen.simukraft.city_core.finance.row",
                        financeTypeText(entry),
                        String.format(Locale.ROOT, "%+.2f", entry.amount()),
                        String.format(Locale.ROOT, "%.2f", entry.balanceAfter()),
                        financeReasonText(entry),
                        entry.actorName().isBlank() ? Component.translatable("screen.simukraft.city_core.finance.system").getString() : entry.actorName()
                )));
            }
        }
        return scrollable(panel);
    }

    private static String financeTypeText(CityCoreOpenResponsePacket.FinanceEntry entry) {
        return Component.translatable("screen.simukraft.city_core.finance.type." + entry.type().name().toLowerCase(Locale.ROOT)).getString();
    }

    private static String financeReasonText(CityCoreOpenResponsePacket.FinanceEntry entry) {
        String reason = entry.reason();
        if (reason == null || reason.isBlank()) {
            return Component.translatable("screen.simukraft.city_core.finance.reason.unknown").getString();
        }
        return Component.translatable("screen.simukraft.city_core.finance.reason." + reason).getString();
    }

    private static void requestMembers(CityCoreOpenResponsePacket packet) {
        PacketDistributor.sendToServer(new CityCoreMembersRequestPacket(packet.pos()));
    }

    private static void requestMap(CityCoreOpenResponsePacket packet) {
        PacketDistributor.sendToServer(new CityCoreMapRequestPacket(packet.pos()));
    }

    private static UIElement cityMapPanel(CityCoreMapResponsePacket packet) {
        UIElement panel = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.CENTER);
            layout.justifyContent(AlignContent.CENTER);
        });
        panel.addChild(new CityChunkMapElement(packet));
        return panel;
    }

    private static UIElement membersPanel(CityCoreMembersResponsePacket packet) {
        UIElement panel = basePanel();
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.members.title")));
        if (packet.viewerPermission().atLeast(CityPermissionLevel.OFFICIAL)) {
            panel.addChild(addMemberPanel(packet));
            panel.addChild(contentSpacer());
        }
        addMemberGroup(panel, packet.members(), CityPermissionLevel.MAYOR, "screen.simukraft.city_core.members.group_mayor", packet.viewerPermission(), packet.pos());
        addMemberGroup(panel, packet.members(), CityPermissionLevel.OFFICIAL, "screen.simukraft.city_core.members.group_officials", packet.viewerPermission(), packet.pos());
        addMemberGroup(panel, packet.members(), CityPermissionLevel.CITIZEN, "screen.simukraft.city_core.members.group_citizens", packet.viewerPermission(), packet.pos());
        return scrollable(panel);
    }

    private static UIElement addMemberPanel(CityCoreMembersResponsePacket packet) {
        UIElement panel = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.gapAll(4);
            layout.alignItems(AlignItems.STRETCH);
        });
        panel.addChild(sectionLabel("screen.simukraft.city_core.members.add_title"));
        panel.addChild(manualAddRow(packet));
        if (!packet.onlineCandidates().isEmpty()) {
            panel.addChild(sectionLabel("screen.simukraft.city_core.members.candidates"));
            packet.onlineCandidates().forEach(candidate -> panel.addChild(candidateRow(packet, candidate)));
        } else {
            panel.addChild(line(Component.translatable("screen.simukraft.city_core.members.candidate_empty")));
        }
        return panel;
    }

    private static UIElement manualAddRow(CityCoreMembersResponsePacket packet) {
        TextField addField = textField("", 180);
        addField.getTextFieldStyle().placeholder(Component.translatable("screen.simukraft.city_core.members.add_placeholder"));
        UIElement row = addRow();
        row.addChild(addField.layout(layout -> {
            layout.flex(1);
            layout.height(20);
        }));
        row.addChild(memberActionButton("screen.simukraft.city_core.members.add_citizen", 72,
                () -> sendAddMember(packet.pos(), CityCoreMemberActionPacket.EMPTY_PLAYER_ID, addField.getValue(), CityPermissionLevel.CITIZEN)));
        if (packet.viewerPermission() == CityPermissionLevel.MAYOR) {
            row.addChild(memberActionButton("screen.simukraft.city_core.members.add_official", 72,
                    () -> sendAddMember(packet.pos(), CityCoreMemberActionPacket.EMPTY_PLAYER_ID, addField.getValue(), CityPermissionLevel.OFFICIAL)));
            row.addChild(memberActionButton("screen.simukraft.city_core.members.add_mayor", 72,
                    () -> sendAddMember(packet.pos(), CityCoreMemberActionPacket.EMPTY_PLAYER_ID, addField.getValue(), CityPermissionLevel.MAYOR)));
        }
        return row;
    }

    private static UIElement candidateRow(CityCoreMembersResponsePacket packet, CityCoreMembersResponsePacket.CandidateEntry candidate) {
        UIElement row = addRow();
        Label name = line(Component.literal(candidate.playerName()));
        name.layout(layout -> {
            layout.flex(1);
            layout.height(13);
        });
        row.addChild(name);
        row.addChild(memberActionButton("screen.simukraft.city_core.members.add_citizen", 72,
                () -> sendAddMember(packet.pos(), candidate.playerId(), candidate.playerName(), CityPermissionLevel.CITIZEN)));
        if (packet.viewerPermission() == CityPermissionLevel.MAYOR) {
            row.addChild(memberActionButton("screen.simukraft.city_core.members.add_official", 72,
                    () -> sendAddMember(packet.pos(), candidate.playerId(), candidate.playerName(), CityPermissionLevel.OFFICIAL)));
            row.addChild(memberActionButton("screen.simukraft.city_core.members.add_mayor", 72,
                    () -> sendAddMember(packet.pos(), candidate.playerId(), candidate.playerName(), CityPermissionLevel.MAYOR)));
        }
        return row;
    }

    private static void addMemberGroup(UIElement panel, List<CityCoreMembersResponsePacket.MemberEntry> members, CityPermissionLevel groupPermission, String titleKey, CityPermissionLevel viewerPermission, BlockPos pos) {
        List<CityCoreMembersResponsePacket.MemberEntry> groupMembers = members.stream()
                .filter(member -> member.permissionLevel() == groupPermission)
                .toList();
        if (groupMembers.isEmpty()) {
            return;
        }
        panel.addChild(sectionLabel(titleKey));
        addMemberRows(panel, groupMembers, viewerPermission, pos);
    }

    private static void addMemberRows(UIElement panel, List<CityCoreMembersResponsePacket.MemberEntry> members, CityPermissionLevel viewerPermission, BlockPos pos) {
        for (CityCoreMembersResponsePacket.MemberEntry member : members) {
            UIElement row = addRow();
            row.addChild(memberLabel(member));
            if (viewerPermission == CityPermissionLevel.MAYOR && member.permissionLevel() != CityPermissionLevel.MAYOR) {
                row.addChild(memberActionButton("screen.simukraft.city_core.members.set_citizen", 56, () -> PacketDistributor.sendToServer(new CityCoreMemberActionPacket(pos, CityCoreMemberActionPacket.Action.SET_PERMISSION, member.playerId(), member.playerName(), CityPermissionLevel.CITIZEN))));
                row.addChild(memberActionButton("screen.simukraft.city_core.members.set_official", 56, () -> PacketDistributor.sendToServer(new CityCoreMemberActionPacket(pos, CityCoreMemberActionPacket.Action.SET_PERMISSION, member.playerId(), member.playerName(), CityPermissionLevel.OFFICIAL))));
                row.addChild(memberActionButton("screen.simukraft.city_core.members.set_mayor", 56, () -> PacketDistributor.sendToServer(new CityCoreMemberActionPacket(pos, CityCoreMemberActionPacket.Action.SET_PERMISSION, member.playerId(), member.playerName(), CityPermissionLevel.MAYOR))));
            }
            if (viewerPermission.atLeast(CityPermissionLevel.OFFICIAL) && member.permissionLevel() != CityPermissionLevel.MAYOR) {
                row.addChild(memberActionButton("screen.simukraft.city_core.members.remove", 48, () -> PacketDistributor.sendToServer(new CityCoreMemberActionPacket(pos, CityCoreMemberActionPacket.Action.REMOVE, member.playerId(), member.playerName(), CityPermissionLevel.CITIZEN))));
            }
            panel.addChild(row);
        }
    }

    private static Label memberLabel(CityCoreMembersResponsePacket.MemberEntry member) {
        Label label = line(Component.translatable("screen.simukraft.city_core.members.row", member.playerName(), permissionText(member.permissionLevel())));
        label.layout(layout -> {
            layout.height(13);
            layout.flex(1);
        });
        return label;
    }

    private static UIElement addRow() {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.height(24);
            layout.flexDirection(FlexDirection.ROW);
            layout.gapAll(4);
            layout.alignItems(AlignItems.CENTER);
        });
    }

    private static Label sectionLabel(String key) {
        Label label = line(Component.translatable(key));
        label.layout(layout -> {
            layout.widthPercent(100);
            layout.height(14);
        });
        return label;
    }

    private static Button memberActionButton(String key, int width, Runnable action) {
        Button button = baseButton(key, action);
        button.layout(layout -> {
            layout.width(width);
            layout.height(18);
            layout.flexShrink(0);
            layout.flexDirection(FlexDirection.ROW);
            layout.justifyContent(AlignContent.CENTER);
        });
        return button;
    }

    private static void sendAddMember(BlockPos pos, java.util.UUID playerId, String playerName, CityPermissionLevel permissionLevel) {
        PacketDistributor.sendToServer(new CityCoreMemberActionPacket(pos, CityCoreMemberActionPacket.Action.ADD, playerId, playerName, permissionLevel));
    }

    private static String permissionText(CityPermissionLevel permissionLevel) {
        return Component.translatable("permission.simukraft." + permissionLevel.name().toLowerCase(Locale.ROOT)).getString();
    }

    private static UIElement contentPanel(CityCoreOpenResponsePacket packet) {
        UIElement panel = basePanel();
        if (!packet.hasCity()) {
            addCreateContent(panel, packet);
        } else if (packet.canManageCity()) {
            addManageContent(panel, packet);
        } else {
            addReadOnlyContent(panel, packet);
        }
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

    private static void addCreateContent(UIElement panel, CityCoreOpenResponsePacket packet) {
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.no_city")));
        panel.addChild(contentSpacer());
        TextField nameField = textField("", 200);
        nameField.getTextFieldStyle().placeholder(Component.translatable("screen.simukraft.city_core.name_placeholder"));
        panel.addChild(nameField);
        if (packet.canCreateCity()) {
            panel.addChild(contentButton("screen.simukraft.city_core.create", () -> PacketDistributor.sendToServer(new CityCoreCreateCityPacket(packet.pos(), nameField.getValue()))));
        } else {
            panel.addChild(line(Component.translatable("screen.simukraft.city_core.cannot_create")));
        }
        panel.addChild(contentSpacer());
    }

    private static void addManageContent(UIElement panel, CityCoreOpenResponsePacket packet) {
        addCitySummary(panel, packet);
    }

    private static void addReadOnlyContent(UIElement panel, CityCoreOpenResponsePacket packet) {
        panel.addChild(line(Component.translatable("screen.simukraft.city_info.title")));
        addCitySummary(panel, packet);
        panel.addChild(line(Component.translatable("screen.simukraft.city_core.manage_disabled")));
    }

    private static UIElement menuSpacer() {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexGrow(1);
        });
    }

    private static UIElement contentSpacer() {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.flexGrow(1);
        });
    }

    private static void addCitySummary(UIElement root, CityCoreOpenResponsePacket packet) {
        root.addChildren(
                line(Component.translatable("screen.simukraft.city_core.city_name", packet.cityName())),
                line(Component.translatable("screen.simukraft.city_core.funds", String.format(Locale.ROOT, "%.2f", packet.funds()))),
                line(Component.translatable("screen.simukraft.city_core.level", packet.cityLevel())),
                line(Component.translatable("screen.simukraft.city_core.population", packet.cityPopulation(), packet.housingCapacity())),
                line(Component.translatable("screen.simukraft.city_core.permission", Component.translatable("permission.simukraft." + packet.permissionLevel().name().toLowerCase(Locale.ROOT)).getString())),
                line(Component.translatable("screen.simukraft.city_core.core_pos", packet.pos().getX(), packet.pos().getY(), packet.pos().getZ()))
        );
        addJobSummary(root, packet);
    }

    private static void addJobSummary(UIElement root, CityCoreOpenResponsePacket packet) {
        root.addChild(contentSpacerSmall());
        root.addChild(line(Component.translatable("screen.simukraft.city_core.job.title")));
        if (packet.jobStats().isEmpty()) {
            root.addChild(line(Component.translatable("screen.simukraft.city_core.job.empty")));
            return;
        }
        for (CityCoreOpenResponsePacket.JobStat stat : packet.jobStats()) {
            root.addChild(line(Component.translatable(
                    "screen.simukraft.city_core.job.row",
                    Component.translatable(stat.type().translationKey()).getString(),
                    stat.pointCount(),
                    stat.assigned(),
                    stat.capacity()
            )));
        }
    }

    private static UIElement contentSpacerSmall() {
        return new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.height(6);
        });
    }

    private static void close() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    private static Label line(Component component) {
        Label label = new Label();
        label.setText(component);
        label.layout(layout -> {
            layout.height(13);
            layout.widthPercent(100);
        });
        return label;
    }

    private static UIElement sidebarHeader(boolean collapsed, Runnable action) {
        UIElement header = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.height(20);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.justifyContent(AlignContent.FLEX_START);
            layout.paddingLeft(2);
        });
        header.addChild(sidebarToggleButton(collapsed, action));
        return header;
    }

    private static UIElement sidebarToggleButton(boolean collapsed, Runnable action) {
        Button button = new Button().noText();
        button.addChild(new UIElement()
                .layout(layout -> layout.width(10).height(10))
                .style(style -> style.backgroundTexture(collapsed ? Icons.EXPAND_HORIZONTAL : Icons.COLLAPSE_HORIZONTAL)));
        button.setOnClick(event -> action.run());
        button.layout(layout -> {
            layout.width(14);
            layout.height(14);
            layout.alignItems(AlignItems.CENTER);
            layout.justifyContent(AlignContent.CENTER);
        });
        return button;
    }

    private static Button menuButton(String key, Runnable action) {
        Button button = baseButton(key, action);
        button.layout(layout -> {
            layout.height(BUTTON_HEIGHT);
            layout.widthPercent(100);
            layout.flexDirection(FlexDirection.ROW);
            layout.justifyContent(AlignContent.CENTER);
        });
        return button;
    }

    private static Button closeButton(String key, Runnable action) {
        Button button = baseButton(key, action);
        button.layout(layout -> {
            layout.width(BACK_BUTTON_WIDTH);
            layout.height(BACK_BUTTON_HEIGHT);
            layout.flexDirection(FlexDirection.ROW);
            layout.justifyContent(AlignContent.CENTER);
        });
        return button;
    }

    private static Button contentButton(String key, Runnable action) {
        Button button = baseButton(key, action);
        button.layout(layout -> {
            layout.width(100);
            layout.height(20);
            layout.flexDirection(FlexDirection.ROW);
            layout.justifyContent(AlignContent.CENTER);
        });
        return button;
    }

    private static TextField textField(String value, int width) {
        TextField field = new TextField();
        field.setText(value == null ? "" : value);
        field.layout(layout -> {
            layout.width(width);
            layout.height(20);
        });
        return field;
    }

    private static Button baseButton(String key, Runnable action) {
        Button button = new Button();
        button.setText(Component.translatable(key));
        button.setOnClick(event -> action.run());
        return button;
    }

    private static final class CityCoreWindow {
        private final CityCoreOpenResponsePacket packet;
        private final CityCoreMembersResponsePacket membersPacket;
        private final CityCoreMapResponsePacket mapPacket;
        private final ViewContainer rightTabs = new ViewContainer();
        private final Map<String, View> openedTabs = new ConcurrentHashMap<>();
        private final UIElement sidebarContainer = new UIElement();
        private boolean sidebarCollapsed;

        private CityCoreWindow(CityCoreOpenResponsePacket packet) {
            this(packet, null, null);
        }

        private CityCoreWindow(CityCoreMembersResponsePacket membersPacket) {
            this(new CityCoreOpenResponsePacket(membersPacket.pos(), true, membersPacket.cityId(), membersPacket.cityName(), membersPacket.funds(), membersPacket.cityLevel(), membersPacket.members().size(), 0, 0, membersPacket.viewerPermission(), false, membersPacket.canManageCity(), List.of(), List.of(), List.of()), membersPacket, null);
        }

        private CityCoreWindow(CityCoreMapResponsePacket mapPacket) {
            this(new CityCoreOpenResponsePacket(mapPacket.pos(), true, mapPacket.cityId(), mapPacket.cityName(), mapPacket.funds(), mapPacket.cityLevel(), mapPacket.memberCount(), 0, 0, mapPacket.permissionLevel(), false, mapPacket.canManageCity(), List.of(), List.of(), List.of()), null, mapPacket);
        }

        private CityCoreWindow(CityCoreOpenResponsePacket packet, CityCoreMembersResponsePacket membersPacket, CityCoreMapResponsePacket mapPacket) {
            this.packet = packet;
            this.membersPacket = membersPacket;
            this.mapPacket = mapPacket;
            rightTabs.layout(layout -> {
                layout.flex(1);
                layout.heightPercent(100);
                layout.widthPercent(100);
            });
            rebuildSidebar();
            openDefaultTabs();
        }

        private void rebuildSidebar() {
            sidebarContainer.clearAllChildren();
            sidebarContainer.layout(layout -> {
                layout.width(sidebarCollapsed ? COLLAPSED_SIDEBAR_WIDTH : BUTTON_WIDTH);
                layout.heightPercent(100);
                layout.flexShrink(0);
                layout.flexDirection(FlexDirection.COLUMN);
                layout.alignItems(AlignItems.STRETCH);
            });
            sidebarContainer.addChild(sidebarHeader(sidebarCollapsed, this::toggleSidebar));
            if (!sidebarCollapsed) {
                sidebarContainer.addChild(scrollable(menuColumn(packet, this)));
            }
        }

        private void toggleSidebar() {
            sidebarCollapsed = !sidebarCollapsed;
            rebuildSidebar();
        }

        private void openDefaultTabs() {
            if (mapPacket != null) {
                openTab("info", "screen.simukraft.city_core.menu.info", scrollable(contentPanel(packet)));
                openTab("map", "screen.simukraft.city_core.map_title", cityMapPanel(mapPacket));
            } else if (membersPacket != null) {
                openTab("members", "screen.simukraft.city_core.members.title", membersPanel(membersPacket));
            } else if (packet.hasCity()) {
                openTab("info", "screen.simukraft.city_core.menu.info", scrollable(contentPanel(packet)));
                requestMap(packet);
            } else {
                openTab("create", "screen.simukraft.city_core.create", createPanel(packet));
            }
        }

        private void openTab(String id, String titleKey, UIElement content) {
            View existing = openedTabs.get(id);
            if (existing != null && rightTabs.hasView(existing)) {
                rightTabs.selectView(existing);
                return;
            }
            BrowserTabView view = new BrowserTabView(titleKey, () -> openedTabs.remove(id));
            view.addChild(content);
            openedTabs.put(id, view);
            rightTabs.addView(view);
            rightTabs.selectView(view);
        }

    }

    private static final class CityChunkMapElement extends UIElement {
        private static final double MIN_ZOOM = 0.5D;
        private static final double MAX_ZOOM = 10.0D;
        private static final double ZOOM_STEP = 0.5D;
        private static final int MAP_SIDE_PADDING = 4;
        private static final int MAP_TOP_PADDING = 4;
        private static final int CURRENT_BORDER_COLOR = 0xCC00DD00;
        private static final int OTHER_BORDER_COLOR = 0xCCFF8800;
        private static final int CURRENT_CHUNK_FILL_COLOR = 0x5500DD00;
        private static final int OTHER_CHUNK_FILL_COLOR = 0x55FF8800;
        private static final int BATCH_CLAIM_PREVIEW_COLOR = 0x66FFFF55;
        private static final int GRID_COLOR = 0x40000000;
        private static final int CORE_MARKER_COLOR = 0xFF4080FF;
        private static final int MAX_BATCH_CLAIM_CHUNKS = 256;
        private static final String BATCH_CLAIM_DRAG_MARKER = "city_chunk_batch_claim";
        private volatile CityCoreMapResponsePacket packet;
        private final ClientCityChunkCache cache = ClientCityChunkCache.getInstance();
        private final SimuMapManager mapManager = SimuMapManager.getInstance();
        private final LinkedHashSet<Long> batchClaimChunks = new LinkedHashSet<>();
        private double zoomLevel = 4.0D;
        private double offsetX;
        private double offsetY;
        private int contextMenuChunkX;
        private int contextMenuChunkZ;
        private double contextMenuX;
        private double contextMenuY;
        private int contextMenuDrawX;
        private int contextMenuDrawY;
        private int contextMenuWidth;
        private int contextMenuHeight;
        private boolean contextMenuVisible;
        private boolean batchClaiming;
        private boolean mapConsumerReleased;

        private CityChunkMapElement(CityCoreMapResponsePacket packet) {
            this.packet = packet;
            // 地图控件打开时才激活地图系统，避免常驻扫描占用客户端性能。
            mapManager.init();
            mapManager.acquireConsumer();
            forceInitialMapScan();
            centerMapOnCityCore();
            layout(layout -> {
                layout.widthPercent(100);
                layout.heightPercent(100);
                layout.flex(1);
            });
            addEventListener(UIEvents.MOUSE_WHEEL, this::onMouseWheel);
            addEventListener(UIEvents.MOUSE_DOWN, this::onMouseDown);
            addEventListener(UIEvents.MOUSE_UP, this::onMouseUp);
            addEventListener(UIEvents.DRAG_SOURCE_UPDATE, this::onDragUpdate);
            addEventListener(UIEvents.DRAG_END, this::onDragEnd);
            activeMapElement = this;
        }

        @Override
        protected void onRemoved() {
            if (activeMapElement == this) {
                activeMapElement = null;
            }
            releaseMapConsumer();
            super.onRemoved();
        }

        private boolean matches(BlockPos pos) {
            return packet.pos().equals(pos);
        }

        private void updatePacket(CityCoreMapResponsePacket packet) {
            this.packet = packet;
        }

        @Override
        public void drawBackgroundAdditional(GUIContext guiContext) {
            int x = Math.round(getPositionX());
            int y = Math.round(getPositionY());
            int canvasWidth = Math.round(getSizeWidth());
            int canvasHeight = Math.round(getSizeHeight());
            if (canvasWidth <= MAP_SIDE_PADDING * 2 || canvasHeight <= MAP_TOP_PADDING + MAP_SIDE_PADDING) {
                return;
            }
            guiContext.graphics.fill(x, y, x + canvasWidth, y + canvasHeight, 0x80000000);
            int mapStartX = x + MAP_SIDE_PADDING;
            int mapStartY = y + MAP_TOP_PADDING;
            int mapWidth = canvasWidth - MAP_SIDE_PADDING * 2;
            int mapHeight = canvasHeight - MAP_TOP_PADDING - MAP_SIDE_PADDING;
            guiContext.graphics.fill(mapStartX - 2, mapStartY - 2, mapStartX + mapWidth + 2, mapStartY + mapHeight + 2, 0xFFFFFFFF);
            guiContext.graphics.fill(mapStartX - 1, mapStartY - 1, mapStartX + mapWidth + 1, mapStartY + mapHeight + 1, 0x80000000);
            guiContext.graphics.flush();
            guiContext.enableScissor(mapStartX, mapStartY, mapWidth, mapHeight);
            renderMap(guiContext, mapStartX, mapStartY, mapWidth, mapHeight);
            guiContext.graphics.flush();
            guiContext.disableScissor();
        }

        private void renderMap(GUIContext guiContext, int startX, int startY, int width, int height) {
            double centerX = startX + width / 2.0D;
            double centerY = startY + height / 2.0D;
            double chunkSize = 16.0D * zoomLevel;
            // 视口坐标先转成 chunk 范围，只处理屏幕可见区域。
            int visibleChunksX = (int) Math.ceil(width / chunkSize) + 2;
            int visibleChunksY = (int) Math.ceil(height / chunkSize) + 2;
            int startChunkX = (int) Math.floor((-offsetX - width / 2.0D) / chunkSize);
            int startChunkZ = (int) Math.floor((-offsetY - height / 2.0D) / chunkSize);
            int endChunkX = startChunkX + visibleChunksX;
            int endChunkZ = startChunkZ + visibleChunksY;
            renderWorldMapTerrain(guiContext, startX, startY, width, height, centerX, centerY);
            renderGridOverlay(guiContext, startX, startY, width, height, centerX, centerY, chunkSize, startChunkX, startChunkZ);
            renderHoveredChunk(guiContext, startX, startY, width, height, centerX, centerY, chunkSize);
            renderOwnedChunkBorders(guiContext, startX, startY, width, height, centerX, centerY, chunkSize, startChunkX, endChunkX, startChunkZ, endChunkZ);
            renderBatchClaimPreview(guiContext, startX, startY, width, height, centerX, centerY, chunkSize);
            renderCoreMarker(guiContext, startX, startY, width, height, centerX, centerY, chunkSize);
            renderHoverBox(guiContext, startX, startY, width, height, centerX, centerY, chunkSize);
            renderContextMenu(guiContext, startX, startY, width, height);
        }

        private void renderWorldMapTerrain(GUIContext guiContext, int startX, int startY, int width, int height, double centerX, double centerY) {
            guiContext.graphics.fill(startX, startY, startX + width, startY + height, 0xFF1F1F1F);
            if (!SimuMapManager.isAvailable()) {
                return;
            }
            guiContext.graphics.flush();
            Collection<SimuMapRegion> regions = mapManager.getAllRegions();
            for (SimuMapRegion region : regions) {
                if (!region.isImageLoaded() && !region.hasData()) {
                    continue;
                }
                int textureId = region.getTextureId();
                if (textureId == -1) {
                    continue;
                }
                double regionWorldX = region.regionX * 512.0D;
                double regionWorldZ = region.regionZ * 512.0D;
                double screenX = centerX + offsetX + regionWorldX * zoomLevel;
                double screenY = centerY + offsetY + regionWorldZ * zoomLevel;
                double regionSize = 512.0D * zoomLevel;
                if (screenX + regionSize < startX || screenX > startX + width || screenY + regionSize < startY || screenY > startY + height) {
                    continue;
                }
                drawRegionTexture(guiContext, textureId, screenX, screenY, regionSize);
            }
        }

        private void drawRegionTexture(GUIContext guiContext, int textureId, double screenX, double screenY, double regionSize) {
            RenderSystem.setShaderTexture(0, textureId);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.enableBlend();
            Matrix4f matrix = guiContext.graphics.pose().last().pose();
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            float x0 = (float) screenX;
            float y0 = (float) screenY;
            float x1 = (float) (screenX + regionSize);
            float y1 = (float) (screenY + regionSize);
            buffer.addVertex(matrix, x0, y1, 0).setUv(0, 1);
            buffer.addVertex(matrix, x1, y1, 0).setUv(1, 1);
            buffer.addVertex(matrix, x1, y0, 0).setUv(1, 0);
            buffer.addVertex(matrix, x0, y0, 0).setUv(0, 0);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
            RenderSystem.disableBlend();
        }

        private void forceInitialMapScan() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return;
            }
            int chunkX = minecraft.player.chunkPosition().x;
            int chunkZ = minecraft.player.chunkPosition().z;
            mapManager.forceScanArea(chunkX, chunkZ, Math.min(12, mapManager.getEffectiveScanRadius()));
            mapManager.forceRenderAll();
        }

        private void releaseMapConsumer() {
            if (mapConsumerReleased) {
                return;
            }
            mapConsumerReleased = true;
            mapManager.releaseConsumer();
        }

        private void renderGridOverlay(GUIContext guiContext, int startX, int startY, int width, int height, double centerX, double centerY, double chunkSize, int startChunkX, int startChunkZ) {
            int verticalLines = (int) Math.ceil(width / chunkSize) + 2;
            int horizontalLines = (int) Math.ceil(height / chunkSize) + 2;
            for (int i = 0; i <= verticalLines; i++) {
                int drawX = (int) Math.round(centerX + offsetX + (startChunkX + i) * chunkSize);
                if (drawX >= startX && drawX <= startX + width) {
                    guiContext.graphics.fill(drawX, startY, drawX + 1, startY + height, GRID_COLOR);
                }
            }
            for (int i = 0; i <= horizontalLines; i++) {
                int drawY = (int) Math.round(centerY + offsetY + (startChunkZ + i) * chunkSize);
                if (drawY >= startY && drawY <= startY + height) {
                    guiContext.graphics.fill(startX, drawY, startX + width, drawY + 1, GRID_COLOR);
                }
            }
        }

        private void renderOwnedChunkBorders(GUIContext guiContext, int startX, int startY, int width, int height, double centerX, double centerY, double chunkSize, int startChunkX, int endChunkX, int startChunkZ, int endChunkZ) {
            for (int chunkX = startChunkX; chunkX <= endChunkX; chunkX++) {
                for (int chunkZ = startChunkZ; chunkZ <= endChunkZ; chunkZ++) {
                    long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
                    if (!cache.isChunkOwned(chunkLong)) {
                        continue;
                    }
                    boolean currentCityChunk = cache.isChunkInCurrentCity(chunkLong);
                    int fillColor = currentCityChunk ? CURRENT_CHUNK_FILL_COLOR : OTHER_CHUNK_FILL_COLOR;
                    drawChunkFill(guiContext, startX, startY, width, height, centerX, centerY, chunkSize, chunkX, chunkZ, fillColor);
                    // 只绘制领地外边缘，不画相邻已占领区块之间的内部线。
                    drawChunkOwnershipBorder(guiContext, startX, startY, width, height, centerX, centerY, chunkSize, chunkX, chunkZ, currentCityChunk ? CURRENT_BORDER_COLOR : OTHER_BORDER_COLOR);
                }
            }
        }

        private void drawChunkFill(GUIContext guiContext, int startX, int startY, int width, int height, double centerX, double centerY, double chunkSize, int chunkX, int chunkZ, int fillColor) {
            double screenX = centerX + offsetX + chunkX * chunkSize;
            double screenY = centerY + offsetY + chunkZ * chunkSize;
            int drawX = Math.max((int) Math.floor(screenX), startX);
            int drawY = Math.max((int) Math.floor(screenY), startY);
            int drawWidth = Math.min((int) Math.ceil(screenX + chunkSize), startX + width) - drawX;
            int drawHeight = Math.min((int) Math.ceil(screenY + chunkSize), startY + height) - drawY;
            if (drawWidth > 0 && drawHeight > 0) {
                guiContext.graphics.fill(drawX, drawY, drawX + drawWidth, drawY + drawHeight, fillColor);
            }
        }

        private void drawChunkOwnershipBorder(GUIContext guiContext, int startX, int startY, int width, int height, double centerX, double centerY, double chunkSize, int chunkX, int chunkZ, int borderColor) {
            double screenX = centerX + offsetX + chunkX * chunkSize;
            double screenY = centerY + offsetY + chunkZ * chunkSize;
            int drawX = Math.max((int) Math.floor(screenX), startX);
            int drawY = Math.max((int) Math.floor(screenY), startY);
            int drawWidth = Math.min((int) Math.ceil(screenX + chunkSize), startX + width) - drawX;
            int drawHeight = Math.min((int) Math.ceil(screenY + chunkSize), startY + height) - drawY;
            if (drawWidth <= 0 || drawHeight <= 0) {
                return;
            }
            int borderThickness = Math.max(1, Math.min(2, drawWidth / 4));
            if (!cache.isChunkOwned(ChunkPos.asLong(chunkX, chunkZ - 1))) {
                guiContext.graphics.fill(drawX, drawY, drawX + drawWidth, drawY + borderThickness, borderColor);
            }
            if (!cache.isChunkOwned(ChunkPos.asLong(chunkX, chunkZ + 1))) {
                guiContext.graphics.fill(drawX, drawY + drawHeight - borderThickness, drawX + drawWidth, drawY + drawHeight, borderColor);
            }
            if (!cache.isChunkOwned(ChunkPos.asLong(chunkX - 1, chunkZ))) {
                guiContext.graphics.fill(drawX, drawY, drawX + borderThickness, drawY + drawHeight, borderColor);
            }
            if (!cache.isChunkOwned(ChunkPos.asLong(chunkX + 1, chunkZ))) {
                guiContext.graphics.fill(drawX + drawWidth - borderThickness, drawY, drawX + drawWidth, drawY + drawHeight, borderColor);
            }
        }

        private void renderBatchClaimPreview(GUIContext guiContext, int startX, int startY, int width, int height, double centerX, double centerY, double chunkSize) {
            if (batchClaimChunks.isEmpty()) {
                return;
            }
            for (long chunkLong : batchClaimChunks) {
                ChunkPos chunkPos = new ChunkPos(chunkLong);
                drawChunkFill(guiContext, startX, startY, width, height, centerX, centerY, chunkSize, chunkPos.x, chunkPos.z, BATCH_CLAIM_PREVIEW_COLOR);
            }
        }

        private void renderCoreMarker(GUIContext guiContext, int startX, int startY, int width, int height, double centerX, double centerY, double chunkSize) {
            double markerScreenX = centerX + offsetX + packet.centerChunkX() * chunkSize + (packet.pos().getX() & 15) * zoomLevel;
            double markerScreenY = centerY + offsetY + packet.centerChunkZ() * chunkSize + (packet.pos().getZ() & 15) * zoomLevel;
            if (markerScreenX >= startX && markerScreenX <= startX + width && markerScreenY >= startY && markerScreenY <= startY + height) {
                guiContext.graphics.fill((int) markerScreenX - 3, (int) markerScreenY - 3, (int) markerScreenX + 3, (int) markerScreenY + 3, 0xFF0000FF);
                guiContext.graphics.fill((int) markerScreenX - 2, (int) markerScreenY - 2, (int) markerScreenX + 2, (int) markerScreenY + 2, CORE_MARKER_COLOR);
            }
        }

        private void renderHoveredChunk(GUIContext guiContext, int startX, int startY, int width, int height, double centerX, double centerY, double chunkSize) {
            Minecraft minecraft = Minecraft.getInstance();
            double mouseX = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
            double mouseY = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
            if (!isInside(mouseX, mouseY, startX, startY, width, height)) {
                return;
            }
            int chunkX = screenToChunk(mouseX, centerX, offsetX, chunkSize);
            int chunkZ = screenToChunk(mouseY, centerY, offsetY, chunkSize);
            double screenX = centerX + offsetX + chunkX * chunkSize;
            double screenY = centerY + offsetY + chunkZ * chunkSize;
            int drawX = Math.max((int) Math.floor(screenX), startX);
            int drawY = Math.max((int) Math.floor(screenY), startY);
            int drawWidth = Math.min((int) Math.ceil(screenX + chunkSize), startX + width) - drawX;
            int drawHeight = Math.min((int) Math.ceil(screenY + chunkSize), startY + height) - drawY;
            if (drawWidth > 0 && drawHeight > 0) {
                guiContext.graphics.fill(drawX, drawY, drawX + drawWidth, drawY + drawHeight, 0x40FFFFFF);
            }
        }

        private void renderHoverBox(GUIContext guiContext, int startX, int startY, int width, int height, double centerX, double centerY, double chunkSize) {
            if (contextMenuVisible) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            double mouseX = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
            double mouseY = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
            if (!isInside(mouseX, mouseY, startX, startY, width, height)) {
                return;
            }
            int chunkX = screenToChunk(mouseX, centerX, offsetX, chunkSize);
            int chunkZ = screenToChunk(mouseY, centerY, offsetY, chunkSize);
            long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
            Component ownerText = cache.isChunkInCurrentCity(chunkLong)
                    ? Component.translatable("screen.simukraft.city_core.map.current_city")
                    : cache.isChunkOwned(chunkLong) ? Component.translatable("screen.simukraft.city_core.map.other_city")
                    : Component.translatable("screen.simukraft.city_core.map.unclaimed");
            List<Component> lines = List.of(
                    Component.translatable("screen.simukraft.city_core.map.tooltip.chunk", chunkX, chunkZ),
                    ownerText
            );
            int tooltipWidth = Math.max(minecraft.font.width(lines.get(0)), minecraft.font.width(lines.get(1)));
            int tooltipHeight = lines.size() * 10;
            int tooltipX = (int) mouseX + 10;
            int tooltipY = (int) mouseY - tooltipHeight - 8;
            if (tooltipX + tooltipWidth + 8 > startX + width) {
                tooltipX = (int) mouseX - tooltipWidth - 12;
            }
            if (tooltipY < startY) {
                tooltipY = (int) mouseY + 12;
            }
            tooltipX = Math.max(startX + 4, Math.min(tooltipX, startX + width - tooltipWidth - 8));
            tooltipY = Math.max(startY + 4, Math.min(tooltipY, startY + height - tooltipHeight - 8));
            guiContext.graphics.renderComponentTooltip(minecraft.font, lines, tooltipX, tooltipY);
        }

        private void renderContextMenu(GUIContext guiContext, int startX, int startY, int width, int height) {
            if (!contextMenuVisible) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            Component title = Component.translatable("screen.simukraft.city_core.map.menu.chunk", contextMenuChunkX, contextMenuChunkZ);
            boolean owned = cache.isChunkOwned(ChunkPos.asLong(contextMenuChunkX, contextMenuChunkZ));
            Component action = owned
                    ? Component.translatable("screen.simukraft.city_core.map.menu.claim_unavailable")
                    : packet.canManageCity()
                    ? Component.translatable("screen.simukraft.city_core.map.menu.claim")
                    : Component.translatable("screen.simukraft.city_core.map.menu.no_permission");
            contextMenuWidth = Math.max(minecraft.font.width(title), minecraft.font.width(action)) + 16;
            contextMenuHeight = 38;
            int menuX = (int) contextMenuX;
            int menuY = (int) contextMenuY;
            if (menuX + contextMenuWidth > startX + width) {
                menuX = startX + width - contextMenuWidth;
            }
            if (menuY + contextMenuHeight > startY + height) {
                menuY = startY + height - contextMenuHeight;
            }
            menuX = Math.max(startX + 2, menuX);
            menuY = Math.max(startY + 2, menuY);
            contextMenuDrawX = menuX;
            contextMenuDrawY = menuY;
            guiContext.graphics.fill(menuX, menuY, menuX + contextMenuWidth, menuY + contextMenuHeight, 0xEE202020);
            guiContext.graphics.fill(menuX, menuY, menuX + contextMenuWidth, menuY + 1, 0xFFFFFFFF);
            guiContext.graphics.fill(menuX, menuY + 19, menuX + contextMenuWidth, menuY + 20, 0x80FFFFFF);
            guiContext.graphics.drawString(minecraft.font, title, menuX + 6, menuY + 6, 0xFFFFFFFF, false);
            guiContext.graphics.drawString(minecraft.font, action, menuX + 6, menuY + 24, owned || !packet.canManageCity() ? 0xFFAAAAAA : 0xFFFFFF55, false);
        }

        private boolean handleContextMenuClick(double mouseX, double mouseY) {
            if (!contextMenuVisible) {
                return false;
            }
            if (!isInside(mouseX, mouseY, contextMenuDrawX, contextMenuDrawY, contextMenuWidth, contextMenuHeight)) {
                contextMenuVisible = false;
                return true;
            }
            if (mouseY < contextMenuDrawY + 20) {
                return true;
            }
            contextMenuVisible = false;
            long chunkLong = ChunkPos.asLong(contextMenuChunkX, contextMenuChunkZ);
            if (packet.canManageCity() && !cache.isChunkOwned(chunkLong)) {
                PacketDistributor.sendToServer(new CityChunkPurchasePacket(packet.pos(), contextMenuChunkX, contextMenuChunkZ));
            }
            return true;
        }

        private void collectBatchClaimChunk(double mouseX, double mouseY) {
            if (batchClaimChunks.size() >= MAX_BATCH_CLAIM_CHUNKS || !isMouseInsideMap(mouseX, mouseY)) {
                return;
            }
            int chunkX = screenToChunk(mouseX, mapCenterX(), offsetX, 16.0D * zoomLevel);
            int chunkZ = screenToChunk(mouseY, mapCenterY(), offsetY, 16.0D * zoomLevel);
            long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
            if (!cache.isChunkOwned(chunkLong)) {
                batchClaimChunks.add(chunkLong);
            }
        }

        private void finishBatchClaim() {
            if (!batchClaiming) {
                return;
            }
            batchClaiming = false;
            if (batchClaimChunks.isEmpty()) {
                return;
            }
            List<CityChunkBatchPurchasePacket.ChunkEntry> chunks = batchClaimChunks.stream()
                    .map(ChunkPos::new)
                    .map(chunkPos -> new CityChunkBatchPurchasePacket.ChunkEntry(chunkPos.x, chunkPos.z))
                    .toList();
            batchClaimChunks.clear();
            PacketDistributor.sendToServer(new CityChunkBatchPurchasePacket(packet.pos(), chunks));
        }

        private int screenToChunk(double screenValue, double centerValue, double offsetValue, double chunkSize) {
            return (int) Math.floor((screenValue - centerValue - offsetValue) / chunkSize);
        }

        private boolean isMouseInsideMap(double mouseX, double mouseY) {
            double mapStartX = getPositionX() + MAP_SIDE_PADDING;
            double mapStartY = getPositionY() + MAP_TOP_PADDING;
            double mapWidth = getSizeWidth() - MAP_SIDE_PADDING * 2.0D;
            double mapHeight = getSizeHeight() - MAP_TOP_PADDING - MAP_SIDE_PADDING;
            return isInside(mouseX, mouseY, mapStartX, mapStartY, mapWidth, mapHeight);
        }

        private double mapCenterX() {
            return getPositionX() + MAP_SIDE_PADDING + (getSizeWidth() - MAP_SIDE_PADDING * 2.0D) / 2.0D;
        }

        private double mapCenterY() {
            return getPositionY() + MAP_TOP_PADDING + (getSizeHeight() - MAP_TOP_PADDING - MAP_SIDE_PADDING) / 2.0D;
        }

        private boolean isInside(double mouseX, double mouseY, double x, double y, double width, double height) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }

        private void onMouseDown(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent event) {
            if (contextMenuVisible && event.button == 0 && handleContextMenuClick(event.x, event.y)) {
                event.stopPropagation();
                return;
            }
            if (!isMouseInsideMap(event.x, event.y)) {
                contextMenuVisible = false;
                return;
            }
            if (event.button == 0) {
                event.target.startDrag(new Vector2f((float) offsetX, (float) offsetY), null);
                event.stopPropagation();
                return;
            }
            if (event.button == 2) {
                contextMenuVisible = false;
                batchClaiming = true;
                batchClaimChunks.clear();
                collectBatchClaimChunk(event.x, event.y);
                event.target.startDrag(BATCH_CLAIM_DRAG_MARKER, null);
                event.stopPropagation();
                return;
            }
            if (event.button == 1) {
                contextMenuChunkX = screenToChunk(event.x, mapCenterX(), offsetX, 16.0D * zoomLevel);
                contextMenuChunkZ = screenToChunk(event.y, mapCenterY(), offsetY, 16.0D * zoomLevel);
                contextMenuX = event.x;
                contextMenuY = event.y;
                contextMenuVisible = true;
                event.stopPropagation();
            }
        }

        private void onMouseUp(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent event) {
            if (event.button == 2) {
                finishBatchClaim();
                event.stopPropagation();
            }
        }

        private void onDragEnd(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent event) {
            finishBatchClaim();
        }

        private void onDragUpdate(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent event) {
            contextMenuVisible = false;
            if (BATCH_CLAIM_DRAG_MARKER.equals(event.dragHandler.getDraggingObject())) {
                collectBatchClaimChunk(event.x, event.y);
                event.stopPropagation();
                return;
            }
            if (event.dragHandler.getDraggingObject() instanceof Vector2f startOffset) {
                offsetX = startOffset.x + event.x - event.dragStartX;
                offsetY = startOffset.y + event.y - event.dragStartY;
                event.stopPropagation();
            }
        }

        private void onMouseWheel(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent event) {
            contextMenuVisible = false;
            double oldZoom = zoomLevel;
            if (event.deltaY > 0) {
                zoomLevel = Math.min(zoomLevel + ZOOM_STEP, MAX_ZOOM);
            } else {
                zoomLevel = Math.max(zoomLevel - ZOOM_STEP, MIN_ZOOM);
            }
            if (oldZoom != zoomLevel) {
                double mapStartX = getPositionX() + MAP_SIDE_PADDING;
                double mapStartY = getPositionY() + MAP_TOP_PADDING;
                double mapWidth = getSizeWidth() - MAP_SIDE_PADDING * 2.0D;
                double mapHeight = getSizeHeight() - MAP_TOP_PADDING - MAP_SIDE_PADDING;
                double centerX = mapStartX + mapWidth / 2.0D;
                double centerY = mapStartY + mapHeight / 2.0D;
                double mouseOffsetX = event.x - centerX;
                double mouseOffsetY = event.y - centerY;
                double scaleFactor = zoomLevel / oldZoom;
                offsetX = mouseOffsetX - (mouseOffsetX - offsetX) * scaleFactor;
                offsetY = mouseOffsetY - (mouseOffsetY - offsetY) * scaleFactor;
            }
            event.stopPropagation();
        }

        private void centerMapOnCityCore() {
            double chunkSize = 16.0D * zoomLevel;
            offsetX = -packet.centerChunkX() * chunkSize;
            offsetY = -packet.centerChunkZ() * chunkSize;
        }
    }

    private static final class BrowserTabView extends View {
        private final Runnable removeListener;

        private BrowserTabView(String name, Runnable removeListener) {
            super(name, IGuiTexture.EMPTY);
            this.removeListener = removeListener;
            setCanRemove(true);
        }

        @Override
        public Tab createTab() {
            Tab tab = new Tab();
            tab.setText(Component.translatable(getName()));
            tab.addChild(new Button().setOnClick(event -> {
                if (event.button == 0) {
                    onClose();
                    event.stopPropagation();
                }
            }).noText().buttonStyle(buttonStyle -> buttonStyle.baseTexture(Icons.CLOSE)
                    .hoverTexture(Icons.CLOSE.copy().setColor(ColorPattern.LIGHT_GRAY.color))
                    .pressedTexture(Icons.CLOSE.copy().setColor(ColorPattern.GRAY.color))).layout(layout -> {
                layout.heightPercent(100);
                layout.setAspectRatio(1f);
            }));
            return tab;
        }

        @Override
        protected void onClose() {
            removeListener.run();
            removeSelf();
        }
    }
}
