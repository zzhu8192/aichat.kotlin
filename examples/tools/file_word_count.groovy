// An example of a kotllm tool that reads a file and counts words/lines.
// Copy this file into <config dir>/tools/ to make it available to the model.

import kotllm.tools.Tool
import kotllm.tools.ToolResult

def schema = Tool.schema('''
{
  "type": "object",
  "properties": {
    "file_path": { "type": "string",  "description": "Path to the file to count words in." },
    "unique":    { "type": "boolean", "description": "Count distinct words instead of every occurrence." }
  },
  "required": ["file_path"]
}
''')

new Tool(
    "file_word_count",
    "Counts words and lines in a file. unique=true counts distinct words.",
    schema,
    { args -> "count words in " + (Tool.optStr(args, "file_path") ?: "") },
    { args ->
        def pathStr = Tool.optStr(args, "file_path")
        if (pathStr == null) {
            return new ToolResult("missing required argument 'file_path'", true)
        }
        def file = new File(pathStr)
        if (!file.exists()) {
            return new ToolResult("file does not exist: " + pathStr, true)
        }
        def lines = file.readLines()
        def words = lines.join(" ").trim().split(/\s+/).findAll { it }
        def lineCount = lines.size()
        def isUnique = Tool.optBool(args, "unique")
        if (isUnique) {
            def distinct = words*.toLowerCase().unique().size()
            return new ToolResult("${distinct} distinct word(s), ${lineCount} line(s)", false)
        } else {
            def wordCount = words.size()
            return new ToolResult("${wordCount} word(s), ${lineCount} line(s)", false)
        }
    },
    true
)
