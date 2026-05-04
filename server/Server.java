import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Server {
    // room -> list of audio output streams (listeners)
    static Map<String, List<OutputStream>> listeners = new ConcurrentHashMap<>();
    // room -> list of usernames
    static Map<String, Set<String>> members = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/create", Server::handleCreate);
        server.createContext("/join", Server::handleJoin);
        server.createContext("/listen", Server::handleListen);
        server.createContext("/send", Server::handleSend);
        server.createContext("/members", Server::handleMembers);
        server.createContext("/leave", Server::handleLeave);
        server.createContext("/", ex -> {
            String r = "Talkie Walkie Server OK";
            ex.sendResponseHeaders(200, r.length());
            ex.getResponseBody().write(r.getBytes());
            ex.getResponseBody().close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Serveur demarré port " + port);
    }

    static void handleCreate(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String user = p.getOrDefault("user", "Anonyme");
        String room = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        listeners.put(room, Collections.synchronizedList(new ArrayList<>()));
        members.put(room, Collections.synchronizedSet(new LinkedHashSet<>()));
        members.get(room).add(user);

        String resp = "{\"room\":\"" + room + "\",\"status\":\"created\"}";
        sendJson(ex, 200, resp);
        System.out.println("Salle créée: " + room + " par " + user);
    }

    static void handleJoin(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        String user = p.getOrDefault("user", "Anonyme");

        if (!members.containsKey(room)) {
            sendJson(ex, 404, "{\"error\":\"Salle introuvable\"}");
            return;
        }

        members.get(room).add(user);
        String resp = "{\"room\":\"" + room + "\",\"status\":\"joined\"}";
        sendJson(ex, 200, resp);
        System.out.println(user + " rejoint: " + room);
    }

    static void handleListen(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        String user = p.getOrDefault("user", "Anonyme");

        if (!listeners.containsKey(room)) {
            sendJson(ex, 404, "{\"error\":\"Salle introuvable\"}");
            return;
        }

        ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        OutputStream out = ex.getResponseBody();
        listeners.get(room).add(out);
        System.out.println(user + " écoute salle: " + room);

        // Garde connexion ouverte
        synchronized (out) {
            try { out.wait(); } catch (InterruptedException e) {}
        }

        listeners.get(room).remove(out);
        System.out.println(user + " arrêté écoute: " + room);
    }

    static void handleSend(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        byte[] data = ex.getRequestBody().readAllBytes();

        List<OutputStream> outs = listeners.getOrDefault(room, new ArrayList<>());
        System.out.println("Send " + data.length + " bytes → salle " + room + " (" + outs.size() + " écouteurs)");

        synchronized (outs) {
            Iterator<OutputStream> it = outs.iterator();
            while (it.hasNext()) {
                OutputStream out = it.next();
                try {
                    out.write(data);
                    out.flush();
                    synchronized (out) { out.notifyAll(); }
                } catch (Exception e) {
                    it.remove();
                }
            }
        }

        sendJson(ex, 200, "{\"status\":\"ok\",\"listeners\":" + outs.size() + "}");
    }

    static void handleMembers(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        Set<String> m = members.getOrDefault(room, new HashSet<>());
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (String name : m) {
            if (i++ > 0) sb.append(",");
            sb.append("\"").append(name).append("\"");
        }
        sb.append("]");
        sendJson(ex, 200, "{\"members\":" + sb + ",\"count\":" + m.size() + "}");
    }

    static void handleLeave(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        String user = p.getOrDefault("user", "");
        if (members.containsKey(room)) members.get(room).remove(user);
        sendJson(ex, 200, "{\"status\":\"left\"}");
    }

    static void sendJson(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, body.getBytes().length);
        ex.getResponseBody().write(body.getBytes());
        ex.getResponseBody().close();
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String part : query.split("&")) {
            String[] kv = part.split("=");
            if (kv.length == 2) map.put(kv[0], URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        return map;
    }
}
