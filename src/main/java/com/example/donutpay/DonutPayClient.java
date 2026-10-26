package com.example.donutpay;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

public class DonutPayClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("donutpay");

    private record Pending(String player, long amount) {}

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    /** Each payment can be resolved exactly once (no double-click double-pay). */
    private static final Map<Integer, Pending> PENDING = Collections.synchronizedMap(
            new LinkedHashMap<Integer, Pending>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Pending> eldest) {
                    return size() > 200;
                }
            });

    @Override
    public void onInitializeClient() {
        Config.get();

        // Settings GUI: keybind (default ]) or /donutpay
        KeyBinding openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutpay.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET,
                KeyBinding.Category.create(Identifier.of("donutpay", "main"))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new SettingsScreen(null));
            }
        });

        // Append the buttons to incoming payment messages.
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            Config cfg = Config.get();
            if (overlay || !cfg.enabled) return message;
            try {
                Matcher m = cfg.compiled.matcher(message.getString());
                if (!m.find()) return message;

                String player = m.group(1);
                long amount = parseAmount(m.group(2));
                if (amount <= 0 || amount < cfg.minAmount) return message;
                if (cfg.maxAmount > 0 && amount > cfg.maxAmount) return message;

                int id = NEXT_ID.getAndIncrement();
                PENDING.put(id, new Pending(player, amount));

                MutableText out = message.copy().append(Text.literal("  "));
                out.append(button("[Win]", Formatting.GREEN, "win", id, "Pay " + player + " $" + fmt(winPayout(amount))));
                out.append(Text.literal(" "));
                out.append(button("[Lose]", Formatting.RED, "lose", id, "Pay nothing"));
                out.append(Text.literal(" "));
                out.append(button("[Refund]", Formatting.YELLOW, "refund", id, "Pay " + player + " $" + fmt(amount)));
                return out;
            } catch (Exception e) {
                LOG.warn("Failed to process payment message", e);
                return message;
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // /donutpay -> opens settings
            dispatcher.register(ClientCommandManager.literal("donutpay").executes(ctx -> {
                MinecraftClient mc = ctx.getSource().getClient();
                mc.send(() -> mc.setScreen(new SettingsScreen(null))); // next tick, after chat closes
                return 1;
            }));

            // /dpay <win|lose|refund> <id>  (run by the buttons)
            dispatcher.register(ClientCommandManager.literal("dpay")
                    .then(ClientCommandManager.argument("action", StringArgumentType.word())
                            .then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
                                    .executes(ctx -> resolve(
                                            ctx.getSource(),
                                            StringArgumentType.getString(ctx, "action"),
                                            IntegerArgumentType.getInteger(ctx, "id"))))));
        });
    }

    private static MutableText button(String label, Formatting color, String action, int id, String tooltip) {
        Style style = Style.EMPTY
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/dpay " + action + " " + id))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(tooltip)));
        return Text.literal(label).setStyle(style);
    }

    private static long winPayout(long amount) {
        return BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(Config.get().winMultiplier))
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
    }

    private static int resolve(FabricClientCommandSource src, String action, int id) {
        Pending p = PENDING.get(id);
        if (p == null) {
            src.sendError(Text.literal("That payment was already handled (or has expired)."));
            return 0;
        }

        long payout;
        switch (action.toLowerCase(Locale.ROOT)) {
            case "win" -> payout = winPayout(p.amount());
            case "refund" -> payout = p.amount();
            case "lose" -> payout = 0;
            default -> {
                src.sendError(Text.literal("Unknown action: " + action));
                return 0;
            }
        }

        PENDING.remove(id);

        if (payout == 0) {
            src.sendFeedback(Text.literal("Lose: " + p.player() + " gets nothing.").formatted(Formatting.RED));
            return 1;
        }

        // Win / Refund pay the player immediately on click.
        String cmd = Config.get().payCommand
                .replace("{player}", p.player())
                .replace("{amount}", Long.toString(payout));
        src.getPlayer().networkHandler.sendChatCommand(cmd);
        src.sendFeedback(Text.literal((action.equalsIgnoreCase("win") ? "Win" : "Refund")
                + ": paid " + p.player() + " $" + fmt(payout)).formatted(Formatting.GREEN));
        return 1;
    }

    /** Parses "1,500", "2.5K", "3M", "1B" into a plain number. Throws NumberFormatException on bad input. */
    static long parseAmount(String raw) {
        String s = raw.replace(",", "").replace("$", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (s.isEmpty()) throw new NumberFormatException("empty");
        long mult = 1;
        char last = s.charAt(s.length() - 1);
        if (Character.isLetter(last)) {
            switch (last) {
                case 'K' -> mult = 1_000L;
                case 'M' -> mult = 1_000_000L;
                case 'B' -> mult = 1_000_000_000L;
                case 'T' -> mult = 1_000_000_000_000L;
                default -> throw new NumberFormatException("bad suffix");
            }
            s = s.substring(0, s.length() - 1);
        }
        return new BigDecimal(s).multiply(BigDecimal.valueOf(mult))
                .setScale(0, RoundingMode.DOWN).longValueExact();
    }

    private static String fmt(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }
}
