package org.medicmobile.webapp.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.widget.Toast;

import com.simprints.libsimprints.SimHelper;

import java.util.List;

import org.xwalk.core.XWalkPreferences;
import org.xwalk.core.XWalkResourceClient;
import org.xwalk.core.XWalkSettings;
import org.xwalk.core.XWalkUIClient;
import org.xwalk.core.XWalkView;

import static android.provider.MediaStore.ACTION_IMAGE_CAPTURE;
import static java.lang.Boolean.parseBoolean;
import static org.medicmobile.webapp.mobile.BuildConfig.DEBUG;
import static org.medicmobile.webapp.mobile.BuildConfig.DISABLE_APP_URL_VALIDATION;
import static org.medicmobile.webapp.mobile.MedicLog.trace;
import static org.medicmobile.webapp.mobile.MedicLog.warn;
import static org.medicmobile.webapp.mobile.SimpleJsonClient2.redactUrl;
import static org.medicmobile.webapp.mobile.Utils.intentHandlerAvailableFor;

public class EmbeddedBrowserActivity extends LockableActivity {
	/** Any activity result with all 3 low bits set is _not_ a simprints result. */
	private static final int NON_SIMPRINTS_FLAGS = 0x7;
	private static final int CAPTURE_IMAGE = (1 << 3) | NON_SIMPRINTS_FLAGS;
	private static final int CHOOSE_FILE = (2 << 3) | NON_SIMPRINTS_FLAGS;

	private static final ValueCallback<String> IGNORE_RESULT = new ValueCallback<String>() {
		public void onReceiveValue(String result) { /* ignore */ }
	};

	private final ValueCallback<String> backButtonHandler = new ValueCallback<String>() {
		public void onReceiveValue(String result) {
			if(!"true".equals(result)) {
				EmbeddedBrowserActivity.this.moveTaskToBack(false);
			}
		}
	};

	private XWalkView container;
	private SettingsStore settings;
	private SimprintsSupport simprints;

	private ValueCallback<Uri> uploadCallback;

	@Override public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		trace(this, "Starting XWalk webview...");

		this.simprints = new SimprintsSupport(this);

		this.settings = SettingsStore.in(this);

		this.requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.main);

		// Add an alarming red border if using configurable (i.e. dev)
		// app with a medic production server.
		if(settings.allowsConfiguration() &&
				settings.getAppUrl().contains("app.medicmobile.org")) {
			findViewById(R.id.lytWebView).setPadding(10, 10, 10, 10);
		}

		container = (XWalkView) findViewById(R.id.wbvMain);

		if(DEBUG) enableWebviewLoggingAndGeolocation(container);
		enableRemoteChromeDebugging();
		enableJavascript(container);
		enableStorage(container);

		enableUrlHandlers(container);

		browseToRoot();

		if(settings.allowsConfiguration()) {
			toast(redactUrl(settings.getAppUrl()));
		}
	}

	@Override public boolean onCreateOptionsMenu(Menu menu) {
		if(settings.allowsConfiguration()) {
			getMenuInflater().inflate(R.menu.unbranded_web_menu, menu);
		} else {
			getMenuInflater().inflate(R.menu.web_menu, menu);
		}
		return super.onCreateOptionsMenu(menu);
	}

	@Override public boolean onOptionsItemSelected(MenuItem item) {
		switch(item.getItemId()) {
			case R.id.mnuSetUnlockCode:
				changeCode();
				return true;
			case R.id.mnuSettings:
				openSettings();
				return true;
			case R.id.mnuHardRefresh:
				browseToRoot();
				return true;
			case R.id.mnuLogout:
				evaluateJavascript("angular.element(document.body).injector().get('AndroidApi').v1.logout()");
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override public void onBackPressed() {
		if(container == null) {
			super.onBackPressed();
		} else {
			container.evaluateJavascript(
					"angular.element(document.body).injector().get('AndroidApi').v1.back()",
					backButtonHandler);
		}
	}

	@Override protected void onActivityResult(int requestCode, int resultCode, Intent i) {
		trace(this, "onActivityResult() :: requestCode=%s, resultCode=%s", requestCode, resultCode);
		if((requestCode & NON_SIMPRINTS_FLAGS) == NON_SIMPRINTS_FLAGS) {
			switch(requestCode) {
				case CAPTURE_IMAGE:
					trace(this, "Should handle captured image...");
				case CHOOSE_FILE:
					if(uploadCallback != null) {
						uploadCallback.onReceiveValue(i == null || resultCode != RESULT_OK ? null : i.getData());
						uploadCallback = null;
					} else warn(this, "uploadCallback is null for requestCode %s", requestCode);
					return;
			}
		} else try {
			String js = simprints.process(requestCode, i);
			trace(this, "Execing JS: %s", js);
			evaluateJavascript(js);
		} catch(Exception ex) {
			warn(ex, "Unhandled intent %s (%s) with requestCode=%s & resultCode=%s", i, i == null ? null : i.getAction(), requestCode, resultCode);
		}
	}

	public void evaluateJavascript(final String js) {
		container.post(new Runnable() {
			public void run() {
				// `WebView.loadUrl()` seems to be significantly faster than
				// `WebView.evaluateJavascript()` on Tecno Y4.  We may find
				// confusing behaviour on Android 4.4+ when using `loadUrl()`
				// to run JS, in which case we should switch to the second
				// block.
				// On switching to XWalkView, we assume the same applies.
				if(true) { // NOPMD
					container.load("javascript:" + js, null);
				} else {
					container.evaluateJavascript(js, IGNORE_RESULT);
				}
			}
		});
	}

	private void openSettings() {
		startActivity(new Intent(this,
				SettingsDialogActivity.class));
		finish();
	}

	private void browseToRoot() {
		String url = settings.getAppUrl() + (DISABLE_APP_URL_VALIDATION ?
				"" : "/medic/_design/medic/_rewrite/");
		if(DEBUG) trace(this, "Pointing browser to %s", redactUrl(url));
		container.load(url, null);
	}

	private void enableRemoteChromeDebugging() {
		XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, true);
	}

	private void enableWebviewLoggingAndGeolocation(XWalkView container) {
		container.setUIClient(new XWalkUIClient(container) {
			public boolean onConsoleMessage(ConsoleMessage cm) {
				trace(this, "onConsoleMessage() :: %s:%s | %s",
						cm.sourceId(),
						cm.lineNumber(),
						cm.message());
				return true;
			}

			@Override public void openFileChooser(XWalkView view, ValueCallback<Uri> callback, String acceptType, String shouldCapture) {
				trace(this, "openFileChooser() :: %s,%s,%s,%s", view, callback, acceptType, shouldCapture);
				uploadCallback = callback;

				if(parseBoolean(shouldCapture)) {
					Intent i = new Intent(ACTION_IMAGE_CAPTURE);
					if(intentHandlerAvailableFor(EmbeddedBrowserActivity.this, i)) {
						startActivityForResult(i, CAPTURE_IMAGE);
						return;
					}
				}

				Intent i = new Intent(Intent.ACTION_GET_CONTENT);
				i.addCategory(Intent.CATEGORY_OPENABLE);
				i.setType(acceptType);
				EmbeddedBrowserActivity.this.startActivityForResult(Intent.createChooser(i, "File Chooser"), CHOOSE_FILE);
			}

			/*
			 * TODO Crosswalk: re-enable this if required
			public void onGeolocationPermissionsShowPrompt(
					String origin,
					GeolocationPermissions.Callback callback) {
				// allow all location requests
				// TODO this should be restricted to the domain
				// set in Settings - issue #1603
				trace(this, "onGeolocationPermissionsShowPrompt() :: origin=%s, callback=%s",
						origin, callback);
				callback.invoke(origin, true, true);
			}
			*/
		});
	}

	@SuppressLint("SetJavaScriptEnabled")
	private void enableJavascript(XWalkView container) {
		container.getSettings().setJavaScriptEnabled(true);

		MedicAndroidJavascript maj = new MedicAndroidJavascript(this);
		maj.setAlert(new Alert(this));

		maj.setLocationManager((LocationManager) this.getSystemService(Context.LOCATION_SERVICE));

		container.addJavascriptInterface(maj, "medicmobile_android");
	}

	private void enableStorage(XWalkView container) {
		XWalkSettings settings = container.getSettings();

		// N.B. in Crosswalk, database seems to be enabled by default

		settings.setDomStorageEnabled(true);

		// N.B. in Crosswalk, appcache seems to work by default, and
		// there is no option to set the storage path.
	}

	private void enableUrlHandlers(XWalkView container) {
		container.setResourceClient(new XWalkResourceClient(container) {
			@Override public boolean shouldOverrideUrlLoading(XWalkView view, String url) {
				if(url.startsWith("tel:") || url.startsWith("sms:")) {
					Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
					view.getContext().startActivity(i);
					return true;
				}
				return false;
			}
		});
	}

	private void toast(String message) {
		Toast.makeText(container.getContext(), message, Toast.LENGTH_LONG).show();
	}
}
