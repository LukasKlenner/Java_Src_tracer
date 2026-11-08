package srctracer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static srctracer.util.JavaParserUtil.hasJmlContract;
import static srctracer.util.JavaParserUtil.isMainMethod;

/**
 * The Java source files of one program, collected from files and directories given on the command line,
 * together with the entry class: the class declaring the entry method (main, fuzzerTestOneInput, ...).
 * <p>
 * Directories are searched recursively; {@code *.instrumented.java} files found there are skipped.
 * {@code <X>Assert.java} files next to an input {@code <X>.java} are always skipped: they declare the same class
 * and are only used by {@link #withAssertVariant()}.
 * <p>
 * The methods declared in the files are recorded by name, so that the method to trace and verify can be selected
 * by its JML contract ({@link #contractMethodName()}) and checked to be unique ({@link #declaringFile(String)}).
 */
public final class ProgramSources {

    private static final String JAVA_SUFFIX = ".java";
    private static final String ASSERT_SUFFIX = "Assert.java";
    private static final String INSTRUMENTED_SUFFIX = ".instrumented.java";

    /**
     * @param path         the file on disk
     * @param relativePath package directories + file name, e.g. {@code a/b/Foo.java}; used for output files
     */
    public record SourceFile(Path path, Path relativePath) {
    }

    /** A method declaration; {@code className} is the binary class name, e.g. {@code a.b.Outer$Inner}. */
    private record MethodRef(String className, String methodName, SourceFile file, boolean hasContract) {
        String displayName() {
            return className + "." + methodName;
        }
    }

    private final List<SourceFile> files;
    private final SourceFile entryFile;
    private final String entryClassName;
    private final List<Path> sourceRoots;
    private final List<MethodRef> methods;

    private ProgramSources(List<SourceFile> files, SourceFile entryFile, String entryClassName, List<Path> sourceRoots,
                           List<MethodRef> methods) {
        this.files = files;
        this.entryFile = entryFile;
        this.entryClassName = entryClassName;
        this.sourceRoots = sourceRoots;
        this.methods = methods;
    }

    /**
     * @param inputs          .java files and directories
     * @param entryMethodName name of the entry method; {@code main} only matches {@code static void main(String[])}
     * @param entryRequired   if false, a missing or ambiguous entry method is not an error and {@link #entryFile()} is null
     */
    public static ProgramSources collect(List<Path> inputs, String entryMethodName, boolean entryRequired) throws IOException {
        Set<Path> paths = new LinkedHashSet<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                paths.addAll(javaFilesIn(input));
            } else if (Files.isRegularFile(input)) {
                paths.add(input.toAbsolutePath().normalize());
            } else {
                throw new IllegalArgumentException("Input not found: " + input);
            }
        }
        paths.removeIf(p -> isAssertVariant(p, paths));
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("No .java files found in " + inputs);
        }

        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(Config.DEFAULT_SOURCE_LANGUAGE_LEVEL));
        List<SourceFile> files = new ArrayList<>();
        Map<Path, Path> pathByRelative = new HashMap<>();
        Set<Path> sourceRoots = new LinkedHashSet<>();
        List<SourceFile> entryFiles = new ArrayList<>();
        Set<String> entryClasses = new TreeSet<>();
        Map<String, Path> fileByTopLevelType = new HashMap<>();
        List<MethodRef> methods = new ArrayList<>();

        for (Path path : paths) {
            ParseResult<CompilationUnit> result = parser.parse(path);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                throw new IllegalArgumentException("Cannot parse " + path + ": " + result.getProblems());
            }
            CompilationUnit cu = result.getResult().get();
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

            Path relative = pkg.isEmpty()
                    ? Path.of(path.getFileName().toString())
                    : Path.of(pkg.replace('.', '/')).resolve(path.getFileName().toString());
            Path previous = pathByRelative.putIfAbsent(relative, path);
            if (previous != null) {
                throw new IllegalArgumentException("Two input files map to " + relative + ": " + previous + " and " + path);
            }
            for (TypeDeclaration<?> td : cu.getTypes()) {
                String typeName = pkg.isEmpty() ? td.getNameAsString() : pkg + "." + td.getNameAsString();
                Path other = fileByTopLevelType.putIfAbsent(typeName, path);
                if (other != null) {
                    throw new IllegalArgumentException("Class " + typeName + " is declared in " + other + " and " + path);
                }
            }
            SourceFile file = new SourceFile(path, relative);
            files.add(file);
            sourceRoots.add(sourceRoot(path, pkg));

            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                methods.add(new MethodRef(binaryClassName(md, pkg), md.getNameAsString(), file, hasJmlContract(md)));
                if (isEntryMethod(md, entryMethodName)) {
                    if (entryClasses.add(binaryClassName(md, pkg))) {
                        entryFiles.add(file);
                    }
                }
            }
        }

        if (entryClasses.size() != 1) {
            if (!entryRequired) {
                return new ProgramSources(List.copyOf(files), null, null, List.copyOf(sourceRoots), List.copyOf(methods));
            }
            throw new IllegalArgumentException(entryClasses.isEmpty()
                    ? "No class declares a " + entryMethodName + " method in " + inputs
                    : "More than one class declares a " + entryMethodName + " method: " + String.join(", ", entryClasses));
        }
        return new ProgramSources(List.copyOf(files), entryFiles.getFirst(), entryClasses.iterator().next(),
                List.copyOf(sourceRoots), List.copyOf(methods));
    }

    public List<SourceFile> files() {
        return files;
    }

    /** The file declaring the entry class, or null if no unique entry class was found (see {@link #collect}). */
    public SourceFile entryFile() {
        return entryFile;
    }

    /** Binary name of the entry class (for class loading), e.g. {@code a.b.Foo} or {@code a.b.Outer$Inner}. */
    public String entryClassName() {
        return entryClassName;
    }

    /** Short name used for output files: the simple entry class name, or the first file's name without an entry class. */
    public String displayName() {
        if (entryClassName != null) {
            String simple = entryClassName.substring(entryClassName.lastIndexOf('.') + 1);
            return simple.substring(simple.lastIndexOf('$') + 1);
        }
        String fileName = files.getFirst().path().getFileName().toString();
        return fileName.substring(0, fileName.length() - JAVA_SUFFIX.length());
    }

    /** Directories from which the package structure of the input files starts (for symbol resolution). */
    public List<Path> sourceRoots() {
        return sourceRoots;
    }

    /**
     * The same program with the entry file {@code <X>.java} replaced by {@code <X>Assert.java} from the same directory.
     * The Assert file must declare the same entry class.
     */
    public ProgramSources withAssertVariant() {
        Path entry = entryFile.path();
        String fileName = entry.getFileName().toString();
        Path assertPath = entry.resolveSibling(fileName.substring(0, fileName.length() - JAVA_SUFFIX.length()) + ASSERT_SUFFIX);
        if (!Files.isRegularFile(assertPath)) {
            throw new IllegalArgumentException("Assert variant of " + entry + " not found: " + assertPath);
        }
        SourceFile assertFile = new SourceFile(assertPath, entryFile.relativePath());
        List<SourceFile> replaced = files.stream().map(f -> f == entryFile ? assertFile : f).toList();
        // the methods of the Assert file are not recorded; they are only needed for selecting the traced method
        List<MethodRef> otherMethods = methods.stream().filter(m -> m.file() != entryFile).toList();
        return new ProgramSources(replaced, assertFile, entryClassName, sourceRoots, otherMethods);
    }

    /**
     * The same program without the entry file, e.g. without the fuzz harness when only the code under test is
     * traced and verified. The entry class name is kept (for output file names), {@link #entryFile()} is null.
     */
    public ProgramSources withoutEntryFile() {
        List<SourceFile> others = files.stream().filter(f -> f != entryFile).toList();
        if (others.isEmpty()) {
            throw new IllegalArgumentException("No input file besides " + entryFile.path());
        }
        List<MethodRef> otherMethods = methods.stream().filter(m -> m.file() != entryFile).toList();
        return new ProgramSources(others, null, entryClassName, sourceRoots, otherMethods);
    }

    /**
     * Name of the one method outside the entry file that has a JML contract ({@code /*@ ... @*}{@code /}).
     * This is the method that is traced and verified when no start method is given explicitly.
     */
    public String contractMethodName() {
        List<MethodRef> withContract = methods.stream()
                .filter(m -> m.hasContract() && m.file() != entryFile)
                .toList();
        if (withContract.isEmpty()) {
            throw new IllegalArgumentException("No method with a JML contract found"
                    + (entryFile == null ? "" : " outside " + entryFile.path().getFileName())
                    + "; add a contract to the method under test or select it with --start-method");
        }
        if (withContract.size() > 1) {
            throw new IllegalArgumentException("More than one method has a JML contract: "
                    + String.join(", ", withContract.stream().map(MethodRef::displayName).toList())
                    + "; select the method under test with --start-method");
        }
        return withContract.getFirst().methodName();
    }

    /**
     * The file declaring the method with the given name. Methods are selected by name only (for tracing and for
     * the proof obligation), so the name must be declared exactly once in the whole program, overloads included.
     */
    public SourceFile declaringFile(String methodName) {
        List<MethodRef> declarations = methods.stream().filter(m -> m.methodName().equals(methodName)).toList();
        if (declarations.isEmpty()) {
            throw new IllegalArgumentException("No method " + methodName + " declared in "
                    + files.stream().map(SourceFile::path).toList());
        }
        if (declarations.size() > 1) {
            throw new IllegalArgumentException("Method name " + methodName + " is declared " + declarations.size()
                    + " times (" + String.join(", ", declarations.stream().map(MethodRef::displayName).toList())
                    + "); the traced method must have a unique name, overloads included");
        }
        return declarations.getFirst().file();
    }

    private static Set<Path> javaFilesIn(Path dir) throws IOException {
        Set<Path> found = new TreeSet<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.toAbsolutePath().normalize())
                    .filter(p -> p.getFileName().toString().endsWith(JAVA_SUFFIX))
                    .filter(p -> !p.getFileName().toString().endsWith(INSTRUMENTED_SUFFIX))
                    .forEach(found::add);
        }
        return found;
    }

    private static boolean isAssertVariant(Path path, Set<Path> files) {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(ASSERT_SUFFIX) || fileName.equals(ASSERT_SUFFIX)) {
            return false;
        }
        String base = fileName.substring(0, fileName.length() - ASSERT_SUFFIX.length()) + JAVA_SUFFIX;
        return files.contains(path.resolveSibling(base));
    }

    private static boolean isEntryMethod(MethodDeclaration md, String entryMethodName) {
        if (entryMethodName.equals("main")) {
            return isMainMethod(md);
        }
        return md.getNameAsString().equals(entryMethodName);
    }

    private static String binaryClassName(MethodDeclaration md, String pkg) {
        List<String> names = new ArrayList<>();
        Optional<Node> parent = md.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof TypeDeclaration<?> td) {
                names.addFirst(td.getNameAsString());
            }
            parent = parent.get().getParentNode();
        }
        String nested = String.join("$", names);
        return pkg.isEmpty() ? nested : pkg + "." + nested;
    }

    /** The directory of {@code path} without the package directories, if the file is laid out by package. */
    private static Path sourceRoot(Path path, String pkg) {
        Path dir = path.getParent();
        if (pkg.isEmpty()) {
            return dir;
        }
        Path pkgPath = Path.of(pkg.replace('.', '/'));
        if (!dir.endsWith(pkgPath)) {
            return dir;
        }
        Path root = dir;
        for (int i = 0; i < pkgPath.getNameCount(); i++) {
            root = root.getParent();
        }
        return root;
    }
}
