package client.cn.kafei.simukraft.client.hire;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import client.cn.kafei.simukraft.client.buildbox.BuildBoxScreenOpener;
import client.cn.kafei.simukraft.client.commercial.CommercialControlBoxScreenOpener;
import client.cn.kafei.simukraft.client.industrial.IndustrialControlBoxScreenOpener;
import client.cn.kafei.simukraft.client.ui.SimuKraftFlexLayout;
import client.cn.kafei.simukraft.client.citizen.CitizenAvatarFactory;
import client.cn.kafei.simukraft.client.ui.SimuKraftUiTheme;
import common.cn.kafei.simukraft.SimuKraft;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import common.cn.kafei.simukraft.citizen.CitizenLevelService;
import common.cn.kafei.simukraft.citizen.CitizenSkillSnapshot;
import common.cn.kafei.simukraft.commercial.CommercialConstants;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.industrial.IndustrialConstants;
import common.cn.kafei.simukraft.network.npc.hire.NpcHireAssignPacket;
import common.cn.kafei.simukraft.network.npc.hire.NpcHireListRequestPacket;
import common.cn.kafei.simukraft.network.npc.hire.NpcHireListResponsePacket;
import common.cn.kafei.simukraft.ui.RecipeBookSearchUi;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public final class NpcHireScreen {
    private static final int CARD_TEXT_COLOR = SimuKraftUiTheme.CARD_TEXT_COLOR;
    private static final int MAX_NPC_PER_PAGE = 12;
    private static final int CARD_GAP = 10;
    private static final int MIN_BUTTON_WIDTH = 50;
    private static final int MIN_BUTTON_HEIGHT = 20;
    private static final float TITLE_LEFT_RATIO = 0.153F;
    private static final float TITLE_TOP_RATIO = 0.038F;
    private static final float TITLE_WIDTH_RATIO = 0.70F;
    private static final float TITLE_HEIGHT_RATIO = 0.124F;
    private static final float CARD_LEFT_RATIO = 0.068F;
    private static final float CARD_TOP_RATIO = 0.168F;
    private static final float CARD_WIDTH_RATIO = 0.864F;
    private static final float CARD_HEIGHT_RATIO = 0.728F;
    private static final float SELECTED_INFO_HEIGHT_RATIO = 0.04F;
    private static final float PAGER_LEFT_RATIO = 0.19F;
    private static final float PAGER_TOP_RATIO = 0.874F;
    private static final float PAGER_WIDTH_RATIO = 0.61F;
    private static final float PAGER_HEIGHT_RATIO = 0.07F;
    private static final float CONFIRM_LEFT_RATIO = 0.79F;
    private static final float CONFIRM_TOP_RATIO = 0.874F;
    private static final float CONFIRM_WIDTH_RATIO = 0.16F;
    private static final float CONFIRM_HEIGHT_RATIO = 0.07F;
    private static final int HEAD_SIZE = 34;
    private static final int PREFERRED_CARD_WIDTH = 180;
    private static final int MIN_CARD_WIDTH = 160;
    private static final int PREFERRED_CARD_HEIGHT = 80;
    private static final int MIN_CARD_HEIGHT = 76;
    private static UUID selectedNpcId;
    private static int currentPage;
    private static String searchText = "";

    private NpcHireScreen() {
    }

    public static void request(BlockPos sourcePos, String sourceType, String role) {
        SimuKraft.LOGGER.info("Simukraft: Requesting hire list sourceType={} role={} pos={}", sourceType, role, sourcePos);
        currentPage = 0;
        selectedNpcId = null;
        searchText = "";
        PacketDistributor.sendToServer(new NpcHireListRequestPacket(sourcePos, sourceType, role));
    }

    public static void open(NpcHireListResponsePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> {
            try {
                minecraft.setScreen(new com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen(createUi(packet), Component.empty()));
            } catch (Exception exception) {
                SimuKraft.LOGGER.error("Simukraft: Failed to set hire screen for sourceType={} role={}", packet.sourceType(), packet.role(), exception);
            }
        });
    }

    private static ModularUI createUi(NpcHireListResponsePacket packet) {
        try {
            SimuKraftFlexLayout.ScreenSize screenSize = SimuKraftFlexLayout.screenSize();
            int screenWidth = screenSize.width();
            int screenHeight = screenSize.height();
            RegionMetrics regions = resolveRegions(screenWidth, screenHeight);
            GridMetrics grid = resolveGridMetrics(regions.cardRegion().width(), regions.cardRegion().height());

            UIElement root = SimuKraftFlexLayout.root(screenSize);
            SimuKraftFlexLayout.addTopChrome(root, screenSize, Component.translatable("gui.button.back"), () -> returnToSource(packet.sourceType(), packet.sourcePos()));

            UIElement titleRegion = absoluteRegion(regions.titleRegion());
            titleRegion.layout(layout -> {
                layout.flexDirection(FlexDirection.COLUMN);
                layout.justifyContent(AlignContent.CENTER);
                layout.alignItems(AlignItems.CENTER);
                layout.gapAll(Math.max(4, regions.titleRegion().height() / 12));
            });
            titleRegion.addChild(textElement(Component.translatable(titleKey(packet.role())), regions.titleRegion().width(), SimuKraftUiTheme.TEXT_PRIMARY_COLOR, TextTexture.TextType.NORMAL).layout(layout -> {
                layout.widthPercent(100);
                layout.height(18);
            }));
            UIElement statusSlot = new UIElement().layout(layout -> {
                layout.widthPercent(100);
                layout.height(18);
            });
            titleRegion.addChild(statusSlot);
            root.addChild(titleRegion);

            UIElement cardRegion = absoluteRegion(regions.cardRegion());
            cardRegion.layout(layout -> {
                layout.flexDirection(FlexDirection.ROW);
                layout.flexWrap(FlexWrap.WRAP);
                layout.alignContent(AlignContent.CENTER);
                layout.alignItems(AlignItems.CENTER);
                layout.justifyContent(AlignContent.CENTER);
                layout.gapAll(CARD_GAP);
                layout.paddingAll(4);
            });
            root.addChild(cardRegion);

            UIElement infoRegion = absoluteRegion(regions.selectedInfoRegion());
            infoRegion.setAllowHitTest(false);
            infoRegion.layout(layout -> {
                layout.flexDirection(FlexDirection.COLUMN);
                layout.alignItems(AlignItems.CENTER);
                layout.justifyContent(AlignContent.CENTER);
            });
            root.addChild(infoRegion);

            UIElement pagerRegion = absoluteRegion(regions.pagerRegion());
            pagerRegion.layout(layout -> {
                layout.flexDirection(FlexDirection.ROW);
                layout.alignItems(AlignItems.CENTER);
                layout.justifyContent(AlignContent.CENTER);
                layout.gapAll(Math.max(8, regions.pagerRegion().width() / 28));
            });
            root.addChild(pagerRegion);

            UIElement confirmRegion = absoluteRegion(regions.confirmRegion());
            confirmRegion.layout(layout -> {
                layout.flexDirection(FlexDirection.ROW);
                layout.alignItems(AlignItems.CENTER);
                layout.justifyContent(AlignContent.CENTER);
            });
            root.addChild(confirmRegion);

            root.addChild(RecipeBookSearchUi.frameElement(regions.searchRegion().left(), regions.searchRegion().top()));
            HireUiController controller = new HireUiController(packet, regions, grid, statusSlot, cardRegion, infoRegion, pagerRegion, confirmRegion);
            root.addChild(RecipeBookSearchUi.createField(regions.searchRegion().left() + RecipeBookSearchUi.TEXT_OFFSET_X,
                    regions.searchRegion().top() + RecipeBookSearchUi.TEXT_OFFSET_Y, searchText, controller::onSearchChanged));
            controller.refresh();
            return new ModularUI(SimuKraftUiTheme.createUi(root)).shouldCloseOnEsc(true).shouldCloseOnKeyInventory(false);
        } catch (Exception exception) {
            SimuKraft.LOGGER.error("Simukraft: Failed to build hire UI sourceType={} role={} page={} selectedNpcId={}",
                    packet.sourceType(), packet.role(), currentPage, selectedNpcId, exception);
            throw exception;
        }
    }

    private static UIElement candidateCard(NpcHireListResponsePacket packet, NpcHireListResponsePacket.HireCandidate candidate, int width, int height, Runnable refreshAction) {
        try {
            boolean selected = candidate.citizenId().equals(selectedNpcId);
            int buttonInset = 2;
            int buttonWidth = Math.max(1, width - buttonInset * 2);
            int buttonHeight = Math.max(1, height - buttonInset * 2);
            UIElement wrapper = new UIElement().layout(layout -> {
                layout.width(width);
                layout.height(height);
                layout.flexShrink(0);
            });
            wrapper.addChild(SimuKraftUiTheme.createDecorationLayer(3, 4, width - 4, height - 4, "simukraft_card_shadow"));

            Button card = new Button().noText();
            card.addClass("simukraft_large_button");
            card.layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.left(buttonInset);
                layout.top(buttonInset);
                layout.width(buttonWidth);
                layout.height(buttonHeight);
                layout.paddingAll(6);
            });
            card.addClass("simukraft_card_button");
            card.setOnClick(event -> {
                selectedNpcId = candidate.citizenId();
                refreshAction.run();
            });
            card.addChild(SimuKraftUiTheme.createDecorationLayer(6, 7, buttonWidth - 12, buttonHeight - 14, "simukraft_card_content_panel"));
            card.addChild(SimuKraftUiTheme.createDecorationLayer(6, 8, HEAD_SIZE + 4, HEAD_SIZE + 4, "simukraft_card_slot"));

            card.addChild(CitizenAvatarFactory.createHead(candidate.skinPath(), 0xFFFFFFFF).layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.left(8);
                layout.top(10);
                layout.width(HEAD_SIZE);
                layout.height(HEAD_SIZE);
            }));

            int textLeft = HEAD_SIZE + 18;
            int textWidth = buttonWidth - textLeft - 10;

            card.addChild(infoLine(Component.literal(candidate.name()), textWidth, CARD_TEXT_COLOR).layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.left(textLeft);
                layout.top(10);
                layout.width(textWidth);
                layout.height(14);
            }));
            card.addChild(infoLine(Component.translatable("work_status.idle"), textWidth, CARD_TEXT_COLOR).layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.left(textLeft);
                layout.top(28);
                layout.width(textWidth);
                layout.height(14);
            }));
            card.addChild(levelBadge(candidate.skillLevel()).layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.left(textLeft);
                layout.top(42);
                layout.width(34);
                layout.height(12);
            }));

            card.addChild(expBar(candidate, buttonWidth, buttonHeight).layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.left(8);
                layout.top(buttonHeight - 18);
                layout.width(buttonWidth - 16);
                layout.height(10);
            }));
            wrapper.addChild(card);
            if (selected) {
                wrapper.addChild(SimuKraftUiTheme.createSelectionBorder(width, height));
            }

            return wrapper;
        } catch (Exception exception) {
            SimuKraft.LOGGER.error("Simukraft: Failed to build hire candidate card id={} name={} skinPath={} level={}",
                    candidate.citizenId(), candidate.name(), candidate.skinPath(), candidate.skillLevel(), exception);
            throw exception;
        }
    }

    private static UIElement expBar(NpcHireListResponsePacket.HireCandidate candidate, int width, int height) {
        CitizenSkillSnapshot skill = new CitizenSkillSnapshot(CityJobType.fromName(candidate.currentJob()), Math.max(1, candidate.skillLevel()), Math.max(0, candidate.skillXp()), Math.max(1, candidate.skillMaxLevel()));
        boolean isMaxLevel = skill.maxLevelReached();
        float progress = CitizenLevelService.progress(skill);
        int barWidth = width - 16;
        String expText = isMaxLevel ? "MAX" : CitizenLevelService.xpInCurrentLevel(skill) + "/" + CitizenLevelService.xpNeededForCurrentLevel(skill);

        ProgressBar bar = new ProgressBar();
        bar.setRange(0.0F, 1.0F);
        bar.setProgress(progress);
        bar.label.setText(expText);
        return bar.layout(layout -> {
            layout.width(barWidth);
            layout.height(10);
        });
    }


    private static UIElement levelBadge(int level) {
        UIElement badge = new UIElement();
        badge.addClass("simukraft_badge");
        badge.style(style -> style.backgroundTexture(new TextTexture("Lv " + level).setWidth(34).setType(TextTexture.TextType.NORMAL).setColor(CARD_TEXT_COLOR).setDropShadow(false)));
        return badge;
    }

    private static UIElement infoLine(Component text, int width, int color) {
        UIElement element = new UIElement();
        element.style(style -> style.backgroundTexture(new TextTexture(text.getString())
                .setWidth(width)
                .setType(TextTexture.TextType.LEFT)
                .setColor(color)
                .setDropShadow(false)));
        return element;
    }

    private static UIElement textElement(Component text, int width, int color, TextTexture.TextType type) {
        UIElement element = new UIElement();
        element.style(style -> style.backgroundTexture(new TextTexture(text.getString())
                .setWidth(width)
                .setType(type)
                .setColor(color)
                .setDropShadow(true)));
        return element;
    }

    private static RegionMetrics resolveRegions(int screenWidth, int screenHeight) {
        RegionBox titleRegion = relativeBox(screenWidth, screenHeight, TITLE_LEFT_RATIO, TITLE_TOP_RATIO, TITLE_WIDTH_RATIO, TITLE_HEIGHT_RATIO);
        RegionBox pagerRegion = relativeBox(screenWidth, screenHeight, PAGER_LEFT_RATIO, PAGER_TOP_RATIO, PAGER_WIDTH_RATIO, PAGER_HEIGHT_RATIO);
        RegionBox confirmRegion = relativeBox(screenWidth, screenHeight, CONFIRM_LEFT_RATIO, CONFIRM_TOP_RATIO, CONFIRM_WIDTH_RATIO, CONFIRM_HEIGHT_RATIO);
        int searchTop = clamp(Math.max(titleRegion.bottom() + 4, Math.round(screenHeight * 0.155F)), 0,
                Math.max(0, pagerRegion.top() - RecipeBookSearchUi.FRAME_HEIGHT - MIN_CARD_HEIGHT - 12));
        RegionBox searchRegion = new RegionBox(
                clamp((screenWidth - RecipeBookSearchUi.FRAME_WIDTH) / 2, 0, Math.max(0, screenWidth - RecipeBookSearchUi.FRAME_WIDTH)),
                searchTop,
                Math.min(RecipeBookSearchUi.FRAME_WIDTH, screenWidth),
                RecipeBookSearchUi.FRAME_HEIGHT
        );
        RegionBox rawCardRegion = relativeBox(screenWidth, screenHeight, CARD_LEFT_RATIO, CARD_TOP_RATIO, CARD_WIDTH_RATIO, CARD_HEIGHT_RATIO);
        int cardTop = clamp(Math.max(rawCardRegion.top(), searchRegion.bottom() + 6), 0, Math.max(0, pagerRegion.top() - MIN_CARD_HEIGHT - 4));
        int cardBottomLimit = Math.max(rawCardRegion.top() + MIN_CARD_HEIGHT, Math.min(rawCardRegion.bottom(), pagerRegion.top() - 4));
        RegionBox cardRegion = new RegionBox(
                rawCardRegion.left(),
                cardTop,
                rawCardRegion.width(),
                Math.max(MIN_CARD_HEIGHT, cardBottomLimit - cardTop)
        );
        int selectedInfoHeight = Math.max(16, Math.round(screenHeight * SELECTED_INFO_HEIGHT_RATIO));
        RegionBox selectedInfoRegion = new RegionBox(
                cardRegion.left(),
                Math.max(cardRegion.top(), cardRegion.bottom() - selectedInfoHeight),
                cardRegion.width(),
                selectedInfoHeight
        );
        return new RegionMetrics(
                titleRegion,
                searchRegion,
                cardRegion,
                selectedInfoRegion,
                pagerRegion,
                confirmRegion
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static UIElement absoluteRegion(RegionBox region) {
        return SimuKraftFlexLayout.absoluteRegion(region.left(), region.top(), region.width(), region.height());
    }

    private static RegionBox relativeBox(int screenWidth, int screenHeight, float leftRatio, float topRatio, float widthRatio, float heightRatio) {
        int left = clamp(Math.round(screenWidth * leftRatio), 0, screenWidth - 1);
        int top = clamp(Math.round(screenHeight * topRatio), 0, screenHeight - 1);
        int width = clamp(Math.round(screenWidth * widthRatio), 1, screenWidth - left);
        int height = clamp(Math.round(screenHeight * heightRatio), 1, screenHeight - top);
        return new RegionBox(left, top, width, height);
    }

    private static void layoutButtonInRegion(Button button, RegionBox region, float widthRatio, float heightRatio) {
        int width = clamp(Math.round(region.width() * widthRatio), Math.min(MIN_BUTTON_WIDTH, region.width()), region.width());
        int height = clamp(Math.round(region.height() * heightRatio), Math.min(MIN_BUTTON_HEIGHT, region.height()), region.height());
        button.layout(layout -> {
            layout.width(width);
            layout.height(height);
        });
    }

    private static GridMetrics resolveGridMetrics(int regionWidth, int regionHeight) {
        int availableWidth = Math.max(MIN_CARD_WIDTH, regionWidth - 8);
        int columns = Math.max(1, (availableWidth + CARD_GAP) / (MIN_CARD_WIDTH + CARD_GAP));
        int cardWidth = Math.max(MIN_CARD_WIDTH, Math.min(PREFERRED_CARD_WIDTH, (availableWidth - (columns - 1) * CARD_GAP) / columns));
        int availableHeight = Math.max(MIN_CARD_HEIGHT, regionHeight - 8);
        int rows = Math.max(1, (availableHeight + CARD_GAP) / (MIN_CARD_HEIGHT + CARD_GAP));
        int cardHeight = Math.max(MIN_CARD_HEIGHT, Math.min(PREFERRED_CARD_HEIGHT, (availableHeight - (rows - 1) * CARD_GAP) / rows));
        int perPage = Math.max(1, Math.min(MAX_NPC_PER_PAGE, columns * rows));
        return new GridMetrics(columns, rows, perPage, cardWidth, cardHeight);
    }

    private static List<NpcHireListResponsePacket.HireCandidate> pageCandidates(List<NpcHireListResponsePacket.HireCandidate> candidates, int page, int perPage) {
        int start = Math.max(0, page) * perPage;
        int end = Math.min(start + perPage, candidates.size());
        if (start >= end) {
            return new ArrayList<>();
        }
        return new ArrayList<>(candidates.subList(start, end));
    }

    private static String selectedNpcName(List<NpcHireListResponsePacket.HireCandidate> candidates, UUID selectedId) {
        for (NpcHireListResponsePacket.HireCandidate candidate : candidates) {
            if (candidate.citizenId().equals(selectedId)) {
                return candidate.name();
            }
        }
        return "";
    }

    private static void returnToSource(String sourceType, BlockPos sourcePos) {
        if ("build_box".equalsIgnoreCase(sourceType)) {
            BuildBoxScreenOpener.open(sourcePos);
            return;
        }
        if (IndustrialConstants.HIRE_SOURCE_TYPE.equalsIgnoreCase(sourceType)) {
            IndustrialControlBoxScreenOpener.request(sourcePos);
            return;
        }
        if (CommercialConstants.HIRE_SOURCE_TYPE.equalsIgnoreCase(sourceType)) {
            CommercialControlBoxScreenOpener.request(sourcePos);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    private static String titleKey(String role) {
        if ("planner".equalsIgnoreCase(role)) {
            return "gui.hire_planner.title";
        }
        if (IndustrialConstants.HIRE_ROLE.equalsIgnoreCase(role)) {
            return "gui.simukraft.industrial.hire_title";
        }
        if (CommercialConstants.HIRE_ROLE.equalsIgnoreCase(role)) {
            return "gui.simukraft.commercial.hire_title";
        }
        return "gui.hire_builder.title";
    }

    private static int totalPages(List<NpcHireListResponsePacket.HireCandidate> candidates, int perPage) {
        return Math.max(1, (int) Math.ceil(Math.max(1, candidates.size()) / (double) Math.max(1, perPage)));
    }

    private static List<NpcHireListResponsePacket.HireCandidate> filteredCandidates(List<NpcHireListResponsePacket.HireCandidate> candidates) {
        String query = normalizedSearchText();
        if (query.isBlank()) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> matchesSearch(candidate, query))
                .toList();
    }

    private static boolean matchesSearch(NpcHireListResponsePacket.HireCandidate candidate, String query) {
        return containsSearch(candidate.name(), query)
                || containsSearch(candidate.gender(), query)
                || containsSearch(Component.translatable("gui.npc.gender." + candidate.gender()).getString(), query)
                || containsSearch(String.valueOf(candidate.age()), query)
                || containsSearch(String.valueOf((int) Math.ceil(candidate.health())), query)
                || containsSearch(String.valueOf((int) Math.ceil(candidate.hunger())), query)
                || containsSearch(candidate.currentJob(), query)
                || containsSearch(Component.translatable(CityJobType.fromName(candidate.currentJob()).translationKey()).getString(), query)
                || containsSearch(candidate.workStatus(), query)
                || containsSearch(Component.translatable("work_status." + candidate.workStatus()).getString(), query)
                || containsSearch("lv " + candidate.skillLevel(), query)
                || containsSearch(String.valueOf(candidate.skillLevel()), query)
                || containsSearch(candidate.skinPath(), query);
    }

    private static boolean containsSearch(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String normalizedSearchText() {
        return searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
    }

    private static final class HireUiController {
        private final NpcHireListResponsePacket packet;
        private final RegionMetrics regions;
        private final GridMetrics grid;
        private final UIElement statusSlot;
        private final UIElement cardRegion;
        private final UIElement infoRegion;
        private final UIElement pagerRegion;
        private final UIElement confirmRegion;

        private HireUiController(NpcHireListResponsePacket packet, RegionMetrics regions, GridMetrics grid, UIElement statusSlot,
                                 UIElement cardRegion, UIElement infoRegion, UIElement pagerRegion, UIElement confirmRegion) {
            this.packet = packet;
            this.regions = regions;
            this.grid = grid;
            this.statusSlot = statusSlot;
            this.cardRegion = cardRegion;
            this.infoRegion = infoRegion;
            this.pagerRegion = pagerRegion;
            this.confirmRegion = confirmRegion;
        }

        /** onSearchChanged: 搜索变化时只刷新列表区域，避免输入框丢焦点。 */
        private void onSearchChanged(String text) {
            searchText = text == null ? "" : text;
            currentPage = 0;
            selectedNpcId = null;
            refresh();
        }

        /** refresh: 根据当前搜索、分页和选中状态重建可变区域。 */
        private void refresh() {
            List<NpcHireListResponsePacket.HireCandidate> filteredCandidates = filteredCandidates(packet.candidates());
            int pageCount = totalPages(filteredCandidates, grid.perPage());
            currentPage = Math.max(0, Math.min(currentPage, pageCount - 1));
            refreshStatus(filteredCandidates, pageCount);
            refreshCards(filteredCandidates);
            refreshSelectedInfo();
            refreshPager(pageCount);
            refreshConfirm();
        }

        private void refreshStatus(List<NpcHireListResponsePacket.HireCandidate> filteredCandidates, int pageCount) {
            statusSlot.clearAllChildren();
            Component status = packet.candidates().isEmpty()
                    ? Component.translatable("message.simukraft.no_idle_npcs")
                    : Component.translatable("gui.select_npc.title", filteredCandidates.size(), currentPage + 1, pageCount);
            int statusColor = packet.candidates().isEmpty()
                    ? SimuKraftUiTheme.TEXT_ERROR_COLOR
                    : filteredCandidates.isEmpty() ? SimuKraftUiTheme.TEXT_WARNING_COLOR : SimuKraftUiTheme.TEXT_SUCCESS_COLOR;
            statusSlot.addChild(textElement(status, regions.titleRegion().width(), statusColor, TextTexture.TextType.NORMAL).layout(layout -> {
                layout.widthPercent(100);
                layout.height(18);
            }));
        }

        private void refreshCards(List<NpcHireListResponsePacket.HireCandidate> filteredCandidates) {
            cardRegion.clearAllChildren();
            List<NpcHireListResponsePacket.HireCandidate> pageCandidates = pageCandidates(filteredCandidates, currentPage, grid.perPage());
            for (NpcHireListResponsePacket.HireCandidate candidate : pageCandidates) {
                cardRegion.addChild(candidateCard(packet, candidate, grid.cardWidth(), grid.cardHeight(), this::refresh));
            }
        }

        private void refreshSelectedInfo() {
            infoRegion.clearAllChildren();
            if (selectedNpcId == null) {
                return;
            }
            String selectedName = selectedNpcName(packet.candidates(), selectedNpcId);
            infoRegion.addChild(textElement(Component.translatable("gui.select_npc.selected", selectedName),
                    regions.selectedInfoRegion().width(), SimuKraftUiTheme.TEXT_WARNING_COLOR, TextTexture.TextType.NORMAL).layout(layout -> {
                layout.widthPercent(100);
                layout.height(12);
            }));
        }

        private void refreshPager(int pageCount) {
            pagerRegion.clearAllChildren();
            Button prevButton = new Button();
            prevButton.setText(Component.translatable("gui.pagination.previous"));
            layoutButtonInRegion(prevButton, regions.pagerRegion(), 0.25F, 0.82F);
            if (currentPage > 0) {
                prevButton.setOnClick(event -> {
                    currentPage--;
                    refresh();
                });
            } else {
                prevButton.setActive(false);
            }

            UIElement pageLabel = textElement(Component.translatable("gui.pagination.info", currentPage + 1, pageCount),
                    regions.pagerRegion().width() / 3, SimuKraftUiTheme.TEXT_SECONDARY_COLOR, TextTexture.TextType.NORMAL);
            pageLabel.layout(layout -> {
                layout.width(Math.max(80, regions.pagerRegion().width() / 3));
                layout.height(14);
            });

            Button nextButton = new Button();
            nextButton.setText(Component.translatable("gui.pagination.next"));
            layoutButtonInRegion(nextButton, regions.pagerRegion(), 0.25F, 0.82F);
            if (currentPage < pageCount - 1) {
                nextButton.setOnClick(event -> {
                    currentPage++;
                    refresh();
                });
            } else {
                nextButton.setActive(false);
            }
            pagerRegion.addChild(prevButton);
            pagerRegion.addChild(pageLabel);
            pagerRegion.addChild(nextButton);
        }

        private void refreshConfirm() {
            confirmRegion.clearAllChildren();
            Button confirmButton = new Button();
            confirmButton.setText(Component.translatable("gui.button.hire"));
            layoutButtonInRegion(confirmButton, regions.confirmRegion(), 0.88F, 0.82F);
            if (selectedNpcId != null) {
                confirmButton.setOnClick(event -> {
                    PacketDistributor.sendToServer(new NpcHireAssignPacket(packet.sourcePos(), packet.sourceType(), packet.role(), selectedNpcId));
                    returnToSource(packet.sourceType(), packet.sourcePos());
                });
            } else {
                confirmButton.setActive(false);
            }
            confirmRegion.addChild(confirmButton);
        }
    }

    private record GridMetrics(int columns, int rows, int perPage, int cardWidth, int cardHeight) {
    }

    private record RegionMetrics(RegionBox titleRegion,
                                 RegionBox searchRegion,
                                 RegionBox cardRegion,
                                 RegionBox selectedInfoRegion,
                                 RegionBox pagerRegion,
                                 RegionBox confirmRegion) {
    }

    private record RegionBox(int left, int top, int width, int height) {
        private int bottom() {
            return top + height;
        }
    }
}


