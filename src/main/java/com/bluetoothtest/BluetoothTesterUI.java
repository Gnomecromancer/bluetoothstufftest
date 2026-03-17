package com.bluetoothtest;

import javax.bluetooth.*;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Two-computer Bluetooth peer tester using ultreia.io BlueCove (RFCOMM/SPP).
 *
 * Usage:
 *   Computer A — open the "Server" tab, click "Start Server", wait.
 *   Computer B — open the "Client" tab, scan, select Computer A, click "Connect".
 *   Both sides can then send messages to each other.
 */
public class BluetoothTesterUI extends JFrame implements DiscoveryListener {

    // Shared service UUID — must match on both computers
    private static final String SERVICE_UUID = "27012F0C5B8E4B4E9B3A6B9D0E3A4F5A";
    private static final String SERVICE_NAME  = "BTPeerTester";

    // --- Server tab ---
    private JButton    startServerButton;
    private JButton    stopServerButton;
    private JLabel     serverStatusLabel;

    // --- Client tab ---
    private JButton                  scanButton;
    private JButton                  connectButton;
    private DefaultListModel<DeviceEntry> deviceListModel;
    private JList<DeviceEntry>       deviceJList;

    // --- Shared messaging area ---
    private JTextArea  chatArea;
    private JTextField messageField;
    private JButton    sendButton;
    private JLabel     connectionStatusLabel;

    // --- Bluetooth / IO state ---
    private final List<RemoteDevice>  discoveredDevices = new ArrayList<>();
    private final List<String>        serviceURLs       = new ArrayList<>();
    private final Object              inquiryLock       = new Object();
    private final Object              serviceLock       = new Object();

    private volatile StreamConnectionNotifier serverNotifier;
    private volatile StreamConnection         connection;
    private volatile PrintWriter              out;
    private volatile boolean                  serverRunning = false;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public BluetoothTesterUI() {
        super("BlueCove Peer Tester");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(780, 580);
        setMinimumSize(new Dimension(620, 480));
        buildUI();
        setLocationRelativeTo(null);
        setVisible(true);
        initLocalDevice();
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top status bar
        connectionStatusLabel = new JLabel("Not connected");
        connectionStatusLabel.setFont(connectionStatusLabel.getFont().deriveFont(Font.BOLD));
        root.add(connectionStatusLabel, BorderLayout.NORTH);

        // Tabbed control pane
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Server  (wait for connection)", buildServerPanel());
        tabs.addTab("Client  (connect to server)",   buildClientPanel());
        root.add(tabs, BorderLayout.CENTER);

        // Messaging panel at the bottom
        root.add(buildMessagingPanel(), BorderLayout.SOUTH);

        add(root);
    }

    private JPanel buildServerPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JTextArea instructions = new JTextArea(
            "1. Click \"Start Server\" on THIS computer.\n" +
            "2. On the OTHER computer, open the Client tab, scan, select this device, and click Connect.\n" +
            "3. Once connected, use the message box below to communicate."
        );
        instructions.setEditable(false);
        instructions.setBackground(panel.getBackground());
        instructions.setFont(UIManager.getFont("Label.font"));
        instructions.setWrapStyleWord(true);
        instructions.setLineWrap(true);

        JPanel infoPanel = new JPanel(new BorderLayout(4, 8));
        infoPanel.add(instructions, BorderLayout.NORTH);
        infoPanel.add(new JLabel("Service UUID: " + SERVICE_UUID), BorderLayout.CENTER);

        serverStatusLabel = new JLabel("Status: Idle");
        infoPanel.add(serverStatusLabel, BorderLayout.SOUTH);
        panel.add(infoPanel, BorderLayout.NORTH);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        startServerButton = new JButton("Start Server");
        startServerButton.addActionListener(e -> startServer());

        stopServerButton = new JButton("Stop Server");
        stopServerButton.setEnabled(false);
        stopServerButton.addActionListener(e -> stopServer());

        btnPanel.add(startServerButton);
        btnPanel.add(stopServerButton);
        panel.add(btnPanel, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildClientPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        deviceListModel = new DefaultListModel<>();
        deviceJList = new JList<>(deviceListModel);
        deviceJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceJList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane listScroll = new JScrollPane(deviceJList);
        listScroll.setBorder(new TitledBorder("Discovered Devices — select one, then click Connect"));
        panel.add(listScroll, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        scanButton = new JButton("Scan for Devices");
        scanButton.addActionListener(e -> startScan());

        connectButton = new JButton("Connect to Selected");
        connectButton.setEnabled(false);
        connectButton.addActionListener(e -> connectToSelected());

        deviceJList.addListSelectionListener(
            e -> connectButton.setEnabled(deviceJList.getSelectedIndex() >= 0 && connection == null)
        );

        btnPanel.add(scanButton);
        btnPanel.add(connectButton);
        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildMessagingPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));

        chatArea = new JTextArea(9, 0);
        chatArea.setEditable(false);
        chatArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(new TitledBorder("Log / Messages"));
        panel.add(chatScroll, BorderLayout.CENTER);

        JPanel sendRow = new JPanel(new BorderLayout(4, 0));
        messageField = new JTextField();
        messageField.setEnabled(false);
        messageField.addActionListener(e -> sendMessage());

        sendButton = new JButton("Send");
        sendButton.setEnabled(false);
        sendButton.addActionListener(e -> sendMessage());

        sendRow.add(messageField, BorderLayout.CENTER);
        sendRow.add(sendButton,   BorderLayout.EAST);
        panel.add(sendRow, BorderLayout.SOUTH);

        return panel;
    }

    // -------------------------------------------------------------------------
    // Local device init
    // -------------------------------------------------------------------------

    private void initLocalDevice() {
        new Thread(() -> {
            try {
                LocalDevice local = LocalDevice.getLocalDevice();
                String name = local.getFriendlyName();
                String addr = formatAddress(local.getBluetoothAddress());
                SwingUtilities.invokeLater(() ->
                    setTitle("BlueCove Peer Tester — " + name + "  [" + addr + "]")
                );
                log("[System] Local Bluetooth ready: " + name + "  [" + addr + "]");
            } catch (BluetoothStateException e) {
                log("[ERROR] Bluetooth init failed: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    startServerButton.setEnabled(false);
                    scanButton.setEnabled(false);
                    setConnectionStatus("Bluetooth unavailable: " + e.getMessage(), false);
                });
            }
        }, "bt-init").start();
    }

    // -------------------------------------------------------------------------
    // Server mode
    // -------------------------------------------------------------------------

    private void startServer() {
        startServerButton.setEnabled(false);
        stopServerButton.setEnabled(true);
        serverRunning = true;
        setServerStatus("Opening RFCOMM socket...");

        new Thread(() -> {
            try {
                String url = "btspp://localhost:" + SERVICE_UUID
                        + ";name=" + SERVICE_NAME
                        + ";authenticate=false;encrypt=false";

                serverNotifier = (StreamConnectionNotifier) Connector.open(url);
                log("[Server] Listening on service \"" + SERVICE_NAME + "\"");
                log("[Server] UUID: " + SERVICE_UUID);
                SwingUtilities.invokeLater(() -> setServerStatus("Listening — waiting for client..."));

                StreamConnection conn = serverNotifier.acceptAndOpen(); // blocks
                if (!serverRunning) return; // stopped while waiting

                SwingUtilities.invokeLater(() -> {
                    stopServerButton.setEnabled(false);
                    startServerButton.setEnabled(false); // keep disabled while connected
                });

                handleConnection(conn, "client");

            } catch (IOException e) {
                if (serverRunning) {
                    log("[Server] Error: " + e.getMessage());
                    SwingUtilities.invokeLater(() -> setServerStatus("Error: " + e.getMessage()));
                }
            } finally {
                closeNotifier();
                SwingUtilities.invokeLater(() -> {
                    startServerButton.setEnabled(true);
                    stopServerButton.setEnabled(false);
                    if (serverRunning) setServerStatus("Stopped.");
                    serverRunning = false;
                });
            }
        }, "bt-server").start();
    }

    private void stopServer() {
        serverRunning = false;
        closeNotifier();
        setServerStatus("Stopped.");
        log("[Server] Server stopped.");
        startServerButton.setEnabled(true);
        stopServerButton.setEnabled(false);
    }

    private void closeNotifier() {
        if (serverNotifier != null) {
            try { serverNotifier.close(); } catch (IOException ignored) {}
            serverNotifier = null;
        }
    }

    // -------------------------------------------------------------------------
    // Client mode — device scan
    // -------------------------------------------------------------------------

    private void startScan() {
        scanButton.setEnabled(false);
        connectButton.setEnabled(false);
        deviceListModel.clear();
        discoveredDevices.clear();
        log("[Client] Starting device inquiry...");

        new Thread(() -> {
            try {
                LocalDevice local = LocalDevice.getLocalDevice();
                DiscoveryAgent agent = local.getDiscoveryAgent();
                synchronized (inquiryLock) {
                    agent.startInquiry(DiscoveryAgent.GIAC, this);
                    inquiryLock.wait();
                }
            } catch (BluetoothStateException | InterruptedException e) {
                log("[Client] Scan error: " + e.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> scanButton.setEnabled(true));
            }
        }, "bt-scan").start();
    }

    // -------------------------------------------------------------------------
    // Client mode — connect
    // -------------------------------------------------------------------------

    private void connectToSelected() {
        DeviceEntry entry = deviceJList.getSelectedValue();
        if (entry == null) return;

        connectButton.setEnabled(false);
        scanButton.setEnabled(false);
        log("[Client] Searching for service \"" + SERVICE_NAME + "\" on " + entry + "...");

        new Thread(() -> {
            try {
                UUID[] uuids   = { new UUID(SERVICE_UUID, false) };
                int[]  attrSet = { 0x0100 }; // ServiceName attribute

                serviceURLs.clear();
                LocalDevice local = LocalDevice.getLocalDevice();
                DiscoveryAgent agent = local.getDiscoveryAgent();

                synchronized (serviceLock) {
                    agent.searchServices(attrSet, uuids, entry.device, this);
                    serviceLock.wait(20_000); // timeout after 20 s
                }

                if (serviceURLs.isEmpty()) {
                    log("[Client] Service not found. Is the server running on the other computer?");
                    SwingUtilities.invokeLater(() -> {
                        scanButton.setEnabled(true);
                        connectButton.setEnabled(true);
                    });
                    return;
                }

                String serviceUrl = serviceURLs.get(0);
                log("[Client] Connecting to: " + serviceUrl);
                StreamConnection conn = (StreamConnection) Connector.open(serviceUrl);
                handleConnection(conn, "server");

            } catch (InterruptedException | IOException e) {
                log("[Client] Connect error: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    scanButton.setEnabled(true);
                    connectButton.setEnabled(deviceJList.getSelectedIndex() >= 0);
                });
            }
        }, "bt-connect").start();
    }

    // -------------------------------------------------------------------------
    // Shared connection handler
    // -------------------------------------------------------------------------

    private void handleConnection(StreamConnection conn, String peerRole) {
        connection = conn;
        try {
            InputStream    is     = conn.openInputStream();
            OutputStream   os     = conn.openOutputStream();
            OutputStreamWriter osw = new OutputStreamWriter(os);
            out = new PrintWriter(osw, true);

            SwingUtilities.invokeLater(() -> {
                setConnectionStatus("Connected to " + peerRole, true);
                messageField.setEnabled(true);
                sendButton.setEnabled(true);
                messageField.requestFocus();
                setServerStatus(peerRole.equals("client") ? "Connected!" : "Status: Idle");
            });
            log("[System] Connection established with " + peerRole + ". You can now send messages.");

            // Read loop — blocks until peer disconnects
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                final String msg = line;
                log("[" + peerRole + "] " + msg);
            }

        } catch (IOException e) {
            log("[System] Connection closed: " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    private void sendMessage() {
        String text = messageField.getText().trim();
        if (text.isEmpty() || out == null) return;
        out.println(text);
        log("[me] " + text);
        messageField.setText("");
    }

    private void disconnect() {
        if (connection != null) {
            try { connection.close(); } catch (IOException ignored) {}
            connection = null;
        }
        out = null;
        SwingUtilities.invokeLater(() -> {
            setConnectionStatus("Disconnected", false);
            messageField.setEnabled(false);
            sendButton.setEnabled(false);
            scanButton.setEnabled(true);
            startServerButton.setEnabled(true);
            stopServerButton.setEnabled(false);
            connectButton.setEnabled(deviceJList.getSelectedIndex() >= 0);
            setServerStatus("Idle");
        });
        log("[System] Disconnected.");
    }

    // -------------------------------------------------------------------------
    // DiscoveryListener
    // -------------------------------------------------------------------------

    @Override
    public void deviceDiscovered(RemoteDevice btDevice, DeviceClass cod) {
        discoveredDevices.add(btDevice);
        String addr = formatAddress(btDevice.getBluetoothAddress());
        String name;
        try {
            name = btDevice.getFriendlyName(false);
            if (name == null || name.isBlank()) name = "(no name)";
        } catch (Exception e) {
            name = "(unknown)";
        }
        DeviceEntry entry = new DeviceEntry(btDevice, name, addr);
        log("[Client] Found: " + entry);
        SwingUtilities.invokeLater(() -> deviceListModel.addElement(entry));
    }

    @Override
    public void inquiryCompleted(int discType) {
        String result;
        if (discType == DiscoveryListener.INQUIRY_COMPLETED) {
            result = "Scan complete — " + discoveredDevices.size() + " device(s). Select one and click Connect.";
        } else if (discType == DiscoveryListener.INQUIRY_TERMINATED) {
            result = "Scan terminated.";
        } else {
            result = "Scan error (code=" + discType + ").";
        }
        log("[Client] " + result);
        synchronized (inquiryLock) { inquiryLock.notifyAll(); }
    }

    @Override
    public void servicesDiscovered(int transID, ServiceRecord[] servRecord) {
        for (ServiceRecord sr : servRecord) {
            String url = sr.getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
            if (url != null) {
                serviceURLs.add(url);
                log("[Client] Service URL: " + url);
            }
        }
    }

    @Override
    public void serviceSearchCompleted(int transID, int respCode) {
        String code;
        if      (respCode == DiscoveryListener.SERVICE_SEARCH_COMPLETED)           code = "completed";
        else if (respCode == DiscoveryListener.SERVICE_SEARCH_TERMINATED)          code = "terminated";
        else if (respCode == DiscoveryListener.SERVICE_SEARCH_ERROR)               code = "error";
        else if (respCode == DiscoveryListener.SERVICE_SEARCH_NO_RECORDS)          code = "no records found";
        else if (respCode == DiscoveryListener.SERVICE_SEARCH_DEVICE_NOT_REACHABLE) code = "device not reachable";
        else                                                                        code = "code=" + respCode;
        log("[Client] Service search " + code);
        synchronized (serviceLock) { serviceLock.notifyAll(); }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append(msg + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    private void setConnectionStatus(String msg, boolean connected) {
        connectionStatusLabel.setText(msg);
        connectionStatusLabel.setForeground(connected ? new Color(0, 128, 0) : Color.DARK_GRAY);
    }

    private void setServerStatus(String msg) {
        serverStatusLabel.setText("Status: " + msg);
    }

    private static String formatAddress(String raw) {
        if (raw == null || raw.length() != 12) return raw;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(raw, i, i + 2);
        }
        return sb.toString().toUpperCase();
    }

    // -------------------------------------------------------------------------
    // Device list entry
    // -------------------------------------------------------------------------

    private static class DeviceEntry {
        final RemoteDevice device;
        final String name;
        final String address;

        DeviceEntry(RemoteDevice device, String name, String address) {
            this.device  = device;
            this.name    = name;
            this.address = address;
        }

        @Override
        public String toString() {
            return name + "  [" + address + "]";
        }
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(BluetoothTesterUI::new);
    }
}
