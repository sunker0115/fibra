package com.sstlfsj.fibra.config;
import com.sstlfsj.fibra.value.LiteralValue;
import java.util.*;
import java.math.BigDecimal;
public class ContractProbe {
 static ConfigSchemaSource source(Map<String,?> fields) {var m=new LinkedHashMap<String,Object>(fields);m.put("$schema",SchemaProfile.DIALECT);return new ConfigSchemaSource("fibra-config-1",(LiteralValue.ObjectValue)LiteralValue.of(m));}
 static void check(boolean result,String message) {if(!result) throw new AssertionError(message);System.out.println("PASS "+message);}
 static void invalid(Map<String,?> fields) {try {ConfigSchema.compile(source(fields));throw new AssertionError("accepted invalid");}catch(ConfigSchemaException expected){}}
 public static void main(String[] args) {
  var string=source(Map.of("type","string"));
  var bad=source(Map.of("type","string","default","not allowed"));
  var mixed=ConfigSchema.validateAll(List.of(new ConfigValidationInput(string,LiteralValue.of(42)),new ConfigValidationInput(bad,LiteralValue.of("ok"))));
  System.out.println("OBSERVE earlierValueMismatchLaterInvalidSchema="+mixed);
  var numeric=ConfigSchema.compile(source(Map.of("type","number","minimum",new BigDecimal("1e-256"),"maximum",new BigDecimal("1e256"))));
  check(numeric.validate(LiteralValue.of(new BigDecimal("1e-256"))).isEmpty(),"minimum lower exponent exact edge");
  check(numeric.validate(LiteralValue.of(new BigDecimal("1e256"))).isEmpty(),"maximum upper exponent exact edge");
  check(numeric.validate(LiteralValue.of(BigDecimal.ZERO)).isPresent(),"minimum exponent rejects zero");
  var props=new LinkedHashMap<String,Object>(); for(int i=0;i<128;i++)props.put("p"+i,true);
  check(ConfigSchema.checkSources(List.of(source(Map.of("type","object","properties",props,"additionalProperties",false)))).isEmpty(),"128 object members accepted");
  props.put("overflow",true);invalid(Map.of("type","object","properties",props,"additionalProperties",false));
  check(true,"129 object members rejected");
  var defs=new LinkedHashMap<String,Object>();defs.put("d0",Map.of("type","null"));for(int i=1;i<16;i++)defs.put("d"+i,Map.of("$ref","#/$defs/d"+(i-1)));
  check(ConfigSchema.checkSources(List.of(source(Map.of("$defs",defs,"$ref","#/$defs/d15")))).isEmpty(),"16 reference edges accepted");
  defs.put("d16",Map.of("$ref","#/$defs/d15"));invalid(Map.of("$defs",defs,"$ref","#/$defs/d16"));check(true,"17 reference edges rejected");
  Map<String,?> branch=Map.of("type","null");for(int i=0;i<4;i++)branch=Map.of("anyOf",List.of(branch,Map.of("type","string")));
  check(ConfigSchema.checkSources(List.of(source(branch))).isEmpty(),"4 combination layers accepted");
  invalid(Map.of("anyOf",List.of(branch,Map.of("type","string"))));check(true,"5 combination layers rejected");
  var enum64=new ArrayList<Integer>();for(int i=0;i<64;i++)enum64.add(i);
  check(ConfigSchema.checkSources(List.of(source(Map.of("type","integer","enum",enum64)))).isEmpty(),"64 enum entries accepted");
  enum64.add(64);invalid(Map.of("type","integer","enum",enum64));check(true,"65 enum entries rejected");
  var u=new ArrayList<ConfigSchemaSource>();for(int i=0;i<128;i++)u.add(source(Map.of("type","null","title","t"+i)));
  check(ConfigSchema.checkSources(u).isEmpty(),"128 unique sources accepted");
  u.add(source(Map.of("type","null","title","t128")));check(ConfigSchema.checkSources(u).orElseThrow().inputIndex()==-1,"129 unique sources rejected as collection");
 }
}
