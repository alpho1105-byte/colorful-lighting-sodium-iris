package me.erykczy.colorfullighting.client;

import me.erykczy.colorfullighting.config.LightUserConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;

/** Entry screen for the block/item and entity light editors. */
public final class EntityLightConfigScreen extends Screen {
    private final Screen parent;
    private final LightConfigSession session;
    private Component statusMessage = Component.empty();
    private int statusColor = 0xA0A0A0;

    public EntityLightConfigScreen(Screen parent) {
        super(Component.translatable("colorful_lighting.config.title"));
        this.parent = parent;
        this.session = new LightConfigSession(LightUserConfig.snapshot());
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(250, Math.max(180, width - 48));
        int x = (width - buttonWidth) / 2;
        int firstY = Math.max(58, height / 2 - 42);

        addRenderableWidget(Button.builder(
                Component.translatable("colorful_lighting.config.category.block_item"),
                button -> minecraft.setScreen(LightListScreen.forBlockItems(this, session))
        ).bounds(x, firstY, buttonWidth, 24)
                .tooltip(Tooltip.create(Component.translatable(
                        "colorful_lighting.config.category.block_item.tooltip"
                )))
                .build());
        addRenderableWidget(Button.builder(
                Component.translatable("colorful_lighting.config.category.entity"),
                button -> minecraft.setScreen(LightListScreen.forEntities(this, session))
        ).bounds(x, firstY + 30, buttonWidth, 24)
                .tooltip(Tooltip.create(Component.translatable(
                        "colorful_lighting.config.category.entity.tooltip"
                )))
                .build());

        int gap = 6;
        int exportWidth = 148;
        int doneWidth = 90;
        int total = exportWidth + doneWidth + gap;
        int footerX = (width - total) / 2;
        int footerY = height - 28;
        addRenderableWidget(Button.builder(
                Component.translatable("colorful_lighting.config.export"),
                button -> exportWorkingCopy()
        ).bounds(footerX, footerY, exportWidth, 20)
                .tooltip(Tooltip.create(Component.translatable(
                        "colorful_lighting.config.export.tooltip"
                )))
                .build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> minecraft.setScreen(parent)
        ).bounds(footerX + exportWidth + gap, footerY, doneWidth, 20).build());
    }

    private void exportWorkingCopy() {
        try {
            Path exported = LightUserConfig.exportResourcePack(session.snapshot());
            setStatus(Component.translatable(
                    "colorful_lighting.config.status.exported",
                    exported.getFileName().toString()
            ), 0x80FF80);
        }
        catch (IOException exception) {
            setStatus(Component.translatable(
                    "colorful_lighting.config.status.export_failed",
                    exception.getMessage()
            ), 0xFF6060);
        }
    }

    private void setStatus(Component message, int color) {
        statusMessage = message;
        statusColor = color;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(
                font,
                Component.translatable("colorful_lighting.config.client_only_note"),
                width / 2,
                27,
                0xFFD070
        );
        graphics.drawCenteredString(
                font,
                Component.translatable("colorful_lighting.config.home.help"),
                width / 2,
                Math.max(47, height / 2 - 58),
                0xA0A0A0
        );
        graphics.drawCenteredString(
                font,
                font.plainSubstrByWidth(statusMessage.getString(), Math.max(100, width - 30)),
                width / 2,
                height - 42,
                statusColor
        );
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
