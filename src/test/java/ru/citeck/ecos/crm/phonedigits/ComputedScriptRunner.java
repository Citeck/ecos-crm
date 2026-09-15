package ru.citeck.ecos.crm.phonedigits;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.yaml.snakeyaml.Yaml;

/**
 * Executes a computed SCRIPT attribute taken straight from a type YAML on a real GraalJS engine.
 *
 * <p>The YAML stays the single source of truth: tests never keep a reference copy of the script.
 * The execution environment mirrors {@code ru.citeck.ecos.commons.utils.script.ScriptExecutorImpl}
 * and {@code AttValueScriptCtxImpl} of the platform:
 * <ul>
 *     <li>the same host access configuration and no engine options besides the platform ones,</li>
 *     <li>the same {@code (function(){...})()} wrapping rule, applied only when the script
 *         contains the substring {@code "return "},</li>
 *     <li>{@code value.load(...)} returns plain java collections, wrapped by GraalJS the same way
 *         as in production - see ComputedScriptRunnerTest for what a script may rely on.</li>
 * </ul>
 */
public final class ComputedScriptRunner {

    private static final String TYPES_DIR = "src/main/resources/app/artifacts/model/type";

    private final String script;
    private final String source;

    private ComputedScriptRunner(String script, String source) {
        this.script = script;
        this.source = source;
    }

    /** Type YAML of this project by type id, e.g. {@code typeFile("opportunity")}. */
    public static Path typeFile(String typeId) {
        return Paths.get(TYPES_DIR, typeId + ".yml").toAbsolutePath();
    }

    /**
     * Type ids of this project whose {@code parentRef} is the given ref, in alphabetical order.
     *
     * <p>Derived from the type directory rather than listed by hand: a child added to the project
     * is picked up by every check built on this list instead of being silently left out of it.
     */
    public static List<String> childTypeIds(String parentRef) {
        List<String> ids = new ArrayList<>();
        try (java.util.stream.Stream<Path> typeFiles = Files.list(Paths.get(TYPES_DIR))) {
            typeFiles.filter(file -> file.getFileName().toString().endsWith(".yml"))
                .forEach(file -> {
                    if (parentRef.equals(loadYaml(file.toAbsolutePath()).get("parentRef"))) {
                        String fileName = file.getFileName().toString();
                        ids.add(fileName.substring(0, fileName.length() - ".yml".length()));
                    }
                });
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Can't list type files in " + TYPES_DIR, e);
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    /** Runner over the {@code computed.config.fn} of the given attribute of the given type file. */
    public static ComputedScriptRunner ofTypeAttribute(Path typeFile, String attId) {
        return new ComputedScriptRunner(readAttributeScript(typeFile, attId), typeFile + "#" + attId);
    }

    /** Runner over an inline script - used to test the harness itself. */
    public static ComputedScriptRunner ofScript(String script) {
        return new ComputedScriptRunner(script, "<inline>");
    }

    public String getScript() {
        return script;
    }

    /**
     * Reads {@code model.attributes[id=attId].computed.config.fn} from a type YAML.
     *
     * @throws IllegalArgumentException with a self-explanatory message if the file, the attribute
     *         or the script is missing - never an NPE.
     */
    public static String readAttributeScript(Path typeFile, String attId) {

        if (!Files.isRegularFile(typeFile)) {
            throw new IllegalArgumentException("Type file is not found: " + typeFile);
        }

        Map<String, Object> type = loadYaml(typeFile);

        Object model = type.get("model");
        if (!(model instanceof Map)) {
            throw new IllegalArgumentException("Type file has no 'model' section: " + typeFile);
        }
        Object attributes = ((Map<?, ?>) model).get("attributes");
        if (!(attributes instanceof List)) {
            throw new IllegalArgumentException("Type file has no 'model.attributes' list: " + typeFile);
        }

        List<String> knownAtts = new ArrayList<>();
        for (Object attObj : (List<?>) attributes) {
            if (!(attObj instanceof Map)) {
                continue;
            }
            Map<?, ?> att = (Map<?, ?>) attObj;
            String id = String.valueOf(att.get("id"));
            knownAtts.add(id);
            if (!id.equals(attId)) {
                continue;
            }
            Object computed = att.get("computed");
            if (!(computed instanceof Map)) {
                throw new IllegalArgumentException(
                    "Attribute '" + attId + "' in " + typeFile + " has no 'computed' section"
                );
            }
            Object config = ((Map<?, ?>) computed).get("config");
            Object fn = config instanceof Map ? ((Map<?, ?>) config).get("fn") : null;
            if (!(fn instanceof String) || ((String) fn).isBlank()) {
                throw new IllegalArgumentException(
                    "Attribute '" + attId + "' in " + typeFile + " has no 'computed.config.fn' script"
                );
            }
            return (String) fn;
        }

        throw new IllegalArgumentException(
            "Attribute '" + attId + "' is not found in " + typeFile + ". Known attributes: " + knownAtts
        );
    }

    /**
     * Runs the script with the given record attributes and returns the result converted to java
     * the same way the platform converts it.
     *
     * @param atts values returned by {@code value.load(...)}, keyed by the attribute the script asks for
     */
    public Object execute(Map<String, Object> atts) {
        try (Context context = newContext()) {
            context.getBindings("js").putMember("value", new ValueStub(atts, source));
            context.getBindings("js").putMember("log", new LogStub());
            return convertToJava(context.eval("js", prepareScript(script)));
        } catch (PolyglotException e) {
            // a failure of the value stub itself is a fixture problem - report it as it was thrown,
            // without the polyglot wrapper hiding the message
            if (e.isHostException() && e.asHostException() instanceof RuntimeException) {
                throw (RuntimeException) e.asHostException();
            }
            throw e;
        }
    }

    /**
     * Same as {@link #execute(Map)}, but shaped like a TEXT attribute value: a list of strings for
     * an array result, a single-element list for a scalar result and {@code null} for a null result -
     * so that "empty array" and "no value" stay distinguishable.
     */
    public List<String> executeToStringList(Map<String, Object> atts) {
        Object result = execute(atts);
        if (result == null) {
            return null;
        }
        List<String> strings = new ArrayList<>();
        if (result instanceof Collection) {
            for (Object element : (Collection<?>) result) {
                strings.add(element == null ? null : String.valueOf(element));
            }
        } else {
            strings.add(String.valueOf(result));
        }
        return strings;
    }

    private static Context newContext() {
        HostAccess hostAccess = HostAccess.newBuilder(HostAccess.SCOPED)
            .allowMapAccess(true)
            .allowListAccess(true)
            .allowArrayAccess(true)
            .allowBigIntegerNumberAccess(true)
            .allowIterableAccess(true)
            .allowIteratorAccess(true)
            .allowAccessInheritance(true)
            .build();
        return Context.newBuilder("js")
            .option("engine.WarnInterpreterOnly", "false")
            .allowHostAccess(hostAccess)
            .build();
    }

    /** Copy of ScriptExecutorImpl.prepareScript: the wrapper needs "return " - with a space. */
    private static String prepareScript(String script) {
        return script.contains("return ") ? "(function(){" + script + "})()" : script;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException("Type file is not a yaml object: " + file);
            }
            return (Map<String, Object>) loaded;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Type file can't be read: " + file, e);
        }
    }

    /** Copy of ScriptUtils.convertToJava, limited to the shapes a computed attribute can return. */
    private static Object convertToJava(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Value)) {
            if (value instanceof Collection) {
                List<Object> result = new ArrayList<>();
                for (Object element : (Collection<?>) value) {
                    result.add(convertToJava(element));
                }
                return result;
            }
            if (value instanceof Map) {
                Map<Object, Object> result = new LinkedHashMap<>();
                ((Map<?, ?>) value).forEach((k, v) -> result.put(k, convertToJava(v)));
                return result;
            }
            return value;
        }
        Value polyglot = (Value) value;
        if (polyglot.isNull()) {
            return null;
        }
        if (polyglot.isHostObject()) {
            return convertToJava(polyglot.asHostObject());
        }
        if (polyglot.isString()) {
            return polyglot.asString();
        }
        if (polyglot.isBoolean()) {
            return polyglot.asBoolean();
        }
        if (polyglot.isNumber()) {
            return polyglot.as(Number.class);
        }
        if (polyglot.hasArrayElements()) {
            List<Object> result = new ArrayList<>();
            for (long i = 0; i < polyglot.getArraySize(); i++) {
                result.add(convertToJava(polyglot.getArrayElement(i)));
            }
            return result;
        }
        if (polyglot.hasMembers()) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (String key : polyglot.getMemberKeys()) {
                result.put(key, convertToJava(polyglot.getMember(key)));
            }
            return result;
        }
        return polyglot.as(Object.class);
    }

    /** Copy of ScriptUtils.convertToScript: java collections, not native js objects. */
    private static Object convertToScript(Object value) {
        if (value instanceof Collection) {
            List<Object> converted = new ArrayList<>();
            for (Object element : (Collection<?>) value) {
                converted.add(convertToScript(element));
            }
            return converted;
        }
        if (value instanceof Map) {
            Map<Object, Object> converted = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((k, v) -> converted.put(k, convertToScript(v)));
            return converted;
        }
        return value;
    }

    /** Stand-in for AttValueScriptCtxImpl - serves attributes from a fixture map. */
    public static final class ValueStub {

        private final Map<String, Object> atts;
        private final String source;

        private ValueStub(Map<String, Object> atts, String source) {
            this.atts = atts;
            this.source = source;
        }

        @HostAccess.Export
        public Object load(Object attributes) {
            Object requested = convertToJava(attributes);
            if (requested instanceof String) {
                return convertToScript(attValue((String) requested));
            }
            Map<Object, Object> result = new LinkedHashMap<>();
            if (requested instanceof Collection) {
                for (Object att : (Collection<?>) requested) {
                    result.put(att, convertToScript(attValue(String.valueOf(att))));
                }
                return result;
            }
            if (requested instanceof Map) {
                ((Map<?, ?>) requested).forEach(
                    (key, att) -> result.put(key, convertToScript(attValue(String.valueOf(att))))
                );
                return result;
            }
            throw new IllegalArgumentException("Incorrect attributes object: " + requested);
        }

        /**
         * Unknown attributes are rejected instead of silently returning null: in a test an
         * attribute the fixture does not declare always means the fixture and the script disagree.
         */
        private Object attValue(String att) {
            if (!atts.containsKey(att)) {
                throw new IllegalArgumentException(
                    "Script " + source + " loads attribute '" + att
                        + "' which is not declared by the test fixture. Declared: " + atts.keySet()
                );
            }
            return atts.get(att);
        }

        @HostAccess.Export
        @Override
        public String toString() {
            return "ScriptRecord(" + source + ")";
        }
    }

    /** Stand-in for ScriptLogger - keeps scripts that log from failing the run. */
    public static final class LogStub {

        @HostAccess.Export
        public void debug(Object message) {
            // no-op
        }

        @HostAccess.Export
        public void info(Object message) {
            // no-op
        }

        @HostAccess.Export
        public void warn(Object message) {
            // no-op
        }

        @HostAccess.Export
        public void error(Object message) {
            // no-op
        }
    }
}
