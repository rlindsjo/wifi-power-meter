package net.tilialacus.powermeter;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.Callable;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.PinPullResistance;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.gpio.trigger.GpioCallbackTrigger;
import com.pi4j.io.gpio.trigger.GpioTrigger;

public class Main {
    private static final double WATTS_MS = 360.0D;
    private static final long PERIOD_MS = 5000L;

    private final TimestampLog timestamp;
    private final DataSender sender;
    private long last;
    private long nextUpdate;

    public Main(TimestampLog timestamp, DataSender sender) {
        this.timestamp = timestamp;
        this.sender = sender;
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        String server = args[0];
        String id = args[1]; 

        TimestampLog timestamp = new TimestampLog("ticks.log");
        DataSender sender = new DataSender(server, id);

        Main main = new Main(timestamp, sender);

        GpioController gpio = GpioFactory.getInstance();
        GpioPinDigitalInput input = gpio.provisionDigitalInputPin(RaspiPin.GPIO_02, PinPullResistance.PULL_DOWN);
        input.addTrigger(new GpioTrigger[] { new GpioCallbackTrigger(PinState.HIGH, main.measureCallback()) });

        while (true) {
            Thread.sleep(5000L);
            timestamp.flush();
        }
    }

    public Callable<Void> measureCallback() {
        return new Callable<Void>() {
            public Void call() throws Exception {
                measure();
                return null;
            }
        };
    }

    private void measure() {
        try {
            long now = System.currentTimeMillis();
            timestamp.store(now);
    
            long delta = now - last;
            double p = WATTS_MS / delta;
            this.last = now;
    
            if (isTimeToSend(now)) {
                this.nextUpdate = (now + PERIOD_MS);
                sender.send(p);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isTimeToSend(long now) {
        return now > nextUpdate;
    }

    private static final class TimestampLog {
        private final Writer writer;
        
        public TimestampLog(String name) throws IOException {
            writer = new BufferedWriter(new FileWriter(name, true));
        }

        public synchronized void store(long now) throws IOException {
            writer.append(String.valueOf(now));
            writer.append('\n');
        }

        public synchronized void flush() throws IOException {
            writer.flush();
        }
    } 

    private static final class DataSender {
        private final URL target;
        
        public DataSender(String server, String id) throws MalformedURLException {
            target = new URL(server + "/" + id);
        }
        
        public void send(double p) throws IOException {
            String dataString = String.format("value=%1$.3f", Double.valueOf(p));
            System.err.println("Sending " + dataString); 
            byte[] data = dataString.getBytes();
            HttpURLConnection connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Content-Length", Integer.toString(data.length));
            connection.setDoOutput(true);
            connection.connect();
            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(data);
            outputStream.flush();
            outputStream.close();
            connection.getInputStream();
            connection.disconnect();
        }
    }
}
