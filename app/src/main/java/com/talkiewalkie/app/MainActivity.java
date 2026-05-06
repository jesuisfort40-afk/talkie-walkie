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

    // ── BUG #4 CORRIGÉ : 16000 Hz au lieu de 44100 (4x moins de données) ──────
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_IN  = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING    = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2;

    private static final int    PORT   = 55555;
    private static final String SERVER = "https://talkie-walkie-production.up.railway.app";

    // ── BUG #3 CORRIGÉ : chunk plus grand = moins de connexions HTTP ──────────
    // À 16000 Hz / 16-bit : 8192 bytes ≈ 256ms d'audio → ~4 requêtes/s au lieu de 40
    private static final int SEND_CHUNK = 8192;

    private Button   btnPush, btnListen, btnCreate, btnJoin;
    private TextView tvStatus, tvMode, tvPeers, tvMyIp, tvRoom, tvMembers;
    private RadioGroup rgMode;
    private EditText   etTargetIp, etDirectIp, etRoomCode, etUsername;
    private LinearLayout layoutWifi, layoutDirect, layoutInternet, layoutRoom;

    private AudioRecord audioRecord;
    private AudioTrack  internetAudioTrack;

    private boolean isSending   = false;
    private boolean isListening = false;
    private boolean inRoom      = false;

    private ServerSocket serverSocket;
    private Thread serverThread, listenThread, sendThread;
    private String  currentMode = "WIFI";
    private String  currentRoom = "";
    private String  username    = "";
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Timer   membersTimer;

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
        btnPush    = findViewById(R.id.btnPush);
        btnListen  = findViewById(R.id.btnListen);
        btnCreate  = findViewById(R.id.btnCreate);
        btnJoin    = findViewById(R.id.btnJoin);
        tvStatus   = findViewById(R.id.tvStatus);
        tvMode     = findViewById(R.id.tvMode);
        tvPeers    = findViewById(R.id.tvPeers);
        tvMyIp     = findViewById(R.id.tvMyIp);
        tvRoom     = findViewById(R.id.tvRoom);
        tvMembers  = findViewById(R.id.tvMembers);
        rgMode     = findViewById(R.id.rgMode);
        etTargetIp = findViewById(R.id.etTargetIp);
        etDirectIp = findViewById(R.id.etDirectIp);
        etRoomCode = findViewById(R.id.etRoomCode);
        etUsername = findViewById(R.id.etUsername);
        layoutWifi     = findViewById(R.id.layoutWifi);
        layoutDirect   = findViewById(R.id.layoutDirect);
        layoutInternet = findViewById(R.id.layoutInternet);
        layoutRoom     = findViewById(R.id.layoutRoom);
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

        // ── PARLER ──────────────────────────────────────────────────────────────
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
                isSending = false;
                btnPush.setText("🎙️ MAINTENIR POUR PARLER");
                btnPush.setBackgroundColor(0xFF4CAF50);
                updateStatus(isListening ? "👂 Écoute en cours..." : "✅ Prêt");
            }
            return true;
        });

        // ── ÉCOUTER ─────────────────────────────────────────────────────────────
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
            }
        });

        // ── CRÉER SALLE ─────────────────────────────────────────────────────────
        btnCreate.setOnClickListener(v -> {
            username = etUsername.getText().toString().trim();
            if (username.isEmpty()) {
                updateStatus("❌ Entrez votre pseudo !");
                return;
            }
            createRoom();
        });

        // ── REJOINDRE SALLE ─────────────────────────────────────────────────────
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

    // ═══════════════════════════════════════════════════════════════════════════
    // SALLE
    // ═══════════════════════════════════════════════════════════════════════════

    private void createRoom() {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER + "/create?user=" + URLEncoder.encode(username, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
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
                conn.setReadTimeout(5000);
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

    // ═══════════════════════════════════════════════════════════════════════════
    // ÉCOUTE INTERNET — Connexion persistante + Jitter Buffer
    // ═══════════════════════════════════════════════════════════════════════════

    // Nombre de chunks à pré-charger avant de commencer la lecture
    // 3 chunks × 256ms = ~768ms de tampon → absorbe la gigue réseau
    private static final int JITTER_BUFFER_CHUNKS = 3;

    private void startListeningInternet() {
        listenThread = new Thread(() -> {
            try {
                internetAudioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE, CHANNEL_OUT, ENCODING,
                        BUFFER_SIZE * 8, // grand buffer interne AudioTrack
                        AudioTrack.MODE_STREAM
                );

                mainHandler.post(() -> updateStatus("👂 Connexion au serveur..."));

                // Connexion HTTP persistante — une seule pour toute la session
                URL url = new URL(SERVER + "/stream?room=" + currentRoom
                        + "&user=" + URLEncoder.encode(username, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(0); // PAS de timeout → connexion permanente
                conn.setRequestProperty("Accept", "application/octet-stream");

                int code = conn.getResponseCode();
                if (code != 200) {
                    mainHandler.post(() -> updateStatus("❌ Serveur: " + code));
                    return;
                }

                DataInputStream dis = new DataInputStream(
                        new BufferedInputStream(conn.getInputStream(), 65536)
                );

                // ── Jitter buffer : on accumule N chunks avant de jouer ────────
                List<byte[]> jitterBuffer = new ArrayList<>();
                boolean buffering = true;
                mainHandler.post(() -> updateStatus("⏳ Tampon audio..."));

                while (isListening && !Thread.interrupted()) {
                    // Lire taille du prochain chunk (4 bytes big-endian)
                    int size = dis.readInt();

                    if (size == 0) continue; // keepalive serveur, ignorer

                    byte[] data = new byte[size];
                    dis.readFully(data);

                    if (buffering) {
                        jitterBuffer.add(data);
                        if (jitterBuffer.size() >= JITTER_BUFFER_CHUNKS) {
                            // Buffer plein → démarrer la lecture
                            internetAudioTrack.play();
                            for (byte[] chunk : jitterBuffer) {
                                internetAudioTrack.write(chunk, 0, chunk.length);
                            }
                            jitterBuffer.clear();
                            buffering = false;
                            mainHandler.post(() -> updateStatus("👂 Écoute en cours..."));
                        }
                    } else {
                        internetAudioTrack.write(data, 0, data.length);
                    }
                }

                conn.disconnect();

            } catch (EOFException e) {
                mainHandler.post(() -> updateStatus("📵 Connexion fermée"));
            } catch (Exception e) {
                if (isListening) {
                    mainHandler.post(() -> updateStatus("❌ Écoute: " + e.getMessage()));
                }
            } finally {
                isListening = false;
                releaseInternetAudioTrack();
            }
        });
        listenThread.setDaemon(true);
        listenThread.start();
    }

    private void stopListening() {
        isListening = false;
        if (listenThread != null) listenThread.interrupt();
        releaseInternetAudioTrack();
        updateStatus("✅ Prêt");
    }

    private void releaseInternetAudioTrack() {
        if (internetAudioTrack != null) {
            try { internetAudioTrack.stop(); internetAudioTrack.release(); } catch (Exception e) {}
            internetAudioTrack = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENVOI INTERNET — Connexion persistante (une seule pour toute la session)
    // ═══════════════════════════════════════════════════════════════════════════

    private void startSendingInternet() {
        sendThread = new Thread(() -> {
            AudioRecord recorder = null;
            HttpURLConnection conn = null;
            try {
                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, CHANNEL_IN, ENCODING, BUFFER_SIZE
                );
                recorder.startRecording();
                audioRecord = recorder;

                // Une seule connexion HTTP persistante avec chunked upload
                URL url = new URL(SERVER + "/send?room=" + currentRoom
                        + "&sender=" + URLEncoder.encode(username, "UTF-8"));
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setChunkedStreamingMode(SEND_CHUNK); // chunked upload sans buffer complet en mémoire
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(0); // connexion permanente
                conn.setRequestProperty("Content-Type", "application/octet-stream");

                DataOutputStream dos = new DataOutputStream(
                        new BufferedOutputStream(conn.getOutputStream(), SEND_CHUNK * 2)
                );

                ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                mainHandler.post(() -> updateStatus("📡 Transmission..."));

                while (isSending && !Thread.interrupted()) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        accumulator.write(buffer, 0, read);
                        if (accumulator.size() >= SEND_CHUNK) {
                            byte[] data = accumulator.toByteArray();
                            accumulator.reset();
                            // Préfixe taille (4 bytes) + données — même format que /stream
                            int len = data.length;
                            dos.write(new byte[]{
                                (byte)(len >> 24), (byte)(len >> 16),
                                (byte)(len >> 8),  (byte)(len)
                            });
                            dos.write(data);
                            dos.flush();
                        }
                    }
                }

                dos.close();

            } catch (Exception e) {
                if (isSending) {
                    mainHandler.post(() -> updateStatus("❌ Micro: " + e.getMessage()));
                }
            } finally {
                if (recorder != null) {
                    try { recorder.stop(); recorder.release(); } catch (Exception ignored) {}
                }
                if (conn != null) conn.disconnect();
                audioRecord = null;
                isSending = false;
            }
        });
        sendThread.setDaemon(true);
        sendThread.start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENVOI LOCAL (WiFi / WiFi Direct) — inchangé
    // ═══════════════════════════════════════════════════════════════════════════

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

            AudioRecord recorder = null;
            try {
                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, CHANNEL_IN, ENCODING, BUFFER_SIZE
                );
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
                mainHandler.post(() -> updateStatus("✅ Prêt"));

            } catch (Exception e) {
                mainHandler.post(() -> updateStatus("❌ " + e.getMessage()));
            } finally {
                if (recorder != null) {
                    try { recorder.stop(); recorder.release(); } catch (Exception e) {}
                }
                audioRecord = null;
                isSending = false;
            }
        });
        sendThread.setDaemon(true);
        sendThread.start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MEMBRES
    // ═══════════════════════════════════════════════════════════════════════════

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
                    conn.setReadTimeout(3000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String resp = br.readLine();
                    conn.disconnect();
                    String count = resp.split("\"count\":")[1].replace("}", "").trim();
                    String m = resp.split("\"members\":")[1].split(",\"count\"")[0]
                            .replace("[", "").replace("]", "").replace("\"", "");
                    mainHandler.post(() -> tvMembers.setText("👥 " + count + " : " + m));
                } catch (Exception e) {}
            }
        }, 0, 3000);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SERVEUR LOCAL (réception WiFi / WiFi Direct)
    // ═══════════════════════════════════════════════════════════════════════════

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
        AudioTrack track = null;
        try {
            DataInputStream dis = new DataInputStream(client.getInputStream());
            dis.readUTF();
            mainHandler.post(() -> {
                updateStatus("📻 Signal reçu !");
                tvPeers.setText("Connecté : " + client.getInetAddress().getHostAddress());
            });

            track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE, CHANNEL_OUT, ENCODING,
                    BUFFER_SIZE, AudioTrack.MODE_STREAM
            );
            track.play();

            byte[] buffer = new byte[BUFFER_SIZE];
            int size;
            while ((size = dis.readInt()) != -1) {
                dis.readFully(buffer, 0, size);
                track.write(buffer, 0, size);
            }
            mainHandler.post(() -> updateStatus("✅ Prêt"));

        } catch (Exception e) {
            mainHandler.post(() -> updateStatus("Réception: " + e.getMessage()));
        } finally {
            if (track != null) {
                try { track.stop(); track.release(); } catch (Exception e) {}
            }
            try { client.close(); } catch (Exception e) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITAIRES
    // ═══════════════════════════════════════════════════════════════════════════

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
        isSending   = false;
        isListening = false;
        if (membersTimer != null) membersTimer.cancel();
        if (listenThread != null) listenThread.interrupt();
        if (sendThread   != null) sendThread.interrupt();
        releaseInternetAudioTrack();
        try {
            if (!currentRoom.isEmpty() && !username.isEmpty()) {
                new Thread(() -> {
                    try {
                        new URL(SERVER + "/leave?room=" + currentRoom
                                + "&user=" + URLEncoder.encode(username, "UTF-8"))
                                .openConnection().getInputStream().close();
                    } catch (Exception e) {}
                }).start();
            }
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {}
        if (serverThread != null) serverThread.interrupt();
    }
}
