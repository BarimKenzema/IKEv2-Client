package com.ikev2client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.FileWriter;
import java.io.File;

public class IKEv2VpnService extends VpnService {

    private static final String TAG = "IKEv2VPN";
    private static final String CHANNEL_ID = "ikev2_vpn";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_CONNECT = "CONNECT";
    public static final String ACTION_DISCONNECT = "DISCONNECT";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_CA_CERT = "ca_cert";
    public static final String EXTRA_PROFILE_NAME = "profile_name";
    public static final String EXTRA_DNS = "dns";

    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;
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
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (stateListener != null) stateListener.onStateChanged(state);
                }
            });
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnect();
            return START_NOT_STICKY;
        }

        if (ACTION_CONNECT.equals(intent.getAction())) {
            String server = intent.getStringExtra(EXTRA_SERVER);
            String username = intent.getStringExtra(EXTRA_USERNAME);
            String password = intent.getStringExtra(EXTRA_PASSWORD);
            String caCert = intent.getStringExtra(EXTRA_CA_CERT);
            String name = intent.getStringExtra(EXTRA_PROFILE_NAME);
            String dns = intent.getStringExtra(EXTRA_DNS);
            connect(server, username, password, caCert, name, dns);
        }

        return START_STICKY;
    }

    private void connect(String server, String username, String password,
                         String caCert, String name, String dns) {
        setState("CONNECTING");
        showNotification("Connecting to " + name + "...");

        vpnThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Save CA certificate
                    File certDir = new File(getFilesDir(), "certs");
                    if (!certDir.exists()) certDir.mkdirs();
                    File certFile = new File(certDir, "ca.pem");
                    FileWriter cw = new FileWriter(certFile);
                    cw.write(caCert);
                    cw.close();

                    // Load strongSwan native libraries
                    try {
                        System.loadLibrary("androidbridge");
                        System.loadLibrary("charon");
                        System.loadLibrary("ipsec");
                        System.loadLibrary("strongswan");
                    } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "Failed to load native libraries: " + e.getMessage());
                        setState("ERROR");
                        showNotification("Error: Native libraries not found");
                        return;
                    }

                    // Create VPN interface
                    Builder builder = new Builder();
                    builder.setSession(name);
                    builder.addAddress("10.0.0.2", 32);
                    builder.addRoute("0.0.0.0", 0);
                    builder.setMtu(1400);

                    if (dns != null && !dns.isEmpty()) {
                        builder.addDnsServer(dns);
                    } else {
                        builder.addDnsServer("1.1.1.1");
                    }

                    vpnInterface = builder.establish();

                    if (vpnInterface != null) {
                        setState("CONNECTED");
                        showNotification("Connected to " + name);

                        // Keep the thread alive
                        while (!Thread.interrupted()) {
                            Thread.sleep(1000);
                        }
                    } else {
                        setState("ERROR");
                        showNotification("Failed to create VPN tunnel");
                    }

                } catch (InterruptedException e) {
                    Log.i(TAG, "VPN thread interrupted");
                } catch (Exception e) {
                    Log.e(TAG, "VPN error", e);
                    setState("ERROR");
                    showNotification("Error: " + e.getMessage());
                }
            }
        });
        vpnThread.start();
    }

    private void disconnect() {
        setState("DISCONNECTING");

        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
        }

        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing VPN", e);
        }

        setState("DISCONNECTED");
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void showNotification(String text) {
        Intent ni = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, ni, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, CHANNEL_ID);
        } else {
            nb = new Notification.Builder(this);
        }

        Notification n = nb
            .setContentTitle("IKEv2 VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();

        startForeground(NOTIF_ID, n);
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
