package com.example.donutpay;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.math.BigDecimal;

public class SettingsScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget minField, maxField, multField;
    private ButtonWidget toggleButton;
    private boolean enabled;
    private Text status = Text.empty();

    public SettingsScreen(Screen parent) {
        super(Text.literal("DonutPay Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Config c = Config.get();
        enabled = c.enabled;

        int w = 200;
        int x = width / 2 - w / 2;
        int y = height / 2 - 85;

        toggleButton = addDrawableChild(ButtonWidget.builder(toggleText(), b -> {
            enabled = !enabled;
            b.setMessage(toggleText());
        }).dimensions(x, y, w, 20).build());

        minField = field(x, y + 42, w, Long.toString(c.minAmount));
        maxField = field(x, y + 80, w, Long.toString(c.maxAmount));
        multField = field(x, y + 118, w, BigDecimal.valueOf(c.winMultiplier).stripTrailingZeros().toPlainString());

        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> save())
                .dimensions(x, y + 148, 98, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
                .dimensions(x + 102, y + 148, 98, 20).build());
    }

    private TextFieldWidget field(int x, int y, int w, String value) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 20, Text.empty());
        f.setMaxLength(24);
        f.setText(value);
        return addDrawableChild(f);
    }

    private Text toggleText() {
        return Text.literal("Buttons: ").append(enabled
                ? Text.literal("ON").formatted(Formatting.GREEN)
                : Text.literal("OFF").formatted(Formatting.RED));
    }

    private void save() {
        try {
            long min = DonutPayClient.parseAmount(minField.getText());
            long max = maxField.getText().trim().equals("0") ? 0 : DonutPayClient.parseAmount(maxField.getText());
            double mult = Double.parseDouble(multField.getText().trim());

            if (min < 0) throw new IllegalArgumentException("Minimum can't be negative.");
            if (max != 0 && max < min) throw new IllegalArgumentException("Maximum must be 0 or at least the minimum.");
            if (mult <= 0) throw new IllegalArgumentException("Multiplier must be above 0.");

            Config c = Config.get();
            c.enabled = enabled;
            c.minAmount = min;
            c.maxAmount = max;
            c.winMultiplier = mult;
            c.save();
            close();
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null || e instanceof NumberFormatException
                    ? "Enter valid numbers (e.g. 1000, 2.5K, 3M)." : e.getMessage();
            status = Text.literal(msg).formatted(Formatting.RED);
        } catch (ArithmeticException e) {
            status = Text.literal("That number is too large.").formatted(Formatting.RED);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int x = width / 2 - 100;
        int y = height / 2 - 85;
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, y - 18, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "Minimum payment (smaller ones are ignored)", x, y + 30, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, "Maximum payment (0 = no limit)", x, y + 68, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, "Win pays back (x multiplier)", x, y + 106, 0xFFAAAAAA);
        ctx.drawCenteredTextWithShadow(textRenderer, status, width / 2, y + 174, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
