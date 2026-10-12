package com.sstlfsj.fibra.artifact;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;
public final class SpecReviewProbe {
  static final byte[] BYTES={1,2,3};
  static ConfigurationResourceEvidence evidence() throws Exception {return ConfigurationResourceEvidence.regular(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)),3);}
  static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
  static ArtifactException fail(Runnable run){try {run.run();throw new AssertionError("expected artifact failure");}catch(ArtifactException ex){check(ex.path()==null && ex.getCause()==null && ex.getSuppressed().length==0,"unsafe failure");return ex;}}
  public static void main(String[] args) throws Exception {
    var supplied=new AtomicInteger();var closed=new AtomicInteger();
    var endless=new InputStream(){public int read(){supplied.incrementAndGet();return 1;} public int read(byte[] b,int off,int len){Arrays.fill(b,off,off+len,(byte)1);supplied.addAndGet(len);return len;}public void close(){closed.incrementAndGet();}};
    var growth=new FixedPluginConfigurationResource(Path.of("unused"),evidence(),p->endless);
    fail(growth::read);check(supplied.get()==1048577,"unbounded actual stream read");check(closed.get()==0,"read closed stream");growth.close();check(closed.get()==1,"close missing");System.out.println("PASS dynamic growth bounded to 1MiB plus one probe byte");
    var foreign=new IOException("secret"){public String getMessage(){throw new AssertionError("foreign message inspected");} public synchronized Throwable getCause(){throw new AssertionError("foreign cause inspected");}public String toString(){throw new AssertionError("foreign string inspected");}};
    var poison=new FixedPluginConfigurationResource(Path.of("unused"),evidence(),p->{throw foreign;});
    var first=fail(poison::read);first.addSuppressed(new Exception("secret"));check(first!=fail(poison::read),"mutable failure cached");poison.close();fail(poison::read);System.out.println("PASS foreign exception never inspected and replay cannot inherit suppressed poison");
    var closeCount=new AtomicInteger();var good=new ByteArrayInputStream(BYTES){public void close()throws IOException{closeCount.incrementAndGet();throw foreign;}};
    var mixed=new FixedPluginConfigurationResource(Path.of("unused"),evidence(),p->good);check(Arrays.equals(BYTES,mixed.read()),"initial read");fail(mixed::close);check(Arrays.equals(BYTES,mixed.read()),"close failure overwrote successful read");fail(mixed::close);check(closeCount.get()==1,"close retried");System.out.println("PASS successful read remains replayable after close failure");
    var temp=Files.createTempDirectory("fibra-spec-r1-probe-");var dir=Files.createDirectory(temp.resolve("real"));Files.write(dir.resolve("fibra-config.json"),BYTES);var link=Files.createSymbolicLink(temp.resolve("link"),dir);
    var rootLink=new FixedPluginConfigurationResource(link,evidence());fail(rootLink::read);rootLink.close();System.out.println("PASS symlink exact root rejected");
    System.out.println("class source="+FixedPluginConfigurationResource.class.getProtectionDomain().getCodeSource().getLocation());
  }
}
