import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Server {
    static Map<String, LinkedBlockingQueue<byte[]>> audioQueues = new ConcurrentHashMap<>();
    static Map<String, Set<String>> members = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/create", Server::handleCreate);
        server.createContext("/join", Server::handleJoin);
        server.createContext("/send", Server::handleSend);
        server.createContext("/poll", Server::handlePoll);
        server.createContext("/members", Server::handleMembers);
        server.createContext("/leave", Server::handleLeave);
        server.createContext("/", ex -> {
            String r = "Talkie Walkie Server V2";
            ex.sendResponseHeaders(200, r.length());
            ex.getResponseBody().write(r.getBytes());
            ex.getResponseBody().close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Serveur V2 démarré port " + port);
    }

    static void handleCreate(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String user = p.getOrDefault("user", "Anonyme");
        String room = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        audioQueues.put(room, new LinkedBlockingQueue<>(200));
        members.put(room, Collections.synchronizedSet(new LinkedHashSet<>()));
        members.get(room).add(user);
        sendJson(ex, 200, "{\"room\":\"" + room + "\",\"status\":\"created\"}");
        System.out.println("Salle créée: " + room);
    }

    static void handleJoin(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        String user = p.getOrDefault("user", "Anonyme");
        if (!audioQueues.containsKey(room)) {
            sendJson(ex, 404, "{\"error\":\"Salle introuvable\"}");
            return;
        }
        members.get(room).add(user);
        sendJson(ex, 200, "{\"room\":\"" + room + "\",\"status\":\"joined\"}");
        System.out.println(user + " rejoint: " + room);
    }

    static void handleSend(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        byte[] data = ex.getRequestBody().readAllBytes();
        LinkedBlockingQueue<byte[]> queue = audioQueues.get(room);
        if (queue != null && data.length > 0) {
            if (queue.size() >= 200) queue.poll();
            queue.offer(data);
            System.out.println("Audio: " + data.length + " bytes → " + room + " queue:" + queue.size());
        }
        sendJson(ex, 200, "{\"status\":\"ok\"}");
    }

    static void handlePoll(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        LinkedBlockingQueue<byte[]> queue = audioQueues.get(room);
        if (queue == null) {
            sendJson(ex, 404, "{\"error\":\"Salle introuvable\"}");
            return;
        }
        try {
            byte[] data = queue.poll(1000, TimeUnit.MILLISECONDS);
            if (data != null) {
                ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
                ex.sendResponseHeaders(200, data.length);
                ex.getResponseBody().write(data);
                ex.getResponseBody().close();
                System.out.println("Poll: envoyé " + data.length + " bytes → " + room);
            } else {
                ex.sendResponseHeaders(204, -1);
            }
        } catch (InterruptedException e) {
            ex.sendResponseHeaders(204, -1);
        }
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
        byte[] bytes = body.getBytes();
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String part : query.split("&")) {
            String[] kv = part.split("=");
            if (kv.length == 2) {
                try { map.put(kv[0], URLDecoder.decode(kv[1], "UTF-8")); }
                catch (Exception e) { map.put(kv[0], kv[1]); }
            }
        }
        return map;
    }
}
