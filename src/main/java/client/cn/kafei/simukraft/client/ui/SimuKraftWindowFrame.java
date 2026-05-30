package client.cn.kafei.simukraft.client.ui;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;

@SuppressWarnings("null")
public final class SimuKraftWindowFrame {
    private static final int TITLE_BAR_HEIGHT = 24;
    private static final int CLOSE_BUTTON_SIZE = 18;
    private static final int ROOT_PADDING = 8;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int TITLE_BAR_COLOR = 0xFF303030;

    private SimuKraftWindowFrame() {
    }

    public static UIElement create(SimuKraftFlexLayout.ScreenSize screenSize, Component title, UIElement content, Runnable closeAction) {
        UIElement root = SimuKraftFlexLayout.root(screenSize);
        UIElement window = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.heightPercent(100);
            layout.paddingAll(ROOT_PADDING);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.STRETCH);
        }).style(style -> style.backgroundTexture(new ColorRectTexture(SimuKraftUiTheme.CITY_CORE_BACKGROUND_COLOR)));

        window.addChild(titleBar(screenSize, title, closeAction));
        window.addChild(content.layout(layout -> {
            layout.flex(1);
            layout.widthPercent(100);
            layout.heightPercent(100);
        }));
        root.addChild(window);
        return root;
    }

    private static UIElement titleBar(SimuKraftFlexLayout.ScreenSize screenSize, Component title, Runnable closeAction) {
        UIElement bar = new UIElement().layout(layout -> {
            layout.widthPercent(100);
            layout.height(TITLE_BAR_HEIGHT);
            layout.paddingLeft(8);
            layout.paddingRight(4);
            layout.flexDirection(FlexDirection.ROW);
            layout.alignItems(AlignItems.CENTER);
            layout.justifyContent(AlignContent.SPACE_BETWEEN);
        }).style(style -> style.backgroundTexture(new ColorRectTexture(TITLE_BAR_COLOR)));

        int titleWidth = Math.max(80, screenSize.width() - ROOT_PADDING * 2 - CLOSE_BUTTON_SIZE - 24);
        UIElement titleText = SimuKraftFlexLayout.text(title, titleWidth, TITLE_COLOR, TextTexture.TextType.LEFT, true);
        titleText.layout(layout -> {
            layout.flex(1);
            layout.height(18);
        });
        bar.addChild(titleText);
        bar.addChild(closeButton(closeAction));
        return bar;
    }

    private static Button closeButton(Runnable closeAction) {
        Button button = new Button();
        button.setOnClick(event -> {
                    if (event.button == 0) {
                        closeAction.run();
                        event.stopPropagation();
                    }
                });
        button.noText().buttonStyle(style -> style.baseTexture(Icons.CLOSE)
                .hoverTexture(Icons.CLOSE.copy().setColor(ColorPattern.LIGHT_GRAY.color))
                .pressedTexture(Icons.CLOSE.copy().setColor(ColorPattern.GRAY.color)));
        button.layout(layout -> {
            layout.width(CLOSE_BUTTON_SIZE);
            layout.height(CLOSE_BUTTON_SIZE);
            layout.flexShrink(0);
        });
        return button;
    }
}
