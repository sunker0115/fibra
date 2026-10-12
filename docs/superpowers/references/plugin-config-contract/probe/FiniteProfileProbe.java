import com.networknt.schema.*;
import com.networknt.schema.serialization.DefaultNodeReader;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.util.stream.Collectors;
import java.util.Collections;

public class FiniteProfileProbe {
  static int checks;
  static void check(boolean result,String label) {if(!result) throw new AssertionError(label);System.out.println("PASS "+(++checks)+" "+label);}
  static String tree(String keyword,int width,int depth) {
    var defs=new StringBuilder("\"d0\":{\"type\":\"string\"}");
    for(int d=1;d<=depth;d++) {
      String ref="{\"$ref\":\"#/$defs/d"+(d-1)+"\"}";
      defs.append(",\"d").append(d).append("\":{\"type\":\"integer\",\"").append(keyword).append("\":[").append(String.join(",",Collections.nCopies(width,ref))).append("]}");
    }
    return "{\"$defs\":{"+defs+"},\"$ref\":\"#/$defs/d"+depth+"\"}";
  }
  public static void main(String[] args) {
    var mapper=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    var registry=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,b -> b.nodeReader(DefaultNodeReader.builder().jsonMapper(mapper).build()).schemaLoader(l -> l.fetchRemoteResources(false).block(iri -> true)));
    var dictionary=registry.getSchema("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"number\",\"minimum\":0.123456789012345678902}}");
    check(dictionary.validate("{\"first\":0.123456789012345678902,\"second\":9007199254740993}",InputFormat.JSON).isEmpty(),"typed dictionary accepts precise boundary and large integer");
    var errors=dictionary.validate("{\"synthetic-secret-map-key\":0.123456789012345678901}",InputFormat.JSON);
    check(errors.size()==1,"typed dictionary rejects adjacent small decimal");
    check(errors.getFirst().getInstanceLocation().toString().contains("synthetic-secret-map-key"),"raw library dictionary path retains input key: adapter must mask it");
    check(!dictionary.validate("{\"first\":\"0.2\"}",InputFormat.JSON).isEmpty(),"typed dictionary rejects string coercion");
    for(String keyword:new String[]{"allOf","anyOf","oneOf"}) for(int width:new int[]{2,8}) for(int depth:new int[]{2,4}) {
      var schema=registry.getSchema(tree(keyword,width,depth));
      for(boolean fast:new boolean[]{false,true}) {
        var pools=ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType()==MemoryType.HEAP).collect(Collectors.toList());
        for(var pool:pools) pool.resetPeakUsage();
        long start=System.nanoTime();
        var found=schema.validate("0",InputFormat.JSON,c -> c.executionConfig(e -> e.failFast(fast)));
        long peak=pools.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
        check(!found.isEmpty(),"finite "+keyword+" rejects width="+width+" depth="+depth+" fast="+fast);
        System.out.println("MEASURE keyword="+keyword+" width="+width+" depth="+depth+" fast="+fast+" returnedErrors="+found.size()+" elapsedMicros="+(System.nanoTime()-start)/1000+" summedHeapPoolPeakUsed="+peak);
      }
    }
    System.out.println("ASSERTIONS="+checks);
  }
}
