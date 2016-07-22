package com.example.android.sunshine.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Created by clerks on 7/21/16.
 */

public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final long NORMAL_UPDATE_RATE_MS = 500;

    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);
    private static final String SUNSHINE_WEATHER_PATH = "/sunshine-weather";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        private static final String TEMPERATURE_KEY = "com.example.android.sunshine.temperature";

        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        static final int MSG_UPDATE_TIME = 0;

        private Calendar calendar;
        private Bitmap weatherBitmap;
        private Paint hourPaint;
        private Paint minutePaint;

        private boolean lowBitAmbient;

        private boolean burnInProtection;

        private boolean isRound;

        private int chinSize;

        private int temperature;

        final Handler updateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            updateTimeHandler
                                    .sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        private GoogleApiClient mGoogleApiClient =
                new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                        .addApi(Wearable.API)
                        .addConnectionCallbacks(this)
                        .addOnConnectionFailedListener(this)
                        .build();

        final BroadcastReceiver timeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                calendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        boolean registeredReceiver = false;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle
                            .BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());


            Resources resources = SunshineWatchFaceService.this.getResources();
            Drawable backgroundDrawable = null; //resources.getDrawable(R.drawable.bg, null);
            weatherBitmap = ((BitmapDrawable) backgroundDrawable).getBitmap();

            // create graphic styles
            hourPaint = new Paint();
            hourPaint.setARGB(255, 200, 200, 200);
            hourPaint.setStrokeWidth(5.0f);
            hourPaint.setAntiAlias(true);
            hourPaint.setStrokeCap(Paint.Cap.ROUND);


            calendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            lowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION,
                    false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            if (lowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                hourPaint.setAntiAlias(antiAlias);
                minutePaint.setAntiAlias(antiAlias);
            }
            invalidate();
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            super.onDraw(canvas, bounds);

            int canvasHeight = canvas.getHeight();
            int canvasWidth = canvas.getWidth();

            if (!isInAmbientMode()) {

            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();
                calendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            updateTimer();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            isRound = insets.isRound();
            chinSize = insets.getSystemWindowInsetBottom();
        }

        private void updateTimer() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                updateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent event : dataEvents) {
                if (event.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem item = event.getDataItem();

                if (!item.getUri().getPath().equals(
                        SUNSHINE_WEATHER_PATH)) {
                    continue;
                }

                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                int currentTemperature = dataMap.getInt(TEMPERATURE_KEY);

                if (currentTemperature != temperature) {
                    temperature = currentTemperature;
                    invalidate();
                }

            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        private void registerReceiver() {
            if (registeredReceiver) {
                return;
            }
            registeredReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(timeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!registeredReceiver) {
                return;
            }
            registeredReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(timeZoneReceiver);
        }

        private void initFormats() {

        }
    }
}
