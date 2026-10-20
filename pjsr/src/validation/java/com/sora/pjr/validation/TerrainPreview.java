package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;

/** A diagnostic of actual PJR geometry, explicitly not a Minecraft screenshot. */
public final class TerrainPreview {
  public static void main(String[] args) throws Exception {
    WorldEngine engine = WorldEngine.load();
    int w = 560, h = 350;
    BufferedImage image = new BufferedImage(w * 2 + 72, h * 2 + 220, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(new Color(17, 24, 36));
    g.fillRect(0, 0, image.getWidth(), image.getHeight());
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
    g.drawString("ProjectJapanRefined / geometry diagnostics", 24, 40);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
    g.setColor(new Color(185, 198, 217));
    g.drawString(
        "Uniform scale 0.375 on all axes. Geometry diagnostic only; this is not a Minecraft"
            + " screenshot.",
        24,
        68);
    Lake biwa =
        engine.hydrology().data().lakes().stream()
            .filter(l -> l.name().equals("Biwa"))
            .findFirst()
            .orElseThrow();
    Bounds b = biwa.polygon().bounds();
    River yodo = river(engine, "Yodo"),
        kurobe = river(engine, "Kurobe"),
        sagami = river(engine, "Sagami");
    River.Point yo = yodo.at(Geo.horizontal(320)),
        ku = kurobe.at(kurobe.totalLength() * 0.38),
        sa = sagami.at(Geo.horizontal(640));
    double lakeScale = Math.max((b.maxX() - b.minX()) / w, (b.maxZ() - b.minZ()) / h) * 1.08;
    List<Panel> panels =
        List.of(
            new Panel(
                "Lake Biwa / geographic extent",
                (b.minX() + b.maxX()) / 2,
                (b.minZ() + b.maxZ()) / 2,
                lakeScale),
            new Panel("Yodo / lake outlet", yo.x(), yo.z(), Geo.horizontal(8)),
            new Panel("Kurobe / mountain river", ku.x(), ku.z(), Geo.horizontal(16)),
            new Panel("Sagami / source lake", sa.x(), sa.z(), Geo.horizontal(8)));
    for (int n = 0; n < panels.size(); n++) {
      Panel p = panels.get(n);
      int ox = 24 + (n % 2) * (w + 24), oz = 115 + (n / 2) * (h + 55);
      g.setColor(Color.WHITE);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
      g.drawString(p.title, ox, oz - 12);
      for (int py = 0; py < h; py++)
        for (int px = 0; px < w; px++) {
          int x = (int) Math.floor(p.x + (px - w / 2.0) * p.scale),
              z = (int) Math.floor(p.z + (py - h / 2.0) * p.scale);
          Geometry a = engine.geometry(x, z);
          int rgb;
          if (a.wet()) {
            double depth = Math.min(1, (a.waterY() - a.groundY()) * Geo.METRES_Y / 96.0);
            rgb = mix(new Color(64, 168, 194), new Color(22, 66, 126), depth).getRGB();
          } else {
            Color base =
                mix(
                    new Color(117, 157, 101),
                    new Color(224, 225, 218),
                    Geo.clamp(a.groundY() * Geo.METRES_Y / 2600.0, 0, 1));
            int neighbour = engine.geometry(x - 2, z - 2).groundY();
            double shade = Geo.clamp(1 + (a.groundY() - neighbour) * 0.08, 0.65, 1.2);
            rgb =
                new Color(
                        (int) Math.min(255, base.getRed() * shade),
                        (int) Math.min(255, base.getGreen() * shade),
                        (int) Math.min(255, base.getBlue() * shade))
                    .getRGB();
          }
          image.setRGB(ox + px, oz + py, rgb);
        }
      g.setColor(new Color(185, 198, 217));
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
      g.drawString(
          String.format(
              java.util.Locale.ROOT,
              "North up | %.1f m/pixel | centre %.5f E, %.5f N",
              p.scale * Geo.METRES_XZ,
              Geo.lon(p.x),
              Geo.lat(p.z)),
          ox,
          oz + h + 18);
    }
    g.dispose();
    Files.createDirectories(Path.of("build/reports"));
    ImageIO.write(image, "png", Path.of("build/reports/terrain-diagnostics.png").toFile());
    System.out.println("Wrote build/reports/terrain-diagnostics.png");
  }

  private record Panel(String title, double x, double z, double scale) {}

  private static River river(WorldEngine e, String name) {
    return e.hydrology().data().rivers().stream()
        .filter(r -> r.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static Color mix(Color a, Color b, double t) {
    return new Color(
        (int) Math.round(a.getRed() * (1 - t) + b.getRed() * t),
        (int) Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
        (int) Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
  }
}
