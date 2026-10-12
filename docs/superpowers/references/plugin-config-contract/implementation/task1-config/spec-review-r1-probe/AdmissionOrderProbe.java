import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
public class AdmissionOrderProbe {
 static class Loader extends URLClassLoader {
  int libraryAttempts;
  Loader(URL[] urls){super(urls,ClassLoader.getPlatformClassLoader());}
  @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
   if(name.equals("com.networknt.schema.SchemaRegistry")){libraryAttempts++;throw new AssertionError("library execution sentinel");}
   return super.loadClass(name,resolve);
  }
 }
 public static void main(String[] args)throws Exception {
  var urls=Arrays.stream(args[0].split(":" )).filter(s->!s.isEmpty()).map(s->{try{return Path.of(s).toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);
  try(var loader=new Loader(urls)){
   var literal=loader.loadClass("com.sstlfsj.fibra.value.LiteralValue");var object=loader.loadClass("com.sstlfsj.fibra.value.LiteralValue$ObjectValue");
   var source=loader.loadClass("com.sstlfsj.fibra.config.ConfigSchemaSource");var config=loader.loadClass("com.sstlfsj.fibra.config.ConfigSchema");var input=loader.loadClass("com.sstlfsj.fibra.config.ConfigValidationInput");
   var factory=literal.getMethod("of",Object.class);var constructor=source.getConstructor(String.class,object);
   var sources=new ArrayList<>();for(int i=0;i<129;i++)sources.add(constructor.newInstance("fibra-config-1",factory.invoke(null,Map.of("$schema","https://json-schema.org/draft/2020-12/schema","type","null","title","t"+i))));
   var result=config.getMethod("checkSources",List.class).invoke(null,sources);
   if(!result.toString().contains("inputIndex=-1")||loader.libraryAttempts!=0)throw new AssertionError(result.toString());
   System.out.println("PASS unique schema overflow rejected before library; attempts="+loader.libraryAttempts);
   var nullLiteral=factory.invoke(null,new Object[]{null});var single=input.getConstructor(source,literal).newInstance(sources.getFirst(),nullLiteral);
   result=config.getMethod("validateAll",List.class).invoke(null,Collections.nCopies(800001,single));
   if(!result.toString().contains("inputIndex=-1")||loader.libraryAttempts!=0)throw new AssertionError(result.toString());
   System.out.println("PASS total repeated-instance work rejected before library; attempts="+loader.libraryAttempts);
   try{config.getMethod("compile",source).invoke(null,sources.getFirst());throw new AssertionError("control did not enter library");}catch(InvocationTargetException e){if(!(e.getCause() instanceof AssertionError))throw e;}
   if(loader.libraryAttempts!=1)throw new AssertionError("sentinel not reached");System.out.println("PASS valid compile control reaches library sentinel");
  }
 }
}
