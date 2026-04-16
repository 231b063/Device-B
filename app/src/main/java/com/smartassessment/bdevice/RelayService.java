package com.smartassessment.bdevice;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.*;
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

    private BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();

    private volatile BluetoothSocket socketA, socketC;
    private volatile BufferedReader readerA, readerC;
    private volatile BufferedWriter writerA, writerC;

    private volatile boolean running = false;

    private Thread threadA, threadC;

    private void log(String msg) {
        Log.d("RelayService", msg);
        if (callback != null) callback.onStatus(msg);
    }

    // ===============================
    // START RELAY
    // ===============================
    public void startRelay(String macC) {

        if (bt == null) {
            log("Bluetooth not supported!");
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

    // ===============================
    // ACCEPT DEVICE A
    // ===============================
    private void acceptLoopA() {
        try {
            BluetoothServerSocket server =
                    bt.listenUsingRfcommWithServiceRecord("DeviceB", SPP_UUID);

            log("Waiting for Device A...");

            while (running) {

                bt.cancelDiscovery();

                BluetoothSocket s = server.accept();

                if (s != null) {
                    log("A connected: " + s.getRemoteDevice().getAddress());

                    closeSocket(socketA);
                    socketA = s;

                    setupAStreams();
                }
            }

        } catch (Exception e) {
            log("Accept error: " + e.getMessage());
        }
    }

    // ===============================
    // CONNECT TO DEVICE C
    // ===============================
    private void connectLoopC(String mac) {
        while (running) {

            try {
                if (socketC != null && socketC.isConnected()) {
                    Thread.sleep(500);
                    continue;
                }

                log("Connecting to C...");

                BluetoothDevice dev = bt.getRemoteDevice(mac);
                BluetoothSocket s =
                        dev.createRfcommSocketToServiceRecord(SPP_UUID);

                bt.cancelDiscovery();
                s.connect();

                log("Connected to C");

                closeSocket(socketC);
                socketC = s;

                setupCStreams();

            } catch (Exception e) {
                log("Retry C connection...");
                try { Thread.sleep(2000); } catch (Exception ignored) {}
            }
        }
    }

    // ===============================
    // SETUP STREAMS
    // ===============================
    private void setupAStreams() {
        try {
            readerA = new BufferedReader(
                    new InputStreamReader(socketA.getInputStream()));
            writerA = new BufferedWriter(
                    new OutputStreamWriter(socketA.getOutputStream()));

            startListenA();

        } catch (Exception e) {
            log("A stream error");
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
            log("C stream error");
            closeSocket(socketC);
        }
    }

    // ===============================
    // LISTEN FROM A
    // ===============================
    private void startListenA() {

        if (threadA != null && threadA.isAlive()) return;

        threadA = new Thread(() -> {
            try {
                String line;

                while (running &&
                        readerA != null &&
                        (line = readerA.readLine()) != null) {

                    log("A → B: " + line);

                    BufferedWriter w = writerC;
                    if (w != null) {
                        w.write(line + "\n");
                        w.flush();
                    }
                }

            } catch (Exception e) {
                log("A disconnected");
            } finally {
                closeSocket(socketA);
                socketA = null;
                readerA = null;
                writerA = null;
            }
        });

        threadA.start();
    }

    // ===============================
    // LISTEN FROM C
    // ===============================
    private void startListenC() {

        if (threadC != null && threadC.isAlive()) return;

        threadC = new Thread(() -> {
            try {
                String line;

                while (running &&
                        readerC != null &&
                        (line = readerC.readLine()) != null) {

                    log("C → B: " + line);

                    BufferedWriter w = writerA;
                    if (w != null) {
                        w.write(line + "\n");
                        w.flush();
                    }
                }

            } catch (Exception e) {
                log("C disconnected");
            } finally {
                closeSocket(socketC);
                socketC = null;
                readerC = null;
                writerC = null;
            }
        });

        threadC.start();
    }

    // ===============================
    // CLOSE SOCKET
    // ===============================
    private void closeSocket(BluetoothSocket s) {
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {}
    }
}