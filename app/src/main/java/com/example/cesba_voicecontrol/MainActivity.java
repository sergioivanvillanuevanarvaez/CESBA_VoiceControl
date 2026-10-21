package com.example.cesba_voicecontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int PERMISO_MICROFONO = 101;
    private static final int MAX_HISTORIAL = 6;

    private final Locale idioma = Locale.forLanguageTag("es-MX");
    private final Random azar = new Random();

    // Android
    private SpeechRecognizer reconocedor;
    private TextToSpeech voz;
    private CountDownTimer temporizador;

    // Estados
    private boolean escuchando = false;
    private boolean vozLista = false;
    private boolean destruida = false;
    private boolean linternaEncendida = false;
    private String ultimaRespuesta = "";

    // Vistas
    private TextView txtEstado, txtReconocido, txtRespuesta;
    private TextView txtTemporizador, txtHistorial;
    private Button btnEscuchar, btnCancelar;
    private View pulse, scrollRoot;
    private LinearLayout mainPanel, panelCalculadora, chipsContainer;
    private EditText numA, numB;

    private final ArrayList<String> historial = new ArrayList<>();

    private static final String[] CHISTES = {
            "¿Por qué los programadores confunden Halloween con Navidad? Porque OCT 31 es igual a DEC 25.",
            "Un servidor entra a un bar y pide un ping. El cantinero responde: pong.",
            "Mi código funciona y no sé por qué. Mi código no funciona y tampoco sé por qué.",
            "Hay 10 tipos de personas: las que entienden binario y las que no."
    };

    private static final String[] COMANDOS_RAPIDOS = {
            "qué hora es", "qué fecha es hoy", "nivel de batería",
            "enciende la linterna", "apaga la linterna", "abrir calculadora",
            "cuánto es 12 por 8", "temporizador 10 segundos",
            "cuéntame un chiste", "ayuda"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtEstado = findViewById(R.id.txtEstado);
        txtReconocido = findViewById(R.id.txtReconocido);
        txtRespuesta = findViewById(R.id.txtRespuesta);
        txtTemporizador = findViewById(R.id.txtTemporizador);
        txtHistorial = findViewById(R.id.txtHistorial);

        btnEscuchar = findViewById(R.id.btnEscuchar);
        btnCancelar = findViewById(R.id.btnCancelar);
        pulse = findViewById(R.id.pulse);
        scrollRoot = findViewById(R.id.scrollRoot);

        mainPanel = findViewById(R.id.mainPanel);
        panelCalculadora = findViewById(R.id.panelCalculadora);
        chipsContainer = findViewById(R.id.chipsContainer);

        numA = findViewById(R.id.numA);
        numB = findViewById(R.id.numB);

        btnEscuchar.setOnClickListener(v -> solicitarOEscuchar());
        btnCancelar.setOnClickListener(v -> cancelarEscucha());

        findViewById(R.id.btnSumar).setOnClickListener(v -> operarCampos('+'));
        findViewById(R.id.btnRestar).setOnClickListener(v -> operarCampos('-'));
        findViewById(R.id.btnMult).setOnClickListener(v -> operarCampos('*'));
        findViewById(R.id.btnDiv).setOnClickListener(v -> operarCampos('/'));

        findViewById(R.id.btnCerrarCalc).setOnClickListener(v -> {
            panelCalculadora.setVisibility(View.GONE);
            responder("Calculadora cerrada");
        });

        btnCancelar.setEnabled(false);

        crearChips();
        prepararVoz();
        prepararReconocimiento();
    }

    // --------------------------------
    // CHIPS DE COMANDOS RÁPIDOS
    // (sirven también si no hay micrófono o Internet)
    // --------------------------------
    private void crearChips() {
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        int margen = (int) (8 * getResources().getDisplayMetrics().density);

        for (String cmd : COMANDOS_RAPIDOS) {
            TextView chip = new TextView(this);
            chip.setText(cmd);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(14);
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setPadding(pad * 2, pad, pad * 2, pad);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(margen);
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> {
                txtReconocido.setText(cmd);
                procesarComando(cmd);
            });
            chipsContainer.addView(chip);
        }
    }

    // --------------------------------
    // 1. TEXT TO SPEECH
    // --------------------------------
    private void prepararVoz() {
        voz = new TextToSpeech(this, estado -> {
            if (destruida) return;
            if (estado == TextToSpeech.SUCCESS) {
                int r = voz.setLanguage(idioma);
                vozLista = r != TextToSpeech.LANG_MISSING_DATA
                        && r != TextToSpeech.LANG_NOT_SUPPORTED;
            } else {
                vozLista = false;
            }
        });
    }

    // --------------------------------
    // 2. RECONOCIMIENTO
    // --------------------------------
    private void prepararReconocimiento() {

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            txtEstado.setText("Reconocimiento no disponible (usa los comandos rápidos)");
            btnEscuchar.setEnabled(false);
            return;
        }

        reconocedor = SpeechRecognizer.createSpeechRecognizer(this);

        reconocedor.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle params) {
                txtEstado.setText("Escuchando...");
            }

            @Override
            public void onBeginningOfSpeech() {
                txtEstado.setText("Voz detectada");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                // El círculo dorado "respira" con el volumen de tu voz
                float nivel = Math.max(0f, Math.min(rmsdB, 10f)) / 10f;
                float escala = 1f + nivel * 0.6f;
                pulse.animate().scaleX(escala).scaleY(escala).setDuration(90).start();
            }

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                txtEstado.setText("Procesando...");
            }

            @Override
            public void onError(int error) {
                finalizarEscucha();

                String mensaje;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                        mensaje = "No reconocí las palabras";
                        break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        mensaje = "No detecté tu voz";
                        break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        mensaje = "Error de conexión";
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        mensaje = "Sin permiso del micrófono";
                        break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        mensaje = "Reconocedor ocupado";
                        break;
                    default:
                        mensaje = "Error de reconocimiento: " + error;
                }
                txtEstado.setText("Error");
                txtRespuesta.setText(mensaje);
            }

            @Override
            public void onResults(Bundle results) {
                finalizarEscucha();

                ArrayList<String> frases = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);

                if (frases == null || frases.isEmpty()) {
                    txtRespuesta.setText("No se obtuvo una transcripción");
                    return;
                }

                // Probamos las alternativas hasta que una sea un comando válido
                String elegida = frases.get(0);
                for (String f : frases) {
                    if (esComandoConocido(normalizar(f))) {
                        elegida = f;
                        break;
                    }
                }

                txtReconocido.setText(elegida);
                procesarComando(elegida);
            }

            @Override
            public void onPartialResults(Bundle partial) {
                ArrayList<String> p = partial.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (p != null && !p.isEmpty()) {
                    txtReconocido.setText(p.get(0) + "…");
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });

        txtEstado.setText("Listo para escuchar");
    }

    // --------------------------------
    // 3. PERMISOS
    // --------------------------------
    private void solicitarOEscuchar() {
        if (destruida || escuchando || reconocedor == null) return;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISO_MICROFONO);
            return;
        }
        iniciarEscucha();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISO_MICROFONO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                iniciarEscucha();
            } else {
                responder("Debes autorizar el micrófono");
            }
        }
    }

    // --------------------------------
    // 4. INICIAR / CANCELAR ESCUCHA
    // --------------------------------
    private void iniciarEscucha() {
        if (destruida || escuchando || reconocedor == null) return;

        if (voz != null) voz.stop();

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        escuchando = true;
        btnEscuchar.setEnabled(false);
        btnCancelar.setEnabled(true);
        txtEstado.setText("Preparando micrófono...");

        try {
            reconocedor.startListening(intent);
        } catch (Exception e) {
            finalizarEscucha();
            txtEstado.setText("No se pudo iniciar el reconocimiento");
            txtRespuesta.setText(e.getMessage());
        }
    }

    private void cancelarEscucha() {
        if (!escuchando || reconocedor == null) return;
        reconocedor.cancel();
        finalizarEscucha();
        txtEstado.setText("Escucha cancelada");
    }

    private void finalizarEscucha() {
        escuchando = false;
        btnEscuchar.setEnabled(reconocedor != null && !destruida);
        btnCancelar.setEnabled(false);
        pulse.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
    }

    // --------------------------------
    // 5. NORMALIZAR TEXTO
    // --------------------------------
    private String normalizar(String texto) {

        String r = texto.toLowerCase(idioma);

        // Símbolos matemáticos a palabras (antes de quitar signos)
        r = r.replace("+", " mas ")
                .replace("×", " por ")
                .replace("*", " por ")
                .replace("÷", " entre ")
                .replace("/", " entre ")
                .replaceAll("(?<=\\d)\\s*x\\s*(?=\\d)", " por ")
                .replaceAll("(?<=\\d)\\s*-\\s*(?=\\d)", " menos ");

        r = Normalizer.normalize(r, Normalizer.Form.NFD);
        r = r.replaceAll("\\p{M}+", "");
        r = r.replaceAll("[^a-z0-9 ]", " ");
        return r.replaceAll("\\s+", " ").trim();
    }

    private boolean tiene(String c, String... claves) {
        for (String k : claves) if (c.contains(k)) return true;
        return false;
    }

    // Para escoger la mejor alternativa del reconocedor
    private boolean esComandoConocido(String c) {
        return tiene(c, "hola", "hora", "fecha", "azul", "rojo", "verde",
                "morado", "dorado", "calculadora", "mensaje", "ayuda",
                "linterna", "bateria", "chiste", "temporizador", "camara",
                "wifi", "repite", "cuanto es", "limpiar", "borrar");
    }

    // --------------------------------
    // 6. INTERPRETAR COMANDOS
    // --------------------------------
    private void procesarComando(String frase) {

        String c = normalizar(frase);
        agregarHistorial(frase);

        // Evitar ejecutar órdenes negadas
        if (c.matches(".*\\b(no|nunca|evita|sin)\\b.*")) {
            responder("Detecté una negación. No ejecutaré la acción.");
            return;
        }

        // ---- Matemáticas por voz: "cuánto es 12 por 8", "5 más 3" ----
        Matcher m = Pattern.compile(
                        "(-?\\d+) (mas|menos|por|entre|dividido|multiplicado|multiplicado por|dividido entre) ?(?:por|entre)? ?(-?\\d+)")
                .matcher(c);
        if (m.find()) {
            double a = Double.parseDouble(m.group(1));
            double b = Double.parseDouble(m.group(3));
            String op = m.group(2);
            char o = op.startsWith("mas") ? '+'
                    : op.startsWith("menos") ? '-'
                      : (op.startsWith("por") || op.startsWith("multi")) ? '*' : '/';
            responder("El resultado es " + calcular(a, b, o));
            return;
        }

        // ---- Temporizador: "temporizador 10 segundos / 2 minutos" ----
        Matcher t = Pattern.compile(
                        "temporizador (?:de )?(\\d+) (segundo|segundos|minuto|minutos)")
                .matcher(c);
        if (t.find()) {
            int n = Integer.parseInt(t.group(1));
            boolean min = t.group(2).startsWith("minuto");
            iniciarTemporizador(min ? n * 60 : n);
            return;
        }

        if (c.equals("hola cesba") || c.equals("hola")) {
            responder("Hola. Bienvenido a CESBA Voice Control.");

        } else if (tiene(c, "que hora es") || c.equals("hora")) {
            responder("Son las " + new SimpleDateFormat("HH:mm", idioma).format(new Date()));

        } else if (tiene(c, "que fecha es", "fecha de hoy")) {
            responder("Hoy es " + new SimpleDateFormat(
                    "EEEE d 'de' MMMM 'de' yyyy", idioma).format(new Date()));

        } else if (tiene(c, "bateria")) {
            responder("Tu batería está al " + nivelBateria() + " por ciento");

        } else if (tiene(c, "enciende la linterna", "prende la linterna", "encender linterna")) {
            alternarLinterna(true);

        } else if (tiene(c, "apaga la linterna", "apagar linterna")) {
            alternarLinterna(false);

        } else if (tiene(c, "abrir camara", "abre la camara")) {
            abrirActividad(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), "Abriendo la cámara");

        } else if (tiene(c, "wifi")) {
            abrirActividad(new Intent(Settings.ACTION_WIFI_SETTINGS), "Abriendo ajustes de Wi-Fi");

        } else if (tiene(c, "azul")) {
            cambiarFondo(Color.rgb(24, 42, 87), "azul");

        } else if (tiene(c, "rojo")) {
            cambiarFondo(Color.rgb(126, 34, 52), "rojo");

        } else if (tiene(c, "verde")) {
            cambiarFondo(Color.rgb(20, 83, 64), "verde");

        } else if (tiene(c, "morado")) {
            cambiarFondo(Color.rgb(76, 40, 120), "morado");

        } else if (tiene(c, "negro", "oscuro")) {
            cambiarFondo(Color.rgb(15, 15, 20), "oscuro");

        } else if (tiene(c, "abrir calculadora", "abre la calculadora")) {
            panelCalculadora.setVisibility(View.VISIBLE);
            responder("Calculadora abierta");

        } else if (tiene(c, "cerrar calculadora")) {
            panelCalculadora.setVisibility(View.GONE);
            responder("Calculadora cerrada");

        } else if (tiene(c, "chiste")) {
            responder(CHISTES[azar.nextInt(CHISTES.length)]);

        } else if (tiene(c, "repite", "repetir")) {
            responder(ultimaRespuesta.isEmpty() ? "Aún no he dicho nada" : ultimaRespuesta);

        } else if (tiene(c, "limpiar historial", "borrar historial")) {
            historial.clear();
            txtHistorial.setText("Aún no hay comandos.");
            responder("Historial borrado");

        } else if (c.equals("leer mensaje")) {
            responder("La programación móvil permite crear aplicaciones Android.");

        } else if (c.equals("ayuda")) {
            responder("Puedes decir: hola CESBA, qué hora es, qué fecha es hoy, "
                    + "nivel de batería, enciende o apaga la linterna, abrir cámara, "
                    + "cambiar a azul, rojo, verde, morado u oscuro, abrir calculadora, "
                    + "cuánto es 12 por 8, temporizador 10 segundos, cuéntame un chiste, "
                    + "repite, limpiar historial o ayuda.");
        } else {
            responder("Comando desconocido. Di ayuda para conocer las instrucciones.");
        }
    }

    // --------------------------------
    // 7. FUNCIONES EXTRA
    // --------------------------------
    private void cambiarFondo(int color, String nombre) {
        scrollRoot.setBackgroundColor(color);
        responder("Fondo " + nombre + " aplicado");
    }

    private int nivelBateria() {
        Intent b = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return -1;
        int nivel = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int escala = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return nivel * 100 / escala;
    }

    private void alternarLinterna(boolean encender) {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            responder("Este dispositivo no tiene linterna");
            return;
        }
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String id = cm.getCameraIdList()[0];
            cm.setTorchMode(id, encender);
            linternaEncendida = encender;
            responder(encender ? "Linterna encendida" : "Linterna apagada");
        } catch (CameraAccessException | RuntimeException e) {
            responder("No pude controlar la linterna");
        }
    }

    private void abrirActividad(Intent intent, String mensaje) {
        try {
            startActivity(intent);
            responder(mensaje);
        } catch (Exception e) {
            responder("No pude abrir esa opción en este dispositivo");
        }
    }

    private void iniciarTemporizador(int segundos) {
        if (temporizador != null) temporizador.cancel();

        txtTemporizador.setVisibility(View.VISIBLE);
        responder("Temporizador de " + segundos + " segundos iniciado");

        temporizador = new CountDownTimer(segundos * 1000L, 1000) {
            @Override
            public void onTick(long ms) {
                long s = ms / 1000;
                txtTemporizador.setText(String.format(
                        Locale.US, "⏱ %02d:%02d", s / 60, s % 60));
            }

            @Override
            public void onFinish() {
                txtTemporizador.setVisibility(View.GONE);
                responder("¡Tiempo terminado!");
            }
        }.start();
    }

    private void agregarHistorial(String frase) {
        String hora = new SimpleDateFormat("HH:mm", idioma).format(new Date());
        historial.add(0, hora + "  ›  " + frase);
        while (historial.size() > MAX_HISTORIAL) {
            historial.remove(historial.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String h : historial) sb.append(h).append("\n");
        txtHistorial.setText(sb.toString().trim());
    }

    // --------------------------------
    // 8. CALCULADORA
    // --------------------------------
    private void operarCampos(char op) {
        try {
            double a = Double.parseDouble(numA.getText().toString().trim());
            double b = Double.parseDouble(numB.getText().toString().trim());
            responder("El resultado es " + calcular(a, b, op));
        } catch (NumberFormatException e) {
            responder("Introduce dos números válidos");
        }
    }

    private String calcular(double a, double b, char op) {
        double r;
        switch (op) {
            case '+': r = a + b; break;
            case '-': r = a - b; break;
            case '*': r = a * b; break;
            default:
                if (b == 0) return "indefinido, no se puede dividir entre cero";
                r = a / b;
        }
        if (r == Math.floor(r) && !Double.isInfinite(r)) {
            return String.valueOf((long) r);
        }
        return String.format(idioma, "%.4f", r).replaceAll("0+$", "");
    }

    // --------------------------------
    // 9. RESPUESTAS HABLADAS
    // --------------------------------
    private void responder(String mensaje) {
        ultimaRespuesta = mensaje;
        txtRespuesta.setText(mensaje);
        txtEstado.setText("Listo para escuchar");

        if (vozLista && voz != null) {
            voz.speak(mensaje, TextToSpeech.QUEUE_FLUSH, null,
                    "cesba-" + System.currentTimeMillis());
        }
    }

    // --------------------------------
    // 10. CICLO DE VIDA
    // --------------------------------
    @Override
    protected void onStop() {
        super.onStop();
        if (escuchando && reconocedor != null) {
            reconocedor.cancel();
            finalizarEscucha();
        }
    }

    @Override
    protected void onDestroy() {
        destruida = true;

        if (temporizador != null) temporizador.cancel();
        if (linternaEncendida) {
            try {
                CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
                cm.setTorchMode(cm.getCameraIdList()[0], false);
            } catch (CameraAccessException | RuntimeException ignored) { }
        }

        if (reconocedor != null) {
            reconocedor.cancel();
            reconocedor.destroy();
            reconocedor = null;
        }
        if (voz != null) {
            voz.stop();
            voz.shutdown();
            voz = null;
        }
        super.onDestroy();
    }
}