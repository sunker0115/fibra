import com.networknt.schema.*;
import com.networknt.schema.serialization.DefaultNodeReader;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

public class IndustryShapeProbe {
  static int count;
  static void check(boolean result,String label) {if(!result) throw new AssertionError(label);System.out.println("PASS "+(++count)+" "+label);}
  public static void main(String[] args) {
    var mapper=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    var r=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,b -> b.nodeReader(DefaultNodeReader.builder().jsonMapper(mapper).build()).schemaLoader(l -> l.fetchRemoteResources(false).block(iri -> true)));
    var connector=r.getSchema("""
      {"$schema":"https://json-schema.org/draft/2020-12/schema",
       "$defs":{
         "strings":{"type":"object","additionalProperties":{"type":"string"}},
         "oauth":{"oneOf":[{"type":"boolean","const":false},{"type":"object","properties":{"client_id":{"type":"string","minLength":1}},"additionalProperties":false}]},
         "local":{"type":"object","properties":{"type":{"type":"string","const":"local"},"command":{"type":"array","items":{"type":"string"},"minItems":1},"environment":{"$ref":"#/$defs/strings"}},"required":["type","command"],"additionalProperties":false},
         "remote":{"type":"object","properties":{"type":{"type":"string","const":"remote"},"url":{"type":"string","minLength":1},"headers":{"$ref":"#/$defs/strings"},"oauth":{"$ref":"#/$defs/oauth"}},"required":["type","url"],"additionalProperties":false},
         "connector":{"oneOf":[{"$ref":"#/$defs/local"},{"$ref":"#/$defs/remote"}]}},
       "$ref":"#/$defs/connector"}
      """);
    check(connector.validate("{\"type\":\"local\",\"command\":[\"node\",\"server.mjs\"],\"environment\":{\"LOG_LEVEL\":\"info\"}}",InputFormat.JSON).isEmpty(),"local with typed environment passes");
    check(connector.validate("{\"type\":\"remote\",\"url\":\"https://example.invalid\",\"headers\":{\"X-Workspace\":\"a\"},\"oauth\":false}",InputFormat.JSON).isEmpty(),"remote with typed headers and false oauth passes");
    check(connector.validate("{\"type\":\"remote\",\"url\":\"https://example.invalid\",\"oauth\":{\"client_id\":\"id\"}}",InputFormat.JSON).isEmpty(),"remote oauth object passes");
    check(!connector.validate("{\"type\":\"local\"}",InputFormat.JSON).isEmpty(),"local missing command fails");
    check(!connector.validate("{\"type\":\"remote\",\"url\":\"x\",\"command\":[\"node\"]}",InputFormat.JSON).isEmpty(),"remote with local-only command fails");
    check(!connector.validate("{\"type\":\"remote\",\"url\":\"x\",\"headers\":{\"X-Workspace\":7}}",InputFormat.JSON).isEmpty(),"numeric header fails");
    check(!connector.validate("{\"type\":\"remote\",\"url\":\"x\",\"oauth\":true}",InputFormat.JSON).isEmpty(),"oauth true fails");
    check(!connector.validate("{\"type\":\"remote\",\"url\":\"x\",\"oauth\":{\"unexpected\":\"x\"}}",InputFormat.JSON).isEmpty(),"unknown oauth field fails");
    var timeout=r.getSchema("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"anyOf\":[{\"type\":\"number\",\"minimum\":0},{\"type\":\"string\",\"const\":\"disabled\"}]}");
    check(timeout.validate("0.123456789012345678901",InputFormat.JSON).isEmpty(),"number-or-disabled accepts exact decimal");
    check(timeout.validate("\"disabled\"",InputFormat.JSON).isEmpty(),"number-or-disabled accepts literal disabled");
    check(!timeout.validate("\"0\"",InputFormat.JSON).isEmpty(),"number string is not coerced");
    check(!timeout.validate("-1",InputFormat.JSON).isEmpty(),"negative timeout fails");
    var overlap=r.getSchema("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"oneOf\":[{\"type\":\"integer\"},{\"type\":\"number\"}]}");
    check(!overlap.validate("1",InputFormat.JSON).isEmpty(),"oneOf rejects two matching branches");
    check(!overlap.validate("\"none\"",InputFormat.JSON).isEmpty(),"oneOf rejects zero matching branches");
    check(overlap.validate("1.5",InputFormat.JSON).isEmpty(),"oneOf accepts exactly one branch");
    var intersect=r.getSchema("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"allOf\":[{\"type\":\"string\",\"minLength\":2},{\"type\":\"string\",\"maxLength\":4}]}");
    check(intersect.validate("\"abc\"",InputFormat.JSON).isEmpty(),"allOf string intersection passes");
    check(!intersect.validate("\"a\"",InputFormat.JSON).isEmpty(),"allOf enforces both constraints");
    var closed=r.getSchema("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"allOf\":[{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"],\"additionalProperties\":false},{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\"}},\"required\":[\"b\"],\"additionalProperties\":false}]}");
    check(!closed.validate("{\"a\":\"x\",\"b\":\"y\"}",InputFormat.JSON).isEmpty(),"allOf does not merge closed object properties");
    System.out.println("ASSERTIONS="+count);
  }
}
