package com.apptech.android.bushero;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener {
    private static final String LOG_TAG = "MainActivity";
    private static final String SAVED_NEAREST_STOP_ID = "NEAREST_STOP_ID";
    private static final String SAVED_CURRENT_POSITION = "CURRENT_POSITION";
    private static final String SAVED_LAST_LONGITUDE = "LAST_LONGITUDE";
    private static final String SAVED_LAST_LATITUDE = "LAST_LATITUDE";
    private static final String SAVED_FAVOURITE_STOP_ID = "FAVOURITE_STOP_ID";
    private static final int REQUEST_PERMISSION_FINE_LOCATION = 1;
    private static final int LOCATION_UPDATE_INTERVAL = 30000; // ms
    private static final int MIN_DISTANCE = 45; // metres
    private static final int UPDATE_CHECK_INTERVAL = 10000; // ms.

    // We add a second to bus times so they elapse once the time expires, e.g. so a bus due
    // at 13:05 won't expire until we hit 13:06. We then add an extra ten seconds as a hack to
    // account for the fact that the transportapi.com server may not be on exactly the same second
    // as us.
    private static final int DEPARTURE_TIME_ADJUSTMENT = (60 + 10) * 1000; // ms.

    // Widgets
    private DrawerLayout mLayoutDrawer;
    private RelativeLayout mRelativeDrawer;
    private TextView mTextName;
    private TextView mTextDistance;
    private TextView mTextBearing;
    private TextView mTextLocality;
    private ListView mListBuses;
    private ListView mListNearest;
    private ListView mListFavorites;
    private ImageButton mButtonNearer;
    private ImageButton mButtonFurther;
    private ImageButton mButtonLocation;
    private ImageButton mButtonFavourite;
    private ProgressDialog mProgressDialog;

    // Variables
    private BusDatabase mBusDatabase;
    private TransportClient mTransportClient;
    private GoogleApiClient mGoogleApiClient;
    private NearestBusStops mNearestBusStops;
    private LiveBuses mLiveBuses;
    private BusAdapter mBusAdapter;
    private NearestStopsAdapter mNearestStopsAdapter;
    private FavouritesAdapter mFavouritesAdapter;
    private Handler mUpdateHandler;
    private int mCurrentPosition;
    private double mLastLongitude;
    private double mLastLatitude;
    private boolean mIsUpdating;
    private boolean mIsChangingLocation;
    private boolean mIsUpdatingLiveBuses;
    private FavouriteStop mFavouriteStop;
    private double mPendingLongitude;
    private double mPendingLatitude;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get widgets from layout.
        mTextName = (TextView) findViewById(R.id.textName);
        mTextDistance = (TextView) findViewById(R.id.textDistance);
        mTextBearing = (TextView) findViewById(R.id.textBearing);
        mTextLocality = (TextView) findViewById(R.id.textLocality);
        mButtonNearer = (ImageButton) findViewById(R.id.buttonNearer);
        mButtonFurther = (ImageButton) findViewById(R.id.buttonFurther);
        mButtonLocation = (ImageButton) findViewById(R.id.buttonLocation);
        mLayoutDrawer = (DrawerLayout)findViewById(R.id.drawerLayout);
        mRelativeDrawer = (RelativeLayout)findViewById(R.id.relativeDrawer);
        mListBuses = (ListView) findViewById(R.id.listBuses);
        mListNearest = (ListView)findViewById(R.id.listNearest);
        mListFavorites = (ListView)findViewById(R.id.listFavourites);
        mButtonFavourite = (ImageButton)findViewById(R.id.buttonFavourite);

        // Handle listview item onclick events.
        mListBuses.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // Get clicked on bus and start route activity.
                Bus bus = mLiveBuses.getBus(position);
                BusStop stop = getCurrentBusStop();
                Intent intent = RouteActivity.newIntent(
                        MainActivity.this,
                        bus.getId(),
                        stop.getAtcoCode());
                startActivity(intent);
            }
        });

        mListNearest.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                mCurrentPosition = position;
                mFavouriteStop = null;
                updateBusStop();
                mLayoutDrawer.closeDrawers();
            }
        });

        mListFavorites.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                FavouriteStop favourite = mFavouritesAdapter.getItem(position);
                loadFavouriteStop(favourite);
                mLayoutDrawer.closeDrawers();
            }
        });

        mLayoutDrawer.addDrawerListener(new DrawerLayout.DrawerListener() {
            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                // create favourites list if it doesn't exist.
                if (mFavouritesAdapter == null) {
                    List<FavouriteStop> favourites = mBusDatabase.getFavouriteStops();
                    mFavouritesAdapter = new FavouritesAdapter(MainActivity.this, favourites);
                    mListFavorites.setAdapter(mFavouritesAdapter);
                }

                // switch colour of favourite star depending on whether currently viewed bus stop is
                // a favourite.
                BusStop stop = getCurrentBusStop();
                if (mFavouriteStop != null || (stop != null && isFavouriteStop(stop.getAtcoCode()))) {
                    setAddFavouriteButtonBright();
                }
                else {
                    setAddFavouriteButtonDark();
                }

                // Update nearest bus stops list in the navigation drawer.
                if (mNearestStopsAdapter == null) {
                    mNearestStopsAdapter = new NearestStopsAdapter(MainActivity.this, mNearestBusStops.getStops());
                    mListNearest.setAdapter(mNearestStopsAdapter);
                }
                else {
                    // Adapter exists already so just update it.
                    mNearestStopsAdapter.clear();
                    mNearestStopsAdapter.addAll(mNearestBusStops.getStops());
                }
            }

            @Override public void onDrawerOpened(View drawerView) {}
            @Override public void onDrawerClosed(View drawerView) {}
            @Override public void onDrawerStateChanged(int newState) {}
        });

        // Setup database and transport API client.
        mBusDatabase = new BusDatabase(this);
        mTransportClient = new TransportClient();

        // Check if we need to get info from preferences or if we are restoring from instance state.
        long nearestStopsId;
        long favouriteStopId;
        if (savedInstanceState == null) {
            SharedPreferences preferences = getPreferences(0);
            nearestStopsId = preferences.getLong(SAVED_NEAREST_STOP_ID, -1);
            mCurrentPosition = preferences.getInt(SAVED_CURRENT_POSITION, 0);
            favouriteStopId = preferences.getLong(SAVED_FAVOURITE_STOP_ID, -1);

            // Workaround for fact that preferences doesn't support double for some reason.
            mLastLongitude = Double.longBitsToDouble(preferences.getLong(SAVED_LAST_LONGITUDE, 0));
            mLastLatitude = Double.longBitsToDouble(preferences.getLong(SAVED_LAST_LATITUDE, 0));

            Log.d(LOG_TAG, "got nearest stops id (" + nearestStopsId + ") from preferences");
        }
        else {
            mCurrentPosition = savedInstanceState.getInt(SAVED_CURRENT_POSITION);
            nearestStopsId = savedInstanceState.getLong(SAVED_NEAREST_STOP_ID, -1);
            mLastLongitude = savedInstanceState.getDouble(SAVED_LAST_LONGITUDE);
            mLastLatitude = savedInstanceState.getDouble(SAVED_LAST_LATITUDE);
            favouriteStopId = savedInstanceState.getLong(SAVED_FAVOURITE_STOP_ID, -1);

            Log.d(LOG_TAG, "got nearest stops id (" + nearestStopsId + ") from saved state");
        }

        // load favourite stop if we're looking at one.
        if (favouriteStopId > -1) {
            Log.d(LOG_TAG, "loading favourite stop (id: " + favouriteStopId + ")");
            mFavouriteStop = mBusDatabase.getFavouriteStop(favouriteStopId);
        }

        // If we have a nearest stop id then restore it from the database.
        if (nearestStopsId > -1) {
            Log.d(LOG_TAG, "loading nearest stops from database");
            mNearestBusStops = mBusDatabase.getNearestBusStops(nearestStopsId);
        }

        updateBusStop();

        // Initialise google play services API to access location GPS data.
        // Once connected to google player services the method onConnected(Bundle) is called.
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addApi(LocationServices.API)
                .build();

        // Start update handler, this in a timer that elapses every so often and checks to see if
        // a live bus update is needed.
        mUpdateHandler = new Handler();
        startUpdateTask();
    }

    private BusStop getCurrentBusStop() {
        if (mNearestBusStops == null) {
            return null;
        }
        return mNearestBusStops.getStop(mCurrentPosition);
    }

    private boolean isFavouriteStop(String atcoCode) {
        if (mFavouritesAdapter == null) {
            return false;
        }

        for (int i = 0; i < mFavouritesAdapter.getCount(); i++) {
            if (mFavouritesAdapter.getItem(i).getAtcoCode().equals(atcoCode)) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Connect to google play services api on start. Once connected the onConnected method is
        // called.
        Log.d(LOG_TAG, "Connecting to Google API Service");
        mGoogleApiClient.connect();

        // Start checking to see if live bus updates are available.
        startUpdateTask();
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Disconnect from google play services API on stop.
        Log.d(LOG_TAG, "Disconnecting from Google API Service");
        mGoogleApiClient.disconnect();

        // Stop checking for live bus updates.
        stopUpdateTask();

        savePreferences(); // Yup.
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Stop showing dialog - this helps if user rotates app when asynctask is running.
        dismissProgressDialog();

        // Stop checking for updates when paused.
        stopUpdateTask();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Start checking for updates again on resume.
        startUpdateTask();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // When stopping make sure preferences are saved.
        savePreferences();
    }

    private void savePreferences() {
        // Save preferences. These are things we want preserved so they are available the next time
        // the user starts up the app.
        if (mNearestBusStops != null) {
            Log.d(LOG_TAG, "saving nearest stops id (" + mNearestBusStops.getId() + ") to preferences");

            SharedPreferences preference = getPreferences(0);
            SharedPreferences.Editor editor = preference.edit();
            editor.putLong(SAVED_NEAREST_STOP_ID, mNearestBusStops.getId());
            editor.putInt(SAVED_CURRENT_POSITION, mCurrentPosition);
            editor.putLong(SAVED_FAVOURITE_STOP_ID, mFavouriteStop == null ? -1 : mFavouriteStop.getId());

            // Workaround for fact that preferences doesn't support double.
            editor.putLong(SAVED_LAST_LONGITUDE, Double.doubleToLongBits(mLastLongitude));
            editor.putLong(SAVED_LAST_LATITUDE, Double.doubleToLongBits(mLastLatitude));

            editor.apply();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Save state data so activity can be recreated. This is used to save state while the
        // activity is running, for instance if the user rotates the device the activity is
        // destroyed and recreated, this lets us save state to be restored later.
        Log.d(LOG_TAG, "saving instance state");
        savedInstanceState.putLong(SAVED_NEAREST_STOP_ID, mNearestBusStops == null ? -1 : mNearestBusStops.getId());
        savedInstanceState.putInt(SAVED_CURRENT_POSITION, mCurrentPosition);
        savedInstanceState.putDouble(SAVED_LAST_LONGITUDE, mLastLongitude);
        savedInstanceState.putDouble(SAVED_LAST_LATITUDE, mLastLatitude);
        savedInstanceState.putLong(SAVED_FAVOURITE_STOP_ID, mFavouriteStop == null ? -1 : mFavouriteStop.getId());
    }

    private void startUpdateTask() {
        // Start live buses update checker, if it's not already running.
        if (!mIsUpdating) {
            Log.d(LOG_TAG, "starting update timer");
            mIsUpdating = true;
            // start update checker after interval.
            mUpdateHandler.postDelayed(mUpdateChecker, UPDATE_CHECK_INTERVAL);
        }
    }

    private void stopUpdateTask() {
        // Stop live buses update checker, if it's actually running that is...
        if (mIsUpdating) {
            Log.d(LOG_TAG, "stopping update timer");
            mUpdateHandler.removeCallbacks(mUpdateChecker);
            mIsUpdating = false;
        }
    }

    // Check for live bus updates.
    private Runnable mUpdateChecker = new Runnable() {
        @Override
        public void run() {
            try {
                // Check if we can perform an update.
                if (mIsChangingLocation || mIsUpdatingLiveBuses || mNearestBusStops == null || mLiveBuses == null) {
                    return;
                }

                // get the next due bus.
                Bus bus = mLiveBuses.getBus(0);
                if (bus == null) {
                    return;
                }

                // Check departure time was in the past.
                if (isBusDepartureDue(bus.getDepartureTime())) {
                    // Ask user if they want to update live bus info.
                    Snackbar snackbar = Snackbar.make(mLayoutDrawer, R.string.snackbar_live_message, Snackbar.LENGTH_INDEFINITE);
                    snackbar.setAction(R.string.snackbar_live_update, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String atcoCode;
                            long busStopId = 0;
                            long favouriteStopId = 0;

                            BusStop busStop = getCurrentBusStop();
                            if (mFavouriteStop != null) {
                                atcoCode = mFavouriteStop.getAtcoCode();
                                favouriteStopId = mFavouriteStop.getId();
                            }
                            else if (busStop != null) {
                                atcoCode = busStop.getAtcoCode();
                                busStopId = busStop.getId();
                            }
                            else {
                                return;
                            }

                            new DownloadLiveBusesAsyncTask(atcoCode, busStopId, favouriteStopId).execute();
                        }
                    });
                    snackbar.show();
                }
            }
            finally {
                // TODO: handler.sendMessageAtTime()????
                // Schedule next time this method should be run.
                mUpdateHandler.postDelayed(mUpdateChecker, UPDATE_CHECK_INTERVAL);
            }
        }
    };

    private boolean isBusDepartureDue(long departureTime) {
        departureTime += DEPARTURE_TIME_ADJUSTMENT;
        long now = System.currentTimeMillis(); // Current system time.

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy/MM/dd - hh:mm:ss", Locale.ENGLISH);
        Log.d(LOG_TAG, "Checking bus expired - now: " + fmt.format(new Date(now)) + " departure: " + fmt.format(new Date(departureTime)));

        // Check departure time was in the past.
        return now > departureTime;
    }

    @Override
    public void onConnected(Bundle bundle) {
        // Once connected to google play services start receiving location updates.
        Log.d(LOG_TAG, "Google API Client connected.");
        startLocationUpdates();
    }

    private void startLocationUpdates() {
        // Check we have permission to request location updates.
        Log.d(LOG_TAG, "checking permissions");
        int permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        if (permission == PackageManager.PERMISSION_GRANTED) {
            // If this is first time then show progress dialog. Other location updates happen in the
            // background so we don't need to show a progress dialog for them.
            if (mNearestBusStops == null) {
                showProgressDialog(R.string.progress_finding_location);
            }

            // Request location updates. When location changes the method onLocationChanged(Location) is called.
            Log.d(LOG_TAG, "requesting location updates");
            LocationRequest request = new LocationRequest();
            request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            request.setInterval(LOCATION_UPDATE_INTERVAL);
            request.setFastestInterval(LOCATION_UPDATE_INTERVAL);
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, request, this);
        }
        else {
            // Request permission to use location from user. This is answered in the method
            // onRequestPermissionsResult.
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_PERMISSION_FINE_LOCATION);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        double longitude = location.getLongitude();
        double latitude = location.getLatitude();
        Log.d(LOG_TAG, "location changed (" + latitude + "," + longitude + ")");

        // If we have no nearest bus stops object then we better make one.
        if (mNearestBusStops == null) {
            new DownloadNearestStopsAsyncTask().execute(longitude, latitude);
        }
        else {
            // Check to see how far the user has moved since last update.
            float distance = getDistanceSinceLastUpdate(longitude, latitude);
            Log.d(LOG_TAG, "distance:" + distance);
            if (distance > MIN_DISTANCE) {
                // show change location button, store pending location for later.
                mPendingLongitude = longitude;
                mPendingLatitude = latitude;
                mButtonLocation.setVisibility(View.VISIBLE);
            }
        }
    }

    private float getDistanceSinceLastUpdate(double longitude, double latitude) {
        // Get distance since last location update in metres.
        float[] results = new float[1];
        Location.distanceBetween(mLastLatitude, mLastLongitude, latitude, longitude, results);
        return results[0];
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        // Called after user prompted to award app permission.
        switch (requestCode) {
            case REQUEST_PERMISSION_FINE_LOCATION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Yes, we have permission, try and update location again.
                    Log.d(LOG_TAG, "permission granted");
                    startLocationUpdates();
                }
                else {
                    Log.d(LOG_TAG, "location permission refused :(");
                    new AlertDialog.Builder(this).
                            setMessage("This app requires location permission to work").
                            setTitle("Permission Needed").
                            show();
                }
                break;
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(LOG_TAG, "Google API Client suspended.");

        dismissProgressDialog();
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        // Could not connect to Google Play API Services for some reason.
        Log.d(LOG_TAG, "Google API Client connection failed.");
        new AlertDialog.Builder(this)
                .setTitle("Google Play Services Error")
                .setMessage("Coult not connect to Google Play Services, either it is not installed or out of date")
                .show();
    }

    public void onClickNearer(View view) {
        // Move to previous bus stop in list.
        if (mNearestBusStops != null && mCurrentPosition > 0) {
            mCurrentPosition--;

            updateBusStop();
        }
    }

    public void onClickFurther(View view) {
        // Move to next bus stop in list.
        if (mNearestBusStops != null && mCurrentPosition + 1 < mNearestBusStops.getStopCount()) {
            mCurrentPosition++;

            updateBusStop();
        }
    }

    public void onClickChangeLocation(View view) {
        new DownloadNearestStopsAsyncTask().execute(mPendingLongitude, mPendingLatitude);
    }

    public void onClickAddFavourite(View view) {
        BusStop stop = getCurrentBusStop();

        if (stop == null) {
            return;
        }

        if (mBusDatabase.hasFavouriteStop(stop.getAtcoCode())) {
            Toast.makeText(this, "Stop already favourited", Toast.LENGTH_SHORT).show();
        }
        else {
            FavouriteStop favourite = new FavouriteStop();
            favourite.setAtcoCode(stop.getAtcoCode());
            favourite.setBearing(stop.getBearing());
            favourite.setIndicator(stop.getIndicator());
            favourite.setLatitude(stop.getLatitude());
            favourite.setLongitude(stop.getLongitude());
            favourite.setMode(stop.getMode());
            favourite.setLocality(stop.getLocality());
            favourite.setName(stop.getName());

            mBusDatabase.addFavouriteStop(favourite);
            mFavouritesAdapter.add(favourite);

            Toast.makeText(this, "Added favourite stop", Toast.LENGTH_SHORT).show();
            setAddFavouriteButtonBright();
        }
    }

    private void removeFavouriteStop(final FavouriteStop favourite) {
        // check user definietly wants to remove it...
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Favourites")
                .setMessage("Remove favourite stop?")
                .setNegativeButton("No", null);

        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Remove from database and adapter.
                mBusDatabase.removeFavouriteStop(favourite);
                mFavouritesAdapter.remove(favourite);

                Toast.makeText(MainActivity.this, "Favourite stop removed", Toast.LENGTH_SHORT).show();
                setAddFavouriteButtonDark();
            }
        });

        builder.show();
    }

    private void setAddFavouriteButtonBright() {
        mButtonFavourite.setImageResource(R.drawable.ic_star_gold);
    }

    private void setAddFavouriteButtonDark() {
        mButtonFavourite.setImageResource(R.drawable.ic_star);
    }

    private void loadFavouriteStop(FavouriteStop favourite) {
        // check if stop already in nearest stops list.

        boolean refreshNeeded = true;

        // check if stop is in current nearest stops list, if so reselect it.
        if (mNearestBusStops != null) {
            int position = mNearestBusStops.getStopPosition(favourite.getAtcoCode());
            if (position > -1) {
                mCurrentPosition = position;
                updateBusStop();
                refreshNeeded = false;
            }
        }

        if (refreshNeeded) {
            mFavouriteStop = favourite;
            updateBusStop();
        }
    }

    private void updateBusStop() {
        String atcoCode = null;
        long busStopId = 0;
        long favouriteStopId = 0;

        if (mFavouriteStop == null) {
            Log.d(LOG_TAG, "loading normal stop");

            BusStop busStop = getCurrentBusStop();
            atcoCode = busStop.getAtcoCode();
            busStopId = busStop.getId();

            // Update current bus stop info first so user sees at least some activity on the screen.
            mTextName.setText(busStop.getName());
            mTextDistance.setText(getString(R.string.bus_stop_distance, busStop.getDistance()));
            mTextBearing.setText(TextHelper.getBearing(busStop.getBearing()));
            mTextLocality.setText(busStop.getLocality());
        }
        else {
            Log.d(LOG_TAG, "loading favourite stop");

            // we calculate the distance ourselves.
            int distance = (int) getDistanceSinceLastUpdate(mFavouriteStop.getLongitude(), mFavouriteStop.getLatitude());
            atcoCode = mFavouriteStop.getAtcoCode();
            favouriteStopId = mFavouriteStop.getId();

            // Update current bus stop info first so user sees at least some activity on the screen.
            mTextName.setText(mFavouriteStop.getName());
            mTextDistance.setText(getString(R.string.bus_stop_distance, distance));
            mTextBearing.setText(TextHelper.getBearing(mFavouriteStop.getBearing()));
            mTextLocality.setText(mFavouriteStop.getLocality());
        }

        // Get live buses from database, if nothing in DB then load from transport API.
        mLiveBuses = mBusDatabase.getLiveBuses(busStopId, favouriteStopId);
        if (mLiveBuses == null) {
            Log.d(LOG_TAG, "no live buses, starting async task");
            // Download live bus data on background thread so as not to hang the main UI while the
            // potentially long network operation completes.
            new DownloadLiveBusesAsyncTask(atcoCode, busStopId, favouriteStopId).execute();
        }
        else {
            // Check we have a bus stop already in our live buses.
            Bus bus = mLiveBuses.getBus(0);
            if (bus != null) {
                if (isBusDepartureDue(bus.getDepartureTime())) {
                    Log.d(LOG_TAG, "live buses from the db is out of date (" + bus.getBestDepartureEstimate() + ") - getting fresh info.");
                    new DownloadLiveBusesAsyncTask(atcoCode, busStopId, favouriteStopId).execute();
                }
                else {
                    // We're good to go, lets use what we got from the DB.
                    updateBuses();
                }
            }
        }

        // set update location button to be invisible
        mButtonLocation.setVisibility(View.INVISIBLE);
    }

    private void updateBuses() {
        // Check there is anything to show.
        if (mLiveBuses == null) {
            return;
        }

        // If adapter does not exist then create it, otherwise update it with new list.
        if (mBusAdapter == null) {
            mBusAdapter = new BusAdapter(this, mLiveBuses.getBuses());
            mListBuses.setAdapter(mBusAdapter);
        }
        else {
            mBusAdapter.updateBuses(mLiveBuses.getBuses());
        }

        // Show/hide nearer button.
        if (mCurrentPosition == 0 || mFavouriteStop != null) {
            mButtonNearer.setVisibility(View.INVISIBLE);
        }
        else {
            mButtonNearer.setVisibility(View.VISIBLE);
        }

        // Show/hide further button.
        if ((mCurrentPosition == mNearestBusStops.getStopCount() - 1) || mFavouriteStop != null) {
            mButtonFurther.setVisibility(View.INVISIBLE);
        }
        else {
            mButtonFurther.setVisibility(View.VISIBLE);
        }
    }

    public void onClickShowMap(View view) {
        // Launch map activity for currently displayed bus stop.
        if (!mLayoutDrawer.isDrawerOpen(mRelativeDrawer) &&  mNearestBusStops != null) {
            BusStop busStop = getCurrentBusStop();
            if (busStop != null) {
                Intent intent = MapActivity.newIntent(this, busStop.getId());
                startActivity(intent);
            }
        }
    }

    private void showProgressDialog(int resid) {
        // Show progess dialog if it doesn't exist, if it does then change the message.
        if (mProgressDialog == null) {
            mProgressDialog = ProgressDialog.show(this, getString(R.string.progress_title), getString(resid), true);
        }
        else if (mProgressDialog.isShowing()){
            mProgressDialog.setMessage(getString(resid));
        }
    }

    private void dismissProgressDialog() {
        // If dialog is showing then dismiss it.
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();
        }
        mProgressDialog = null;
    }

    private class DownloadNearestStopsAsyncTask extends AsyncTask<Double, Void, NearestBusStops> {
        private String mAtcoCode;
        private double mLongitude;
        private double mLatitude;

        @Override
        public void onPreExecute() {
            // Show loading dialog. this isn't hidden until the end of DownloadLiveBusesAsyncTask.
            showProgressDialog(R.string.progress_finding_nearest_stop);

            // get currently viewed stops ATCO code so it can be reselected.
            BusStop stop = getCurrentBusStop();
            if (stop != null) {
                mAtcoCode = stop.getAtcoCode();
            }

            mIsChangingLocation = true;
        }

        @Override
        protected NearestBusStops doInBackground(Double[] params) {
           try {
               mLongitude = params[0];
               mLatitude = params[1];

                // Get nearest stops from Transport API.
                Log.d(LOG_TAG, "fetching nearest bus stops");
                return mTransportClient.getNearestBusStops(mLongitude, mLatitude);
            }
            catch (IOException e) {
                Log.d(LOG_TAG, "Nearest Bus Stops Exception: " + e.toString());
                return null;
            }
        }

        @Override
        public void onPostExecute(final NearestBusStops result) {
            if (result == null) {
                return;
            }

            try {
                // Delete current stop and its buses.
                if (mNearestBusStops != null) {
                    Log.d(LOG_TAG, "deleting nearest stops and live buses from cache");
                    mBusDatabase.deleteNearestStops(mNearestBusStops);
                }

                // Save nearest stops in database.
                Log.d(LOG_TAG, "adding nearest stops to cache.");
                mBusDatabase.addNearestBusStops(result);
                mNearestBusStops = result;

                // Reset current position. If we have an existing stop then reselect it, otherwise
                // just select the first stop.
                if (mAtcoCode == null) {
                    Log.d(LOG_TAG, "no ATCO code, current position 0");
                    mCurrentPosition = 0;
                }
                else {
                    Log.d(LOG_TAG, "existing ATCO code: " + mAtcoCode);
                    int position = result.getStopPosition(mAtcoCode);
                    Log.d(LOG_TAG, "current position: " + mCurrentPosition);
                    if (position > -1) {
                        mCurrentPosition = position;
                    }
                    else {
                        mCurrentPosition = 0;
                    }
                }

                // do this before updateBusStop.
                mLastLongitude = mLongitude;
                mLastLatitude = mLatitude;
                mButtonLocation.setVisibility(View.INVISIBLE);

                // Get nearest bus stop if there are any stops returned.
                if (mNearestBusStops.getStopCount() == 0) {
                    // If there are no stops no other action will be performed, so hide the process
                    // dialog here.
                    dismissProgressDialog();
                }
                else {
                    // Update the activity for this bus stop.
                    Log.d(LOG_TAG, "updating bus stop info");
                    updateBusStop();
                }
            }
            finally {
                // No longer updating.
                mIsChangingLocation = false;
            }
        }
    }

    private class DownloadLiveBusesAsyncTask extends AsyncTask<BusStop, Void, LiveBuses> {
        private final String mAtcoCode;
        private final long mBusStopId;
        private final long mFavouriteStopId;

        public DownloadLiveBusesAsyncTask(String atcoCode, long busStopId, long favouriteStopId) {
            mAtcoCode = atcoCode;
            mBusStopId = busStopId;
            mFavouriteStopId = favouriteStopId;
        }

        @Override
        public void onPreExecute() {
            // Let app know we are updating the live buses, so no one else tries to.
            mIsUpdatingLiveBuses = true;
            showProgressDialog(R.string.progress_finding_live_buses);
        }

        @Override
        protected LiveBuses doInBackground(BusStop... params) {
            // Get live buses from Transport API.
            Log.d(LOG_TAG, "fetching live buses");
            try {
                return mTransportClient.getLiveBuses(mAtcoCode);
            }
            catch (IOException e) {
                Log.d(LOG_TAG, "Live Buses Exception: " + e.toString());
                Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return null;
            }
        }

        public void onPostExecute(final LiveBuses result) {
            try {
                if (result == null) {
                    return;
                }

                // TODO: bus composite key: operator + line + time e.g. FGL614:24

                // Remove current buses for this stop.
                Log.d(LOG_TAG, "removing old live buses from database");
                mBusDatabase.removeLiveBuses(mBusStopId, mFavouriteStopId);

                // Add newly downloaded buses to database
                Log.d(LOG_TAG, "caching live buses in database");
                mBusDatabase.addLiveBuses(result, mBusStopId, mFavouriteStopId);
                mLiveBuses = result; // need this later.

                updateBuses(); // Update buses UI for the activity.
            }
            finally {
                // Dismiss progress dialog and set flag as not updating.
                mIsUpdatingLiveBuses = false;
                dismissProgressDialog();
            }
        }
    }

    // Bus adapter for converting a bus object into a view for the ListView.
    private class BusAdapter extends ArrayAdapter<Bus> {
        public BusAdapter(Context context, List<Bus> buses) {
            super(context, -1);

            addAll(buses);
        }

        public void updateBuses(List<Bus> buses) {
            clear();
            addAll(buses);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // Get the bus we're showing the view for.
            Bus bus = getItem(position);

            // If a view already exists then reuse it.
            if (convertView == null) {
                LayoutInflater inflater = getLayoutInflater();
                convertView = inflater.inflate(R.layout.list_item_bus, parent, false);
            }

            // Get widgets
            TextView textLine = (TextView) convertView.findViewById(R.id.textLine);
            TextView textDestination = (TextView) convertView.findViewById(R.id.textDestination);
            TextView textTime = (TextView) convertView.findViewById(R.id.textTime);
            TextView textDirection = (TextView) convertView.findViewById(R.id.textDirection);
            TextView textOperator = (TextView) convertView.findViewById(R.id.textOperator);

            // Set widgets
            textLine.setText(bus.getLine().trim());
            textDestination.setText(TextHelper.getDestination(bus.getDestination()));
            textTime.setText(bus.getBestDepartureEstimate());
            textDirection.setText(TextHelper.getDirection(bus.getDirection()));
            textOperator.setText(TextHelper.getOperator(bus.getOperator()));

            return convertView;
        }
    }

    private class NearestStopsAdapter extends ArrayAdapter<BusStop> {
        public NearestStopsAdapter(Context context, List<BusStop> stops) {
            super(context, -1);

            if (stops != null) {
                addAll(stops);
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            BusStop stop = getItem(position);

            // If a view already exists then reuse it.
            if (convertView == null) {
                LayoutInflater inflater = getLayoutInflater();
                convertView = inflater.inflate(R.layout.list_item_nearest_stop, parent, false);
            }

            TextView textName = (TextView)convertView.findViewById(R.id.textName);
            TextView textDistance = (TextView)convertView.findViewById(R.id.textDistance);
            ImageView imageLocation = (ImageView)convertView.findViewById(R.id.imageLocation);
            ImageView imageFavourite = (ImageView)convertView.findViewById(R.id.imageFavourite);

            textName.setText(stop.getName());
            textDistance.setText(getString(R.string.text_nearest_distance, stop.getDistance()));

            // set location icon visible if this is the current bus stop.{
            imageLocation.setVisibility(position == mCurrentPosition ? View.VISIBLE : View.INVISIBLE);

            // check if this is in our favourites list, if so set favourite icon to visible.
            boolean found = isFavouriteStop(stop.getAtcoCode());
            imageFavourite.setVisibility(found ? View.VISIBLE : View.INVISIBLE);

            return convertView;
        }
    }

    private class FavouritesAdapter extends ArrayAdapter<FavouriteStop> {
        public FavouritesAdapter(Context context, List<FavouriteStop> stops) {
            super(context, -1);

            if (stops != null) {
                addAll(stops);
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FavouriteStop stop = getItem(position);

            // If a view already exists then reuse it.
            if (convertView == null) {
                LayoutInflater inflater = getLayoutInflater();
                convertView = inflater.inflate(R.layout.list_item_favourite, parent, false);

                // Only do this once when the view is inflated.
                ImageButton buttonDelete = (ImageButton)convertView.findViewById(R.id.buttonDelete);
                buttonDelete.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int position = (int)v.getTag(); // Get position from tag.
                        FavouriteStop stop = getItem(position);

                        Log.d(LOG_TAG, "removing favourite stop: " + stop.getName());

                        removeFavouriteStop(stop);
                    }
                });
            }

            TextView textName = (TextView)convertView.findViewById(R.id.textName);
            textName.setText(stop.getName());

            // We use tag to store the position so we can retrieve it later.
            ImageButton buttonDelete = (ImageButton)convertView.findViewById(R.id.buttonDelete);
            buttonDelete.setTag(position);

            return convertView;
        }
    }
}
