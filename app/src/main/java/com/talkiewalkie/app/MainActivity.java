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

    private Button btnPush;
    private TextView tvStatus, tvMode, tvPeers, tvMyIp;
    private RadioGroup rgMode;
    private EditText etTargetIp, etDirectIp, etServerIp, etRoomCode;
    private LinearLayout layoutWifi, layoutDirect, layoutInternet;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isSending = false;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private String currentMode = "WIFI";
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
        tvMyIp = findViewById(R.id.tvMyIp);
        rgMode = findViewById(R.id.rgMode);
        etTargetIp = findViewById(R.id.etTargetIp);
        etDirectIp = findViewById(R.id.etDirectIp);
        etServerIp = findViewById(R.id.etServerIp);
        etRoomCode = findViewById(R.id.etRoomCode);
        layoutWifi = findViewById(R.id.layoutWifi);
        layoutDirect = findViewById(R.id.layoutDirect);
        layoutInternet = findViewById(R.id.layoutInternet);
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
                updateStatus("Connectez via WiFi Direct dans paramètres");
            } else if (checkedId == R.id.rbInternet) {
                currentMode = "INTERNET";
                layoutInternet.setVisibility(View.VISIBLE);
                tvMode.setText("MODE : Internet (illimité)");
                updateStatus("Entrez le code salle");
            }
        });
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

        if (currentMode.equals("INTERNET")) {
            startSendingInternet();
        } else {
            startSendingLocal();
        }
    }

    private void startSendingLocal() {
        String targetIp = currentMode.equals("WIFI") ?
            etTargetIp.getText().toString().trim() :
            etDirectIp.getText().toString().trim();

        if (targetIp.isEmpty()) {
            updateStatus("❌ Entrez l'IP !");
            isSending = false;
            return;
        }

        new Thread(() -> {
            try {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_IN, ENCODING, BUFFER_SIZE);
                audioRecord.startRecording();

                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(targetIp, PORT), 2000);
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                dos.writeUTF("LOCAL");

                byte[] buffer = new byte[BUFFER_SIZE];
                mainHandler.post(() -> updateStatus("📡 Transmission..."));

                while (isSending) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        dos.writeInt(read);
                        dos.write(buffer, 0, read);
                        dos.flush();
                    }
                }

                dos.writeInt(-1);
                socket.close();
                audioRecord.stop();
                audioRecord.release();
                mainHandler.post(() -> updateStatus("✅ Prêt"));

            } catch (Exception e) {
                mainHandler.post(() -> updateStatus("❌ " + e.getMessage()));
                isSending = false;
            }
        }).start();
    }

    private void startSendingInternet() {
        String room = etRoomCode.getText().toString().trim();
        if (room.isEmpty()) {
            updateStatus("❌ Entrez le code salle !");
            isSending = false;
            return;
        }

        new Thread(() -> {
            try {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_IN, ENCODING, BUFFER_SIZE);
                audioRecord.startRecording();
                mainHandler.post(() -> updateStatus("📡 Transmission internet..."));

                byte[] buffer = new byte[BUFFER_SIZE];
                while (isSending) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        byte[] data = Arrays.copyOf(buffer, read);
                        URL url = new URL("https://talkie-walkie-production.up.railway.app/send?room=" + room);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setConnectTimeout(3000);
                        conn.getOutputStream().write(data);
                        conn.getOutputStream().flush();
                        conn.getResponseCode();
                        conn.disconnect();
                    }
                }

                audioRecord.stop();
                audioRecord.release();
                mainHandler.post(() -> updateStatus("✅ Prêt"));

            } catch (Exception e) {
                mainHandler.post(() -> updateStatus("❌ " + e.getMessage()));
                isSending = false;
            }
        }).start();
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
                if (e.getMessage() != null && !e.getMessage().contains("closed")) {
                    mainHandler.post(() -> updateStatus("Serveur: " + e.getMessage()));
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        if (currentMode.equals("INTERNET")) {
            startReceivingInternet();
        }
    }

    private void startReceivingInternet() {
        String room = etRoomCode.getText().toString().trim();
        if (room.isEmpty()) return;

        new Thread(() -> {
            try {
                URL url = new URL("https://talkie-walkie-production.up.railway.app/join?room=" + room);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setReadTimeout(0);
                InputStream in = conn.getInputStream();

                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE, CHANNEL_OUT, ENCODING, BUFFER_SIZE, AudioTrack.MODE_STREAM);
                audioTrack.play();
                mainHandler.post(() -> updateStatus("📻 En écoute internet..."));

                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    audioTrack.write(buffer, 0, read);
                }

                audioTrack.stop();
                audioTrack.release();

            } catch (Exception e) {
                mainHandler.post(() -> updateStatus("Réception: " + e.getMessage()));
            }
        }).start();
    }

    private void handleIncoming(Socket client) {
        try {
            DataInputStream dis = new DataInputStream(client.getInputStream());
            String code = dis.readUTF();

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

    private void stopSending() {
        isSending = false;
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
        } catch (Exception e) {}
        return "Inconnu";
    }

    private void updateStatus(String msg) {
        tvStatus.setText(msg);
    }

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
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
        if (serverThread != null) serverThread.interrupt();
    }
}
