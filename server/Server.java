import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Server {

    // room -> (username -> queue personnelle)
    static Map<String, Map<String, LinkedBlockingQueue<byte[]>>> userQueues = new ConcurrentHashMap<>();
    // room -> membres (ordre insertion)
    static Map<String, Set<String>> members = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/create",  Server::handleCreate);
        server.createContext("/join",    Server::handleJoin);
        server.createContext("/send",    Server::handleSend);
        server.createContext("/poll",    Server::handlePoll);
        server.createContext("/members", Server::handleMembers);
        server.createContext("/leave",   Server::handleLeave);
        server.createContext("/", ex -> {
            String r = "Talkie Walkie Server V3";
            ex.sendResponseHeaders(200, r.length());
            ex.getResponseBody().write(r.getBytes());
            ex.getResponseBody().close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Serveur V3 démarré port " + port);
    }

    // ─── CRÉER SALLE ────────────────────────────────────────────────────────────

    static void handleCreate(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String user = p.getOrDefault("user", "Anonyme");
        String room = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        Map<String, LinkedBlockingQueue<byte[]>> queues = new ConcurrentHashMap<>();
        queues.put(user, new LinkedBlockingQueue<>(300));
        userQueues.put(room, queues);

        Set<String> m = Collections.synchronizedSet(new LinkedHashSet<>());
        m.add(user);
        members.put(room, m);

        sendJson(ex, 200, "{\"room\":\"" + room + "\",\"status\":\"created\"}");
        System.out.println("Salle créée: " + room + " par " + user);
    }

    // ─── REJOINDRE SALLE ────────────────────────────────────────────────────────

    static void handleJoin(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        String user = p.getOrDefault("user", "Anonyme");

        if (!userQueues.containsKey(room)) {
            sendJson(ex, 404, "{\"error\":\"Salle introuvable\"}");
            return;
        }

        // Créer une queue perso pour ce nouvel utilisateur
        userQueues.get(room).putIfAbsent(user, new LinkedBlockingQueue<>(300));
        members.get(room).add(user);

        sendJson(ex, 200, "{\"room\":\"" + room + "\",\"status\":\"joined\"}");
        System.out.println(user + " rejoint: " + room);
    }

    // ─── ENVOYER AUDIO ──────────────────────────────────────────────────────────
    // /send?room=X&sender=Y
    // L'audio est poussé dans la queue de TOUS les membres SAUF l'expéditeur

    static void handleSend(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room   = p.getOrDefault("room", "");
        String sender = p.getOrDefault("sender", "");

        byte[] data = ex.getRequestBody().readAllBytes();

        Map<String, LinkedBlockingQueue<byte[]>> queues = userQueues.get(room);
        if (queues != null && data.length > 0) {
            for (Map.Entry<String, LinkedBlockingQueue<byte[]>> entry : queues.entrySet()) {
                if (!entry.getKey().equals(sender)) { // Ne pas s'envoyer à soi-même
                    LinkedBlockingQueue<byte[]> q = entry.getValue();
                    if (q.size() >= 300) q.poll(); // Vider si pleine (évite le blocage)
                    q.offer(data);
                }
            }
            System.out.println("Audio: " + data.length + " bytes | " + room + " | sender=" + sender);
        }

        sendJson(ex, 200, "{\"status\":\"ok\"}");
    }

    // ─── RECEVOIR AUDIO ─────────────────────────────────────────────────────────
    // /poll?room=X&user=Y
    // Long-poll : attend jusqu'à 2s pour avoir un chunk, sinon 204

    static void handlePoll(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        String user = p.getOrDefault("user", "");

        Map<String, LinkedBlockingQueue<byte[]>> queues = userQueues.get(room);
        if (queues == null) {
            sendJson(ex, 404, "{\"error\":\"Salle introuvable\"}");
            return;
        }

        LinkedBlockingQueue<byte[]> myQueue = queues.get(user);
        if (myQueue == null) {
            sendJson(ex, 403, "{\"error\":\"Utilisateur non enregistré dans cette salle\"}");
            return;
        }

        try {
            byte[] data = myQueue.poll(2000, TimeUnit.MILLISECONDS);
            if (data != null) {
                ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
                ex.sendResponseHeaders(200, data.length);
                ex.getResponseBody().write(data);
                ex.getResponseBody().close();
                System.out.println("Poll: " + data.length + " bytes → " + user + "@" + room);
            } else {
                ex.sendResponseHeaders(204, -1);
            }
        } catch (InterruptedException e) {
            ex.sendResponseHeaders(204, -1);
        }
    }

    // ─── MEMBRES ────────────────────────────────────────────────────────────────

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

    // ─── QUITTER ────────────────────────────────────────────────────────────────

    static void handleLeave(HttpExchange ex) throws IOException {
        Map<String, String> p = parseQuery(ex.getRequestURI().getQuery());
        String room = p.getOrDefault("room", "");
        String user = p.getOrDefault("user", "");

        if (members.containsKey(room))    members.get(room).remove(user);
        if (userQueues.containsKey(room)) userQueues.get(room).remove(user);

        // Nettoyer la salle si vide
        if (members.containsKey(room) && members.get(room).isEmpty()) {
            members.remove(room);
            userQueues.remove(room);
            System.out.println("Salle supprimée (vide): " + room);
        }

        sendJson(ex, 200, "{\"status\":\"left\"}");
        System.out.println(user + " a quitté: " + room);
    }

    // ─── UTILITAIRES ────────────────────────────────────────────────────────────

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
