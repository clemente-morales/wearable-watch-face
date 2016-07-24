package com.example.android.sunshine.wear;

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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static android.R.attr.centerX;

/**
 * Created by clerks on 7/21/16.
 */

public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final long NORMAL_UPDATE_RATE_MS = 500;

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);
    private static final String SUNSHINE_WEATHER_PATH = "/sunshine-weather";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        static final String COLON_STRING = ":";

        private static final String HIGHEST_TEMPERATURE_KEY = "highestTemperature";
        private static final String LOWEST_TEMPERATURE_KEY = "lowestTemperature";
        private static final String WEATHER_ICON_KEY = "weatherIcon";

        /**
         * Alpha value for drawing time when in mute mode.
         */
        static final int MUTE_ALPHA = 100;

        /**
         * Alpha value for drawing time when not in mute mode.
         */
        static final int NORMAL_ALPHA = 255;


        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        static final int MSG_UPDATE_TIME = 0;

        private Calendar calendar;
        private Bitmap weatherBitmap;
        private Paint weatherPaint;
        private Paint hourPaint;
        private Paint minutePaint;

        private boolean lowBitAmbient;

        private boolean burnInProtection;

        private boolean isRound;

        private int chinSize;

        private int highestTemperature;

        Date date;
        SimpleDateFormat dayOfWeekFormat;
        java.text.DateFormat dateFormat;

        boolean shouldDrawColons;

        int mInteractiveBackgroundColor = R.color.interactive_background_color;
        int mInteractiveHourDigitsColor =
                SunshineWatchFaceHelper.COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS;
        int mInteractiveMinuteDigitsColor =
                SunshineWatchFaceHelper.COLOR_VALUE_DEFAULT_AND_AMBIENT_MINUTE_DIGITS;

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
        float xOffset;
        float yOffset;

        private float mLineHeight;
        private Paint backgroundPaint;
        private Paint datePaint;
        private Paint colonPaint;
        float colonWidth;
        boolean mute;
        private int lowestTemperature;
        private Paint highestTemperaturePaint;
        private Paint lowestTemperaturePaint;


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

            yOffset = resources.getDimension(R.dimen.digital_y_offset);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            backgroundPaint = new Paint();
            weatherPaint = new Paint();

            backgroundPaint.setColor(resources.getColor(mInteractiveBackgroundColor));
            datePaint = createTextPaint(resources.getColor(R.color.digital_date));

            hourPaint = createTextPaint(mInteractiveHourDigitsColor);
            minutePaint = createTextPaint(mInteractiveMinuteDigitsColor);
            colonPaint = createTextPaint(resources.getColor(R.color.digital_colons));

            highestTemperaturePaint = createTextPaint(Color.WHITE, BOLD_TYPEFACE);
            lowestTemperaturePaint = createTextPaint(Color.WHITE, BOLD_TYPEFACE);

            calendar = Calendar.getInstance();
            date = new Date();
            initFormats();
        }

        @Override
        public void onDestroy() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION,
                    false);
            hourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            lowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            adjustPaintColorToCurrentMode(backgroundPaint, mInteractiveBackgroundColor,
                    SunshineWatchFaceHelper.COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND);

            adjustPaintColorToCurrentMode(hourPaint, mInteractiveHourDigitsColor,
                    SunshineWatchFaceHelper.COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS);
            adjustPaintColorToCurrentMode(minutePaint, mInteractiveMinuteDigitsColor,
                    SunshineWatchFaceHelper.COLOR_VALUE_DEFAULT_AND_AMBIENT_MINUTE_DIGITS);

            if (lowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                datePaint.setAntiAlias(antiAlias);
                hourPaint.setAntiAlias(antiAlias);
                minutePaint.setAntiAlias(antiAlias);
                colonPaint.setAntiAlias(antiAlias);
            }

            invalidate();
            updateTimer();
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);
            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

            if (mute != inMuteMode) {
                mute = inMuteMode;
                int alpha = inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA;
                datePaint.setAlpha(alpha);
                hourPaint.setAlpha(alpha);
                minutePaint.setAlpha(alpha);
                colonPaint.setAlpha(alpha);
                invalidate();
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            calendar.setTimeInMillis(now);
            date.setTime(now);
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            shouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), backgroundPaint);

            // Draw the hours.
            float x = 0.0f;
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(calendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = calendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }

            String minuteString = formatTwoDigitNumber(calendar.get(Calendar.MINUTE));
            String fullTimeText = hourString.concat(COLON_STRING).concat(minuteString);

            float fullTimeTextWidth = hourPaint.measureText(fullTimeText);
            float hourPosition = (bounds.width() - fullTimeTextWidth) / 2;

            canvas.drawText(hourString, hourPosition, yOffset, hourPaint);
            x = hourPosition + hourPaint.measureText(hourString);

            // In ambient and mute modes, always draw the first colon. Otherwise, draw the
            // first colon for the first half of each second.
            if (isInAmbientMode() || mute || shouldDrawColons) {
                canvas.drawText(COLON_STRING, x, yOffset, colonPaint);
            }
            x += colonWidth;
            canvas.drawText(minuteString, x, yOffset, minutePaint);

            String dateText = dayOfWeekFormat.format(date);
            float dateTextWidth = datePaint.measureText(dateText);
            float datePosition = (bounds.width() - dateTextWidth) / 2;
            canvas.drawText(dateText, datePosition, yOffset + mLineHeight, datePaint);

            if (getPeekCardPosition().isEmpty()) {
                // Day of week
                // Date
                if (weatherBitmap != null && !lowBitAmbient)
                    canvas.drawBitmap(weatherBitmap, centerX - weatherBitmap.getWidth() - weatherBitmap.getWidth() / 4,
                            yOffset - weatherBitmap.getHeight() / 2, weatherPaint);


                String highestTemperatureText = Integer.toString(highestTemperature);
                canvas.drawText(highestTemperatureText, centerX, yOffset, highestTemperaturePaint);

                String lowestTemperatureText = Integer.toString(lowestTemperature);
                float highTempSize = highestTemperaturePaint.measureText(highestTemperatureText);
                canvas.drawText(lowestTemperatureText, centerX + highTempSize, yOffset, lowestTemperaturePaint);
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

            Resources resources = SunshineWatchFaceService.this.getResources();
            xOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            datePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            hourPaint.setTextSize(textSize);
            minutePaint.setTextSize(textSize);
            colonPaint.setTextSize(textSize);

            colonWidth = colonPaint.measureText(COLON_STRING);
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
                final int latestHighestTemperature = dataMap.getInt(HIGHEST_TEMPERATURE_KEY);
                final int latestLowestTemperature = dataMap.getInt(LOWEST_TEMPERATURE_KEY);

                if (latestHighestTemperature != highestTemperature
                        || latestLowestTemperature != lowestTemperature) {

                    final Asset iconAsset = dataMap.getAsset(WEATHER_ICON_KEY);

                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            updateData(latestHighestTemperature, latestLowestTemperature, iconAsset);
                        }
                    });
                }

            }
        }

        private void updateData(int latestHighestTemperature, int latestLowestTemperature, Asset iconAsset) {
            Bitmap weatherIcon = assetToBitmap(iconAsset);
            int weatherIconSize = Float.valueOf(getResources().getDimension(R.dimen.weather_icon_size)).intValue();
            weatherBitmap = Bitmap.createScaledBitmap(weatherIcon, weatherIconSize, weatherIconSize, false);
            highestTemperature = latestHighestTemperature;
            lowestTemperature = latestLowestTemperature;
            setInteractiveBackgroundColor(R.color.interactive_background_color);
            setInteractiveHourDigitsColor(R.color.white);
            setInteractiveMinuteDigitsColor(R.color.white);
            postInvalidate();
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

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
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
            dayOfWeekFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            dayOfWeekFormat.setCalendar(calendar);
            dateFormat = DateFormat.getDateFormat(SunshineWatchFaceService.this);
            dateFormat.setCalendar(calendar);
        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        private void setInteractiveBackgroundColor(int color) {
            mInteractiveBackgroundColor = color;
            updatePaintIfInteractive(backgroundPaint, color);
        }

        private void setInteractiveHourDigitsColor(int color) {
            mInteractiveHourDigitsColor = color;
            updatePaintIfInteractive(hourPaint, color);
        }

        private void setInteractiveMinuteDigitsColor(int color) {
            mInteractiveMinuteDigitsColor = color;
            updatePaintIfInteractive(minutePaint, color);
        }

        private void updatePaintIfInteractive(Paint paint, int interactiveColor) {
            if (!isInAmbientMode() && paint != null) {
                paint.setColor(interactiveColor);
            }
        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }

            mInteractiveUpdateRateMs = updateRateMs;

            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        public Bitmap assetToBitmap(Asset asset) {
            if (asset == null)
                return null;

            ConnectionResult result = mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
            if (!result.isSuccess())
                return null;

            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null)
                return null;

            return BitmapFactory.decodeStream(assetInputStream);
        }
    }
}
