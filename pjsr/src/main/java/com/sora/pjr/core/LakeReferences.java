package com.sora.pjr.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit source-backed corrections; no runtime network, no hidden edits to original W09 data. */
public final class LakeReferences {
  private static final Properties REFERENCE = load();
  private static final Set<String> GENTLE_OUTLETS =
      Set.of(
          "Kussharo",
          "Abashiri",
          "Ogawara",
          "Jusan",
          "Suwa",
          "Yamanaka",
          "Biwa",
          "Shinji",
          "Nakaumi");

  private LakeReferences() {}

  private static Properties load() {
    try (InputStream in =
        LakeReferences.class.getResourceAsStream("/assets/pjr/world/lake-references.properties")) {
      if (in == null) throw new IOException("Missing verified lake references");
      Properties p = new Properties();
      p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      return p;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load lake references", e);
    }
  }

  public static double elevation(String id, double fallback) {
    String value = REFERENCE.getProperty(id + ".elevation_metres");
    return value == null ? fallback : Double.parseDouble(value);
  }

  /** Reviewed coarse lake heights that require a DEM-supported generation level. */
  public static boolean terrainSupportedLevel(String id) {
    return Boolean.parseBoolean(REFERENCE.getProperty(id + ".terrain_supported_level", "false"));
  }

  /** A non-positive maximum is not a usable depth for a waterbody. Keep the input auditable. */
  public static double depth(String id, double rawDepth) {
    if (Double.isFinite(rawDepth) && rawDepth > 0) return rawDepth;
    String value = REFERENCE.getProperty(id + ".reference_max_depth_metres");
    return value == null ? Double.NaN : Double.parseDouble(value);
  }

  /** Explicit basin estimate, used only when a measured maximum depth is absent. */
  public static double modelDepth(String id, double fallback) {
    String value = REFERENCE.getProperty(id + ".model_max_depth_metres");
    return value == null ? fallback : Double.parseDouble(value);
  }

  public static String source(String id) {
    return REFERENCE.getProperty(id + ".source", "");
  }

  public static boolean gentleOutlet(String name) {
    return GENTLE_OUTLETS.contains(name);
  }

  /** A reviewed physical shore contact whose rounded centreline misses the polygon. */
  public static boolean verifiedShoreLink(String id,String riverCode) {
    return riverCode.equals(REFERENCE.getProperty(id+".shore_river_code"));
  }
}
