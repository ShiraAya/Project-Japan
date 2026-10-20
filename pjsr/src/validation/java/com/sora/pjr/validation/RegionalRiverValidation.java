package com.sora.pjr.validation;

import com.sora.pjr.core.*;
import java.nio.file.*;
import java.util.*;

/** Geographic acceptance checks for the 14 added rivers and unchanged original hydraulic network. */
public final class RegionalRiverValidation {
  private static long checks;
  private static void require(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception { verify(WorldEngine.load()); }

  public static void verify(WorldEngine engine) throws Exception {
    WorldData data = engine.hydrology().data();
    Map<String, River> rivers = new HashMap<>();
    for (River r : data.rivers()) rivers.put(r.name(), r);
    Map<String, Lake> lakes = new HashMap<>();
    for (Lake l : data.lakes()) lakes.put(l.id(), l);
    int oldRivers = 0, oldLakes = 0, recomputedProfiles = 0;
    for (String line : Files.readAllLines(Path.of("data/baseline-hydrology-0.1.6.tsv"))) {
      String[] v = line.split("\t");
      if (v[0].equals("river")) {
        require(rivers.containsKey(v[1]), "Existing river missing " + v[1]);
        // Ocean-mask repair legitimately recomputes downstream support. Preserve the raw
        // routes, and validate every new profile with the hydraulic invariants in CoreValidation.
        if (!HydrologyFingerprint.river(rivers.get(v[1])).equals(v[2])) recomputedProfiles++;
        oldRivers++;
      } else {
        require(HydrologyFingerprint.lake(lakes.get(v[1])).equals(v[2]), "Lake attributes changed " + v[1]);
        oldLakes++;
      }
    }
    require(oldRivers == 115 && oldLakes == 205, "Complete baseline comparison");
    String[] names = {"Yoro","Obitsu","Koito","Isumi","Ichinomiya","Kuriyama","Sakawa",
      "Sakaigawa-Kanagawa","Hikiji","Nameri","Tanabu","Ohata","Kawauchi-Shimokita","Okoppe"};
    double[] minimumKm = {70,70,50,65,25,30,48,48,19,5,23,27,26,6};
    require(data.rivers().size() == RawGeography.load().rivers().size(), "Every selected river solved");
    require(RiverShapes.names().containsAll(Set.of(names)) && RiverShapes.names().size()==55, "Every new river has deliberate dimensions");
    StringBuilder report = new StringBuilder("PJR regional river validation PASS\n");
    StringBuilder samples = new StringBuilder("river,progress,x,z,lon,lat,water_y,model_width,channel_depth,geometry_kind,geometry_depth\n");
    StringBuilder routes = new StringBuilder("river,new,lon,lat\n");
    for (River r : data.rivers())
      for (River.Point p : r.points()) routes.append(String.format(Locale.ROOT,"%s,%s,%.8f,%.8f%n",r.name(),RiverShapes.names().contains(r.name()),Geo.lon(p.x()),Geo.lat(p.z())));
    for (int n = 0; n < names.length; n++) {
      String name = names[n];
      River r = rivers.get(name);
      require(r != null, "Missing regional river " + name);
      require(r.totalLength()*Geo.METRES_XZ/1000 >= minimumKm[n], "Truncated upstream route " + name);
      require(r.sea() && r.terminalRiver().isEmpty() && r.sourceRiver().isEmpty(), "Independent sea-going river " + name);
      require(r.water(r.totalLength()) == 0, "Sea anchor " + name);
      require(r.at(0).width() <= 1.500001, "Broad headwaters " + name);
      RiverShapes.Shape shape = RiverShapes.get(name);
      require(r.at(r.totalLength()).width() <= Geo.horizontal(shape.mouthWidthMetres())+1e-8, "Mouth width exceeds regional scale " + name);
      int last = r.water(0), jumps = 0;
      for (int s=1; s<r.profileSize(); s++) {
        int current = r.water(s);
        require(current<=last && last-current<=1, "Reversed or broken profile " + name);
        if (last!=current) jumps++;
        last=current;
      }
      for (int i=0; i<=100; i++) {
        double station = r.totalLength()*i/100.0;
        River.Point p = r.at(station);
        int x=(int)Math.floor(p.x()), z=(int)Math.floor(p.z());
        Geometry g = engine.geometry(x,z);
        require(g.wet(), "Dry new river centre " + name + " " + x + "," + z);
        int depth = Hydrology.depth(new River.Hit(r,station,0,p.width()));
        require(depth>=1 && depth<=shape.maxChannelDepth(), "Regional depth cap " + name);
        double radius = Hydrology.radius(new River.Hit(r,station,0,p.width()));
        require(Hydrology.depth(new River.Hit(r,station,radius,p.width()))==1, "Shallow edge " + name);
        if (i<100) require(p.width()<=r.at(r.totalLength()*(i+1)/100).width()+1e-8,"Downstream narrowing " + name);
        samples.append(String.format(Locale.ROOT,"%s,%.2f,%d,%d,%.8f,%.8f,%d,%.5f,%d,%s,%d%n",
          name,i/100.0,x,z,Geo.lon(x),Geo.lat(z),r.water(station),p.width(),depth,g.kind(),g.waterY()-g.groundY()));
        // Cross-sections include dry-bank contact and continuous wet cells between channel edges.
        if (i>0 && i<100 && i%5==0) {
          River.Point a=r.at(Math.max(0,station-4)), b=r.at(Math.min(r.totalLength(),station+4));
          double dx=b.x()-a.x(), dz=b.z()-a.z(), len=Math.hypot(dx,dz);
          for (int offset=-(int)Math.ceil(radius+3);offset<=radius+3;offset++) {
            int px=(int)Math.floor(p.x()-dz/len*offset), pz=(int)Math.floor(p.z()+dx/len*offset);
            Geometry q=engine.geometry(px,pz);
            if (q.wet()) continue;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
              Geometry adj=engine.geometry(px+d[0],pz+d[1]);
              if (adj.wet()) require(q.groundY()>=adj.waterTop(), "Uncontained bank " + name);
            }
          }
        }
      }
      report.append(String.format(Locale.ROOT,"%s lengthKm=%.3f mouthWidth=%.3f channelDepthCap=%d gates=%d steps=%d%n",name,r.totalLength()*Geo.METRES_XZ/1000,r.at(r.totalLength()).width(),shape.maxChannelDepth(),r.gates().size(),jumps));
    }
    // These two rivers require files across prefecture borders, not inferred replacements.
    require(Geo.lon(rivers.get("Sakawa").at(0).x()) < 138.90, "Sakawa includes Shizuoka Ayuzawa headwaters");
    require(Geo.lat(rivers.get("Sakaigawa-Kanagawa").at(0).z()) > 35.60, "Sakaigawa includes Tokyo upper river");
    report.append("originalRiverIdentitiesRetained=").append(oldRivers).append("\nprofilesRecomputedAfterOceanMaskRepair=").append(recomputedProfiles).append("\noriginalLakeAttributesUnchanged=").append(oldLakes)
      .append("\naddedRivers=").append(names.length).append("\nassertions=").append(checks)
      .append("\nLimit: core geometry checks; no Minecraft client, fluid ticks or FPS measurement.\n");
    Files.createDirectories(Path.of("build/reports"));
    Files.writeString(Path.of("build/reports/regional-river-validation.txt"),report);
    Files.writeString(Path.of("build/reports/regional-river-samples.csv"),samples);
    Files.writeString(Path.of("build/reports/regional-river-routes.csv"),routes);
    System.out.print(report);
  }
}
