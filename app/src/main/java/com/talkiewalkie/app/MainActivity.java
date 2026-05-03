package com.talkiewalkie.app;

import android.Manifest;
import android.content.pm.PackageManager;
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
import androidx.core.content.ContextCompat;

import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    // Audio config
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING) * 2;

    // Network
    private static final int PORT = 55555;

    // UI
    private Button btnPush;
    private TextView tvStatus, tvMode, tvPeers;
    private RadioGroup rgMode;
    private EditText etServerIp, etRoomCode;
    private LinearLayout layoutWifi, layoutDirect, layoutInternet;

    // Audio
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isSending = false;
    private boolean isReceiving = false;

    // Network
    private ServerSocket serverSocket;
    private Socket sendSocket;
    private Thread receiveThread, sendThread, serverThread;
    private String currentMode = "WIFI";

    // Internet relay (simple UDP broadcast simulation via TCP relay)
    private String serverRelayIp = "";
    private String roomCode = "";

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        requestPermissions();
        setupModeSelector();
        setupPushToTalk();
        startReceiveServer();
    }

    private void initViews() {
        btnPush = findViewById(R.id.btnPush);
        tvStatus = findViewById(R.id.tvStatus);
        tvMode = findViewById(R.id.tvMode);
        tvPeers = findViewById(R.id.tvPeers);
        rgMode = findViewById(R.id.rgMode);
        etServerIp = findViewById(R.id.etServerIp);
        etRoomCode = findViewById(R.id.etRoomCode);
        layoutWifi = findViewById(R.id.layoutWifi);
        layoutDirect = findViewById(R.id.layoutDirect);
        layoutInternet = findViewById(R.id.layoutInternet);

        // Show local IP
        TextView tvMyIp = findViewById(R.id.tvMyIp);
        tvMyIp.setText("Mon IP : " + getLocalIp());
    }

    private void setupModeSelector() {
        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            layoutWifi.setVisibility(View.GONE);
            layoutDirect.setVisibility(View.GONE);
            layoutInternet.setVisibility(View.GONE);

            if (checkedId == R.id.rbWifi) {
                currentMode = "WIFI";
                layoutWifi.setVisibility(View.VISIBLE);
                tvMode.setText("MODE : WiFi Local");
                updateStatus("Entrez l'IP de l'autre téléphone");
            } else if (checkedId == R.id.rbDirect) {
                currentMode = "DIRECT";
                layoutDirect.setVisibility(View.VISIBLE);
                tvMode.setText("MODE : WiFi Direct (~200m)");
                updateStatus("Activez WiFi Direct dans les paramètres");
                startWifiDirectDiscovery();
            } else if (checkedId == R.id.rbInternet) {
                currentMode = "INTERNET";
                layoutInternet.setVisibility(View.VISIBLE);
                tvMode.setText("MODE : Internet (illimité)");
                updateStatus("Entrez IP serveur et code salle");
            }
        });

        // Default mode
        rgMode.check(R.id.rbWifi);
    }

    private void setupPushToTalk() {
        btnPush.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                startSending();
                btnPush.setText("🔴 EN COURS...");
                btnPush.setBackgroundColor(0xFFFF4444);
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                stopSending();
                btnPush.setText("🎙️ MAINTENIR POUR PARLER");
                btnPush.setBackgroundColor(0xFF4CAF50);
            }
            return true;
        });
    }

    private void startSending() {
        if (isSending) return;
        isSending = true;

        String targetIp = getTargetIp();
        if (targetIp.isEmpty()) {
            updateStatus("❌ Entrez l'IP de destination !");
            isSending = false;
            return;
        }

        sendThread = new Thread(() -> {
            try {
                audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_IN, ENCODING, BUFFER_SIZE
                );
                audioRecord.startRecording();

                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(targetIp, PORT), 2000);
                OutputStream out = socket.getOutputStream();
                DataOutputStream dos = new DataOutputStream(out);

                // Send room code for internet mode
                dos.writeUTF(currentMode.equals("INTERNET") ? roomCode : "LOCAL");

                byte[] buffer = new byte[BUFFER_SIZE];
                mainHandler.post(() -> updateStatus("📡 Transmission en cours..."));

                while (isSending) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        dos.writeInt(read);
                        dos.write(buffer, 0, read);
                        dos.flush();
                    }
                }

                dos.writeInt(-1); // Signal fin
                socket.close();
                audioRecord.stop();
                audioRecord.release();
                mainHandler.post(() -> updateStatus("✅ Prêt"));

            } catch (Exception e) {
                mainHandler.post(() -> updateStatus("❌ Erreur : " + e.getMessage()));
                isSending = false;
            }
        });
        sendThread.start();
    }

    private void stopSending() {
        isSending = false;
    }

    private void startReceiveServer() {
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                mainHandler.post(() -> updateStatus("✅ Prêt à recevoir"));

                while (!Thread.interrupted()) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleIncoming(client)).start();
                }
            } catch (Exception e) {
                if (!e.getMessage().contains("closed")) {
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
            String code = dis.readUTF();

            // Filter by room code in internet mode
            if (currentMode.equals("INTERNET") && !code.equals(roomCode) && !roomCode.isEmpty()) {
                client.close();
                return;
            }

            mainHandler.post(() -> {
                updateStatus("📻 Signal reçu !");
                tvPeers.setText("Connecté : " + client.getInetAddress().getHostAddress());
            });

            audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE, CHANNEL_OUT, ENCODING,
                BUFFER_SIZE, AudioTrack.MODE_STREAM
            );
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

    private void startWifiDirectDiscovery() {
        // WiFi Direct uses the same socket mechanism
        // User needs to connect via WiFi Direct first in Android Settings
        // Then use the group owner IP (usually 192.168.49.1)
        EditText etDirectIp = findViewById(R.id.etDirectIp);
        etDirectIp.setHint("IP groupe (ex: 192.168.49.1)");
        updateStatus("Connectez via WiFi Direct → paramètres Android\nIP groupe owner: 192.168.49.1");
    }

    private String getTargetIp() {
        if (currentMode.equals("WIFI")) {
            EditText etIp = findViewById(R.id.etTargetIp);
            return etIp.getText().toString().trim();
        } else if (currentMode.equals("DIRECT")) {
            EditText etIp = findViewById(R.id.etDirectIp);
            return etIp.getText().toString().trim();
        } else {
            serverRelayIp = etServerIp.getText().toString().trim();
            roomCode = etRoomCode.getText().toString().trim();
            return serverRelayIp;
        }
    }

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
        } catch (Exception e) { }
        return "Inconnu";
    }

    private void updateStatus(String msg) {
        tvStatus.setText(msg);
    }

    private void requestPermissions() {
        String[] perms = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        };
        ActivityCompat.requestPermissions(this, perms, 1);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isSending = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
        if (serverThread != null) serverThread.interrupt();
    }
}
