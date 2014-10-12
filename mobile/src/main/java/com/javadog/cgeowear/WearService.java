package com.javadog.cgeowear;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.net.ConnectException;
import java.util.HashSet;
import java.util.NoSuchElementException;

public class WearService extends Service
		implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
		SensorEventListener {
	public static final String DEBUG_TAG = "com.javadog.cgeowear";

	private static final String INTENT_INIT = "cgeo.geocaching.wear.NAVIGATE_TO";
	private static final String INTENT_STOP = "com.javadog.cgeowear.STOP_APP";

	//Location update speed (preferred and max, respectively) in milliseconds
	private static final long LOCATION_UPDATE_INTERVAL = 2000;
	private static final long LOCATION_UPDATE_MAX_INTERVAL = 1000;

	private LocationRequest locationRequest;

	private static final String EXTRA_CACHE_NAME = "cgeo.geocaching.wear.extra.CACHE_NAME";
	private static final String EXTRA_GEOCODE = "cgeo.geocaching.wear.extra.GEOCODE";
	private static final String EXTRA_LATITUDE = "cgeo.geocaching.wear.extra.LATITUDE";
	private static final String EXTRA_LONGITUDE = "cgeo.geocaching.wear.extra.LONGITUDE";

	private GoogleApiClient apiClient;
	private WearInterface wearInterface;

	private SensorManager sensorManager;
	private Sensor accelerometer;
	private Sensor magnetometer;

	private String cacheName;
	private String geocode;
	private Location geocacheLocation;
	private Location currentLocation;
	private float distance;
	private float direction;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if(intent != null) {
			final String action = intent.getAction();
			if(INTENT_INIT.equals(action)) {
				cacheName = intent.getStringExtra(EXTRA_CACHE_NAME);
				geocode = intent.getStringExtra(EXTRA_GEOCODE);

				final double latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0d);
				final double longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0d);
				geocacheLocation = new Location("c:geo");
				geocacheLocation.setLatitude(latitude);
				geocacheLocation.setLongitude(longitude);

				Toast.makeText(
						getApplicationContext(), getText(R.string.toast_service_started), Toast.LENGTH_SHORT).show();
			}
		}

		return START_STICKY;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		handleInit();
	}

	/**
	 * Starts service & watch app.
	 */
	private void handleInit() {
		//TODO: Ensure an Android Wear device is paired

		//Register listener for INTENT_STOP events
		IntentFilter filter = new IntentFilter(INTENT_STOP);
		registerReceiver(intentReceiver, filter);

		//Show a persistent notification
		Intent stopServiceIntent = new Intent(INTENT_STOP);
		PendingIntent nIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, stopServiceIntent, 0);
		Notification notification = new NotificationCompat.Builder(getApplicationContext())
				.setOngoing(true)
				.setContentIntent(nIntent)
				.setSmallIcon(R.drawable.ic_launcher)
				.setContentTitle(getText(R.string.app_name))
				.setContentText(getText(R.string.notification_text))
				.build();

		//Specify how quickly we want to receive location updates
		locationRequest = LocationRequest.create()
				.setInterval(LOCATION_UPDATE_INTERVAL)
				.setFastestInterval(LOCATION_UPDATE_MAX_INTERVAL)
				.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

		//Start reading compass sensors
		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
		sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);

		//Connect to Google APIs
		apiClient = new GoogleApiClient.Builder(getApplicationContext(), this, this)
				.addApi(Wearable.API)
				.addApi(LocationServices.API)
				.build();
		apiClient.connect();

		//Start service in foreground
		startForeground(R.string.app_name, notification);
	}

	/**
	 * Handles INTENT_STOP event.
	 */
	BroadcastReceiver intentReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if(INTENT_STOP.equals(action)) {
				stopApp();
			}
		}
	};

	@Override
	public void onDestroy() {
		stopWearApp();
		stopForeground(true);
		apiClient.disconnect();

		//Stop listeners
		unregisterReceiver(intentReceiver);
		sensorManager.unregisterListener(this, accelerometer);
		sensorManager.unregisterListener(this, magnetometer);

		super.onDestroy();
	}

	/**
	 * Stops both this phone service and the Wear app.
	 */
	private void stopApp() {
		stopWearApp();
		stopSelf();
	}

	/**
	 * Stops the Android Wear counterpart to this app.
	 */
	private void stopWearApp() {
		//TODO: Implement
	}

	@Override
	public void onConnected(Bundle bundle) {
		Log.d(DEBUG_TAG, "Connected to Play Services");

		//Subscribe to location updates
		LocationServices.FusedLocationApi.requestLocationUpdates(apiClient, locationRequest, locationListener);

		//Get ID of connected Wear device and send it the initial cache info
		new Thread(new Runnable() {
			@Override
			public void run() {
				HashSet<String> connectedWearDevices = new HashSet<String>();
				NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi.getConnectedNodes(apiClient).await();
				for(Node node : nodes.getNodes()) {
					connectedWearDevices.add(node.getId());
				}

				try {
					wearInterface = new WearInterface(apiClient, connectedWearDevices.iterator().next());
					wearInterface.initTracking(cacheName, geocode, 12.34f, 0.12f);
				} catch(ConnectException e) {
					Log.e(DEBUG_TAG, "Couldn't send initial tracking data.");
				} catch(NoSuchElementException e) {
					//TODO: Handle this with a warning in the UI
					Log.e(DEBUG_TAG, "No Wear devices connected. Killing service...");
					stopApp();
				}
			}
		}).start();
	}

	@Override
	public void onConnectionSuspended(int i) {
		Log.d(DEBUG_TAG, "Play Services connection suspended.");
	}

	@Override
	public void onConnectionFailed(ConnectionResult connectionResult) {
		Log.e(DEBUG_TAG, "Failed to connect to Google Play Services.");
	}

	private LocationListener locationListener = new LocationListener() {
		@Override
		public void onLocationChanged(Location location) {
			//Update stored currentLocation
			currentLocation = location;

			//Calculate new distance (meters) to geocache
			distance = location.distanceTo(geocacheLocation);

			//Calculate the angle to the geocache


			//Send these values off to Android Wear
			wearInterface.sendLocationUpdate(distance, direction);
		}
	};

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	float[] gravity;
	float[] geomagnetic;
	/**
	 * Handles compass azimuth rotation. Direction values are simply stored in the instance
	 * and sent along with location updates.
	 */
	@Override
	public void onSensorChanged(SensorEvent event) {
		if(event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
			gravity = event.values.clone();
		} else if(event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
			geomagnetic = event.values.clone();
		}

		if(gravity != null && geomagnetic != null) {
			float[] R = new float[9];
			float[] I = new float[9];

			boolean success = SensorManager.getRotationMatrix(R, I, gravity, geomagnetic);
			if(success) {
				float[] orientation = new float[3];
				SensorManager.getOrientation(R, orientation);
				float azimuth = (float) Math.toDegrees(orientation[0]);

				if(currentLocation != null) {
					GeomagneticField geomagneticField = new GeomagneticField(
							(float) currentLocation.getLatitude(),
							(float) currentLocation.getLongitude(),
							(float) currentLocation.getAltitude(),
							System.currentTimeMillis()
					);
					azimuth += geomagneticField.getDeclination();
					float bearing = currentLocation.bearingTo(geocacheLocation);

					direction = -(azimuth - bearing);
				}
			}
		}
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {

	}
}
