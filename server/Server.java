import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Server {
    static Map<String, List<OutputStream>> rooms = new ConcurrentHashMap<>();
    static Map<String, List<String>> roomMembers = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/join", Server::handleJoin);
        server.createContext("/send", Server::handleSend);
        server.createContext("/create", Server::handleCreate);
        server.createContext("/members", Server::handleMembers);
        server.createContext("/", ex -> {
            String resp = "Talkie Walkie Server OK";
            ex.sendResponseHeaders(200, resp.length());
            ex.getResponseBody().write(resp.getBytes());
            ex.getResponseBody().close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Serveur démarré port " + port);
    }

    static void handleCreate(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);
        String room = params.getOrDefault("room", UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        String user = params.getOrDefault("user", "Anonyme");

        rooms.computeIfAbsent(room, k -> Collections.synchronizedList(new ArrayList<>()));
        roomMembers.computeIfAbsent(room, k -> Collections.synchronizedList(new ArrayList<>())).add(user);

        String resp = "{\"room\":\"" + room + "\",\"user\":\"" + user + "\",\"status\":\"created\"}";
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, resp.length());
        ex.getResponseBody().write(resp.getBytes());
        ex.getResponseBody().close();
        System.out.println("Salle créée: " + room + " par " + user);
    }

    static void handleJoin(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);
        String room = params.getOrDefault("room", "");
        String user = params.getOrDefault("user", "Anonyme");

        if (!rooms.containsKey(room)) {
            String resp = "{\"error\":\"Salle introuvable\"}";
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(404, resp.length());
            ex.getResponseBody().write(resp.getBytes());
            ex.getResponseBody().close();
            return;
        }

        roomMembers.get(room).add(user);
        System.out.println(user + " rejoint salle: " + room);

        ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        OutputStream out = ex.getResponseBody();
        rooms.get(room).add(out);

        synchronized (out) {
            try { out.wait(); } catch (InterruptedException e) {}
        }

        roomMembers.get(room).remove(user);
        rooms.get(room).remove(out);
        System.out.println(user + " quitté salle: " + room);
    }

    static void handleSend(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);
        String room = params.getOrDefault("room", "");
        byte[] data = ex.getRequestBody().readAllBytes();

        List<OutputStream> listeners = rooms.getOrDefault(room, new ArrayList<>());
        synchronized (listeners) {
            Iterator<OutputStream> it = listeners.iterator();
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

        String resp = "OK";
        ex.sendResponseHeaders(200, resp.length());
        ex.getResponseBody().write(resp.getBytes());
        ex.getResponseBody().close();
    }

    static void handleMembers(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String room = query != null ? query.replace("room=", "") : "";
        List<String> members = roomMembers.getOrDefault(room, new ArrayList<>());
        String resp = "{\"members\":" + members.toString().replace("[", "[\"").replace("]", "\"]").replace(", ", "\",\"") + ",\"count\":" + members.size() + "}";
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, resp.length());
        ex.getResponseBody().write(resp.getBytes());
        ex.getResponseBody().close();
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String part : query.split("&")) {
            String[] kv = part.split("=");
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }
            }
