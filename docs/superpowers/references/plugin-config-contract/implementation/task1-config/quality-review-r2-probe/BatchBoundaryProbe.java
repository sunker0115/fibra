import java.util.*;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.value.LiteralValue;
public class BatchBoundaryProbe {
 static ConfigSchemaSource source(Map<String,Object> fields) {
  var m = new LinkedHashMap<>(fields); m.put("$schema", "https://json-schema.org/draft/2020-12/schema");
  return new ConfigSchemaSource("fibra-config-1", (LiteralValue.ObjectValue)LiteralValue.of(m));
 }
 static void expect(Optional<ConfigBatchFailure> result, int index, String code) {
  var f=result.orElseThrow(); if(f.inputIndex()!=index || !f.violation().code().equals(code)) throw new AssertionError(f);
  System.out.println(f);
 }
 public static void main(String[] args) {
  var valid=source(Map.of("type","string"));
  var over=source(Map.of("type","null","description","x".repeat(4097)));
  expect(ConfigSchema.checkSources(Collections.nCopies(10000000,over)),0,"BUDGET_EXCEEDED");
  expect(ConfigSchema.validateAll(Collections.nCopies(10000000,new ConfigValidationInput(valid,LiteralValue.of("ok")))),-1,"BUDGET_EXCEEDED");
  var invalid=source(Map.of("type","null","default",false));
  expect(ConfigSchema.checkSources(List.of(valid,valid,invalid)),2,"INVALID_SCHEMA");
  expect(ConfigSchema.validateAll(List.of(new ConfigValidationInput(valid,LiteralValue.of("ok")),new ConfigValidationInput(valid,LiteralValue.of(42)),new ConfigValidationInput(invalid,LiteralValue.of(null)))),1,"VALUE_MISMATCH");
  var future=new ConfigSchemaSource("future",valid.schema());
  expect(ConfigSchema.validateAll(Collections.nCopies(100000,new ConfigValidationInput(future,LiteralValue.of("ok")))),0,"UNSUPPORTED_PROFILE");
 }
}
