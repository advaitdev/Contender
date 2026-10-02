package com.uhcranked.testbed.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.text.ObjectTextContent;
import net.minecraft.text.Text;
import net.minecraft.text.object.AtlasTextObjectContents;
import net.minecraft.util.Atlases;

/** Loopback-only semantic and screenshot driver for an actual Minecraft client. */
public final class UhcrClientDriver implements ClientModInitializer {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final Map<String, Map<String, Object>> actionStatuses = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();
    private final AtomicLong screenshotSequence = new AtomicLong();
    private final Deque<Map<String, Object>> events = new ArrayDeque<>();
    private String runId;
    private String token;
    private Path actionsFile;
    private Path artifactDirectory;
    private HttpServer controller;

    @Override
    public void onInitializeClient() {
        try {
            loadConfig();
            int port = Integer.parseInt(required("uhcr.testbed.client.port"));
            controller = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            controller.createContext("/", this::handle);
            controller.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            controller.start();
            appendEvent("client.ready", Map.of("port", port));
            // Log every sound the client plays, including ones it makes on its own (item pickups, firework bursts),
            // so a noise can be traced to its source. Lines start with [sound] in the client log.
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                    client.getSoundManager().registerListener((sound, soundSet, range) ->
                            org.slf4j.LoggerFactory.getLogger("testbed").info("[sound] " + sound.getId() + " " + sound.getCategory())));
        } catch (Exception failure) {
            throw new IllegalStateException("UHCR render driver refused to start", failure);
        }
    }

    private void loadConfig() throws IOException {
        if (!Boolean.parseBoolean(System.getProperty("uhcr.testbed", "false"))) {
            throw new IOException("-Duhcr.testbed=true is required");
        }
        runId = required("uhcr.testbed.runId");
        if (!runId.matches("[0-9]{8}T[0-9]{6}Z-[0-9a-f]{4,16}")) throw new IOException("Invalid testbed run ID");
        Path tokenFile = Path.of(required("uhcr.testbed.tokenFile")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(tokenFile) || !Files.isReadable(tokenFile)) throw new IOException("Token file is not readable");
        UserPrincipal current = FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        if (!Files.getOwner(tokenFile).equals(current)) throw new IOException("Token file is not owned by this user");
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tokenFile);
            if (permissions.stream().anyMatch(value -> value.name().startsWith("GROUP_") || value.name().startsWith("OTHERS_"))) {
                throw new IOException("Token file permissions are too broad");
            }
        } catch (UnsupportedOperationException ignored) {
        }
        token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
        if (token.length() < 43) throw new IOException("Controller token must contain at least 256 bits");
        actionsFile = Path.of(required("uhcr.testbed.actionsFile")).toAbsolutePath().normalize();
        artifactDirectory = Path.of(required("uhcr.testbed.client.artifacts")).toAbsolutePath().normalize();
        Files.createDirectories(artifactDirectory);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                respond(exchange, 401, Map.of("schemaVersion", 1, "error", "UNAUTHORIZED"));
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String replayKey = headerKey(exchange);
            Map<String, Object> replay = replayKey == null ? null : actionStatuses.get(replayKey);
            if ((method.equals("POST") || method.equals("DELETE")) && replay != null) {
                respond(exchange, 200, Map.of("schemaVersion", 1, "duplicate", true, "action", replay));
                return;
            }
            if (method.equals("GET") && path.equals("/v1/live")) respond(exchange, 200, Map.of("schemaVersion", 1, "runId", runId, "live", true));
            else if (method.equals("GET") && path.equals("/v1/ready")) respond(exchange, 200,
                    Map.of("schemaVersion", 1, "runId", runId, "ready", MinecraftClient.getInstance() != null));
            else if (method.equals("GET") && path.equals("/v1/capabilities")) respond(exchange, 200,
                    Map.of("schemaVersion", 1, "actorTypes", List.of("render"),
                            "actions", List.of("command", "chat", "disconnect", "playerList"),
                            "widgetTree", true, "screenshots", true, "inventorySlots", true,
                            "slotClickModes", List.of("PICKUP", "QUICK_MOVE")));
            else if (method.equals("GET") && path.equals("/v1/state")) respond(exchange, 200, client(this::state));
            else if (method.equals("GET") && path.equals("/v1/events")) {
                long after = queryLong(exchange, "after", 0);
                respond(exchange, 200, Map.of("schemaVersion", 1, "runId", runId,
                        "events", eventsAfter(after), "latestSequence", eventSequence.get()));
            }
            else if (method.equals("GET") && path.startsWith("/v1/actions/")) {
                Map<String, Object> status = actionStatuses.get(path.substring("/v1/actions/".length()));
                respond(exchange, status == null ? 404 : 200,
                        status == null ? Map.of("schemaVersion", 1, "error", "ACTION_NOT_FOUND") : status);
            }
            else if (method.equals("POST") && path.equals("/v1/actors")) {
                JsonObject body = body(exchange); String key = journal(exchange, path, body);
                String alias = body.has("alias") ? body.get("alias").getAsString() : "";
                if (!alias.equals(MinecraftClient.getInstance().getSession().getUsername())) {
                    transition(key, "failed", null, "Render alias mismatch");
                    respond(exchange, 409, Map.of("schemaVersion", 1, "error", "RENDER_ALIAS_MISMATCH",
                            "message", "The render actor is the launched client session"));
                } else {
                    Map<String, Object> result = client(this::state);
                    transition(key, "succeeded", result, null);
                    respond(exchange, 200, result);
                }
            }
            else if (method.equals("GET") && path.matches("/v1/actors/[A-Za-z0-9_]{1,16}")) {
                String alias = path.substring(path.lastIndexOf('/') + 1);
                if (!alias.equals(MinecraftClient.getInstance().getSession().getUsername()))
                    respond(exchange, 404, Map.of("schemaVersion", 1, "error", "ACTOR_NOT_FOUND"));
                else respond(exchange, 200, client(this::state));
            }
            else if (method.equals("GET") && path.matches("/v1/actors/[A-Za-z0-9_]{1,16}/screen")) {
                Map<String, Object> screen = client(this::screen);
                if (queryFlag(exchange, "screenshot")) screen.put("screenshot", client(this::screenshot));
                respond(exchange, 200, screen);
            } else if (method.equals("POST") && path.matches("/v1/actors/[A-Za-z0-9_]{1,16}/actions")) {
                JsonObject body = body(exchange); String key = journal(exchange, path, body);
                Map<String, Object> result = Map.of("schemaVersion", 1, "result", client(() -> action(body, key)));
                transition(key, "succeeded", result, null);
                respond(exchange, 200, result);
            } else if (method.equals("POST") && path.matches("/v1/actors/[A-Za-z0-9_]{1,16}/click")) {
                JsonObject body = body(exchange); String key = journal(exchange, path, body);
                Map<String, Object> result = Map.of("schemaVersion", 1, "result", client(() -> click(body, key)));
                transition(key, "succeeded", result, null);
                respond(exchange, 200, result);
            } else if (method.equals("POST") && path.equals("/v1/shutdown")) {
                JsonObject body = body(exchange); String key = journal(exchange, path, body);
                Map<String, Object> result = Map.of("schemaVersion", 1, "shuttingDown", true);
                transition(key, "succeeded", result, null);
                respond(exchange, 202, result);
                MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().scheduleStop());
            } else respond(exchange, 404, Map.of("schemaVersion", 1, "error", "NOT_FOUND"));
        } catch (DuplicateAction duplicate) {
            respond(exchange, 200, Map.of("schemaVersion", 1, "duplicate", true, "action", duplicate.status));
        } catch (java.util.concurrent.TimeoutException timeout) {
            transition(headerKey(exchange), "timed-out-unknown", null, timeout.toString());
            respond(exchange, 504, Map.of("schemaVersion", 1, "error", "CLIENT_THREAD_TIMEOUT"));
        } catch (Exception failure) {
            transition(headerKey(exchange), "failed", null, failure.toString());
            respond(exchange, 500, Map.of("schemaVersion", 1, "error", "DRIVER_ERROR", "message", failure.toString()));
        } finally {
            exchange.close();
        }
    }

    private Map<String, Object> state() {
        MinecraftClient client = MinecraftClient.getInstance();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("runId", runId);
        value.put("alias", client.getSession().getUsername());
        value.put("type", "render");
        value.put("connected", client.getNetworkHandler() != null);
        value.put("version", client.getGameVersion());
        value.put("screen", client.currentScreen == null ? null : client.currentScreen.getClass().getName());
        value.put("playerListVisible", client.options.playerListKey.isPressed());
        value.put("latestEventSequence", eventSequence.get());
        return value;
    }

    private Map<String, Object> screen() {
        MinecraftClient client = MinecraftClient.getInstance();
        Screen screen = client.currentScreen;
        if (screen == null) return new LinkedHashMap<>(Map.of("schemaVersion", 1, "open", false));
        List<Map<String, Object>> widgets = new ArrayList<>();
        int index = 0;
        for (ClickableWidget widget : clickableWidgets(screen)) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", "widget-" + index++);
            value.put("testId", stableId(widget));
            value.put("class", widget.getClass().getName());
            value.put("label", widget.getMessage().getString());
            value.put("labelWidth", client.textRenderer.getWidth(widget.getMessage()));
            value.put("x", widget.getX());
            value.put("y", widget.getY());
            value.put("width", widget.getWidth());
            value.put("height", widget.getHeight());
            value.put("enabled", widget.active);
            value.put("focused", widget.isFocused());
            value.put("sprites", resolveSprites(widget.getMessage(), widget.getMessage().getString()));
            widgets.add(value);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("open", true);
        value.put("screenClass", screen.getClass().getName());
        value.put("title", screen.getTitle().getString());
        value.put("widgets", widgets);
        value.put("dialogType", screen.getClass().getSimpleName());
        value.put("titleComponent", screen.getTitle().toString());
        List<Map<String, Object>> sprites = new ArrayList<>();
        sprites.addAll(resolveSprites(screen.getTitle(), "dialog title"));
        for (Map<String, Object> widget : widgets) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> widgetSprites = (List<Map<String, Object>>) widget.get("sprites");
            sprites.addAll(widgetSprites);
        }
        value.put("sprites", sprites);
        value.put("window", Map.of("width", client.getWindow().getWidth(), "height", client.getWindow().getHeight(),
                "framebufferWidth", client.getWindow().getFramebufferWidth(),
                "framebufferHeight", client.getWindow().getFramebufferHeight(),
                "guiScale", client.getWindow().getScaleFactor()));
        value.put("environment", Map.of(
                "clientVersion", client.getGameVersion(),
                "language", client.options.language,
                "fov", client.options.getFov().getValue(),
                "guiScaleOption", client.options.getGuiScale().getValue(),
                "resourcePacks", List.copyOf(client.getResourcePackManager().getEnabledIds())));
        value.put("inventory", inventory(client));
        if (screen instanceof HandledScreen<?> handled) {
            value.put("slots", handled.getScreenHandler().slots.stream().map(slot -> {
                ItemStack stack = slot.getStack();
                return Map.of("id", slot.id, "x", slot.x, "y", slot.y,
                        "label", stack.getName().getString(), "item", stack.getItem().toString(),
                        "count", stack.getCount(), "components", stack.getComponents().toString());
            }).toList());
            value.put("cursor", handled.getScreenHandler().getCursorStack().getCount());
        }
        value.put("missingResources", resourceErrors(client.runDirectory.toPath().resolve("logs/latest.log")));
        value.put("recentWarnings", recentWarnings(client.runDirectory.toPath().resolve("logs/latest.log")));
        return value;
    }

    private static List<Map<String, Object>> resolveSprites(Text text, String label) {
        List<Map<String, Object>> result = new ArrayList<>();
        collectSprites(text, label, result);
        return List.copyOf(result);
    }

    private static void collectSprites(Text text, String label, List<Map<String, Object>> result) {
        if (text.getContent() instanceof ObjectTextContent object
                && object.contents() instanceof AtlasTextObjectContents atlas) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("componentLabel", label);
            value.put("atlasKey", atlas.atlas().toString());
            value.put("spriteKey", atlas.sprite().toString());
            try {
                SpriteAtlasTexture texture = MinecraftClient.getInstance().getAtlasManager()
                        .getAtlasTexture(atlas.atlas());
                Sprite sprite = texture.getSprite(atlas.sprite());
                String resolved = sprite.getContents().getId().toString();
                value.put("resolvedSpriteIdentifier", resolved);
                value.put("missing", sprite == texture.getMissingSprite()
                        || sprite.getContents().getId().equals(MissingSprite.getMissingSpriteId()));
                value.put("atlasConsistent", atlasConsistent(atlas));
            } catch (RuntimeException failure) {
                value.put("resolvedSpriteIdentifier", null);
                value.put("missing", true);
                value.put("atlasConsistent", false);
                value.put("resolutionError", failure.toString());
            }
            result.add(java.util.Collections.unmodifiableMap(value));
        }
        for (Text sibling : text.getSiblings()) collectSprites(sibling, label, result);
    }

    private static boolean atlasConsistent(AtlasTextObjectContents contents) {
        String spritePath = contents.sprite().getPath();
        if (spritePath.startsWith("item/")) return contents.atlas().equals(Atlases.ITEMS);
        if (spritePath.startsWith("block/")) return contents.atlas().equals(Atlases.BLOCKS);
        return true;
    }

    private static String stableId(ClickableWidget widget) {
        String value = (widget.getClass().getSimpleName() + "-" + widget.getMessage().getString())
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return value.isBlank() ? "widget" : value;
    }

    private static List<ClickableWidget> clickableWidgets(ParentElement root) {
        List<ClickableWidget> result = new ArrayList<>();
        collectClickableWidgets(root.children(), result);
        return result;
    }

    private static void collectClickableWidgets(List<? extends Element> elements,
                                                List<ClickableWidget> result) {
        for (Element element : elements) {
            if (element instanceof ClickableWidget widget) result.add(widget);
            if (element instanceof ParentElement parent) {
                collectClickableWidgets(parent.children(), result);
            }
        }
    }

    private static List<Map<String, Object>> inventory(MinecraftClient client) {
        if (client.player == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            result.add(Map.of("slot", slot, "item", stack.getItem().toString(), "count", stack.getCount(),
                    "components", stack.getComponents().toString()));
        }
        return result;
    }

    private Map<String, Object> action(JsonObject request, String actionId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) throw new IllegalStateException("Client is not connected");
        String type = request.get("type").getAsString();
        if (type.equals("command")) {
            String command = request.get("command").getAsString().replaceFirst("^/", "");
            client.getNetworkHandler().sendChatCommand(command);
            appendEvent("actor.command", Map.of("alias", client.getSession().getUsername(),
                    "command", command, "actionId", actionId));
            return Map.of("performed", true, "path", "real client command input");
        }
        if (type.equals("chat")) {
            client.getNetworkHandler().sendChatMessage(request.get("message").getAsString());
            appendEvent("actor.chat", Map.of("alias", client.getSession().getUsername(), "actionId", actionId));
            return Map.of("performed", true, "path", "real client chat input");
        }
        if (type.equals("disconnect")) {
            client.disconnect(Text.literal("UHCR testbed actor disconnect"));
            return Map.of("performed", true, "path", "real client disconnect");
        }
        if (type.equals("useHotbar")) {
            int slot = request.get("slot").getAsInt();
            if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot must be 0..8");
            if (client.currentScreen != null || client.interactionManager == null) {
                throw new IllegalStateException("Hotbar use requires a connected player with no screen open");
            }
            client.player.getInventory().setSelectedSlot(slot);
            client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.MAIN_HAND);
            appendEvent("actor.use_hotbar", Map.of("alias", client.getSession().getUsername(),
                    "slot", slot, "actionId", actionId));
            return Map.of("performed", true, "slot", slot, "path", "real client selected-slot and use-item packets");
        }
        if (type.equals("mouse")) {
            // Moves the real cursor (in window pixels), for example out of the way before a screenshot.
            double x = request.get("x").getAsDouble(), y = request.get("y").getAsDouble();
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().getHandle(), x, y);
            // GLFW doesn't report a programmatic move back to Minecraft, so tell its mouse handler too.
            try {
                var method = net.minecraft.client.Mouse.class.getDeclaredMethod("onCursorPos", long.class, double.class, double.class);
                method.setAccessible(true);
                method.invoke(client.mouse, client.getWindow().getHandle(), x, y);
            } catch (ReflectiveOperationException failure) {
                return Map.of("performed", false, "error", failure.toString());
            }
            return Map.of("performed", true, "x", x, "y", y);
        }
        if (type.equals("attack")) {
            // Hits whatever the crosshair is on, exactly like a left click.
            var target = client.targetedEntity;
            if (target == null || client.interactionManager == null) return Map.of("performed", false, "reason", "nothing targeted");
            client.interactionManager.attackEntity(client.player, target);
            client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            return Map.of("performed", true, "target", target.getName().getString());
        }
        if (type.equals("swing")) {
            // A left click at nothing: the arm swing packet, as when clicking in-world displays.
            client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            return Map.of("performed", true);
        }
        if (type.equals("close")) {
            if (client.currentScreen != null) client.currentScreen.close();
            return Map.of("performed", true);
        }
        if (type.equals("hud")) {
            boolean hidden = request.has("hidden") && request.get("hidden").getAsBoolean();
            client.options.hudHidden = hidden;
            return Map.of("performed", true, "hidden", hidden);
        }
        if (type.equals("playerList")) {
            boolean pressed = request.has("pressed") && request.get("pressed").getAsBoolean();
            client.options.playerListKey.setPressed(pressed);
            appendEvent("actor.player_list", Map.of("alias", client.getSession().getUsername(),
                    "pressed", pressed, "actionId", actionId));
            return Map.of("performed", true, "pressed", pressed,
                    "path", "real client player-list key binding");
        }
        throw new IllegalArgumentException("Unsupported render action: " + type);
    }

    private Map<String, Object> click(JsonObject request, String actionId) {
        Screen screen = MinecraftClient.getInstance().currentScreen;
        if (screen == null) throw new IllegalStateException("No screen is open");
        if (request.has("slotId")) {
            if (!(screen instanceof HandledScreen<?> handled)) throw new IllegalStateException("No inventory screen is open");
            int slot = request.get("slotId").getAsInt();
            int button = request.has("button") ? request.get("button").getAsInt() : 0;
            String mode = request.has("mode") ? request.get("mode").getAsString() : "PICKUP";
            if (slot < 0 || slot >= handled.getScreenHandler().slots.size() || button < 0 || button > 1)
                throw new IllegalArgumentException("Invalid inventory slot or button");
            if (!mode.equals("PICKUP") && !mode.equals("QUICK_MOVE")) throw new IllegalArgumentException("Unsupported slot click mode");
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.interactionManager == null || client.player == null) throw new IllegalStateException("Client is not connected");
            client.interactionManager.clickSlot(handled.getScreenHandler().syncId, slot, button,
                    SlotActionType.valueOf(mode), client.player);
            appendEvent("actor.slot_click", Map.of("slotId", slot, "button", button, "mode", mode, "actionId", actionId));
            return Map.of("performed", true, "path", "real client inventory click packet", "slotId", slot);
        }
        String id = request.get("widgetId").getAsString();
        int wanted = Integer.parseInt(id.replace("widget-", ""));
        List<ClickableWidget> widgets = clickableWidgets(screen);
        if (wanted >= 0 && wanted < widgets.size()) {
            ClickableWidget widget = widgets.get(wanted);
            if (request.has("text")) {
                // Types into a text field, replacing what it holds.
                if (!(widget instanceof net.minecraft.client.gui.widget.TextFieldWidget field)) throw new IllegalArgumentException("Widget is not a text field: " + id);
                field.setText(request.get("text").getAsString());
                return Map.of("performed", true, "path", "real client text field", "widgetId", id);
            }
            // "position" (0 to 1) clicks along a slider's track instead of the middle.
            double x = request.has("position")
                    ? widget.getX() + 4 + (widget.getWidth() - 8) * Math.clamp(request.get("position").getAsDouble(), 0, 1)
                    : widget.getX() + widget.getWidth() / 2.0;
            Click click = new Click(x, widget.getY() + widget.getHeight() / 2.0, new MouseInput(0, 0));
            widget.onClick(click, false);
            appendEvent("actor.widget_click", Map.of("widgetId", id,
                    "label", widget.getMessage().getString(), "actionId", actionId));
            return Map.of("performed", true, "path", "real client widget click", "widgetId", id);
            }
        throw new IllegalArgumentException("Unknown widget ID: " + id);
    }

    private Map<String, String> screenshot() throws IOException {
        MinecraftClient client = MinecraftClient.getInstance();
        long capture = screenshotSequence.incrementAndGet();
        Path path = artifactDirectory.resolve("screen-" + capture + ".png");
        Path cropPath = artifactDirectory.resolve("screen-" + capture + "-crop.png");
        int[] bounds = dialogBounds(client);
        ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), image -> {
            try {
                writeAtomically(image, path);
                if (bounds != null) {
                    int left = Math.max(0, Math.min(image.getWidth() - 1, bounds[0]));
                    int top = Math.max(0, Math.min(image.getHeight() - 1, bounds[1]));
                    int right = Math.max(left + 1, Math.min(image.getWidth(), bounds[2]));
                    int bottom = Math.max(top + 1, Math.min(image.getHeight(), bounds[3]));
                    try (var crop = new net.minecraft.client.texture.NativeImage(right - left, bottom - top, false)) {
                        for (int y = top; y < bottom; y++) {
                            for (int x = left; x < right; x++) {
                                crop.setColorArgb(x - left, y - top, image.getColorArgb(x, y));
                            }
                        }
                        writeAtomically(crop, cropPath);
                    }
                }
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            } finally {
                image.close();
            }
        });
        return Map.of("full", path.toString(), "dialog", cropPath.toString());
    }

    private static void writeAtomically(net.minecraft.client.texture.NativeImage image, Path target)
            throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        image.writeTo(temporary);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int[] dialogBounds(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen == null) return null;
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = 0, bottom = 0;
        for (ClickableWidget widget : clickableWidgets(screen)) {
            if (widget.visible) {
                left = Math.min(left, widget.getX());
                top = Math.min(top, widget.getY());
                right = Math.max(right, widget.getRight());
                bottom = Math.max(bottom, widget.getBottom());
            }
        }
        if (left == Integer.MAX_VALUE) return null;
        double scale = client.getWindow().getScaleFactor();
        int margin = 24;
        return new int[] {
                (int) Math.floor(Math.max(0, left - margin) * scale),
                (int) Math.floor(Math.max(0, top - margin) * scale),
                (int) Math.ceil(Math.min(client.getWindow().getScaledWidth(), right + margin) * scale),
                (int) Math.ceil(Math.min(client.getWindow().getScaledHeight(), bottom + margin) * scale)
        };
    }

    private List<String> resourceErrors(Path log) {
        if (!Files.isRegularFile(log)) return List.of();
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return lines.stream().skip(Math.max(0, lines.size() - 100))
                    .filter(line -> line.matches("(?i).*(missing (texture|sprite)|unable to load resource|resource reload failed).*") )
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private List<String> recentWarnings(Path log) {
        if (!Files.isRegularFile(log)) return List.of();
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return lines.stream().skip(Math.max(0, lines.size() - 200))
                    .filter(line -> line.matches("(?i).*(warn|error|exception).*")).limit(50).toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private <T> T client(java.util.concurrent.Callable<T> operation) throws Exception {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft.isOnThread()) return operation.call();
        CompletableFuture<T> result = new CompletableFuture<>();
        minecraft.execute(() -> {
            try { result.complete(operation.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            result.cancel(false);
            throw timeout;
        }
    }

    private JsonObject body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(65_537);
        if (bytes.length > 65_536) throw new IOException("Request is too large");
        if (bytes.length == 0) return new JsonObject();
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private synchronized String journal(HttpExchange exchange, String route, JsonObject request) throws IOException {
        String key = headerKey(exchange);
        if (key == null || key.isBlank() || key.length() > 128) throw new IOException("X-Idempotency-Key is required");
        Map<String, Object> prior = actionStatuses.get(key);
        if (prior != null) throw new DuplicateAction(prior);
        if (actionStatuses.size() >= 4_096) throw new IOException("Action retention limit reached");
        String now = Instant.now().toString();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("schemaVersion", 1); action.put("runId", runId); action.put("idempotencyKey", key);
        action.put("route", route); action.put("request", request); action.put("state", "accepted");
        action.put("acceptedAt", now); action.put("updatedAt", now);
        actionStatuses.put(key, action); writeAction(action, "accepted");
        transition(key, "started", null, null);
        return key;
    }

    private synchronized void transition(String key, String state, Object result, String error) throws IOException {
        if (key == null) return;
        Map<String, Object> prior = actionStatuses.get(key);
        if (prior == null || Set.of("succeeded", "failed", "timed-out-unknown").contains(prior.get("state"))) return;
        Map<String, Object> next = new LinkedHashMap<>(prior);
        next.put("state", state); next.put("updatedAt", Instant.now().toString());
        if (result != null) next.put("result", result);
        if (error != null) next.put("error", error);
        actionStatuses.put(key, next); writeAction(next, state);
    }

    private void writeAction(Map<String, Object> action, String transition) throws IOException {
        Map<String, Object> line = new LinkedHashMap<>(action);
        line.put("transition", transition); line.put("time", Instant.now().toString());
        Files.writeString(actionsFile, JSON.toJson(line) + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private static String headerKey(HttpExchange exchange) {
        return exchange.getRequestHeaders().getFirst("X-Idempotency-Key");
    }

    private boolean authorized(HttpExchange exchange) {
        return constantEquals(exchange.getRequestHeaders().getFirst("Authorization"), "Bearer " + token)
                && constantEquals(exchange.getRequestHeaders().getFirst("X-UHCR-Testbed-Run"), runId);
    }

    private static boolean queryFlag(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return false;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(name) && pair.length == 2 && pair[1].equalsIgnoreCase("true")) return true;
        }
        return false;
    }

    private static long queryLong(HttpExchange exchange, String name, long fallback) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return fallback;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(name) && pair.length == 2) {
                try { return Long.parseLong(pair[1]); }
                catch (NumberFormatException ignored) { return fallback; }
            }
        }
        return fallback;
    }

    private synchronized void appendEvent(String type, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sequence", eventSequence.incrementAndGet());
        event.put("time", Instant.now().toString());
        event.put("type", type);
        event.put("data", data);
        events.addLast(event);
        while (events.size() > 2_048) events.removeFirst();
    }

    private synchronized List<Map<String, Object>> eventsAfter(long after) {
        return events.stream().filter(event -> ((Number) event.get("sequence")).longValue() > after)
                .limit(500).toList();
    }

    private static boolean constantEquals(String left, String right) {
        return left != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private static String required(String property) throws IOException {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IOException("Missing -D" + property);
        return value;
    }

    private static final class DuplicateAction extends RuntimeException {
        private final Map<String, Object> status;
        private DuplicateAction(Map<String, Object> status) { this.status = status; }
    }
}
