package common.cn.kafei.simukraft.commercial;

import com.mojang.blaze3d.systems.RenderSystem;
import com.lowdragmc.lowdraglib2.gui.slot.LocalSlot;
import net.minecraft.world.entity.player.Player;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import common.cn.kafei.simukraft.network.commercial.CommercialTradeOpenResponsePacket;
import common.cn.kafei.simukraft.network.commercial.CommercialTradePacket;
import common.cn.kafei.simukraft.ui.RecipeBookSearchUi;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@SuppressWarnings("null")
public final class CommercialTradeUiRoot extends UIElement {
    private static final ResourceLocation VILLAGER_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/container/villager.png");
    private static final ResourceLocation SCROLLER_SPRITE = ResourceLocation.withDefaultNamespace("container/villager/scroller");
    private static final ResourceLocation SCROLLER_DISABLED_SPRITE = ResourceLocation.withDefaultNamespace("container/villager/scroller_disabled");
    private static final ResourceLocation TRADE_ARROW_SPRITE = ResourceLocation.withDefaultNamespace("container/villager/trade_arrow");
    private static final ResourceLocation TRADE_ARROW_OUT_OF_STOCK_SPRITE = ResourceLocation.withDefaultNamespace("container/villager/trade_arrow_out_of_stock");
    private static final ResourceLocation OUT_OF_STOCK_SPRITE = ResourceLocation.withDefaultNamespace("container/villager/out_of_stock");
    private static final ResourceLocation BUTTON_SPRITE = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation BUTTON_HIGHLIGHTED_SPRITE = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
    private static final Component TRADES_LABEL = Component.translatable("merchant.trades");
    private static final Component INVENTORY_LABEL = Component.translatable("container.inventory");
    private static final int IMAGE_WIDTH = 276;
    private static final int IMAGE_HEIGHT = 166;
    private static final int ROOT_WIDTH = IMAGE_WIDTH;
    private static final int TEXTURE_WIDTH = 512;
    private static final int TEXTURE_HEIGHT = 256;
    private static final int ROW_COUNT = 6;
    private static final int ROW_X = 5;
    private static final int ROW_Y = 51;
    private static final int ROW_WIDTH = 89;
    private static final int ROW_HEIGHT = 18;
    private static final int SCROLL_X = 94;
    private static final int SCROLL_Y = ROW_Y;
    private static final int SCROLL_WIDTH = 6;
    private static final int SCROLL_HEIGHT = ROW_COUNT * ROW_HEIGHT;
    private static final int SCROLLER_HEIGHT = 22;
    private static final int SEARCH_FRAME_X = 5;
    private static final int SEARCH_FRAME_Y = 34;
    private static final int SEARCH_TEXT_X = 25;
    private static final int SEARCH_TEXT_Y = 36;
    private static final int COST_A_X = 136;
    private static final int COST_B_X = 162;
    private static final int RESULT_X = 220;
    private static final int TRADE_SLOT_Y = 37;
    private static final int INVENTORY_X = 108;
    private static final int INVENTORY_Y = 84;
    private static final int SLOT_SIZE = 18;
    private static final int TEXT_COLOR = 4210752;
    private static final int MUTED_COLOR = 0xFF555555;
    private static final int ERROR_COLOR = 0xFF8A2020;
    private static final int LEFT_PANEL_BACKGROUND = 0xFF404040;
    private static WeakReference<CommercialTradeUiRoot> activeRoot = new WeakReference<>(null);
    private CommercialTradeOpenResponsePacket packet;
    private final ItemSlot costSlotA = displaySlot(COST_A_X, TRADE_SLOT_Y);
    private final ItemSlot costSlotB = displaySlot(COST_B_X, TRADE_SLOT_Y);
    private final ItemSlot resultSlot = displaySlot(RESULT_X, TRADE_SLOT_Y);
    private final TextField searchField = searchField();
    private CommercialTradeOfferTab activeTab = CommercialTradeOfferTab.SELL;
    private String selectedOfferId = "";
    private String searchText = "";
    private int scrollOff;

    public CommercialTradeUiRoot(CommercialTradeOpenResponsePacket packet) {
        this.packet = packet;
        activeRoot = new WeakReference<>(this);
        layout(layout -> layout.width(ROOT_WIDTH).height(IMAGE_HEIGHT));
        resultSlot.addEventListener(UIEvents.MOUSE_DOWN, this::onResultSlotMouseDown);
        addChild(tabHitbox());
        addChild(offerListHitbox());
        addChild(scrollerHitbox());
        addChild(searchField);
        addChild(costSlotA);
        addChild(costSlotB);
        addChild(resultSlot);
        addChild(playerInventorySlots());
        updateDisplaySlots();
    }

    /** refreshActive: 刷新当前打开的商业交易界面快照。 */
    @OnlyIn(Dist.CLIENT)
    public static void refreshActive(CommercialTradeOpenResponsePacket packet) {
        CommercialTradeUiRoot root = activeRoot.get();
        if (root != null && root.isSameSession(packet)) {
            root.updatePacket(packet);
        }
    }

    /** drawBackgroundAdditional: 绘制原版村民风格交易内容和左侧 Tab。 */
    @OnlyIn(Dist.CLIENT)
    @Override
    public void drawBackgroundAdditional(GUIContext guiContext) {
        updateDisplaySlots();
        int frameLeft = (int) getPositionX();
        int top = (int) getPositionY();
        Font font = guiContext.mc.font;
        guiContext.graphics.blit(VILLAGER_LOCATION, frameLeft, top, 0, 0.0F, 0.0F, IMAGE_WIDTH, IMAGE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        renderLeftPanelBackground(guiContext, frameLeft, top);
        CommercialTradeTabStrip.render(guiContext, font, frameLeft, top, activeTab);
        renderSearchBox(guiContext, frameLeft, top);
        renderLabels(guiContext, font, frameLeft, top);
        renderSelectedOffer(guiContext, font, frameLeft, top);
        renderOfferList(guiContext, font, frameLeft, top);
        renderScroller(guiContext, frameLeft, top);
        renderHoveredTooltip(guiContext, font, frameLeft, top);
    }

    /** onTabMouseDown: 只在左侧 Tab 命中区切换交易分类。 */
    @OnlyIn(Dist.CLIENT)
    private void renderLeftPanelBackground(GUIContext guiContext, int left, int top) {
        guiContext.graphics.fill(left + ROW_X, top + CommercialTradeTabStrip.HIT_Y,
                left + CommercialTradeTabStrip.HIT_X + CommercialTradeTabStrip.HIT_WIDTH, top + ROW_Y, LEFT_PANEL_BACKGROUND);
    }

    private void onTabMouseDown(UIEvent event) {
        if (event.button != 0) {
            release(event);
            return;
        }
        CommercialTradeOfferTab tab = CommercialTradeTabStrip.hit(event.x, event.y, (int) getPositionX(), (int) getPositionY());
        if (tab == null) {
            release(event);
            return;
        }
        activeTab = tab;
        selectedOfferId = "";
        scrollOff = 0;
        updateDisplaySlots();
        consume(event);
    }

    /** onOfferListMouseDown: 只处理报价列表行点击，避免抢占玩家背包槽位。 */
    private void onOfferListMouseDown(UIEvent event) {
        if (event.button != 0) {
            release(event);
            return;
        }
        int rowIndex = hoveredRow(event.x, event.y, (int) getPositionX(), (int) getPositionY());
        List<CommercialTradeOpenResponsePacket.OfferEntry> offers = filteredOffers();
        if (rowIndex < 0 || rowIndex >= offers.size()) {
            release(event);
            return;
        }
        selectedOfferId = offers.get(rowIndex).id();
        clampScrollToSelection();
        updateDisplaySlots();
        consume(event);
    }

    /** onOfferListMouseWheel: 滚动当前 Tab 的报价列表。 */
    private void onOfferListMouseWheel(UIEvent event) {
        if (!canScroll()) {
            release(event);
            return;
        }
        scrollOff = Mth.clamp((int) (scrollOff - event.deltaY), 0, maxScroll());
        consume(event);
    }

    /** onScrollerMouseDown: 点击或拖拽滚动条时定位报价列表。 */
    private void onScrollerMouseDown(UIEvent event) {
        if (event.button != 0 || !canScroll()) {
            release(event);
            return;
        }
        setScrollFromMouse(event.y, (int) getPositionY());
        event.target.startDrag(null, null);
        consume(event);
    }

    /** onScrollerDragUpdate: 拖动滚动条时同步列表偏移。 */
    private void onScrollerDragUpdate(UIEvent event) {
        if (!canScroll()) {
            release(event);
            return;
        }
        setScrollFromMouse(event.y, (int) getPositionY());
        consume(event);
    }

    /** onResultSlotMouseDown: 点击结果槽时执行一次当前报价。 */
    private void onResultSlotMouseDown(UIEvent event) {
        CommercialTradeOpenResponsePacket.OfferEntry offer = selectedOffer();
        if (event.button != 0 || offer == null || !canTrade(offer)) {
            return;
        }
        boolean isRetail = !offer.result().isEmpty() && offer.result().get(0).count() == 1;
        int count = (Screen.hasShiftDown() && isRetail) ? 64 : 1;
        tradeSelected(true, count);
        event.stopImmediatePropagation();
    }

    @OnlyIn(Dist.CLIENT)
    private void renderLabels(GUIContext guiContext, Font font, int left, int top) {
        drawCenteredStringNoShadow(guiContext, font, CommercialTradeMenuProvider.title(packet), left + 187, top + 6, TEXT_COLOR);
        int tradesWidth = font.width(TRADES_LABEL);
        guiContext.graphics.drawString(font, TRADES_LABEL, left + 5 - tradesWidth / 2 + 48, top + 6, TEXT_COLOR, false);
        guiContext.graphics.drawString(font, INVENTORY_LABEL, left + 107, top + 72, TEXT_COLOR, false);
        String balance = Component.translatable("gui.simukraft.commercial.balance", CommercialTradeUiSupport.money(packet.cityBalance())).getString();
        guiContext.graphics.drawString(font, fitText(font, balance, 116), left + 136, top + 18, MUTED_COLOR, false);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderSearchBox(GUIContext guiContext, int left, int top) {
        RecipeBookSearchUi.renderFrame(guiContext, left + SEARCH_FRAME_X, top + SEARCH_FRAME_Y,
                RecipeBookSearchUi.FRAME_WIDTH, RecipeBookSearchUi.FRAME_TEXTURE_WIDTH, RecipeBookSearchUi.FRAME_HEIGHT,
                RecipeBookSearchUi.TEXT_OFFSET_X, RecipeBookSearchUi.TEXT_OFFSET_Y,
                RecipeBookSearchUi.TEXT_WIDTH, RecipeBookSearchUi.TEXT_HEIGHT);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderSelectedOffer(GUIContext guiContext, Font font, int left, int top) {
        CommercialTradeOpenResponsePacket.OfferEntry offer = selectedOffer();
        if (offer == null) {
            return;
        }
        boolean canTrade = canTrade(offer);
        if (!canTrade) {
            guiContext.graphics.blitSprite(OUT_OF_STOCK_SPRITE, left + 182, top + 35, 28, 21);
        }
        String costKey = CommercialTradeUiSupport.costEnough(packet, offer, getModularUI() != null ? getModularUI().player : null, 1)
                ? "gui.simukraft.commercial.cost_ok"
                : "gui.simukraft.commercial.cost_missing";
        guiContext.graphics.drawString(font, fitText(font, Component.translatable(costKey).getString(), 96), left + 136, top + 60, canTrade ? 0xFF2A602A : ERROR_COLOR, false);
        guiContext.graphics.drawString(font, fitText(font, Component.translatable("gui.simukraft.commercial.stock", CommercialTradeUiSupport.stockText(offer)).getString(), 96),
                left + 136, top + 70, CommercialTradeUiSupport.stockColor(offer, 1), false);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderOfferList(GUIContext guiContext, Font font, int left, int top) {
        List<CommercialTradeOpenResponsePacket.OfferEntry> offers = filteredOffers();
        if (offers.isEmpty()) {
            guiContext.graphics.drawCenteredString(font, Component.translatable("gui.simukraft.commercial.no_offers"), left + 49, top + 82, ERROR_COLOR);
            return;
        }
        for (int row = 0; row < ROW_COUNT; row++) {
            int offerIndex = scrollOff + row;
            if (offerIndex >= offers.size()) {
                break;
            }
            renderOfferRow(guiContext, left, top, row, offers.get(offerIndex));
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void renderOfferRow(GUIContext guiContext, int left, int top, int row, CommercialTradeOpenResponsePacket.OfferEntry offer) {
        int rowLeft = left + ROW_X;
        int rowTop = top + ROW_Y + row * ROW_HEIGHT;
        boolean selected = offer.id().equals(selectedOfferId);
        boolean canTrade = canTrade(offer);
        boolean hovered = inside(guiContext.mouseX, guiContext.mouseY, rowLeft, rowTop, ROW_WIDTH, ROW_HEIGHT);
        RenderSystem.enableBlend();
        guiContext.graphics.blitSprite(selected || hovered ? BUTTON_HIGHLIGHTED_SPRITE : BUTTON_SPRITE, rowLeft, rowTop, ROW_WIDTH, ROW_HEIGHT);
        int itemY = rowTop + 1;
        renderResource(guiContext, first(offer.cost(), 0), left + 10, itemY);
        renderResource(guiContext, first(offer.cost(), 1), left + 40, itemY);
        renderTradeArrow(guiContext, canTrade, left + 60, itemY + 3);
        renderResource(guiContext, first(offer.result(), 0), left + 73, itemY);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderTradeArrow(GUIContext guiContext, boolean canTrade, int x, int y) {
        RenderSystem.enableBlend();
        guiContext.graphics.blitSprite(canTrade ? TRADE_ARROW_SPRITE : TRADE_ARROW_OUT_OF_STOCK_SPRITE, x, y, 0, 10, 9);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderScroller(GUIContext guiContext, int left, int top) {
        if (!canScroll()) {
            guiContext.graphics.blitSprite(SCROLLER_DISABLED_SPRITE, left + SCROLL_X, top + SCROLL_Y, SCROLL_WIDTH, SCROLLER_HEIGHT);
            return;
        }
        int steps = filteredOffers().size() + 1 - ROW_COUNT;
        int hidden = SCROLL_HEIGHT - (SCROLLER_HEIGHT + (steps - 1) * SCROLL_HEIGHT / steps);
        int stepHeight = 1 + hidden / steps + SCROLL_HEIGHT / steps;
        int scrollY = Math.min(SCROLL_HEIGHT - SCROLLER_HEIGHT, scrollOff * stepHeight);
        if (scrollOff == maxScroll()) {
            scrollY = SCROLL_HEIGHT - SCROLLER_HEIGHT;
        }
        guiContext.graphics.blitSprite(SCROLLER_SPRITE, left + SCROLL_X, top + SCROLL_Y + scrollY, SCROLL_WIDTH, SCROLLER_HEIGHT);
    }

    @OnlyIn(Dist.CLIENT)
    private void renderResource(GUIContext guiContext, @Nullable CommercialTradeOpenResponsePacket.ResourceEntry resource, int x, int y) {
        if (resource == null) {
            return;
        }
        ItemStack stack = CommercialTradeUiSupport.resourceStack(resource);
        guiContext.graphics.renderFakeItem(stack, x, y);
        if (CommercialTradeUiSupport.isMoney(resource)) {
            drawResourceCount(guiContext, CommercialTradeUiSupport.moneyShort(resource.money()), x, y);
        } else {
            guiContext.graphics.renderItemDecorations(guiContext.mc.font, stack, x, y);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void renderHoveredTooltip(GUIContext guiContext, Font font, int left, int top) {
        CommercialTradeOpenResponsePacket.ResourceEntry resource = hoveredResource(guiContext.mouseX, guiContext.mouseY, left, top);
        if (resource == null || getModularUI() == null) {
            return;
        }
        ItemStack stack = CommercialTradeUiSupport.resourceStack(resource);
        if (CommercialTradeUiSupport.isMoney(resource)) {
            getModularUI().setHoverTooltip(List.of(Component.translatable("gui.simukraft.commercial.money_resource", CommercialTradeUiSupport.money(resource.money()))), stack, font, null);
        } else {
            getModularUI().setHoverTooltip(List.of(stack.getHoverName(), Component.literal("x" + Math.max(1, resource.count()))), stack, font, null);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void drawResourceCount(GUIContext guiContext, String label, int x, int y) {
        if (label.isBlank()) {
            return;
        }
        Font font = guiContext.mc.font;
        guiContext.pose.pushPose();
        guiContext.pose.translate(0.0F, 0.0F, 200.0F);
        guiContext.graphics.drawString(font, label, x + 19 - 2 - font.width(label), y + 9, 0xFFFFFF, true);
        guiContext.pose.popPose();
    }

    private void tradeSelected(boolean quickMove, int count) {
        CommercialTradeOpenResponsePacket.OfferEntry offer = selectedOffer();
        if (offer != null && canTrade(offer) && packet.workerId() != null) {
            PacketDistributor.sendToServer(new CommercialTradePacket(packet.boxPos(), packet.workerId(), offer.id(), count, quickMove));
        }
    }

    private boolean canTrade(CommercialTradeOpenResponsePacket.OfferEntry offer) {
        return packet.running()
                && packet.workerId() != null
                && CommercialTradeUiSupport.costEnough(packet, offer, getModularUI() != null ? getModularUI().player : null, 1)
                && CommercialTradeUiSupport.stockEnough(offer, 1);
    }

    private void updateDisplaySlots() {
        CommercialTradeOpenResponsePacket.OfferEntry offer = selectedOffer();
        setSlotResource(costSlotA, offer == null ? null : first(offer.cost(), 0));
        setSlotResource(costSlotB, offer == null ? null : first(offer.cost(), 1));
        setSlotResource(resultSlot, offer == null ? null : first(offer.result(), 0));
    }

    private void setSlotResource(ItemSlot slot, @Nullable CommercialTradeOpenResponsePacket.ResourceEntry resource) {
        slot.setItem(resource == null ? ItemStack.EMPTY : CommercialTradeUiSupport.resourceStack(resource), false);
    }

    private List<CommercialTradeOpenResponsePacket.OfferEntry> filteredOffers() {
        return packet.offers().stream()
                .filter(activeTab::matches)
                .filter(this::matchesSearch)
                .toList();
    }

    @Nullable
    private CommercialTradeOpenResponsePacket.OfferEntry selectedOffer() {
        List<CommercialTradeOpenResponsePacket.OfferEntry> offers = filteredOffers();
        for (CommercialTradeOpenResponsePacket.OfferEntry offer : offers) {
            if (offer.id().equals(selectedOfferId)) {
                return offer;
            }
        }
        return null;
    }

    private boolean isSameSession(CommercialTradeOpenResponsePacket next) {
        return next != null
                && Objects.equals(packet.boxPos(), next.boxPos())
                && Objects.equals(packet.workerId(), next.workerId());
    }

    private void updatePacket(CommercialTradeOpenResponsePacket next) {
        this.packet = next;
        if (selectedOffer() == null) {
            selectedOfferId = "";
        }
        scrollOff = Mth.clamp(scrollOff, 0, maxScroll());
        updateDisplaySlots();
    }

    private void onSearchChanged(String text) {
        searchText = text != null ? text.trim().toLowerCase(Locale.ROOT) : "";
        selectedOfferId = "";
        scrollOff = 0;
        updateDisplaySlots();
    }

    private boolean matchesSearch(CommercialTradeOpenResponsePacket.OfferEntry offer) {
        if (searchText.isBlank()) {
            return true;
        }
        if (containsSearch(offer.id()) || containsSearch(offer.stockItem())) {
            return true;
        }
        return offer.cost().stream().anyMatch(this::matchesSearch)
                || offer.result().stream().anyMatch(this::matchesSearch);
    }

    private boolean matchesSearch(CommercialTradeOpenResponsePacket.ResourceEntry resource) {
        if (resource == null) {
            return false;
        }
        if (CommercialTradeUiSupport.isMoney(resource)) {
            return containsSearch(Component.translatable("gui.simukraft.commercial.money_resource", CommercialTradeUiSupport.money(resource.money())).getString())
                    || containsSearch(CommercialTradeUiSupport.money(resource.money()));
        }
        ItemStack stack = CommercialTradeUiSupport.resourceStack(resource);
        return containsSearch(resource.itemId()) || containsSearch(stack.getHoverName().getString());
    }

    private boolean containsSearch(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(searchText);
    }

    private void clampScrollToSelection() {
        int selectedIndex = selectedOfferIndex();
        if (selectedIndex < 0) {
            scrollOff = Mth.clamp(scrollOff, 0, maxScroll());
            return;
        }
        if (selectedIndex < scrollOff) {
            scrollOff = selectedIndex;
        } else if (selectedIndex >= scrollOff + ROW_COUNT) {
            scrollOff = selectedIndex - ROW_COUNT + 1;
        }
        scrollOff = Mth.clamp(scrollOff, 0, maxScroll());
    }

    private int selectedOfferIndex() {
        List<CommercialTradeOpenResponsePacket.OfferEntry> offers = filteredOffers();
        for (int i = 0; i < offers.size(); i++) {
            if (offers.get(i).id().equals(selectedOfferId)) {
                return i;
            }
        }
        return -1;
    }

    private int hoveredRow(float mouseX, float mouseY, int left, int top) {
        int row = hoveredVisibleRow(mouseX, mouseY, left, top);
        int index = row < 0 ? -1 : scrollOff + row;
        return index >= 0 && index < filteredOffers().size() ? index : -1;
    }

    private int hoveredVisibleRow(float mouseX, float mouseY, int left, int top) {
        if (!inside(mouseX, mouseY, left + ROW_X, top + ROW_Y, ROW_WIDTH, ROW_HEIGHT * ROW_COUNT)) {
            return -1;
        }
        int row = ((int) mouseY - (top + ROW_Y)) / ROW_HEIGHT;
        return row >= 0 && row < ROW_COUNT && scrollOff + row < filteredOffers().size() ? row : -1;
    }

    @Nullable
    private CommercialTradeOpenResponsePacket.ResourceEntry hoveredResource(float mouseX, float mouseY, int left, int top) {
        CommercialTradeOpenResponsePacket.OfferEntry selected = selectedOffer();
        if (selected != null) {
            CommercialTradeOpenResponsePacket.ResourceEntry hovered = hoveredInOffer(selected, mouseX, mouseY, left, top, true, 0);
            if (hovered != null) {
                return hovered;
            }
        }
        int row = hoveredVisibleRow(mouseX, mouseY, left, top);
        if (row < 0) {
            return null;
        }
        List<CommercialTradeOpenResponsePacket.OfferEntry> offers = filteredOffers();
        if (scrollOff + row >= offers.size()) {
            return null;
        }
        return hoveredInOffer(offers.get(scrollOff + row), mouseX, mouseY, left, top, false, row);
    }

    @Nullable
    private CommercialTradeOpenResponsePacket.ResourceEntry hoveredInOffer(CommercialTradeOpenResponsePacket.OfferEntry offer, float mouseX, float mouseY, int left, int top, boolean detail, int row) {
        int y = detail ? top + TRADE_SLOT_Y : top + ROW_Y + row * ROW_HEIGHT;
        int costAX = detail ? left + COST_A_X : left + 10;
        int costBX = detail ? left + COST_B_X : left + 40;
        int resultX = detail ? left + RESULT_X : left + 73;
        if (inside(mouseX, mouseY, costAX, y, SLOT_SIZE, SLOT_SIZE)) {
            return first(offer.cost(), 0);
        }
        if (inside(mouseX, mouseY, costBX, y, SLOT_SIZE, SLOT_SIZE)) {
            return first(offer.cost(), 1);
        }
        if (inside(mouseX, mouseY, resultX, y, SLOT_SIZE, SLOT_SIZE)) {
            return first(offer.result(), 0);
        }
        return null;
    }

    private void setScrollFromMouse(float mouseY, int top) {
        int max = maxScroll();
        float factor = (mouseY - (top + SCROLL_Y) - SCROLLER_HEIGHT / 2.0F) / (SCROLL_HEIGHT - SCROLLER_HEIGHT);
        scrollOff = Mth.clamp((int) (factor * max + 0.5F), 0, max);
    }

    private boolean canScroll() {
        return filteredOffers().size() > ROW_COUNT;
    }

    private int maxScroll() {
        return Math.max(0, filteredOffers().size() - ROW_COUNT);
    }

    private UIElement tabHitbox() {
        UIElement hitbox = interactionHitbox(CommercialTradeTabStrip.HIT_X, CommercialTradeTabStrip.HIT_Y, CommercialTradeTabStrip.HIT_WIDTH, CommercialTradeTabStrip.HIT_HEIGHT);
        hitbox.addEventListener(UIEvents.MOUSE_DOWN, this::onTabMouseDown);
        return hitbox;
    }

    private UIElement offerListHitbox() {
        UIElement hitbox = interactionHitbox(ROW_X, ROW_Y, ROW_WIDTH, ROW_HEIGHT * ROW_COUNT);
        hitbox.addEventListener(UIEvents.MOUSE_DOWN, this::onOfferListMouseDown);
        hitbox.addEventListener(UIEvents.MOUSE_WHEEL, this::onOfferListMouseWheel);
        return hitbox;
    }

    private UIElement scrollerHitbox() {
        UIElement hitbox = interactionHitbox(SCROLL_X, SCROLL_Y, SCROLL_WIDTH, SCROLL_HEIGHT + 1);
        hitbox.addEventListener(UIEvents.MOUSE_DOWN, this::onScrollerMouseDown);
        hitbox.addEventListener(UIEvents.MOUSE_WHEEL, this::onOfferListMouseWheel);
        hitbox.addEventListener(UIEvents.DRAG_UPDATE, this::onScrollerDragUpdate);
        return hitbox;
    }

    private static UIElement interactionHitbox(int x, int y, int width, int height) {
        UIElement hitbox = new UIElement();
        hitbox.layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE).left(x).top(y).width(width).height(height));
        hitbox.style(style -> style.backgroundTexture(IGuiTexture.EMPTY).zIndex(20));
        return hitbox;
    }

    private static void consume(UIEvent event) {
        event.stopPropagation();
    }

    @OnlyIn(Dist.CLIENT)
    private static void drawCenteredStringNoShadow(GUIContext guiContext, Font font, Component text, int centerX, int y, int color) {
        guiContext.graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private static void release(UIEvent event) {
        event.hasHandler = false;
    }

    private static ItemSlot displaySlot(int x, int y) {
        ItemSlot slot = new ItemSlot(new LocalSlot() {
            @Override
            public boolean mayPickup(Player player) {
                return false;
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
        slot.layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE).left(x - 1).top(y - 1).width(SLOT_SIZE).height(SLOT_SIZE));
        slot.style(style -> style.backgroundTexture(IGuiTexture.EMPTY));
        slot.slotStyle(style -> style.slotOverlay(IGuiTexture.EMPTY).showItemTooltips(true));
        return slot;
    }

    private static InventorySlots playerInventorySlots() {
        InventorySlots slots = new InventorySlots();
        slots.layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE).left(INVENTORY_X - 1).top(INVENTORY_Y - 1).width(162).height(76));
        slots.apply(slot -> slot.style(style -> style.backgroundTexture(IGuiTexture.EMPTY)));
        return slots;
    }

    private TextField searchField() {
        return RecipeBookSearchUi.createField(SEARCH_TEXT_X, SEARCH_TEXT_Y, "", this::onSearchChanged);
    }

    @OnlyIn(Dist.CLIENT)
    private static String fitText(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width(ellipsis))) + ellipsis;
    }

    @Nullable
    private static CommercialTradeOpenResponsePacket.ResourceEntry first(List<CommercialTradeOpenResponsePacket.ResourceEntry> resources, int index) {
        return index >= 0 && index < resources.size() ? resources.get(index) : null;
    }

    private static boolean inside(float mouseX, float mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
