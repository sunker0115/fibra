import java.util.*;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.value.LiteralValue;
public class BatchCopyProbe {
  public static void main(String[] args) {
    var source = new ConfigSchemaSource("fibra-config-1", (LiteralValue.ObjectValue)LiteralValue.of(Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "type", "string")));
    var input = new ConfigValidationInput(source, LiteralValue.of("x".repeat(65537)));
    System.out.println("constant-space input constructed; count="+args[0]);
    System.out.println(ConfigSchema.validateAll(Collections.nCopies(Integer.parseInt(args[0]), input)));
  }
}
