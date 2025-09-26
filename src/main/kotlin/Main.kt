import ast.Program
import cli.Args
import cli.CLIArguments.parseArgs
import codegen.LLVMEmitter
import front.Lexer
import front.Parser
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.system.exitProcess

fun main(argv: Array<String>) {
    val args = parseArgs(argv)
    val inputPath = Path.of(args.input!!)
    val source = try {
        Files.readString(inputPath)
    } catch(e: Exception) {
        System.err.println("Error reading file ${inputPath.toAbsolutePath()}: ${e.message}")
        return
    }

    val startTime = System.currentTimeMillis()

    try {
        // Lexer
        val tokens = Lexer(source, inputPath.fileName.toString()).lex()
        if(args.emitTokens) {
            println("TOKENS (" + tokens.size + "):")
            for(token in tokens) {
                val pos = "${token.span.line}:${token.span.col}"
                val kind = token.kind.toString()
                val lex = token.lexeme.replace("\n", "\\n").replace("\t", "\\t")
                println("  $pos\t$kind\t'$lex'")
            }

            return
        }

        if(args.emitJsonTokens) {
            val json = buildString {
                append("[\n")
                tokens.forEachIndexed { i, token ->
                    val pos = "${token.span.line}:${token.span.col}"
                    val kind = token.kind.toString()
                    val lex = token.lexeme.replace("\n", "\\n").replace("\t", "\\t").replace("\"", "\\\"")
                    append("  { \"pos\": \"$pos\", \"kind\": \"$kind\", \"lexeme\": \"$lex\" }")
                    if(i < tokens.size - 1) append(",")
                    append("\n")
                }

                append("]\n")
            }

            println(json)
            return
        }

        // Parser
        val program: Program = Parser(tokens, inputPath.fileName.toString()).parseProgram()
        if(args.emitAst) {
            val json = Json { prettyPrint = true; classDiscriminator = "kind" }
            println(json.encodeToString(Program.serializer(), program))
            return
        }

        // IR generation
        val ir = LLVMEmitter().emit(program)
        if(args.emitIR) {
            println(ir)
            return
        }

        // Write the IR to a file (with the format "coal-[...].ll" if we don't keep the IR,
        // and "filename.ll" if we do, assuming `filename` is the name of the Coal source file.)
        val outPath = computeOutputBinaryPath(args, inputPath)
        val llPath =
            if(args.keepLL) {
                withExtension(outPath, "ll")
            } else {
                createTempFile("coal-", ".ll")
            }

        Files.writeString(llPath, ir)

        // Compile the IR into an executable file using clang.
        val cc = pickCompiler(args.cc)
        val cmd = if(cc == "clang") {
            listOf("clang", llPath.toString(), "-o", outPath.toString())
        } else {
            listOf("clang", llPath.toString(), "-o", outPath.toString())
        }

        // Create a process for clang.
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()

        if(code != 0) {
            System.err.println("Build failed (exit $code): Compiler output:\n$out")
            if(!args.keepLL) llPath.deleteIfExists()
            exitProcess(code)
        }

        if(!args.keepLL) llPath.deleteIfExists() // Delete the temporary file

        val endTime = System.currentTimeMillis()
        val dur = endTime - startTime
        println("Build successful: ${outPath.toAbsolutePath()} (in ${dur}ms)")
    } catch(e: RuntimeException) {
        System.err.println(e.message)
        exitProcess(2)
    }
}

private fun computeOutputBinaryPath(args: Args, inputPath: Path): Path {
    val user = args.output
    if(user != null) return Path.of(user)

    val base = inputPath.fileName.toString().substringBeforeLast('.', inputPath.fileName.toString())
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val name = base.ifEmpty { "a" }
    val withExt = if(isWindows) "$name.exe" else name
    return inputPath.parent?.resolve(withExt) ?: Path.of(withExt)
}

private fun pickCompiler(ccArg: String?): String {
    return ccArg ?: "clang"
}

private fun withExtension(p: Path, newExtNoDot: String): Path {
    val name = p.fileName.toString()
    val base = name.substringBeforeLast('.', name)
    return p.resolveSibling("$base.$newExtNoDot")
}
