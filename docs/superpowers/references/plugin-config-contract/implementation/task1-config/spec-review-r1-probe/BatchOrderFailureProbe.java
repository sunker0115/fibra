package com.sstlfsj.fibra.config;
import com.sstlfsj.fibra.value.LiteralValue;
import java.util.*;
public class BatchOrderFailureProbe {
 public static void main(String[] args){
  var first=ContractProbe.source(Map.of("type","string"));var second=ContractProbe.source(Map.of("type","string","default","forbidden"));
  var failure=ConfigSchema.validateAll(List.of(new ConfigValidationInput(first,LiteralValue.of(42)),new ConfigValidationInput(second,LiteralValue.of("ok")))).orElseThrow();
  if(failure.inputIndex()!=0||!failure.violation().code().equals("VALUE_MISMATCH"))throw new AssertionError("Expected index=0 VALUE_MISMATCH; actual="+failure);
 }
}
