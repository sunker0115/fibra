import com.networknt.schema.*;
import com.networknt.schema.serialization.DefaultNodeReader;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.DecimalNode;
import java.math.BigDecimal;

public class ProfileProbe {
  static int count;
  static void check(boolean b,String label) { if(!b) throw new AssertionError(label); System.out.println("PASS "+(++count)+" "+label); }
  public static void main(String[] args) {
    var mapper=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    var registry=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,b -> b.nodeReader(DefaultNodeReader.builder().jsonMapper(mapper).build()).schemaLoader(l -> l.fetchRemoteResources(false).block(iri -> true)));
    // These checks deliberately establish counterexamples; they are not acceptance of the behavior.
    check(!registry.getSchema("{\"minimum\":0.123456789012345678902}").validate("0.123456789012345678901",InputFormat.JSON).isEmpty(),"minimum exact adjacent decimals");
    check(!registry.getSchema("{\"maximum\":9007199254740992}").validate("9007199254740993",InputFormat.JSON).isEmpty(),"maximum exact adjacent large integers");
    check(!registry.getSchema("{\"exclusiveMinimum\":1}").validate("1.000",InputFormat.JSON).isEmpty(),"exclusiveMinimum equal number rejects");
    check(!registry.getSchema("{\"exclusiveMaximum\":1}").validate("1.000",InputFormat.JSON).isEmpty(),"exclusiveMaximum equal number rejects");
    check(!registry.getSchema("{\"enum\":[0.123456789012345678901]}").validate("0.123456789012345678902",InputFormat.JSON).isEmpty(),"enum exact adjacent decimals");
    check(registry.getSchema("{\"type\":\"integer\"}").validate(DecimalNode.valueOf(new BigDecimal("9007199254740993"))).isEmpty(),"LiteralValue DecimalNode integral accepted");
    check(!registry.getSchema("{\"type\":\"integer\"}").validate(DecimalNode.valueOf(new BigDecimal("1.0000000000000000001"))).isEmpty(),"fractional DecimalNode rejected as integer");
    var closed=registry.getSchema("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}},\"required\":[\"n\"],\"additionalProperties\":false}");
    var es=closed.validate("{\"synthetic-secret-key\":\"synthetic-secret-value\"}",InputFormat.JSON,c -> c.executionConfig(e -> e.failFast(true)));
    check(es.size()==1,"branchless profile failFast returns one failure");
    check(!closed.validate("{}",InputFormat.JSON,OutputFormat.BOOLEAN),"boolean output avoids returning Error");
    check(registry.getSchema("{\"required\":7}").validate("{}",InputFormat.JSON).isEmpty(),"ordinary compile ignores malformed required value");
    System.out.println("ASSERTIONS="+count);
  }
}
