package com.sora.pjr.core;

import java.io.*;
import java.util.*;

/** Explicit visual dimensions for regional rivers, not surveyed channel measurements. */
public final class RiverShapes {
  public record Shape(double mouthWidthMetres, int maxChannelDepth, double sourceWidthMetres) {}

  private static final Map<String, Shape> SHAPES = load();

  private RiverShapes() {}

  public static Shape get(String name) { return SHAPES.get(name); }

  public static Set<String> names() { return SHAPES.keySet(); }

  private static Map<String, Shape> load() {
    Properties properties = new Properties();
    try (InputStream in = RiverShapes.class.getResourceAsStream("/assets/pjr/world/river-shapes.properties")) {
      if (in == null) throw new IOException("Missing regional river dimensions");
      properties.load(in);
      Map<String, Shape> result = new HashMap<>();
      for (String name : properties.stringPropertyNames()) {
        String[] values = properties.getProperty(name).split(",");
        if (values.length != 2 && values.length != 3) throw new IOException("Invalid river dimensions for " + name);
        double width = Double.parseDouble(values[0].trim());
        int depth = Integer.parseInt(values[1].trim());
        double source = values.length == 3 ? Double.parseDouble(values[2].trim()) : 4;
        if (!Double.isFinite(width) || width < 4 || width > 320 || depth < 1 || depth > 16)
          throw new IOException("River dimensions out of range for " + name);
        if (!Double.isFinite(source) || source<4 || source>width) throw new IOException("Invalid source width " + name);
        result.put(name, new Shape(width, depth, source));
      }
      return Collections.unmodifiableMap(result);
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalStateException("Invalid regional river dimensions", e);
    }
  }
}
