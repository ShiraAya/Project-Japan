package com.sora.pjr.validation;
import com.sora.pjr.core.*;import com.sora.pjr.roads.*;import java.util.*;import java.util.concurrent.*;
public final class PjsrCoreValidation {
 static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception{
  var catalog=RoadCatalog.load();var engine=WorldEngine.load();var p=new RoadPlan(catalog,engine);
  check(p.units.size()==14,"fixed resource unit count");check(p.length>1000&&p.length<1100,"route length");
  var ids=new HashSet<UUID>();RoadPlan.Sample previous=null;
  for(var u:p.units){
   check(ids.add(u.id()),"stable unique identities");var a=u.samples().get(0);var b=u.samples().get(u.samples().size()-1);
   check(b.station()-a.station()<100,"bounded edit length");
   if(previous!=null){check(Math.hypot(previous.x()-a.x(),previous.z()-a.z())<1e-9,"shared exact anchor");check(previous.y()==a.y(),"shared elevation");check(previous.width()==a.width(),"shared width");}
   previous=b;
   for(int i=0;i<u.samples().size();i++){
    var s=u.samples().get(i);check(p.inChunk((int)Math.floor(s.x()/16),(int)Math.floor(s.z()/16)).contains(u),"negative chunk index coverage");
    if(i>0){var t=u.samples().get(i-1);check(Math.abs(s.y()-t.y())<=.1200001*(s.station()-t.station()),"grade limit");}
    check(s.y()>Geo.MIN_Y&&s.y()+6<Geo.MAX_Y,"height envelope");
   }
  }
  var pool=Executors.newFixedThreadPool(2);
  try{var futures=pool.invokeAll(List.<Callable<RoadPlan>>of(()->new RoadPlan(catalog,engine),()->new RoadPlan(catalog,engine)));for(var f:futures)check(f.get().units.equals(p.units),"parallel planning deterministic");}finally{pool.shutdown();}
  System.out.println("PJSR CORE PASS checks="+checks+" units="+p.units.size()+" length="+p.length);
 }
}
