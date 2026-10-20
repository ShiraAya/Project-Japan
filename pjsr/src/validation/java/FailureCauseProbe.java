import com.sora.pjr.forge.WorldRuntime;
public class FailureCauseProbe {
 static Throwable root(Throwable t){while(t.getCause()!=null)t=t.getCause();return t;}
 public static void main(String[] args) {
  Throwable first=null;
  for(int i=0;i<2;i++) {
   try {WorldRuntime.engine();throw new AssertionError("Changed hydraulic input accepted");}
   catch(IllegalStateException expected) {
    Throwable cause=root(expected);
    if(!cause.toString().contains("Stale hydraulic input: lake-references.properties"))throw new AssertionError(cause);
    if(i==0)first=cause;else if(first!=cause)throw new AssertionError("Original cause was lost");
    System.out.println("failure["+i+"] "+expected.getClass().getSimpleName()+" root="+cause);
   }
  }
  System.out.println("FAILURE CAUSE RETENTION / INPUT INTEGRITY PASS");
 }
}
