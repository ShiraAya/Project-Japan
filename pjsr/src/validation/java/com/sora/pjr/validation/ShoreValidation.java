package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Regressions at the six user-supplied screenshot locations, plus a valley-floor oracle. */
public final class ShoreValidation {
  private static final int[][] SITES = {
    {-57780, -15112},
    {-53598, -4704},
    {-53659, -4715},
    {-42018, 19229},
    {-2866, 8475},
    {-30451, -10810}
  };
  private static final String[] NAMES = {
    "1 / Suwa: graded shoreline",
    "2 / Fuji: mountain bank",
    "3 / Fuji: upstream bend",
    "4 / Fuji: lowland meander",
    "5 / Tsurumi: narrow coastal beach",
    "6 / Arakawa: local waterline blend"
  };
  private static final int[][] OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    verify(WorldEngine.load());
  }

  public static void verify(WorldEngine engine) throws Exception {
    var anchors =
        List.of(
            new HydraulicSolver.Anchor(0, 40, "source"),
            new HydraulicSolver.Anchor(120, 0, "mouth"));
    short[] grade = HydraulicSolver.solveTerrain(new double[] {40, 15, 35, 0}, 40, 120, anchors);
    require(grade[0] == 40 && grade[120] == 0, "Terrain grade endpoint anchors");
    require(grade[40] <= 15 && grade[80] <= 15, "A downstream rise must not lift a valley's water");
    for (int i = 1; i < grade.length; i++)
      require(grade[i] <= grade[i - 1] && grade[i - 1] - grade[i] <= 1, "Terrain grade slope");
    var suwa = engine.column(-58020, -15296);
    require(
        suwa.geometry().groundY() >= 285 && suwa.geometry().groundY() <= 286,
        "Suwa immediate shore loses its artificial pedestal, without moving lake level");
    require(
        suwa.layers().top() == SurfacePainter.Material.PODZOL,
        "A deep neighbouring lake bed must not turn flat dry soil into stone");
    require(engine.geometry(-57780, -15112).waterY() == 285, "Keep Suwa at Y=285");
    require(
        engine.column(-3074, 8429).layers().top() == SurfacePainter.Material.GRASS,
        "Low inland ground at the Tsurumi mouth must stay grass");
    require(
        engine.column(-3186, 8563).layers().top() == SurfacePainter.Material.GRASS,
        "Withdrawn Tokyo overlay shore is inland in the national base coast");
    require(
        engine.hydrology().terrain().sample(-3088, 8608).land()
            && engine.hydrology().terrain().coastDistance(-3088, 8608) <= 9
            && engine.column(-3088, 8608).layers().top() == SurfacePainter.Material.SAND,
        "Keep sand at the restored national base coast");

    BufferedImage image = new BufferedImage(1200, 1050, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(new Color(18, 26, 38));
    graphics.fillRect(0, 0, 1200, 1050);
    graphics.setColor(Color.WHITE);
    graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
    graphics.drawString("PJR / shore-fix geometry diagnostics", 24, 38);
    graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
    graphics.drawString(
        "Scale 0.375 : 0.375 : 0.375 | Numerical geometry and materials, not game screenshots",
        24,
        66);
    StringBuilder report =
        new StringBuilder(
            "PJR screenshot shore regression PASS\n"
                + "scale=0.375:0.375:0.375\n"
                + "6 windows: +/-240 blocks in X/Z; grid spacing=2; 58,081 sampled columns per"
                + " window.\n"
                + "Extra jump = max(0, abs(final dry-neighbour height difference) - abs(original"
                + " difference)).\n"
                + "Height jumps and containment also query all four immediate neighbours.\n"
                + "Synthetic valley: downstream high terrain cannot average an upstream low valley"
                + " upward.\n"
                + "Suwa reference shore: artificial pedestal removed, lake Y=285 retained,"
                + " top=podzol.\n");
    for (int k = 0; k < SITES.length; k++) {
      int cx = SITES[k][0], cz = SITES[k][1];
      int dry = 0, sand = 0, stone = 0, maxRaise = 0, maxJump = 0;
      BufferedImage panel = new BufferedImage(241, 241, BufferedImage.TYPE_INT_RGB);
      for (int iz = 0; iz < 241; iz++)
        for (int ix = 0; ix < 241; ix++) {
          int x = cx - 240 + ix * 2, z = cz - 240 + iz * 2;
          Column c = engine.column(x, z);
          Geometry g = c.geometry();
          if (!g.wet()) {
            dry++;
            if (c.layers().top() == SurfacePainter.Material.SAND) sand++;
            if (c.layers().top() == SurfacePainter.Material.STONE) stone++;
            maxRaise = Math.max(maxRaise, g.groundY() - g.naturalY());
            for (int[] offset : OFFSETS) {
              Geometry b = engine.geometry(x + offset[0], z + offset[1]);
              if (b.wet())
                require(
                    g.groundY() >= b.waterTop(), "Uncontained photographed bank " + x + "," + z);
              else
                maxJump =
                    Math.max(
                        maxJump,
                        Math.abs(g.groundY() - b.groundY())
                            - Math.abs(g.naturalY() - b.naturalY()));
            }
          }
          panel.setRGB(ix, iz, colour(c, engine.geometry(x - 1, z - 1)));
        }
      require(
          maxRaise == 0,
          "Artificial raised wall in screenshot window " + (k + 1) + ": " + maxRaise);
      require(
          maxJump <= 3,
          "Artificial dry-bank cliff in screenshot window " + (k + 1) + ": " + maxJump);
      if (k == 0) require(stone == 0, "False stone rim on Suwa's flat terrace");
      if (k == 4) require(sand > 0 && sand < dry * 0.10, "Excessive coastal sand coverage");
      report.append(
          String.format(
              Locale.ROOT,
              "site%d centre=(%d,%d) dry=%d sand=%d stone=%d maxRaise=%d maxExtraJump=%d%n",
              k + 1,
              cx,
              cz,
              dry,
              sand,
              stone,
              maxRaise,
              maxJump));
      int ox = 24 + (k % 3) * 392, oy = 115 + (k / 3) * 428;
      graphics.setColor(Color.WHITE);
      graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
      graphics.drawString(NAMES[k], ox, oy - 12);
      graphics.drawImage(panel, ox, oy, 360, 360, null);
      graphics.setColor(new Color(195, 206, 223));
      graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
      graphics.drawString("Centre X=" + cx + ", Z=" + cz + " | north up", ox, oy + 380);
      graphics.drawString(
          "Raised wall: " + maxRaise + " | extra dry step: " + maxJump, ox, oy + 399);
      engine.releaseThreadCache();
    }
    graphics.setColor(new Color(195, 206, 223));
    graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
    graphics.drawString(
        "Blue: water | Green / brown: soil | Grey: rock | Pale yellow: sand | White: snow",
        24,
        987);
    graphics.drawString(
        "Each panel: 1.28 km x 1.28 km. Natural relief retained; coast sand reaches at most 9"
            + " blocks inland.",
        24,
        1013);
    graphics.dispose();
    int pairs = 0, maxGap = 0, highPairs = 0;
    for (int z = -10910; z <= -10710; z++)
      for (int x = -30551; x <= -30351; x++) {
        Geometry bank = engine.geometry(x, z);
        if (bank.wet()) continue;
        for (int[] offset : OFFSETS) {
          Geometry water = engine.geometry(x + offset[0], z + offset[1]);
          if (!water.wet()) continue;
          int gap = bank.groundY() - water.waterTop();
          require(gap >= 0, "Arakawa water containment");
          maxGap = Math.max(maxGap, gap);
          if (gap >= 3) highPairs++;
          pairs++;
        }
      }
    require(pairs >= 300, "Arakawa shoreline regression coverage");
    require(maxGap <= 3, "Arakawa local waterline must not retain the old 5-block rim");
    report.append(
        "Arakawa +/-100 block window: shorePairs="
            + pairs
            + " maxGap="
            + maxGap
            + " pairsAtLeast3="
            + highPairs
            + "; 0.1.2 baseline: maxGap=5, pairsAtLeast3=119.\n");
    report.append(
        "Limit: these are local sampled regressions, not exhaustive shore checks or"
            + " client/fluid-tick tests.\n");
    Files.createDirectories(Path.of("build/reports"));
    Files.writeString(Path.of("build/reports/shore-validation.txt"), report);
    ImageIO.write(image, "png", Path.of("build/reports/terrain-diagnostics.png").toFile());
    System.out.print(report);
  }

  private static int colour(Column c, Geometry neighbour) {
    Color base =
        switch (c.layers().top()) {
          case GRASS -> new Color(104, 153, 78);
          case PODZOL, COARSE_DIRT, DIRT -> new Color(130, 115, 72);
          case SAND -> new Color(225, 211, 158);
          case STONE, GRAVEL -> new Color(141, 146, 145);
          case SNOW, SNOW_BLOCK -> new Color(238, 241, 243);
          default -> new Color(131, 140, 127);
        };
    Geometry g = c.geometry();
    if (g.wet()) {
      double depth = Math.min(1, (g.waterY() - g.groundY()) / 20.0);
      base = new Color((int) (65 - depth * 40), (int) (171 - depth * 85), (int) (200 - depth * 70));
    }
    double shade =
        g.wet() || neighbour.wet()
            ? 1
            : Geo.clamp(1 + (g.groundY() - neighbour.groundY()) * 0.10, 0.60, 1.25);
    return new Color(
            (int) Math.min(255, base.getRed() * shade),
            (int) Math.min(255, base.getGreen() * shade),
            (int) Math.min(255, base.getBlue() * shade))
        .getRGB();
  }
}
