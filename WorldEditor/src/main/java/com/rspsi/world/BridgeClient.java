package com.rspsi.world;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** One ordered request stream. The backend can run locally or through SSH. */
public final class BridgeClient implements AutoCloseable {
    private final Process process;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final StringBuilder diagnostics = new StringBuilder();
    private long sequence;

    public BridgeClient(List<String> command) throws IOException {
        if (command.isEmpty()) throw new IOException("Configure a backend command first");
        process = new ProcessBuilder(command).start();
        input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread errors = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (diagnostics) {
                        diagnostics.append(line).append('\n');
                        if (diagnostics.length() > 12000) diagnostics.delete(0, diagnostics.length() - 12000);
                    }
                }
            } catch (IOException ignored) { /* Process shutdown closes this pipe. */ }
        }, "map-editor-diagnostics");
        errors.setDaemon(true);
        errors.start();
    }

    public synchronized JsonObject call(String action, JsonObject body, Consumer<String> progress) throws IOException {
        long id = ++sequence;
        JsonObject request = object("id", id, "action", action, "body", body);
        output.write(request.toString()); output.newLine(); output.flush();
        for (;;) {
            String line = input.readLine();
            if (line == null) {
                String log;
                synchronized (diagnostics) { log = diagnostics.toString(); }
                throw new IOException("Map backend stopped" + (log.isEmpty() ? "" : ":\n" + log));
            }
            JsonObject response;
            try { response = JsonParser.parseString(line).getAsJsonObject(); }
            catch (RuntimeException error) { throw new IOException("Backend returned an invalid response", error); }
            if (response.has("event")) {
                if (response.get("id").getAsLong() != id) throw new IOException("Backend progress belongs to another request");
                progress.accept(response.get("message").getAsString()); continue;
            }
            if (!response.has("id") || response.get("id").isJsonNull())
                throw new IOException(response.has("error") ? response.get("error").getAsString() : "Backend could not start");
            if (response.get("id").getAsLong() != id) throw new IOException("Backend response belongs to another request");
            if (!response.get("ok").getAsBoolean()) throw new IOException(response.get("error").getAsString());
            return response.getAsJsonObject("result");
        }
    }

    public static JsonObject object(Object... values) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < values.length; i += 2) {
            Object value = values[i + 1]; String key = (String) values[i];
            if (value instanceof JsonElement) result.add(key, (JsonElement) value);
            else if (value instanceof Number) result.addProperty(key, (Number) value);
            else if (value instanceof Boolean) result.addProperty(key, (Boolean) value);
            else if (value == null) result.add(key, JsonNull.INSTANCE);
            else result.addProperty(key, value.toString());
        }
        return result;
    }

    public static List<String> command(String json) {
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString() || element.getAsString().isEmpty())
                throw new IllegalArgumentException("Backend command must be a JSON array of nonempty arguments");
            result.add(element.getAsString());
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Backend command is empty");
        return result;
    }

    public static Path home() {
        String configured = System.getenv("XYREN_EDITOR_HOME");
        if (configured != null) return Paths.get(configured).toAbsolutePath();
        List<Path> roots = new ArrayList<>();
        roots.add(Paths.get("").toAbsolutePath());
        try { roots.add(Paths.get(BridgeClient.class.getProtectionDomain().getCodeSource().getLocation().toURI())); }
        catch (Exception ignored) { /* Development builds also resolve from cwd. */ }
        for (Path root : roots) {
            for (Path p = root; p != null; p = p.getParent()) {
                if (Files.isRegularFile(p.resolve("tools/xyren_bridge.py"))) return p;
            }
        }
        return Paths.get("").toAbsolutePath();
    }

    public static String defaultCommand() {
        JsonArray command = new JsonArray();
        command.add("python3"); command.add("-u"); command.add(home().resolve("tools/xyren_bridge.py").toString());
        return command.toString();
    }

    @Override public void close() {
        try {
            output.close(); // EOF lets the backend release its owned export stages.
            if(!process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))process.destroy();
        } catch(InterruptedException error) {Thread.currentThread().interrupt();process.destroy();}
        catch(IOException error) {process.destroy();}
        finally {try {input.close();}catch(IOException ignored) { /* Shutdown. */ }}
    }
}
