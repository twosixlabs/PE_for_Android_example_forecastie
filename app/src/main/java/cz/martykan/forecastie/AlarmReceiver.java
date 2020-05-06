/*
 * This work was modified by Two Six Labs, LLC and is sponsored by a
 * subcontract agreement with Raytheon BBN Technologies Corp. under Prime
 * Contract No. FA8750-16-C-0006 with the Air Force Research Laboratory (AFRL).

 * The Government has unlimited rights to use, modify, reproduce, release,
 * perform, display, or disclose computer software or computer software
 * documentation marked with this legend. Any reproduction of technical data,
 * computer software, or portions thereof marked with this legend must also
 * reproduce this marking.
 *
 * (C) 2020 Two Six Labs, LLC.  All rights reserved.
 *
 */

package cz.martykan.forecastie;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.privatedata.DataRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.privatedata.PrivateDataManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

import cz.martykan.forecastie.activities.MainActivity;
import cz.martykan.forecastie.widgets.AbstractWidgetProvider;
import cz.martykan.forecastie.widgets.DashClockWeatherExtension;

public class AlarmReceiver extends BroadcastReceiver {

    Context context;
    PrivateDataManager pdm;

    private ResultReceiver microPalReceiver = new ResultReceiver(new Handler(Looper.getMainLooper())){
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            super.onReceiveResult(resultCode, resultData);
            if (resultData != null) {
                new GetCityNameTask().execute(String.valueOf(resultData.getDouble("latitude")), String.valueOf(resultData.getDouble("longitude")));
            } else {
                Toast.makeText(context, "Couldn't determine user's zip code", Toast.LENGTH_LONG).show();
                new GetWeatherTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                new GetLongTermWeatherTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        }
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;
        pdm = PrivateDataManager.getInstance();
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            String interval = sp.getString("refreshInterval", "1");
            if (!interval.equals("0")) {
                setRecurringAlarm(context);
                getWeather();
            }
        } else if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
            // Get weather if last attempt failed or if 'update location in background' is activated
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            String interval = sp.getString("refreshInterval", "1");
            if (!interval.equals("0") &&
                    (sp.getBoolean("backgroundRefreshFailed", false) || isUpdateLocation())) {
                getWeather();
            }
        } else {
            getWeather();
        }
    }

    private void getWeather() {
        Log.d("Alarm", "Recurring alarm; requesting download service.");
        boolean failed;
        if (isNetworkAvailable()) {
            failed = false;
            if (isUpdateLocation()) {
                Bundle locationParams = new DataRequest.LocationParamsBuilder()
                        .setTimeoutMillis(1000)
                        .setUpdateMode(DataRequest.LocationParamsBuilder.MODE_LAST_LOCATION)
                        .build();


                Bundle palParams = new Bundle();
                DataRequest.Purpose purpose = DataRequest.Purpose.UTILITY("Get location of zip code");
                DataRequest request = new DataRequest(context,
                        DataRequest.DataType.LOCATION, locationParams,
                        context.getString(R.string.zipcode_micropal), null,
                        purpose, microPalReceiver);

                pdm.requestData(request);
            } else {
                new GetWeatherTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                new GetLongTermWeatherTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        } else {
            failed = true;
        }
        SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(context).edit();
        editor.putBoolean("backgroundRefreshFailed", failed);
        editor.apply();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private boolean isUpdateLocation() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getBoolean("updateLocationAutomatically", false);
    }

    private String getLanguage() {
        String language = Locale.getDefault().getLanguage();
        if (language.equals("cs")) {
            language = "cz";
        }else if(language.equals("ko")){
            language = "kr";
        }
        return language;
    }

    public class GetWeatherTask extends AsyncTask<String, String, Void> {

        protected void onPreExecute() {

        }

        @Override
        protected Void doInBackground(String... params) {
            try {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                String language = getLanguage();
                String apiKey = sp.getString("apiKey", context.getResources().getString(R.string.apiKey));
                URL url = new URL("https://api.openweathermap.org/data/2.5/weather?id=" + URLEncoder.encode(sp.getString("cityId", Constants.DEFAULT_CITY_ID), "UTF-8") + "&lang="+ language +"&appid=" + apiKey);
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                BufferedReader connectionBufferedReader = null;
                try {
                    connectionBufferedReader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                    if (urlConnection.getResponseCode() == 200) {
                        StringBuilder result = new StringBuilder();
                        String line;
                        while ((line = connectionBufferedReader.readLine()) != null) {
                            result.append(line).append("\n");
                        }
                        SharedPreferences.Editor editor = sp.edit();
                        editor.putString("lastToday", result.toString());
                        editor.apply();
                        MainActivity.saveLastUpdateTime(sp);
                    } else {
                        // Connection problem
                    }
                } finally {
                    if (connectionBufferedReader != null) connectionBufferedReader.close();
                }
            } catch (IOException e) {
                // No connection
            }
            return null;
        }

        protected void onPostExecute(Void v) {
            // Update widgets
            AbstractWidgetProvider.updateWidgets(context);
            DashClockWeatherExtension.updateDashClock(context);
        }
    }

    class GetLongTermWeatherTask extends AsyncTask<String, String, Void> {

        protected void onPreExecute() {

        }

        @Override
        protected Void doInBackground(String... params) {
            try {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                String language = getLanguage();
                String apiKey = sp.getString("apiKey", context.getResources().getString(R.string.apiKey));
                URL url = new URL("https://api.openweathermap.org/data/2.5/forecast?id=" + URLEncoder.encode(sp.getString("cityId", Constants.DEFAULT_CITY_ID), "UTF-8") + "&lang="+ language +"&mode=json&appid=" + apiKey);
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                BufferedReader connectionBufferedReader = null;
                try {
                    connectionBufferedReader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                    if (urlConnection.getResponseCode() == 200) {
                        StringBuilder result = new StringBuilder();
                        String line;
                        while ((line = connectionBufferedReader.readLine()) != null) {
                            result.append(line).append("\n");
                        }
                        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
                        editor.putString("lastLongterm", result.toString());
                        editor.apply();
                    } else {
                        // Connection problem
                    }
                } finally {
                    if (connectionBufferedReader != null) connectionBufferedReader.close();
                }
            } catch (IOException e) {
                // No connection
            }
            return null;
        }

        protected void onPostExecute(Void v) {

        }
    }


    public class GetCityNameTask extends AsyncTask <String, String, Void> {
        private static final String TAG = "GetCityNameTask";

        @Override
        protected Void doInBackground(String... params) {
            String lat = params[0];
            String lon = params[1];

            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            String language = getLanguage();
            String apiKey = sp.getString("apiKey", context.getResources().getString(R.string.apiKey));

            try {
                URL url = new URL("https://api.openweathermap.org/data/2.5/weather?q=&lat=" + lat + "&lon=" + lon + "&lang="+ language +"&appid=" + apiKey);
                Log.d(TAG, "Request: " + url.toString());

                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

                if (urlConnection.getResponseCode() == 200) {
                    BufferedReader connectionBufferedReader = null;
                    try {
                        connectionBufferedReader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                        StringBuilder result = new StringBuilder();
                        String line;
                        while ((line = connectionBufferedReader.readLine()) != null) {
                            result.append(line).append("\n");
                        }
                        Log.d(TAG, "JSON Result: " + result);
                        JSONObject reader = new JSONObject(result.toString());
                        String cityId = reader.getString("id");
                        String city = reader.getString("name");
                        String country = "";
                        JSONObject countryObj = reader.optJSONObject("sys");
                        if (countryObj != null) {
                            country = ", " + countryObj.getString("country");
                        }
                        Log.d(TAG, "City: " + city + country);
                        String lastCity = PreferenceManager.getDefaultSharedPreferences(context).getString("city", "");
                        String currentCity = city + country;
                        SharedPreferences.Editor editor = sp.edit();
                        editor.putString("cityId", cityId);
                        editor.putString("city", currentCity);
                        editor.putBoolean("cityChanged", !currentCity.equals(lastCity));
                        editor.commit();

                    } catch (JSONException e){
                        Log.e(TAG, "An error occurred while reading the JSON object", e);
                    } finally {
                        if (connectionBufferedReader != null) connectionBufferedReader.close();
                    }
                } else {
                    Log.e(TAG, "Error: Response code " + urlConnection.getResponseCode());
                }
            } catch (IOException e) {
                Log.e(TAG, "Connection error", e);
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            new GetWeatherTask().execute();
            new GetLongTermWeatherTask().execute();
        }
    }

    private static long intervalMillisForRecurringAlarm(String intervalPref) {
        int interval = Integer.parseInt(intervalPref);
        switch (interval) {
            case 0:
                return 0; // special case for cancel
            case 15:
                return AlarmManager.INTERVAL_FIFTEEN_MINUTES;
            case 30:
                return AlarmManager.INTERVAL_HALF_HOUR;
            case 1:
                return AlarmManager.INTERVAL_HOUR;
            case 12:
                return AlarmManager.INTERVAL_HALF_DAY;
            case 24:
                return AlarmManager.INTERVAL_DAY;
            default: // cases 2 and 6 (or any number of hours)
                return interval * 3600000;
        }
    }

    public static void setRecurringAlarm(Context context) {
        String intervalPref = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("refreshInterval", "1");
        Intent refresh = new Intent(context, AlarmReceiver.class);
        PendingIntent recurringRefresh = PendingIntent.getBroadcast(context,
                0, refresh, PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager alarms = (AlarmManager) context.getSystemService(
                Context.ALARM_SERVICE);
        long intervalMillis = intervalMillisForRecurringAlarm(intervalPref);
        if (intervalMillis == 0) {
            // Cancel previous alarm
            alarms.cancel(recurringRefresh);
        } else {
            alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + intervalMillis,
                    intervalMillis,
                    recurringRefresh);
        }
    }
}
