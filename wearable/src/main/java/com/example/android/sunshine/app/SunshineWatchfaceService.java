/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
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

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchfaceService extends CanvasWatchFaceService {
    private static final Typeface LIGHT_TYPEFACE =
            Typeface.create("sans-serif-light", Typeface.NORMAL);

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = 500;

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchfaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchfaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchfaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        GoogleApiClient mGoogleApiClient;

        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;
        boolean mShouldDrawColons;

        Paint mBackgroundPaint;
        Paint mNormalPaint;
        Paint mLightPaint;

        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mTimeXOffset;
        float mTimeYOffset;
        float mDateXOffset;
        float mDateYOffset;
        float mLineYOffset;
        float mWeatherYOffset;
        float mColonWidth;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private static final String LOG_TAG = "SunshineWearableEngine";
        private int mHighTemp = 0;
        private int mLowTemp = 0;
        private int mWeatherId;
        private float mIconLeft;
        private float mIconTop;
        private float mIconWidth;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchfaceService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchfaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchfaceService.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mNormalPaint = new Paint();
            mNormalPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mLightPaint = new Paint();
            mLightPaint = createTextPaint(resources.getColor(R.color.digital_text), LIGHT_TYPEFACE);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
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

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                mRegisteredTimeZoneReceiver = true;
                IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
                SunshineWatchfaceService.this.registerReceiver(mTimeZoneReceiver, filter);
            }
        }

        private void unregisterReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                mRegisteredTimeZoneReceiver = false;
                SunshineWatchfaceService.this.unregisterReceiver(mTimeZoneReceiver);
            }
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchfaceService.this.getResources();
            boolean isRound = insets.isRound();

            //time vars setup

            float timeTextSize = resources.getDimension(R.dimen.time_text_size);

            mNormalPaint.setTextSize(timeTextSize);
            mLightPaint.setTextSize(timeTextSize);

            mColonWidth = mNormalPaint.measureText(":");

            mTimeXOffset = resources.getDimension(isRound ? R.dimen.time_x_offset_round : R.dimen.time_x_offset);
            mTimeYOffset = resources.getDimension(isRound ? R.dimen.time_y_offset_round : R.dimen.time_y_offset);

            mDateXOffset = resources.getDimension(isRound ? R.dimen.date_x_offset_round : R.dimen.date_x_offset);
            mDateYOffset = resources.getDimension(isRound ? R.dimen.date_y_offset_round : R.dimen.date_y_offset);

            mLineYOffset = getResources().getDimension(isRound ? R.dimen.line_top_round : R.dimen.line_top);

            mIconLeft = getResources().getDimension(isRound ? R.dimen.icon_left_round : R.dimen.icon_left);
            mIconTop = getResources().getDimension(isRound ? R.dimen.icon_top_round : R.dimen.icon_top);
            mIconWidth = getResources().getDimension(R.dimen.icon_width);

            mWeatherYOffset = getResources().getDimension(isRound ? R.dimen.weather_y_offset_round : R.dimen.weather_y_offset);
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
                    mNormalPaint.setAntiAlias(!inAmbientMode);
                    mLightPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            mNormalPaint.setTextSize(getResources().getDimension(R.dimen.time_text_size));
            mLightPaint.setColor(getResources().getColor(R.color.white));

            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String hourText = String.format(Locale.getDefault(), "%02d",
                    mCalendar.get(Calendar.HOUR_OF_DAY));
            String minText = String.format(Locale.getDefault(), "%02d",
                    mCalendar.get(Calendar.MINUTE));

            canvas.drawText(hourText, mTimeXOffset, mTimeYOffset, mNormalPaint);
            float x = mTimeXOffset + mNormalPaint.measureText(hourText);
            if (isInAmbientMode() || mShouldDrawColons) {
                canvas.drawText(":", x, mTimeYOffset, mNormalPaint);
            }
            x += mColonWidth;
            mLightPaint.setTextSize(getResources().getDimension(R.dimen.time_text_size));
            canvas.drawText(minText, x, mTimeYOffset, mLightPaint);

            if (!isInAmbientMode()) {
                SimpleDateFormat formatter = new SimpleDateFormat();
                formatter.applyPattern("EEE, MMM dd yyyy");
                String dateText = formatter.format(mCalendar.getTime()).toUpperCase();

                mLightPaint.setTextSize(getResources().getDimension(R.dimen.date_text_size));
                canvas.drawText(dateText, mDateXOffset, mDateYOffset, mLightPaint);

                float lineLength = getResources().getDimension(R.dimen.line_length);
                float lineLeft = (bounds.width() / 2) - (lineLength / 2);
                float lineRight = lineLeft + lineLength;

                canvas.drawRect(lineLeft, mLineYOffset, lineRight, mLineYOffset + 1, mLightPaint);

                if (mWeatherId == 0) {
                    mWeatherId = 800;
                }

                Bitmap defaultBitmap = BitmapFactory.decodeResource(getResources(), getIconResourceForWeatherCondition(mWeatherId));
                Rect src = new Rect(0, 0, defaultBitmap.getWidth(), defaultBitmap.getHeight());
//                Rect dst = new Rect((int) mDateXOffset, 180, 100, 220);
                Rect dst = new Rect((int) mIconLeft, (int) mIconTop, (int) (mIconLeft + mIconWidth), (int) (mIconTop + mIconWidth));
                canvas.drawBitmap(defaultBitmap, src, dst, null);

                mNormalPaint.setTextSize(getResources().getDimension(R.dimen.temp_text_size));
                mLightPaint.setTextSize(getResources().getDimension(R.dimen.temp_text_size));
                mLightPaint.setColor(getResources().getColor(R.color.light_blue));
                x = mIconLeft + 70;
                canvas.drawText(mHighTemp + "°", x, mWeatherYOffset, mNormalPaint);
                x += 55;
                canvas.drawText(mLowTemp + "°", x, mWeatherYOffset, mLightPaint);
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

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(SunshineWatchfaceService.class.getSimpleName(), "onDataChanged called...");
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().equals("/weather")) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        mHighTemp = dataMap.getInt("maxtemp");
                        mLowTemp = dataMap.getInt("mintemp");
                        mWeatherId = dataMap.getInt("weatherId");

                        Log.d("WATCH_DATA", "High: " + mHighTemp + " Low: " + mLowTemp + " ID: " + mWeatherId);
                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "Connected to google apis");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "Suspended connection to google apis");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "Connection failed to google apis");
        }

        private int getIconResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            }
            return -1;
        }
    }
}
