package engine.pp;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main extends JFrame {

    // Komponenty GUI
    private JTextField hostField, portsField, intervalField;
    private JButton startButton, stopButton;
    private JTextArea logArea;
    private JLabel statusLabel;
    private JPanel statusIndicator;

    // Komponenty do wykresu
    private TimeSeries latencySeries;
    private ChartPanel chartPanel;

    // Wątek do monitorowania w tle
    private MonitorWorker monitorWorker;

    public Main() {
        super("Network Monitor");
        initComponents();
        layoutComponents();
        setFrameProperties();
    }

    private void initComponents() {
        // Pola tekstowe
        hostField = new JTextField("google.com", 15);
        portsField = new JTextField("80, 443", 15);
        intervalField = new JTextField("5", 5);

        // Przyciski
        startButton = new JButton("Start");
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);

        // Obszar logów
        logArea = new JTextArea(10, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        // Pasek statusu
        statusLabel = new JLabel("Status: IDLE");
        statusIndicator = new JPanel();
        statusIndicator.setPreferredSize(new Dimension(20, 20));
        statusIndicator.setBackground(Color.GRAY);
        statusIndicator.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

        // Wykres
        this.latencySeries = new TimeSeries("Ping Latency (ms)");
        TimeSeriesCollection dataset = new TimeSeriesCollection(this.latencySeries);
        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                "Ping Latency Over Time", "Time", "Latency (ms)", dataset, true, true, false);

        // Dostosowanie wyglądu wykresu
        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setBackgroundPaint(Color.LIGHT_GRAY);
        plot.getDomainAxis().setAutoRange(true);
        ((NumberAxis) plot.getRangeAxis()).setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        this.chartPanel = new ChartPanel(chart);

        // Dodanie akcji do przycisków
        startButton.addActionListener(e -> startMonitoring());
        stopButton.addActionListener(e -> stopMonitoring());
    }

    private void layoutComponents() {
        // Panel wejściowy (góra)
        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        inputPanel.add(new JLabel("Host:"));
        inputPanel.add(hostField);
        inputPanel.add(new JLabel("Ports (csv):"));
        inputPanel.add(portsField);
        inputPanel.add(new JLabel("Interval (s):"));
        inputPanel.add(intervalField);
        inputPanel.add(startButton);
        inputPanel.add(stopButton);
        add(inputPanel, BorderLayout.NORTH);

        // Panel statusu (dół)
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.add(statusIndicator);
        statusPanel.add(statusLabel);
        add(statusPanel, BorderLayout.SOUTH);

        // Panel główny (środek) z podziałem
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartPanel, new JScrollPane(logArea));
        splitPane.setResizeWeight(0.6); // Wykres zajmuje 60% miejsca
        add(splitPane, BorderLayout.CENTER);
    }

    private void setFrameProperties() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null); // Wyśrodkowanie okna
        setMinimumSize(new Dimension(800, 600));
    }

    private void startMonitoring() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Hostname cannot be empty.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int interval;
        try {
            interval = Integer.parseInt(intervalField.getText().trim());
            if (interval <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Interval must be a positive integer.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Czyszczenie poprzednich danych
        logArea.setText("");
        latencySeries.clear();

        // Ustawienie stanu GUI
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        hostField.setEditable(false);
        portsField.setEditable(false);
        intervalField.setEditable(false);
        statusLabel.setText("Status: RUNNING");

        // Uruchomienie workera w tle
        monitorWorker = new MonitorWorker(host, portsField.getText().trim(), interval);
        monitorWorker.execute();
    }

    private void stopMonitoring() {
        if (monitorWorker != null) {
            monitorWorker.cancel(true); // Sygnał do zatrzymania wątku
        }
    }

    // Klasa wewnętrzna do obsługi monitorowania w tle, aby nie blokować GUI
    private class MonitorWorker extends SwingWorker<Void, MonitorUpdate> {
        private final String host;
        private final String portsStr;
        private final int interval;
        private final boolean isWindows;
        private final Pattern pingLatencyPattern;

        public MonitorWorker(String host, String portsStr, int interval) {
            this.host = host;
            this.portsStr = portsStr;
            this.interval = interval;
            this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");

            // Wzorce do wyciągania czasu odpowiedzi z komendy ping
            if (isWindows) {
                // Przykład: "Czas=23ms"
                pingLatencyPattern = Pattern.compile("time[=<](\\d+)ms");
            } else {
                // Przykład: "time=23.4 ms"
                pingLatencyPattern = Pattern.compile("time=(\\d+(\\.\\d+)?) ms");
            }
        }

        @Override
        protected Void doInBackground() throws Exception {
            while (!isCancelled()) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                StringBuilder logMessage = new StringBuilder();

                // 1. Sprawdzenie PING
                PingResult pingResult = pingHost();

                logMessage.append(String.format("[%s] Host %s is %s.", timestamp, host, pingResult.isUp ? "UP" : "DOWN"));
                if (pingResult.isUp) {
                    logMessage.append(String.format(" (Latency: %.2f ms)", pingResult.latency));
                }
                logMessage.append("\n");

                // 2. Sprawdzenie portów (jeśli host odpowiada i porty są podane)
                if (pingResult.isUp && !portsStr.isEmpty()) {
                    try {
                        String[] portParts = portsStr.split(",");
                        for (String p : portParts) {
                            int port = Integer.parseInt(p.trim());
                            boolean isOpen = checkPort(host, port, 2000);
                            logMessage.append(String.format("  - Port %d is %s\n", port, isOpen ? "OPEN" : "CLOSED"));
                        }
                    } catch (NumberFormatException e) {
                        logMessage.append("  - Invalid port list format.\n");
                    }
                }

                logMessage.append("------------------------------------------\n");

                // Przekazanie wyników do GUI
                publish(new MonitorUpdate(logMessage.toString(), pingResult.isUp, pingResult.latency));

                // Czekanie na następny cykl
                Thread.sleep(interval * 1000L);
            }
            return null;
        }

        @Override
        protected void process(List<MonitorUpdate> chunks) {
            // Ta metoda jest wykonywana w wątku GUI
            for (MonitorUpdate update : chunks) {
                logArea.append(update.logMessage);
                logArea.setCaretPosition(logArea.getDocument().getLength()); // Auto-scroll

                if (update.isUp) {
                    statusIndicator.setBackground(Color.GREEN);
                    // Dodaj dane do wykresu tylko, jeśli host odpowiada
                    latencySeries.addOrUpdate(new Millisecond(), update.latency);
                } else {
                    statusIndicator.setBackground(Color.RED);
                }
            }
        }

        @Override
        protected void done() {
            // Ta metoda jest wywoływana po zakończeniu `doInBackground`
            try {
                get(); // Sprawdza, czy nie wystąpił wyjątek w tle
            } catch (CancellationException e) {
                // Oczekiwany wyjątek, gdy użytkownik naciśnie "Stop". Ignorujemy go.
                logArea.append("Monitoring stopped by user.\n");
            } catch (InterruptedException | ExecutionException e) {
                // Inne błędy, które mogły wystąpić w doInBackground
                if (!(e.getCause() instanceof InterruptedException)) {
                    logArea.append("An error occurred during monitoring: " + e.getCause().getMessage() + "\n");
                }
            } finally {
                // Przywrócenie stanu GUI - ten blok wykona się zawsze
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                hostField.setEditable(true);
                portsField.setEditable(true);
                intervalField.setEditable(true);
                statusLabel.setText("Status: STOPPED");
                statusIndicator.setBackground(Color.GRAY);
            }
        }


        private PingResult pingHost() {
            String[] command = isWindows
                    ? new String[]{"ping", "-n", "1", "-w", "2000", host} // -w 2000ms timeout
                    : new String[]{"ping", "-c", "1", "-W", "2", host};   // -W 2s timeout

            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                Process process = pb.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    Matcher matcher = pingLatencyPattern.matcher(output.toString());
                    if (matcher.find()) {
                        double latency = Double.parseDouble(matcher.group(1));
                        return new PingResult(true, latency);
                    }
                    return new PingResult(true, 0); // Sukces, ale nie udało się sparsować opóźnienia
                } else {
                    return new PingResult(false, -1);
                }
            } catch (IOException | InterruptedException e) {
                // Jeśli wątek zostanie przerwany podczas pingu (przez cancel), złapiemy tu InterruptedException
                Thread.currentThread().interrupt(); // Przywrócenie flagi przerwania
                return new PingResult(false, -1);
            }
        }

        private boolean checkPort(String host, int port, int timeout) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), timeout);
                return true;
            } catch (IOException e) {
                return false; // Timeout lub odmowa połączenia
            }
        }
    }

    // Pomocnicze klasy do przekazywania danych
    private static class PingResult {
        final boolean isUp;
        final double latency;
        PingResult(boolean isUp, double latency) {
            this.isUp = isUp;
            this.latency = latency;
        }
    }

    private static class MonitorUpdate {
        final String logMessage;
        final boolean isUp;
        final double latency;
        MonitorUpdate(String logMessage, boolean isUp, double latency) {
            this.logMessage = logMessage;
            this.isUp = isUp;
            this.latency = latency;
        }
    }

    public static void main(String[] args) {
        // Ustawienie wyglądu i działania aplikacji w wątku zdarzeń GUI
        SwingUtilities.invokeLater(() -> {
            new Main().setVisible(true);
        });
    }
}