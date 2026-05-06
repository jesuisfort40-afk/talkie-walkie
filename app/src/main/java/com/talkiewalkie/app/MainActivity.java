package com.talkiewalkie.app;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING) * 2;
    private static final int PORT = 55555;
    private static final String SERVER = "https://talkie-walkie-production.up.railway.app";

    private Button btnPush, btnListen, btnCreate, btnJoin;
    private TextView tvStatus, tvMode, tvPeers, tvMyIp, tvRoom, tvMembers;
    private RadioGroup rgMode;
    private EditText etTargetIp, etDirectIp, etRoomCode, etUsername;
    private LinearLayout layoutWifi, layoutDirect, layoutInternet, layoutRoom;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isSending = false;
    private boolean isListening = false;
    private boolean inRoom = false;
    private ServerSocket serverSocket;
    private Thread serverThread, listenThread, sendThread;
    private String currentMode = "WIFI";
    private String currentRoom = "";
    private String username = "";
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Timer membersTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        requestPermissions();
        setupModeSelector();
        setupButtons();
        startReceiveServer();
    }

    private void initViews() {
        btnPush = findViewById(R.id.btnPush);
        btnListen = findViewById(R.id.btnListen);
        btnCreate = findViewById(R.id.btnCreate);
        btnJoin = findViewById(R.id.btnJoin);
        tvStatus = findViewById(R.id.tvStatus);
        tvMode = findViewById(R.id.tvMode);
        tvPeers = findViewById(R.id.tvPeers);
        tvMyIp = findViewById(R.id.tvMyIp);
        tvRoom = findViewById(R.id.tvRoom);
        tvMembers = findViewById(R.id.tvMembers);
        rgMode = findViewById(R.id.rgMode);
        etTargetIp = findViewById(R.id.etTargetIp);
        etDirectIp = findViewById(R.id.etDirectIp);
        etRoomCode = findViewById(R.id.etRoomCode);
        etUsername = findViewById(R.id.etUsername);
        layoutWifi = findViewById(R.id.layoutWifi);
        layoutDirect = findViewById(R.id.layoutDirect);
        layoutInternet = findViewById(R.id.layoutInternet);
        layoutRoom = findViewById(R.id.layoutRoom);
        tvMyIp.setText("Mon IP : " + getLocalIp());
    }

    private void setupModeSelector() {
        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            layoutWifi.setVisibility(View.GONE);
            layoutDirect.setVisibility(View.GONE);
            layoutInternet.setVisibility(View.GONE);
            layoutRoom.setVisibility(View.GONE);
            btnListen.setVisibility(View.GONE);
            btnCreate.setVisibility(View.GONE);
            btnJoin.setVisibility(View.GONE);

            if (checkedId == R.id.rbWifi) {
                currentMode = "WIFI";
                layoutWifi.setVisibility(View.VISIBLE);
                tvMode.setText("MODE : WiFi Local");
                updateStatus("Entrez l'IP de l'autre téléphone");
            } else if (checkedId == R.id.rbDirect) {
                currentMode = "DIRECT";
                layoutDirect.setVisibility(View.VISIBLE);
                tvMode.setText("MODE : WiFi Direct (~200m)");
                updateStatus("Connectez via WiFi Direct dans paramètres");
            } else if (checkedId == R.id.rbInternet) {
                currentMode = "INTERNET";
                layoutInternet.setVisibility(View.VISIBLE);
                btnCreate.setVisibility(View.VISIBLE);
                btnJoin.setVisibility(View.VISIBLE);
                tvMode.setText("MODE : Internet (illimité)");
                updateStatus("Créez ou rejoignez une salle");
            }
        });
        rgMode.check(R.id.rbWifi);
    }

    private void setupButtons() {
        // PARLER — indépendant de l'écoute
        btnPush.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (currentMode.equals("INTERNET") && !inRoom) {
                    updateStatus("❌ Rejoins une salle d'abord !");
                    return true;
                }
                isSending = true;
                btnPush.setText("🔴 EN COURS...");
                btnPush.setBackgroundColor(0xFFFF4444);
                if (currentMode.equals("INTERNET")) {
                    startSendingInternet();
                } else {
                    startSendingLocal();
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                // Arrête seulement l'envoi — PAS l'écoute !
                isSending = false;
                btnPush.setText("🎙️ MAINTENIR POUR PARLER");
                btnPush.setBackgroundColor(0xFF4CAF50);
                updateStatus(isListening ? "👂 Écoute en cours..." : "✅ Prêt");
            }
            return true;
        });

        // ÉCOUTER — indépendant de l'envoi
        btnListen.setOnClickListener(v -> {
            if (!inRoom) {
                updateStatus("❌ Rejoins une salle d'abord !");
                return;
            }
            if (!isListening) {
                isListening = true;
                btnListen.setText("🔴 STOP ÉCOUTE");
                btnListen.setBackgroundColor(0xFFFF4444);
                startListeningInternet();
            } else {
                isListening = false;
                btnListen.setText("👂 ÉCOUTER");
                btnListen.setBackgroundColor(0xFF2196F3);
                stopListening();
                updateStatus("✅ Prêt");
            }
        });

        // CRÉER SALLE
        btnCreate.setOnClickListener(v -> {
            username = etUsername.getText().toString().trim();
            if (username.isEmpty()) {
                updateStatus("❌ Entrez votre pseudo !");
                return;
            }
            createRoom();
        });

        // REJOINDRE SALLE
        btnJoin.setOnClickListener(v -> {
            username = etUsername.getText().toString().trim();
            String room = etRoomCode.getText().toString().trim().toUpperCase();
            if (username.isEmpty()) {
                updateStatus("❌ Entrez votre pseudo !");
                return;
            }
            if (room.isEmpty()) {
                updateStatus("❌ Entrez le code salle !");
                return;
            }
            joinRoom(room);
        });
    }

    // ========== SALLE ==========

    private void createRoom() {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER + "/create?user=" + URLEncoder.encode(username, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String response = br.readLine();
                conn.disconnect();

                String room = response.split("\"room\":\"")[1].split("\"")[0];
                currentRoom = room;
                inRoom = true;

                mainHandler.post(() -> {
                    tvRoom.setText("🏠 Code : " + room + "\n👆 Partage ce code !");
                    layoutRoom.setVisibility(View.VISIBLE);
                    btnListen.setVisibility(View.VISIBLE);
                    btnCreate.setVisibility(View.GONE);
                    btnJoin.setVisibility(View.GONE);
                    updateStatus("✅ Salle créée ! Appuie ÉCOUTER");
                    tvPeers.setText("Tu es le créateur");
                });

                startMembersRefresh();

            } catch (Exception e) {
                mainHandler.post(() -> updateStatus("❌ Erreur: " + e.getMessage()));
            }
        }).start();
    }

    private void joinRoom(String room) {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER + "/join?room=" + room
                    + "&user=" + URLEncoder.encode(username, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                int code = conn.getResponseCode();
                conn.disconnect();

                if (code == 404) {
                    mainHandler.post(() -> updateStatus("❌ Salle introuvable !"));
                    return;
                }

                currentRoom = room;
                inRoom = true;

                mainHandler.post(() -> {
                    tvRoom.setText("🏠 Salle : " + room);
                    layoutRoom.setVisibility(View.VISIBLE);
                    btnListen.setVisibility(View.VISIBLE);
                    btnCreate.setVisibility(View.GONE);
                    btnJoin.setVisibility(View.GONE);
                    updateStatus("✅ Connecté ! Appuie ÉCOUTER");
                    tvPeers.setText("Membre de la salle");
                });

                startMembersRefresh();

            } catch (Exception e) {
                mainHandler.post(() -> updateStatus("❌ " + e.getMessage()));
            }
        }).start();
    }

    // ========== ÉCOUTE INTERNET ==========

    private void startListeningInternet() {
    listenThread = new Thread(() -> {
        try {
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                SAMPLE_RATE, CHANNEL_OUT, ENCODING,
                BUFFER_SIZE * 4, AudioTrack.MODE_STREAM);
            audioTrack.play();
            mainHandler.post(() -> updateStatus("👂 Écoute en cours..."));

            while (isListening && !Thread.interrupted()) {
                try {
                    URL url = new URL(SERVER + "/poll?room=" + currentRoom);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        byte[] data = conn.getInputStream().readAllBytes();
                        if (data.length > 0) {
                            audioTrack.write(data, 0, data.length);
                        }
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    try { Thread.sleep(50); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            mainHandler.post(() -> updateStatus("❌ " + e.getMessage()));
        } finally {
            isListening = false;
            if (audioTrack != null) {
                try { audioTrack.stop(); audioTrack.release(); } catch (Exception e) {}
            }
        }
    });
    listenThread.setDaemon(true);
    listenThread.start();
    }

    private void stopListening() {
        isListening = false;
        if (listenThread != null) listenThread.interrupt();
        if (audioTrack != null) {
            try { audioTrack.stop(); audioTrack.release(); } catch (Exception e) {}
        }
    }

    // ========== ENVOI INTERNET ==========

    private void startSendingInternet() {
        sendThread = new Thread(() -> {
            AudioRecord recorder = null;
            try {
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_IN, ENCODING, BUFFER_SIZE);
                recorder.startRecording();
                audioRecord = recorder;

                byte[] buffer = new byte[2048];

                // Envoie audio tant que le bouton est pressé
                // N'affecte PAS isListening !
                while (isSending) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        final byte[] data = Arrays.copyOf(buffer, read);
                        try {
                            URL url = new URL(SERVER + "/send?room=" + currentRoom);
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("POST");
                            conn.setDoOutput(true);
                            conn.setConnectTimeout(2000);
                            conn.setReadTimeout(2000);
                            conn.getOutputStream().write(data);
                            conn.getOutputStream().flush();
                            conn.getResponseCode();
                            conn.disconnect();
                        } catch (Exception e) {
                            // Continue même si un envoi échoue
                        }
                    }
                }

            } catch (Exception e) {
                mainHandler.post(() -> updateStatus("❌ Micro: " + e.getMessage()));
            } finally {
                if (recorder != null) {
                    try { recorder.stop(); recorder.release(); } catch (Exception e) {}
                }
            }
        });
        sendThread.setDaemon(true);
        sendThread.start();
    }

    // ========== ENVOI LOCAL ==========

    private void startSendingLocal() {
        sendThread = new Thread(() -> {
            String targetIp = currentMode.equals("WIFI") ?
                etTargetIp.getText().toString().trim() :
                etDirectIp.getText().toString().trim();

            if (targetIp.isEmpty()) {
                mainHandler.post(() -> updateStatus("❌ Entrez l'IP !"));
                isSending = false;
                return;
            }

            try {
                AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_IN, ENCODING, BUFFER_SIZE);
                recorder.startRecording();
                audioRecord = recorder;

                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(targetIp, PORT), 2000);
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                dos.writeUTF("LOCAL");

                byte[] buffer = new byte[BUFFER_SIZE];
                mainHandler.post(() -> updateStatus("📡 Transmission..."));

                while (isSending) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        dos.writeInt(read);
                        dos.write(buffer, 0, read);
                        dos.flush();
                    }
                }

                dos.writeInt(-1);
                socket.close();
                recorder.stop();
                recorder.release();
                mainHandler.post(() -> updateStatus("✅ Prêt"));

            } catch (Exception e) {
                mainHandler.post(() -> updateStatus("❌ " + e.getMessage()));
                isSending = false;
            }
        });
        sendThread.setDaemon(true);
        sendThread.start();
    }

    // ========== MEMBRES ==========

    private void startMembersRefresh() {
        if (membersTimer != null) membersTimer.cancel();
        membersTimer = new Timer();
        membersTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    URL url = new URL(SERVER + "/members?room=" + currentRoom);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String resp = br.readLine();
                    conn.disconnect();
                    String count = resp.split("\"count\":")[1].replace("}", "").trim();
                    String m = resp.split("\"members\":")[1].split(",\"count\"")[0]
                        .replace("[","").replace("]","").replace("\"","");
                    mainHandler.post(() -> tvMembers.setText("👥 " + count + " membre(s) : " + m));
                } catch (Exception e) {}
            }
        }, 0, 3000);
    }

    // ========== SERVEUR LOCAL ==========

    private void startReceiveServer() {
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                mainHandler.post(() -> updateStatus("✅ Prêt"));
                while (!Thread.interrupted()) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleIncoming(client)).start();
                }
            } catch (Exception e) {
                if (e.getMessage() != null && !e.getMessage().contains("closed")) {
                    mainHandler.post(() -> updateStatus("Serveur: " + e.getMessage()));
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void handleIncoming(Socket client) {
        try {
            DataInputStream dis = new DataInputStream(client.getInputStream());
            dis.readUTF();
            mainHandler.post(() -> {
                updateStatus("📻 Signal reçu !");
                tvPeers.setText("Connecté : " + client.getInetAddress().getHostAddress());
            });

            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                SAMPLE_RATE, CHANNEL_OUT, ENCODING, BUFFER_SIZE, AudioTrack.MODE_STREAM);
            audioTrack.play();

            byte[] buffer = new byte[BUFFER_SIZE];
            int size;
            while ((size = dis.readInt()) != -1) {
                dis.readFully(buffer, 0, size);
                audioTrack.write(buffer, 0, size);
            }

            audioTrack.stop();
            audioTrack.release();
            client.close();
            mainHandler.post(() -> updateStatus("✅ Prêt"));

        } catch (Exception e) {
            mainHandler.post(() -> updateStatus("Réception: " + e.getMessage()));
        }
    }

    // ========== UTILITAIRES ==========

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {}
        return "Inconnu";
    }

    private void updateStatus(String msg) { tvStatus.setText(msg); }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        }, 1);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isSending = false;
        isListening = false;
        if (membersTimer != null) membersTimer.cancel();
        if (listenThread != null) listenThread.interrupt();
        if (sendThread != null) sendThread.interrupt();
        try {
            if (!currentRoom.isEmpty() && !username.isEmpty()) {
                new Thread(() -> {
                    try {
                        new URL(SERVER + "/leave?room=" + currentRoom
                            + "&user=" + URLEncoder.encode(username, "UTF-8"))
                            .openConnection().getInputStream();
                    } catch (Exception e) {}
                }).start();
            }
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {}
        if (serverThread != null) serverThread.interrupt();
    }
}
