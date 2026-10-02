package io.github.emanuelececcon.livemaps;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.map.MapView;

/**
 * Writes pixels into a map's saved colours. Bukkit has no API for that (renderers only paint a
 * per-player overlay), so this calls the server's {@code MapItemSavedData.updateColor}, which
 * stores the pixel, marks the map for saving and sends the change to everyone who sees the map.
 * Main thread only.
 */
final class MapWriter {

    private Field savedDataField;
    private MethodHandle updateColor;

    /** Writes the known pixels of {@code rect}; returns how many actually changed. */
    int write(MapView view, PixelRect rect, int[] pixels) {
        Object savedData = savedData(view);
        int changed = 0;
        int index = 0;
        try {
            for (int z = rect.z0(); z <= rect.z1(); z++) {
                for (int x = rect.x0(); x <= rect.x1(); x++) {
                    int pixel = pixels[index++];
                    if (pixel != PixelRenderer.SKIP && (boolean) updateColor.invoke(savedData, x, z, (byte) pixel)) {
                        changed++;
                    }
                }
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not write map pixels: " + failure, failure);
        }
        return changed;
    }

    private Object savedData(MapView view) {
        try {
            if (savedDataField == null) {
                Field field = view.getClass().getDeclaredField("worldMap");
                field.setAccessible(true);
                Object savedData = field.get(view);
                Method method = savedData.getClass().getMethod("updateColor", int.class, int.class, byte.class);
                updateColor = MethodHandles.lookup().unreflect(method);
                savedDataField = field;
                return savedData;
            }
            return savedDataField.get(view);
        } catch (ReflectiveOperationException | RuntimeException unsupported) {
            throw new IllegalStateException("This server's map data is not supported (" + unsupported + ")", unsupported);
        }
    }
}
