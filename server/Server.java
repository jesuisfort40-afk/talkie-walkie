import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Server {
    static final int PORT = 55555;
    static Map<String, List<Socket>> rooms = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(PORT);
        System.out.println("Serveur démarré port " + PORT);

        while (true) {
            Socket client = server.accept();
            new Thread(() -> handle(client)).start();
        }
    }

    static void handle(Socket client) {
        try {
            DataInputStream dis = new DataInputStream(client.getInputStream());
            String room = dis.readUTF();

            rooms.computeIfAbsent(room, k -> Collections.synchronizedList(new ArrayList<>())).add(client);
            System.out.println("Client rejoint salle: " + room);

            byte[] buf = new byte[4096];
            int len;
            while ((len = client.getInputStream().read(buf)) != -1) {
                for (Socket peer : rooms.get(room)) {
                    if (peer != client && !peer.isClosed()) {
                        peer.getOutputStream().write(buf, 0, len);
                        peer.getOutputStream().flush();
                    }
                }
            }
            rooms.get(room).remove(client);
        } catch (Exception e) {
            System.out.println("Client déconnecté");
        }
    }
}
