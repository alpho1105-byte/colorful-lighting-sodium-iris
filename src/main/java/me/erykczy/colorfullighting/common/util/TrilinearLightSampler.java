package me.erykczy.colorfullighting.common.util;

/** Smoothly samples block-centered RGB light values at an arbitrary world position. */
public final class TrilinearLightSampler {
    @FunctionalInterface
    public interface Lookup {
        ColorRGB4 get(int x, int y, int z);
    }

    private TrilinearLightSampler() {
    }

    public static ColorRGB8 sample(double x, double y, double z, Lookup lookup) {
        double gridX = x - 0.5;
        double gridY = y - 0.5;
        double gridZ = z - 0.5;
        int cornerX = (int) Math.floor(gridX);
        int cornerY = (int) Math.floor(gridY);
        int cornerZ = (int) Math.floor(gridZ);
        double deltaX = gridX - cornerX;
        double deltaY = gridY - cornerY;
        double deltaZ = gridZ - cornerZ;

        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;
        for(int dx = 0; dx <= 1; dx++) {
            for(int dy = 0; dy <= 1; dy++) {
                for(int dz = 0; dz <= 1; dz++) {
                    ColorRGB4 corner = lookup.get(cornerX + dx, cornerY + dy, cornerZ + dz);
                    double weight = (dx == 0 ? 1.0 - deltaX : deltaX)
                            * (dy == 0 ? 1.0 - deltaY : deltaY)
                            * (dz == 0 ? 1.0 - deltaZ : deltaZ);
                    red += corner.red4 * weight;
                    green += corner.green4 * weight;
                    blue += corner.blue4 * weight;
                }
            }
        }

        return ColorRGB8.fromRGB8(
                (int) Math.round(red * 17.0),
                (int) Math.round(green * 17.0),
                (int) Math.round(blue * 17.0)
        );
    }
}
