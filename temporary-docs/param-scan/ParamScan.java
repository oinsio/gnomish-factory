import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Parameter-count scan for introduce-take-order tasks 0.2 / 5.2.
 * Counts every method and constructor declaration in `** /src/main/java/**.java` with more
 * than 7 parameters. Excluded by construction: members declared inside a record (the record
 * exemption) and methods annotated @Override. Parse-only (no classpath needed).
 * Usage: java temporary-docs/param-scan/ParamScan.java [repoRoot] [limit]
 */
public class ParamScan {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 7;
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .filter(p -> !p.toString().contains("/temporary-docs/"))
                    .sorted().toList();
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null);
        JavacTask task = (JavacTask) javac.getTask(null, fm, d -> {}, List.of("-proc:none"), null,
                fm.getJavaFileObjectsFromPaths(files));
        List<String> hits = new ArrayList<>();
        for (CompilationUnitTree cu : task.parse()) {
            Path file = root.toAbsolutePath().normalize()
                    .relativize(Path.of(cu.getSourceFile().toUri()).normalize());
            LineMap lines = cu.getLineMap();
            String src = cu.getSourceFile().getCharContent(true).toString();
            SourcePositions pos = Trees.instance(task).getSourcePositions();
            new TreePathScanner<Void, Deque<Boolean>>() {
                @Override public Void visitClass(ClassTree c, Deque<Boolean> inRecord) {
                    inRecord.push(c.getKind() == Tree.Kind.RECORD);
                    super.visitClass(c, inRecord);
                    inRecord.pop();
                    return null;
                }
                @Override public Void visitMethod(MethodTree m, Deque<Boolean> inRecord) {
                    int n = m.getParameters().size();
                    boolean override = m.getModifiers().getAnnotations().stream()
                            .anyMatch(a -> a.getAnnotationType().toString().endsWith("Override"));
                    if (n > limit && !override && !Boolean.TRUE.equals(inRecord.peek())) {
                        String name = m.getName().contentEquals("<init>") ? "<ctor>" : m.getName().toString();
                        long start = pos.getStartPosition(cu, m);
                        int at = src.indexOf((name.equals("<ctor>") ? m.getParameters().get(0).getType().toString() : name + "("), (int) start);
                        long line = lines.getLineNumber(at >= 0 && name.equals("<ctor>") ? src.lastIndexOf('(', at) : at >= 0 ? at : start);
                        hits.add(file + ":" + line + " " + name + " (" + n + ")");
                    }
                    return super.visitMethod(m, inRecord);
                }
            }.scan(cu, new ArrayDeque<>());
        }
        hits.forEach(System.out::println);
        System.out.println("TOTAL " + hits.size());
    }
}
