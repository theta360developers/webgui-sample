package guide.theta360.webguisample;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import com.samskivert.mustache.Mustache;
import guide.theta360.webguisample.R;

import guide.theta360.webguisample.task.TakePictureTask;

import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginlibrary.values.LedColor;
import com.theta360.pluginlibrary.values.LedTarget;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends PluginActivity {

    private Context context;
    private WebServer webServer;
    private static final String PREFERENCE_KEY_COLOR = "color";

    private TakePictureTask.Callback mTakePictureTaskCallback = new TakePictureTask.Callback() {
        @Override
        public void onTakePicture(String fileUrl) {

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.context = getApplicationContext();
        notificationLedBlink(LedTarget.LED3, this.loadLedColor(), 1000);
        this.webServer = new WebServer(this.context);
        try {
            this.webServer.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Set enable to close by pluginlibrary, If you set false, please call close() after finishing your end processing.
        setAutoClose(true);
        // Set a callback when a button operation event is acquired.
        setKeyCallback(new KeyCallback() {
            @Override
            public void onKeyDown(int keyCode, KeyEvent event) {
                if (keyCode == KeyReceiver.KEYCODE_CAMERA) {
                    /*
                     * To take a static picture, use the takePicture method.
                     * You can receive a fileUrl of the static picture in the callback.
                     */
                    new TakePictureTask(mTakePictureTaskCallback).execute();
                }
            }

            @Override
            public void onKeyUp(int keyCode, KeyEvent event) {
                /**
                 * You can control the LED of the camera.
                 * It is possible to change the way of lighting, the cycle of blinking, the color of light emission.
                 * Light emitting color can be changed only LED3.
                 */
//         notificationLedBlink(LedTarget.LED3, LedColor.BLUE, 1000);
            }

            @Override
            public void onKeyLongPress(int keyCode, KeyEvent event) {

            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        // Do end processing
        close();

        super.onPause();
    }

    protected void onDestroy() {
        super.onDestroy();
        if (this.webServer != null) {
            this.webServer.stop();
        }
    }

    private void saveLedColor(LedColor ledColor) {
        SharedPreferences data = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = data.edit();
        editor.putString(PREFERENCE_KEY_COLOR, ledColor.toString());
        editor.apply();
    }

    private void saveBracket(String bracketNumber) {
        SharedPreferences data = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = data.edit();
        editor.putString("bracket", bracketNumber);
        editor.apply();
    }

    private LedColor loadLedColor() {
        SharedPreferences data = PreferenceManager.getDefaultSharedPreferences(context);
        String savedColor = data.getString(PREFERENCE_KEY_COLOR, LedColor.BLUE.toString());
        return LedColor.getValue(savedColor);
    }

    private class WebServer extends NanoHTTPD {

        private static final int PORT = 8888;
        private Context context;
        private static final String INDEX_TEMPLATE_FILE_NAME = "index_template.html";
        private static final String INDEX_OUTPUT_FILE_NAME = "index_out.html";
        private static final String HTML_SELECTOR_ID_COLOR = "color";
        private static final String HTML_SELECTOR_ID_BRACKET = "bracket";

        public WebServer(Context context) {
            super(PORT);
            this.context = context;
        }

        @Override
        public Response serve(IHTTPSession session) {
            Method method = session.getMethod();
            String uri = session.getUri();
            switch (method) {
                case GET:
                    return this.serveFile(uri);
                case POST:
                    Map<String, List<String>> parameters = this.parseBodyParameters(session);
                    this.updatePreferences(uri, parameters);
                    return this.serveFile(uri);
                default:
                    return newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain",
                            "Method [" + method + "] is not allowed.");
            }
        }

        private Response serveFile(String uri) {
            switch (uri) {
                case "/":
                    return this.newHtmlResponse(this.generateIndexHtmlContext(), INDEX_TEMPLATE_FILE_NAME, INDEX_OUTPUT_FILE_NAME);
                default:
                    return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "URI [" + uri + "] is not found.");
            }
        }

        private Response newHtmlResponse(Map<String, Object> data, String templateFileName, String outFileName) {
            AssetManager assetManager = context.getAssets();
            try(InputStreamReader template = new InputStreamReader(assetManager.open(templateFileName));
                OutputStreamWriter output = new OutputStreamWriter(openFileOutput(outFileName, Context.MODE_PRIVATE))) {
                Mustache.compiler().compile(template).execute(data, output);
                return newChunkedResponse(Status.OK, "text/html", openFileInput(outFileName));
            } catch (IOException e) {
                e.printStackTrace();
                return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", e.getMessage());
            }
        }

        private Map<String, List<String>> parseBodyParameters(IHTTPSession session) {
            Map<String, String> tmpRequestFile = new HashMap<>();
            try {
                session.parseBody(tmpRequestFile);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ResponseException e) {
                e.printStackTrace();
            }
            return session.getParameters();
        }

        private void updatePreferences(String uri, Map<String, List<String>> parameters) {
            if(parameters == null) return;

            switch (uri) {
                case "/":
                    this.updateLedColor(parameters);
                    this.updateBracket(parameters);
                    return;
                default:
                    return;
            }
        }

        private void updateLedColor(Map<String, List<String>> parameters) {
            if (parameters.get(HTML_SELECTOR_ID_COLOR) == null || parameters.get(HTML_SELECTOR_ID_COLOR).isEmpty()) {
                return;
            }

            String color = parameters.get(HTML_SELECTOR_ID_COLOR).get(0);
            LedColor ledColor = LedColor.getValue(color);
            notificationLedBlink(LedTarget.LED3, ledColor, 1000);
            saveLedColor(ledColor);
        }

        private void updateBracket(Map<String, List<String>> parameters) {
            if (parameters.get(HTML_SELECTOR_ID_BRACKET) == null || parameters.get(HTML_SELECTOR_ID_BRACKET).isEmpty()) {
                return;
            }

            /*
            Test section to receive bracket parameter and display an LED for feedback
            7 brackets: camera LED will light
            9 brackets: Video LED will light
            13 brackets: LIVE LED will light
             */

            String bracket = parameters.get("bracket").get(0);
            saveBracket(bracket);
            Log.d("VFX", bracket);

            if (bracket.equals("7")) {
                notificationLedShow(LedTarget.LED4);
                Log.d("VFX", "saving bracket 7");
                notificationLedHide(LedTarget.LED5);
                notificationLedHide(LedTarget.LED6);
            }

            if (bracket.equals("9")) {
                notificationLedHide(LedTarget.LED4);
                notificationLedShow(LedTarget.LED5);
                notificationLedHide(LedTarget.LED6);
                Log.d("VFX", "saving parameter 9 ");
            }

            if (bracket.equals("13")) {
                notificationLedHide(LedTarget.LED4);
                notificationLedHide(LedTarget.LED5);
                notificationLedShow(LedTarget.LED6);
                Log.d("VFX", "saving parameter 9 ");
            }
     }

        private Map<String, Object> generateIndexHtmlContext() {
            Map<String, Object> context = new HashMap<>();
            context.putAll(this.generateLedColorContext());
            return context;
        }

        private Map<String, Object> generateLedColorContext() {
            Map<String, Object> ledContext = new HashMap<>();
            LedColor ledColor = loadLedColor();
            switch (ledColor) {
                case BLUE:
                    ledContext.put("isBlue", true);
                    break;
                case RED:
                    ledContext.put("isRed", true);
                    break;
                case WHITE:
                    ledContext.put("isWhite", true);
                    break;
                default:
                    ledContext.put("isBlue", true);
            }
            return ledContext;
        }

    }

}
