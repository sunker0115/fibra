package com.sstlfsj.fibra.plugins.fs.search;

import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchOutputParserTest {
    @Test
    void parsesOnlyMatchRecordsAndPreservesOneBasedLineNumbers() {
        var output = String.join("\n",
            "{\"type\":\"begin\",\"data\":{}}",
            "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"src/A.java\"},\"lines\":{\"text\":\"hello\\n\"},\"line_number\":7}}",
            "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"binary.dat\"},\"lines\":{\"bytes\":\"AA==\"},\"line_number\":2}}",
            "{\"type\":\"summary\",\"data\":{}}", "");

        assertEquals(List.of(
            new SearchMatch("src/A.java", 7, "hello"),
            new SearchMatch("binary.dat", 2, "(line is not valid UTF-8)")),
            SearchOutputParser.grep(output));
    }

    @Test
    void malformedJsonFailsInsteadOfReturningPartialMatches() {
        var failure = assertThrows(ToolException.class,
            () -> SearchOutputParser.grep("{not-json}"));
        assertEquals(ToolFailureCode.SEARCH_FAILED, failure.failure().code());
    }

    @Test
    void parsesGlobLinesWithoutInventingResultsForEmptyOutput() {
        assertEquals(List.of("a.txt", "dir/b.txt"),
            SearchOutputParser.glob("a.txt\ndir/b.txt\n"));
        assertEquals(List.of(), SearchOutputParser.glob(""));
    }
}
