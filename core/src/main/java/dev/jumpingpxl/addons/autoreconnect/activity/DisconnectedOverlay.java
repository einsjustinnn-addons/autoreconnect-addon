package dev.jumpingpxl.addons.autoreconnect.activity;

import dev.jumpingpxl.addons.autoreconnect.AutoReconnect;
import java.util.concurrent.TimeUnit;
import net.labymod.api.Laby;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.NamedTextColor;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.client.gfx.pipeline.renderer.text.TextRenderer;
import net.labymod.api.client.gui.navigation.NavigationElement;
import net.labymod.api.client.gui.navigation.elements.ScreenNavigationElement;
import net.labymod.api.client.gui.screen.Parent;
import net.labymod.api.client.gui.screen.ScreenInstance;
import net.labymod.api.client.gui.screen.activity.AutoActivity;
import net.labymod.api.client.gui.screen.activity.Link;
import net.labymod.api.client.gui.screen.activity.types.AbstractLayerActivity;
import net.labymod.api.client.gui.screen.key.InputType;
import net.labymod.api.client.gui.screen.key.Key;
import net.labymod.api.client.gui.screen.widget.AbstractWidget;
import net.labymod.api.client.gui.screen.widget.attributes.bounds.BoundsType;
import net.labymod.api.client.gui.screen.widget.widgets.input.ButtonWidget;
import net.labymod.api.client.gui.screen.widget.widgets.layout.FlexibleContentWidget;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.gui.screen.ScreenUpdateVanillaWidgetEvent;
import net.labymod.api.util.I18n;
import net.labymod.api.util.bounds.ModifyReason;
import net.labymod.api.util.bounds.ReasonableMutableRectangle;
import net.labymod.api.util.concurrent.task.Task;

@AutoActivity
@Link("disconnected.lss")
public class DisconnectedOverlay extends AbstractLayerActivity {

  private static final String RECONNECT_PREFIX = "autoreconnect.activity.autoReconnect";
  private static final String RECONNECT_REMAINING = "autoreconnect.activity.remainingSeconds";
  private static final String RECONNECT_TEXT = "autoreconnect.activity.reconnect";
  private static final String WIDTH_VARIABLE_KEY = "--auto-reconnect-width";

  private static final ModifyReason UPDATE_CONTAINER = ModifyReason.of("updateButtonContainer");

  private final AutoReconnect autoReconnect;
  private final boolean enabled;

  private final ButtonWidget reconnectButton;
  private final Task task;
  private FlexibleContentWidget buttonContainer;
  private int remainingSeconds;

  public DisconnectedOverlay(ScreenInstance parentScreen, AutoReconnect autoReconnect) {
    super(parentScreen);
    this.autoReconnect = autoReconnect;
    this.enabled = autoReconnect.configuration().enabled().get() && autoReconnect.canReconnect();
    if (!this.enabled) {
      this.reconnectButton = null;
      this.buttonContainer = null;
      this.task = null;
      return;
    }

    this.remainingSeconds = autoReconnect.configuration().delay().get();

    this.reconnectButton = ButtonWidget.component(this.reconnectComponent(), this::reconnect);
    this.reconnectButton.addId("reconnect-button");

    // Pre-calculate the total width of the text so the button won't jiggle
    this.calculateButtonWidth();

    if (autoReconnect.isAutoReconnect()) {
      this.task = Task.builder(
              () -> autoReconnect.labyAPI().minecraft().executeOnRenderThread(
                  this::updateReconnectComponent
              )
          )
          .repeat(1, TimeUnit.SECONDS)
          .build();

      this.task.execute();
    } else {
      this.task = null;
    }
  }

  @Override
  public void initialize(Parent parent) {
    super.initialize(parent);
    if (!this.enabled) {
      return;
    }

    this.buttonContainer = new FlexibleContentWidget();
    this.buttonContainer.addId("button-container");

    this.buttonContainer.addContent(this.reconnectButton);

    String translation = this.autoReconnect.labyAPI().minecraft().getTranslation("gui.toMenu");
    ButtonWidget backButton = ButtonWidget.text(translation, this::backToServerList);
    backButton.addId("back-button");
    this.buttonContainer.addFlexibleContent(backButton);

    this.document.addChild(this.buttonContainer);
  }

  @Override
  public void onCloseScreen() {
    super.onCloseScreen();
    this.autoReconnect.resetServerData();
    if (this.task != null && this.task.isRunning()) {
      this.task.cancel();
    }
  }

  @Override
  public boolean shouldHandleEscape() {
    return true;
  }

  @Override
  public boolean keyPressed(Key key, InputType type) {
    if (key == Key.ESCAPE) {
      this.backToServerList();
      return true;
    }

    return super.keyPressed(key, type);
  }

  @Subscribe
  public void onScreenUpdateVanillaWidget(ScreenUpdateVanillaWidgetEvent event) {
    if (!this.enabled || this.buttonContainer == null) {
      return;
    }

    AbstractWidget<?> widget = event.getWidget();
    if (!widget.hasId("gui-tomenu-widget") && !widget.hasId("0-widget")) {
      return;
    }

    widget.setVisible(false);
    ReasonableMutableRectangle vanillaBounds = widget.bounds().rectangle(BoundsType.BORDER);
    ReasonableMutableRectangle bounds = this.buttonContainer.bounds().rectangle(BoundsType.OUTER);

    bounds.setPosition(
        vanillaBounds.getCenterX() - bounds.getWidth() / 2,
        vanillaBounds.getY(),
        UPDATE_CONTAINER
    );
  }

  private void calculateButtonWidth() {
    String text;
    if (this.autoReconnect.isAutoReconnect()) {
      String reconnect = I18n.getTranslation(RECONNECT_PREFIX);
      String remaining = I18n.getTranslation(RECONNECT_REMAINING);
      if (reconnect != null) {
        text = reconnect + remaining;
      } else {
        text = null;
      }
    } else {
      text = I18n.getTranslation(RECONNECT_TEXT);
    }

    if (text != null) {
      TextRenderer textRenderer = Laby.references().textRendererProvider().getRenderer();
      float width = textRenderer.getWidth(text);
      this.setVariable(WIDTH_VARIABLE_KEY, width);
    }
  }

  private void backToServerList() {
    if (this.task != null && this.task.isRunning()) {
      this.task.cancel();
    }

    this.autoReconnect.resetServerData();
    NavigationElement<?> navigation = this.labyAPI.navigationService().getById("multiplayer");
    this.labyAPI.minecraft().minecraftWindow().displayScreen(
        ((ScreenNavigationElement) navigation).getScreen()
    );
  }

  private void updateReconnectComponent() {
    if (!this.enabled || this.reconnectButton == null) {
      return;
    }

    if (this.remainingSeconds < 0) {
      this.reconnect();
      return;
    }

    this.reconnectButton.updateComponent(this.reconnectComponent());
    this.remainingSeconds--;
  }

  private Component reconnectComponent() {
    if (!this.autoReconnect.isAutoReconnect()) {
      return Component.translatable(RECONNECT_TEXT);
    }

    TextColor color = NamedTextColor.WHITE;
    if (this.remainingSeconds < 4) {
      color = NamedTextColor.DARK_RED;
    } else if (this.remainingSeconds < 6) {
      color = NamedTextColor.RED;
    } else if (this.remainingSeconds < 11) {
      color = NamedTextColor.YELLOW;
    }

    return Component.translatable()
        .key(RECONNECT_PREFIX)
        .append(Component.translatable(
            RECONNECT_REMAINING,
            color,
            Component.text(this.remainingSeconds)
        ))
        .build();
  }

  private void reconnect() {
    if (this.task != null && this.task.isRunning()) {
      this.task.cancel();
    }

    if (!this.autoReconnect.reconnectToLastServer()) {
      this.backToServerList();
    }
  }
}
