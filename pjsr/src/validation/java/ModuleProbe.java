import java.nio.file.*;
import java.lang.module.*;
import java.util.*;
public class ModuleProbe {
 public static void main(String[] args)throws Exception {
  Path jar=Path.of(args[0]);ModuleFinder finder=ModuleFinder.of(jar);String name=finder.findAll().iterator().next().descriptor().name();
  var cf=ModuleLayer.boot().configuration().resolve(finder,ModuleFinder.of(),Set.of(name));
  var layer=ModuleLayer.boot().defineModulesWithOneLoader(cf,ClassLoader.getPlatformClassLoader());
  var loader=layer.findLoader(name);Thread.currentThread().setContextClassLoader(loader);
  var type=Class.forName("com.sora.pjr.forge.WorldRuntime",true,loader);
  System.out.println("module="+type.getModule()+" resource="+type.getResource("/assets/pjr/terrain/japan_height.png"));
  long t=System.nanoTime();type.getMethod("engine").invoke(null);System.out.println("MODULE STARTUP PASS seconds="+(System.nanoTime()-t)*1e-9);
 }
}
