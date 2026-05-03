import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Server {
    static Map<String, List<OutputStream>> rooms = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/join", Server::handleJoin);
        server.createContext("/send", Server::handleSend);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Serveur HTTP démarré port " + port);
    }

    static void handleJoin(HttpExchange ex) throws IOException {
        String room = ex.getRequestURI().getQuery().replace("room=", "");
        ex.getResponseHeaders().add("Content-Type", "audio/pcm");
        ex.getResponseHeaders().add("Transfer-Encoding", "chunked");
        ex.sendResponseHeaders(200, 0);
        rooms.computeIfAbsent(room, k -> Collections.synchronizedList(new ArrayList<>()))
             .add(ex.getResponseBody());
        System.out.println("Écouteur rejoint salle: " + room);
    }

    static void handleSend(HttpExchange ex) throws IOException {
        String room = ex.getRequestURI().getQuery().replace("room=", "");
        byte[] data = ex.getRequestBody().readAllBytes();
        List<OutputStream> listeners = rooms.getOrDefault(room, new ArrayList<>());
        synchronized (listeners) {
            Iterator<OutputStream> it = listeners.iterator();
            while (it.hasNext()) {
                try {
                    OutputStream out = it.next();
                    out.write(data);
                    out.flush();
                } catch (Exception e) {
                    it.remove();
                }
            }
        }
        ex.sendResponseHeaders(200, 0);
        ex.getResponseBody().close();
    }
}
