package com.smartassessment.bdevice;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.util.UUID;

public class RelayService {

    public interface RelayCallback {
        void onStatus(String msg);
    }

    private RelayCallback callback;
    private Context context;

    public RelayService(RelayCallback cb, Context ctx) {
        this.callback = cb;
        this.context = ctx;
    }

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();

    private volatile BluetoothSocket socketA;
    private volatile BluetoothSocket socketC;

    private volatile BufferedReader readerA, readerC;
    private volatile BufferedWriter writerA, writerC;

    private volatile boolean running = false;

    private void log(String msg) {
        Log.d("RelayService", msg);
        if (callback != null) callback.onStatus(msg);
    }

    // ======================================================
    // PERMISSION CHECK UTILITIES
    // ======================================================
    private boolean hasBtConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasBtScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // ======================================================
    // START RELAY – SAFE VERSION
    // ======================================================
    public void startRelay(String macC) {

        if (!hasBtConnectPermission()) {
            log("Bluetooth CONNECT permission missing!");
            return;
        }

        if (!hasBtScanPermission()) {
            log("Bluetooth SCAN permission missing!");
            return;
        }

        if (running) return;
        running = true;

        new Thread(this::acceptLoopA).start();
        new Thread(() -> connectLoopC(macC)).start();
    }

    public void stopRelay() {
        running = false;
        closeSocket(socketA);
        closeSocket(socketC);
    }

    // ======================================================
    // ACCEPT DEVICE A
    // ======================================================
    private void acceptLoopA() {
        try {
            if (!hasBtConnectPermission()) {
                log("Missing BLUETOOTH_CONNECT permission for server socket.");
                return;
            }

            BluetoothServerSocket server =
                    bt.listenUsingRfcommWithServiceRecord("DeviceB", SPP_UUID);

            log("Server socket opened, waiting for Device A...");

            while (running) {
                try {
                    BluetoothSocket s = server.accept();

                    if (s != null) {
                        log("Accepted connection from A: " + s.getRemoteDevice().getAddress());
                        closeSocket(socketA);
                        socketA = s;
                        setupAStreams();
                    }

                } catch (IOException e) {
                    log("Accept failed: " + e.getMessage());
                }

                Thread.sleep(200);
            }

            try { server.close(); } catch (Exception ignored) {}

        } catch (SecurityException se) {
            log("SecurityException: Missing CONNECT permission!");
        } catch (Exception e) {
            log("Server socket open failed: " + e);
        }
    }

    // ======================================================
    // CONNECT TO DEVICE C (Client Mode)
    // ======================================================
    private void connectLoopC(String mac) {
        while (running) {

            if (!hasBtConnectPermission()) {
                log("Cannot connect to C: Missing BLUETOOTH_CONNECT permission.");
                return;
            }

            if (socketC != null && socketC.isConnected()) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                continue;
            }

            try {
                log("Trying connect to C: " + mac);

                BluetoothDevice devC = bt.getRemoteDevice(mac);

                BluetoothSocket sC =
                        devC.createRfcommSocketToServiceRecord(SPP_UUID);

                bt.cancelDiscovery();

                sC.connect();

                log("Connected to C: " + mac);

                closeSocket(socketC);
                socketC = sC;

                setupCStreams();

            } catch (SecurityException se) {
                log("SecurityException: Missing BT permission!");
                return;

            } catch (Exception e) {
                log("Connect to C failed: " + e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    // ======================================================
    // STREAM SETUP
    // ======================================================
    private void setupAStreams() {
        try {
            readerA = new BufferedReader(
                    new InputStreamReader(socketA.getInputStream()));
            writerA = new BufferedWriter(
                    new OutputStreamWriter(socketA.getOutputStream()));

            startListenA();

        } catch (Exception e) {
            log("setupAStreams error: " + e.getMessage());
            closeSocket(socketA);
        }
    }

    private void setupCStreams() {
        try {
            readerC = new BufferedReader(
                    new InputStreamReader(socketC.getInputStream()));
            writerC = new BufferedWriter(
                    new OutputStreamWriter(socketC.getOutputStream()));

            startListenC();

        } catch (Exception e) {
            log("setupCStreams error: " + e.getMessage());
            closeSocket(socketC);
        }
    }

    // ======================================================
    // LISTEN FROM A
    // ======================================================
    private void startListenA() {
        new Thread(() -> {
            try {
                String line;
                while (running && readerA != null &&
                        (line = readerA.readLine()) != null) {

                    log("A → B: " + line);

                    if (writerC != null) {
                        writerC.write(line + "\n");
                        writerC.flush();
                    } else {
                        log("C not connected; cannot forward.");
                    }
                }
            } catch (Exception e) {
                log("Lost connection from A: " + e.getMessage());
            } finally {
                closeSocket(socketA);
            }
        }).start();
    }

    // ======================================================
    // LISTEN FROM C
    // ======================================================
    private void startListenC() {
        new Thread(() -> {
            try {
                String line;
                while (running && readerC != null &&
                        (line = readerC.readLine()) != null) {

                    log("C → B: " + line);

                    if (writerA != null) {
                        writerA.write(line + "\n");
                        writerA.flush();
                    } else {
                        log("A not connected; cannot forward.");
                    }
                }

            } catch (Exception e) {
                log("Lost connection from C: " + e.getMessage());
            } finally {
                closeSocket(socketC);
            }
        }).start();
    }

    // ======================================================
    // CLOSE SOCKET SAFELY
    // ======================================================
    private void closeSocket(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) {}
    }
}
