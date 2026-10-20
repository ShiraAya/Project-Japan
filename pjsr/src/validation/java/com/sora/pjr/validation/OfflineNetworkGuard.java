package com.sora.pjr.validation;

import java.security.Permission;

/** Java 17 validation-only guard: any runtime connection attempt fails the release test. */
@SuppressWarnings("removal")
public final class OfflineNetworkGuard extends SecurityManager {
  private static final java.util.concurrent.atomic.AtomicInteger attempts=new java.util.concurrent.atomic.AtomicInteger();
  public OfflineNetworkGuard(){Runtime.getRuntime().addShutdownHook(new Thread(()->System.out.println("PJR offline network guard: blockedAttempts="+attempts.get())));}
  @Override public void checkPermission(Permission p) {}
  @Override public void checkPermission(Permission p,Object context) {}
  private void denied(){attempts.incrementAndGet();new Exception("Blocked network operation trace").printStackTrace(System.out);throw new SecurityException("Network disabled for offline release validation");}
  @Override public void checkConnect(String host,int port){denied();}
  @Override public void checkConnect(String host,int port,Object context){denied();}
  @Override public void checkListen(int port){denied();}
  @Override public void checkAccept(String host,int port){denied();}
  @Override public void checkMulticast(java.net.InetAddress address){denied();}
}
