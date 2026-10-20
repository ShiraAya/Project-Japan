package com.sora.pjr.validation;

import java.nio.file.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/** Invoke the actual Forge decorator's selector with registered Minecraft blocks. */
final class FlowerValidation {
  static void verify() throws Exception {
    var method=Class.forName("com.sora.pjr.forge.Vegetation").getDeclaredMethod("flower",int.class,int.class,SplittableRandom.class);
    method.setAccessible(true);
    Map<String,Integer> counts=new TreeMap<>();
    for(int i=0;i<20000;i++){
      int x=i%200*13,z=i/200*17;
      Block first=(Block)method.invoke(null,x,z,new SplittableRandom(i));
      Block second=(Block)method.invoke(null,x,z,new SplittableRandom(i));
      if(first!=second)throw new AssertionError("Non-deterministic flower selection");
      String id=BuiltInRegistries.BLOCK.getKey(first).toString();counts.merge(id,1,Integer::sum);
    }
    if(counts.size()!=9||!counts.containsKey("minecraft:poppy")||!counts.containsKey("minecraft:cornflower")||!counts.containsKey("minecraft:oxeye_daisy"))
      throw new AssertionError("Missing flower colors "+counts);
    String report="PJR flower selection validation PASS\nsamples=20000\nregisteredFlowerSpecies="+counts.size()+"\ncounts="+counts+"\nLimit: actual selector and registered blocks; no live-world placement/lighting simulation.\n";
    Files.createDirectories(Path.of("build/reports"));Files.writeString(Path.of("build/reports/flower-validation.txt"),report);System.out.print(report);
  }
}
