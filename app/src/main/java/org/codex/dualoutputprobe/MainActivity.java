package org.codex.dualoutputprobe;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "DualOutputProbe";
    private static final int SAMPLE_RATE = 48000;
    private static final int BLOCK_FRAMES = 960;
    private static final double AMPLITUDE = 0.035;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private TextView deviceList;
    private TextView status;
    private TrackState earpieceState;
    private TrackState speakerState;
    private boolean polling;

    private static final class TrackState {
        final String label;
        final double frequency;
        final AudioTrack track;
        volatile boolean running = true;
        Thread writer;
        boolean preferenceAccepted;

        TrackState(String label, double frequency, AudioTrack track) {
            this.label = label;
            this.frequency = frequency;
            this.track = track;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        buildUi();
        refreshDevices();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("Dual Output Probe");
        title.setTextSize(24);
        root.addView(title, matchWrap());

        TextView instructions = new TextView(this);
        instructions.setText("Test one output at a time, or request both outputs together in either start order. Lower media volume before starting. This does not change system or vendor settings.");
        instructions.setTextSize(16);
        instructions.setPadding(0, dp(12), 0, dp(12));
        root.addView(instructions, matchWrap());

        deviceList = new TextView(this);
        deviceList.setTextSize(14);
        root.addView(deviceList, matchWrap());

        Button refresh = new Button(this);
        refresh.setText("Refresh output devices");
        refresh.setOnClickListener(v -> refreshDevices());
        root.addView(refresh, matchWrap());

        Button earpieceOnly = new Button(this);
        earpieceOnly.setText("Test earpiece only");
        earpieceOnly.setOnClickListener(v -> startSingle(true));
        root.addView(earpieceOnly, matchWrap());

        Button speakerOnly = new Button(this);
        speakerOnly.setText("Test bottom speaker only");
        speakerOnly.setOnClickListener(v -> startSingle(false));
        root.addView(speakerOnly, matchWrap());

        Button earpieceFirst = new Button(this);
        earpieceFirst.setText("Test both (earpiece starts first)");
        earpieceFirst.setOnClickListener(v -> startProbe(false));
        root.addView(earpieceFirst, matchWrap());

        Button speakerFirst = new Button(this);
        speakerFirst.setText("Test both (speaker starts first)");
        speakerFirst.setOnClickListener(v -> startProbe(true));
        root.addView(speakerFirst, matchWrap());

        Button stop = new Button(this);
        stop.setText("Stop tones");
        stop.setOnClickListener(v -> stopProbe());
        root.addView(stop, matchWrap());

        status = new TextView(this);
        status.setTextSize(15);
        status.setGravity(Gravity.TOP);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void refreshDevices() {
        AudioDeviceInfo earpiece = findDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        AudioDeviceInfo speaker = findDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        StringBuilder text = new StringBuilder("Built-in output devices:\n");
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            text.append("• ").append(deviceName(device))
                    .append("  [type=").append(device.getType())
                    .append(", id=").append(device.getId()).append("]\n");
        }
        text.append("\nEarpiece target: ").append(earpiece == null ? "not exposed" : deviceName(earpiece));
        text.append("\nSpeaker target: ").append(speaker == null ? "not exposed" : deviceName(speaker));
        deviceList.setText(text.toString());
        setStatus("Ready. No audio is playing.");
    }

    private AudioDeviceInfo findDevice(int type) {
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == type) return device;
        }
        return null;
    }

    private void startSingle(boolean useEarpiece) {
        stopProbe();
        AudioDeviceInfo target = findDevice(useEarpiece
                ? AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                : AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        if (target == null) {
            setStatus("Cannot run: Android did not expose the requested built-in output.");
            return;
        }
        int minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) {
            setStatus("Cannot run: AudioTrack reported an invalid minimum buffer size (" + minBytes + ").");
            return;
        }
        int bufferBytes = Math.max(minBytes, BLOCK_FRAMES * 2 * 4);
        TrackState state = createTrack(useEarpiece ? "earpiece" : "speaker",
                useEarpiece ? 440.0 : 880.0, bufferBytes, target);
        if (state == null) return;
        if (useEarpiece) earpieceState = state;
        else speakerState = state;
        try {
            state.track.play();
            startWriter(state);
        } catch (IllegalStateException e) {
            stopProbe();
            setStatus("Playback failed to start: " + e.getMessage());
            return;
        }
        polling = true;
        updateRoutes();
        handler.postDelayed(routePoll, 1000);
    }

    private void startProbe(boolean speakerFirst) {
        stopProbe();
        AudioDeviceInfo earpiece = findDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        AudioDeviceInfo speaker = findDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        if (earpiece == null || speaker == null) {
            setStatus("Cannot run: Android did not expose both built-in output devices. Refresh the list and share the result.");
            return;
        }

        int minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) {
            setStatus("Cannot run: AudioTrack reported an invalid minimum buffer size (" + minBytes + ").");
            return;
        }
        int bufferBytes = Math.max(minBytes, BLOCK_FRAMES * 2 * 4);

        if (speakerFirst) {
            speakerState = createTrack("speaker", 880.0, bufferBytes, speaker);
            earpieceState = createTrack("earpiece", 440.0, bufferBytes, earpiece);
        } else {
            earpieceState = createTrack("earpiece", 440.0, bufferBytes, earpiece);
            speakerState = createTrack("speaker", 880.0, bufferBytes, speaker);
        }
        if (earpieceState == null || speakerState == null) {
            stopProbe();
            return;
        }

        try {
            if (speakerFirst) {
                speakerState.track.play();
                earpieceState.track.play();
                startWriter(speakerState);
                startWriter(earpieceState);
            } else {
                earpieceState.track.play();
                speakerState.track.play();
                startWriter(earpieceState);
                startWriter(speakerState);
            }
        } catch (IllegalStateException e) {
            stopProbe();
            setStatus("Playback failed to start: " + e.getMessage());
            return;
        }

        polling = true;
        updateRoutes();
        handler.postDelayed(routePoll, 1000);
    }

    private TrackState createTrack(String label, double frequency, int bufferBytes,
                                   AudioDeviceInfo preferredDevice) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            setStatus("Could not initialize the " + label + " AudioTrack.");
            return null;
        }
        TrackState state = new TrackState(label, frequency, track);
        state.preferenceAccepted = track.setPreferredDevice(preferredDevice);
        Log.i(TAG, label + " preference accepted=" + state.preferenceAccepted
                + " target=" + deviceName(preferredDevice));
        return state;
    }

    private void startWriter(TrackState state) {
        state.writer = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            short[] samples = new short[BLOCK_FRAMES];
            double phase = 0.0;
            double phaseStep = 2.0 * Math.PI * state.frequency / SAMPLE_RATE;
            while (state.running) {
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) (Math.sin(phase) * Short.MAX_VALUE * AMPLITUDE);
                    phase += phaseStep;
                    if (phase >= 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                }
                int written = state.track.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
                if (written < 0) {
                    Log.e(TAG, state.label + " write failed=" + written);
                    break;
                }
            }
        }, "Tone-" + state.label);
        state.writer.start();
    }

    private final Runnable routePoll = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            updateRoutes();
            handler.postDelayed(this, 1000);
        }
    };

    private void updateRoutes() {
        if (earpieceState == null && speakerState == null) return;
        StringBuilder report = new StringBuilder();
        if (earpieceState != null && speakerState != null) {
            String e = routeName(earpieceState);
            String s = routeName(speakerState);
            report.append("Playing both tracks.\n\n")
                    .append("Earpiece track: request accepted=")
                    .append(earpieceState.preferenceAccepted).append("; actual route=").append(e).append('\n')
                    .append("Speaker track: request accepted=")
                    .append(speakerState.preferenceAccepted).append("; actual route=").append(s).append("\n\n")
                    .append("A distinct route for each track is required for simultaneous output.");
        } else {
            TrackState state = earpieceState != null ? earpieceState : speakerState;
            report.append("Playing the ").append(state.label).append(" track only.\n\n")
                    .append("Request accepted=").append(state.preferenceAccepted)
                    .append("; actual route=").append(routeName(state)).append("\n\n")
                    .append("This checks whether the requested route works by itself.");
        }
        String text = report.toString();
        setStatus(text);
        Log.i(TAG, text.replace('\n', ' '));
    }

    private String routeName(TrackState state) {
        AudioDeviceInfo route = state.track.getRoutedDevice();
        return route == null ? "not reported" : deviceName(route);
    }

    private void stopProbe() {
        polling = false;
        handler.removeCallbacks(routePoll);
        releaseTrack(earpieceState);
        releaseTrack(speakerState);
        earpieceState = null;
        speakerState = null;
        if (status != null && !"Ready. No audio is playing.".contentEquals(status.getText())) {
            setStatus("Stopped. No tones are playing.");
        }
    }

    private void releaseTrack(TrackState state) {
        if (state == null) return;
        state.running = false;
        if (state.writer != null) {
            try {
                state.writer.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (state.writer != null && state.writer.isAlive()) {
            try { state.track.pause(); } catch (IllegalStateException ignored) { }
        }
        try { state.track.pause(); } catch (IllegalStateException ignored) { }
        try { state.track.flush(); } catch (IllegalStateException ignored) { }
        try { state.track.stop(); } catch (IllegalStateException ignored) { }
        state.track.release();
    }

    private String deviceName(AudioDeviceInfo device) {
        return device.getProductName() + " (type " + device.getType() + ", id " + device.getId() + ")";
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected void onDestroy() {
        stopProbe();
        super.onDestroy();
    }
}
