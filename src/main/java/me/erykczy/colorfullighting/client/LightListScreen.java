package me.erykczy.colorfullighting.client;

import me.erykczy.colorfullighting.client.LightFamilyIndex.Family;
import me.erykczy.colorfullighting.client.LightFamilyIndex.Target;
import me.erykczy.colorfullighting.common.EntityLightTextCodec;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.config.LightOverride;
import me.erykczy.colorfullighting.config.LightUserConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Searchable paged editor shared by families, individual members, and entities. */
public final class LightListScreen extends Screen {
    private static final int[] PALETTE = {
            0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA,
            0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA,
            0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21
    };
    private static final String[] PALETTE_NAMES = {
            "white", "orange", "magenta", "light_blue",
            "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue",
            "brown", "green", "red", "black"
    };

    private final Screen parent;
    private final LightConfigSession session;
    private final List<Option> allOptions;
    private final Component categoryTitle;
    private final List<Button> resultButtons = new ArrayList<>();
    private final List<Button> paletteButtons = new ArrayList<>();
    private List<Page> pages = List.of();

    private EditBox searchBox;
    private EditBox hexBox;
    private LightLevelSlider brightnessSlider;
    private Button colorModeButton;
    private Button brightnessModeButton;
    private Button individualButton;
    private Button applyButton;
    private Button disableButton;
    private Button resetButton;
    private Button previousPageButton;
    private Button nextPageButton;

    @Nullable
    private Option selected;
    private FieldMode colorMode = FieldMode.INHERIT;
    private FieldMode brightnessMode = FieldMode.INHERIT;
    private int pageIndex;
    private int resultsPerPage;
    private int leftX;
    private int leftWidth;
    private int rightX;
    private int rightWidth;
    private Component statusMessage = Component.empty();
    private int statusColor = 0xA0A0A0;

    public static LightListScreen forBlockItems(Screen parent, LightConfigSession session) {
        return new LightListScreen(
                parent,
                session,
                Component.translatable("colorful_lighting.config.category.block_item"),
                familyOptions(LightFamilyIndex.blockAndItemFamilies())
        );
    }

    public static LightListScreen forEntities(Screen parent, LightConfigSession session) {
        return new LightListScreen(
                parent,
                session,
                Component.translatable("colorful_lighting.config.category.entity"),
                familyOptions(LightFamilyIndex.entityFamilies())
        );
    }

    private static LightListScreen forMembers(
            Screen parent,
            LightConfigSession session,
            Family family
    ) {
        ArrayList<Option> options = new ArrayList<>();
        for(Target target : family.members()) {
            String typeName = typeName(target).getString();
            options.add(new Option(
                    target.id(),
                    target.localizedName(),
                    target.modName(),
                    List.of(target),
                    target.searchable() + " " + typeName.toLowerCase(Locale.ROOT),
                    (target.id().getNamespace() + " " + target.modName()).toLowerCase(Locale.ROOT),
                    null
            ));
        }
        return new LightListScreen(
                parent,
                session,
                Component.translatable(
                        "colorful_lighting.config.individual.title",
                        family.localizedName()
                ),
                List.copyOf(options)
        );
    }

    private LightListScreen(
            Screen parent,
            LightConfigSession session,
            Component categoryTitle,
            List<Option> options
    ) {
        super(Component.translatable("colorful_lighting.config.editor.title", categoryTitle));
        this.parent = parent;
        this.session = session;
        this.categoryTitle = categoryTitle;
        this.allOptions = options;
    }

    @Override
    protected void init() {
        int margin = 10;
        int gap = 12;
        leftX = margin;
        leftWidth = Math.max(138, (width - margin * 2 - gap) / 2);
        rightX = leftX + leftWidth + gap;
        rightWidth = Math.max(126, width - rightX - margin);
        if(rightX + rightWidth > width - margin) rightWidth = width - margin - rightX;
        resultsPerPage = Math.max(1, (height - 116) / 22);

        searchBox = addRenderableWidget(new EditBox(
                font,
                leftX,
                31,
                leftWidth,
                20,
                Component.translatable("colorful_lighting.config.search")
        ));
        searchBox.setHint(Component.translatable("colorful_lighting.config.search_hint"));
        searchBox.setResponder(query -> {
            pageIndex = 0;
            refreshResults();
        });

        hexBox = addRenderableWidget(new EditBox(
                font,
                rightX,
                74,
                Math.max(64, rightWidth - 24),
                20,
                Component.translatable("colorful_lighting.config.hex")
        ));
        hexBox.setHint(Component.literal("#RRGGBB"));
        hexBox.setMaxLength(7);
        hexBox.setFilter(LightListScreen::isPotentialHex);

        colorModeButton = addRenderableWidget(Button.builder(
                Component.empty(),
                button -> {
                    colorMode = nextMode(colorMode);
                    updateModeControls();
                }
        ).bounds(rightX, 52, rightWidth, 20).build());
        brightnessSlider = addRenderableWidget(new LightLevelSlider(
                rightX,
                96,
                rightWidth,
                20,
                15
        ));
        brightnessModeButton = addRenderableWidget(Button.builder(
                Component.empty(),
                button -> {
                    brightnessMode = nextMode(brightnessMode);
                    updateModeControls();
                }
        ).bounds(rightX, 74, rightWidth, 20).build());

        addPaletteButtons();

        individualButton = addRenderableWidget(Button.builder(
                Component.translatable("colorful_lighting.config.individual"),
                button -> openIndividualSettings()
        ).bounds(rightX + Math.max(0, rightWidth - 72), 30, Math.min(72, rightWidth), 20).build());

        int actionY = height - 50;
        int actionWidth = Math.max(34, (rightWidth - 8) / 3);
        applyButton = addRenderableWidget(Button.builder(
                Component.translatable("colorful_lighting.config.apply_entry"),
                button -> applySelection()
        ).bounds(rightX, actionY, actionWidth, 20).build());
        disableButton = addRenderableWidget(Button.builder(
                Component.translatable("colorful_lighting.config.remove_entry"),
                button -> disableSelection()
        ).bounds(rightX + actionWidth + 4, actionY, actionWidth, 20).build());
        resetButton = addRenderableWidget(Button.builder(
                Component.translatable("colorful_lighting.config.reset_entry"),
                button -> resetSelection()
        ).bounds(
                rightX + (actionWidth + 4) * 2,
                actionY,
                rightWidth - (actionWidth + 4) * 2,
                20
        ).build());

        int pageY = height - 50;
        previousPageButton = addRenderableWidget(Button.builder(
                Component.literal("<"),
                button -> {
                    pageIndex = Math.max(0, pageIndex - 1);
                    refreshResultButtons();
                }
        ).bounds(leftX, pageY, 24, 20).build());
        nextPageButton = addRenderableWidget(Button.builder(
                Component.literal(">"),
                button -> {
                    pageIndex = Math.min(Math.max(0, pages.size() - 1), pageIndex + 1);
                    refreshResultButtons();
                }
        ).bounds(leftX + leftWidth - 24, pageY, 24, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.back"),
                button -> minecraft.setScreen(parent)
        ).bounds(width / 2 - 45, height - 26, 90, 20).build());

        refreshResults();
        if(selected != null) loadSelection();
        else updateEditorControls();
    }

    private void addPaletteButtons() {
        int spacing = 2;
        int paletteWidth = Math.max(12, (rightWidth - spacing * 7) / 8);
        for(int index = 0; index < PALETTE.length; index++) {
            int paletteIndex = index;
            int rgb = PALETTE[index];
            MutableComponent swatch = Component.literal("■").withStyle(
                    Style.EMPTY.withColor(TextColor.fromRgb(rgb))
            );
            Button button = Button.builder(swatch, ignored -> {
                hexBox.setValue(String.format("#%06x", rgb));
                colorMode = FieldMode.OVERRIDE;
                updateModeControls();
            }).bounds(
                    rightX + (index % 8) * (paletteWidth + spacing),
                    108 + (index / 8) * 17,
                    paletteWidth,
                    15
            ).tooltip(Tooltip.create(Component.translatable(
                    "colorful_lighting.config.palette." + PALETTE_NAMES[paletteIndex]
            ))).build();
            paletteButtons.add(addRenderableWidget(button));
        }
    }

    private void refreshResults() {
        String query = searchBox == null ? "" : searchBox.getValue().strip().toLowerCase(Locale.ROOT);
        String[] tokens = query.isEmpty() ? new String[0] : query.split("\\s+");
        List<Option> matching = allOptions.stream()
                .filter(option -> matches(option, tokens))
                .toList();
        List<Option> configured = matching.stream()
                .filter(option -> session.isConfigured(option.targets()))
                .toList();
        List<Option> unconfigured = matching.stream()
                .filter(option -> !session.isConfigured(option.targets()))
                .toList();

        pages = LightEditorPaging.partition(configured, unconfigured, resultsPerPage).stream()
                .map(segment -> new Page(segment.entries(), segment.configured()))
                .toList();
        pageIndex = Mth.clamp(pageIndex, 0, Math.max(0, pages.size() - 1));
        refreshResultButtons();
    }

    private boolean matches(Option option, String[] tokens) {
        for(String token : tokens) {
            if(token.isEmpty()) continue;
            if(token.charAt(0) == '@') {
                if(!option.modSearchable().contains(token.substring(1))) return false;
            }
            else if(!option.searchable().contains(token)) return false;
        }
        return true;
    }

    private void refreshResultButtons() {
        for(Button button : resultButtons) removeWidget(button);
        resultButtons.clear();
        if(pages.isEmpty()) {
            previousPageButton.active = false;
            nextPageButton.active = false;
            return;
        }

        List<Option> options = pages.get(pageIndex).options();
        for(int index = 0; index < options.size(); index++) {
            Option option = options.get(index);
            Button button = Button.builder(
                    resultMessage(option),
                    ignored -> select(option)
            ).bounds(leftX, 55 + index * 22, leftWidth, 20)
                    .tooltip(Tooltip.create(resultTooltip(option)))
                    .build();
            resultButtons.add(addRenderableWidget(button));
        }
        previousPageButton.active = pageIndex > 0;
        nextPageButton.active = pageIndex + 1 < pages.size();
    }

    private Component resultMessage(Option option) {
        LightConfigSession.Aggregate raw = session.aggregate(option.targets());
        LightConfigSession.ColorState color = session.aggregateEffectiveColor(option.targets());
        int rgb = 0xFFFFFF;
        if(raw.disabled() && !raw.disabledMixed()) rgb = 0x707070;
        else if(!session.hasEffectiveLight(option.targets())) rgb = 0x707070;
        else if(color.mixed() || raw.disabledMixed()) rgb = 0xFFD070;
        else if(color.color() != null) rgb = toRgb8(color.color());
        return Component.literal(option.localizedName()).withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(rgb))
        );
    }

    private Component resultTooltip(Option option) {
        MutableComponent tooltip = Component.literal(option.modName());
        for(Target target : option.targets()) {
            tooltip.append("\n").append(typeName(target)).append(": ")
                    .append(Component.literal(target.id().toString()));
        }

        LightConfigSession.Aggregate aggregate = session.aggregate(option.targets());
        tooltip.append("\n").append(Component.translatable(
                "colorful_lighting.config.tooltip.color",
                aggregate.colorMixed()
                        ? Component.translatable("colorful_lighting.config.mixed")
                        : aggregate.color() == null
                                ? Component.translatable("colorful_lighting.config.inherit")
                                : Component.literal(LightUserConfig.encodeColor(aggregate.color()))
        ));
        tooltip.append("\n").append(Component.translatable(
                "colorful_lighting.config.tooltip.brightness",
                aggregate.brightnessMixed()
                        ? Component.translatable("colorful_lighting.config.mixed")
                        : aggregate.brightness() == null
                                ? Component.translatable("colorful_lighting.config.original")
                                : Component.literal(Integer.toString(aggregate.brightness()))
        ));
        LightConfigSession.ColorState effectiveColor = session.aggregateEffectiveColor(option.targets());
        tooltip.append("\n").append(Component.translatable(
                "colorful_lighting.config.tooltip.effective_color",
                effectiveColor.mixed()
                        ? Component.translatable("colorful_lighting.config.mixed")
                        : effectiveColor.color() == null
                                ? Component.translatable("colorful_lighting.config.source.none")
                                : Component.literal(LightUserConfig.encodeColor(effectiveColor.color()))
        ));
        List<Integer> effectiveBrightness = option.targets().stream()
                .map(session::effective)
                .filter(effective -> !effective.disabled())
                .map(LightConfigSession.Effective::brightness)
                .distinct()
                .toList();
        Component brightnessValue = effectiveBrightness.size() > 1
                ? Component.translatable("colorful_lighting.config.mixed")
                : effectiveBrightness.isEmpty() || effectiveBrightness.getFirst() == null
                        ? Component.translatable("colorful_lighting.config.original")
                        : Component.literal(Integer.toString(effectiveBrightness.getFirst()));
        tooltip.append("\n").append(Component.translatable(
                "colorful_lighting.config.tooltip.effective_brightness",
                brightnessValue
        ));
        if(aggregate.disabled() && !aggregate.disabledMixed())
            tooltip.append("\n").append(Component.translatable(
                    "colorful_lighting.config.tooltip.disabled"
            ));
        List<String> sources = option.targets().stream()
                .map(target -> session.effective(target).source())
                .distinct()
                .toList();
        tooltip.append("\n").append(Component.translatable(
                "colorful_lighting.config.tooltip.source",
                sources.size() == 1
                        ? sourceName(sources.getFirst())
                        : Component.translatable("colorful_lighting.config.mixed")
        ));
        if(option.family() != null && option.family().hasIndividualSettings())
            tooltip.append("\n").append(Component.translatable(
                    "colorful_lighting.config.tooltip.family_count",
                    option.targets().size()
            ));
        return tooltip;
    }

    private void select(Option option) {
        selected = option;
        statusMessage = Component.empty();
        loadSelection();
    }

    private void loadSelection() {
        if(selected == null) return;
        LightConfigSession.Aggregate aggregate = session.aggregate(selected.targets());
        colorMode = aggregate.colorMixed()
                ? FieldMode.KEEP
                : aggregate.color() == null ? FieldMode.INHERIT : FieldMode.OVERRIDE;
        brightnessMode = aggregate.brightnessMixed()
                ? FieldMode.KEEP
                : aggregate.brightness() == null ? FieldMode.INHERIT : FieldMode.OVERRIDE;

        ColorRGB4 color = aggregate.color();
        Integer brightness = aggregate.brightness();
        if(color == null || brightness == null) {
            LightConfigSession.Effective effective = session.effective(selected.targets().getFirst());
            if(color == null) color = effective.color();
            if(brightness == null) brightness = effective.brightness();
        }
        if(color == null) color = ConfigColors.WHITE;
        if(brightness == null) brightness = 15;
        hexBox.setValue(LightUserConfig.encodeColor(color));
        brightnessSlider.setLevel(brightness);
        updateEditorControls();
    }

    private void updateEditorControls() {
        boolean hasSelection = selected != null;
        applyButton.active = hasSelection;
        disableButton.active = hasSelection && session.hasEffectiveLight(selected.targets());
        resetButton.active = hasSelection;
        colorModeButton.active = hasSelection;
        brightnessModeButton.active = hasSelection;
        individualButton.visible = hasSelection
                && statusMessage.getString().isEmpty()
                && selected.family() != null
                && selected.family().hasIndividualSettings();
        individualButton.active = individualButton.visible;
        if(individualButton.visible) individualButton.setTooltip(Tooltip.create(memberTooltip(selected)));
        updateModeControls();
    }

    private void updateModeControls() {
        colorModeButton.setMessage(modeMessage("color", colorMode));
        brightnessModeButton.setMessage(brightnessModeMessage());
        boolean showColorEditor = selected != null && colorMode == FieldMode.OVERRIDE;
        boolean showBrightnessEditor = selected != null && brightnessMode == FieldMode.OVERRIDE;
        hexBox.visible = showColorEditor;
        hexBox.active = showColorEditor;
        paletteButtons.forEach(button -> button.visible = showColorEditor);
        brightnessSlider.visible = showBrightnessEditor;
        brightnessSlider.active = showBrightnessEditor;
        layoutEditorControls(showColorEditor);
        brightnessModeButton.setTooltip(Tooltip.create(
                brightnessMode == FieldMode.OVERRIDE
                        ? Component.translatable("colorful_lighting.config.brightness.fixed.warning")
                        : Component.translatable("colorful_lighting.config.brightness.inherit.tooltip")
        ));
    }

    private Component brightnessModeMessage() {
        if(selected == null || brightnessMode != FieldMode.INHERIT)
            return modeMessage("brightness", brightnessMode);
        LightConfigSession.BrightnessState inherited = session.inheritedBrightness(selected.targets());
        Component value = inherited.mixed()
                ? Component.translatable("colorful_lighting.config.mixed")
                : inherited.value() == null
                        ? Component.translatable("colorful_lighting.config.state_dependent")
                        : Component.literal(Integer.toString(inherited.value()));
        return Component.translatable("colorful_lighting.config.mode.brightness.inherit", value);
    }

    private void layoutEditorControls(boolean showColorEditor) {
        colorModeButton.setY(52);
        hexBox.setY(74);

        int paletteTop = 108;
        for(int index = 0; index < paletteButtons.size(); index++)
            paletteButtons.get(index).setY(paletteTop + (index / 8) * 17);

        int brightnessModeY = showColorEditor ? 145 : 74;
        brightnessModeButton.setY(brightnessModeY);
        brightnessSlider.setY(brightnessModeY + 22);
    }

    private Component modeMessage(String field, FieldMode mode) {
        return Component.translatable(
                "colorful_lighting.config.mode." + field,
                Component.translatable(switch(mode) {
                    case KEEP -> "colorful_lighting.config.mixed_keep";
                    case INHERIT -> "colorful_lighting.config.inherit";
                    case OVERRIDE -> "colorful_lighting.config.override";
                })
        );
    }

    private void applySelection() {
        if(selected == null) return;
        ColorRGB4 editorColor = null;
        if(colorMode == FieldMode.OVERRIDE) {
            try {
                editorColor = LightUserConfig.decodeColor(hexBox.getValue());
            }
            catch (IllegalArgumentException exception) {
                setStatus(Component.translatable("colorful_lighting.config.status.invalid_hex"), 0xFF6060);
                return;
            }
        }
        ColorRGB4 resolvedEditorColor = editorColor;
        persistSelectionChange(() -> {
            for(Target target : selected.targets()) {
                LightOverride before = session.get(target);
                ColorRGB4 color = switch(colorMode) {
                    case KEEP -> before.color();
                    case INHERIT -> null;
                    case OVERRIDE -> resolvedEditorColor;
                };
                Integer brightness = switch(brightnessMode) {
                    case KEEP -> before.brightness();
                    case INHERIT -> null;
                    case OVERRIDE -> brightnessSlider.getLevel();
                };
                // Apply never toggles the disabled state: re-applying values to a
                // family must not silently re-enable a member the user disabled
                // individually (re-enabling goes through Reset). A disabled override
                // clears color/brightness in its constructor, keeping this consistent.
                session.set(target, new LightOverride(color, brightness, before.disabled()));
            }
        }, Component.translatable("colorful_lighting.config.status.entry_applied"), 0x80FF80);
    }

    private void disableSelection() {
        if(selected == null) return;
        persistSelectionChange(
                () -> session.disable(selected.targets()),
                Component.translatable("colorful_lighting.config.status.entry_removed"),
                0xFFD070
        );
    }

    private void resetSelection() {
        if(selected == null) return;
        persistSelectionChange(
                () -> session.reset(selected.targets()),
                Component.translatable("colorful_lighting.config.status.entry_reset"),
                0x80C0FF
        );
    }

    private void persistSelectionChange(Runnable change, Component successMessage, int successColor) {
        if(selected == null) return;
        HashMap<Target, LightOverride> previous = new HashMap<>();
        selected.targets().forEach(target -> previous.put(target, session.get(target)));
        change.run();
        try {
            LightUserConfig.saveAndApply(session.snapshot());
            setStatus(successMessage, successColor);
        }
        catch (IOException exception) {
            previous.forEach(session::set);
            setStatus(Component.translatable(
                    "colorful_lighting.config.status.save_failed",
                    exception.getMessage()
            ), 0xFF6060);
        }
        refreshResults();
        focusSelectedPage();
        loadSelection();
    }

    private void focusSelectedPage() {
        if(selected == null) return;
        for(int index = 0; index < pages.size(); index++) {
            if(pages.get(index).options().contains(selected)) {
                pageIndex = index;
                refreshResultButtons();
                return;
            }
        }
    }

    private void openIndividualSettings() {
        if(selected == null || selected.family() == null) return;
        minecraft.setScreen(forMembers(this, session, selected.family()));
    }

    private void setStatus(Component message, int color) {
        statusMessage = message;
        statusColor = color;
        if(individualButton != null) updateEditorControls();
    }

    private Component memberTooltip(Option option) {
        MutableComponent tooltip = Component.translatable(
                "colorful_lighting.config.individual.tooltip"
        );
        for(Target target : option.targets()) {
            tooltip.append("\n").append(typeName(target)).append(": ")
                    .append(Component.literal(target.localizedName()))
                    .append(" (").append(Component.literal(target.id().toString())).append(")");
        }
        return tooltip;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if(mouseX >= leftX && mouseX < leftX + leftWidth && pages.size() > 1) {
            int direction = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
            int next = Mth.clamp(pageIndex + direction, 0, pages.size() - 1);
            if(next != pageIndex) {
                pageIndex = next;
                refreshResultButtons();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, categoryTitle, width / 2, 10, 0xFFFFFF);
        graphics.drawCenteredString(
                font,
                Component.translatable("colorful_lighting.config.client_only_note.short"),
                width / 2,
                20,
                0xFFD070
        );
        graphics.fill(rightX - 6, 29, rightX - 5, height - 29, 0x60808080);

        Component selectedName = selected == null
                ? Component.translatable("colorful_lighting.config.no_selection")
                : Component.literal(selected.localizedName());
        Component headline = statusMessage.getString().isEmpty() ? selectedName : statusMessage;
        int selectedWidth = individualButton.visible ? Math.max(20, rightWidth - 76) : rightWidth;
        graphics.drawString(
                font,
                font.plainSubstrByWidth(headline.getString(), selectedWidth),
                rightX,
                34,
                statusMessage.getString().isEmpty() ? 0xFFFFFF : statusColor
        );

        if(selected != null && colorMode == FieldMode.OVERRIDE) {
            int previewRgb = parseHex(hexBox.getValue());
            int previewX = rightX + rightWidth - 20;
            int previewY = hexBox.getY();
            graphics.fill(previewX, previewY, previewX + 20, previewY + 20, 0xFFFFFFFF);
            graphics.fill(
                    previewX + 1,
                    previewY + 1,
                    previewX + 19,
                    previewY + 19,
                    0xFF000000 | Math.max(0, previewRgb)
            );
            graphics.drawString(
                    font,
                    Component.translatable("colorful_lighting.config.palette"),
                    rightX,
                    paletteButtons.getFirst().getY() - 11,
                    0xA0A0A0
            );
        }
        if(selected != null && brightnessMode == FieldMode.OVERRIDE) {
            int warningY = brightnessSlider.getY() + brightnessSlider.getHeight() + 3;
            if(warningY < height - 61) {
                graphics.drawString(
                        font,
                        font.plainSubstrByWidth(
                                Component.translatable(
                                        "colorful_lighting.config.brightness.fixed.warning.short"
                                ).getString(),
                                rightWidth
                        ),
                        rightX,
                        warningY,
                        0xFFD070
                );
            }
        }

        Component section = pages.isEmpty()
                ? Component.translatable("colorful_lighting.config.section.none")
                : Component.translatable(pages.get(pageIndex).configured()
                        ? "colorful_lighting.config.section.configured"
                        : "colorful_lighting.config.section.unconfigured");
        graphics.drawCenteredString(
                font,
                Component.translatable(
                        "colorful_lighting.config.page",
                        pages.isEmpty() ? 0 : pageIndex + 1,
                        pages.size(),
                        section
                ),
                leftX + leftWidth / 2,
                height - 44,
                0xA0A0A0
        );
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private static List<Option> familyOptions(List<Family> families) {
        return families.stream().map(family -> new Option(
                family.representativeId(),
                family.localizedName(),
                family.modName(),
                family.members(),
                family.searchable(),
                family.modSearchable(),
                family
        )).toList();
    }

    private static Component typeName(Target target) {
        return Component.translatable("colorful_lighting.config.type."
                + target.kind().name().toLowerCase(Locale.ROOT));
    }

    private static Component sourceName(String source) {
        return Component.translatable("colorful_lighting.config.source." + source);
    }

    private static FieldMode nextMode(FieldMode current) {
        return current == FieldMode.OVERRIDE ? FieldMode.INHERIT : FieldMode.OVERRIDE;
    }

    private static boolean isPotentialHex(String value) {
        if(value.length() > 7) return false;
        int start = value.startsWith("#") ? 1 : 0;
        for(int index = start; index < value.length(); index++) {
            if(Character.digit(value.charAt(index), 16) < 0) return false;
        }
        return start == 0 || value.indexOf('#', 1) < 0;
    }

    private static int parseHex(String value) {
        // canonical hex parsing lives in EntityLightTextCodec
        return EntityLightTextCodec.tryParseRgb8(value);
    }

    private static int toRgb8(ColorRGB4 color) {
        ColorRGB8 expanded = ColorRGB8.fromRGB4(color);
        return (expanded.red << 16) | (expanded.green << 8) | expanded.blue;
    }

    private enum FieldMode {
        KEEP,
        INHERIT,
        OVERRIDE
    }

    private record Option(
            ResourceLocation id,
            String localizedName,
            String modName,
            List<Target> targets,
            String searchable,
            String modSearchable,
            @Nullable Family family
    ) {
        private Option {
            targets = List.copyOf(targets);
        }
    }

    private record Page(List<Option> options, boolean configured) {
    }

    private static final class ConfigColors {
        private static final ColorRGB4 WHITE = ColorRGB4.fromRGB4(15, 15, 15);
    }

    private static final class LightLevelSlider extends AbstractSliderButton {
        private LightLevelSlider(int x, int y, int width, int height, int level) {
            super(x, y, width, height, Component.empty(), level / 15.0);
            updateMessage();
        }

        private int getLevel() {
            return Mth.clamp((int) Math.round(value * 15.0), 0, 15);
        }

        private void setLevel(int level) {
            value = Mth.clamp(level, 0, 15) / 15.0;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(
                    "colorful_lighting.config.brightness",
                    getLevel()
            ));
        }

        @Override
        protected void applyValue() {
        }
    }
}
