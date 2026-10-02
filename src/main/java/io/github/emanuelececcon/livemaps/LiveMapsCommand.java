package io.github.emanuelececcon.livemaps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;
import org.bukkit.util.RayTraceResult;

/** /livemaps info | fill [scale] | scale <scale> [confirm] | reveal | refresh [map id] | status | reload */
final class LiveMapsCommand implements TabExecutor {

    private static final String ADMIN = "livemaps.admin";

    private final LiveMapsPlugin plugin;
    private final WallFiller filler;

    LiveMapsCommand(LiveMapsPlugin plugin) {
        this.plugin = plugin;
        this.filler = new WallFiller(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(sender);
            case "fill" -> fill(sender, args);
            case "scale" -> scale(sender, args);
            case "reveal" -> reveal(sender);
            case "refresh" -> refresh(sender, args);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(Component.text(
                    "Usage: /" + label + " <info|fill [scale]|scale <0-4>|reveal|refresh [map id]|status|reload>", NamedTextColor.RED));
        }
        return true;
    }

    /** Tells whether the map the player looks at (in a frame) or holds updates by itself, and why not. */
    private void info(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(text("Look at a framed map or hold one in game. From the console use /livemaps status."));
            return;
        }
        if (!allowed(sender, "livemaps.info")) {
            return;
        }
        ItemFrame frame = lookedAtFrame(player);
        ItemStack item = frame != null ? frame.getItem() : player.getInventory().getItemInMainHand();
        String where = frame != null ? "in this frame" : "in your hand";
        MapView view = FrameRegistry.mapView(item);
        if (view == null) {
            sender.sendMessage(text("Look at an item frame with a map, or hold a map."));
            return;
        }
        String reason = FrameRegistry.whyNotLive(item, plugin.settings());
        String header = "Map #" + view.getId() + " " + where + " (scale 1:" + (1 << view.getScale().getValue())
                + ", centre " + view.getCenterX() + ", " + view.getCenterZ() + "): ";
        if (reason != null) {
            sender.sendMessage(Component.text(header + "not updated, because " + reason + ".", NamedTextColor.YELLOW));
        } else if (plugin.registry().isLive(view.getId())) {
            sender.sendMessage(Component.text(header + "updates by itself.", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(text(header + "it will update by itself once it is in an item frame."));
        }
    }

    /** Puts seamless new maps into the empty frames of the wall the player looks at. */
    private void fill(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(text("Only players can fill a wall: look at one of its frames."));
            return;
        }
        if (!allowed(sender, "livemaps.fill")) {
            return;
        }
        Integer scale = args.length >= 2 ? parseScale(sender, args[1]) : null;
        if (args.length >= 2 && scale == null) {
            return;
        }
        ItemFrame frame = wallFrame(player);
        if (frame != null) {
            sender.sendMessage(filler.fill(player, frame, scale));
        }
    }

    /** Swaps the maps of the wall the player looks at for maps of another scale, same centre. */
    private void scale(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(text("Only players can rescale a wall: look at one of its frames."));
            return;
        }
        if (!allowed(sender, "livemaps.scale")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /livemaps scale <0-4>, looking at one of the wall's frames."
                    + " 0 is 1:1 (128 blocks per map), 4 is 1:16 (2048 blocks).", NamedTextColor.RED));
            return;
        }
        Integer scale = parseScale(sender, args[1]);
        if (scale == null) {
            return;
        }
        boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
        ItemFrame frame = wallFrame(player);
        if (frame != null) {
            sender.sendMessage(filler.rescale(frame, scale, confirmed));
        }
    }

    private static Integer parseScale(CommandSender sender, String argument) {
        try {
            int scale = Integer.parseInt(argument);
            if (scale >= 0 && scale <= 4) {
                return scale;
            }
        } catch (NumberFormatException notANumber) {
            // reported below
        }
        sender.sendMessage(Component.text("The scale is 0 (1:1, 128 blocks per map) to 4 (1:16, 2048 blocks).",
                NamedTextColor.RED));
        return null;
    }

    private static ItemFrame wallFrame(Player player) {
        ItemFrame frame = lookedAtFrame(player);
        if (frame == null) {
            player.sendMessage(Component.text("Look at one of the wall's item frames (up to 6 blocks away).",
                    NamedTextColor.RED));
        }
        return frame;
    }

    private void reveal(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(text("Only players can reveal the map they hold."));
            return;
        }
        if (!allowed(sender, "livemaps.reveal")) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        String reason = FrameRegistry.whyNotLive(item, plugin.settings());
        if (reason != null) {
            sender.sendMessage(Component.text("Hold a map to reveal: this one can't be drawn, because " + reason + ".",
                    NamedTextColor.RED));
            return;
        }
        MapView view = FrameRegistry.mapView(item);
        sender.sendMessage(text("Drawing map #" + view.getId() + " from every chunk the server has generated..."));
        plugin.engine().markAll(view.getId(), MapArea.of(view), true, changed -> {
            if (player.isOnline()) {
                player.sendMessage(Component.text("Map #" + view.getId() + " revealed: " + changed + " pixels changed.",
                        NamedTextColor.GREEN));
            }
        });
    }

    private void refresh(CommandSender sender, String[] args) {
        if (!allowed(sender, ADMIN)) {
            return;
        }
        List<FrameRegistry.LiveMap> maps = new ArrayList<>();
        if (args.length >= 2) {
            try {
                int id = Integer.parseInt(args[1]);
                plugin.registry().maps().stream().filter(map -> map.id == id).forEach(maps::add);
            } catch (NumberFormatException notANumber) {
                sender.sendMessage(Component.text("Use a map id, like /livemaps refresh 42.", NamedTextColor.RED));
                return;
            }
            if (maps.isEmpty()) {
                sender.sendMessage(Component.text("Map #" + args[1] + " is not in an item frame.", NamedTextColor.RED));
                return;
            }
        } else {
            maps.addAll(plugin.registry().maps());
        }
        int[] left = {maps.size()};
        int[] changed = {0};
        for (FrameRegistry.LiveMap map : maps) {
            plugin.engine().markAll(map.id, map.area, false, pixels -> {
                changed[0] += pixels;
                if (--left[0] == 0) {
                    sender.sendMessage(Component.text("Refresh done: " + changed[0] + " pixels changed on "
                            + maps.size() + " maps.", NamedTextColor.GREEN));
                }
            });
        }
        sender.sendMessage(text(maps.isEmpty() ? "No map is in an item frame yet."
                : "Redrawing " + maps.size() + " framed maps in the background..."));
    }

    private void status(CommandSender sender) {
        if (!allowed(sender, ADMIN)) {
            return;
        }
        Settings settings = plugin.settings();
        sender.sendMessage(text("LiveMaps " + plugin.getPluginMeta().getVersion() + ": "
                + plugin.registry().maps().size() + " maps in " + plugin.registry().frameCount() + " item frames."));
        sender.sendMessage(text("Block changes drawn after " + settings.delaySeconds() + " s; sweep "
                + (settings.sweepSeconds() == 0 ? "off" : "every " + settings.sweepSeconds() + " s")
                + "; biome colours " + (settings.biomeColors() ? "on" : "off") + "."));
        plugin.engine().statistics().forEach(line -> sender.sendMessage(text(line)));
    }

    private void reload(CommandSender sender) {
        if (!allowed(sender, ADMIN)) {
            return;
        }
        boolean colorsBefore = plugin.settings().biomeColors();
        plugin.reloadSettings();
        sender.sendMessage(Component.text("LiveMaps config reloaded.", NamedTextColor.GREEN));
        if (colorsBefore != plugin.settings().biomeColors()) {
            sender.sendMessage(text("Biome colours changed: run /livemaps refresh to redraw the framed maps."));
        }
    }

    private static ItemFrame lookedAtFrame(Player player) {
        World world = player.getWorld();
        RayTraceResult hit = world.rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(),
                6.0, 0.2, entity -> entity instanceof ItemFrame);
        return hit != null && hit.getHitEntity() instanceof ItemFrame frame ? frame : null;
    }

    private static boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
        return false;
    }

    private static Component text(String message) {
        return Component.text(message, NamedTextColor.GRAY);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("info"));
            if (sender.hasPermission("livemaps.fill")) {
                options.add("fill");
            }
            if (sender.hasPermission("livemaps.scale")) {
                options.add("scale");
            }
            if (sender.hasPermission("livemaps.reveal")) {
                options.add("reveal");
            }
            if (sender.hasPermission(ADMIN)) {
                options.addAll(List.of("refresh", "status", "reload"));
            }
            String typed = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.startsWith(typed)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("fill") && sender.hasPermission("livemaps.fill")
                || args[0].equalsIgnoreCase("scale") && sender.hasPermission("livemaps.scale"))) {
            return List.of("0", "1", "2", "3", "4").stream().filter(scale -> scale.startsWith(args[1])).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("refresh") && sender.hasPermission(ADMIN)) {
            return plugin.registry().maps().stream().map(map -> String.valueOf(map.id))
                    .filter(id -> id.startsWith(args[1])).sorted().toList();
        }
        return List.of();
    }
}
