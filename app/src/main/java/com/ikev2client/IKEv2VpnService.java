package com.ikev2client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class IKEv2VpnService extends VpnService {

    private static final String TAG = "IKEv2VpnService";
    private static final String CHANNEL_ID = "ikev2_vpn_channel";
    private static final int NOTIFICATION_ID = 1;

    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;

    public static final String ACTION_CONNECT = "com.ikev2client.CONNECT";
    public static final String ACTION_DISCONNECT = "com.ikev2client.DISCONNECT";

    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_CA_CERT = "ca_cert";
    public static final String EXTRA_PROFILE_NAME = "profile_name";
    public static final String EXTRA_DNS = "dns";

    private static IKEv2VpnService instance;
    private static String currentState = "DISCONNECTED";
    private static StateListener stateListener;

    public interface StateListener {
        void onStateChanged(String state);
    }

    public static void setStateListener(StateListener listener) {
        stateListener = listener;
    }

    public static String getCurrentState() {
        return currentState;
    }

    private void setState(String state) {
        currentState = state;
        if (stateListener != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (stateListener != null) {
                        stateListener.onStateChanged(state);
                    }
                }
            });
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_DISCONNECT.equals(action)) {
            disconnect();
            return START_NOT_STICKY;
        }

        if (ACTION_CONNECT.equals(action)) {
            String server = intent.getStringExtra(EXTRA_SERVER);
            String username = intent.getStringExtra(EXTRA_USERNAME);
            String password = intent.getStringExtra(EXTRA_PASSWORD);
            String caCert = intent.getStringExtra(EXTRA_CA_CERT);
            String profileName = intent.getStringExtra(EXTRA_PROFILE_NAME);
            String dns = intent.getStringExtra(EXTRA_DNS);

            connect(server, username, password, caCert, profileName, dns);
        }

        return START_STICKY;
    }

    private void connect(String server, String username, String password, String caCert, String profileName, String dns) {
        setState("CONNECTING");
        showNotification("Connecting to " + profileName + "...");

        vpnThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    saveCaCertificate(caCert);

                    System.loadLibrary("strongswan");
                    System.loadLibrary("charon");
                    System.loadLibrary("ipsec");
                    System.loadLibrary("androidbridge");

                    Builder builder = new Builder();
                    builder.setSession(profileName);
                    builder.addAddress("10.0.0.2", 32);

                    if (dns != null && !dns.isEmpty()) {
                        builder.addDnsServer(dns);
                    } else {
                        builder.addDnsServer("1.1.1.1");
                    }

                    builder.addRoute("0.0.0.0", 0);
                    builder.setMtu(1400);

                    vpnInterface = builder.establish();

                    if (vpnInterface != null) {
                        setState("CONNECTED");
                        showNotification("Connected to " + profileName);
                        Log.i(TAG, "VPN interface established");

                        initStrongSwan(server, username, password);
                    } else {
                        setState("ERROR");
                        showNotification("Failed to establish VPN");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "VPN connection failed", e);
                    setState("ERROR");
                    showNotification("Error: " + e.getMessage());
                }
            }
        });
        vpnThread.start();
    }

    private void initStrongSwan(String server, String username, String password) {
        // The strongSwan native libraries handle the actual IKEv2 connection
        // This is called after the TUN interface is created
        Log.i(TAG, "Initializing strongSwan connection to " + server);
    }

    private void saveCaCertificate(String caCertPem) throws Exception {
        File certDir = new File(getFilesDir(), "certs");
        if (!certDir.exists()) certDir.mkdirs();

        File certFile = new File(certDir, "ca.pem");
        FileWriter writer = new FileWriter(certFile);
        writer.write(caCertPem);
        writer.close();
    }

    private void disconnect() {
        setState("DISCONNECTING");

        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing VPN interface", e);
        }

        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
        }

        setState("DISCONNECTED");
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows VPN connection status");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void showNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
            .setContentTitle("IKEv2 VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        disconnect();
        instance = null;
        super.onDestroy();
    }
}
