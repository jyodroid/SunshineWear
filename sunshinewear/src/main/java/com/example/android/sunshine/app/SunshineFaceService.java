package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.jyo.android.sunshinewear.R;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final String LOG_TAG = SunshineFaceService.class.getSimpleName();

    //Message keys
    private static final String KEY_HIGH_TEMPERATURE = "HIGH_TEMPERATURE";
    private static final String KEY_LOW_TEMPERATURE = "LOW_TEMPERATURE";
    private static final String KEY_WEATHER_CONDITION = "WEATHER_CONDITION";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private static final String COLON_STRING = ":";
    private static final int MAIN_ALPHA = 97;
    private static final int DATE_ALPHA = 180;

    public static String highTemp;
    public static String lowTemp;
    public static Bitmap weatherCondition;
    public static final String WEATHER_PATH = "/weather";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            CapabilityApi.CapabilityListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHourPaint;
        Paint mMinutesPaint;
        Paint mLinePaint;
        Paint mDatePaint;
        Paint mHighPaint;
        Paint mLowPaint;
        Paint mWeatherConditionPaint;
        boolean mAmbient;
        boolean mIsRound;
        Calendar mCalendar;
        boolean mShouldDrawColons;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        int mTapCount;

        float mYOffset;

        GoogleApiClient mGoogleApiClient;

        final ResultCallback<DataItemBuffer> onConnectedResultCallback =
                new ResultCallback<DataItemBuffer>() {
                    @Override
                    public void onResult(DataItemBuffer dataItems) {
                        for (DataItem dataItem : dataItems) {
                            // DataItem changed
                            if (WEATHER_PATH.equals(dataItem.getUri().getPath())) {
                                DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                                updateTemperature(dataMap);
                            }
                        }
                    }
                };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineFaceService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();

            long now = System.currentTimeMillis();
            mCalendar = Calendar.getInstance();
            mCalendar.setTimeInMillis(now);

            //Set background according the time
            if (mCalendar.get(Calendar.AM_PM) == Calendar.AM) {
                mBackgroundPaint.setColor(
                        ContextCompat.getColor(SunshineFaceService.this,
                                R.color.background));
            } else {
                mBackgroundPaint.setColor(
                        ContextCompat.getColor(SunshineFaceService.this,
                                R.color.background_dark));
            }

            mHourPaint = new Paint();
            mHourPaint = createTextPaint(ContextCompat.getColor(
                    SunshineFaceService.this, R.color.digital_text),
                    NORMAL_TYPEFACE);

            mMinutesPaint = new Paint();
            Typeface lightTypeface = Typeface.create("sans-serif-light", Typeface.NORMAL);
            mMinutesPaint = createTextPaint(ContextCompat.getColor(
                    SunshineFaceService.this, R.color.digital_text),
                    lightTypeface);

            mLinePaint = new Paint();
            mLinePaint.setColor(
                    ContextCompat.getColor(
                            SunshineFaceService.this,
                            R.color.disabled_text_light));
            mLinePaint.setAlpha(MAIN_ALPHA);
            mLinePaint.setAntiAlias(true);

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(ContextCompat.getColor(
                    SunshineFaceService.this, R.color.digital_text),
                    lightTypeface);
            mDatePaint.setAlpha(DATE_ALPHA);
            mHighPaint = new Paint();
            mHighPaint = createTextPaint(ContextCompat.getColor(
                    SunshineFaceService.this, R.color.digital_text),
                    lightTypeface);
            mLowPaint = new Paint();
            mLowPaint = createTextPaint(ContextCompat.getColor(
                    SunshineFaceService.this, R.color.digital_text),
                    lightTypeface);
            mLowPaint.setAlpha(DATE_ALPHA);
            mWeatherConditionPaint = new Paint();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            releaseGoogleApiClient();
            super.onDestroy();
        }


        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
                mGoogleApiClient.connect();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    releaseGoogleApiClient();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineFaceService.this.getResources();
            mIsRound = insets.isRound();
            float textSize = resources.getDimension(mIsRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mHourPaint.setTextSize(textSize);
            mMinutesPaint.setTextSize(textSize);

            float dateTextSize = resources.getDimension(mIsRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);
            mDatePaint.setTextSize(dateTextSize);

            float tempTextSize = resources.getDimension(mIsRound
                    ? R.dimen.digital_temp_text_size_round : R.dimen.digital_temp_text_size);

            mHighPaint.setTextSize(tempTextSize);
            mLowPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinutesPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mHighPaint.setAntiAlias(!inAmbientMode);
                    mLowPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    long now = System.currentTimeMillis();
                    mCalendar.setTimeInMillis(now);

                    //Change background color
                    if (mCalendar.get(Calendar.AM_PM) == Calendar.AM) {

                        mBackgroundPaint.setColor(ContextCompat.getColor(SunshineFaceService.this,
                                mTapCount % 2 == 0 ?
                                        R.color.background : R.color.background_dark));
                    } else {
                        mBackgroundPaint.setColor(ContextCompat.getColor(SunshineFaceService.this,
                                mTapCount % 2 == 0 ?
                                        R.color.background_dark : R.color.background));
                    }

                    break;
            }
            invalidate();
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (mAmbient) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            boolean is24Hour = DateFormat.is24HourFormat(SunshineFaceService.this);

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            int hours;
            if (is24Hour) {
                hours = mCalendar.get(Calendar.HOUR_OF_DAY);
            } else {
                hours = mCalendar.get(Calendar.HOUR);
                if (hours == 0) {
                    hours = 12;
                }
            }
            int minutes = mCalendar.get(Calendar.MINUTE);

            String hourString = String.format("%d", hours);
            String minuteString = String.format("%02d", minutes);

            float xOffset =
                    bounds.exactCenterX() -
                            mHourPaint.measureText(hourString + COLON_STRING + minuteString) / 2;

            canvas.drawText(hourString, xOffset, mYOffset, mHourPaint);
            float xColonsOfset = xOffset + mHourPaint.measureText(hourString);
            canvas.drawText(
                    minuteString,
                    xColonsOfset + mHourPaint.measureText(COLON_STRING),
                    mYOffset,
                    mMinutesPaint);
            if (!mAmbient) {
                if (mShouldDrawColons) {
                    canvas.drawText(COLON_STRING, xColonsOfset, mYOffset, mHourPaint);
                }
            } else {
                canvas.drawText(COLON_STRING, xColonsOfset, mYOffset, mHourPaint);
            }

            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");
            String date = dateFormat.format(mCalendar.getTime());
            float xDateOffSet = bounds.exactCenterX() - mDatePaint.measureText(date) / 2;
            float yDateOffSet = mYOffset + mHourPaint.getTextSize() - mDatePaint.getTextSize();
            canvas.drawText(date, xDateOffSet, yDateOffSet, mDatePaint);

            canvas.drawLine(
                    xOffset,
                    yDateOffSet + mDatePaint.getTextSize(),
                    xOffset + mHourPaint.measureText(hourString + COLON_STRING + minuteString),
                    yDateOffSet + mDatePaint.getTextSize(),
                    mLinePaint);

            float xHighOffSet = bounds.exactCenterX();
            float yHighOffSet;
            if (mIsRound){
                yHighOffSet = bounds.bottom - mYOffset + mHighPaint.getTextSize();
            }else {
                yHighOffSet = bounds.bottom - mYOffset + mHighPaint.getTextSize()*2;
            }
            if (highTemp != null) {
                xHighOffSet -= mHighPaint.measureText(highTemp + " " + lowTemp) / 2;
                canvas.drawText(String.format("%s", highTemp), xHighOffSet, yHighOffSet, mHighPaint);
            }

            if (lowTemp != null) {
                float xLowOffSet = xHighOffSet + mHighPaint.measureText(highTemp + " ");
                canvas.drawText(String.format("%s", lowTemp), xLowOffSet, yHighOffSet, mLowPaint);
            }

            if (weatherCondition != null && !mAmbient) {

                float yOffSet;
                if (mIsRound){
                    yOffSet = yHighOffSet;
                }else {
                    yOffSet = yHighOffSet - mHighPaint.getTextSize();
                }

                canvas.drawBitmap(
                        weatherCondition,
                        bounds.exactCenterX() - weatherCondition.getWidth()/2,
                        yOffSet,
                        mWeatherConditionPaint);
            }

            if (!mAmbient) {
                invalidate();
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        //Message Override methods
        @Override
        public void onConnected(@Nullable Bundle connectionHint) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Wearable.CapabilityApi.addListener(
                    mGoogleApiClient, this, Uri.parse("wear://"), CapabilityApi.FILTER_REACHABLE);
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(onConnectedResultCallback);
        }

        @Override
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (WEATHER_PATH.equals(item.getUri().getPath())) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        updateTemperature(dataMap);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                    Log.v(LOG_TAG, "Data item deleted");
                }
            }

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onConnectionFailed: " + connectionResult);
            }
        }

        @Override
        public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "compatibilityChanged: " + capabilityInfo);
            }
        }

        private void updateTemperature(DataMap tempData) {
            for (String temp : tempData.keySet()) {
                if (!tempData.containsKey(temp)) {
                    continue;
                }
                if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                    Log.d(LOG_TAG, "Found watch face config key: " + temp + " -> "
                            + highTemp);
                }

                if (temp.equals(KEY_HIGH_TEMPERATURE)) {
                    highTemp = tempData.getString(temp);
                }

                if (temp.equals(KEY_LOW_TEMPERATURE)) {
                    lowTemp = tempData.getString(temp);
                }

                if (temp.equals(KEY_WEATHER_CONDITION)) {
                    Asset weatherConditionAsset = tempData.getAsset(temp);
                    new LoadBitmapAsyncTask().execute(weatherConditionAsset);
                }
                invalidate();
            }
        }

        private void releaseGoogleApiClient() {
            if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }
        }

        /*
        * Extracts {@link android.graphics.Bitmap} data from the
        * {@link com.google.android.gms.wearable.Asset}
        */
        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... params) {

                if (params.length > 0) {

                    Asset asset = params[0];

                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();

                    if (assetInputStream == null) {
                        Log.w(LOG_TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);

                } else {
                    Log.e(LOG_TAG, "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {

                if (bitmap != null) {
                    weatherCondition = bitmap;
                }
            }
        }
    }
}
